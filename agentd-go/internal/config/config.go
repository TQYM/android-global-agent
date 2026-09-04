// Package config persists the user-editable LLM API settings on the device.
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
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
		BaseURL: "https://open.bigmodel.cn/api/paas/v4",
		Model:   "glm-4.6",
		Port:    "8080",
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

// Save writes the config atomically-enough for a local tool (0600 perms).
func (c *Config) Save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}
