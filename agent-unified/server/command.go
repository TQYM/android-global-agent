// SPDX-License-Identifier: Apache-2.0
//
// AgentLite 同步服务 — 跨设备指令队列
//
// 场景：用户在设备 1 上说「让设备 2 打开 B 站」，设备 2 自动执行。
//
// 设计取舍：
//   - **拉取式投递**：目标设备定时来问「有我的指令吗」。不用长连接/推送，
//     因为客户端会被国产 ROM 杀后台，长连接不可靠，而拉取天然自愈。
//   - **指令是瞬态的**：5 分钟未被领取即过期。存在内存里，重启即丢——
//     过期的指令本来也不该再执行。落盘的只有审计流水。
//   - **只允许定向，不支持广播**：必须显式指定目标设备。广播等于让一个失陷账号
//     一次控制全部设备，风险面完全不同。
//   - **目标设备必须属于同一账号**，且 ack 只能由目标设备自己发出（防冒领/防伪造结果）。
package main

import (
	"crypto/rand"
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
	// 指令有效期：超时未领取即作废。用户不会等太久，过期执行反而危险
	//（比如十分钟后才打开某个页面，用户早已不在那个语境里）。
	CommandTTL = 5 * time.Minute

	cmdStatusPending = "pending"
	cmdStatusRunning = "running"
	cmdStatusDone    = "done"
	cmdStatusFailed  = "failed"
	cmdStatusExpired = "expired"

	maxCommandTaskLen = 512
)

var (
	ErrCommandNotFound = errors.New("指令不存在或已过期")
	ErrCommandConsumed = errors.New("指令已被领取")
	ErrNotTargetDevice = errors.New("只有目标设备可以操作该指令")
	ErrCommandSelf     = errors.New("不能给自己下发指令")
)

// DeviceCommand 是一条跨设备指令。
type DeviceCommand struct {
	CommandID  string    `json:"command_id"`
	AccountID  string    `json:"account_id"`
	FromDevice string    `json:"from_device"` // 下发方
	ToDevice   string    `json:"to_device"`   // 目标设备
	Task       string    `json:"task"`        // 目标设备要执行的自然语言任务
	Status     string    `json:"status"`
	Detail     string    `json:"detail,omitempty"` // 终态归因 / 结果摘要
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
	ExpiresAt  time.Time `json:"expires_at"`
}

// 指令在内存中流转（瞬态），另落一份审计流水。
type commandStore struct {
	mu   sync.Mutex
	byID map[string]*DeviceCommand
	// 限流：account_id -> 最近下发时间戳，防止一个账号刷爆目标设备
	recent map[string][]time.Time
}

func newCommandStore() *commandStore {
	return &commandStore{
		byID:   map[string]*DeviceCommand{},
		recent: map[string][]time.Time{},
	}
}

func (s *Store) auditPath() string { return filepath.Join(s.dir, "commands.jsonl") }

func newCommandID() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "c_" + hex.EncodeToString(b), nil
}

// CreateCommand 下发一条跨设备指令。仅允许同账号设备之间定向下发。
func (s *Store) CreateCommand(accountID, fromDevice, toDevice, task string) (*DeviceCommand, error) {
	task = strings.TrimSpace(task)
	if task == "" || len(task) > maxCommandTaskLen {
		return nil, fmt.Errorf("%w: 任务需 1-%d 字", ErrBadPayload, maxCommandTaskLen)
	}
	if fromDevice == toDevice {
		return nil, ErrCommandSelf
	}
	// 目标必须是本账号下的设备：否则等于可以被用来向任意设备投递指令
	if _, err := s.DeviceOfAccount(accountID, toDevice); err != nil {
		return nil, fmt.Errorf("%w: 目标设备不属于当前账号", ErrNotTargetDevice)
	}

	s.cmds.mu.Lock()
	defer s.cmds.mu.Unlock()

	// 限流：每账号每分钟最多 10 条，避免刷爆目标设备
	now := time.Now().UTC()
	cut := now.Add(-time.Minute)
	kept := s.cmds.recent[accountID][:0]
	for _, t := range s.cmds.recent[accountID] {
		if t.After(cut) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= 10 {
		s.cmds.recent[accountID] = kept
		return nil, fmt.Errorf("%w: 指令下发过于频繁", ErrBadPayload)
	}
	s.cmds.recent[accountID] = append(kept, now)

	id, err := newCommandID()
	if err != nil {
		return nil, err
	}
	c := &DeviceCommand{
		CommandID: id, AccountID: accountID,
		FromDevice: fromDevice, ToDevice: toDevice, Task: task,
		Status: cmdStatusPending, CreatedAt: now, UpdatedAt: now,
		ExpiresAt: now.Add(CommandTTL),
	}
	s.cmds.byID[id] = c
	s.auditCommand(c)
	cp := *c
	return &cp, nil
}

// PendingFor 返回目标设备待执行的指令（顺带清理过期项）。
func (s *Store) PendingFor(accountID, deviceID string) []DeviceCommand {
	s.cmds.mu.Lock()
	defer s.cmds.mu.Unlock()

	now := time.Now().UTC()
	out := []DeviceCommand{}
	for id, c := range s.cmds.byID {
		if c.AccountID != accountID || c.ToDevice != deviceID {
			continue
		}
		if c.Status != cmdStatusPending {
			continue
		}
		if now.After(c.ExpiresAt) {
			c.Status = cmdStatusExpired
			c.UpdatedAt = now
			c.Detail = "超过有效期未被领取"
			s.auditCommand(c)
			delete(s.cmds.byID, id)
			continue
		}
		out = append(out, *c)
	}
	return out
}

// AckCommand 由**目标设备**回报执行状态。其它设备无权回报（防伪造结果）。
func (s *Store) AckCommand(accountID, deviceID, commandID, status, detail string) (*DeviceCommand, error) {
	switch status {
	case cmdStatusRunning, cmdStatusDone, cmdStatusFailed:
	default:
		return nil, fmt.Errorf("%w: 状态只能是 running/done/failed", ErrBadPayload)
	}

	s.cmds.mu.Lock()
	defer s.cmds.mu.Unlock()

	c, ok := s.cmds.byID[commandID]
	if !ok {
		return nil, ErrCommandNotFound
	}
	if c.AccountID != accountID {
		return nil, ErrCommandNotFound // 不泄露其它账号是否存在该指令
	}
	if c.ToDevice != deviceID {
		return nil, ErrNotTargetDevice
	}
	if c.Status == cmdStatusDone || c.Status == cmdStatusFailed {
		return nil, ErrCommandConsumed
	}
	now := time.Now().UTC()
	c.Status = status
	c.Detail = truncate(detail, 256)
	c.UpdatedAt = now
	s.auditCommand(c)
	if status == cmdStatusDone || status == cmdStatusFailed {
		delete(s.cmds.byID, commandID)
	}
	cp := *c
	return &cp, nil
}

// GetCommand 供下发方查询结果（同账号任意设备可查）。
func (s *Store) GetCommand(accountID, commandID string) (*DeviceCommand, error) {
	s.cmds.mu.Lock()
	defer s.cmds.mu.Unlock()
	c, ok := s.cmds.byID[commandID]
	if !ok || c.AccountID != accountID {
		return nil, ErrCommandNotFound
	}
	cp := *c
	return &cp, nil
}

// auditCommand 追加一条审计流水（内存锁内调用，失败只记日志不打断主流程）。
func (s *Store) auditCommand(c *DeviceCommand) {
	f, err := os.OpenFile(s.auditPath(), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		logError("指令审计写入失败: %v", err)
		return
	}
	defer f.Close()
	line, err := json.Marshal(c)
	if err != nil {
		return
	}
	if _, err := f.Write(append(line, '\n')); err != nil {
		logError("指令审计写入失败: %v", err)
	}
}
