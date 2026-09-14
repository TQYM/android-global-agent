// SPDX-License-Identifier: Apache-2.0

// agentd is a device-resident Android UI agent with a local WebUI.
// Started as root, it drives the phone through uiautomator/screencap/input
// and calls a user-configured LLM API for decisions. No PC required.
package main

import (
	"context"
	"embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
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
	mu      sync.Mutex
	cfg     *config.Config
	cfgPath string
	dir     string
	// apiToken 是 `/api/*` 的访问令牌（见 auth.go）。只有本机 root/shell
	// 读得到令牌文件，同机其他 App 拿不到。
	apiToken string
	// port 是启动时的监听端口，用于 Host/Origin 白名单比对。
	port string
	// taskCancel 取消当前任务的模型请求与循环（见 handleStop / Runner.Ctx）。
	taskCancel context.CancelFunc

	running    atomic.Bool
	stopped    atomic.Bool
	task       string
	lastStep   int
	lastAction string
	lastNodes  []semantics.Node
	lastTapX   int
	lastTapY   int
	lastTapAt  time.Time
	logs       []string
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

// asrFilenames 是 /api/asr 允许的音频格式白名单（format → multipart 文件名）。
//
// format 直接被拼进上传文件名，任意字符串等于把文件名与扩展名交给上游
// （手册 §15.2-5）；只认下面这几个明确值。
var asrFilenames = map[string]string{
	"":     "audio.wav",
	"wav":  "audio.wav",
	"mp3":  "audio.mp3",
	"m4a":  "audio.m4a",
	"webm": "audio.webm",
	"ogg":  "audio.ogg",
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
	// 真实 Key 不回流到浏览器：只回哨兵，提交哨兵即表示「保持不变」。
	// 这样即使有人在本机打开页面、或页面被别的进程读到，也拿不到 Key。
	if a.cfg.APIKey != "" {
		masked.APIKey = config.MaskSentinel
	}
	writeJSON(w, masked)
}

func (a *App) handleSetConfig(w http.ResponseWriter, r *http.Request) {
	var in config.Config
	// 限制请求体：配置项很小，超大 body 只可能是误用或攻击。
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&in); err != nil {
		http.Error(w, "bad json: "+err.Error(), 400)
		return
	}

	// 锁内合并成不可变快照：并发保存时，落盘的必须是本次提交的那一份，
	// 而不是「锁外再读 a.cfg」拿到的、可能已被另一个请求替换的对象。
	a.mu.Lock()
	merged := in.Merge(a.cfg)
	if err := merged.Validate(); err != nil {
		a.mu.Unlock()
		http.Error(w, "配置非法: "+err.Error(), http.StatusBadRequest)
		return
	}
	prev := a.cfg
	a.cfg = merged
	a.mu.Unlock()

	if err := merged.Save(a.cfgPath); err != nil {
		// 落盘失败就回滚内存，避免出现「界面显示新配置、磁盘还是旧配置」
		// 这种重启后行为突变的假象。
		a.mu.Lock()
		a.cfg = prev
		a.mu.Unlock()
		a.logf("保存配置失败: %v", err)
		http.Error(w, "save failed: "+err.Error(), 500)
		return
	}
	a.logf("配置已保存 (model=%s base=%s)", merged.Model, merged.BaseURL)
	if merged.Port != a.port {
		// 监听端口在启动时确定，热改配置不会换端口——说清楚，别让用户干等。
		a.logf("注意：port 变更（%s → %s）需重启 agentd 才生效", a.port, merged.Port)
	}
	writeJSON(w, map[string]string{"status": "ok"})
}

func (a *App) handleTask(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Task string `json:"task"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&in); err != nil || strings.TrimSpace(in.Task) == "" {
		http.Error(w, "task 字段不能为空", 400)
		return
	}
	task := strings.TrimSpace(in.Task)

	a.mu.Lock()
	cfg := *a.cfg
	a.mu.Unlock()
	if cfg.APIKey == "" {
		http.Error(w, "尚未配置 API Key（请先在配置区保存）", http.StatusConflict)
		return
	}
	_, dumpPath, screenPath := a.paths()

	// 任务级上下文：停止时取消它，进行中的模型请求会立刻断开，
	// 不必等最长 180 秒的读超时（手册 §15.2-2）。
	taskCtx, cancelTask := context.WithCancel(context.Background())

	client := &llm.Client{BaseURL: cfg.BaseURL, APIKey: cfg.APIKey, Model: cfg.Model}
	runner := &agent.Runner{
		Cfg:        &cfg,
		DumpPath:   dumpPath,
		ScreenPath: screenPath,
		Client:     client,
		Stopped:    &a.stopped,
		Ctx:        taskCtx,
		Log:        func(s string) { a.logf("%s", s) },
		OnStep: func(step int, action string, nodes []semantics.Node) {
			a.mu.Lock()
			a.lastStep = step
			a.lastAction = action
			a.lastNodes = nodes
			a.mu.Unlock()
		},
		OnTap: func(x, y int, label string) {
			a.mu.Lock()
			a.lastTapX, a.lastTapY, a.lastTapAt = x, y, time.Now()
			a.mu.Unlock()
		},
	}

	// 「检查 + 占位」必须是一步：先 Load 再 Store 之间存在窗口，两个并发
	// 请求会同时通过检查、同时启动 Runner，争用输入、截图与日志
	// （手册 §15.2-1）。CompareAndSwap 让只有一个请求能占住运行位。
	if !a.running.CompareAndSwap(false, true) {
		// 没拿到运行位就把刚建的 context 释放掉，别留下永不取消的句柄
		cancelTask()
		http.Error(w, "已有任务在运行", http.StatusConflict)
		return
	}
	// 占位成功之后不再有失败分支，运行位的释放统一交给 goroutine 的 defer。
	a.stopped.Store(false)
	a.mu.Lock()
	a.taskCancel = cancelTask
	a.task = task
	a.lastStep = 0
	a.lastAction = ""
	a.lastNodes = nil
	a.logs = nil
	a.mu.Unlock()
	a.logf("任务启动: %s (model=%s)", task, cfg.Model)

	go func() {
		defer a.running.Store(false)
		defer cancelTask()
		defer func() {
			a.mu.Lock()
			a.taskCancel = nil
			a.mu.Unlock()
		}()
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
	// 只置标志位时，任务要等当前模型请求读完（最长 180 秒）才停；
	// 取消 context 会让请求立刻断开（手册 §15.2-2）。
	a.mu.Lock()
	cancel := a.taskCancel
	a.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	a.logf("收到停止请求")
	writeJSON(w, map[string]string{"status": "stopping"})
}

func (a *App) handleStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	var tap interface{}
	if !a.lastTapAt.IsZero() && time.Since(a.lastTapAt) < 4*time.Second {
		tap = map[string]int{"x": a.lastTapX, "y": a.lastTapY}
	}
	writeJSON(w, map[string]interface{}{
		"running":     a.running.Load(),
		"task":        a.task,
		"step":        a.lastStep,
		"last_action": a.lastAction,
		"logs":        a.logs,
		"nodes":       a.lastNodes,
		"last_tap":    tap,
	})
}

// handleAsr accepts base64 audio from the WebUI mic and transcribes it via
// the configured ASR model, returning {text}.
func (a *App) handleAsr(w http.ResponseWriter, r *http.Request) {
	var in struct {
		AudioB64 string `json:"audio_b64"`
		Format   string `json:"format"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.AudioB64 == "" {
		http.Error(w, "audio_b64 字段不能为空", 400)
		return
	}
	raw, err := base64.StdEncoding.DecodeString(in.AudioB64)
	if err != nil {
		http.Error(w, "audio_b64 解码失败: "+err.Error(), 400)
		return
	}
	if len(raw) > 8<<20 {
		http.Error(w, "音频过大（上限 8MB）", 413)
		return
	}
	a.mu.Lock()
	cfg := *a.cfg
	a.mu.Unlock()
	filename, ok := asrFilenames[strings.ToLower(strings.TrimSpace(in.Format))]
	if !ok {
		http.Error(w, "format 仅支持 wav/mp3/m4a/webm/ogg", 400)
		return
	}
	client := &llm.Client{BaseURL: cfg.BaseURL, APIKey: cfg.APIKey, Model: cfg.Model}
	text, err := client.Transcribe(raw, filename, cfg.AsrModel)
	if err != nil {
		a.logf("语音识别失败: %v", err)
		http.Error(w, err.Error(), 502)
		return
	}
	a.logf("语音识别: %s", text)
	writeJSON(w, map[string]string{"text": text})
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

	// 配置来自设备本地文件，可能被手工改坏；先校验再进内存，
	// 免得非法 base_url / port 一路带到 root 进程的网络与监听逻辑里。
	if err := cfg.Validate(); err != nil {
		fmt.Fprintf(os.Stderr, "配置非法（%s）：%v\n", cfgPath, err)
		os.Exit(1)
	}

	token, tokenPath, err := loadOrCreateToken(dir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}

	app := &App{cfg: cfg, cfgPath: cfgPath, dir: dir, apiToken: token, port: cfg.Port}
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

	// 所有 `/api/*` 都过 guard：令牌 + 方法 + Host/Origin + Content-Type。
	g := &guard{token: app.apiToken, port: cfg.Port}
	mux := http.NewServeMux()
	mux.HandleFunc("/", app.handleIndex)
	mux.HandleFunc("/api/config", g.protect(
		[]string{http.MethodGet, http.MethodPost},
		func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodPost {
				app.handleSetConfig(w, r)
				return
			}
			app.handleGetConfig(w, r)
		}))
	mux.HandleFunc("/api/task", g.protect([]string{http.MethodPost}, app.handleTask))
	mux.HandleFunc("/api/stop", g.protect([]string{http.MethodPost}, app.handleStop))
	mux.HandleFunc("/api/status", g.protect([]string{http.MethodGet}, app.handleStatus))
	mux.HandleFunc("/api/screen", g.protect([]string{http.MethodGet}, app.handleScreen))
	// /api/test 会真的抓一次屏幕并 dump 节点树，属于有副作用的动作：只收 POST。
	mux.HandleFunc("/api/test", g.protect([]string{http.MethodPost}, app.handleTest))
	mux.HandleFunc("/api/asr", g.protect([]string{http.MethodPost}, app.handleAsr))

	// 显式绑定回环。此前的 `":"+port` 等价于 0.0.0.0——局域网内任何设备
	// 都能直连这个拥有 root 能力的 API（手册 §15.1 P0-1）。
	addr := net.JoinHostPort(localHostIP, cfg.Port)
	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	fmt.Printf("agentd listening on http://%s  (data dir %s)\n", addr, dir)
	fmt.Printf("WebUI 访问令牌：cat %s（0600，仅本机 root/shell 可读）\n", tokenPath)
	app.logf("agentd 已启动，端口 %s（仅回环 + 令牌鉴权）", cfg.Port)
	if err := srv.ListenAndServe(); err != nil {
		fmt.Fprintf(os.Stderr, "server: %v\n", err)
		os.Exit(1)
	}
}
