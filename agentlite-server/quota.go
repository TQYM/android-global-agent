// SPDX-License-Identifier: Apache-2.0
//
// # AgentLite 同步服务 — 额度账本与兑换码
//
// 记账单位：1 unit = 1000 tokens（整数记账，避免浮点误差）。
// 额度只在两处变动：兑换码核销（+）与网关转发后的用量扣减（-）。
// 所有变动都写 append-only 流水，便于对账与追责。
package main

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// 新账号赠送额度（unit = 1000 tokens）。
//
// 让用户注册后能立刻试跑，而不是先被"去兑换"卡住；额度耗尽后自然引导到兑换码。
//
// **2026-09-15 从 500 收紧到 100**（安全审查 P0-S2）：注册只需一个名字+口令、
// 每 IP 限流又可被 IPv6/代理池绕过，500 units（50 万 tokens）足以规模化盗刷上游付费 Key。
// 100 units（10 万 tokens）够新用户跑几十条真实任务，把"零门槛试用"与"被盗刷风险"平衡开。
const newAccountGrant = 100

var (
	ErrCodeNotFound = errors.New("兑换码不存在")
	ErrCodeUsed     = errors.New("兑换码已被使用")
	ErrQuotaEmpty   = errors.New("额度不足")
)

// RedeemCode 是一张一次性兑换码。
type RedeemCode struct {
	Code      string    `json:"code"`
	Units     int64     `json:"units"`
	Batch     string    `json:"batch,omitempty"`
	CreatedAt time.Time `json:"created_at"`
	UsedBy    string    `json:"used_by,omitempty"`
	UsedAt    time.Time `json:"used_at,omitempty"`
}

// Ledger 是一条额度流水。
type Ledger struct {
	Time      time.Time `json:"time"`
	AccountID string    `json:"account_id"`
	Delta     int64     `json:"delta"` // 正=充值，负=消耗
	Reason    string    `json:"reason"`
	Balance   int64     `json:"balance"`
}

// 兑换码字母表：刻意剔除 0/O/1/I/L 等易混字符，便于人工抄写与口述。
const codeAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

func (s *Store) codesPath() string  { return filepath.Join(s.dir, "redeem_codes.json") }
func (s *Store) ledgerPath() string { return filepath.Join(s.dir, "quota_ledger.jsonl") }

func (s *Store) loadCodes() error {
	raw, err := os.ReadFile(s.codesPath())
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var list []RedeemCode
	if err := json.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("redeem_codes.json 损坏: %w", err)
	}
	for i := range list {
		c := list[i]
		s.codes[c.Code] = &c
	}
	return nil
}

// generateCode 产出形如 AGT-7K2M-9QXP-4RTW 的兑换码。
func generateCode() (string, error) {
	const groups, groupLen = 3, 4
	var sb strings.Builder
	sb.WriteString("AGT-")
	buf := make([]byte, groups*groupLen)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	for i, b := range buf {
		if i > 0 && i%groupLen == 0 {
			sb.WriteByte('-')
		}
		sb.WriteByte(codeAlphabet[int(b)%len(codeAlphabet)])
	}
	return sb.String(), nil
}

// CreateCodes 批量生成兑换码（管理接口调用）。
func (s *Store) CreateCodes(n int, units int64, batch string) ([]string, error) {
	if n <= 0 || n > 1000 {
		return nil, fmt.Errorf("单批数量需 1-1000")
	}
	if units <= 0 || units > 10_000_000 {
		return nil, fmt.Errorf("单码额度超出允许范围")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	out := make([]string, 0, n)
	tx := billingTxn{}
	pending := make(map[string]bool, n)
	now := time.Now().UTC()
	for i := 0; i < n; i++ {
		var code string
		// 极小概率碰撞；重试即可，不做无限循环
		for attempt := 0; attempt < 5; attempt++ {
			c, err := generateCode()
			if err != nil {
				return nil, err
			}
			if _, exists := s.codes[c]; !exists && !pending[c] {
				code = c
				break
			}
		}
		if code == "" {
			return nil, fmt.Errorf("生成兑换码失败（连续碰撞）")
		}
		tx.Codes = append(tx.Codes, RedeemCode{Code: code, Units: units, Batch: truncate(batch, 32), CreatedAt: now})
		pending[code] = true
		out = append(out, code)
	}
	if err := s.commitBillingLocked(tx); err != nil {
		return nil, err
	}
	return out, nil
}

// Redeem 核销兑换码并把额度加到账号上。
//
// 核销与记账在同一把锁内完成——否则并发提交同一张码可能双花。
func (s *Store) Redeem(accountID, rawCode string) (int64, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.redeemLocked(accountID, rawCode, nil)
}

func normalizeCode(code string) string {
	return strings.ReplaceAll(strings.ToUpper(strings.TrimSpace(code)), " ", "")
}

// 直接兑换与订单支付共用同一次核销；调用方持 Store.mu，未落盘不修改活状态。
func (s *Store) redeemLocked(accountID, rawCode string, order *Order) (int64, int64, error) {
	code := normalizeCode(rawCode)
	c, ok := s.codes[code]
	if !ok {
		return 0, 0, ErrCodeNotFound
	}
	if c.UsedBy != "" {
		return 0, 0, ErrCodeUsed
	}
	a, ok := s.accounts[accountID]
	if !ok {
		return 0, 0, ErrNotFound
	}
	now := time.Now().UTC()
	nextCode, nextAccount := *c, *a
	nextCode.UsedBy, nextCode.UsedAt = accountID, now
	nextAccount.Quota += c.Units
	tx := billingTxn{Accounts: []Account{nextAccount}, Codes: []RedeemCode{nextCode}, Ledger: &Ledger{
		Time: now, AccountID: accountID, Delta: c.Units, Reason: "redeem:" + code, Balance: nextAccount.Quota,
	}}
	if order != nil {
		next := *order
		next.Status, next.Method, next.RedeemCode = orderPaid, methodRedeem, code
		next.Units, next.PaidAt = c.Units, now
		tx.Orders = []Order{next}
	}
	if err := s.commitBillingLocked(tx); err != nil {
		return 0, 0, err
	}
	return c.Units, nextAccount.Quota, nil
}

// ConsumeQuota 扣减额度，返回扣减后的余额。额度不足返回 ErrQuotaEmpty。
func (s *Store) ConsumeQuota(accountID string, units int64, reason string) (int64, error) {
	if units <= 0 {
		units = 1
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.accounts[accountID]
	if !ok {
		return 0, ErrNotFound
	}
	next := *a
	next.Quota -= units
	next.QuotaUsed += units
	overdraft := a.Quota < units
	if overdraft {
		reason += "|overdraft"
	}
	if err := s.commitBillingLocked(billingTxn{Accounts: []Account{next}, Ledger: &Ledger{
		Time: time.Now().UTC(), AccountID: accountID, Delta: -units, Reason: reason, Balance: next.Quota,
	}}); err != nil {
		return 0, err
	}
	// 保留实际用量扣到负数的既有口径；后续网关请求由 quota<=0 拦截。
	if overdraft {
		return next.Quota, ErrQuotaEmpty
	}
	return next.Quota, nil
}

// CodeStats 返回兑换码统计（管理视图用）。
func (s *Store) CodeStats() (total, used int, unusedUnits int64) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.codes {
		total++
		if c.UsedBy != "" {
			used++
		} else {
			unusedUnits += c.Units
		}
	}
	return total, used, unusedUnits
}
