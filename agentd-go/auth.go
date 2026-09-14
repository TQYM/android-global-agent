// SPDX-License-Identifier: Apache-2.0

// 本地 API 的鉴权与请求校验。
//
// 为什么需要（2026-09-14 手册 §15.1 P0-1）：agentd 以 root 运行，
// 且 Android 的 loopback 不是应用级安全边界——同机任意 App 都能访问
// 本机端口。此前 `ListenAndServe(":"+port)` 更是绑定**所有网卡**，
// 局域网内任何设备都能直连 `/api/config` 拿到 API Key、读屏、提交任务。
//
// 本文件把三个边界显式化：
//  1. 监听地址收敛到 127.0.0.1（见 main.go）；
//  2. 每个 `/api/*` 都要带随机令牌（启动时生成，0600 落盘，不进页面）；
//  3. Host / Origin / 方法 / Content-Type 一律白名单校验——挡掉 DNS
//     重绑定（Host 被换域名）与浏览器 CSRF（页面被指向本机端口）。
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

const (
	// apiTokenFile 是访问令牌的落盘文件名（位于 agentd 数据目录）。
	apiTokenFile = "api.token"
	// tokenHeader 是首选令牌头；`Authorization: Bearer <token>` 同样接受，
	// 便于 curl / 脚本按通用习惯调用。
	tokenHeader = "X-Agentd-Token"
	// tokenEnv 允许外部（部署脚本、测试）注入令牌，跳过文件生成。
	tokenEnv = "AGENTD_TOKEN"
	// minTokenLen 是令牌最小长度（hex(32 字节) = 64 字符）。
	minTokenLen = 32
	// maskSentinel 是「保持原 API Key 不变」的显式哨兵。
	//
	// 此前用 `strings.Contains(in.APIKey, "...")` 判断，会把真实 Key 里
	// 恰好含 "..." 的输入误判成掩码（手册 §15.2-4），一个固定哨兵既
	// 不会误判，也让「保留」变成调用方的显式意图。
	maskSentinel = "__AGENTD_KEEP__"
	localHostIP  = "127.0.0.1"
)

// loadOrCreateToken 返回本机 API 令牌。
//
// 顺序：环境变量 → 已有令牌文件 → 新生成。文件权限 0600：令牌本身不是
// 长期凭据（重启可轮换），但它等价于「本机 root 能力的遥控器」，不该
// 让同机其他 App 直接读到。
func loadOrCreateToken(dir string) (token string, path string, err error) {
	path = filepath.Join(dir, apiTokenFile)
	if env := strings.TrimSpace(os.Getenv(tokenEnv)); len(env) >= minTokenLen {
		return env, path, nil
	}
	if data, rerr := os.ReadFile(path); rerr == nil {
		if t := strings.TrimSpace(string(data)); len(t) >= minTokenLen {
			return t, path, nil
		}
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", path, fmt.Errorf("生成令牌失败: %w", err)
	}
	token = hex.EncodeToString(raw)
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		return "", path, fmt.Errorf("写入令牌文件失败: %w", err)
	}
	// 已存在的文件可能被放宽过权限（例如上一版实现），显式收紧。
	_ = os.Chmod(path, 0o600)
	return token, path, nil
}

// guard 是 `/api/*` 的统一守门人。
type guard struct {
	token string
	// port 是监听端口，用于 Host / Origin 白名单比对。
	port string
}

// protect 包装一个处理器：先做方法、来源与令牌校验，全部通过才进入业务逻辑。
func (g *guard) protect(methods []string, handler http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := g.checkHost(r); err != nil {
			http.Error(w, err.Error(), http.StatusForbidden)
			return
		}
		if err := g.checkOrigin(r); err != nil {
			http.Error(w, err.Error(), http.StatusForbidden)
			return
		}
		if !methodAllowed(r.Method, methods) {
			w.Header().Set("Allow", strings.Join(methods, ", "))
			http.Error(w, "方法不允许: "+r.Method, http.StatusMethodNotAllowed)
			return
		}
		// 携带请求体的方法必须是 JSON：表单/纯文本提交一律拒绝，
		// 顺带挡住浏览器 <form> 与简单请求 CSRF。
		//
		// 只对**真的有 body**的请求要求：/api/stop 这类无参数的 POST 不带
		// Content-Type 是正常的，一律要求会让「停止」按钮直接 415。
		if r.Method == http.MethodPost && hasBody(r) && !isJSONContentType(r.Header.Get("Content-Type")) {
			http.Error(w, "Content-Type 必须是 application/json", http.StatusUnsupportedMediaType)
			return
		}
		if !g.tokenOK(r) {
			// 不回显期望值，也不区分「没带」与「带错」，避免成为探测信号。
			w.Header().Set("WWW-Authenticate", tokenHeader)
			http.Error(w, "缺少或错误的访问令牌", http.StatusUnauthorized)
			return
		}
		handler(w, r)
	}
}

func methodAllowed(method string, allowed []string) bool {
	for _, m := range allowed {
		if method == m {
			return true
		}
	}
	return false
}

func isJSONContentType(ct string) bool {
	if i := strings.IndexByte(ct, ';'); i >= 0 {
		ct = ct[:i]
	}
	return strings.EqualFold(strings.TrimSpace(ct), "application/json")
}

// hasBody 判断请求是否真的带请求体（/api/stop 这类空 POST 不带 Content-Type）。
func hasBody(r *http.Request) bool {
	return r.ContentLength > 0 || len(r.TransferEncoding) > 0
}

// tokenOK 做常量时间比较，避免通过响应时间逐字节试探令牌。
func (g *guard) tokenOK(r *http.Request) bool {
	if g.token == "" {
		return false
	}
	got := r.Header.Get(tokenHeader)
	if got == "" {
		if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
			got = strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
		}
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(g.token)) == 1
}

// checkHost 校验 Host 头指向本机回环。
//
// 没有这一步时，攻击者可以让受害者浏览器把 evil.com 解析到 127.0.0.1
// （DNS 重绑定），从而以同源身份调用本机 API。
func (g *guard) checkHost(r *http.Request) error {
	host := r.Host
	if host == "" {
		return errors.New("缺少 Host 头")
	}
	name, port, err := net.SplitHostPort(host)
	if err != nil {
		// 没有端口（:80/:443 默认端口被省略）时按整体当主机名处理。
		name, port = host, ""
	}
	if !isLoopbackName(name) {
		return fmt.Errorf("Host 必须是本机回环，收到 %q", name)
	}
	if port != "" && g.port != "" && port != g.port {
		return fmt.Errorf("Host 端口不匹配，收到 %q", port)
	}
	return nil
}

// checkOrigin 校验浏览器来源。同源页面（WebUI 自身）带 Origin 时放行，
// 其他来源一律拒绝；非浏览器调用（curl）不带 Origin，不受影响。
func (g *guard) checkOrigin(r *http.Request) error {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return nil
	}
	if origin == "null" {
		return errors.New("拒绝不透明来源（Origin: null）")
	}
	u, err := url.Parse(origin)
	if err != nil {
		return errors.New("Origin 非法")
	}
	if u.Scheme != "http" || !isLoopbackName(u.Hostname()) {
		return fmt.Errorf("Origin 不是本机 WebUI: %s", origin)
	}
	if p := u.Port(); p != "" && g.port != "" && p != g.port {
		return fmt.Errorf("Origin 端口不匹配: %s", origin)
	}
	return nil
}

func isLoopbackName(name string) bool {
	name = strings.Trim(name, "[]")
	if strings.EqualFold(name, "localhost") {
		return true
	}
	if ip := net.ParseIP(name); ip != nil {
		return ip.IsLoopback()
	}
	return false
}
