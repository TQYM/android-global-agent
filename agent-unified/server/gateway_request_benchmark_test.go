package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func BenchmarkGatewayRequest(b *testing.B) {
	for _, size := range []int{8 << 10, 128 << 10, 1 << 20} {
		for _, stream := range []bool{false, true} {
			body := []byte(fmt.Sprintf(`{"model":"old","stream":%t,"messages":[{"role":"user","content":[{"type":"text","text":"%s"},{"type":"image_url","image_url":{"url":"data:image/png;base64,%s"}}]}],"stream_options":{"include_usage":false,"extension":1},"tools":[{"type":"function","function":{"name":"tool","parameters":{"type":"object"}}}]}`, stream, strings.Repeat("x", size/2), strings.Repeat("a", size/2)))
			g := newGateway("https://api.deepseek.example/v1", "key", "mapped")
			for _, single := range []bool{false, true} {
				style := "legacy-multi-decode"
				if single {
					style = "single-raw-map"
				}
				b.Run(fmt.Sprintf("%s/stream=%t/bytes=%d", style, stream, size), func(b *testing.B) {
					b.ReportAllocs()
					b.SetBytes(int64(len(body)))
					b.ResetTimer()
					for i := 0; i < b.N; i++ {
						var err error
						if single {
							_, _, _, err = g.prepareChatRequest(body)
						} else {
							_, err = gatewayMultiDecodeBaseline(g, body)
						}
						if err != nil {
							b.Fatal(err)
						}
					}
				})
			}
		}
	}
}

// 仅供基准比较，复刻审计前 probe→ensureIncludeUsage→adaptRequest 的解码/编码次数。
func gatewayMultiDecodeBaseline(g *gatewayConfig, body []byte) ([]byte, error) {
	var probe struct {
		Stream bool   `json:"stream"`
		Model  string `json:"model"`
	}
	if err := json.Unmarshal(body, &probe); err != nil {
		return nil, err
	}
	if probe.Stream {
		var fields map[string]json.RawMessage
		if err := json.Unmarshal(body, &fields); err != nil {
			return nil, err
		}
		var options map[string]json.RawMessage
		if err := json.Unmarshal(fields["stream_options"], &options); err != nil {
			return nil, err
		}
		options["include_usage"] = json.RawMessage("true")
		raw, err := json.Marshal(options)
		if err != nil {
			return nil, err
		}
		fields["stream_options"] = raw
		body, err = json.Marshal(fields)
		if err != nil {
			return nil, err
		}
	}
	var all map[string]interface{}
	if err := json.Unmarshal(body, &all); err != nil {
		return nil, err
	}
	if g.upstreamModel != "" && probe.Model != g.upstreamModel {
		all["model"] = g.upstreamModel
	}
	if strings.Contains(strings.ToLower(g.baseURL), "deepseek") {
		if _, ok := all["thinking"]; !ok {
			all["thinking"] = map[string]string{"type": "disabled"}
		}
	}
	return json.Marshal(all)
}
