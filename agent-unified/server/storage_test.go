package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func storageItem(id string) PendingItem {
	return PendingItem{Kind: kindTask, Item: Incoming{ID: id, Data: json.RawMessage(`{"ok":true}`)}}
}

func storageFailNextSync(s *Store) error {
	fault := errors.New("injected storage fsync failure")
	first := true
	s.storageSync = func(f *os.File) error {
		if first {
			first = false
			return fault
		}
		return f.Sync()
	}
	return fault
}

func TestStorageAppendSyncFailureDoesNotPublishAndRetryKeepsSequence(t *testing.T) {
	s := testStore(t)
	first := storageItem("first")
	if _, err := s.Append("a_one", "d_one", []PendingItem{first}); err != nil {
		t.Fatal(err)
	}
	before, _ := os.Stat(s.recordPath("a_one"))
	fault := storageFailNextSync(s)
	items := []PendingItem{first, storageItem("second"), storageItem("third")}
	res, err := s.Append("a_one", "d_two", items)
	if !errors.Is(err, fault) || res.Accepted != 0 || res.Seq != 1 || s.lastSeq["a_one"] != 1 || len(s.seenIDs["a_one"]) != 1 || len(s.recordIndex["a_one"]) != 1 {
		t.Fatalf("failed append published state: res=%+v err=%v seq=%d seen=%d index=%d", res, err, s.lastSeq["a_one"], len(s.seenIDs["a_one"]), len(s.recordIndex["a_one"]))
	}
	after, _ := os.Stat(s.recordPath("a_one"))
	if after.Size() != before.Size() {
		t.Fatal("failed append tail was not rolled back")
	}
	reopened, err := NewStore(s.dir)
	if err != nil || reopened.lastSeq["a_one"] != 1 {
		t.Fatalf("reopen failed batch: %v", err)
	}
	s.storageSync = nil
	res, err = s.Append("a_one", "d_two", items)
	if err != nil || res.Accepted != 2 || res.Duplicates != 1 || res.Seq != 3 {
		t.Fatalf("retry=%+v %v", res, err)
	}
	recs, last, err := s.Pull("a_one", 0, 100)
	if err != nil || last != 3 || len(recs) != 3 || recs[1].Seq != 2 || recs[1].DeviceID != "d_two" {
		t.Fatalf("retry records: %+v last=%d err=%v", recs, last, err)
	}
}

func TestStorageRollbackFailureStopsFurtherWrites(t *testing.T) {
	s := testStore(t)
	fault := errors.New("persistent fsync failure")
	s.storageSync = func(*os.File) error { return fault }
	res, err := s.Append("a_one", "d_one", []PendingItem{storageItem("one")})
	if !errors.Is(err, fault) || s.storageErr == nil || res.Accepted != 0 || len(s.seenIDs["a_one"]) != 0 || len(s.recordIndex["a_one"]) != 0 || s.lastSeq["a_one"] != 0 {
		t.Fatalf("uncertain rollback published state: %+v %v", res, err)
	}
	s.storageSync = nil
	if _, err := s.Append("a_two", "d_two", []PendingItem{storageItem("two")}); err == nil {
		t.Fatal("write resumed after uncertain rollback")
	}
	if _, err := os.Stat(s.recordPath("a_two")); !os.IsNotExist(err) {
		t.Fatalf("blocked write touched other file: %v", err)
	}
	if _, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: "new"}); err == nil || len(s.devices) != 0 {
		t.Fatal("device write resumed after uncertain rollback")
	}
}

func TestStorageOpenFailureDoesNotPublish(t *testing.T) {
	s := testStore(t)
	if err := os.Mkdir(s.recordPath("a_blocked"), 0700); err != nil {
		t.Fatal(err)
	}
	res, err := s.Append("a_blocked", "d_one", []PendingItem{storageItem("one")})
	if err == nil || res.Accepted != 0 || s.lastSeq["a_blocked"] != 0 || len(s.seenIDs["a_blocked"]) != 0 || len(s.recordIndex["a_blocked"]) != 0 {
		t.Fatalf("open failure changed state: %+v %v", res, err)
	}
	if _, err := s.Append("a_other", "d_one", []PendingItem{storageItem("one")}); err != nil {
		t.Fatal("ordinary open error poisoned other account:", err)
	}
}

func TestStorageRejectedRecordsDoNotConsumeSequenceAndDedupIsAccountWide(t *testing.T) {
	s := testStore(t)
	bad := storageItem("repair")
	bad.Item.Data = json.RawMessage(`{"broken":`)
	first := storageItem("same")
	first.Origin = "d_origin"
	memory := first
	memory.Kind = kindMemory
	res, err := s.Append("a_one", "d_sender", []PendingItem{bad, first, first, memory})
	if err != nil || res.Accepted != 2 || res.Duplicates != 1 || res.Rejected != 1 || res.Seq != 2 {
		t.Fatalf("batch=%+v %v", res, err)
	}
	s.storageSync = func(*os.File) error { t.Fatal("empty or duplicate batch fsynced"); return nil }
	for _, items := range [][]PendingItem{nil, {first, memory}} {
		res, err := s.Append("a_one", "d_other", items)
		if err != nil || res.Accepted != 0 || res.Seq != 2 {
			t.Fatalf("no-op append: %+v %v", res, err)
		}
	}
	s.storageSync = nil
	if res, err := s.Append("a_two", "d_other", []PendingItem{first}); err != nil || res.Accepted != 1 || res.Seq != 1 {
		t.Fatalf("cross-account id collision: %+v %v", res, err)
	}
	if res, err := s.Append("a_one", "d_other", []PendingItem{storageItem("repair")}); err != nil || res.Accepted != 1 || res.Seq != 3 {
		t.Fatalf("rejected id became duplicate: %+v %v", res, err)
	}
	recs, last, err := s.Pull("a_one", 0, 100)
	if err != nil || last != 3 || len(recs) != 3 || recs[0].Seq != 1 || recs[0].DeviceID != "d_origin" || recs[1].Seq != 2 {
		t.Fatalf("sequence/origin changed: %+v %d %v", recs, last, err)
	}
}

func TestStorageDeviceFailuresDoNotPublish(t *testing.T) {
	cases := []struct {
		name  string
		write func(*Store, *Device) error
	}{
		{"new-bind", func(s *Store, d *Device) error {
			_, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: "new"})
			return err
		}},
		{"existing-bind", func(s *Store, d *Device) error {
			_, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: d.Fingerprint, Name: "changed"})
			return err
		}},
		{"touch", func(s *Store, d *Device) error { return s.Touch(d.DeviceID, json.RawMessage(`{"changed":true}`)) }},
		{"disable", func(s *Store, d *Device) error { return s.SetDisabled(d.DeviceID, true) }},
		{"lan", func(s *Store, d *Device) error {
			return s.RecordLANPresence(d.DeviceID, "203.0.113.2", []string{"192.168.0.2"}, 99)
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := testStore(t)
			d, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: "existing", Name: "before"})
			if err != nil {
				t.Fatal(err)
			}
			if err := s.Touch(d.DeviceID, json.RawMessage(`{"before":true}`)); err != nil {
				t.Fatal(err)
			}
			before := cloneDevice(s.devices[d.DeviceID])
			fileBefore, _ := os.Stat(s.devicesPath())
			fault := storageFailNextSync(s)
			if err := tc.write(s, d); !errors.Is(err, fault) {
				t.Fatalf("expected sync fault: %v", err)
			}
			if len(s.devices) != 1 || len(s.byFinger) != 1 || !reflect.DeepEqual(before, *s.devices[d.DeviceID]) {
				t.Fatalf("failed %s changed device/index: %+v", tc.name, s.devices[d.DeviceID])
			}
			fileAfter, _ := os.Stat(s.devicesPath())
			if fileAfter.Size() != fileBefore.Size() {
				t.Fatal("device journal tail remained")
			}
			reopened, err := NewStore(s.dir)
			if err != nil || !reflect.DeepEqual(before, *reopened.devices[d.DeviceID]) {
				t.Fatalf("failed device update recovered: %v", err)
			}
		})
	}
}

func TestStorageDeviceSnapshotsDoNotAliasMutableInputs(t *testing.T) {
	s := testStore(t)
	d, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: "one"})
	if err != nil {
		t.Fatal(err)
	}
	status := json.RawMessage(`{"ok":true}`)
	if err := s.Touch(d.DeviceID, status); err != nil {
		t.Fatal(err)
	}
	status[2] = 'X'
	if err := s.RecordLANPresence(d.DeviceID, "203.0.113.1", []string{"192.168.1.1"}, 80); err != nil {
		t.Fatal(err)
	}
	got, err := s.DeviceOfAccount("a_one", d.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	got.LastStatus[2] = 'Y'
	got.LANIPs[0] = "changed"
	list := s.Devices()
	list[0].LastStatus[2] = 'Z'
	list[0].LANIPs[0] = "changed-again"
	again, _ := s.DeviceOfAccount("a_one", d.DeviceID)
	if string(again.LastStatus) != `{"ok":true}` || again.LANIPs[0] != "192.168.1.1" {
		t.Fatalf("snapshot aliases stored data: %+v", again)
	}
}

func TestStorageLegacyDevicesAndRecordsMigrateOnce(t *testing.T) {
	dir := t.TempDir()
	legacy := []Device{{DeviceID: "d_legacy", Fingerprint: "unowned", Name: "old", LastStatus: json.RawMessage(`{"legacy":true}`)}, {DeviceID: "d_owned", AccountID: "a_owner", Fingerprint: "owned"}}
	raw, _ := json.Marshal(legacy)
	legacyPath := filepath.Join(dir, "devices.json")
	if err := os.WriteFile(legacyPath, raw, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "records"), 0700); err != nil {
		t.Fatal(err)
	}
	var records bytes.Buffer
	for i := 0; i < 2; i++ {
		line, _ := json.Marshal(Record{Seq: int64(i + 1), Kind: kindTask, ID: fmt.Sprint(i), Data: json.RawMessage(`{"legacy":true}`)})
		records.Write(line)
		records.WriteByte('\n')
	}
	if err := os.WriteFile(filepath.Join(dir, "records", "d_legacy.jsonl"), records.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	s, err := NewStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	d, created, err := s.BindDevice("a_new", RegisterRequest{Fingerprint: "unowned", Name: "new"})
	if err != nil || created || d.DeviceID != "d_legacy" || s.byFinger["\x00unowned"] != "" || s.byFinger["a_new\x00unowned"] != "d_legacy" {
		t.Fatalf("legacy claim=%+v %v %v", d, created, err)
	}
	other, created, err := s.BindDevice("a_new", RegisterRequest{Fingerprint: "owned"})
	if err != nil || !created || other.DeviceID == "d_owned" || s.devices["d_owned"].AccountID != "a_owner" {
		t.Fatalf("legacy owner moved: %+v %v %v", other, created, err)
	}
	recs, last, err := s.Pull("a_new", 0, 10)
	if err != nil || last != 2 || len(recs) != 2 || recs[0].DeviceID != "d_legacy" {
		t.Fatalf("migration=%+v %d %v", recs, last, err)
	}
	archived, _ := os.ReadFile(legacyPath)
	if !bytes.Equal(raw, archived) {
		t.Fatal("legacy device snapshot was dual-written")
	}
	if err := os.WriteFile(legacyPath, []byte(`{"corrupted":true}`), 0600); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewStore(dir)
	if err != nil || reopened.devices["d_legacy"].AccountID != "a_new" {
		t.Fatalf("legacy snapshot reread: %v", err)
	}
	if _, _, err := reopened.BindDevice("a_new", RegisterRequest{Fingerprint: "unowned"}); err != nil {
		t.Fatal(err)
	}
	recs, last, err = reopened.Pull("a_new", 0, 10)
	if err != nil || len(recs) != 2 || last != 2 {
		t.Fatalf("migration repeated: %+v %d %v", recs, last, err)
	}
}

func TestStoragePartialTailsAreNotPublishedAfterRestart(t *testing.T) {
	s := testStore(t)
	d, _, err := s.BindDevice("a_one", RegisterRequest{Fingerprint: "one"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Append("a_one", d.DeviceID, []PendingItem{storageItem("one")}); err != nil {
		t.Fatal(err)
	}
	paths := []string{s.devicesPath(), s.recordPath("a_one")}
	sizes := make([]int64, len(paths))
	for i, path := range paths {
		st, _ := os.Stat(path)
		sizes[i] = st.Size()
		f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0600)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := f.WriteString(`{"seq":2,"device_id":"incomplete"`); err != nil {
			t.Fatal(err)
		}
		if err := f.Close(); err != nil {
			t.Fatal(err)
		}
	}
	reopened, err := NewStore(s.dir)
	if err != nil || reopened.lastSeq["a_one"] != 1 || reopened.devices["incomplete"] != nil {
		t.Fatalf("partial tail applied: %v", err)
	}
	for i, path := range paths {
		st, _ := os.Stat(path)
		if st.Size() != sizes[i] {
			t.Fatalf("tail not trimmed: %s", path)
		}
	}
	if res, err := reopened.Append("a_one", d.DeviceID, []PendingItem{storageItem("two")}); err != nil || res.Seq != 2 || res.Accepted != 1 {
		t.Fatalf("tail recovery append=%+v %v", res, err)
	}
}

func TestStorageCompleteCorruptionIsAnError(t *testing.T) {
	for _, target := range []string{"devices", "records", "sequence"} {
		t.Run(target, func(t *testing.T) {
			s := testStore(t)
			path, raw := s.devicesPath(), []byte("{broken}\n")
			if target != "devices" {
				path = s.recordPath("a_one")
			}
			if target == "sequence" {
				raw = []byte("{\"seq\":2,\"kind\":\"task\",\"id\":\"a\"}\n{\"seq\":1,\"kind\":\"task\",\"id\":\"b\"}\n")
			}
			if err := os.WriteFile(path, raw, 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := NewStore(s.dir); err == nil {
				t.Fatal("complete corrupt row was silently skipped")
			}
		})
	}
}

func TestStoragePullSparseSequenceAndFutureCursor(t *testing.T) {
	s := testStore(t)
	var data bytes.Buffer
	for _, seq := range []int64{2, 5, 10} {
		line, _ := json.Marshal(Record{Seq: seq, DeviceID: "d_one", Kind: kindTask, ID: fmt.Sprint(seq)})
		data.Write(line)
		data.WriteString("\n\n")
	}
	if err := os.WriteFile(s.recordPath("a_one"), data.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewStore(s.dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		since int64
		limit int
		want  []int64
	}{{0, 2, []int64{2, 5}}, {3, 1, []int64{5}}, {5, 0, []int64{10}}, {10, 1, []int64{}}, {99, 1001, []int64{}}} {
		recs, last, err := reopened.Pull("a_one", tc.since, tc.limit)
		seqs := make([]int64, len(recs))
		for i, rec := range recs {
			seqs[i] = rec.Seq
		}
		if err != nil || last != 10 || !reflect.DeepEqual(seqs, tc.want) {
			t.Fatalf("since=%d got=%v last=%d err=%v", tc.since, seqs, last, err)
		}
	}
}

func TestStorageLargeEscapedRecordCanBePulledAndReindexed(t *testing.T) {
	s := testStore(t)
	item := storageItem("large")
	item.Item.Data = json.RawMessage(`{"text":"` + strings.Repeat("<", maxPayloadB-len(`{"text":""}`)) + `"}`)
	if _, err := s.Append("a_one", "d_one", []PendingItem{item}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		recs, last, err := s.Pull("a_one", 0, 1)
		if err != nil || last != 1 || len(recs) != 1 {
			t.Fatalf("large pull %d: last=%d len=%d err=%v", i, last, len(recs), err)
		}
		var got map[string]string
		if err := json.Unmarshal(recs[0].Data, &got); err != nil || len(got["text"]) != maxPayloadB-len(`{"text":""}`) {
			t.Fatalf("large data=%v", err)
		}
		s, err = NewStore(s.dir)
		if err != nil {
			t.Fatal(err)
		}
	}
}

func TestStorageSequenceExhaustionDoesNotWrap(t *testing.T) {
	s := testStore(t)
	raw, _ := json.Marshal(Record{Seq: math.MaxInt64, DeviceID: "d_one", Kind: kindTask, ID: "last"})
	if err := os.WriteFile(s.recordPath("a_one"), append(raw, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	s, err := NewStore(s.dir)
	if err != nil {
		t.Fatal(err)
	}
	res, err := s.Append("a_one", "d_one", []PendingItem{storageItem("next")})
	if err == nil || res.Accepted != 0 || s.lastSeq["a_one"] != math.MaxInt64 || len(s.seenIDs["a_one"]) != 1 {
		t.Fatalf("seq wrapped: %+v %v", res, err)
	}
}

func TestStorageConcurrentAppendAndPullKeepOneSnapshot(t *testing.T) {
	s := testStore(t)
	var wg sync.WaitGroup
	errs := make(chan error, 2)
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			if _, err := s.Append("a_one", "d_one", []PendingItem{storageItem(fmt.Sprint(i))}); err != nil {
				errs <- err
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 150; i++ {
			recs, last, err := s.Pull("a_one", 0, 1000)
			if err != nil {
				errs <- err
				return
			}
			for j, rec := range recs {
				if rec.Seq != int64(j+1) || rec.Seq > last {
					errs <- fmt.Errorf("seq=%d last=%d at=%d", rec.Seq, last, j)
					return
				}
			}
			if len(recs) != int(last) {
				errs <- fmt.Errorf("snapshot rows=%d last=%d", len(recs), last)
				return
			}
		}
	}()
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
}

func TestStorageConcurrentLoginsPreserveQuotaAndOtherTokens(t *testing.T) {
	s := testStore(t)
	salt := make([]byte, acctSaltLen)
	hash, err := hashAccountKey("test-key-123456", salt, 10000)
	if err != nil {
		t.Fatal(err)
	}
	a := Account{AccountID: "a_login", Name: "login_test", KeyHash: base64.StdEncoding.EncodeToString(hash), KeySalt: base64.StdEncoding.EncodeToString(salt), KeyIter: 10000, Quota: 9}
	s.mu.Lock()
	err = s.commitBillingLocked(billingTxn{Accounts: []Account{a}})
	s.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	var wg sync.WaitGroup
	errs := make(chan error, 16)
	tokens := make(chan string, 8)
	for i := 0; i < 8; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			<-start
			_, token, err := s.LoginAccount(a.Name, "test-key-123456")
			if err != nil {
				errs <- err
			} else {
				tokens <- token
			}
		}()
		go func() {
			defer wg.Done()
			<-start
			if err := s.AddQuota(a.AccountID, 1, 0); err != nil {
				errs <- err
			}
		}()
	}
	close(start)
	wg.Wait()
	close(errs)
	close(tokens)
	for err := range errs {
		t.Fatal(err)
	}
	for token := range tokens {
		if _, err := s.AccountByToken(token); err != nil {
			t.Fatal("concurrent login invalidated token:", err)
		}
	}
	reopened, err := NewStore(s.dir)
	if err != nil || reopened.accounts[a.AccountID].Quota != 17 || len(reopened.accounts[a.AccountID].Tokens) != 8 {
		t.Fatalf("login overwrote current state: %v", err)
	}
}
