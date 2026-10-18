// SPDX-License-Identifier: Apache-2.0
//
// AgentLite 同步服务 — 套餐与订单（应用内买单）
//
// 定位：给客户端的「收款界面」提供后端。**当前可用的支付方式是兑换码**——
// 真实在线支付（支付宝/微信）需要商户资质，尚未接入；订单模型已经把
// 「支付方式」独立成字段（Method），接入时只需实现一个 PayOrder 分支，
// 不必改动订单生命周期。
//
// 订单生命周期：
//
//	pending --(兑换码核销)--> paid
//	   \--(超时 15 分钟)--> expired
//	   \--(用户取消)------> cancelled
//
// 记账口径（重要）：**额度以兑换码为准**，订单记录的是"用户选了什么套餐"。
// 核销时把兑换码实际带来的 units 写进订单，两者不一致时以实际入账为准——
// 这样不会因为有人拿小码付大套餐而产生凭空额度。
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
	"time"
)

const (
	orderTTL = 15 * time.Minute

	orderPending   = "pending"
	orderPaid      = "paid"
	orderCancelled = "cancelled"
	orderExpired   = "expired"

	methodRedeem = "redeem" // 目前唯一可用；未来可加 alipay / wechat
)

var (
	ErrOrderNotFound = errors.New("订单不存在")
	ErrOrderClosed   = errors.New("订单已结束（已支付/已取消/已过期）")
	ErrPackNotFound  = errors.New("套餐不存在")
)

// Pack 是一个可购买的额度套餐。
type Pack struct {
	PackID   string  `json:"pack_id"`
	Name     string  `json:"name"`
	Units    int64   `json:"units"`
	Bonus    int64   `json:"bonus"`     // 赠送额度
	PriceCNY float64 `json:"price_cny"` // 展示价；真实收款未接入
	Note     string  `json:"note,omitempty"`
}

// TotalUnits 是购买后实际到账的额度。
func (p Pack) TotalUnits() int64 { return p.Units + p.Bonus }

// Order 是一笔买单记录。
type Order struct {
	OrderID    string    `json:"order_id"`
	AccountID  string    `json:"account_id"`
	PackID     string    `json:"pack_id"`
	PackName   string    `json:"pack_name"`
	Units      int64     `json:"units"`     // 实际入账（核销后写实付所得）
	PriceCNY   float64   `json:"price_cny"` // 展示价
	Status     string    `json:"status"`
	Method     string    `json:"method,omitempty"`
	RedeemCode string    `json:"redeem_code,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
	PaidAt     time.Time `json:"paid_at,omitempty"`
	ExpiresAt  time.Time `json:"expires_at"`
}

type orderStore struct {
	byID map[string]*Order
}

func newOrderStore() *orderStore { return &orderStore{byID: map[string]*Order{}} }

// defaultPacks 是内置套餐。运维可在 /var/lib/agentlite-server/packs.json 覆盖
// （见 Store.Packs），无需改代码或重启。
func defaultPacks() []Pack {
	return []Pack{
		{PackID: "starter", Name: "体验包", Units: 500, Bonus: 0, PriceCNY: 5, Note: "约 50 万 tokens"},
		{PackID: "standard", Name: "标准包", Units: 2000, Bonus: 200, PriceCNY: 18, Note: "约 220 万 tokens"},
		{PackID: "pro", Name: "专业包", Units: 6000, Bonus: 1000, PriceCNY: 48, Note: "约 700 万 tokens"},
	}
}

func (s *Store) packsPath() string  { return filepath.Join(s.dir, "packs.json") }
func (s *Store) ordersPath() string { return filepath.Join(s.dir, "orders.json") }

// Packs 返回套餐列表（优先读 packs.json，按 mtime 缓存）。
func (s *Store) Packs() []Pack {
	s.cfgMu.Lock()
	defer s.cfgMu.Unlock()

	st, err := os.Stat(s.packsPath())
	if err == nil {
		if s.packsVal != nil && st.ModTime().Equal(s.packsMod) {
			return s.packsVal
		}
		if raw, err := os.ReadFile(s.packsPath()); err == nil {
			var list []Pack
			if json.Unmarshal(raw, &list) == nil && len(list) > 0 {
				s.packsVal, s.packsMod = list, st.ModTime()
				return list
			}
		}
	}
	if s.packsVal == nil {
		s.packsVal = defaultPacks()
	}
	return s.packsVal
}

func (s *Store) packByID(id string) (Pack, bool) {
	for _, p := range s.Packs() {
		if p.PackID == id {
			return p, true
		}
	}
	return Pack{}, false
}

func newOrderID() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "o_" + hex.EncodeToString(b), nil
}

// LoadOrders 启动时载入历史订单。
func (s *Store) LoadOrders() error {
	raw, err := os.ReadFile(s.ordersPath())
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	var list []Order
	if err := json.Unmarshal(raw, &list); err != nil {
		return fmt.Errorf("orders.json 损坏: %w", err)
	}
	for i := range list {
		o := list[i]
		s.orders.byID[o.OrderID] = &o
	}
	return nil
}

// CreateOrder 创建一笔待支付订单。
func (s *Store) CreateOrder(accountID, packID string) (*Order, error) {
	pack, ok := s.packByID(strings.TrimSpace(packID))
	if !ok {
		return nil, ErrPackNotFound
	}
	id, err := newOrderID()
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()

	s.mu.Lock()
	defer s.mu.Unlock()

	o := &Order{
		OrderID: id, AccountID: accountID,
		PackID: pack.PackID, PackName: pack.Name,
		Units: pack.TotalUnits(), PriceCNY: pack.PriceCNY,
		Status: orderPending, CreatedAt: now, ExpiresAt: now.Add(orderTTL),
	}
	if err := s.commitBillingLocked(billingTxn{Orders: []Order{*o}}); err != nil {
		return nil, err
	}
	cp := *o
	return &cp, nil
}

// ListOrders 返回账号的订单，最新在前。
func (s *Store) ListOrders(accountID string) []Order {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	out := []Order{}
	for _, o := range s.orders.byID {
		if o.AccountID != accountID {
			continue
		}
		// 顺手处理超时（惰性过期，不另起定时器）
		cp := *o
		if cp.Status == orderPending && now.After(cp.ExpiresAt) {
			cp.Status = orderExpired
		}
		out = append(out, cp)
	}
	// 最新在前（OrderID 随机，按创建时间排）
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].CreatedAt.After(out[j-1].CreatedAt); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// CancelOrder 用户取消待支付订单。
func (s *Store) CancelOrder(accountID, orderID string) (*Order, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.orders.byID[orderID]
	if !ok || o.AccountID != accountID {
		return nil, ErrOrderNotFound
	}
	if o.Status != orderPending {
		return nil, ErrOrderClosed
	}
	if time.Now().UTC().After(o.ExpiresAt) {
		return nil, ErrOrderClosed
	}
	cp := *o
	cp.Status = orderCancelled
	if err := s.commitBillingLocked(billingTxn{Orders: []Order{cp}}); err != nil {
		return nil, err
	}
	return &cp, nil
}

// PayOrderWithCode 用兑换码核销支付订单。
//
// 记账口径：**以兑换码实际带来的 units 为准**写入订单。若用户拿一张小码付大套餐，
// 入账的是小码的额度——不会凭空产生额度。订单里保留所选套餐名与展示价供对账。
func (s *Store) PayOrderWithCode(accountID, orderID, code string) (*Order, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	o, ok := s.orders.byID[orderID]
	if !ok || o.AccountID != accountID {
		return nil, 0, ErrOrderNotFound
	}
	if o.Status == orderPaid && o.RedeemCode == normalizeCode(code) {
		cp := *o
		return &cp, s.accounts[accountID].Quota, nil // 同订单、同码重试不再次记账
	}
	if o.Status != orderPending || time.Now().UTC().After(o.ExpiresAt) {
		return nil, 0, ErrOrderClosed
	}
	units, balance, err := s.redeemLocked(accountID, code, o)
	if err != nil {
		return nil, 0, err
	}
	cp := *s.orders.byID[orderID]
	logInfo("order paid %s account=%s pack=%s units=%d balance=%d", cp.OrderID, accountID, cp.PackID, units, balance)
	return &cp, balance, nil
}
