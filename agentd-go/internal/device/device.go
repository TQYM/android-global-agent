// Package device runs Android shell commands to perceive and control the UI.
//
// agentd is started as root (via `su -c`), so it executes the system
// commands directly without re-invoking su. This is what unlocks the
// richer root-only uiautomator hierarchy and keeps injection reliable.
package device

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

func run(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return string(out), err
}

// DumpUI writes the semantic hierarchy XML and returns its text.
// uiautomator can exit 0 while printing "null root node"; the caller must
// verify the file content, which this function does.
func DumpUI(dumpPath string) (string, error) {
	_ = os.Remove(dumpPath)
	out, err := run("uiautomator", "dump", dumpPath)
	data, readErr := os.ReadFile(dumpPath)
	if readErr != nil || !strings.Contains(string(data), "<hierarchy") {
		if err != nil {
			return "", fmt.Errorf("uiautomator dump failed: %s", strings.TrimSpace(out))
		}
		return "", fmt.Errorf("uiautomator dump produced no hierarchy: %s",
			strings.TrimSpace(out))
	}
	return string(data), nil
}

// Screenshot captures the current screen to a PNG file.
func Screenshot(pngPath string) error {
	out, err := run("screencap", "-p", pngPath)
	if err != nil {
		return fmt.Errorf("screencap: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func Tap(x, y int) error {
	_, err := run("input", "tap", strconv.Itoa(x), strconv.Itoa(y))
	return err
}

func Swipe(x1, y1, x2, y2, durMs int) error {
	_, err := run("input", "swipe",
		strconv.Itoa(x1), strconv.Itoa(y1),
		strconv.Itoa(x2), strconv.Itoa(y2), strconv.Itoa(durMs))
	return err
}

func Key(code int) error {
	_, err := run("input", "keyevent", strconv.Itoa(code))
	return err
}

// Text types printable ASCII, escaping spaces as %s (the `input` convention).
// input text cannot deliver CJK/Unicode — see TypeText for the full path.
func Text(s string) error {
	s = strings.ReplaceAll(s, " ", "%s")
	_, err := run("input", "text", s)
	return err
}

const a11yBase = "http://127.0.0.1:8081"

var a11yHTTP = &http.Client{Timeout: 5 * time.Second}

// SetTextViaA11y delivers text to the focused editable node through the
// bundled AccessibilityService (ACTION_SET_TEXT) — the only channel that
// handles CJK/Unicode and punctuation that `input text` mangles. The text
// replaces the field content unless append is true.
func SetTextViaA11y(text string, appendMode bool) error {
	body, _ := json.Marshal(map[string]interface{}{
		"text": text, "append": appendMode,
	})
	resp, err := a11yHTTP.Post(a11yBase+"/settext", "application/json",
		bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("a11y 服务不可达（agentd-apk 未运行？）: %w", err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<10))
	var r struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal(data, &r); err != nil {
		return fmt.Errorf("a11y settext 响应非法: %s", trim120(string(data)))
	}
	if !r.OK {
		return fmt.Errorf("a11y settext 被拒绝: %s", r.Error)
	}
	return nil
}

func trim120(s string) string {
	s = strings.TrimSpace(s)
	if len(s) > 120 {
		return s[:120] + "..."
	}
	return s
}

// isASCII reports whether s contains only printable ASCII + whitespace
// (i.e. characters `input text` can deliver).
func isASCII(s string) bool {
	for _, r := range s {
		if r < 32 || r > 126 {
			return false
		}
	}
	return true
}

// TypeText routes text into the focused field: the a11y service first
// (full Unicode, replaces content), `input text` as the ASCII fallback.
func TypeText(s string) error {
	if err := SetTextViaA11y(s, false); err != nil {
		if isASCII(s) {
			return Text(s)
		}
		return fmt.Errorf("中文/特殊字符输入需要 agentd-apk 无障碍服务: %w", err)
	}
	return nil
}

func Back() error  { return Key(4) }
func Home() error  { return Key(3) }
func Wake() error  { return Key(224) }

func getSetting(namespace, key string) string {
	out, _ := run("settings", "get", namespace, key)
	return strings.TrimSpace(out)
}

func setSetting(namespace, key, value string) error {
	_, err := run("settings", "put", namespace, key, value)
	return err
}

// ResetA11y cycles the accessibility master switch (secure namespace), which
// revives the uiautomator instrumentation bridge after ColorOS kills it —
// verified on OnePlus 13T / ColorOS 16: no reboot needed.
func ResetA11y() {
	_ = setSetting("secure", "accessibility_enabled", "0")
	time.Sleep(2 * time.Second)
	_ = setSetting("secure", "accessibility_enabled", "1")
	time.Sleep(1 * time.Second)
}

// EnsureA11yService appends the bundled AccessibilityService APK to the
// enabled-services list (colon separated), preserving services the user
// already enabled, and flips the master accessibility switch.
func EnsureA11yService() error {
	const svc = "com.dsh.agentd/.AgentA11yService"
	cur := getSetting("secure", "enabled_accessibility_services")
	if strings.Contains(cur, svc) {
		return nil
	}
	next := svc
	if cur != "" && cur != "null" {
		next = cur + ":" + svc
	}
	if err := setSetting("secure", "enabled_accessibility_services", next); err != nil {
		return err
	}
	return setSetting("secure", "accessibility_enabled", "1")
}

var animationKeys = []string{
	"window_animation_scale",
	"transition_animation_scale",
	"animator_duration_scale",
}

// QuietAnimations zeroes the global animation scales (the standard trick to
// let uiautomator reach an idle state on animated launchers) and returns a
// restore closure with the previous values.
func QuietAnimations() func() {
	prev := map[string]string{}
	for _, k := range animationKeys {
		prev[k] = getSetting("global", k)
		_ = setSetting("global", k, "0")
	}
	return func() {
		for _, k := range animationKeys {
			if v, ok := prev[k]; ok && v != "" {
				_ = setSetting("global", k, v)
			}
		}
	}
}

// appAliases maps AOSP/Play package names LLMs tend to guess onto their
// OEM counterparts actually shipped on this device family (ColorOS 16 /
// OnePlus 13T verified via pm). Tried in order after the guessed package
// fails to resolve.
var appAliases = map[string][]string{
	"com.android.gallery3d":          {"com.coloros.gallery3d", "com.oneplus.gallery"},
	"com.google.android.apps.photos": {"com.coloros.gallery3d", "com.oneplus.gallery"},
	"com.google.android.keep":        {"com.coloros.note", "com.oneplus.note"},
	"com.android.notes":              {"com.coloros.note", "com.oneplus.note"},
	"com.coloros.notepad":            {"com.coloros.note"},
	"com.android.camera2":            {"com.oplus.camera", "com.oneplus.camera"},
	"com.android.calculator2":        {"com.coloros.calculator"},
	"com.android.music":              {"com.heytap.music"},
}

// StartApp launches an app by package name. It resolves the launcher
// activity first (am start needs no monkey), and falls back to running the
// monkey script through sh — direct exec fails with ENOEXEC since monkey is
// a script wrapper, not a binary. When the guessed package is absent, OEM
// aliases from appAliases are tried before giving up.
func StartApp(pkg, activity string) error {
	if err := startAppOnce(pkg, activity); err == nil {
		return nil
	}
	for _, alt := range appAliases[pkg] {
		if err := startAppOnce(alt, ""); err == nil {
			return nil
		}
	}
	// report the original failure so the LLM sees the alias attempt too
	return startAppOnce(pkg, activity)
}

func startAppOnce(pkg, activity string) error {
	if activity != "" {
		_, err := run("am", "start", "-n", pkg+"/"+activity)
		return err
	}
	out, _ := run("cmd", "package", "resolve-activity", "--brief",
		"-c", "android.intent.category.LAUNCHER", pkg)
	lines := strings.Split(strings.TrimSpace(out), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		line := strings.TrimSpace(lines[i])
		if strings.Contains(line, "/") && strings.Contains(line, pkg) {
			if _, err := run("am", "start", "-n", line); err == nil {
				return nil
			}
			break
		}
	}
	mout, err := run("sh", "/system/bin/monkey", "-p", pkg,
		"-c", "android.intent.category.LAUNCHER", "1")
	if err != nil {
		return fmt.Errorf("monkey: %v: %s", err, strings.TrimSpace(mout))
	}
	return nil
}

var sizeRe = regexp.MustCompile(`(\d+)x(\d+)`)

// ScreenSize returns the physical display dimensions.
func ScreenSize() (int, int, error) {
	out, err := run("wm", "size")
	if err != nil {
		return 0, 0, err
	}
	m := sizeRe.FindStringSubmatch(out)
	if len(m) != 3 {
		return 0, 0, fmt.Errorf("unexpected wm size output: %s", out)
	}
	w, _ := strconv.Atoi(m[1])
	h, _ := strconv.Atoi(m[2])
	return w, h, nil
}

// ---- 全功能控制层（小布/小爱式直达能力）----

// LongPress holds a point for durMs (a zero-duration swipe is how
// `input` expresses a long-press gesture).
func LongPress(x, y, durMs int) error {
	if durMs <= 0 {
		durMs = 900
	}
	_, err := run("input", "swipe",
		strconv.Itoa(x), strconv.Itoa(y),
		strconv.Itoa(x), strconv.Itoa(y), strconv.Itoa(durMs))
	return err
}

// OpenURL opens a URL or app scheme via the VIEW intent — deep links like
// alipay://, weixin://, tel:, https:// jump straight into the target app.
func OpenURL(url string) error {
	out, err := run("am", "start", "-a", "android.intent.action.VIEW",
		"-d", url)
	if err != nil {
		return fmt.Errorf("am start VIEW: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

// settingsPages maps short page keys to android.settings intents so the
// agent jumps straight to a Settings page instead of tapping through
// menus — the way 小布/小爱 serve “打开WLAN设置”.
var settingsPages = map[string]string{
	"wifi":          "android.settings.WIFI_SETTINGS",
	"wlan":          "android.settings.WIFI_SETTINGS",
	"bluetooth":     "android.settings.BLUETOOTH_SETTINGS",
	"display":       "android.settings.DISPLAY_SETTINGS",
	"brightness":    "android.settings.DISPLAY_SETTINGS",
	"sound":         "android.settings.SOUND_SETTINGS",
	"apps":          "android.settings.MANAGE_APPLICATIONS_SETTINGS",
	"notifications": "android.settings.NOTIFICATION_SETTINGS",
	"location":      "android.settings.LOCATION_SOURCE_SETTINGS",
	"security":      "android.settings.SECURITY_SETTINGS",
	"battery":       "android.settings.BATTERY_SAVER_SETTINGS",
	"storage":       "android.settings.INTERNAL_STORAGE_SETTINGS",
	"date":          "android.settings.DATE_SETTINGS",
	"language":      "android.settings.LOCALE_SETTINGS",
	"accessibility": "android.settings.ACCESSIBILITY_SETTINGS",
	"airplane":      "android.settings.AIRPLANE_MODE_SETTINGS",
	"network":       "android.settings.WIRELESS_SETTINGS",
	"vpn":           "android.settings.VPN_SETTINGS",
	"nfc":           "android.settings.NFC_SETTINGS",
	"cast":          "android.settings.CAST_SETTINGS",
	"developer":     "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
	"deviceinfo":    "android.settings.DEVICE_INFO_SETTINGS",
	"home":          "android.settings.HOME_SETTINGS",
}

// OpenSettingsPage jumps straight to a Settings page by key.
func OpenSettingsPage(page string) error {
	action, ok := settingsPages[strings.ToLower(strings.TrimSpace(page))]
	if !ok {
		return fmt.Errorf("未知设置页 %q（可用: wifi bluetooth display sound apps notifications location security battery storage date language accessibility airplane network vpn nfc cast developer deviceinfo home）", page)
	}
	out, err := run("am", "start", "-a", action)
	if err != nil {
		return fmt.Errorf("打开设置页 %s 失败: %v: %s", page, err, strings.TrimSpace(out))
	}
	return nil
}

// StatusBar expands/collapses the notification shade or quick settings.
// mode: notifications | settings | collapse
func StatusBar(mode string) error {
	var sub string
	switch strings.ToLower(mode) {
	case "notifications", "notification", "通知":
		sub = "expand-notifications"
	case "settings", "quick", "快捷":
		sub = "expand-settings"
	case "collapse", "收起":
		sub = "collapse"
	default:
		return fmt.Errorf("未知状态栏模式 %q", mode)
	}
	_, err := run("cmd", "statusbar", sub)
	return err
}

// Volume adjusts media volume: up | down | mute.
func Volume(dir string) error {
	code := map[string]string{"up": "24", "down": "25", "mute": "164"}[strings.ToLower(dir)]
	if code == "" {
		return fmt.Errorf("未知音量方向 %q（up/down/mute）", dir)
	}
	_, err := run("input", "keyevent", code)
	return err
}

// Brightness sets manual screen brightness (0-255).
func Brightness(level int) error {
	if level < 0 {
		level = 0
	}
	if level > 255 {
		level = 255
	}
	if _, err := run("settings", "put", "system", "screen_brightness_mode", "0"); err != nil {
		return err
	}
	_, err := run("settings", "put", "system", "screen_brightness",
		strconv.Itoa(level))
	return err
}

// SetWiFi toggles the Wi-Fi radio directly (root svc call — no UI).
func SetWiFi(on bool) error {
	op := "disable"
	if on {
		op = "enable"
	}
	_, err := run("svc", "wifi", op)
	return err
}

// SetBluetooth toggles the Bluetooth radio directly.
func SetBluetooth(on bool) error {
	op := "disable"
	if on {
		op = "enable"
	}
	_, err := run("svc", "bluetooth", op)
	return err
}


// RebindA11yService force-rebinds the bundled service. ColorOS's own
// freezer (not AOSP doze) suspends the a11y process after ~15min idle —
// the deviceidle whitelist does NOT prevent it — and a frozen process can
// never serve 8081 again. Removing and re-adding the service entry makes
// AMS start a fresh, unfrozen process; agentd runs as root and can do
// this any time.
func RebindA11yService() {
	_, _ = run("settings", "put", "secure", "enabled_accessibility_services", "")
	time.Sleep(1200 * time.Millisecond)
	_, _ = run("settings", "put", "secure", "enabled_accessibility_services",
		"com.dsh.agentd/.AgentA11yService")
	_, _ = run("settings", "put", "secure", "accessibility_enabled", "1")
	time.Sleep(2500 * time.Millisecond) // let the new process bind + listen
}
