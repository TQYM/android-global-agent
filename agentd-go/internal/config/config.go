// SPDX-License-Identifier: Apache-2.0

// Package config persists the user-editable LLM API settings on the device.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config holds everything the WebUI lets the user customize.
type Config struct {
	BaseURL      string `json:"base_url"`
	APIKey       string `json:"api_key"`
	Model        string `json:"model"`
	Port         string `json:"port"`
	MaxSteps     int    `json:"max_steps"`
	SystemPrompt string `json:"system_prompt"`
	// Vision sends the current screenshot (compressed JPEG) with every
	// perception message. Requires a vision-capable model (GLM-4V series);
	// text-only models will reject the multimodal shape.
	Vision bool `json:"vision"`
	// AsrModel is the speech-to-text model for the WebUI mic (Zhipu
	// /audio/transcriptions). Configurable because model availability
	// varies by account.
	AsrModel string `json:"asr_model"`
}

// Default returns sane defaults (no key until the user fills it in).
func Default() *Config {
	return &Config{
		BaseURL:  "https://open.bigmodel.cn/api/paas/v4",
		Model:    "glm-4.6",
		Port:     "8080",
		MaxSteps: 20,
		AsrModel: "glm-asr-2512",
		SystemPrompt: "你是手机上的智能语音助手（类似小布/小爱）。工作方式：" +
			"1) 直达优先：打开设置页用 setting 一步到位，开关 WiFi/蓝牙/亮度/音量用专用动作，" +
			"能不点界面就不点。2) 界面操作是兜底：点击用节点 index，找不到先 scroll 或搜索，" +
			"不猜坐标。3) 页面加载中先 wait，不要盲点。4) 弹窗、广告、权限请求优先点关闭/跳过/" +
			"拒绝，除非任务是授权本身。5) 幂等：开关类任务先看当前状态，已是目标态直接 done。" +
			"6) 支付、转账、发消息、删数据等不可逆操作前，用 done 报告并请求用户确认，不要自己执行。" +
			"根据屏幕语义节点和截图，一步步完成用户任务。只输出一个 JSON 动作。",
	}
}

// Load reads the config file, falling back to defaults when it is absent.
func Load(path string) (*Config, error) {
	c := Default()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return c, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, c); err != nil {
		return nil, err
	}
	if c.MaxSteps <= 0 {
		c.MaxSteps = 20
	}
	if c.AsrModel == "" {
		c.AsrModel = "glm-asr-2512"
	}
	return c, nil
}

// Save 原子落盘：先写同目录临时文件（0600），fsync 后 rename 覆盖。
//
// 直接 WriteFile 覆盖在写入中途失败（或断电）时会留下半截 JSON，下次启动
// Load 直接报错、守护进程起不来；rename 在同一文件系统上是原子的，
// 要么是旧配置，要么是完整的新配置（手册 §15.2-4）。
func (c *Config) Save(path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".config-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpName)
		}
	}()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	cleanup = false
	return nil
}

// Merge 用 base（当前生效配置）补全 c 中用户未提交的字段，返回新快照。
//
// 为什么必须返回**新对象**：调用方在锁内合并、锁外落盘，落盘用的是这份
// 不可变快照；此前是把 `a.cfg = &in` 指向调用方传入的变量再在锁外读
// `a.cfg`，并发保存时会保存到别人的配置（手册 §15.2-4）。
//
// APIKey 的两条保留规则（前端只回填掩码，不回填真值）：
//   - 提交哨兵 "__AGENTD_KEEP__" → 保留原 Key；
//   - 提交空字符串 → 同样保留，避免界面误清空导致任务全部 401。
//
// 其余字段为空时继承 base，保证「只改模型」这类局部提交不会清空其他项。
func (c *Config) Merge(base *Config) *Config {
	out := *c
	if base == nil {
		base = Default()
	}
	if out.APIKey == "" || out.APIKey == MaskSentinel {
		out.APIKey = base.APIKey
	}
	if out.BaseURL == "" {
		out.BaseURL = base.BaseURL
	}
	if out.Model == "" {
		out.Model = base.Model
	}
	if out.Port == "" {
		out.Port = base.Port
	}
	if out.MaxSteps <= 0 {
		out.MaxSteps = base.MaxSteps
	}
	if out.SystemPrompt == "" {
		out.SystemPrompt = base.SystemPrompt
	}
	if out.AsrModel == "" {
		out.AsrModel = base.AsrModel
	}
	if out.Port == "" {
		out.Port = "8080"
	}
	if out.MaxSteps <= 0 {
		out.MaxSteps = 20
	}
	return &out
}

// MaskSentinel 是「保持原 API Key」的显式标记，与前端约定一致。
const MaskSentinel = "__AGENTD_KEEP__"

// Validate 校验用户可编辑字段。保存前必须过这一关：这些值会进入 root
// 进程的 URL 请求、监听地址与子进程参数，不能靠前端自觉。
func (c *Config) Validate() error {
	if err := ValidateBaseURL(c.BaseURL); err != nil {
		return err
	}
	if strings.TrimSpace(c.Model) == "" {
		return errors.New("model 不能为空")
	}
	if err := ValidatePort(c.Port); err != nil {
		return err
	}
	if c.MaxSteps <= 0 || c.MaxSteps > 200 {
		return fmt.Errorf("max_steps 必须在 1..200，收到 %d", c.MaxSteps)
	}
	if strings.TrimSpace(c.AsrModel) == "" {
		return errors.New("asr_model 不能为空")
	}
	return nil
}

// ValidatePort 只接受 1..65535 的十进制端口，杜绝 `":"+port` 拼接出
// 非预期监听地址（例如空串 = 全体网卡）。
func ValidatePort(port string) error {
	n, err := strconv.Atoi(strings.TrimSpace(port))
	if err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("port 必须是 1..65535 的整数，收到 %q", port)
	}
	return nil
}

// ValidateBaseURL 强制 HTTPS；仅本机回环允许明文 HTTP（本地中转/自测）。
//
// 不加这条时，用户（或任何能写配置的本地进程）可以把 BaseURL 指向
// 明文 http:// 的第三方域名，API Key 会以明文 Bearer 头送出去
// （手册 §15.2-5）。
func ValidateBaseURL(raw string) error {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return errors.New("base_url 不能为空")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("base_url 非法: %v", err)
	}
	if u.Host == "" {
		return fmt.Errorf("base_url 缺少主机名: %s", raw)
	}
	if u.User != nil {
		return errors.New("base_url 不允许携带用户名/密码")
	}
	switch u.Scheme {
	case "https":
	case "http":
		if !isLoopbackHost(u.Hostname()) {
			return fmt.Errorf("base_url 必须是 https（仅本机回环可用 http）: %s", raw)
		}
	default:
		return fmt.Errorf("base_url 协议不受支持: %q", u.Scheme)
	}
	if p := u.Port(); p != "" {
		if err := ValidatePort(p); err != nil {
			return err
		}
	}
	return nil
}

func isLoopbackHost(host string) bool {
	host = strings.Trim(host, "[]")
	if strings.EqualFold(host, "localhost") {
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback()
	}
	return false
}
