// agentd is a device-resident Android UI agent with a local WebUI.
// Started as root, it drives the phone through uiautomator/screencap/input
// and calls a user-configured LLM API for decisions. No PC required.
package main

import (
	"embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"agentd/internal/agent"
	"agentd/internal/config"
	"agentd/internal/device"
	"agentd/internal/llm"
	"agentd/internal/semantics"
)

//go:embed web/index.html
var webFS embed.FS

const defaultDataDir = "/data/local/tmp/agentd"

// Android (bionic) has no /etc/ssl/certs; point Go's x509 at the system CA
// stores before any TLS handshake happens. Android 14+ keeps user-visible
// roots in the conscrypt APEX; older trees use /system/etc/security/cacerts.
func init() {
	_ = os.Setenv("SSL_CERT_DIR",
		"/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts")
}

type App struct {
	mu     sync.Mutex
	cfg    *config.Config
	cfgPath string
	dir    string

	running   atomic.Bool
	stopped   atomic.Bool
	task      string
	lastStep  int
	lastAction string
	lastNodes []semantics.Node
	logs      []string
}

func (a *App) logf(format string, args ...interface{}) {
	a.mu.Lock()
	defer a.mu.Unlock()
	line := fmt.Sprintf("[%s] %s", time.Now().Format("15:04:05"),
		fmt.Sprintf(format, args...))
	a.logs = append(a.logs, line)
	if len(a.logs) > 500 {
		a.logs = a.logs[len(a.logs)-500:]
	}
	fmt.Println(line)
}

func (a *App) paths() (string, string, string) {
	return filepath.Join(a.dir, "config.json"),
		filepath.Join(a.dir, "ui.xml"),
		filepath.Join(a.dir, "screen.png")
}

func maskKey(k string) string {
	if len(k) <= 8 {
		return "***"
	}
	return k[:4] + "..." + k[len(k)-4:]
}

// ---- HTTP handlers ----

func (a *App) handleIndex(w http.ResponseWriter, r *http.Request) {
	data, _ := webFS.ReadFile("web/index.html")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write(data)
}

func (a *App) handleGetConfig(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	masked := *a.cfg
	masked.APIKey = maskKey(a.cfg.APIKey)
	writeJSON(w, masked)
}

func (a *App) handleSetConfig(w http.ResponseWriter, r *http.Request) {
	var in config.Config
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "bad json: "+err.Error(), 400)
		return
	}
	a.mu.Lock()
	// preserve key when the masked placeholder is submitted unchanged
	if strings.Contains(in.APIKey, "...") && a.cfg != nil {
		in.APIKey = a.cfg.APIKey
	}
	if in.BaseURL == "" {
		in.BaseURL = "https://open.bigmodel.cn/api/paas/v4"
	}
	if in.Model == "" {
		in.Model = "glm-4.6"
	}
	if in.Port == "" {
		in.Port = a.cfg.Port
	}
	if in.MaxSteps <= 0 {
		in.MaxSteps = a.cfg.MaxSteps
	}
	if in.SystemPrompt == "" {
		in.SystemPrompt = a.cfg.SystemPrompt
	}
	a.cfg = &in
	cfgPath, _, _ := a.paths()
	a.mu.Unlock()

	if err := a.cfg.Save(cfgPath); err != nil {
		a.logf("保存配置失败: %v", err)
		http.Error(w, "save failed: "+err.Error(), 500)
		return
	}
	a.logf("配置已保存 (model=%s base=%s)", a.cfg.Model, a.cfg.BaseURL)
	writeJSON(w, map[string]string{"status": "ok"})
}

func (a *App) handleTask(w http.ResponseWriter, r *http.Request) {
	if a.running.Load() {
		http.Error(w, "已有任务在运行", 409)
		return
	}
	var in struct {
		Task string `json:"task"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Task) == "" {
		http.Error(w, "task 字段不能为空", 400)
		return
	}
	task := strings.TrimSpace(in.Task)

	a.mu.Lock()
	cfg := *a.cfg
	a.mu.Unlock()
	_, dumpPath, screenPath := a.paths()

	client := &llm.Client{BaseURL: cfg.BaseURL, APIKey: cfg.APIKey, Model: cfg.Model}
	runner := &agent.Runner{
		Cfg:        &cfg,
		DumpPath:   dumpPath,
		ScreenPath: screenPath,
		Client:     client,
		Stopped:    &a.stopped,
		Log:        func(s string) { a.logf("%s", s) },
		OnStep: func(step int, action string, nodes []semantics.Node) {
			a.mu.Lock()
			a.lastStep = step
			a.lastAction = action
			a.lastNodes = nodes
			a.mu.Unlock()
		},
	}

	a.running.Store(true)
	a.stopped.Store(false)
	a.mu.Lock()
	a.task = task
	a.logs = nil
	a.mu.Unlock()
	a.logf("任务启动: %s (model=%s)", task, cfg.Model)

	go func() {
		defer a.running.Store(false)
		summary, err := runner.Run(task)
		if err != nil {
			a.logf("任务失败: %v", err)
		} else {
			a.logf("任务完成: %s", summary)
		}
	}()
	writeJSON(w, map[string]string{"status": "started"})
}

func (a *App) handleStop(w http.ResponseWriter, r *http.Request) {
	a.stopped.Store(true)
	a.logf("收到停止请求")
	writeJSON(w, map[string]string{"status": "stopping"})
}

func (a *App) handleStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	writeJSON(w, map[string]interface{}{
		"running":    a.running.Load(),
		"task":       a.task,
		"step":       a.lastStep,
		"last_action": a.lastAction,
		"logs":       a.logs,
		"nodes":      a.lastNodes,
	})
}

func (a *App) handleScreen(w http.ResponseWriter, r *http.Request) {
	_, _, screenPath := a.paths()
	data, err := os.ReadFile(screenPath)
	if err != nil {
		http.Error(w, "no screenshot yet", 404)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	_, _ = w.Write(data)
}

func (a *App) handleTest(w http.ResponseWriter, r *http.Request) {
	// quick self-check of perception + control without an LLM call
	_, dumpPath, screenPath := a.paths()
	xmlText, err := device.DumpUI(dumpPath)
	if err != nil {
		writeJSON(w, map[string]interface{}{"ok": false, "error": err.Error()})
		return
	}
	nodes, _ := semantics.Parse(xmlText)
	_ = device.Screenshot(screenPath)
	writeJSON(w, map[string]interface{}{"ok": true, "nodes": len(nodes)})
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

func main() {
	dir := os.Getenv("AGENTD_DIR")
	if dir == "" {
		dir = defaultDataDir
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "mkdir %s: %v\n", dir, err)
		os.Exit(1)
	}
	cfgPath := filepath.Join(dir, "config.json")
	cfg, err := config.Load(cfgPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "load config: %v\n", err)
		os.Exit(1)
	}

	app := &App{cfg: cfg, cfgPath: cfgPath, dir: dir}
	if err := app.cfg.Save(cfgPath); err != nil {
		fmt.Fprintf(os.Stderr, "init config: %v\n", err)
		os.Exit(1)
	}

	// Enable the bundled AccessibilityService APK (first-class perception
	// channel); non-fatal — uiautomator remains the fallback.
	if err := device.EnsureA11yService(); err != nil {
		fmt.Printf("a11y service enable failed: %v\n", err)
	} else {
		fmt.Println("a11y service ensured (com.dsh.agentd/.AgentA11yService)")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", app.handleIndex)
	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			app.handleSetConfig(w, r)
			return
		}
		app.handleGetConfig(w, r)
	})
	mux.HandleFunc("/api/task", app.handleTask)
	mux.HandleFunc("/api/stop", app.handleStop)
	mux.HandleFunc("/api/status", app.handleStatus)
	mux.HandleFunc("/api/screen", app.handleScreen)
	mux.HandleFunc("/api/test", app.handleTest)

	addr := ":" + cfg.Port
	fmt.Printf("agentd listening on http://127.0.0.1%s  (data dir %s)\n", addr, dir)
	app.logf("agentd 已启动，端口 %s", cfg.Port)
	if err := http.ListenAndServe(addr, mux); err != nil {
		fmt.Fprintf(os.Stderr, "server: %v\n", err)
		os.Exit(1)
	}
}
