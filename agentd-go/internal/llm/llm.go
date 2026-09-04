// Package llm is a minimal OpenAI-compatible chat client (no external deps).
//
// Android ships no /etc/resolv.conf, so Go's pure resolver falls back to
// localhost:53 and fails. When that happens we resolve through the system
// (netd) by shelling out to ping, pin the IP for dialing, and keep the real
// hostname for TLS SNI + HTTP Host so certificates still validate.
package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// Message is one chat message. Content is normally a plain string; for
// vision models it can be a []ContentPart mixing text and image parts
// (OpenAI-compatible multimodal shape, accepted by GLM-4V series).
type Message struct {
	Role    string      `json:"role"`
	Content interface{} `json:"content"`
}

// ContentPart is one element of a multimodal content array.
type ContentPart struct {
	Type     string    `json:"type"` // "text" | "image_url"
	Text     string    `json:"text,omitempty"`
	ImageURL *ImageURL `json:"image_url,omitempty"`
}

// ImageURL carries a data: URL (base64 JPEG) for vision requests.
type ImageURL struct {
	URL string `json:"url"`
}

// TextMessage builds a plain text message.
func TextMessage(role, text string) Message {
	return Message{Role: role, Content: text}
}

// VisionMessage builds a multimodal message: text plus one screenshot
// data URL. Non-vision models reject this shape, so callers gate it on
// the config's vision switch.
func VisionMessage(role, text, dataURL string) Message {
	return Message{Role: role, Content: []ContentPart{
		{Type: "text", Text: text},
		{Type: "image_url", ImageURL: &ImageURL{URL: dataURL}},
	}}
}

type Client struct {
	BaseURL string
	APIKey  string
	Model   string
	HC      *http.Client
}

type chatRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Error struct {
		Message string `json:"message"`
	} `json:"error"`
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

var pingIPRe = regexp.MustCompile(`\((\d{1,3}(?:\.\d{1,3}){3})\)`)

// resolveViaSystem asks the Android system resolver (netd) by pinging once.
func resolveViaSystem(host string) string {
	out, err := exec.Command("ping", "-c1", "-W3", host).CombinedOutput()
	if m := pingIPRe.FindStringSubmatch(string(out)); len(m) == 2 {
		return m[1]
	}
	_ = err
	return ""
}

// init builds the http.Client lazily, pinning an IP when Go DNS is broken.
func (c *Client) init() error {
	host, port, err := hostPortOf(c.BaseURL)
	if err != nil {
		return err
	}
	pinned := ""
	if ips, lerr := net.LookupHost(host); lerr != nil || len(ips) == 0 {
		pinned = resolveViaSystem(host)
		if pinned == "" {
			return fmt.Errorf("无法解析 %s（Go DNS 与系统解析均失败）", host)
		}
	}
	dialer := &net.Dialer{Timeout: 20 * time.Second}
	tr := &http.Transport{
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 120 * time.Second,
		// Stale keep-alive connections hang until ResponseHeaderTimeout
		// when the server closes them silently; a per-step agent prefers
		// a fresh connection every time.
		DisableKeepAlives: true,
	}
	if pinned != "" {
		tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			if h, _, serr := net.SplitHostPort(addr); serr == nil && h == host {
				addr = net.JoinHostPort(pinned, port)
			}
			return dialer.DialContext(ctx, network, addr)
		}
	} else {
		tr.DialContext = dialer.DialContext
	}
	c.HC = &http.Client{Timeout: 180 * time.Second, Transport: tr}
	return nil
}

func hostPortOf(base string) (string, string, error) {
	u, err := url.Parse(strings.TrimRight(base, "/"))
	if err != nil {
		return "", "", err
	}
	host := u.Hostname()
	if host == "" {
		return "", "", fmt.Errorf("base url 缺少主机名: %s", base)
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "http" {
			port = "80"
		} else {
			port = "443"
		}
	}
	return host, port, nil
}

// Chat sends one chat-completion request and returns the assistant text.
func (c *Client) Chat(messages []Message) (string, error) {
	if c.HC == nil {
		if err := c.init(); err != nil {
			return "", err
		}
	}
	body := chatRequest{Model: c.Model, Messages: messages, Temperature: 0.1}
	data, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	// BaseURL carries the full API prefix (e.g. .../api/paas/v4 or .../v1).
	endpoint := strings.TrimRight(c.BaseURL, "/") + "/chat/completions"
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(data))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)

	resp, err := c.HC.Do(req)
	if err != nil {
		return "", fmt.Errorf("chat request: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("api status %d: %s", resp.StatusCode, truncate(string(raw), 300))
	}
	var cr chatResponse
	if err := json.Unmarshal(raw, &cr); err != nil {
		return "", fmt.Errorf("decode response: %w", err)
	}
	if len(cr.Choices) == 0 {
		return "", fmt.Errorf("api returned no choices: %s", truncate(string(raw), 300))
	}
	return cr.Choices[0].Message.Content, nil
}
