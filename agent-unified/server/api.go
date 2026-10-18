// SPDX-License-Identifier: Apache-2.0
//
// # AgentLite 同步服务 — HTTP 接口层
//
// 鉴权模型：
//   - 账号令牌（Bearer）覆盖绝大多数接口；设备通过 X-Device-Id 归属到账号。
//   - 服务端**校验 device_id 确实属于该账号**，否则拒绝——防止跨账号伪造来源。
//   - 管理接口另用 X-Admin-Token（独立于账号体系，存服务器本地文件）。
package main

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const maxBodyBytes = 8 << 20 // 单次请求体上限 8MB

// ---------- 响应工具 ----------

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

type apiError struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func writeErr(w http.ResponseWriter, code int, kind, msg string) {
	writeJSON(w, code, apiError{Error: kind, Message: msg})
}

func decodeBody(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	// 刻意不用 DisallowUnknownFields：客户端先于服务端升级时，
	// 新字段应被忽略而不是让整批同步 400 失败（丢数据的代价高于错过拼写错误）。
	// 代价是未知顶层字段会被静默忽略，因此测试必须断言 accepted 计数，不能只看 HTTP 状态码。
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, "body_too_large", "请求体超过 8MB")
			return false
		}
		writeErr(w, http.StatusBadRequest, "bad_json", "请求体不是合法 JSON: "+err.Error())
		return false
	}
	return true
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const p = "Bearer "
	if len(h) > len(p) && strings.EqualFold(h[:len(p)], p) {
		return strings.TrimSpace(h[len(p):])
	}
	return ""
}

// ---------- 中间件 ----------

func (s *server) recoverMW(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				logError("panic on %s %s: %v", r.Method, r.URL.Path, rec)
				writeErr(w, http.StatusInternalServerError, "internal", "服务端内部错误")
			}
		}()
		next(w, r)
	}
}

func (s *server) logMW(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		lw := &loggingWriter{ResponseWriter: w, code: 200}
		next(lw, r)
		// 不记录 Authorization 头与请求体——令牌与任务内容不进日志
		logInfo("%s %s %d %dB %s", r.Method, r.URL.Path, lw.code, lw.n,
			time.Since(start).Round(time.Millisecond))
	}
}

type loggingWriter struct {
	http.ResponseWriter
	code        int
	n           int
	wroteHeader bool
}

// ResponseController 沿 Unwrap 找到底层 Flush/Deadline，不复制 HTTP 能力实现。
func (l *loggingWriter) Unwrap() http.ResponseWriter { return l.ResponseWriter }

func (l *loggingWriter) WriteHeader(code int) {
	if l.wroteHeader {
		return
	}
	l.ResponseWriter.WriteHeader(code)
	if code >= 100 && code < 200 && code != http.StatusSwitchingProtocols {
		return
	}
	l.wroteHeader = true
	l.code = code
}

func (l *loggingWriter) Write(b []byte) (int, error) {
	if !l.wroteHeader {
		l.WriteHeader(http.StatusOK)
	}
	n, err := l.ResponseWriter.Write(b)
	l.n += n
	return n, err
}

// authMW 校验账号令牌。
func (s *server) authMW(next func(http.ResponseWriter, *http.Request, *Account)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := bearerToken(r)
		if tok == "" {
			writeErr(w, http.StatusUnauthorized, "missing_token",
				"缺少 Authorization: Bearer <account_token>")
			return
		}
		acct, err := s.store.AccountByToken(tok)
		if err != nil {
			// 不区分「令牌不存在」与「账号被禁用」，避免探测
			writeErr(w, http.StatusUnauthorized, "invalid_token", "账号令牌无效")
			return
		}
		next(w, r, acct)
	}
}

// authDeviceMW 校验账号令牌，并把 X-Device-Id 解析为该账号名下的设备。
func (s *server) authDeviceMW(next func(http.ResponseWriter, *http.Request, *Account, *Device)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := bearerToken(r)
		if tok == "" {
			writeErr(w, http.StatusUnauthorized, "missing_token",
				"缺少 Authorization: Bearer <account_token>")
			return
		}
		acct, err := s.store.AccountByToken(tok)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "invalid_token", "账号令牌无效")
			return
		}
		id := strings.TrimSpace(r.Header.Get("X-Device-Id"))
		if id == "" {
			writeErr(w, http.StatusBadRequest, "missing_device", "缺少 X-Device-Id 头")
			return
		}
		dev, err := s.store.DeviceOfAccount(acct.AccountID, id)
		if err != nil {
			// 设备不属于该账号与设备不存在返回同一个错误，避免跨账号探测设备 ID
			writeErr(w, http.StatusForbidden, "device_not_bound", "该设备未绑定到当前账号")
			return
		}
		next(w, r, acct, dev)
	}
}

// adminMW 校验管理令牌（用于发放兑换码）。
func (s *server) adminMW(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.adminToken == "" {
			writeErr(w, http.StatusServiceUnavailable, "admin_disabled",
				"未配置管理令牌，管理接口不可用")
			return
		}
		got := r.Header.Get("X-Admin-Token")
		if !subtleCompare(got, s.adminToken) {
			writeErr(w, http.StatusUnauthorized, "bad_admin_token", "管理令牌无效")
			return
		}
		next(w, r)
	}
}

// ---------- 基础接口 ----------

func (s *server) handleHealth(w http.ResponseWriter, r *http.Request) {
	devices := s.store.Devices()
	active := 0
	for _, d := range devices {
		if !d.Disabled && time.Since(d.LastSeenAt) < 7*24*time.Hour {
			active++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":          true,
		"service":     "agentlite-sync",
		"version":     version,
		"accounts":    len(s.store.Accounts()),
		"devices":     len(devices),
		"active_7d":   active,
		"uptime_sec":  int64(time.Since(s.started).Seconds()),
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 账号接口 ----------

type credentials struct {
	Name string `json:"name"`
	Key  string `json:"key"`
}

func accountPayload(a *Account, token string) map[string]any {
	return map[string]any{
		"account_id":     a.AccountID,
		"name":           a.Name,
		"token":          token, // 仅注册/登录时返回明文
		"quota":          a.Quota,
		"quota_used":     a.QuotaUsed,
		"created_at":     a.CreatedAt,
		"lan_key_params": LANKeyParamsNow(),
		"server_time":    time.Now().UTC().Format(time.RFC3339),
	}
}

func (s *server) handleAccountRegister(w http.ResponseWriter, r *http.Request) {
	if !s.rl.allow("reg:" + clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "注册过于频繁，请稍后再试")
		return
	}
	var req credentials
	if !decodeBody(w, r, &req) {
		return
	}
	a, token, err := s.store.RegisterAccount(req.Name, req.Key)
	if err != nil {
		switch {
		case errors.Is(err, ErrAccountExists):
			writeErr(w, http.StatusConflict, "account_exists", "账号名已被占用")
		case errors.Is(err, ErrBadAccountName), errors.Is(err, ErrBadAccountKey):
			writeErr(w, http.StatusBadRequest, "bad_input", err.Error())
		default:
			logError("register account failed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "注册失败")
		}
		return
	}
	logInfo("account registered id=%s name=%s", a.AccountID, a.Name)
	writeJSON(w, http.StatusOK, accountPayload(a, token))
}

func (s *server) handleAccountLogin(w http.ResponseWriter, r *http.Request) {
	if !s.rl.allow("login:" + clientIP(r)) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "登录尝试过于频繁，请稍后再试")
		return
	}
	var req credentials
	if !decodeBody(w, r, &req) {
		return
	}
	a, token, err := s.store.LoginAccount(req.Name, req.Key)
	if err != nil {
		// 统一错误文案，不区分「账号不存在」与「密钥错误」，避免账号枚举
		writeErr(w, http.StatusUnauthorized, "bad_credentials", "账号名或账号密钥错误")
		return
	}
	logInfo("account login id=%s name=%s", a.AccountID, a.Name)
	writeJSON(w, http.StatusOK, accountPayload(a, token))
}

func (s *server) handleAccountMe(w http.ResponseWriter, r *http.Request, a *Account) {
	writeJSON(w, http.StatusOK, map[string]any{
		"account_id":     a.AccountID,
		"name":           a.Name,
		"quota":          a.Quota,
		"quota_used":     a.QuotaUsed,
		"created_at":     a.CreatedAt,
		"last_login_at":  a.LastLoginAt,
		"devices":        s.store.DevicesOfAccount(a.AccountID),
		"lan_key_params": LANKeyParamsNow(),
		"server_time":    time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 设备绑定 ----------

func (s *server) handleDeviceBind(w http.ResponseWriter, r *http.Request, a *Account) {
	var req RegisterRequest
	if !decodeBody(w, r, &req) {
		return
	}
	dev, created, err := s.store.BindDevice(a.AccountID, req)
	if err != nil {
		if errors.Is(err, ErrBadPayload) {
			writeErr(w, http.StatusBadRequest, "bad_payload", err.Error())
			return
		}
		logError("bind device failed: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "设备绑定失败")
		return
	}
	if created {
		logInfo("device bound account=%s device=%s model=%s", a.AccountID, dev.DeviceID, dev.Model)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"device_id":   dev.DeviceID,
		"account_id":  dev.AccountID,
		"created":     created,
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 同步 ----------

// pushRequest 是上行同步请求体。
// 服务端不理解 task/memory 的内部结构，data 原样保存——客户端可自由演进。
type pushRequest struct {
	Tasks    []Incoming      `json:"tasks"`
	Memories []Incoming      `json:"memories"`
	Status   json.RawMessage `json:"status"`
}

func (s *server) handlePush(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	var req pushRequest
	if !decodeBody(w, r, &req) {
		return
	}
	items := make([]PendingItem, 0, len(req.Tasks)+len(req.Memories))
	for _, t := range req.Tasks {
		items = append(items, PendingItem{
			Kind: kindTask, Item: t,
			Origin: s.store.ValidOrigin(a.AccountID, t.OriginDeviceID),
		})
	}
	for _, m := range req.Memories {
		items = append(items, PendingItem{
			Kind: kindMemory, Item: m,
			Origin: s.store.ValidOrigin(a.AccountID, m.OriginDeviceID),
		})
	}
	// 状态是**快照**而非日志，刻意不进 append-only 记录流：
	// 记录流要求客户端提供稳定 id 才能幂等，而状态没有天然 id——
	// 早期版本用秒级时间戳当 id，导致同一秒重推翻倍、跨秒又变新记录。
	// 状态只走 Touch 写进设备档案（最新值覆盖），旧值不留历史。

	res, err := s.store.Append(a.AccountID, dev.DeviceID, items)
	if err != nil {
		logError("append failed account=%s: %v", a.AccountID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "写入失败")
		return
	}
	if err := s.store.Touch(dev.DeviceID, req.Status); err != nil {
		logError("touch failed device=%s: %v", dev.DeviceID, err)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accepted":    res.Accepted,
		"duplicates":  res.Duplicates,
		"rejected":    res.Rejected,
		"server_seq":  res.Seq,
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

func (s *server) handlePull(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	if since < 0 {
		since = 0
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	recs, last, err := s.store.Pull(a.AccountID, since, limit)
	if err != nil {
		logError("pull failed account=%s: %v", a.AccountID, err)
		writeErr(w, http.StatusInternalServerError, "internal", "读取失败")
		return
	}
	nextSince := since
	if len(recs) > 0 {
		nextSince = recs[len(recs)-1].Seq
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"records":     recs,
		"server_seq":  last, // 兼容高水位；消费游标只使用 next_since
		"next_since":  nextSince,
		"has_more":    nextSince < last,
		"config":      s.store.Config(),
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

func (s *server) handleConfig(w http.ResponseWriter, r *http.Request, a *Account) {
	writeJSON(w, http.StatusOK, map[string]any{
		"config":      s.store.Config(),
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 套餐与订单（应用内买单） ----------

// handlePacks 返回可购买套餐。价格仅用于展示——真实在线支付未接入。
func (s *server) handlePacks(w http.ResponseWriter, r *http.Request, a *Account) {
	writeJSON(w, http.StatusOK, map[string]any{
		"packs":       s.store.Packs(),
		"pay_methods": []string{methodRedeem},
		"online_pay":  false, // 明确告知客户端：在线支付未接入，当前只能用兑换码
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

type createOrderRequest struct {
	PackID string `json:"pack_id"`
}

func (s *server) handleCreateOrder(w http.ResponseWriter, r *http.Request, a *Account) {
	var req createOrderRequest
	if !decodeBody(w, r, &req) {
		return
	}
	o, err := s.store.CreateOrder(a.AccountID, req.PackID)
	if err != nil {
		if errors.Is(err, ErrPackNotFound) {
			writeErr(w, http.StatusNotFound, "pack_not_found", "套餐不存在")
			return
		}
		logError("create order failed: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "下单失败")
		return
	}
	logInfo("order created %s account=%s pack=%s", o.OrderID, a.AccountID, o.PackID)
	writeJSON(w, http.StatusOK, map[string]any{"order": o})
}

func (s *server) handleListOrders(w http.ResponseWriter, r *http.Request, a *Account) {
	writeJSON(w, http.StatusOK, map[string]any{
		"orders": s.store.ListOrders(a.AccountID),
		"quota":  a.Quota,
	})
}

type orderActionRequest struct {
	OrderID string `json:"order_id"`
}

func (s *server) handleCancelOrder(w http.ResponseWriter, r *http.Request, a *Account) {
	var req orderActionRequest
	if !decodeBody(w, r, &req) {
		return
	}
	o, err := s.store.CancelOrder(a.AccountID, req.OrderID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "order_closed", "订单不存在或已结束")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"order": o})
}

type payOrderRequest struct {
	OrderID string `json:"order_id"`
	Code    string `json:"code"`
}

// handlePayOrder 用兑换码支付订单：核销兑换码 → 加额度 → 订单置为 paid。
//
// 这是当前唯一可用的支付方式。接入真实在线支付时，新增一个 method 分支即可，
// 订单生命周期不用改。
func (s *server) handlePayOrder(w http.ResponseWriter, r *http.Request, a *Account) {
	if !s.rl.allow("pay:" + a.AccountID) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "支付尝试过于频繁")
		return
	}
	var req payOrderRequest
	if !decodeBody(w, r, &req) {
		return
	}
	o, balance, err := s.store.PayOrderWithCode(a.AccountID, req.OrderID, req.Code)
	if err != nil {
		switch {
		case errors.Is(err, ErrOrderNotFound), errors.Is(err, ErrOrderClosed):
			writeErr(w, http.StatusNotFound, "order_closed", "订单不存在或已结束")
		case errors.Is(err, ErrCodeNotFound), errors.Is(err, ErrCodeUsed):
			// 与直接兑换保持同一文案，避免通过响应差异枚举有效兑换码
			writeErr(w, http.StatusNotFound, "code_invalid", "兑换码无效或已被使用")
		default:
			logError("pay order failed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "支付失败")
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"order":       o,
		"quota":       balance,
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 跨设备指令 ----------

type createCommandRequest struct {
	ToDevice string `json:"to_device"`
	Task     string `json:"task"`
}

// handleCreateCommand 设备 A 向同账号的设备 B 下发一条任务。
//
// 定向、限流、限期：不支持广播（一个失陷账号不该能一次控制全部设备）、
// 每账号每分钟最多 10 条、5 分钟未被领取即作废。
func (s *server) handleCreateCommand(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	var req createCommandRequest
	if !decodeBody(w, r, &req) {
		return
	}
	c, err := s.store.CreateCommand(a.AccountID, dev.DeviceID, strings.TrimSpace(req.ToDevice), req.Task)
	if err != nil {
		switch {
		case errors.Is(err, ErrCommandSelf):
			writeErr(w, http.StatusBadRequest, "command_self", "不能给自己下发指令")
		case errors.Is(err, ErrNotTargetDevice):
			writeErr(w, http.StatusForbidden, "bad_target", "目标设备不属于当前账号")
		case errors.Is(err, ErrBadPayload):
			writeErr(w, http.StatusBadRequest, "bad_input", err.Error())
		default:
			logError("create command failed: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "下发失败")
		}
		return
	}
	logInfo("command created id=%s account=%s from=%s to=%s", c.CommandID, a.AccountID, c.FromDevice, c.ToDevice)
	writeJSON(w, http.StatusOK, map[string]any{
		"command":     c,
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

// handleListCommands 目标设备拉取待执行指令。拉取式投递：客户端会被国产 ROM
// 杀后台，长连接不可靠，而拉取天然自愈。
func (s *server) handleListCommands(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	cmds := s.store.PendingFor(a.AccountID, dev.DeviceID)
	writeJSON(w, http.StatusOK, map[string]any{
		"commands":    cmds,
		"count":       len(cmds),
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

type ackCommandRequest struct {
	CommandID string `json:"command_id"`
	Status    string `json:"status"`
	Detail    string `json:"detail"`
}

// handleAckCommand 目标设备回报执行状态。**只有目标设备能回报**，防伪造结果。
func (s *server) handleAckCommand(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	var req ackCommandRequest
	if !decodeBody(w, r, &req) {
		return
	}
	c, err := s.store.AckCommand(a.AccountID, dev.DeviceID, req.CommandID, req.Status, req.Detail)
	if err != nil {
		switch {
		case errors.Is(err, ErrNotTargetDevice):
			writeErr(w, http.StatusForbidden, "not_target", "只有目标设备可以回报该指令")
		case errors.Is(err, ErrCommandConsumed):
			writeErr(w, http.StatusConflict, "already_acked", "该指令已回报过终态")
		case errors.Is(err, ErrCommandNotFound):
			writeErr(w, http.StatusNotFound, "command_not_found", "指令不存在或已过期")
		default:
			writeErr(w, http.StatusBadRequest, "bad_input", err.Error())
		}
		return
	}
	logInfo("command ack id=%s status=%s device=%s", c.CommandID, c.Status, dev.DeviceID)
	writeJSON(w, http.StatusOK, map[string]any{"command": c})
}

// handleGetCommand 下发方查询指令结果。
func (s *server) handleGetCommand(w http.ResponseWriter, r *http.Request, a *Account) {
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	c, err := s.store.GetCommand(a.AccountID, id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "command_not_found", "指令不存在或已过期")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"command": c})
}

// ---------- 局域网会合 ----------

type rendezvousRequest struct {
	LANIPs []string `json:"lan_ips"`
	Port   int      `json:"port"`
}

// handleRendezvous 设备上报自己的局域网位置，换取同账号邻居列表。
//
// 「同一内网」判定依据是服务端观测到的公网出口 IP（同 NAT 后必然一致），
// 不采信客户端自报——自报可伪造。局域网数据本身不过服务器。
func (s *server) handleRendezvous(w http.ResponseWriter, r *http.Request, a *Account, dev *Device) {
	var req rendezvousRequest
	if !decodeBody(w, r, &req) {
		return
	}
	if req.Port < 1 || req.Port > 65535 {
		writeErr(w, http.StatusBadRequest, "bad_port", "端口需在 1-65535")
		return
	}
	pub := clientIP(r)
	if err := s.store.RecordLANPresence(dev.DeviceID, pub, req.LANIPs, req.Port); err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "记录局域网状态失败")
		return
	}
	peers := s.store.LANPeers(a.AccountID, pub, dev.DeviceID)
	writeJSON(w, http.StatusOK, map[string]any{
		"same_lan":       len(peers) > 0,
		"peers":          peers,
		"public_ip":      pub,
		"lan_key_params": LANKeyParamsNow(),
		"server_time":    time.Now().UTC().Format(time.RFC3339),
	})
}

// ---------- 额度与兑换码 ----------

type redeemRequest struct {
	Code string `json:"code"`
}

func (s *server) handleRedeem(w http.ResponseWriter, r *http.Request, a *Account) {
	if !s.rl.allow("redeem:" + a.AccountID) {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "兑换尝试过于频繁")
		return
	}
	var req redeemRequest
	if !decodeBody(w, r, &req) {
		return
	}
	units, balance, err := s.store.Redeem(a.AccountID, req.Code)
	if err != nil {
		switch {
		case errors.Is(err, ErrCodeNotFound), errors.Is(err, ErrCodeUsed):
			// 合并两类错误，避免通过响应差异枚举有效兑换码
			writeErr(w, http.StatusNotFound, "code_invalid", "兑换码无效或已被使用")
		default:
			logError("redeem failed account=%s: %v", a.AccountID, err)
			writeErr(w, http.StatusInternalServerError, "internal", "兑换失败")
		}
		return
	}
	logInfo("redeem ok account=%s units=%d balance=%d", a.AccountID, units, balance)
	writeJSON(w, http.StatusOK, map[string]any{
		"units_added": units,
		"quota":       balance,
		"server_time": time.Now().UTC().Format(time.RFC3339),
	})
}

type createCodesRequest struct {
	Count int    `json:"count"`
	Units int64  `json:"units"`
	Batch string `json:"batch"`
}

func (s *server) handleAdminCreateCodes(w http.ResponseWriter, r *http.Request) {
	var req createCodesRequest
	if !decodeBody(w, r, &req) {
		return
	}
	codes, err := s.store.CreateCodes(req.Count, req.Units, req.Batch)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_input", err.Error())
		return
	}
	logInfo("admin created %d codes units=%d batch=%s", len(codes), req.Units, req.Batch)
	writeJSON(w, http.StatusOK, map[string]any{"codes": codes, "units": req.Units})
}

// ---------- 辅助 ----------

// clientIP 取客户端地址。服务直接暴露在公网、不经反代，
// 因此刻意**不信任** X-Forwarded-For（否则限流与局域网判定都可被伪造头绕过）。
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// subtleCompare 常量时间比较，避免管理令牌被逐字节试探。
func subtleCompare(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

// drainAndClose 确保连接可复用（http.MaxBytesReader 提前报错时尤其重要）。
func drainAndClose(r *http.Request) {
	if r.Body != nil {
		_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 64*1024))
		_ = r.Body.Close()
	}
}
