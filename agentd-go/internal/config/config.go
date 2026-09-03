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
}

// Default returns sane defaults (no key until the user fills it in).
func Default() *Config {
	return &Config{
		BaseURL: "https://open.bigmodel.cn/api/paas/v4",
		Model:   "glm-4.6",
		Port:    "8080",
		MaxSteps: 20,
		SystemPrompt: "你是一个 Android 手机操作助手。根据当前屏幕的语义节点列表，" +
			"一步一步操作手机完成用户任务。只输出一个 JSON 动作，不要输出其他文字。",
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
