// SPDX-License-Identifier: Apache-2.0
//
// AgentLite 同步服务 — 额度网关（OpenAI 兼容）
//
// 定位：用户在应用内用兑换码充值额度，模型请求经本网关转发到上游（DashScope），
// 按实际用量扣减账号额度。客户端因此**不需要持有自己的 DashScope Key**——
// 只要把 base_url 指向本服务、api_key 填账号令牌即可。
//
// 为什么必须在服务端计量：额度是计费凭证，客户端自报用量不可信。因此网关是
// 唯一能拿到权威 usage 的地方，也是唯一能做扣减的地方。
//
// 已知边界（勿粉饰）：
//   - 额度是「先检查后扣减」，并发请求可能小幅超支（本服务面向单用户级规模，可接受）
//   - 流式响应的用量取自上游最后一个带 usage 的 chunk；若上游没给，则按最小单位计费
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// 网关上游配置（通过 -upstream-* 标志或环境变量注入；密钥放 0600 的 env 文件）
type gatewayConfig struct {
	baseURL string
	apiKey  string
	// upstreamModel 非空时，把客户端请求里的 model 统一改写为它（见 prepareChatRequest）。
	// 用途：换上游后客户端配置里的模型名（如 qwen3.5-omni-plus）在上游不存在，
	// 由服务端做映射，用户端**不需要改任何设置**（手册 §16：降低技术门槛）。
	upstreamModel string
	client        *http.Client
}

func newGateway(baseURL, apiKey, upstreamModel string) *gatewayConfig {
	return &gatewayConfig{
		baseURL:       strings.TrimRight(baseURL, "/"),
		apiKey:        apiKey,
		upstreamModel: strings.TrimSpace(upstreamModel),
		client: &http.Client{
			// 不设全局 Timeout：流式对话可能持续数分钟，靠 ResponseController 管理读写期限
			Transport: &http.Transport{
				MaxIdleConns:        16,
				MaxIdleConnsPerHost: 8,
				IdleConnTimeout:     90 * time.Second,
			},
		},
	}
}

func (g *gatewayConfig) enabled() bool { return g != nil && g.apiKey != "" && g.baseURL != "" }

// 网关并发上限：每账号 + 全局。数值小是刻意的——UI Agent 一次只跑一个任务，
// 正常用户不会撞上限；而盗刷者要么被每账号闸门卡住，要么被全局闸门拖慢。
const (
	maxInflightPerAccount = 2
	maxInflightGlobal     = 16
)

// acquireGatewaySlot 申请一个在飞名额；满则返回 false（调用方回 429）。
func (s *server) acquireGatewaySlot(accountID string) bool {
	s.gwMu.Lock()
	defer s.gwMu.Unlock()
	if s.gwInflightGlobal >= maxInflightGlobal {
		return false
	}
	if s.gwInflight == nil {
		s.gwInflight = make(map[string]int)
	}
	if s.gwInflight[accountID] >= maxInflightPerAccount {
		return false
	}
	s.gwInflightGlobal++
	s.gwInflight[accountID]++
	return true
}

func (s *server) releaseGatewaySlot(accountID string) {
	s.gwMu.Lock()
	defer s.gwMu.Unlock()
	if s.gwInflightGlobal > 0 {
		s.gwInflightGlobal--
	}
	if n := s.gwInflight[accountID]; n > 1 {
		s.gwInflight[accountID] = n - 1
	} else {
		delete(s.gwInflight, accountID)
	}
}

// handleChatCompletions 转发 OpenAI 兼容的对话补全请求并按用量扣额度。
func (s *server) handleChatCompletions(w http.ResponseWriter, r *http.Request, a *Account) {
	if !s.gateway.enabled() {
		writeErr(w, http.StatusServiceUnavailable, "gateway_disabled",
			"额度网关未启用（服务端未配置上游密钥）")
		return
	}
	if a.Quota <= 0 {
		writeErr(w, http.StatusPaymentRequired, "quota_exhausted",
			"额度不足，请在应用内使用兑换码充值")
		return
	}

	// 并发闸门（安全审查 2026-09-15 P0-S3）：额度是"先检查、后扣减"，两者之间若允许
	// 无限并发，超支就是 N × 单次上游成本且 N 无上限。限制同时在飞的请求数，
	// 让超支有明确上界（每账号 2 + 全局 16）。
	if !s.acquireGatewaySlot(a.AccountID) {
		writeErr(w, http.StatusTooManyRequests, "too_many_requests",
			"同一账号同时在跑的请求过多，请稍后重试")
		return
	}
	defer s.releaseGatewaySlot(a.AccountID)

	// 长连接流式响应需要放宽写期限，否则会被 http.Server 的 WriteTimeout 掐断
	rc := http.NewResponseController(w)
	_ = rc.SetWriteDeadline(time.Now().Add(15 * time.Minute))

	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, "body_too_large", "请求体过大")
		return
	}
	// 顶层只解析一次；messages、tools 等扩展字段保持 RawMessage，直传上游。
	body, model, stream, err := s.gateway.prepareChatRequest(body)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "请求体不是合法 JSON 对象或字段类型错误")
		return
	}

	upReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost,
		s.gateway.baseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "构造上游请求失败")
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	upReq.Header.Set("Authorization", "Bearer "+s.gateway.apiKey)
	upReq.Header.Set("Accept", "application/json")

	resp, err := s.gateway.client.Do(upReq)
	if err != nil {
		logError("gateway upstream error account=%s: %v", a.AccountID, err)
		writeErr(w, http.StatusBadGateway, "upstream_error", "上游请求失败")
		return
	}
	defer resp.Body.Close()

	// 上游报错原样透传，且**不计费**——用户不该为失败的请求付费
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, io.LimitReader(resp.Body, 64*1024))
		logInfo("gateway upstream %d account=%s model=%s（未计费）",
			resp.StatusCode, a.AccountID, model)
		return
	}

	if !stream {
		data, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
		if err != nil {
			writeErr(w, http.StatusBadGateway, "upstream_read", "读取上游响应失败")
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(data)
		s.chargeQuota(a.AccountID, extractUsageTokens(data), model)
		return
	}

	// 流式：逐行转发并保持 SSE 行为，同时从带 usage 的 chunk 里取权威用量
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	var tokens int
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for sc.Scan() {
		line := sc.Bytes()
		if bytes.Contains(line, []byte(`"usage"`)) {
			if u := extractUsageTokens(line); u > tokens {
				tokens = u
			}
		}
		if _, err := w.Write(line); err != nil {
			break // 客户端断开会走到这里，仍需按已产生的用量计费
		}
		if _, err := w.Write([]byte("\n")); err != nil {
			break
		}
		if err := rc.Flush(); err != nil {
			break
		}
	}
	s.chargeQuota(a.AccountID, tokens, model)
}

// handleModels 透传模型列表，便于客户端发现可用模型。
func (s *server) handleModels(w http.ResponseWriter, r *http.Request, a *Account) {
	if !s.gateway.enabled() {
		writeErr(w, http.StatusServiceUnavailable, "gateway_disabled", "额度网关未启用")
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet,
		s.gateway.baseURL+"/models", nil)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "构造上游请求失败")
		return
	}
	req.Header.Set("Authorization", "Bearer "+s.gateway.apiKey)
	resp, err := s.gateway.client.Do(req)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "上游请求失败")
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 1<<20))
}

// prepareChatRequest 只解码一次顶层对象，再局部读取 model/stream。
// messages、tools 及未来扩展字段以 RawMessage 保留，避免重建嵌套对象、浮点化数字。
// 流式用量开关与上游模型/思考模式适配在同一份 map 上完成，最多编码一次整份请求。
func (g *gatewayConfig) prepareChatRequest(body []byte) ([]byte, string, bool, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(body, &fields); err != nil {
		return nil, "", false, err
	}
	if fields == nil {
		return nil, "", false, fmt.Errorf("请求体须为 JSON 对象")
	}
	var model string
	var stream bool
	if raw, ok := fields["model"]; ok {
		if err := json.Unmarshal(raw, &model); err != nil {
			return nil, "", false, fmt.Errorf("model: %w", err)
		}
	}
	if raw, ok := fields["stream"]; ok {
		if err := json.Unmarshal(raw, &stream); err != nil {
			return nil, "", false, fmt.Errorf("stream: %w", err)
		}
	}
	changed := false
	if stream {
		var opts map[string]json.RawMessage
		if raw, ok := fields["stream_options"]; ok {
			if err := json.Unmarshal(raw, &opts); err != nil {
				return nil, "", false, fmt.Errorf("stream_options: %w", err)
			}
		}
		if opts == nil {
			opts = map[string]json.RawMessage{}
		}
		// 用量由上游计量，客户端的 false 或 null 都须改为 true。
		if !bytes.Equal(bytes.TrimSpace(opts["include_usage"]), []byte("true")) {
			opts["include_usage"] = json.RawMessage("true")
			raw, err := json.Marshal(opts)
			if err != nil {
				return nil, "", false, err
			}
			fields["stream_options"] = raw
			changed = true
		}
	}
	if g.upstreamModel != "" && model != g.upstreamModel {
		model = g.upstreamModel
		raw, err := json.Marshal(model)
		if err != nil {
			return nil, "", false, err
		}
		fields["model"] = raw
		changed = true
	}
	if strings.Contains(strings.ToLower(g.baseURL), "deepseek") {
		if _, ok := fields["thinking"]; !ok {
			fields["thinking"] = json.RawMessage(`{"type":"disabled"}`)
			changed = true
		}
	}
	if !changed {
		return body, model, stream, nil
	}
	out, err := json.Marshal(fields)
	return out, model, stream, err
}

// extractUsageTokens 从响应体（或单个 SSE 行）里取 usage.total_tokens。
// 取不到返回 0，由 chargeQuota 按最小计费单位处理。
func extractUsageTokens(b []byte) int {
	// 必须匹配 `"usage"` **后面跟对象**的情况：流式 chunk 里普遍是 `"usage":null`，
	// 早期版本在 "usage" 之后找第一个 '{'，结果抓到的是后面 "choices":[{...}] 那个对象，
	// 解出来全是 0 → 静默按最小单位计费。这里显式跳过 null。
	needle := []byte(`"usage"`)
	from := 0
	for {
		i := bytes.Index(b[from:], needle)
		if i < 0 {
			return 0
		}
		i += from
		rest := b[i+len(needle):]
		trimmed := bytes.TrimLeft(rest, " \t\r\n:")
		if len(trimmed) == 0 {
			return 0
		}
		if trimmed[0] != '{' {
			from = i + len(needle) // usage 是 null / 标量，继续往后找
			continue
		}
		if n := parseUsageObject(trimmed); n > 0 {
			return n
		}
		from = i + len(needle)
	}
}

// parseUsageObject 从以 '{' 开头的片段里解出 token 数；解不出返回 0。
func parseUsageObject(seg []byte) int {

	// 注意：seg 已经**指向 usage 对象本身**，所以直接解到用量结构体上。
	// 早期版本在这里又套了一层 `Usage struct{...} json:"usage"` 的包装，
	// 结果永远解出 0，导致每笔只按最小单位计费——静默少扣费。
	var u struct {
		TotalTokens      int `json:"total_tokens"`
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	}
	if err := json.Unmarshal(seg[:firstJSONObjectEnd(seg)], &u); err != nil {
		return 0
	}
	if u.TotalTokens > 0 {
		return u.TotalTokens
	}
	return u.PromptTokens + u.CompletionTokens
}

// firstJSONObjectEnd 返回 seg 中第一个**配平**的 JSON 对象结束位置（含花括号）。
// 不能简单地找第一个 '}'——usage 里可能嵌套对象，那样会截断成非法 JSON。
func firstJSONObjectEnd(seg []byte) int {
	depth, inStr, esc := 0, false, false
	for i, c := range seg {
		if inStr {
			switch {
			case esc:
				esc = false
			case c == '\\':
				esc = true
			case c == '"':
				inStr = false
			}
			continue
		}
		switch c {
		case '"':
			inStr = true
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return i + 1
			}
		}
	}
	return len(seg)
}

// chargeQuota 按用量扣减额度。1 unit = 1000 tokens，向上取整且至少 1 unit。
func (s *server) chargeQuota(accountID string, tokens int, model string) {
	units := int64((tokens + 999) / 1000)
	if units < 1 {
		units = 1 // 即使上游没报 usage，也按最小单位计费，避免零成本无限调用
	}
	bal, err := s.store.ConsumeQuota(accountID, units, "llm:"+model)
	if err != nil {
		log.Printf("[agentlite] 扣减额度失败 account=%s units=%d: %v", accountID, units, err)
		return
	}
	logInfo("gateway charged account=%s model=%s tokens=%d units=%d balance=%d",
		accountID, model, tokens, units, bal)
}
