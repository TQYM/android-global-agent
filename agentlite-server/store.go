// SPDX-License-Identifier: Apache-2.0
//
// AgentLite 同步服务 — 存储层
//
// 设计约束（刻意为之）：
//   - 纯标准库，零第三方依赖（与 agentd-go / 无 Gradle 构建一脉相承）
//   - 不使用 SQLite：避免 cgo 与 GOPROXY 依赖；数据量级（单机数个账号）
//     用 append-only JSONL + 原子重写 JSON 完全够用
//   - **记录流按账号归档**，每条带来源 device_id：同账号所有设备共享一份信息，
//     同时保留「这条是谁产生的」溯源能力
package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	kindTask    = "task"
	kindMemory  = "memory"
	kindStatus  = "status"
	maxIDLen    = 128
	maxKindLen  = 32
	maxPayloadB = 256 * 1024 // 单条记录上限
)

var (
	ErrNotFound   = errors.New("not found")
	ErrDisabled   = errors.New("disabled")
	ErrBadPayload = errors.New("invalid payload")
)

// Device 是一台已绑定到账号的 AgentLite 客户端。
type Device struct {
	DeviceID    string    `json:"device_id"`
	AccountID   string    `json:"account_id"`
	Fingerprint string    `json:"fingerprint"`
	Name        string    `json:"name"`
	Model       string    `json:"model"`
	Brand       string    `json:"brand"`
	SDK         int       `json:"sdk"`
	AppVersion  string    `json:"app_version"`
	CreatedAt   time.Time `json:"created_at"`
	LastSeenAt  time.Time `json:"last_seen_at"`

	// 旧版遗留字段：账号体系上线前的设备级令牌，仅用于识别历史数据，不再参与鉴权
	LegacyTokenHash string `json:"token_hash,omitempty"`

	Disabled     bool            `json:"disabled"`
	LastStatus   json.RawMessage `json:"last_status,omitempty"`
	LastStatusAt time.Time       `json:"last_status_at,omitempty"`

	// 局域网会合信息（每次同步上报）
	LANIPs    []string  `json:"lan_ips,omitempty"`
	LANPort   int       `json:"lan_port,omitempty"`
	PublicIP  string    `json:"public_ip,omitempty"`
	LANSeenAt time.Time `json:"lan_seen_at,omitempty"`
}

// Record 是一条同步记录。seq 由服务端按**账号**单调分配，作为客户端拉取的游标。
type Record struct {
	Seq      int64           `json:"seq"`
	DeviceID string          `json:"device_id"`
	Kind     string          `json:"kind"`
	ID       string          `json:"id"`
	TS       string          `json:"ts,omitempty"`
	Data     json.RawMessage `json:"data,omitempty"`
}

// Incoming 是客户端上行的一条记录。
type Incoming struct {
	ID   string          `json:"id"`
	TS   string          `json:"ts"`
	Data json.RawMessage `json:"data"`

	// OriginDeviceID：局域网转发场景下，客户端代为转交**别的设备**产生的记录。
	//
	// 为什么需要它：A、B 同处内网时，A 走局域网把记录推给 B 并跳过云端，
	// B 之后再上云。若不声明来源，云端会把这些记录记成 B 产生的——
	// 数据不丢，但「这条是谁产生的」这个溯源信息就错了。
	//
	// 服务端**只在该设备确实属于同一账号时才采信**，否则退回请求方自身，
	// 避免被伪造成任意设备来源。
	OriginDeviceID string `json:"origin_device_id,omitempty"`
}

// PendingItem 是待落库的一条记录（具名类型，避免匿名结构体在调用侧到处重复）。
type PendingItem struct {
	Kind string
	Item Incoming
	// Origin 是记录的**实际产生者**；空表示由本次请求的设备产生。
	// 由调用方（api 层）校验合法性后填入。
	Origin string
}

// Config 是下发给客户端的远程配置。Values 对服务端不透明——
// 键值语义由客户端定义，服务端只负责版本化与下发。
type Config struct {
	Version int             `json:"version"`
	Values  json.RawMessage `json:"values"`
}

// Store 是全部持久状态的持有者。
type Store struct {
	dir string

	mu       sync.RWMutex
	devices  map[string]*Device             // device_id -> Device
	byFinger map[string]string              // fingerprint -> device_id
	lastSeq  map[string]int64               // account_id -> 最新 seq
	seenIDs  map[string]map[string]struct{} // account_id -> {"kind:id"} 去重集

	// 账号（见 account.go）
	accounts    map[string]*Account // account_id -> Account
	acctByName  map[string]string   // name -> account_id
	acctByToken map[string]string   // token_hash -> account_id

	// 额度与兑换码（见 quota.go）
	codes map[string]*RedeemCode

	// 跨设备指令队列（见 command.go）
	cmds *commandStore

	// 套餐与订单（见 order.go）
	orders   *orderStore
	packsVal []Pack
	packsMod time.Time

	cfgMu   sync.Mutex
	cfgPath string
	cfgMod  time.Time
	cfgVal  *Config
}

// NewStore 打开（必要时创建）数据目录并加载已有状态。
func NewStore(dir string) (*Store, error) {
	s := &Store{
		dir:         dir,
		devices:     map[string]*Device{},
		byFinger:    map[string]string{},
		lastSeq:     map[string]int64{},
		seenIDs:     map[string]map[string]struct{}{},
		accounts:    map[string]*Account{},
		acctByName:  map[string]string{},
		acctByToken: map[string]string{},
		codes:       map[string]*RedeemCode{},
		cmds:        newCommandStore(),
		orders:      newOrderStore(),
		cfgPath:     filepath.Join(dir, "config.json"),
	}
	for _, sub := range []string{dir, filepath.Join(dir, "records")} {
		if err := os.MkdirAll(sub, 0o700); err != nil {
			return nil, fmt.Errorf("创建数据目录 %s: %w", sub, err)
		}
	}
	if err := s.loadAccounts(); err != nil {
		return nil, err
	}
	if err := s.loadCodes(); err != nil {
		return nil, err
	}
	if err := s.loadDevices(); err != nil {
		return nil, err
	}
	if err := s.loadRecordIndex(); err != nil {
		return nil, err
	}
	if err := s.LoadOrders(); err != nil {
		return nil, err
	}
	return s, nil
}

// ---------- 设备绑定 ----------

func newToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func hashToken(tok string) string {
	sum := sha256.Sum256([]byte(tok))
	return hex.EncodeToString(sum[:])
}

func newDeviceID() (string, error) {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return "d_" + hex.EncodeToString(buf), nil
}

// RegisterRequest 是设备绑定请求体。
type RegisterRequest struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	Model       string `json:"model"`
	Brand       string `json:"brand"`
	SDK         int    `json:"sdk"`
	AppVersion  string `json:"app_version"`
}

// BindDevice 把一台设备绑定到账号（同一 fingerprint 重复绑定视为同一台设备）。
//
// 首次绑定时会把旧版「按设备归档」的记录迁移进账号流，保证升级不丢历史。
func (s *Store) BindDevice(accountID string, req RegisterRequest) (*Device, bool, error) {
	fp := strings.TrimSpace(req.Fingerprint)
	if fp == "" || len(fp) > 256 {
		return nil, false, fmt.Errorf("%w: fingerprint 必填且长度需 <=256", ErrBadPayload)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UTC()
	var dev *Device
	created := false
	if id, ok := s.byFinger[fp]; ok {
		dev = s.devices[id]
	} else {
		id, err := newDeviceID()
		if err != nil {
			return nil, false, err
		}
		dev = &Device{DeviceID: id, Fingerprint: fp, CreatedAt: now}
		s.devices[id] = dev
		s.byFinger[fp] = id
		created = true
	}
	// 换账号登录时把设备改挂到新账号（同一台设备不应同时属于两个账号）
	dev.AccountID = accountID
	dev.Name = truncate(req.Name, 64)
	dev.Model = truncate(req.Model, 64)
	dev.Brand = truncate(req.Brand, 64)
	dev.SDK = req.SDK
	dev.AppVersion = truncate(req.AppVersion, 32)
	dev.LastSeenAt = now
	dev.Disabled = false

	if err := s.saveDevicesLocked(); err != nil {
		return nil, false, err
	}
	// 迁移旧记录**不能**只看 created：
	// 账号体系上线前就存在的设备行（例如已通过旧版 /device/register 注册过的手机）
	// 在首次绑定账号时 created=false，若因此跳过迁移，它的历史记录就会永远留在
	// records/<device_id>.jsonl 里成为孤儿数据，用户在账号下再也看不到。
	// 迁移函数内部对不存在旧文件的设备会立即返回，且成功后把文件改名 .migrated，
	// 因此这里无条件调用是幂等且廉价的。
	if n, err := s.migrateLegacyRecordsLocked(dev.DeviceID, accountID); err != nil {
		logError("迁移旧记录失败 device=%s: %v", dev.DeviceID, err)
	} else if n > 0 {
		logInfo("已迁移旧记录到账号流 device=%s account=%s 条数=%d", dev.DeviceID, accountID, n)
	}
	cp := *dev
	return &cp, created, nil
}

// DeviceOfAccount 校验 device_id 确实属于该账号，防止跨账号伪造来源。
func (s *Store) DeviceOfAccount(accountID, deviceID string) (*Device, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	d, ok := s.devices[deviceID]
	if !ok || d.AccountID != accountID {
		return nil, ErrNotFound
	}
	if d.Disabled {
		return nil, ErrDisabled
	}
	cp := *d
	return &cp, nil
}

// Touch 刷新 last_seen（失败不影响主流程，调用方应忽略错误）。
func (s *Store) Touch(deviceID string, status json.RawMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	dev, ok := s.devices[deviceID]
	if !ok {
		return ErrNotFound
	}
	now := time.Now().UTC()
	dev.LastSeenAt = now
	if len(status) > 0 {
		dev.LastStatus = status
		dev.LastStatusAt = now
	}
	return s.saveDevicesLocked()
}

// Devices 返回全部设备快照（供 /health 与管理视图）。
func (s *Store) Devices() []Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Device, 0, len(s.devices))
	for _, d := range s.devices {
		cp := *d
		out = append(out, cp)
	}
	return out
}

// SetDisabled 启用/禁用设备（账号体系下的单设备管控开关）。
func (s *Store) SetDisabled(deviceID string, disabled bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	dev, ok := s.devices[deviceID]
	if !ok {
		return ErrNotFound
	}
	dev.Disabled = disabled
	return s.saveDevicesLocked()
}

// ---------- 记录追加与拉取（账号维度） ----------

// AppendResult 描述一次上行写入的结果。
type AppendResult struct {
	Accepted   int   `json:"accepted"`
	Duplicates int   `json:"duplicates"`
	Rejected   int   `json:"rejected"`
	Seq        int64 `json:"seq"`
}

// Append 追加一批记录到账号流。按 id 去重（幂等），seq 单调递增。
func (s *Store) Append(accountID, deviceID string, items []PendingItem) (AppendResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.appendLocked(accountID, deviceID, items)
}

// appendLocked 是 Append 的无锁内核，供持锁调用方（旧记录迁移）复用。
func (s *Store) appendLocked(accountID, deviceID string, items []PendingItem) (AppendResult, error) {
	res := AppendResult{}

	seen := s.seenIDs[accountID]
	if seen == nil {
		seen = map[string]struct{}{}
		s.seenIDs[accountID] = seen
	}

	f, err := os.OpenFile(s.recordPath(accountID), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return res, err
	}
	defer f.Close()

	seq := s.lastSeq[accountID]
	w := bufio.NewWriter(f)
	for _, it := range items {
		kind := strings.TrimSpace(it.Kind)
		id := strings.TrimSpace(it.Item.ID)
		if kind == "" || len(kind) > maxKindLen || id == "" || len(id) > maxIDLen {
			res.Rejected++
			continue
		}
		if len(it.Item.Data) > maxPayloadB {
			res.Rejected++
			continue
		}
		key := kind + ":" + id
		if _, dup := seen[key]; dup {
			res.Duplicates++
			continue
		}
		seq++
		// 来源设备优先取 Origin（局域网转发场景，已由 api 层校验归属同一账号）
		origin := deviceID
		if it.Origin != "" {
			origin = it.Origin
		}
		rec := Record{
			Seq: seq, DeviceID: origin, Kind: kind, ID: id,
			TS: truncate(it.Item.TS, 40), Data: it.Item.Data,
		}
		line, err := json.Marshal(rec)
		if err != nil {
			res.Rejected++
			continue
		}
		if _, err := w.Write(append(line, '\n')); err != nil {
			return res, err
		}
		seen[key] = struct{}{}
		res.Accepted++
	}
	if err := w.Flush(); err != nil {
		return res, err
	}
	if err := f.Sync(); err != nil {
		return res, err
	}
	s.lastSeq[accountID] = seq
	res.Seq = seq
	return res, nil
}

// Pull 返回账号流中 seq > since 的记录，最多 limit 条。
func (s *Store) Pull(accountID string, since int64, limit int) ([]Record, int64, error) {
	if limit <= 0 || limit > 1000 {
		limit = 500
	}
	s.mu.RLock()
	last := s.lastSeq[accountID]
	s.mu.RUnlock()

	f, err := os.Open(s.recordPath(accountID))
	if err != nil {
		if os.IsNotExist(err) {
			return []Record{}, last, nil
		}
		return nil, 0, err
	}
	defer f.Close()

	out := make([]Record, 0, 64)
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), maxPayloadB+64*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec Record
		if err := json.Unmarshal(line, &rec); err != nil {
			continue // 跳过损坏行，不让单行拖垮整次拉取
		}
		if rec.Seq <= since {
			continue
		}
		out = append(out, rec)
		if len(out) >= limit {
			break
		}
	}
	if err := sc.Err(); err != nil {
		return nil, 0, err
	}
	return out, last, nil
}

// ---------- 远程配置 ----------

// Config 读取下发配置。按 mtime 缓存——运维直接改 config.json 即可生效，
// 无需重启服务，也无需管理接口。
func (s *Store) Config() *Config {
	s.cfgMu.Lock()
	defer s.cfgMu.Unlock()

	empty := func() *Config { return &Config{Version: 1, Values: json.RawMessage(`{}`)} }

	st, err := os.Stat(s.cfgPath)
	if err != nil {
		if s.cfgVal == nil {
			s.cfgVal = empty()
		}
		return s.cfgVal
	}
	if s.cfgVal != nil && st.ModTime().Equal(s.cfgMod) {
		return s.cfgVal
	}
	raw, err := os.ReadFile(s.cfgPath)
	if err != nil {
		if s.cfgVal == nil {
			s.cfgVal = empty()
		}
		return s.cfgVal
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		// 配置损坏时保留上一份可用配置，而不是下发垃圾
		if s.cfgVal == nil {
			s.cfgVal = empty()
		}
		return s.cfgVal
	}
	if len(cfg.Values) == 0 {
		cfg.Values = json.RawMessage(`{}`)
	}
	s.cfgVal, s.cfgMod = &cfg, st.ModTime()
	return s.cfgVal
}

// ---------- 落盘 ----------

func (s *Store) recordPath(accountID string) string {
	return filepath.Join(s.dir, "records", sanitizeID(accountID)+".jsonl")
}

// sanitizeID 防御性清洗标识符，杜绝路径穿越。
func sanitizeID(id string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_', r == '-':
			return r
		default:
			return -1
		}
	}, id)
}

func (s *Store) loadDevices() error {
	raw, err := os.ReadFile(filepath.Join(s.dir, "devices.json"))
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var list []Device
	if err := json.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("devices.json 损坏: %w", err)
	}
	for i := range list {
		d := list[i]
		s.devices[d.DeviceID] = &d
		if d.Fingerprint != "" {
			s.byFinger[d.Fingerprint] = d.DeviceID
		}
	}
	return nil
}

// saveDevicesLocked 原子重写设备表（临时文件 + rename）。调用方必须持有写锁。
func (s *Store) saveDevicesLocked() error {
	list := make([]Device, 0, len(s.devices))
	for _, d := range s.devices {
		list = append(list, *d)
	}
	raw, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(filepath.Join(s.dir, "devices.json"), raw, 0o600)
}

// loadRecordIndex 重放各账号的 JSONL，恢复 lastSeq 与去重集。
func (s *Store) loadRecordIndex() error {
	entries, err := os.ReadDir(filepath.Join(s.dir, "records"))
	if err != nil {
		return err
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".jsonl") {
			continue
		}
		owner := strings.TrimSuffix(e.Name(), ".jsonl")
		last, seen, err := scanRecordFile(filepath.Join(s.dir, "records", e.Name()))
		if err != nil {
			return err
		}
		s.lastSeq[owner] = last
		s.seenIDs[owner] = seen
	}
	return nil
}

// scanRecordFile 读一遍记录文件，返回最大 seq 与去重集。
func scanRecordFile(path string) (int64, map[string]struct{}, error) {
	seen := map[string]struct{}{}
	f, err := os.Open(path)
	if err != nil {
		return 0, seen, err
	}
	defer f.Close()
	var last int64
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), maxPayloadB+64*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec Record
		if err := json.Unmarshal(line, &rec); err != nil {
			continue
		}
		if rec.Seq > last {
			last = rec.Seq
		}
		seen[rec.Kind+":"+rec.ID] = struct{}{}
	}
	return last, seen, sc.Err()
}

// migrateLegacyRecordsLocked 把旧版按设备归档的记录并入账号流（只做一次）。
//
// 版本沿革：账号体系上线前，记录写在 records/<device_id>.jsonl 且无 device_id 字段。
// 升级后若直接丢弃，用户已同步的历史就会消失，因此首次绑定账号时迁移。
func (s *Store) migrateLegacyRecordsLocked(deviceID, accountID string) (int, error) {
	legacy := filepath.Join(s.dir, "records", sanitizeID(deviceID)+".jsonl")
	if _, err := os.Stat(legacy); err != nil {
		return 0, nil // 无旧数据，正常路径
	}
	f, err := os.Open(legacy)
	if err != nil {
		return 0, err
	}
	items := []PendingItem{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), maxPayloadB+64*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec Record
		if err := json.Unmarshal(line, &rec); err != nil {
			continue
		}
		items = append(items, PendingItem{
			Kind: rec.Kind,
			Item: Incoming{ID: rec.ID, TS: rec.TS, Data: rec.Data},
		})
	}
	f.Close()
	if err := sc.Err(); err != nil {
		return 0, err
	}

	res, err := s.appendLocked(accountID, deviceID, items)
	if err != nil {
		return 0, err
	}
	// 迁移成功才归档旧文件；失败则保留，下次绑定重试
	if err := os.Rename(legacy, legacy+".migrated"); err != nil {
		return res.Accepted, err
	}
	return res.Accepted, nil
}

func atomicWrite(path string, data []byte, perm os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, perm); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n]
}
