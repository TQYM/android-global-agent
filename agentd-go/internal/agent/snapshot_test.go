// SPDX-License-Identifier: Apache-2.0
package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"agentd/internal/config"
	"agentd/internal/llm"
	"agentd/internal/vision"
)

type snapshotTransport func(*http.Request) (*http.Response, error)

func (f snapshotTransport) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

type snapshotRequest struct{ image, text string }

// Only the Android bridge and capture are fixtures. Run, HTTP LLM requests,
// PNG decoding, resizing, JPEG encoding and Base64 all use production code.
func snapshotRig(t *testing.T, visual bool) (*Runner, *atomic.Int64, <-chan snapshotRequest) {
	t.Helper()
	old := a11yClient
	var sensed atomic.Int64
	a11yClient = &http.Client{Transport: snapshotTransport(func(req *http.Request) (*http.Response, error) {
		if req.URL.Path != "/nodes" {
			return nil, fmt.Errorf("unexpected bridge path: %s", req.URL.Path)
		}
		n := sensed.Add(1)
		text := fmt.Sprintf(`[{"index":1,"text":"node-%d","center_x":20,"center_y":30}]`, n)
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(text)), Request: req}, nil
	})}
	t.Cleanup(func() { a11yClient = old })
	var calls atomic.Int64
	requests := make(chan snapshotRequest, 4)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		n := calls.Add(1)
		var body struct {
			Messages []struct {
				Content json.RawMessage `json:"content"`
			} `json:"messages"`
		}
		if err := json.NewDecoder(req.Body).Decode(&body); err != nil || len(body.Messages) == 0 {
			t.Errorf("invalid chat request: %v", err)
			http.Error(w, "fixture", 400)
			return
		}
		var got snapshotRequest
		content := body.Messages[len(body.Messages)-1].Content
		if visual {
			var parts []llm.ContentPart
			if err := json.Unmarshal(content, &parts); err != nil {
				t.Errorf("vision body: %v", err)
			}
			for _, part := range parts {
				if part.Type == "text" {
					got.text = part.Text
				}
				if part.ImageURL != nil {
					got.image = part.ImageURL.URL
				}
			}
		} else if err := json.Unmarshal(content, &got.text); err != nil {
			t.Errorf("text body: %v", err)
		}
		requests <- got
		action := `{"action":"wait","ms":1}`
		if n == 2 {
			action = `{"action":"done","summary":"fixture completed"}`
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": action}}}})
	}))
	t.Cleanup(server.Close)
	r := &Runner{
		Cfg:        &config.Config{Vision: visual, MaxSteps: 3, SystemPrompt: "fixture"},
		ScreenPath: filepath.Join(t.TempDir(), "screen.png"),
		Client:     &llm.Client{BaseURL: server.URL, Model: "fixture", HC: server.Client()},
		Log:        func(string) {},
	}
	return r, &calls, requests
}

func TestSingleFreshScreenshotPerVisionDecision(t *testing.T) {
	r, calls, requests := snapshotRig(t, true)
	captures := 0
	r.captureScreenshot = func(path string) error {
		captures++
		img := image.NewRGBA(image.Rect(0, 0, 20, 20))
		shade := color.RGBA{R: 220, A: 255}
		if captures > 1 {
			shade = color.RGBA{B: 220, A: 255}
		}
		for y := 0; y < 20; y++ {
			for x := 0; x < 20; x++ {
				img.Set(x, y, shade)
			}
		}
		f, err := os.Create(path)
		if err != nil {
			return err
		}
		err = png.Encode(f, img)
		closeErr := f.Close()
		if err != nil {
			return err
		}
		return closeErr
	}
	summary, err := r.Run("fixture task")
	if err != nil || summary != "fixture completed" {
		t.Fatalf("Run: %q %v", summary, err)
	}
	if calls.Load() != 2 || captures != 2 {
		t.Fatalf("two decisions: requests=%d captures=%d", calls.Load(), captures)
	}
	first, second := <-requests, <-requests
	if first.image == "" || first.image == second.image {
		t.Fatal("image was absent or reused across an action")
	}
	if !strings.Contains(first.text, "node-1") || !strings.Contains(second.text, "node-2") {
		t.Fatal("node/image generation mismatch")
	}
	current, err := vision.DataURL(r.ScreenPath, 640, 60)
	if err != nil || current != second.image {
		t.Fatalf("UI file and latest model frame differ: %v", err)
	}
}

func TestTextModePreservesOnePostActionPreview(t *testing.T) {
	r, calls, requests := snapshotRig(t, false)
	captures := 0
	r.captureScreenshot = func(string) error { captures++; return nil }
	if _, err := r.Run("fixture task"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 || captures != 1 {
		t.Fatalf("text mode requests=%d captures=%d", calls.Load(), captures)
	}
	for i := 0; i < 2; i++ {
		if got := <-requests; got.image != "" || got.text == "" {
			t.Fatal("text mode content changed")
		}
	}
}

func TestVisionCaptureFailureStopsBeforeModelRequest(t *testing.T) {
	r, calls, _ := snapshotRig(t, true)
	r.captureScreenshot = func(string) error { return errors.New("capture fixture failure") }
	if _, err := r.Run("fixture"); err == nil || !strings.Contains(err.Error(), "capture fixture failure") {
		t.Fatalf("expected explicit capture failure: %v", err)
	}
	if calls.Load() != 0 {
		t.Fatal("vision failure was silently converted to a text request")
	}
}

func TestVisionEncodingFailureStopsBeforeModelRequest(t *testing.T) {
	r, calls, _ := snapshotRig(t, true)
	r.captureScreenshot = func(path string) error { return os.WriteFile(path, []byte("invalid PNG fixture"), 0600) }
	if _, err := r.Run("fixture"); err == nil || !strings.Contains(err.Error(), "视觉感知编码失败") {
		t.Fatalf("expected encoding failure: %v", err)
	}
	if calls.Load() != 0 {
		t.Fatal("encoding failure was silently converted to a text request")
	}
}

func TestOptionalTextPreviewFailureDoesNotChangeExecution(t *testing.T) {
	r, calls, _ := snapshotRig(t, false)
	var logs []string
	r.Log = func(text string) { logs = append(logs, text) }
	r.captureScreenshot = func(string) error { return errors.New("preview fixture failure") }
	if _, err := r.Run("fixture"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 || !strings.Contains(strings.Join(logs, "\n"), "preview fixture failure") {
		t.Fatal("optional preview error was hidden or affected text execution")
	}
}

func TestCancelledRunnerDoesNotCaptureOrRequest(t *testing.T) {
	r, calls, _ := snapshotRig(t, true)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r.Ctx = ctx
	captures := 0
	r.captureScreenshot = func(string) error { captures++; return nil }
	if _, err := r.Run("fixture"); err == nil {
		t.Fatal("cancelled runner was accepted")
	}
	if captures != 0 || calls.Load() != 0 {
		t.Fatal("cancelled runner performed I/O")
	}
}
