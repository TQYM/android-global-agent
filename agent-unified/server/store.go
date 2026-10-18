// SPDX-License-Identifier: Apache-2.0
//
// # AgentLite 同步服务 — 存储层
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
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
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

// recordPosition 只保存序号及文件区间；记录正文仍只在账号 JSONL 中。
type recordPosition struct {
	Seq    int64
	Offset int64
	Size   int64
}

// Store 是全部持久状态的持有者，写入统一由 mu 串行提交。
type Store struct {
	dir        string
	billingErr error // 账务尾部回滚失败时阻断后续写入
	storageErr error // 同步日志尾部回滚失败时阻断后续写入
	// 测试可注入 fsync 故障；生产始终直接使用 os.File.Sync。
	storageSync func(*os.File) error

	mu          sync.RWMutex
	devices     map[string]*Device             // device_id -> Device
	byFinger    map[string]string              // account_id + NUL + fingerprint -> device_id
	lastSeq     map[string]int64               // account_id -> 最新 seq
	seenIDs     map[string]map[string]struct{} // account_id -> {"kind:id"} 去重集
	recordIndex map[string][]recordPosition    // account_id -> 按 seq 递增的字节区间

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
		recordIndex: map[string][]recordPosition{},
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
	if err := s.loadBilling(); err != nil {
		return nil, err
	}
	if err := s.loadDevices(); err != nil {
		return nil, err
	}
	if err := s.loadRecordIndex(); err != nil {
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
	fingerKey := accountID + "\x00" + fp
	id, ok := s.byFinger[fingerKey]
	if !ok {
		// 只认领尚未归属账号的历史设备；有账号的设备始终留在原账号。
		id, ok = s.byFinger["\x00"+fp]
	}
	if ok {
		next := cloneDevice(s.devices[id])
		dev = &next
	} else {
		id, err := newDeviceID()
		if err != nil {
			return nil, false, err
		}
		dev = &Device{DeviceID: id, Fingerprint: fp, CreatedAt: now}
		created = true
	}
	// fingerprint 只标识同一账号下的安装；换账号创建新归属，不搬运状态。
	dev.AccountID = accountID
	dev.Name = truncate(req.Name, 64)
	dev.Model = truncate(req.Model, 64)
	dev.Brand = truncate(req.Brand, 64)
	dev.SDK = req.SDK
	dev.AppVersion = truncate(req.AppVersion, 32)
	dev.LastSeenAt = now
	dev.Disabled = false

	if err := s.commitDeviceLocked(dev); err != nil {
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
	cp := cloneDevice(dev)
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
	cp := cloneDevice(d)
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
	next := cloneDevice(dev)
	now := time.Now().UTC()
	next.LastSeenAt = now
	if len(status) > 0 {
		next.LastStatus = append(json.RawMessage(nil), status...)
		next.LastStatusAt = now
	}
	return s.commitDeviceLocked(&next)
}

// Devices 返回全部设备快照（供 /health 与管理视图）。
func (s *Store) Devices() []Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Device, 0, len(s.devices))
	for _, d := range s.devices {
		out = append(out, cloneDevice(d))
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
	next := cloneDevice(dev)
	next.Disabled = disabled
	return s.commitDeviceLocked(&next)
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
	res := AppendResult{Seq: s.lastSeq[accountID]}
	seen := s.seenIDs[accountID]
	staged := make(map[string]struct{}, len(items))
	positions := make([]recordPosition, 0, len(items))
	var data bytes.Buffer
	seq := res.Seq
	for _, it := range items {
		kind := strings.TrimSpace(it.Kind)
		id := strings.TrimSpace(it.Item.ID)
		if kind == "" || len(kind) > maxKindLen || id == "" || len(id) > maxIDLen || len(it.Item.Data) > maxPayloadB {
			res.Rejected++
			continue
		}
		key := kind + ":" + id
		_, dup := seen[key]
		_, batchDup := staged[key]
		if dup || batchDup {
			res.Duplicates++
			continue
		}
		origin := deviceID
		if it.Origin != "" {
			origin = it.Origin // api 层已核验同账号归属
		}
		if seq == math.MaxInt64 {
			return res, fmt.Errorf("账号 %s 记录序号已耗尽", accountID)
		}
		rec := Record{
			Seq: seq + 1, DeviceID: origin, Kind: kind, ID: id,
			TS: truncate(it.Item.TS, 40), Data: it.Item.Data,
		}
		line, err := json.Marshal(rec)
		if err != nil {
			res.Rejected++
			continue // 序号与去重状态都尚未发布
		}
		seq++
		positions = append(positions, recordPosition{Seq: seq, Offset: int64(data.Len()), Size: int64(len(line) + 1)})
		data.Write(line)
		data.WriteByte('\n')
		staged[key] = struct{}{}
	}
	if len(positions) == 0 {
		return res, nil // 空、全重复或全拒绝批次不打开文件、不 fsync
	}
	start, err := s.appendStorageLocked(s.recordPath(accountID), data.Bytes())
	if err != nil {
		return res, err
	}
	// 完整写入并 fsync 后一次发布；重试失败批次仍可获得相同序号。
	if seen == nil {
		seen = map[string]struct{}{}
		s.seenIDs[accountID] = seen
	}
	for key := range staged {
		seen[key] = struct{}{}
	}
	for i := range positions {
		positions[i].Offset += start
	}
	s.recordIndex[accountID] = append(s.recordIndex[accountID], positions...)
	s.lastSeq[accountID] = seq
	res.Accepted = len(positions)
	res.Seq = seq
	return res, nil
}

// Pull 按账号序列索引定位当前页，只读取已提交的字节区间。
// 高水位和页边界在同一读锁下取快照，正文读取不持有全局锁。
func (s *Store) Pull(accountID string, since int64, limit int) ([]Record, int64, error) {
	if limit <= 0 || limit > 1000 {
		limit = 500
	}
	s.mu.RLock()
	last := s.lastSeq[accountID]
	index := s.recordIndex[accountID]
	start := sort.Search(len(index), func(i int) bool { return index[i].Seq > since })
	stop := min(start+limit, len(index))
	var begin, end int64
	if start < stop {
		begin = index[start].Offset
		end = index[stop-1].Offset + index[stop-1].Size
	}
	s.mu.RUnlock()
	if start == stop {
		return []Record{}, last, nil
	}
	f, err := os.Open(s.recordPath(accountID))
	if err != nil {
		return nil, 0, err
	}
	defer f.Close()
	out := make([]Record, 0, stop-start)
	sc := bufio.NewScanner(io.NewSectionReader(f, begin, end-begin))
	sc.Buffer(make([]byte, 0, 64*1024), maxPayloadB*6+64*1024)
	prev := since
	for sc.Scan() {
		line := sc.Bytes()
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		var rec Record
		if err := json.Unmarshal(line, &rec); err != nil {
			return nil, 0, err
		}
		if rec.Seq <= prev || rec.Seq > last {
			return nil, 0, fmt.Errorf("账号 %s 记录序列与已提交索引不一致", accountID)
		}
		out = append(out, rec)
		prev = rec.Seq
	}
	if err := sc.Err(); err != nil {
		return nil, 0, err
	}
	if len(out) != stop-start {
		return nil, 0, io.ErrUnexpectedEOF
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

func (s *Store) devicesPath() string { return filepath.Join(s.dir, "devices.jsonl") }

func cloneDevice(d *Device) Device {
	cp := *d
	cp.LastStatus = append(json.RawMessage(nil), d.LastStatus...)
	cp.LANIPs = append([]string(nil), d.LANIPs...)
	return cp
}

func (s *Store) applyDeviceLocked(next *Device) {
	if old := s.devices[next.DeviceID]; old != nil {
		key := old.AccountID + "\x00" + old.Fingerprint
		if s.byFinger[key] == old.DeviceID {
			delete(s.byFinger, key)
		}
	}
	cp := cloneDevice(next)
	s.devices[cp.DeviceID] = &cp
	if cp.Fingerprint != "" {
		s.byFinger[cp.AccountID+"\x00"+cp.Fingerprint] = cp.DeviceID
	}
}

// loadDevices 仅首次导入 devices.json；日志存在后它就是唯一事实源。
func (s *Store) loadDevices() error {
	f, err := os.OpenFile(s.devicesPath(), os.O_RDWR, 0o600)
	if errors.Is(err, os.ErrNotExist) {
		raw, err := os.ReadFile(filepath.Join(s.dir, "devices.json"))
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		var list []Device
		if err == nil {
			if err := json.Unmarshal(raw, &list); err != nil {
				return fmt.Errorf("devices.json 损坏: %w", err)
			}
		}
		var data bytes.Buffer
		for i := range list {
			line, err := json.Marshal(list[i])
			if err != nil {
				return err
			}
			data.Write(line)
			data.WriteByte('\n')
		}
		if err := atomicWrite(s.devicesPath(), data.Bytes(), 0o600); err != nil {
			return err
		}
		for i := range list {
			s.applyDeviceLocked(&list[i])
		}
		return nil
	}
	if err != nil {
		return err
	}
	defer f.Close()
	r := bufio.NewReader(f)
	var good int64
	for {
		line, err := readStorageLine(r, maxBodyBytes*6+64*1024)
		if err == io.EOF {
			return trimStorageTail(f, good, len(line) > 0)
		}
		if err != nil {
			return err
		}
		if len(bytes.TrimSpace(line)) > 0 {
			var d Device
			if err := json.Unmarshal(line, &d); err != nil {
				return fmt.Errorf("devices.jsonl offset=%d: %w", good, err)
			}
			if d.DeviceID == "" {
				return fmt.Errorf("devices.jsonl offset=%d: 缺少 device_id", good)
			}
			s.applyDeviceLocked(&d)
		}
		good += int64(len(line))
	}
}

func (s *Store) commitDeviceLocked(next *Device) error {
	line, err := json.Marshal(next)
	if err != nil {
		return err
	}
	if _, err := s.appendStorageLocked(s.devicesPath(), append(line, '\n')); err != nil {
		return err
	}
	s.applyDeviceLocked(next)
	return nil
}

// appendStorageLocked 是设备与记录共用的同步日志写内核；Store.mu 是唯一写锁。
// fsync 失败则截回原长度；连回滚也失败时停止写入，不发布内存状态。
func (s *Store) appendStorageLocked(path string, data []byte) (int64, error) {
	if s.storageErr != nil {
		return 0, s.storageErr
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	start, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		return 0, err
	}
	n, err := f.Write(data)
	if err == nil && n != len(data) {
		err = io.ErrShortWrite
	}
	if err == nil {
		err = s.syncStorageFile(f)
	}
	if err == nil && start == 0 {
		// 首次创建账号流时也保证目录项落盘。
		dir, openErr := os.Open(filepath.Dir(path))
		if openErr != nil {
			err = openErr
		} else {
			err = s.syncStorageFile(dir)
			_ = dir.Close()
		}
	}
	if err != nil {
		undo := f.Truncate(start)
		if undo == nil {
			undo = s.syncStorageFile(f)
		}
		if undo != nil {
			s.storageErr = fmt.Errorf("同步日志写入与回滚失败: %w", errors.Join(err, undo))
			return 0, s.storageErr
		}
		return 0, err
	}
	return start, nil
}

func (s *Store) syncStorageFile(f *os.File) error {
	if s.storageSync != nil {
		return s.storageSync(f)
	}
	return f.Sync()
}

// readStorageLine 保留换行符，以便精确恢复字节区间；上限覆盖 RawMessage 的 HTML 转义。
func readStorageLine(r *bufio.Reader, max int) ([]byte, error) {
	var line []byte
	for {
		part, err := r.ReadSlice('\n')
		if len(line)+len(part) > max {
			return nil, fmt.Errorf("同步日志单行超过 %d 字节", max)
		}
		line = append(line, part...)
		if err != bufio.ErrBufferFull {
			return line, err
		}
	}
}

func trimStorageTail(f *os.File, good int64, partial bool) error {
	if !partial {
		return nil
	}
	if err := f.Truncate(good); err != nil {
		return err
	}
	return f.Sync()
}

// loadRecordIndex 启动时重放一次，重建序号、去重和 seq→字节区间索引。
// 不另写索引文件，记录 JSONL 始终是唯一事实源。
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
		if err := s.indexRecordFile(owner, filepath.Join(s.dir, "records", e.Name())); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) indexRecordFile(owner, path string) error {
	f, err := os.OpenFile(path, os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	r := bufio.NewReader(f)
	seen := map[string]struct{}{}
	var index []recordPosition
	var last, good int64
	for {
		line, err := readStorageLine(r, maxPayloadB*6+64*1024)
		if err == io.EOF {
			if err := trimStorageTail(f, good, len(line) > 0); err != nil {
				return err
			}
			s.lastSeq[owner], s.seenIDs[owner], s.recordIndex[owner] = last, seen, index
			return nil
		}
		if err != nil {
			return err
		}
		if len(bytes.TrimSpace(line)) > 0 {
			var rec Record
			if err := json.Unmarshal(line, &rec); err != nil {
				return fmt.Errorf("记录 %s offset=%d: %w", owner, good, err)
			}
			if len(index) > 0 && rec.Seq <= last {
				return fmt.Errorf("记录 %s offset=%d: seq=%d 未递增", owner, good, rec.Seq)
			}
			index = append(index, recordPosition{Seq: rec.Seq, Offset: good, Size: int64(len(line))})
			last = rec.Seq
			seen[rec.Kind+":"+rec.ID] = struct{}{}
		}
		good += int64(len(line))
	}
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
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, perm)
	if err != nil {
		return err
	}
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	dir, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n]
}
