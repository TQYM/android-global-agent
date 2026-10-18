// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// billingTxn 是一次提交的变更行，不复制整张表。核销、余额、订单和流水
// 同处一条 JSONL；fsync 成功后才发布到内存。Store.mu 是唯一事务锁。
// 旧 JSON 表仅用于首次导入；billing.jsonl 一经建立即为账务唯一事实源。
type billingTxn struct {
	Version  int          `json:"version"`
	Accounts []Account    `json:"accounts,omitempty"`
	Codes    []RedeemCode `json:"codes,omitempty"`
	Orders   []Order      `json:"orders,omitempty"`
	Ledger   *Ledger      `json:"ledger,omitempty"`
}

func (s *Store) billingPath() string { return filepath.Join(s.dir, "billing.jsonl") }

func (s *Store) applyBillingLocked(tx billingTxn) {
	for _, a := range tx.Accounts {
		if old := s.accounts[a.AccountID]; old != nil {
			delete(s.acctByName, old.Name)
			for _, tk := range old.Tokens {
				delete(s.acctByToken, tk.Hash)
			}
		}
		a.Tokens = append([]AccountToken(nil), a.Tokens...)
		s.accounts[a.AccountID] = &a
		s.acctByName[a.Name] = a.AccountID
		for _, tk := range a.Tokens {
			s.acctByToken[tk.Hash] = a.AccountID
		}
	}
	for _, c := range tx.Codes {
		s.codes[c.Code] = &c
	}
	for _, o := range tx.Orders {
		s.orders.byID[o.OrderID] = &o
	}
}

func (s *Store) loadBilling() error {
	f, err := os.OpenFile(s.billingPath(), os.O_RDWR, 0600)
	if errors.Is(err, os.ErrNotExist) {
		if err := s.loadAccounts(); err != nil {
			return err
		}
		if err := s.loadCodes(); err != nil {
			return err
		}
		if err := s.LoadOrders(); err != nil {
			return err
		}
		tx := billingTxn{Version: 1}
		for _, a := range s.accounts {
			tx.Accounts = append(tx.Accounts, *a)
		}
		for _, c := range s.codes {
			tx.Codes = append(tx.Codes, *c)
		}
		for _, o := range s.orders.byID {
			tx.Orders = append(tx.Orders, *o)
		}
		data, err := json.Marshal(tx)
		if err != nil {
			return err
		}
		// 一次性导入也按完整提交落盘；原文件保留作历史归档，随后不双写。
		return atomicWrite(s.billingPath(), append(data, '\n'), 0600)
	}
	if err != nil {
		return err
	}
	defer f.Close()
	r := bufio.NewReader(f)
	var good int64
	for {
		line, err := r.ReadBytes('\n')
		if err == io.EOF {
			if good == 0 {
				return fmt.Errorf("billing.jsonl 缺少完整初始提交")
			}
			if len(line) > 0 {
				if err := f.Truncate(good); err != nil {
					return err
				}
				if err := f.Sync(); err != nil {
					return err
				}
			}
			return nil
		}
		if err != nil {
			return err
		}
		var tx billingTxn
		if err := json.Unmarshal(line, &tx); err != nil {
			return fmt.Errorf("billing.jsonl offset=%d: %w", good, err)
		}
		if tx.Version != 1 {
			return fmt.Errorf("billing.jsonl 未知版本 %d", tx.Version)
		}
		s.applyBillingLocked(tx)
		good += int64(len(line))
	}
}

func (s *Store) commitBillingLocked(tx billingTxn) error {
	if s.billingErr != nil {
		return s.billingErr
	}
	tx.Version = 1
	line, err := json.Marshal(tx)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(s.billingPath(), os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	start, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		return err
	}
	data := append(line, '\n')
	n, err := f.Write(data)
	if err == nil && n != len(data) {
		err = io.ErrShortWrite
	}
	if err == nil {
		err = f.Sync()
	}
	if err != nil {
		// 部分写入不是一笔提交。回滚尾部；若磁盘也阻断回滚，停止后续账务写入。
		undo := f.Truncate(start)
		if undo == nil {
			undo = f.Sync()
		}
		if undo != nil {
			s.billingErr = fmt.Errorf("账务写入与回滚失败: %w", errors.Join(err, undo))
			return s.billingErr
		}
		return err
	}
	s.applyBillingLocked(tx)
	return nil
}
