// Package device runs Android shell commands to perceive and control the UI.
//
// agentd is started as root (via `su -c`), so it executes the system
// commands directly without re-invoking su. This is what unlocks the
// richer root-only uiautomator hierarchy and keeps injection reliable.
package device

import (
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
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
func Text(s string) error {
	s = strings.ReplaceAll(s, " ", "%s")
	_, err := run("input", "text", s)
	return err
}

func Back() error  { return Key(4) }
func Home() error  { return Key(3) }
func Wake() error  { return Key(224) }

func GetSetting(key string) string {
	out, _ := run("settings", "get", "global", key)
	return strings.TrimSpace(out)
}

func SetSetting(key, value string) error {
	_, err := run("settings", "put", "global", key, value)
	return err
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
		prev[k] = GetSetting(k)
		_ = SetSetting(k, "0")
	}
	return func() {
		for _, k := range animationKeys {
			if v, ok := prev[k]; ok && v != "" {
				_ = SetSetting(k, v)
			}
		}
	}
}

// StartApp launches an app by package name. It resolves the launcher
// activity first (am start needs no monkey), and falls back to running the
// monkey script through sh — direct exec fails with ENOEXEC since monkey is
// a script wrapper, not a binary.
func StartApp(pkg, activity string) error {
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
