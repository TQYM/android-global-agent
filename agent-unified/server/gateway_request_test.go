package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"
)

func TestGatewayRequestKeepsUnknownFieldsAndIntegerPrecision(t *testing.T) {
	g := newGateway("https://deepseek.example/v1", "key", "mapped-model")
	body := []byte(`{"model":"old","stream":true,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}]}],"tools":[{"type":"function","function":{"name":"tool","parameters":{"type":"object","properties":{"count":{"const":9007199254740993}}}}}],"tool_choice":{"type":"function","function":{"name":"tool"}},"extension":{"n":9007199254740993,"decimal":1.234567890123456789,"nested":[null,true,"<text>"]},"stream_options":{"include_usage":false,"provider_extra":{"n":9007199254740993}}}`)
	out, model, stream, err := g.prepareChatRequest(body)
	if err != nil || model != "mapped-model" || !stream {
		t.Fatalf("prepare: model=%q stream=%v err=%v", model, stream, err)
	}
	decode := func(raw []byte) map[string]any {
		t.Helper()
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var obj map[string]any
		if err := dec.Decode(&obj); err != nil {
			t.Fatal(err)
		}
		return obj
	}
	before, after := decode(body), decode(out)
	for _, field := range []string{"messages", "tools", "tool_choice", "extension"} {
		if !reflect.DeepEqual(before[field], after[field]) {
			t.Fatalf("field %s changed: %#v -> %#v", field, before[field], after[field])
		}
	}
	oldOptions := before["stream_options"].(map[string]any)
	options := after["stream_options"].(map[string]any)
	if options["include_usage"] != true || !reflect.DeepEqual(options["provider_extra"], oldOptions["provider_extra"]) {
		t.Fatalf("stream options changed incorrectly: %#v", options)
	}
	if !reflect.DeepEqual(after["thinking"], map[string]any{"type": "disabled"}) {
		t.Fatalf("thinking=%#v", after["thinking"])
	}
}

func TestGatewayRequestStreamOptionsBoundaries(t *testing.T) {
	g := newGateway("https://upstream.example/v1", "key", "")
	for _, options := range []string{"", `,"stream_options":null`, `,"stream_options":{}`, `,"stream_options":{"include_usage":false}`, `,"stream_options":{"include_usage":null}`, `,"stream_options":{"include_usage":true,"extra":1}`} {
		t.Run(options, func(t *testing.T) {
			out, _, stream, err := g.prepareChatRequest([]byte(`{"model":"m","stream":true,"messages":[]` + options + `}`))
			if err != nil || !stream {
				t.Fatalf("stream=%v err=%v", stream, err)
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(out, &fields); err != nil {
				t.Fatal(err)
			}
			var got map[string]json.RawMessage
			if err := json.Unmarshal(fields["stream_options"], &got); err != nil || string(got["include_usage"]) != "true" {
				t.Fatalf("options=%s err=%v", fields["stream_options"], err)
			}
		})
	}
}

func TestGatewayRequestUnchangedBodyIsReturnedVerbatim(t *testing.T) {
	g := newGateway("https://upstream.example/v1", "key", "")
	for _, body := range []string{
		` { "messages": [], "model":"m", "extra":9007199254740993 } `,
		`{"model":"m","stream":true,"stream_options":{"include_usage":true,"extra":1},"messages":[]}`,
		`{"model":null,"stream":null,"messages":[],"stream_options":"not-used-for-nonstream"}`,
	} {
		out, _, _, err := g.prepareChatRequest([]byte(body))
		if err != nil || string(out) != body {
			t.Fatalf("body rewritten without changes: %q %v", out, err)
		}
	}
	g = newGateway("https://deepseek.example/v1", "key", "")
	body := []byte(`{"model":"m","messages":[],"thinking":{"type":"enabled","extra":9007199254740993}}`)
	out, _, _, err := g.prepareChatRequest(body)
	if err != nil || !bytes.Equal(body, out) {
		t.Fatalf("explicit thinking was overridden: %s %v", out, err)
	}
}

func TestGatewayRequestRejectsBadTypesWithoutFallback(t *testing.T) {
	g := newGateway("https://upstream.example/v1", "key", "")
	for _, body := range []string{`null`, `[]`, `true`, `1`, `"text"`, `{"model":1}`, `{"stream":"true"}`, `{"stream":true,"stream_options":[]}`, `{"stream":true,"stream_options":1}`, `{"stream":true,"stream_options":"text"}`, `{"model":"m"} {}`} {
		t.Run(body, func(t *testing.T) {
			if _, _, _, err := g.prepareChatRequest([]byte(body)); err == nil {
				t.Fatal("invalid request accepted")
			}
		})
	}
}

func TestGatewayHandlerUsesPreparedRequestAndChargesEffectiveModel(t *testing.T) {
	requests := make(chan []byte, 1)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" || r.Header.Get("Authorization") != "Bearer upstream-key" {
			t.Errorf("wrong upstream request: %s %s", r.URL.Path, r.Header.Get("Authorization"))
		}
		captured, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		requests <- captured
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"ok"}}],"usage":{"total_tokens":1000}}`)
	}))
	defer upstream.Close()
	s := testStore(t)
	a, _, err := s.RegisterAccount("gateway_test", "test-key-123456")
	if err != nil {
		t.Fatal(err)
	}
	srv := &server{store: s, gateway: newGateway(upstream.URL, "upstream-key", "mapped")}
	r := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(`{"model":"client","messages":[],"tools":[{"extension":9007199254740993}]}`))
	w := httptest.NewRecorder()
	srv.handleChatCompletions(w, r, a)
	var captured []byte
	select {
	case captured = <-requests:
	default:
		t.Fatalf("upstream was not called: status=%d response=%s", w.Code, w.Body.Bytes())
	}
	if w.Code != http.StatusOK || !bytes.Contains(captured, []byte(`"model":"mapped"`)) || !bytes.Contains(captured, []byte("9007199254740993")) {
		t.Fatalf("status=%d upstream=%s response=%s", w.Code, captured, w.Body.Bytes())
	}
	if srv.gwInflightGlobal != 0 || s.accounts[a.AccountID].QuotaUsed != 1 {
		t.Fatal("slot or usage accounting not preserved")
	}
	ledger := gatewayLastLedger(t, s)
	if ledger.Reason != "llm:mapped" || ledger.Delta != -1 {
		t.Fatalf("effective model not used for charge: %+v", ledger)
	}
}

func TestGatewayStreamingRequestPreservesFlushAndUsageAccounting(t *testing.T) {
	wire := "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
		"data: {\"choices\":[],\"usage\":{\"total_tokens\":1501}}\n\n" +
		"data: [DONE]\n\n"
	requests := make(chan []byte, 1)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		requests <- captured
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, wire)
	}))
	defer upstream.Close()
	s := testStore(t)
	a, _, err := s.RegisterAccount("gateway_stream", "test-key-123456")
	if err != nil {
		t.Fatal(err)
	}
	srv := &server{store: s, gateway: newGateway(upstream.URL, "key", "mapped")}
	r := httptest.NewRequest(http.MethodPost, "/v1/chat/completions", strings.NewReader(`{"model":"client","stream":true,"messages":[],"stream_options":{"include_usage":false,"extension":9007199254740993}}`))
	w := httptest.NewRecorder()
	srv.handleChatCompletions(&loggingWriter{ResponseWriter: w}, r, a)
	var captured []byte
	select {
	case captured = <-requests:
	default:
		t.Fatalf("stream upstream was not called: %d %s", w.Code, w.Body.Bytes())
	}
	if w.Code != http.StatusOK || !w.Flushed || w.Body.String() != wire || !bytes.Contains(captured, []byte(`"include_usage":true`)) || !bytes.Contains(captured, []byte("9007199254740993")) {
		t.Fatalf("stream status=%d flushed=%v upstream=%s response=%s", w.Code, w.Flushed, captured, w.Body.Bytes())
	}
	if srv.gwInflightGlobal != 0 || s.accounts[a.AccountID].QuotaUsed != 2 {
		t.Fatal("stream usage or slot not preserved")
	}
	ledger := gatewayLastLedger(t, s)
	if ledger.Reason != "llm:mapped" || ledger.Delta != -2 {
		t.Fatalf("stream ledger=%+v", ledger)
	}
}

func gatewayLastLedger(t *testing.T, s *Store) *Ledger {
	t.Helper()
	raw, err := os.ReadFile(s.billingPath())
	if err != nil {
		t.Fatal(err)
	}
	lines := bytes.Split(bytes.TrimSpace(raw), []byte{'\n'})
	var tx billingTxn
	if err := json.Unmarshal(lines[len(lines)-1], &tx); err != nil || tx.Ledger == nil {
		t.Fatalf("last billing record has no ledger: %v", err)
	}
	return tx.Ledger
}
