#!/usr/bin/env python3
"""harmony_agent.py — HarmonyOS NEXT 外挂式 Agent（T8 原型，纯节点模式）。

架构（对应规划书 §4.3）：
  PC ── hdc (USB/WiFi) ──> NEXT 设备
   │  dumpLayout 控件树 → 节点表 → OpenAI 兼容 LLM 决策 → uiInput 注入 → 复验循环

零第三方依赖。动作 schema 复用 agent-lite 思路：
  {"action":"tap","index":N} | {"action":"tap","px":..,"py":..} | {"action":"swipe","px1".."py2"}
  | {"action":"text","text":".."} | {"action":"back"/"home"/"app"} | {"action":"done","summary":".."}
  | {"action":"fail","reason":".."} | {"action":"wait","ms":1500}

用法:
  python3 tools/harmony-agent/harmony_agent.py --task "打开设置搜索蓝牙" --serial <SN>
  （首日先跑 probe.py 出命令可用性表，再跑本脚本；API 配置用环境变量或 --base-url/--api-key）
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hdc

SCHEMA = (
    "你是鸿蒙(HarmonyOS NEXT)设备上的自动化助手，通过 HDC 通道操作设备。\n"
    "输入是当前屏幕的控件节点表（每行: 编号|文本|可点|可滚|中心坐标），按可点+有文本优先排序。\n"
    "只输出一个 JSON 动作（严禁任何其他文字）：\n"
    "- {\"action\":\"tap\",\"index\":N} 点击节点 N（优先，最稳）\n"
    "- {\"action\":\"tap\",\"px\":0.5,\"py\":0.3} 比例坐标点击（节点表缺失时，0~1 全屏比例）\n"
    "- {\"action\":\"swipe\",\"px1\":..,\"py1\":..,\"px2\":..,\"py2\":..} 滑动\n"
    "- {\"action\":\"text\",\"text\":\"..\"} 输入文字（中文可能失败，失败换 wait+重试或 ask）\n"
    "- {\"action\":\"back\"} / {\"action\":\"home\"} 返回/主页\n"
    "- {\"action\":\"app\",\"bundle\":\"..\",\"ability\":\"..\"} 启动应用\n"
    "- {\"action\":\"wait\",\"ms\":1500} 等待页面加载\n"
    "- {\"action\":\"done\",\"summary\":\"..\"} 任务完成\n"
    "- {\"action\":\"fail\",\"reason\":\"..\"} 任务无法完成（如实归因）\n"
    "守则：1) 点击优先用节点编号，禁止沿用旧编号（每步重新分配）；2) 页面加载中先 wait 不要盲点；"
    "3) 禁止重复点击同一位置，无进展立即换策略；4) 支付/密码等敏感操作必须 fail 请用户手动。\n"
)


def llm_chat(messages, base_url, api_key, model, timeout=90):
    """OpenAI 兼容 chat 调用（stdlib urllib）。"""
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps({"model": model, "messages": messages}).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode())
    return resp["choices"][0]["message"]["content"].strip()


def parse_action(reply):
    """从 LLM 输出提取 JSON 动作（容忍 markdown 围栏）。"""
    m = re.search(r"\{[^{}]*\}", reply, re.S)
    if not m:
        raise ValueError("输出无 JSON: " + reply[:200])
    return json.loads(m.group(0))


def to_prompt(nodes, screen_w, screen_h, max_nodes=40):
    if not nodes:
        return "（当前控件树为空，页面可能加载中，请 wait）"
    lines = [f"屏幕 {screen_w}x{screen_h}。节点表（共 {len(nodes)}，显示前 {max_nodes}）："]
    for n in nodes[:max_nodes]:
        flag = ("可点" if n["clickable"] else "") + ("|可滚" if n["scrollable"] else "")
        t = (n["text"] or n["desc"] or "").replace("\n", " ")
        lines.append(f"#{n['index']}|{t[:40]}|{flag}|({n['cx']},{n['cy']})")
    return "\n".join(lines)


def fingerprint(nodes):
    return "|".join(f"{n['cx']},{n['cy']},{n['text']}" for n in nodes[:20])


def run(task, serial, base_url, api_key, model, max_steps=30, verbose=True):
    def log(msg):
        if verbose:
            print(msg, flush=True)

    w, h = hdc.screen_size(serial)
    log(f"屏幕尺寸: {w}x{h}")
    messages = [{"role": "system", "content": SCHEMA}, {"role": "user", "content": task}]

    last_key, repeats, fp_history = "", 0, []
    stasis_asked = False
    for step in range(1, max_steps + 1):
        nodes = hdc.collect_nodes(serial)
        fp = fingerprint(nodes)
        fp_history.append(fp)
        if len(fp_history) >= 6 and len(set(fp_history[-6:])) == 1:
            if not stasis_asked:
                # 有些操作成功但屏幕不变（静默开关/后台生效）→ 先问模型一次，避免误判
                stasis_asked = True
                log(f"第 {step} 步：屏幕连续无变化，询问模型是否已完成")
                messages.append({"role": "user", "content":
                    "屏幕已连续多步无变化（可能操作已静默生效，也可能卡死）。"
                    "若任务目标已达成，输出 {\"action\":\"done\",\"summary\":\"...\"}；"
                    "若未达成，输出 {\"action\":\"fail\",\"reason\":\"具体原因\"}。"
                    "不要再重复相同操作。"})
                continue
            log(f"第 {step} 步：模型未能给出结论，判定卡死")
            return "fail: 屏幕无变化且模型未给出结论（目标可能不存在/界面卡住）"
        log(f"第 {step} 步：感知 {len(nodes)} 个节点")
        prompt = to_prompt(nodes, w, h)
        messages.append({"role": "user", "content": "当前屏幕：\n" + prompt})
        # 保留 system + 任务 + 最近 8 条
        while len(messages) > 10:
            messages = [messages[0], messages[1]] + messages[-8:]
        reply = llm_chat(messages, base_url, api_key, model)
        try:
            a = parse_action(reply)
        except ValueError as e:
            log(f"LLM 输出无法解析: {e}")
            messages.append({"role": "assistant", "content": reply})
            messages.append({"role": "user", "content": "输出不是合法 JSON 动作，重新只输出一个 JSON 动作。"})
            continue
        messages.append({"role": "assistant", "content": reply})

        kind = a.get("action", "")
        act_key = f"{kind}|{a.get('index')}|{int(a.get('px', 0) * 1000)},{int(a.get('py', 0) * 1000)}"
        repeats = repeats + 1 if act_key == last_key else 0
        last_key = act_key

        try:
            if kind == "tap":
                if "index" in a:
                    n = next((x for x in nodes if x["index"] == a["index"]), None)
                    if n is None:
                        raise ValueError(f"节点 {a['index']} 不在表中（每步重新编号）")
                    hdc.click(serial, n["cx"], n["cy"])
                    label = f"tap#{a['index']}"
                else:
                    x, y = int(a.get("px", 0.5) * w), int(a.get("py", 0.5) * h)
                    hdc.click(serial, x, y)
                    label = f"tap({x},{y})"
            elif kind == "swipe":
                x1, y1 = int(a.get("px1", 0.5) * w), int(a.get("py1", 0.5) * h)
                x2, y2 = int(a.get("px2", 0.5) * w), int(a.get("py2", 0.5) * h)
                hdc.swipe(serial, x1, y1, x2, y2)
                label = f"swipe({x1},{y1})→({x2},{y2})"
            elif kind == "text":
                hdc.input_text(serial, a.get("text", ""))
                label = "text"
            elif kind == "back":
                hdc.back(serial)
                label = "back"
            elif kind == "home":
                hdc.home(serial)
                label = "home"
            elif kind == "app":
                hdc.start_app(serial, a.get("bundle", ""), a.get("ability", ""))
                label = f"app {a.get('bundle')}"
            elif kind == "wait":
                time.sleep(min(max(a.get("ms", 1500), 200), 8000) / 1000)
                label = "wait"
            elif kind == "done":
                log(f"✅ 任务完成：{a.get('summary', '')}")
                return f"done: {a.get('summary', '')}"
            elif kind == "fail":
                log(f"❌ 任务失败：{a.get('reason', '')}")
                return f"fail: {a.get('reason', '')}"
            else:
                raise ValueError("unknown action " + kind)
        except hdc.HdcError as e:
            log(f"执行失败: {e}")
            msg = f"动作 {kind} 执行报错：{str(e)[:200]}。"
            if kind == "text":
                msg += ("中文/特殊字符输入在该设备上可能不支持。按顺序尝试替代方案："
                        "① 改用英文关键词或拼音输入（如 bluetooth）；"
                        "② 优先用点击/滑动等不依赖文字输入的方式找到目标；"
                        "③ 都不可行时如实输出 {\"action\":\"fail\",\"reason\":\"中文输入不可用\"}，"
                        "严禁在目标未达成时输出 done。")
            else:
                msg += "换一种方式继续。"
            messages.append({"role": "user", "content": msg})
            continue
        except Exception as e:
            log(f"执行异常: {e}")
            messages.append({"role": "user", "content": f"动作 {kind} 异常：{str(e)[:200]}。换策略继续。"})
            continue

        log(f"第 {step} 步：执行 {label}")
        time.sleep(1.2)  # 等待 UI 稳定
        nxt = "已执行动作，这是执行后的屏幕，请继续下一步（或 done）。"
        if repeats >= 1:
            nxt = "警告：连续重复相同动作且无进展！必须换策略：换坐标、滑动翻页、或 back 重来。"
        messages.append({"role": "user", "content": nxt})
    return "fail: 达到最大步数"


def main():
    ap = argparse.ArgumentParser(description="HarmonyOS NEXT HDC 外挂式 Agent")
    ap.add_argument("--task", required=True)
    ap.add_argument("--serial", default=None, help="hdc 目标序列号（不填取 list targets 第一个）")
    ap.add_argument("--base-url", default=os.environ.get("AGENT_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    ap.add_argument("--api-key", default=os.environ.get("AGENT_API_KEY", ""))
    ap.add_argument("--model", default=os.environ.get("AGENT_MODEL", "qwen3.5-flash"))
    ap.add_argument("--max-steps", type=int, default=30)
    args = ap.parse_args()

    if not args.api_key:
        print("缺少 API Key：--api-key 或环境变量 AGENT_API_KEY", file=sys.stderr)
        sys.exit(2)
    serial = args.serial
    if not serial:
        targets = hdc.list_targets()
        if not targets:
            print("无 hdc 目标设备（检查 USB/开发者模式/hdc 环境）", file=sys.stderr)
            sys.exit(2)
        serial = targets[0]
    print(f"目标设备: {serial}  模型: {args.model}")
    result = run(args.task, serial, args.base_url, args.api_key, args.model, args.max_steps)
    print("RESULT:", result)


if __name__ == "__main__":
    main()
