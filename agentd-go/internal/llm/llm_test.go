// SPDX-License-Identifier: Apache-2.0

package llm

import (
	"context"
	"strings"
	"testing"
	"time"
)

// 已取消的 ctx 必须让请求立即失败——这是「停止任务」不再等 180 秒超时的前提。
func TestChatCtxFailsFastOnCancelledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	c := &Client{BaseURL: "https://127.0.0.1:1/v1", APIKey: "k", Model: "m"}
	start := time.Now()
	_, err := c.ChatCtx(ctx, []Message{TextMessage("user", "hi")})
	if err == nil {
		t.Fatal("已取消的 ctx 应立即返回错误")
	}
	if !strings.Contains(err.Error(), "context canceled") {
		t.Fatalf("错误应说明取消原因，实为: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("取消未即时生效，耗时 %v", elapsed)
	}
}

// 取消发生在请求进行中（而不是调用前）时同样要立刻返回。
func TestChatCtxCancelsInFlightRequest(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()

	// 指向一个不会应答的地址：若无 ctx 取消，这里会一直等到客户端超时。
	c := &Client{BaseURL: "https://10.255.255.1:443/v1", APIKey: "k", Model: "m"}
	done := make(chan error, 1)
	go func() {
		_, err := c.ChatCtx(ctx, []Message{TextMessage("user", "hi")})
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("取消后应返回错误")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("取消后 5 秒仍未被中断")
	}
}
