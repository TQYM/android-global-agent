package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type controlledWriter struct {
	*httptest.ResponseRecorder
	deadline time.Time
}

func (w *controlledWriter) SetWriteDeadline(t time.Time) error { w.deadline = t; return nil }

func TestLoggingWriterPreservesResponseController(t *testing.T) {
	base := &controlledWriter{ResponseRecorder: httptest.NewRecorder()}
	w := &loggingWriter{ResponseWriter: base, code: 200}
	rc := http.NewResponseController(w)
	deadline := time.Now().Add(time.Minute)
	if err := rc.SetWriteDeadline(deadline); err != nil {
		t.Fatal(err)
	}
	if !base.deadline.Equal(deadline) {
		t.Fatal("deadline lost")
	}
	w.WriteHeader(http.StatusAccepted)
	w.WriteHeader(http.StatusInternalServerError)
	if _, err := w.Write([]byte("data: one\n\n")); err != nil {
		t.Fatal(err)
	}
	if err := rc.Flush(); err != nil {
		t.Fatal(err)
	}
	if !base.Flushed || w.n != 11 || w.code != http.StatusAccepted {
		t.Fatalf("flush=%v bytes=%d status=%d", base.Flushed, w.n, w.code)
	}
}

func TestLoggingMiddlewareStreamsBeforeHandlerFinishes(t *testing.T) {
	release := make(chan struct{})
	sent := make(chan struct{})
	srv := &server{}
	s := httptest.NewServer(srv.logMW(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: one\n\n"))
		if err := http.NewResponseController(w).Flush(); err != nil {
			t.Error(err)
		}
		close(sent)
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer s.Close()
	defer close(release)
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(s.URL)
	if err != nil {
		t.Fatal("first chunk not flushed:", err)
	}
	defer resp.Body.Close()
	chunk := make([]byte, 11)
	if _, err := io.ReadFull(resp.Body, chunk); err != nil {
		t.Fatal(err)
	}
	if string(chunk) != "data: one\n\n" {
		t.Fatal(string(chunk))
	}
	<-sent
}

func TestLoggingWriterImplicitStatus(t *testing.T) {
	base := httptest.NewRecorder()
	w := &loggingWriter{ResponseWriter: base, code: 200}
	_, _ = w.Write([]byte("ok"))
	w.WriteHeader(http.StatusNotFound)
	if w.code != 200 || base.Code != 200 || w.n != 2 {
		t.Fatalf("wrong implicit status: %+v", w)
	}
}
