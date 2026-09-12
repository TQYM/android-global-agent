// SPDX-License-Identifier: Apache-2.0
//
// # AgentLite 同步服务 — 入口
//
// 面向 AgentLite Android 客户端（com.dsh.agent）的远程信息同步服务：
//   - 设备注册换取 Bearer 令牌（明文令牌只在注册响应里返回一次）
//   - 任务历史 / 记忆 / 设备状态 增量上行（按 id 幂等）
//   - 远程配置下发（服务端改 config.json 即时生效）
//
// 用法见 README.md；systemd 单元见 deploy/agentlite-server.service。
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

const version = "0.1.0"

type server struct {
	store      *Store
	rl         *rateLimiter
	started    time.Time
	adminToken string // 发放兑换码用的管理令牌；空 = 管理接口关闭

	// 额度网关（见 gateway.go）：账号额度在此被真实消费
	gateway *gatewayConfig
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	var (
		addr     = flag.String("addr", envOr("AGENTLITE_ADDR", "0.0.0.0:18443"), "监听地址")
		dataDir  = flag.String("data", envOr("AGENTLITE_DATA", "/var/lib/agentlite-server"), "数据目录")
		certFile = flag.String("cert", envOr("AGENTLITE_CERT", "/srv/agentlite-server/tls/server.crt"), "TLS 证书")
		keyFile  = flag.String("key", envOr("AGENTLITE_KEY", "/srv/agentlite-server/tls/server.key"), "TLS 私钥")
		showVer  = flag.Bool("version", false, "打印版本后退出")
		rlMax    = flag.Int("register-rate", 10, "每 IP 在注册窗口内允许的注册次数")
		rlWindow = flag.Duration("register-window", 10*time.Minute, "注册限流窗口")
		adminTok = flag.String("admin-token", envOr("AGENTLITE_ADMIN_TOKEN", ""),
			"管理令牌（发放兑换码用）；留空则管理接口关闭")
		upstreamURL = flag.String("upstream-url",
			envOr("AGENTLITE_UPSTREAM_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
			"额度网关的上游 OpenAI 兼容端点")
		upstreamKey = flag.String("upstream-key", envOr("AGENTLITE_UPSTREAM_KEY", ""),
			"上游 API Key（放 0600 env 文件，勿写入单元文件）；留空则网关关闭")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("agentlite-server %s\n", version)
		return
	}

	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetPrefix("[agentlite] ")

	store, err := NewStore(*dataDir)
	if err != nil {
		log.Fatalf("初始化存储失败: %v", err)
	}

	s := &server{
		store:      store,
		rl:         newRateLimiter(*rlMax, *rlWindow),
		started:    time.Now(),
		adminToken: *adminTok,
		gateway:    newGateway(*upstreamURL, *upstreamKey),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.wrap(s.handleHealth))

	// 账号体系
	mux.HandleFunc("POST /api/v1/account/register", s.wrap(s.handleAccountRegister))
	mux.HandleFunc("POST /api/v1/account/login", s.wrap(s.handleAccountLogin))
	mux.HandleFunc("GET /api/v1/account/me", s.wrap(s.authMW(s.handleAccountMe)))

	// 设备绑定与信息同步（X-Device-Id 标识来源设备）
	mux.HandleFunc("POST /api/v1/device/bind", s.wrap(s.authMW(s.handleDeviceBind)))
	mux.HandleFunc("POST /api/v1/sync/push", s.wrap(s.authDeviceMW(s.handlePush)))
	mux.HandleFunc("GET /api/v1/sync/pull", s.wrap(s.authDeviceMW(s.handlePull)))
	mux.HandleFunc("GET /api/v1/config", s.wrap(s.authMW(s.handleConfig)))

	// 套餐与订单（应用内买单：套餐展示 → 下单 → 兑换码支付）
	mux.HandleFunc("GET /api/v1/packs", s.wrap(s.authMW(s.handlePacks)))
	mux.HandleFunc("POST /api/v1/orders", s.wrap(s.authMW(s.handleCreateOrder)))
	mux.HandleFunc("GET /api/v1/orders", s.wrap(s.authMW(s.handleListOrders)))
	mux.HandleFunc("POST /api/v1/orders/cancel", s.wrap(s.authMW(s.handleCancelOrder)))
	mux.HandleFunc("POST /api/v1/orders/pay", s.wrap(s.authMW(s.handlePayOrder)))

	// 跨设备指令（设备 A 让同账号的设备 B 做事）
	mux.HandleFunc("POST /api/v1/device/command", s.wrap(s.authDeviceMW(s.handleCreateCommand)))
	mux.HandleFunc("GET /api/v1/device/commands", s.wrap(s.authDeviceMW(s.handleListCommands)))
	mux.HandleFunc("POST /api/v1/device/command/ack", s.wrap(s.authDeviceMW(s.handleAckCommand)))
	mux.HandleFunc("GET /api/v1/device/command", s.wrap(s.authMW(s.handleGetCommand)))

	// 局域网会合（真正的数据在设备间直连，不过服务器）
	mux.HandleFunc("POST /api/v1/lan/rendezvous", s.wrap(s.authDeviceMW(s.handleRendezvous)))

	// 额度与兑换码
	mux.HandleFunc("POST /api/v1/redeem", s.wrap(s.authMW(s.handleRedeem)))
	mux.HandleFunc("POST /api/v1/admin/codes", s.wrap(s.adminMW(s.handleAdminCreateCodes)))

	// 额度网关（OpenAI 兼容）：api_key 直接用账号令牌，额度不足返回 402
	mux.HandleFunc("POST /v1/chat/completions", s.wrap(s.authMW(s.handleChatCompletions)))
	mux.HandleFunc("GET /v1/models", s.wrap(s.authMW(s.handleModels)))

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeErr(w, http.StatusNotFound, "not_found", "未知接口: "+r.URL.Path)
	})

	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    16 << 10,
		TLSConfig: &tls.Config{
			MinVersion: tls.VersionTLS12,
			// 自签证书 + App 端 pin：不依赖系统 CA，也不做 OCSP
			CurvePreferences: []tls.CurveID{tls.X25519, tls.CurveP256},
		},
	}

	// 证书可用性提前校验，避免 systemd 反复重启才发现问题
	if _, err := tls.LoadX509KeyPair(*certFile, *keyFile); err != nil {
		log.Fatalf("加载 TLS 证书失败 (%s): %v", *certFile, err)
	}

	idle := make(chan struct{})
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		got := <-sig
		log.Printf("收到信号 %s，开始优雅退出…", got)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
		close(idle)
	}()

	log.Printf("agentlite-sync %s 启动，监听 https://%s（数据目录 %s）", version, *addr, *dataDir)
	if s.gateway.enabled() {
		log.Printf("额度网关已启用 → %s", s.gateway.baseURL)
	} else {
		log.Printf("额度网关未启用（未配置 -upstream-key）")
	}
	if err := srv.ListenAndServeTLS(*certFile, *keyFile); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("服务异常退出: %v", err)
	}
	<-idle
	log.Printf("已退出")
}

// wrap 组合日志与 panic 恢复中间件。
func (s *server) wrap(h http.HandlerFunc) http.HandlerFunc {
	return s.logMW(s.recoverMW(h))
}

// ---------- 日志 ----------

func logInfo(format string, args ...any)  { log.Printf(format, args...) }
func logError(format string, args ...any) { log.Printf("ERROR "+format, args...) }

// ---------- 限流 ----------

// rateLimiter 是按 key（此处为客户端 IP）的滑动窗口计数器。
// 注册接口是唯一无需令牌即可调用的写接口，必须限流。
type rateLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	window time.Duration
	max    int
	lastGC time.Time
}

func newRateLimiter(max int, window time.Duration) *rateLimiter {
	return &rateLimiter{
		hits:   map[string][]time.Time{},
		window: window,
		max:    max,
		lastGC: time.Now(),
	}
}

func (rl *rateLimiter) allow(key string) bool {
	now := time.Now()
	cutoff := now.Add(-rl.window)

	rl.mu.Lock()
	defer rl.mu.Unlock()

	// 惰性 GC：最多每分钟清一次，避免 map 无界增长
	if now.Sub(rl.lastGC) > time.Minute {
		for k, ts := range rl.hits {
			kept := ts[:0]
			for _, t := range ts {
				if t.After(cutoff) {
					kept = append(kept, t)
				}
			}
			if len(kept) == 0 {
				delete(rl.hits, k)
			} else {
				rl.hits[k] = kept
			}
		}
		rl.lastGC = now
	}

	kept := rl.hits[key][:0]
	for _, t := range rl.hits[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= rl.max {
		rl.hits[key] = kept
		return false
	}
	rl.hits[key] = append(kept, now)
	return true
}
