// SPDX-License-Identifier: Apache-2.0
//
// # AgentLite 同步服务 — 账号体系与局域网会合
//
// 账号模型：
//   - 账号名 + 账号密钥（本地账号，不涉及手机号/短信，因此无需备案与短信资质）
//   - 密钥只存 PBKDF2-HMAC-SHA256 派生值 + 随机盐；明文永不落盘
//   - 登录换取账号令牌（32 字节随机数），服务端只存 sha256
//   - 设备通过 X-Device-Id 归属到账号；同账号所有设备共享一条记录流
//
// 局域网会合：
//   - 服务端只做「介绍人」：按 (账号, 公网出口 IP) 分组，把同账号同出口的设备
//     互相告知 LAN 地址。真正的数据同步走设备间 P2P，不过服务器。
//   - **服务器不经手局域网对称密钥**：两端各自用账号密钥做 PBKDF2 派生
//     （参数见 LANKeyParams，客户端 Java 用 SecretKeyFactory 同参复现）。
//     因此即使服务器失陷，也无法解密局域网内的同步流量。
package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	acctKeyIterations = 210_000 // 账号密钥 PBKDF2 迭代数（服务端存哈希）
	acctKeyLen        = 32
	acctSaltLen       = 16

	// 局域网密钥派生参数：两端本地计算，服务端只负责公布参数保证一致。
	LANKeyIterations = 100_000
	LANKeyLen        = 32
	LANKeySaltPrefix = "agentlite-lan|"
)

var (
	ErrAccountExists   = errors.New("account name already taken")
	ErrBadCredentials  = errors.New("invalid account name or key")
	ErrBadAccountName  = errors.New("invalid account name")
	ErrBadAccountKey   = errors.New("invalid account key")
	ErrAccountDisabled = errors.New("account disabled")
)

// Account 是一个本地账号。
type Account struct {
	AccountID string `json:"account_id"`
	Name      string `json:"name"`
	KeyHash   string `json:"key_hash"` // base64(pbkdf2)
	KeySalt   string `json:"key_salt"` // base64
	KeyIter   int    `json:"key_iter"`
	// TokenHash 是**历史字段**：早期每次登录都换掉这一把唯一令牌，
	// 直接导致「同账号第二台设备登录把第一台踢下线」，而跨设备恰恰要求两台同时在线
	// （2026-09-15 真机实测：手机登录后手表一登录，手机立刻 401）。
	// 现在改为多令牌（Tokens）；此字段只在读取旧数据时用于迁移，写回时清空。
	TokenHash string `json:"token_hash,omitempty"`
	// Tokens 是该账号当前有效的所有令牌（每台设备登录一次追加一把）。
	Tokens      []AccountToken `json:"tokens,omitempty"`
	CreatedAt   time.Time      `json:"created_at"`
	LastLoginAt time.Time      `json:"last_login_at"`
	Disabled    bool           `json:"disabled"`

	// 额度（units = 1000 tokens 的记账单位由网关定义，见 quota.go）
	Quota     int64 `json:"quota"`
	QuotaUsed int64 `json:"quota_used"`
}

// AccountToken 一把账号令牌。多把并存 = 多台设备可同时在线（跨设备的前提）。
type AccountToken struct {
	Hash      string    `json:"hash"`
	CreatedAt time.Time `json:"created_at"`
}

// maxTokensPerAccount 单账号令牌上限：超出按创建时间淘汰最旧的一把
// （避免无限增长，同时满足正常用户的多设备场景）。
const maxTokensPerAccount = 10

// appendAccountToken 只修改待提交的账号副本；持久化成功才发布令牌索引。
func appendAccountToken(a *Account, token string) {
	a.Tokens = append(append([]AccountToken(nil), a.Tokens...), AccountToken{Hash: hashToken(token), CreatedAt: time.Now().UTC()})
	if len(a.Tokens) > maxTokensPerAccount {
		a.Tokens = a.Tokens[len(a.Tokens)-maxTokensPerAccount:]
	}
}

// LANKeyParams 公布给客户端的局域网密钥派生参数，避免两端参数漂移。
type LANKeyParams struct {
	Algorithm  string `json:"algorithm"`
	Iterations int    `json:"iterations"`
	KeyLen     int    `json:"key_len"`
	SaltPrefix string `json:"salt_prefix"`
}

func LANKeyParamsNow() LANKeyParams {
	return LANKeyParams{
		Algorithm:  "PBKDF2WithHmacSHA256",
		Iterations: LANKeyIterations,
		KeyLen:     LANKeyLen,
		SaltPrefix: LANKeySaltPrefix,
	}
}

// ---------- 校验 ----------

// normalizeAccountName 规范化并校验账号名：3-32 位小写字母/数字/_/-。
func normalizeAccountName(name string) (string, error) {
	n := strings.ToLower(strings.TrimSpace(name))
	if len(n) < 3 || len(n) > 32 {
		return "", fmt.Errorf("%w: 长度需 3-32", ErrBadAccountName)
	}
	for _, r := range n {
		ok := (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-'
		if !ok {
			return "", fmt.Errorf("%w: 只允许小写字母、数字、下划线、连字符", ErrBadAccountName)
		}
	}
	return n, nil
}

// validateAccountKey 账号密钥至少 8 位，不设字符集限制（但提示强度）。
func validateAccountKey(key string) error {
	if len(key) < 8 {
		return fmt.Errorf("%w: 至少 8 位", ErrBadAccountKey)
	}
	if len(key) > 256 {
		return fmt.Errorf("%w: 过长", ErrBadAccountKey)
	}
	return nil
}

func hashAccountKey(key string, salt []byte, iter int) ([]byte, error) {
	return pbkdf2.Key(sha256.New, key, salt, iter, acctKeyLen)
}

func randomB64(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func newAccountID() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "a_" + hex.EncodeToString(b), nil
}

// ---------- 账号持久化 ----------

func (s *Store) accountsPath() string { return filepath.Join(s.dir, "accounts.json") }

func (s *Store) loadAccounts() error {
	raw, err := os.ReadFile(s.accountsPath())
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var list []Account
	if err := json.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("accounts.json 损坏: %w", err)
	}
	for i := range list {
		a := list[i]
		s.accounts[a.AccountID] = &a
		s.acctByName[a.Name] = a.AccountID
		// 兼容旧数据：早期账号只有单把 TokenHash，读入时并入 Tokens
		if a.TokenHash != "" {
			a.Tokens = append(a.Tokens, AccountToken{Hash: a.TokenHash, CreatedAt: a.CreatedAt})
			a.TokenHash = ""
		}
		for _, tk := range a.Tokens {
			s.acctByToken[tk.Hash] = a.AccountID
		}
	}
	return nil
}

// ---------- 注册 / 登录 ----------

// RegisterAccount 创建账号并直接签发令牌。
func (s *Store) RegisterAccount(name, key string) (*Account, string, error) {
	n, err := normalizeAccountName(name)
	if err != nil {
		return nil, "", err
	}
	if err := validateAccountKey(key); err != nil {
		return nil, "", err
	}

	salt := make([]byte, acctSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return nil, "", err
	}
	hash, err := hashAccountKey(key, salt, acctKeyIterations)
	if err != nil {
		return nil, "", err
	}
	token, err := randomB64(32)
	if err != nil {
		return nil, "", err
	}
	id, err := newAccountID()
	if err != nil {
		return nil, "", err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.acctByName[n]; exists {
		return nil, "", ErrAccountExists
	}
	now := time.Now().UTC()
	a := &Account{
		AccountID:   id,
		Name:        n,
		KeyHash:     base64.StdEncoding.EncodeToString(hash),
		KeySalt:     base64.StdEncoding.EncodeToString(salt),
		KeyIter:     acctKeyIterations,
		CreatedAt:   now,
		LastLoginAt: now,
		Quota:       newAccountGrant, // 新账号赠送额度，便于开箱试用
	}
	appendAccountToken(a, token)
	if err := s.commitBillingLocked(billingTxn{Accounts: []Account{*a}}); err != nil {
		return nil, "", err
	}
	cp := *a
	return &cp, token, nil
}

// LoginAccount 在锁外校验密钥，提交时追加令牌并保留其他设备的令牌。
func (s *Store) LoginAccount(name, key string) (*Account, string, error) {
	n, err := normalizeAccountName(name)
	if err != nil {
		return nil, "", ErrBadCredentials
	}

	s.mu.RLock()
	id, ok := s.acctByName[n]
	var snapshot Account
	if a := s.accounts[id]; ok && a != nil {
		snapshot = *a
	} else {
		ok = false
	}
	s.mu.RUnlock()
	if !ok {
		// 未知账号仍做等价派生，但不阻塞全局存储锁。
		_, _ = hashAccountKey(key, make([]byte, acctSaltLen), acctKeyIterations)
		return nil, "", ErrBadCredentials
	}
	salt, err := base64.StdEncoding.DecodeString(snapshot.KeySalt)
	if err != nil {
		return nil, "", ErrBadCredentials
	}
	iter := snapshot.KeyIter
	if iter <= 0 {
		iter = acctKeyIterations
	}
	got, err := hashAccountKey(key, salt, iter)
	if err != nil {
		return nil, "", ErrBadCredentials
	}
	want, err := base64.StdEncoding.DecodeString(snapshot.KeyHash)
	if err != nil || subtle.ConstantTimeCompare(got, want) != 1 {
		return nil, "", ErrBadCredentials
	}
	token, err := randomB64(32)
	if err != nil {
		return nil, "", err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	a := s.accounts[id]
	// 派生期间凭据或归属变化时不签发令牌；禁用状态以提交时为准。
	if a == nil || s.acctByName[n] != id || a.KeyHash != snapshot.KeyHash || a.KeySalt != snapshot.KeySalt || a.KeyIter != snapshot.KeyIter {
		return nil, "", ErrBadCredentials
	}
	if a.Disabled {
		return nil, "", ErrAccountDisabled
	}
	// 从当前状态复制，保留派生期间其他登录/账务更新，避免用旧快照覆盖余额。
	cp := *a
	appendAccountToken(&cp, token)
	cp.LastLoginAt = time.Now().UTC()
	if err := s.commitBillingLocked(billingTxn{Accounts: []Account{cp}}); err != nil {
		return nil, "", err
	}
	return &cp, token, nil
}

// AccountByToken 用明文令牌换取账号。
func (s *Store) AccountByToken(token string) (*Account, error) {
	if token == "" {
		return nil, ErrNotFound
	}
	h := hashToken(token)
	s.mu.RLock()
	id, ok := s.acctByToken[h]
	var a *Account
	if ok {
		a = s.accounts[id]
	}
	if a != nil {
		cp := *a
		cp.Tokens = append([]AccountToken(nil), a.Tokens...)
		a = &cp
	}
	s.mu.RUnlock()
	if !ok || a == nil {
		return nil, ErrNotFound
	}
	if a.Disabled {
		return nil, ErrAccountDisabled
	}
	cp := *a
	return &cp, nil
}

// AddQuota 增加账号额度（兑换码核销后调用）。
func (s *Store) AddQuota(accountID string, units int64, used int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.accounts[accountID]
	if !ok {
		return ErrNotFound
	}
	next := *a
	next.Quota += units
	next.QuotaUsed += used
	return s.commitBillingLocked(billingTxn{Accounts: []Account{next}, Ledger: &Ledger{
		Time: time.Now().UTC(), AccountID: accountID, Delta: units, Reason: "adjust", Balance: next.Quota,
	}})
}

// ---------- 局域网会合 ----------

// LANPeer 是返回给客户端的同账号邻居。
type LANPeer struct {
	DeviceID string   `json:"device_id"`
	Name     string   `json:"name"`
	Model    string   `json:"model"`
	LANIPs   []string `json:"lan_ips"`
	LANPort  int      `json:"lan_port"`
	SeenSec  int64    `json:"seen_sec"` // 距上次上报秒数
}

// RecordLANPresence 记录设备本次上报的局域网位置。
func (s *Store) RecordLANPresence(deviceID, publicIP string, lanIPs []string, port int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[deviceID]
	if !ok {
		return ErrNotFound
	}
	clean := make([]string, 0, len(lanIPs))
	for _, ip := range lanIPs {
		ip = strings.TrimSpace(ip)
		if ip != "" && len(ip) <= 45 {
			clean = append(clean, ip)
		}
		if len(clean) >= 8 {
			break
		}
	}
	next := cloneDevice(d)
	next.LANIPs = clean
	next.LANPort = port
	next.PublicIP = publicIP
	next.LANSeenAt = time.Now().UTC()
	return s.commitDeviceLocked(&next)
}

// LANPeers 返回同账号、同公网出口、且最近仍活跃的其他设备。
//
// 「同一内网」判定用公网出口 IP：同 NAT 后的设备对外呈现同一个 IP。
// 这是纯服务端可观测的信号，不依赖客户端自报（自报可伪造）。
func (s *Store) LANPeers(accountID, publicIP, excludeDeviceID string) []LANPeer {
	const fresh = 3 * time.Minute

	s.mu.RLock()
	defer s.mu.RUnlock()

	now := time.Now().UTC()
	out := []LANPeer{}
	if publicIP == "" {
		return out
	}
	for _, d := range s.devices {
		if d.AccountID != accountID || d.DeviceID == excludeDeviceID || d.Disabled {
			continue
		}
		if d.PublicIP != publicIP || len(d.LANIPs) == 0 || d.LANPort == 0 {
			continue
		}
		if now.Sub(d.LANSeenAt) > fresh {
			continue
		}
		out = append(out, LANPeer{
			DeviceID: d.DeviceID,
			Name:     d.Name,
			Model:    d.Model,
			LANIPs:   append([]string{}, d.LANIPs...),
			LANPort:  d.LANPort,
			SeenSec:  int64(now.Sub(d.LANSeenAt).Seconds()),
		})
	}
	return out
}

// ValidOrigin 校验转发方声明的「来源设备」是否合法。
//
// 只在来源设备**确实属于同一账号**时采信，否则返回空串让调用方退回请求方自身。
// 刻意不报错：一个坏字段不该让整批同步失败，但也绝不采信越权来源。
func (s *Store) ValidOrigin(accountID, origin string) string {
	if origin == "" {
		return ""
	}
	s.mu.RLock()
	d, ok := s.devices[origin]
	s.mu.RUnlock()
	if !ok || d.AccountID != accountID || d.Disabled {
		logError("拒绝越权来源声明 account=%s origin=%s（设备不存在或不属于该账号）", accountID, origin)
		return ""
	}
	return origin
}

// DevicesOfAccount 返回账号下全部设备（客户端设备管理页用）。
func (s *Store) DevicesOfAccount(accountID string) []Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []Device{}
	for _, d := range s.devices {
		if d.AccountID == accountID {
			cp := *d
			cp.LegacyTokenHash = "" // 不外泄
			out = append(out, cp)
		}
	}
	return out
}

// Accounts 返回全部账号快照（供 /health 计数；不含密钥与令牌哈希）。
func (s *Store) Accounts() []Account {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Account, 0, len(s.accounts))
	for _, a := range s.accounts {
		cp := *a
		cp.KeyHash = ""
		cp.KeySalt = ""
		cp.TokenHash = ""
		cp.Tokens = nil
		out = append(out, cp)
	}
	return out
}
