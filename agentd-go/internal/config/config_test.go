// SPDX-License-Identifier: Apache-2.0

package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMergeKeepsAPIKey(t *testing.T) {
	base := Default()
	base.APIKey = "sk-real-key"

	// 哨兵 = 保持不变（前端只回填哨兵，不回填真值）
	if got := (&Config{APIKey: MaskSentinel}).Merge(base); got.APIKey != "sk-real-key" {
		t.Fatalf("哨兵未保留原 Key: %q", got.APIKey)
	}
	// 空值 = 同样保留，避免界面误清空导致任务全部 401
	if got := (&Config{APIKey: ""}).Merge(base); got.APIKey != "sk-real-key" {
		t.Fatalf("空 Key 未保留原值: %q", got.APIKey)
	}
	// 新值 = 覆盖
	if got := (&Config{APIKey: "sk-new"}).Merge(base); got.APIKey != "sk-new" {
		t.Fatalf("新 Key 未生效: %q", got.APIKey)
	}
	// 返回值必须是新对象：base 不能被改写（锁外落盘用的是快照）
	if base.APIKey != "sk-real-key" {
		t.Fatalf("base 被就地修改: %q", base.APIKey)
	}
}

func TestMergeInheritsAndFillsDefaults(t *testing.T) {
	base := Default()
	base.APIKey = "sk-real"
	base.Port = "8080"

	got := (&Config{Model: "glm-4.6"}).Merge(base)
	if got.BaseURL != base.BaseURL || got.Port != "8080" || got.MaxSteps != base.MaxSteps ||
		got.SystemPrompt != base.SystemPrompt || got.AsrModel != base.AsrModel {
		t.Fatalf("空字段未继承: %+v", got)
	}

	// base 为 nil 时按默认值兜底，不能 panic 或产出空端口（空端口 = 监听 ":")
	got = (&Config{}).Merge(nil)
	if got.Port == "" || got.Model == "" || got.MaxSteps <= 0 {
		t.Fatalf("默认兜底失败: %+v", got)
	}
}

func TestMergeLocalSubmitKeepsOtherFields(t *testing.T) {
	base := Default()
	base.APIKey = "sk-real"
	base.Model = "glm-4.6"
	// 只提交模型：其余字段必须原样保留（并发保存时尤其重要）
	got := (&Config{Model: "glm-4v"}).Merge(base)
	if got.Model != "glm-4v" || got.APIKey != "sk-real" || got.BaseURL != base.BaseURL {
		t.Fatalf("局部提交覆盖了其他字段: %+v", got)
	}
}

func TestValidateBaseURL(t *testing.T) {
	ok := []string{
		"https://open.bigmodel.cn/api/paas/v4",
		"https://api.example.com:8443/v1",
		"http://127.0.0.1:8000/v1",
		"http://localhost:11434/v1",
	}
	for _, u := range ok {
		if err := ValidateBaseURL(u); err != nil {
			t.Fatalf("%q 应通过: %v", u, err)
		}
	}
	bad := []string{
		"",
		"http://api.example.com/v1",       // 明文外网 = 泄露 Bearer Key
		"ftp://api.example.com/v1",        // 协议不受支持
		"https://",                        // 缺主机
		"https://user:pw@api.example.com", // 凭证内联
		"https://api.example.com:99999",   // 端口越界
	}
	for _, u := range bad {
		if err := ValidateBaseURL(u); err == nil {
			t.Fatalf("%q 应被拒绝", u)
		}
	}
}

func TestValidatePort(t *testing.T) {
	for _, p := range []string{"1", "8080", "65535"} {
		if err := ValidatePort(p); err != nil {
			t.Fatalf("%q 应通过: %v", p, err)
		}
	}
	for _, p := range []string{"", "0", "65536", "-1", "80a", "8080 8081"} {
		if err := ValidatePort(p); err == nil {
			t.Fatalf("%q 应被拒绝", p)
		}
	}
}

func TestValidateFullConfig(t *testing.T) {
	c := Default()
	c.APIKey = "sk-x"
	if err := c.Validate(); err != nil {
		t.Fatalf("默认配置应合法: %v", err)
	}
	c.MaxSteps = 0
	if err := c.Validate(); err == nil {
		t.Fatal("max_steps=0 应被拒绝")
	}
	c = Default()
	c.BaseURL = "http://evil.example.com"
	if err := c.Validate(); err == nil {
		t.Fatal("明文 http 应被拒绝")
	}
}

// Save 必须是原子替换：写完权限 0600，且目录里不留临时文件。
func TestSaveIsAtomicAndPrivate(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	c := Default()
	c.APIKey = "sk-secret"
	if err := c.Save(path); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("配置权限应为 0600，实为 %o", perm)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".config-") {
			t.Fatalf("残留临时文件: %s", e.Name())
		}
	}

	// 覆盖写之后内容可解析且为新值
	c.APIKey = "sk-2"
	if err := c.Save(path); err != nil {
		t.Fatal(err)
	}
	back, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if back.APIKey != "sk-2" {
		t.Fatalf("覆盖写失败: %q", back.APIKey)
	}
}

// 半截 JSON（模拟旧实现写入中断）必须报错，而不是静默用默认值跑起来。
func TestLoadRejectsTruncatedFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	if err := os.WriteFile(path, []byte(`{"model": "glm`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("截断的配置应报错")
	}
}
