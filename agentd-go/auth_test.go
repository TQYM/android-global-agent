// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"agentd/internal/config"
)

func TestLoadOrCreateTokenCreatesAndReuses(t *testing.T) {
	dir := t.TempDir()
	t.Setenv(tokenEnv, "")

	tok, path, err := loadOrCreateToken(dir)
	if err != nil {
		t.Fatalf("首次生成失败: %v", err)
	}
	if len(tok) < minTokenLen {
		t.Fatalf("令牌过短: %q", tok)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("令牌文件缺失: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("令牌文件权限应为 0600，实为 %o", perm)
	}

	// 重启必须复用同一令牌，否则每次重启 WebUI 都要重新录入。
	again, _, err := loadOrCreateToken(dir)
	if err != nil || again != tok {
		t.Fatalf("令牌未复用: %q vs %q (err=%v)", again, tok, err)
	}

	// 内容被手工改短（或半截写入）时应重新生成，而不是接受弱令牌。
	if err := os.WriteFile(path, []byte("short\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	fresh, _, err := loadOrCreateToken(dir)
	if err != nil || len(fresh) < minTokenLen || fresh == tok {
		t.Fatalf("弱令牌未被替换: %q (err=%v)", fresh, err)
	}
}

func TestLoadOrCreateTokenEnvOverride(t *testing.T) {
	dir := t.TempDir()
	env := strings.Repeat("a", 64)
	t.Setenv(tokenEnv, env)
	tok, _, err := loadOrCreateToken(dir)
	if err != nil || tok != env {
		t.Fatalf("环境变量令牌未生效: %q (err=%v)", tok, err)
	}
}

// newTestGuard 返回一个已包装的处理器与一个记录是否被调用的标志。
func newTestGuard(token, port string) (http.HandlerFunc, *bool) {
	reached := false
	g := &guard{token: token, port: port}
	h := g.protect([]string{http.MethodGet, http.MethodPost}, func(w http.ResponseWriter, r *http.Request) {
		reached = true
		w.WriteHeader(http.StatusOK)
	})
	return h, &reached
}

func TestGuardRejectsBadRequests(t *testing.T) {
	const tok = "0123456789abcdef0123456789abcdef"
	cases := []struct {
		name    string
		method  string
		host    string
		origin  string
		ctype   string
		body    string
		token   string
		authz   string
		want    int
		reached bool
	}{
		{name: "无令牌", method: http.MethodGet, host: "127.0.0.1:8080", want: 401},
		{name: "错令牌", method: http.MethodGet, host: "127.0.0.1:8080", token: tok + "x", want: 401},
		{name: "令牌正确", method: http.MethodGet, host: "127.0.0.1:8080", token: tok, want: 200, reached: true},
		{name: "Bearer 令牌", method: http.MethodGet, host: "127.0.0.1:8080", authz: "Bearer " + tok, want: 200, reached: true},
		{name: "localhost 主机", method: http.MethodGet, host: "localhost:8080", token: tok, want: 200, reached: true},
		{name: "DNS 重绑定", method: http.MethodGet, host: "evil.com:8080", token: tok, want: 403},
		{name: "端口不符", method: http.MethodGet, host: "127.0.0.1:9999", token: tok, want: 403},
		{name: "跨源 Origin", method: http.MethodGet, host: "127.0.0.1:8080", origin: "http://evil.com", token: tok, want: 403},
		{name: "不透明 Origin", method: http.MethodGet, host: "127.0.0.1:8080", origin: "null", token: tok, want: 403},
		{name: "同源 Origin", method: http.MethodGet, host: "127.0.0.1:8080", origin: "http://127.0.0.1:8080", token: tok, want: 200, reached: true},
		{name: "方法不允许", method: http.MethodDelete, host: "127.0.0.1:8080", token: tok, want: 405},
		{name: "POST 非 JSON", method: http.MethodPost, host: "127.0.0.1:8080", token: tok, ctype: "text/plain", body: `{"a":1}`, want: 415},
		{name: "POST JSON", method: http.MethodPost, host: "127.0.0.1:8080", token: tok, ctype: "application/json; charset=utf-8", want: 200, reached: true},
		// 无请求体的 POST 不该被 Content-Type 规则误伤：/api/stop 就是这样调的，
		// 一律要求 JSON 会让「停止」按钮直接 415（实测踩到过）。
		{name: "POST 无 body 无 Content-Type", method: http.MethodPost, host: "127.0.0.1:8080", token: tok, want: 200, reached: true},
		{name: "POST 无 body 但声明 text/plain", method: http.MethodPost, host: "127.0.0.1:8080", token: tok, ctype: "text/plain", want: 200, reached: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h, reached := newTestGuard(tok, "8080")
			var reader io.Reader
			if tc.body != "" {
				reader = strings.NewReader(tc.body)
			}
			req := httptest.NewRequest(tc.method, "http://"+tc.host+"/api/config", reader)
			req.Host = tc.host
			if tc.origin != "" {
				req.Header.Set("Origin", tc.origin)
			}
			if tc.ctype != "" {
				req.Header.Set("Content-Type", tc.ctype)
			}
			if tc.token != "" {
				req.Header.Set(tokenHeader, tc.token)
			}
			if tc.authz != "" {
				req.Header.Set("Authorization", tc.authz)
			}
			rec := httptest.NewRecorder()
			h(rec, req)
			if rec.Code != tc.want {
				t.Fatalf("状态码 = %d，期望 %d（body=%q）", rec.Code, tc.want, rec.Body.String())
			}
			if *reached != tc.reached {
				t.Fatalf("业务处理器 reached = %v，期望 %v", *reached, tc.reached)
			}
		})
	}
}

// guard 在令牌为空时（理论上不会发生）必须拒绝一切，而不是放行。
func TestGuardFailsClosedOnEmptyToken(t *testing.T) {
	h, reached := newTestGuard("", "8080")
	req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8080/api/config", nil)
	req.Host = "127.0.0.1:8080"
	rec := httptest.NewRecorder()
	h(rec, req)
	if rec.Code != http.StatusUnauthorized || *reached {
		t.Fatalf("空令牌未 fail-closed: code=%d reached=%v", rec.Code, *reached)
	}
}

func TestAsrFormatWhitelist(t *testing.T) {
	for _, ok := range []string{"", "wav", "WAV", "mp3"} {
		if _, found := asrFilenames[strings.ToLower(strings.TrimSpace(ok))]; !found {
			t.Fatalf("白名单应包含 %q", ok)
		}
	}
	for _, bad := range []string{"../../etc/passwd", "wav;rm -rf /", "exe"} {
		if _, found := asrFilenames[strings.ToLower(strings.TrimSpace(bad))]; found {
			t.Fatalf("白名单不应包含 %q", bad)
		}
	}
}

// 令牌文件与配置同目录，确认默认路径不会被拼到别处。
func TestTokenPathInsideDataDir(t *testing.T) {
	dir := t.TempDir()
	t.Setenv(tokenEnv, "")
	_, path, err := loadOrCreateToken(dir)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Dir(path) != dir {
		t.Fatalf("令牌文件应在数据目录内: %s", path)
	}
}

func newTestApp(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Default()
	cfg.APIKey = "sk-test"
	cfg.Port = "8080"
	return &App{
		cfg:      cfg,
		cfgPath:  filepath.Join(dir, "config.json"),
		dir:      dir,
		apiToken: strings.Repeat("b", 64),
		port:     cfg.Port,
	}
}

func postJSON(t *testing.T, h http.HandlerFunc, path, body string) (int, string) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8080"+path, strings.NewReader(body))
	req.Host = "127.0.0.1:8080"
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(tokenHeader, strings.Repeat("b", 64))
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec.Code, rec.Body.String()
}

// 任务在运行时，第二个请求必须是 409，且不得改写当前任务与日志。
// （并发窗口本身由 CompareAndSwap 关闭，见 main.go handleTask。）
func TestHandleTaskRejectsWhileRunning(t *testing.T) {
	app := newTestApp(t)
	app.running.Store(true)
	app.task = "正在跑的任务"
	app.logs = []string{"[00:00:00] 已有日志"}

	code, body := postJSON(t, app.handleTask, "/api/task", `{"task":"插队的任务"}`)
	if code != http.StatusConflict {
		t.Fatalf("运行中应返回 409，实为 %d (%s)", code, body)
	}
	if app.task != "正在跑的任务" || len(app.logs) != 1 {
		t.Fatalf("被拒的请求改写了运行状态: task=%q logs=%v", app.task, app.logs)
	}
}

// 未配置 API Key 时 fail-fast，不占用运行位（否则会出现「启动成功但立刻失败」）。
func TestHandleTaskRequiresAPIKey(t *testing.T) {
	app := newTestApp(t)
	app.cfg.APIKey = ""
	code, _ := postJSON(t, app.handleTask, "/api/task", `{"task":"打开设置"}`)
	if code != http.StatusConflict {
		t.Fatalf("缺少 Key 应拒绝启动，实为 %d", code)
	}
	if app.running.Load() {
		t.Fatal("被拒的请求不应占住运行位")
	}
}

func TestHandleTaskRejectsEmptyTask(t *testing.T) {
	app := newTestApp(t)
	if code, _ := postJSON(t, app.handleTask, "/api/task", `{"task":"   "}`); code != http.StatusBadRequest {
		t.Fatalf("空任务应 400，实为 %d", code)
	}
	if app.running.Load() {
		t.Fatal("空任务不应占住运行位")
	}
}

// 配置保存：非法值必须 400，且不落盘、不改内存（避免界面与磁盘不一致）。
func TestHandleSetConfigRejectsAndRollsBack(t *testing.T) {
	app := newTestApp(t)
	before := *app.cfg
	code, _ := postJSON(t, app.handleSetConfig, "/api/config",
		`{"base_url":"http://evil.example.com/v1","model":"glm-4.6"}`)
	if code != http.StatusBadRequest {
		t.Fatalf("非法 base_url 应 400，实为 %d", code)
	}
	if app.cfg.BaseURL != before.BaseURL || app.cfg.Model != before.Model {
		t.Fatalf("被拒的保存改动了内存配置: %+v", app.cfg)
	}
	if _, err := os.Stat(app.cfgPath); !os.IsNotExist(err) {
		t.Fatalf("被拒的保存不应落盘: %v", err)
	}
}

// 合法保存：哨兵保留原 Key，其他字段落盘。
func TestHandleSetConfigAppliesAndPersists(t *testing.T) {
	app := newTestApp(t)
	code, body := postJSON(t, app.handleSetConfig, "/api/config",
		`{"base_url":"https://open.bigmodel.cn/api/paas/v4","api_key":"`+config.MaskSentinel+
			`","model":"glm-4v","max_steps":30}`)
	if code != http.StatusOK {
		t.Fatalf("保存应成功，实为 %d (%s)", code, body)
	}
	if app.cfg.APIKey != "sk-test" || app.cfg.Model != "glm-4v" || app.cfg.MaxSteps != 30 {
		t.Fatalf("保存结果不对: %+v", app.cfg)
	}
	saved, err := config.Load(app.cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if saved.APIKey != "sk-test" || saved.Model != "glm-4v" {
		t.Fatalf("落盘内容不对: %+v", saved)
	}
}

// 停止请求必须立刻取消任务上下文——只置标志位时，任务要等最长 180 秒
// 的读超时才停（手册 §15.2-2）。
func TestHandleStopCancelsTaskContext(t *testing.T) {
	app := newTestApp(t)
	ctx, cancel := context.WithCancel(context.Background())
	app.taskCancel = cancel
	app.running.Store(true)

	if code, body := postJSON(t, app.handleStop, "/api/stop", ""); code != http.StatusOK {
		t.Fatalf("停止应返回 200，实为 %d (%s)", code, body)
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("停止请求没有取消任务上下文")
	}
	if !app.stopped.Load() {
		t.Fatal("停止请求应同时置位 stopped 标志")
	}
}

// 没有任务在跑时，停止请求也不应 panic。
func TestHandleStopWithoutTask(t *testing.T) {
	app := newTestApp(t)
	if code, _ := postJSON(t, app.handleStop, "/api/stop", ""); code != http.StatusOK {
		t.Fatalf("空转停止应返回 200，实为 %d", code)
	}
}
