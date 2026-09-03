// Package agent runs the perceive→decide→act→verify loop on-device.
package agent

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"agentd/internal/config"
	"agentd/internal/device"
	"agentd/internal/llm"
	"agentd/internal/semantics"
)

// Action is the single JSON step the LLM emits.
type Action struct {
	Action    string `json:"action"`
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
	Stopped  *atomic.Bool
}

const actionSchema = `

动作必须是单个 JSON 对象，字段 action 取值：
- {"action":"tap","x":<int>,"y":<int>}           点击坐标
- {"action":"swipe","x1":<int>,"y1":<int>,"x2":<int>,"y2":<int>,"dur":<int>} 滑动
- {"action":"scroll","direction":"up"|"down"}    翻页
- {"action":"key","code":<int>}                  按键(4=返回,3=主页)
- {"action":"text","text":"..."}                 输入文字(空格用 %s)
- {"action":"app","package":"包名"}               启动应用(如 com.android.settings)
- {"action":"done","summary":"完成说明"}          任务已完成
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

func (r *Runner) exec(a Action) (string, error) {
	switch a.Action {
	case "tap":
		return "tap", device.Tap(a.X, a.Y)
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
		return "text", device.Text(a.Text)
	case "app":
		return "app " + a.Package, device.StartApp(a.Package, a.Activity)
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

// sense dumps the UI hierarchy with retries; the a11y channel is flaky on
// animated screens and can silently die on OEM ROMs.
func (r *Runner) sense() (string, error) {
	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		if r.Stopped != nil && r.Stopped.Load() {
			return "", fmt.Errorf("任务已被用户停止")
		}
		xmlText, err := device.DumpUI(r.DumpPath)
		if err == nil {
			return xmlText, nil
		}
		lastErr = err
		if !strings.Contains(err.Error(), "idle") {
			_ = device.Wake()
		}
		time.Sleep(1500 * time.Millisecond)
	}
	return "", lastErr
}

// Run executes the task loop and returns a completion summary.
func (r *Runner) Run(task string) (string, error) {
	restore := device.QuietAnimations()
	defer restore()

	messages := []llm.Message{
		{Role: "system", Content: r.Cfg.SystemPrompt + actionSchema},
		{Role: "user", Content: "任务：" + task},
	}

	for step := 1; step <= r.Cfg.MaxSteps; step++ {
		if r.Stopped != nil && r.Stopped.Load() {
			return "", fmt.Errorf("任务已被用户停止")
		}

		xmlText, senseErr := r.sense()
		var nodes []semantics.Node
		prompt := ""
		if senseErr != nil {
			// The ColorOS launcher never reaches an idle state, so the home
			// screen cannot be dumped; degrade to blind actions (app/key/
			// back/home need no coordinates) and let perception recover
			// once we land inside a real app.
			r.Log("感知失败，降级为盲操作模式：" + senseErr.Error())
			prompt = "当前屏幕语义不可用（桌面动画导致）。" +
				"请使用不需要坐标的动作：app 启动应用 / key 按键 / back / home。"
		} else {
			parsed, err := semantics.Parse(xmlText)
			if err != nil {
				return "", err
			}
			nodes = parsed
			r.Log(fmt.Sprintf("第 %d 步：感知到 %d 个节点", step, len(nodes)))
			prompt = semantics.ToPrompt(nodes, 40)
		}

		messages = append(messages, llm.Message{Role: "user", Content: prompt})
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
			r.Log(fmt.Sprintf("执行 %s 失败：%v", label, execErr))
			return "", execErr
		}
		r.Log(fmt.Sprintf("第 %d 步：执行 %s%s", step, label, reason(action.Reason)))
		if r.OnStep != nil {
			r.OnStep(step, fmt.Sprintf("%s (%d,%d)", action.Action, action.X, action.Y), nodes)
		}
		_ = device.Screenshot(r.ScreenPath)

		// trim conversation to keep the context bounded
		messages = append(messages,
			llm.Message{Role: "assistant", Content: reply},
			llm.Message{Role: "user", Content: "已执行动作，这是执行后的屏幕，请继续下一步（或 done）。"},
		)
		if len(messages) > 10 {
			// keep system + first task message + last 8
			messages = append(messages[:2], messages[len(messages)-8:]...)
		}
	}
	return "", fmt.Errorf("达到最大步数 %d，任务未确认完成", r.Cfg.MaxSteps)
}

func reason(s string) string {
	if s == "" {
		return ""
	}
	return "（" + s + "）"
}
