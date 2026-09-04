// Package agent runs the perceive→decide→act→verify loop on-device.
package agent

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"agentd/internal/config"
	"agentd/internal/device"
	"agentd/internal/llm"
	"agentd/internal/semantics"
	"agentd/internal/vision"
)

// Action is the single JSON step the LLM emits.
type Action struct {
	Action    string `json:"action"`
	Index     *int   `json:"index"`
	X         int    `json:"x"`
	Y         int    `json:"y"`
	X1        int    `json:"x1"`
	Y1        int    `json:"y1"`
	X2        int    `json:"x2"`
	Y2        int    `json:"y2"`
	Dur       int    `json:"dur"`
	Text      string `json:"text"`
	Code      int    `json:"code"`
	Direction string `json:"direction"`
	Package   string `json:"package"`
	Activity  string `json:"activity"`
	Page      string `json:"page"`
	URL       string `json:"url"`
	On        *bool  `json:"on"`
	Mode      string `json:"mode"`
	Dir       string `json:"dir"`
	Level     int    `json:"level"`
	Ms        int    `json:"ms"`
	Summary   string `json:"summary"`
	Reason    string `json:"reason"`
}

// Runner drives one task loop.
type Runner struct {
	Cfg        *config.Config
	DumpPath   string
	ScreenPath string
	Client     *llm.Client

	Log      func(string)
	OnStep   func(step int, action string, nodes []semantics.Node)
	OnTap    func(x, y int, label string) // tap marker for the WebUI
	Stopped  *atomic.Bool

	curNodes []semantics.Node // last perceived nodes, for index taps
}

const actionSchema = `

动作必须是单个 JSON 对象，字段 action 取值：
【直达能力 —— 优先使用，像系统语音助手一样一步到位】
- {"action":"setting","page":"wifi"}             直达设置页(可选: wifi bluetooth display sound apps notifications location security battery storage date language accessibility airplane network vpn nfc cast developer deviceinfo home)
- {"action":"app","package":"包名"}               启动应用(如 com.tencent.mm；打不开时换 tap 桌面图标)
- {"action":"open_url","url":"..."}              打开链接或应用 scheme(如 https://、alipay://、weixin://、tel:10086)
- {"action":"wifi","on":true} / {"action":"bluetooth","on":false}   直接开关 WiFi/蓝牙(无需进设置)
- {"action":"brightness","level":<0-255>}        直接调亮度
- {"action":"volume","dir":"up|down|mute"}       音量
- {"action":"statusbar","mode":"notifications|settings|collapse"}  展开通知栏/快捷设置/收起
- {"action":"wake"}                              点亮屏幕
【界面操作 —— 直达做不到时的兜底】
- {"action":"tap","index":<节点编号>}            点击节点（首选编号；目标不在表中才用 "x","y" 坐标）
- {"action":"longpress","index":<节点编号>}      长按节点(可带 "dur" 毫秒)
- {"action":"swipe","x1":<int>,"y1":<int>,"x2":<int>,"y2":<int>,"dur":<int>} 滑动
- {"action":"scroll","direction":"up"|"down"}    翻页
- {"action":"key","code":<int>}                  按键(4=返回,3=主页)
- {"action":"text","text":"..."}                 输入文字(支持中文，会替换输入框内容；先 tap 聚焦输入框)
- {"action":"wait","ms":<int>}                   等待页面加载(最长 8000ms)
- {"action":"done","summary":"完成说明"}          任务已完成
原则：能直达不翻页；页面在加载先 wait；弹窗/广告优先点关闭/跳过；同一动作执行后屏幕没变化必须换策略，不要重复点同一位置。
只输出 JSON，不要输出任何其他文字、解释或 markdown 代码块。`

var jsonRe = regexp.MustCompile(`\{[^{}]*\}`)

func parseAction(reply string) (Action, error) {
	reply = strings.TrimSpace(reply)
	// strip markdown code fences if the model wraps the JSON
	reply = strings.TrimPrefix(reply, "```json")
	reply = strings.TrimPrefix(reply, "```")
	reply = strings.TrimSuffix(reply, "```")
	reply = strings.TrimSpace(reply)

	var a Action
	if err := json.Unmarshal([]byte(reply), &a); err == nil && a.Action != "" {
		return a, nil
	}
	if m := jsonRe.FindString(reply); m != "" {
		if err := json.Unmarshal([]byte(m), &a); err == nil && a.Action != "" {
			return a, nil
		}
	}
	return Action{}, fmt.Errorf("cannot parse action from reply: %s", truncate(reply, 200))
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func (r *Runner) scroll(direction string) error {
	w, h, err := device.ScreenSize()
	if err != nil {
		return err
	}
	x := w / 2
	if direction == "down" {
		return device.Swipe(x, h/4, x, h*3/4, 400)
	}
	return device.Swipe(x, h*3/4, x, h/4, 400)
}

// resolveNode maps a node-table index to its center coordinates.
func (r *Runner) resolveNode(idx *int) (int, int, error) {
	if idx == nil {
		return 0, 0, fmt.Errorf("缺少 index")
	}
	for _, n := range r.curNodes {
		if n.Index == *idx {
			return n.CenterX, n.CenterY, nil
		}
	}
	return 0, 0, fmt.Errorf("节点编号 %d 不在当前节点表中（共 %d 个）",
		*idx, len(r.curNodes))
}

func (r *Runner) tapAt(x, y int, label string) error {
	if r.OnTap != nil {
		r.OnTap(x, y, label)
	}
	return device.Tap(x, y)
}

func (r *Runner) exec(a Action) (string, error) {
	switch a.Action {
	case "tap":
		if a.Index != nil {
			x, y, err := r.resolveNode(a.Index)
			if err != nil {
				return "tap", err
			}
			return fmt.Sprintf("tap#%d", *a.Index), r.tapAt(x, y, fmt.Sprintf("tap#%d", *a.Index))
		}
		return "tap", r.tapAt(a.X, a.Y, "tap")
	case "longpress":
		dur := a.Dur
		if dur <= 0 {
			dur = 900
		}
		if a.Index != nil {
			x, y, err := r.resolveNode(a.Index)
			if err != nil {
				return "longpress", err
			}
			if r.OnTap != nil {
				r.OnTap(x, y, "longpress")
			}
			return fmt.Sprintf("longpress#%d", *a.Index), device.LongPress(x, y, dur)
		}
		return "longpress", device.LongPress(a.X, a.Y, dur)
	case "swipe":
		if a.Dur == 0 {
			a.Dur = 400
		}
		return "swipe", device.Swipe(a.X1, a.Y1, a.X2, a.Y2, a.Dur)
	case "scroll":
		return "scroll " + a.Direction, r.scroll(a.Direction)
	case "key":
		return fmt.Sprintf("key %d", a.Code), device.Key(a.Code)
	case "text":
		return "text", device.TypeText(a.Text)
	case "app":
		return "app " + a.Package, device.StartApp(a.Package, a.Activity)
	case "setting":
		return "setting " + a.Page, device.OpenSettingsPage(a.Page)
	case "open_url":
		return "open_url", device.OpenURL(a.URL)
	case "statusbar":
		return "statusbar " + a.Mode, device.StatusBar(a.Mode)
	case "volume":
		return "volume " + a.Dir, device.Volume(a.Dir)
	case "brightness":
		return fmt.Sprintf("brightness %d", a.Level), device.Brightness(a.Level)
	case "wifi":
		if a.On == nil {
			return "wifi", fmt.Errorf("缺少 on 字段")
		}
		return fmt.Sprintf("wifi %v", *a.On), device.SetWiFi(*a.On)
	case "bluetooth":
		if a.On == nil {
			return "bluetooth", fmt.Errorf("缺少 on 字段")
		}
		return fmt.Sprintf("bluetooth %v", *a.On), device.SetBluetooth(*a.On)
	case "wake":
		return "wake", device.Wake()
	case "wait":
		ms := a.Ms
		if ms <= 0 {
			ms = 1500
		}
		if ms > 8000 {
			ms = 8000
		}
		time.Sleep(time.Duration(ms) * time.Millisecond)
		return fmt.Sprintf("wait %dms", ms), nil
	case "back":
		return "back", device.Back()
	case "home":
		return "home", device.Home()
	case "done":
		return "done", nil
	default:
		return "", fmt.Errorf("unknown action %q", a.Action)
	}
}

// sense dumps the UI hierarchy with retries. Dumping mid-transition is the
// suspected trigger that permanently bricks the a11y bridge on ColorOS, so
// back off longer on null-root errors and never hammer the channel.
func (r *Runner) sense(quick bool) (string, error) {
	var lastErr error
	resetDone := false
	attempts := 4
	if quick {
		attempts = 1 // blind-fast: don't burn 20-50s per step when both
		// channels already failed once this task
	}
	for attempt := 0; attempt < attempts; attempt++ {
		if r.Stopped != nil && r.Stopped.Load() {
			return "", fmt.Errorf("任务已被用户停止")
		}
		xmlText, err := device.DumpUI(r.DumpPath)
		if err == nil {
			return xmlText, nil
		}
		lastErr = err
		if strings.Contains(err.Error(), "null root") {
			if !quick && !resetDone && attempt >= 1 {
				// self-heal: cycling the a11y master switch revives the
				// bridge without a reboot (ColorOS kills it silently)
				r.Log("a11y 桥疑似被系统禁用，循环无障碍开关自愈…")
				device.ResetA11y()
				resetDone = true
			}
			if !quick {
				time.Sleep(4 * time.Second)
			}
		} else {
			if !strings.Contains(err.Error(), "idle") {
				_ = device.Wake()
			}
			if !quick {
				time.Sleep(1500 * time.Millisecond)
			}
		}
	}
	return "", lastErr
}

// fingerprint summarizes the node tree cheaply for change detection.
func fingerprint(nodes []semantics.Node) string {
	if len(nodes) == 0 {
		return "empty"
	}
	fp := fmt.Sprintf("%d", len(nodes))
	for _, i := range []int{0, len(nodes) / 2, len(nodes) - 1} {
		n := nodes[i]
		fp += "|" + n.Text + n.Desc + n.ID
	}
	return fp
}

// waitForChange waits until the a11y node tree differs from the previous
// perception (screen transition done), bounded to [350ms, 1.6s]. Falls
// back to a fixed 900ms when the service is unreachable.
func (r *Runner) waitForChange(prev []semantics.Node) {
	if len(prev) == 0 {
		time.Sleep(900 * time.Millisecond)
		return
	}
	fp := fingerprint(prev)
	time.Sleep(350 * time.Millisecond) // minimum: let the transition start
	deadline := time.Now().Add(1600 * time.Millisecond)
	for time.Now().Before(deadline) {
		cur, err := r.senseViaService()
		if err != nil {
			time.Sleep(900 * time.Millisecond)
			return
		}
		if fingerprint(cur) != fp {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
}

const a11yServiceURL = "http://127.0.0.1:8081/nodes"

var a11yClient = &http.Client{Timeout: 4 * time.Second}

// senseViaService reads the node tree from the on-device AccessibilityService
// APK — a first-class a11y connection that survives ColorOS killing the
// uiautomator instrumentation bridge.
func (r *Runner) senseViaService() ([]semantics.Node, error) {
	resp, err := a11yClient.Get(a11yServiceURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("a11y service status %d", resp.StatusCode)
	}
	var nodes []semantics.Node
	if err := json.NewDecoder(resp.Body).Decode(&nodes); err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nil, fmt.Errorf("a11y service returned no nodes")
	}
	for i := range nodes {
		nodes[i].HasBounds = true // the service always reports centers
	}
	return nodes, nil
}

// Run executes the task loop and returns a completion summary.
// System animations are left untouched — the user wants to watch the
// agent work, and the a11y service channel no longer needs frozen
// animations to stay stable.
func (r *Runner) Run(task string) (string, error) {
	messages := []llm.Message{
		{Role: "system", Content: r.Cfg.SystemPrompt + actionSchema},
		{Role: "user", Content: "任务：" + task},
	}
	blinded := 0 // consecutive null-root perception failures
	lastKey := ""
	repeats := 0 // consecutive identical actions (stuck detector)
	lastRebind := time.Now().Add(-time.Minute) // allow immediate first rebind

	for step := 1; step <= r.Cfg.MaxSteps; step++ {
		if r.Stopped != nil && r.Stopped.Load() {
			return "", fmt.Errorf("任务已被用户停止")
		}

		var nodes []semantics.Node
		prompt := ""
		viaService, svcErr := r.senseViaService()
		if svcErr != nil && time.Since(lastRebind) > 20*time.Second {
			// ColorOS freezes the service process when idle; force a
			// rebind (root) and try once more before degrading.
			r.Log("a11y 服务不可达（疑似被系统速冻），强制重绑…")
			device.RebindA11yService()
			lastRebind = time.Now()
			viaService, svcErr = r.senseViaService()
		}
		if svcErr == nil {
			blinded = 0
			nodes = viaService
			r.curNodes = nodes
			r.Log(fmt.Sprintf("第 %d 步：感知到 %d 个节点（a11y 服务）", step, len(nodes)))
			prompt = semantics.ToPrompt(nodes, 40)
		} else if xmlText, senseErr := r.sense(blinded > 0); senseErr == nil {
			blinded = 0
			parsed, err := semantics.Parse(xmlText)
			if err != nil {
				return "", err
			}
			nodes = parsed
			r.curNodes = nodes
			r.Log(fmt.Sprintf("第 %d 步：感知到 %d 个节点", step, len(nodes)))
			prompt = semantics.ToPrompt(nodes, 40)
		} else {
			r.curNodes = nil
			// Both channels failed: the ColorOS launcher never idles and the
			// uiautomator bridge gets killed by the OS; degrade to blind
			// actions (app/key/back/home need no coordinates).
			r.Log("感知失败，降级为盲操作模式：" + senseErr.Error())
			prompt = "当前屏幕语义不可用（桌面动画或通道受限导致）。" +
				"请使用不需要坐标的动作：app 启动应用 / key 按键 / back / home。"
			if strings.Contains(senseErr.Error(), "null root") {
				blinded++
				if blinded >= 3 {
					return "", fmt.Errorf(
						"感知通道疑似被系统禁用（连续 %d 次 null root）。请重启手机后重试任务", blinded)
				}
			}
		}

		messages = append(messages, r.perceptionMessage(prompt))
		reply, err := r.Client.Chat(messages)
		if err != nil {
			return "", fmt.Errorf("LLM 调用失败: %v", err)
		}
		action, err := parseAction(reply)
		if err != nil {
			r.Log("LLM 输出无法解析：" + err.Error())
			messages = append(messages, llm.Message{Role: "assistant", Content: reply})
			messages = append(messages, llm.Message{Role: "user", Content: "输出不是合法 JSON 动作，请重新只输出一个 JSON 动作。"})
			continue
		}

		if action.Action == "done" {
			r.Log("任务完成：" + action.Summary)
			return action.Summary, nil
		}

		label, execErr := r.exec(action)
		if execErr != nil {
			// non-fatal: tell the LLM what failed so it can pick another
			// route (wrong package name, missing a11y service, …)
			r.Log(fmt.Sprintf("执行 %s 失败：%v", label, execErr))
			messages = append(messages,
				llm.TextMessage("assistant", reply),
				llm.TextMessage("user", "动作执行失败："+trimErr(execErr)+
					"。请换一种方式继续（例如 tap 屏幕上的图标/元素，而不是猜包名），或 done。"),
			)
			continue
		}
		r.Log(fmt.Sprintf("第 %d 步：执行 %s%s", step, label, reason(action.Reason)))
		if r.OnStep != nil {
			r.OnStep(step, fmt.Sprintf("%s (%d,%d)", action.Action, action.X, action.Y), nodes)
		}
		// Adaptive settle: poll the a11y service until the node tree
		// changes (transition finished) or the timeout hits — typically
		// ~0.5s instead of a fixed 1.2s. wait actions already slept.
		if action.Action != "wait" {
			r.waitForChange(nodes)
		}
		_ = device.Screenshot(r.ScreenPath)

		// stuck detector: identical action repeated without progress
		idxStr := "nil"
		if action.Index != nil {
			idxStr = fmt.Sprintf("%d", *action.Index)
		}
		actKey := fmt.Sprintf("%s|%s|%d,%d", action.Action, idxStr, action.X, action.Y)
		if actKey == lastKey {
			repeats++
		} else {
			repeats = 0
		}
		lastKey = actKey
		nextMsg := "已执行动作，这是执行后的屏幕，请继续下一步（或 done）。"
		if repeats >= 2 {
			nextMsg = fmt.Sprintf("警告：你已连续 %d 次执行完全相同但没有进展的动作。"+
				"必须换策略——用 index 点击节点表中真正的目标节点，或 scroll 翻页，"+
				"不要再点同一位置。", repeats+1)
		}

		// trim conversation to keep the context bounded
		messages = append(messages,
			llm.TextMessage("assistant", reply),
			llm.TextMessage("user", nextMsg),
		)
		if len(messages) > 10 {
			// keep system + first task message + last 8
			messages = append(messages[:2], messages[len(messages)-8:]...)
		}
	}
	return "", fmt.Errorf("达到最大步数 %d，任务未确认完成", r.Cfg.MaxSteps)
}

// perceptionMessage wraps the node-table prompt, attaching the current
// screenshot when vision is enabled. The screenshot is refreshed here so
// the image the model sees matches the nodes it was built from.
func (r *Runner) perceptionMessage(prompt string) llm.Message {
	if !r.Cfg.Vision {
		return llm.TextMessage("user", prompt)
	}
	if err := device.Screenshot(r.ScreenPath); err == nil {
		if dataURL, derr := vision.DataURL(r.ScreenPath, 640, 60); derr == nil {
			return llm.VisionMessage("user", prompt+"\n\n同时附上了当前屏幕截图。", dataURL)
		}
	}
	// screenshot/compress failed: degrade gracefully to text-only
	return llm.TextMessage("user", prompt)
}

func trimErr(err error) string {
	s := err.Error()
	if len(s) > 300 {
		return s[:300] + "..."
	}
	return s
}

func reason(s string) string {
	if s == "" {
		return ""
	}
	return "（" + s + "）"
}
