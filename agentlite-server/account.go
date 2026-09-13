// SPDX-License-Identifier: Apache-2.0
//
// AgentLite 同步服务 — 账号体系与局域网会合
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
	AccountID   string    `json:"account_id"`
	Name        string    `json:"name"`
	KeyHash     string    `json:"key_hash"` // base64(pbkdf2)
	KeySalt     string    `json:"key_salt"` // base64
	KeyIter     int       `json:"key_iter"`
	TokenHash   string    `json:"token_hash"`
	CreatedAt   time.Time `json:"created_at"`
	LastLoginAt time.Time `json:"last_login_at"`
	Disabled    bool      `json:"disabled"`

	// 额度（units = 1000 tokens 的记账单位由网关定义，见 quota.go）
	Quota     int64 `json:"quota"`
	QuotaUsed int64 `json:"quota_used"`
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
		if a.TokenHash != "" {
			s.acctByToken[a.TokenHash] = a.AccountID
		}
	}
	return nil
}

// saveAccountsLocked 原子重写账号表。调用方须持写锁。
func (s *Store) saveAccountsLocked() error {
	list := make([]Account, 0, len(s.accounts))
	for _, a := range s.accounts {
		list = append(list, *a)
	}
	raw, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(s.accountsPath(), raw, 0o600)
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
		TokenHash:   hashToken(token),
		CreatedAt:   now,
		LastLoginAt: now,
		Quota:       newAccountGrant, // 新账号赠送额度，便于开箱试用
	}
	s.accounts[id] = a
	s.acctByName[n] = id
	s.acctByToken[a.TokenHash] = id
	if err := s.saveAccountsLocked(); err != nil {
		return nil, "", err
	}
	cp := *a
	return &cp, token, nil
}

// LoginAccount 校验凭据并轮换令牌（旧令牌立即失效）。
func (s *Store) LoginAccount(name, key string) (*Account, string, error) {
	n, err := normalizeAccountName(name)
	if err != nil {
		// 名字不合法与凭据错误统一返回，避免账号枚举
		return nil, "", ErrBadCredentials
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	id, ok := s.acctByName[n]
	if !ok {
		// 抵御账号枚举：仍做一次等价开销的派生
		salt := make([]byte, acctSaltLen)
		_, _ = hashAccountKey(key, salt, acctKeyIterations)
		return nil, "", ErrBadCredentials
	}
	a := s.accounts[id]
	salt, err := base64.StdEncoding.DecodeString(a.KeySalt)
	if err != nil {
		return nil, "", ErrBadCredentials
	}
	iter := a.KeyIter
	if iter <= 0 {
		iter = acctKeyIterations
	}
	got, err := hashAccountKey(key, salt, iter)
	if err != nil {
		return nil, "", ErrBadCredentials
	}
	want, err := base64.StdEncoding.DecodeString(a.KeyHash)
	if err != nil {
		return nil, "", ErrBadCredentials
	}
	if subtle.ConstantTimeCompare(got, want) != 1 {
		return nil, "", ErrBadCredentials
	}
	if a.Disabled {
		return nil, "", ErrAccountDisabled
	}

	token, err := randomB64(32)
	if err != nil {
		return nil, "", err
	}
	if a.TokenHash != "" {
		delete(s.acctByToken, a.TokenHash)
	}
	a.TokenHash = hashToken(token)
	a.LastLoginAt = time.Now().UTC()
	s.acctByToken[a.TokenHash] = a.AccountID
	if err := s.saveAccountsLocked(); err != nil {
		return nil, "", err
	}
	cp := *a
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
	a.Quota += units
	a.QuotaUsed += used
	return s.saveAccountsLocked()
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
	d.LANIPs = clean
	d.LANPort = port
	d.PublicIP = publicIP
	d.LANSeenAt = time.Now().UTC()
	return s.saveDevicesLocked()
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
		out = append(out, cp)
	}
	return out
}
