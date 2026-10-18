package main

import (
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testStore(t *testing.T) *Store {
	t.Helper()
	s, err := NewStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestPullDeliveredCursorDoesNotSkipPages(t *testing.T) {
	s := testStore(t)
	items := make([]PendingItem, 1001)
	for i := range items {
		items[i] = PendingItem{Kind: kindTask, Item: Incoming{ID: fmt.Sprint(i), Data: json.RawMessage(`{"ok":true}`)}}
	}
	if _, err := s.Append("a_one", "d_one", items); err != nil {
		t.Fatal(err)
	}
	srv := &server{store: s}
	var since int64
	count := 0
	for page := 0; page < 6; page++ {
		w := httptest.NewRecorder()
		r := httptest.NewRequest("GET", fmt.Sprintf("/api/sync/pull?since=%d&limit=200", since), nil)
		srv.handlePull(w, r, &Account{AccountID: "a_one"}, &Device{DeviceID: "d_one"})
		var got struct {
			Records   []Record `json:"records"`
			ServerSeq int64    `json:"server_seq"`
			NextSince int64    `json:"next_since"`
			HasMore   bool     `json:"has_more"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		if got.ServerSeq != 1001 {
			t.Fatalf("legacy high water = %d", got.ServerSeq)
		}
		for _, rec := range got.Records {
			count++
			if rec.Seq != int64(count) {
				t.Fatalf("record %d has seq %d", count, rec.Seq)
			}
		}
		if got.NextSince <= since {
			t.Fatalf("page %d did not advance delivered cursor: %d", page, got.NextSince)
		}
		since = got.NextSince
		if got.HasMore != (count < 1001) {
			t.Fatalf("has_more=%v, count=%d", got.HasMore, count)
		}
	}
	if count != 1001 || since != 1001 {
		t.Fatalf("received=%d cursor=%d", count, since)
	}
}

func TestBindFingerprintIsScopedToAccount(t *testing.T) {
	s := testStore(t)
	req := RegisterRequest{Fingerprint: "same-install", Name: "one"}
	a, _, err := s.BindDevice("a_first", req)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Touch(a.DeviceID, json.RawMessage(`{"private":"first"}`)); err != nil {
		t.Fatal(err)
	}
	if err := s.RecordLANPresence(a.DeviceID, "203.0.113.1", []string{"192.168.1.2"}, 1234); err != nil {
		t.Fatal(err)
	}
	b, created, err := s.BindDevice("a_second", req)
	if err != nil {
		t.Fatal(err)
	}
	if !created || b.DeviceID == a.DeviceID || len(b.LastStatus) > 0 || len(b.LANIPs) > 0 {
		t.Fatalf("cross-account state inherited: %+v", b)
	}
	if _, err := s.DeviceOfAccount("a_first", a.DeviceID); err != nil {
		t.Fatal("first owner lost device:", err)
	}
	again, created, err := s.BindDevice("a_second", req)
	if err != nil || created || again.DeviceID != b.DeviceID {
		t.Fatalf("same-account bind not idempotent: %+v %v %v", again, created, err)
	}
	reopened, err := NewStore(s.dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, pair := range []struct{ account, id string }{{"a_first", a.DeviceID}, {"a_second", b.DeviceID}} {
		d, created, err := reopened.BindDevice(pair.account, req)
		if err != nil || created || d.DeviceID != pair.id {
			t.Fatalf("reopen bind: %+v %v %v", d, created, err)
		}
	}
}

func TestCommandTerminalQueryableAndAckIdempotent(t *testing.T) {
	s := testStore(t)
	from, _, _ := s.BindDevice("a_one", RegisterRequest{Fingerprint: "from"})
	to, _, _ := s.BindDevice("a_one", RegisterRequest{Fingerprint: "to"})
	c, err := s.CreateCommand("a_one", from.DeviceID, to.DeviceID, "test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AckCommand("a_one", to.DeviceID, c.CommandID, cmdStatusRunning, ""); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AckCommand("a_one", to.DeviceID, c.CommandID, cmdStatusDone, "result one"); err != nil {
		t.Fatal(err)
	}
	got, err := s.GetCommand("a_one", c.CommandID)
	if err != nil || got.Detail != "result one" || got.Status != cmdStatusDone {
		t.Fatalf("terminal query: %+v %v", got, err)
	}
	updated := got.UpdatedAt
	if _, err := s.AckCommand("a_one", to.DeviceID, c.CommandID, cmdStatusDone, "result one"); err != nil {
		t.Fatal("duplicate ack:", err)
	}
	got, _ = s.GetCommand("a_one", c.CommandID)
	if !got.UpdatedAt.Equal(updated) {
		t.Fatal("retry extended result lifetime")
	}
	if _, err := s.AckCommand("a_one", to.DeviceID, c.CommandID, cmdStatusFailed, "different"); err == nil {
		t.Fatal("terminal state changed")
	}
	if len(s.PendingFor("a_one", to.DeviceID)) != 0 {
		t.Fatal("completed task redelivered")
	}
	s.cmds.byID[c.CommandID].UpdatedAt = time.Now().Add(-25 * time.Hour)
	if _, err := s.GetCommand("a_one", c.CommandID); err == nil {
		t.Fatal("expired terminal retained")
	}
}

func TestBindLegacyOwnedDeviceNeverMoves(t *testing.T) {
	dir := t.TempDir()
	old := []Device{{DeviceID: "d_old", AccountID: "a_old", Fingerprint: "legacy", LastStatus: json.RawMessage(`{"secret":1}`)}}
	raw, _ := json.Marshal(old)
	if err := os.WriteFile(filepath.Join(dir, "devices.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
	s, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	d, _, err := s.BindDevice("a_new", RegisterRequest{Fingerprint: "legacy"})
	if err != nil || d.DeviceID == "d_old" {
		t.Fatalf("legacy owner moved: %+v %v", d, err)
	}
}
