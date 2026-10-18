package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

// 比较代码仅保留于 benchmark，生产 Touch/Pull 各只有一份实现。
func BenchmarkStorageTouch(b *testing.B) {
	for _, count := range []int{1, 1000, 10000} {
		for _, indexed := range []bool{false, true} {
			style := "legacy-full-snapshot"
			if indexed {
				style = "single-row-journal"
			}
			b.Run(fmt.Sprintf("%s/devices=%d", style, count), func(b *testing.B) {
				dir := b.TempDir()
				devices := make([]Device, count)
				for i := range devices {
					devices[i] = Device{DeviceID: fmt.Sprintf("d_%d", i), AccountID: "a_bench", Fingerprint: fmt.Sprint(i), Name: "bench"}
				}
				raw, err := json.Marshal(devices)
				if err != nil {
					b.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(dir, "devices.json"), raw, 0600); err != nil {
					b.Fatal(err)
				}
				s, err := NewStore(dir)
				if err != nil {
					b.Fatal(err)
				}
				before, _ := os.Stat(s.devicesPath())
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					if indexed {
						if err := s.Touch("d_0", nil); err != nil {
							b.Fatal(err)
						}
					} else {
						// 审计前 saveDevicesLocked：拷贝整表、缩进编码、fsync+rename+目录 fsync。
						list := make([]Device, 0, len(s.devices))
						for _, d := range s.devices {
							list = append(list, *d)
						}
						raw, err := json.MarshalIndent(list, "", "  ")
						if err != nil {
							b.Fatal(err)
						}
						if err := atomicWrite(filepath.Join(dir, "baseline-devices.json"), raw, 0600); err != nil {
							b.Fatal(err)
						}
					}
				}
				b.StopTimer()
				if indexed {
					after, _ := os.Stat(s.devicesPath())
					b.ReportMetric(float64(after.Size()-before.Size())/float64(b.N), "journal-bytes/op")
				} else {
					st, _ := os.Stat(filepath.Join(dir, "baseline-devices.json"))
					b.ReportMetric(float64(st.Size()), "snapshot-bytes/op")
				}
			})
		}
	}
}

func BenchmarkStoragePullTail(b *testing.B) {
	for _, count := range []int{1000, 100000} {
		for _, indexed := range []bool{false, true} {
			style := "legacy-full-scan"
			if indexed {
				style = "sequence-index"
			}
			b.Run(fmt.Sprintf("%s/records=%d", style, count), func(b *testing.B) {
				dir := b.TempDir()
				s, err := NewStore(dir)
				if err != nil {
					b.Fatal(err)
				}
				var data bytes.Buffer
				for i := 0; i < count; i++ {
					line, err := json.Marshal(Record{Seq: int64(i + 1), DeviceID: "d_bench", Kind: kindTask, ID: fmt.Sprint(i), Data: json.RawMessage(`{"ok":true}`)})
					if err != nil {
						b.Fatal(err)
					}
					data.Write(line)
					data.WriteByte('\n')
				}
				if err := os.WriteFile(s.recordPath("a_bench"), data.Bytes(), 0600); err != nil {
					b.Fatal(err)
				}
				s, err = NewStore(dir)
				if err != nil {
					b.Fatal(err)
				}
				since := int64(count - 200)
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					var recs []Record
					var err error
					if indexed {
						recs, _, err = s.Pull("a_bench", since, 200)
					} else {
						recs, err = storageFullScanBaseline(s.recordPath("a_bench"), since, 200)
					}
					if err != nil || len(recs) != 200 {
						b.Fatalf("pull rows=%d err=%v", len(recs), err)
					}
				}
			})
		}
	}
}

func storageFullScanBaseline(path string, since int64, limit int) ([]Record, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := make([]Record, 0, 64)
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), maxPayloadB+64*1024)
	for sc.Scan() {
		var rec Record
		if err := json.Unmarshal(sc.Bytes(), &rec); err != nil {
			continue
		}
		if rec.Seq <= since {
			continue
		}
		out = append(out, rec)
		if len(out) >= limit {
			break
		}
	}
	return out, sc.Err()
}
