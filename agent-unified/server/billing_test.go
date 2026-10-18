package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func billingFixture(t *testing.T) (*Store, *Account, string, *Order) {
	t.Helper()
	s := testStore(t)
	a, _, err := s.RegisterAccount("billing_test", "test-key-123456")
	if err != nil {
		t.Fatal(err)
	}
	codes, err := s.CreateCodes(1, 7, "regression")
	if err != nil {
		t.Fatal(err)
	}
	o, err := s.CreateOrder(a.AccountID, "starter")
	if err != nil {
		t.Fatal(err)
	}
	return s, a, codes[0], o
}

func TestPaymentIsOneTransactionAndRetryIsIdempotent(t *testing.T) {
	s, a, code, o := billingFixture(t)
	paid, bal, err := s.PayOrderWithCode(a.AccountID, o.OrderID, code)
	if err != nil || paid.Status != orderPaid || paid.Units != 7 || bal != a.Quota+7 {
		t.Fatalf("payment: %+v %d %v", paid, bal, err)
	}
	if _, _, err := s.PayOrderWithCode(a.AccountID, o.OrderID, code); err != nil {
		t.Fatal("payment retry:", err)
	}
	if _, _, err := s.Redeem(a.AccountID, code); !errors.Is(err, ErrCodeUsed) {
		t.Fatalf("spent code accepted: %v", err)
	}
	reopened, err := NewStore(s.dir)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.accounts[a.AccountID].Quota != a.Quota+7 || reopened.orders.byID[o.OrderID].Status != orderPaid || reopened.codes[code].UsedBy != a.AccountID {
		t.Fatal("transaction not recovered atomically")
	}
}

func TestConcurrentCancelAndPayHaveOneWinner(t *testing.T) {
	for i := 0; i < 20; i++ {
		s, a, code, o := billingFixture(t)
		var payErr, cancelErr error
		start := make(chan struct{})
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); <-start; _, _, payErr = s.PayOrderWithCode(a.AccountID, o.OrderID, code) }()
		go func() { defer wg.Done(); <-start; _, cancelErr = s.CancelOrder(a.AccountID, o.OrderID) }()
		close(start)
		wg.Wait()
		if (payErr == nil) == (cancelErr == nil) {
			t.Fatalf("winners: pay=%v cancel=%v", payErr, cancelErr)
		}
		reopened, err := NewStore(s.dir)
		if err != nil {
			t.Fatal(err)
		}
		paid := reopened.orders.byID[o.OrderID].Status == orderPaid
		if paid != (payErr == nil) || paid != (reopened.codes[code].UsedBy != "") {
			t.Fatal("order and code disagree")
		}
		want := a.Quota
		if paid {
			want += 7
		}
		if reopened.accounts[a.AccountID].Quota != want {
			t.Fatalf("balance=%d want=%d", reopened.accounts[a.AccountID].Quota, want)
		}
	}
}

func TestPaymentWriteFailureDoesNotChangeMemory(t *testing.T) {
	s, a, code, o := billingFixture(t)
	path := filepath.Join(s.dir, "billing.jsonl")
	if filepath.Dir(path) != s.dir || !filepath.IsAbs(path) {
		t.Fatal("unexpected fixture path")
	}
	if err := os.Rename(path, path+".saved"); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.PayOrderWithCode(a.AccountID, o.OrderID, code); err == nil {
		t.Fatal("blocked journal reported success")
	}
	if s.accounts[a.AccountID].Quota != a.Quota || s.codes[code].UsedBy != "" || s.orders.byID[o.OrderID].Status != orderPending {
		t.Fatal("failed write published partial transaction")
	}
}

func TestBillingRecoversOnlyCompleteTransactions(t *testing.T) {
	s, a, code, o := billingFixture(t)
	if _, _, err := s.PayOrderWithCode(a.AccountID, o.OrderID, code); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(s.dir, "billing.jsonl")
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.WriteString(`{"accounts":[{"account_id":"partial"`)
	_ = f.Close()
	reopened, err := NewStore(s.dir)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.accounts[a.AccountID].Quota != a.Quota+7 || reopened.accounts["partial"] != nil {
		t.Fatal("incomplete transaction applied")
	}
	after, _ := os.Stat(path)
	if after.Size() != before.Size() {
		t.Fatal("uncommitted tail not removed")
	}
}

func TestLegacyBillingImportedOnce(t *testing.T) {
	dir := t.TempDir()
	a := []Account{{AccountID: "a_legacy", Name: "legacy", Quota: 9, TokenHash: hashToken("legacy-token")}}
	c := []RedeemCode{{Code: "LEGACY", Units: 4}}
	for name, val := range map[string]any{"accounts.json": a, "redeem_codes.json": c} {
		raw, _ := json.Marshal(val)
		if err := os.WriteFile(filepath.Join(dir, name), raw, 0600); err != nil {
			t.Fatal(err)
		}
	}
	s, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Redeem("a_legacy", "LEGACY"); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if reopened.accounts["a_legacy"].Quota != 13 || reopened.codes["LEGACY"].UsedBy == "" {
		t.Fatal("legacy snapshot reapplied")
	}
	if _, err := reopened.AccountByToken("legacy-token"); err != nil {
		t.Fatal(err)
	}
}
