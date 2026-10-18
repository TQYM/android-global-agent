#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""mock_llm_server.py — 本地假模型服务（OpenAI 兼容），离线验证「模糊语义转译」接线。

    MOCK_PORT=18999 python3 tests/mock_llm_server.py &
    echo good > /tmp/mock_llm_scene.txt        # 切换场景

为什么需要它：转译层真正的风险不在纯逻辑（那由 tests/FuzzyTranslatorTest.java 83 条断言
覆盖），而在**接线**——提示词有没有发出去、`max_tokens` 够不够、返回的流程有没有被
解析并注入引擎、失败时能不能退回原路径。这些只有把 App 真的跑起来才看得见，
而真机 + 真模型既不可重复、又要花钱、还可能因为模型当天心情不好而误判。

本服务按请求的 system 内容分流：
  · system 含「结构化转译器」→ 转译请求，按场景返回一份 FlowSpec（或故意坏掉的东西）；
  · 否则                      → 引擎主循环请求，第一步回一个动作，之后回 done。

场景（写进 /tmp/mock_llm_scene.txt）：
  good     合法 FlowSpec（验证主路径）
  unsafe   risk=money 但没有确认点（验证**必须被拒**并回退——这是安全闸）
  prose    返回散文而非 JSON（验证退化路径仍然可用）
  broken   JSON 被截断（验证半成品被丢弃、绝不喂给引擎）
  flow     主循环只给**可复现白名单内**的动作（验证操作记忆库：首次入库、再次零往返复现）
  integrated 三者一体：结构化模糊语义 + 一次性弹窗关闭步 + 业务 app 步；用于验证弹窗步不污染记忆且复现零往返

每次请求都追加到 MOCK_LOG_FILE（默认 /tmp/mock_llm_calls.jsonl），
含 max_tokens 与用户消息摘要，供事后核对。
"""

from __future__ import annotations

import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "18999"))
SCENE_FILE = os.environ.get("MOCK_SCENE_FILE", "/tmp/mock_llm_scene.txt")
LOG_FILE = os.environ.get("MOCK_LOG_FILE", "/tmp/mock_llm_calls.jsonl")

_lock = threading.Lock()
_seq = 0


def scene() -> str:
    global _m3_pos
    try:
        with open(SCENE_FILE, encoding="utf-8") as f:
            s = f.read().strip()
            s = s or "good"
    except Exception:
        s = "good"
    if s != _last_scene[0]:
        _m3_pos = 0            # 场景切换：m3invalid 序列从头开始
        _last_scene[0] = s
    return s


# m3invalid 的服务端推进状态：引擎正常路径**不**向对话累积 assistant 历史
# （纯视觉模式每轮重建消息），按 msgs 里的 assistant 计数推进会失真——
# 实测停在 seq[3]（key 4 = BACK）把 app 自己退到桌面后无限打转。
_M3_INVALID_SEQ = [
    '{"action":"tap","px":1.5,"py":0.5}',       # 越界：px 必须 0.0~1.0
    '{"action":"tap","index":0,"foo":"bar"}',   # 未知参数 foo（additionalProperties=false）
    '{"action":"scroll"}',                        # 缺必填 direction
    '{"action":"key","code":4}',                 # 合法：应放行执行（BACK 键）
    '{"action":"done","summary":"M3 校验序列完成"}',
]
_m3_pos = 0
_last_scene = [None]


SPEC_GOOD = {
    "v": 1,
    "intent": "setting_toggle",
    "goal": "打开显示设置",
    "target_app": {"name": "设置", "pkg": "com.android.settings"},
    "slots": {},
    "steps": [{"n": 1, "do": "打开显示设置页", "channel": "setting",
               "expect": "显示设置", "guard": "none"}],
    "done_when": "显示设置页出现",
    "risk": "none",
    "must_ask_before": [],
    "carry_over": [],
    "confidence": 0.9,
}

# risk=money 但没有任何确认点：validate() 必须拒收（设计方案 FF-1）
SPEC_UNSAFE = {
    "v": 1,
    "intent": "pay_bill",
    "goal": "给用户交话费",
    "target_app": {"name": "支付宝", "pkg": "com.eg.android.AlipayGphone"},
    "slots": {},
    "steps": [{"n": 1, "do": "点击立即支付", "channel": "ui",
               "expect": "支付成功", "guard": "none"}],
    "done_when": "支付成功页出现",
    "risk": "money",
    "must_ask_before": [],
    "carry_over": [],
    "confidence": 0.9,
}


def translator_reply(sc: str, user_text: str = "") -> str:
    if sc == "ab":
        return ab_translator_reply(user_text)
    if sc == "unsafe":
        return json.dumps(SPEC_UNSAFE, ensure_ascii=False)
    if sc in ("flow", "integrated"):
        return json.dumps(SPEC_FLOW, ensure_ascii=False)
    if sc == "prose":
        return "意图/直达建议：直接打开显示设置（setting display）\n推荐应用：设置\n步骤提炼：打开设置页即可"
    if sc == "broken":
        # 模拟 max_tokens 不够时的截断：括号不闭合
        return '{"v":1,"intent":"setting_toggle","goal":"打开显示设置","slots":{"很长的槽位名":"值"'
    return json.dumps(SPEC_GOOD, ensure_ascii=False)


def ab_translator_reply(user_text: str) -> str:
    """A/B 模式：转译层按**任务内容**产出流程（含风险标注），模拟真实转译行为。

    （第一版这里偷懒返回了固定流程，结果对「付话费」也给出 risk=none，
     A 组因此显得同样不会确认——那是假模型的缺陷，不是转译层的问题。）
    """
    m = re.search(r"用户任务：([^\n]+)", user_text)
    task = (m.group(1) if m else "").strip().lower()

    def spec(intent, goal, steps, risk, asks, done_when):
        return json.dumps({
            "v": 1, "intent": intent, "goal": goal,
            "target_app": {"name": "", "pkg": ""}, "slots": {},
            "steps": steps, "done_when": done_when, "risk": risk,
            "must_ask_before": asks, "carry_over": [], "confidence": 0.85,
        }, ensure_ascii=False)

    if any(w in task for w in ("pay", "bill", "transfer")):
        return spec("pay_bill", "缴纳话费", [
            {"n": 1, "do": "进入缴费页面", "channel": "app", "expect": "缴费页", "guard": "none"},
            {"n": 2, "do": "提交支付", "channel": "ui", "expect": "支付成功", "guard": "ask"},
        ], "money", ["提交支付"], "支付成功页出现")
    if any(w in task for w in ("send", "message")):
        return spec("sms", "给妈妈发消息", [
            {"n": 1, "do": "打开短信应用", "channel": "app", "expect": "会话列表", "guard": "none"},
            {"n": 2, "do": "发送消息", "channel": "ui", "expect": "已发送", "guard": "ask"},
        ], "send", ["发送消息"], "消息进入已发送列表")
    if "bluetooth" in task:
        return spec("setting_toggle", "关闭蓝牙", [
            {"n": 1, "do": "打开蓝牙设置页", "channel": "setting", "expect": "蓝牙设置",
             "guard": "none"},
        ], "none", [], "蓝牙开关处于关闭状态")
    if "search" in task or "meituan" in task:
        return spec("search", "在美团搜索奶茶", [
            {"n": 1, "do": "启动美团", "channel": "app", "expect": "美团首页", "guard": "none"},
            {"n": 2, "do": "搜索「奶茶」", "channel": "open_api", "expect": "搜索结果",
             "guard": "none"},
        ], "none", [], "搜索结果页出现奶茶列表")
    return json.dumps(SPEC_GOOD, ensure_ascii=False)


def engine_reply(msgs: list, sc: str = "good") -> str:
    """主循环：第一步给一个动作，之后收尾。"""
    if sc == "ab":
        return ab_engine_reply(msgs)
    if sc == "flow":
        return flow_engine_reply(msgs)
    if sc == "integrated":
        return integrated_engine_reply(msgs)
    if sc == "openurl":
        return openurl_engine_reply(msgs)
    if sc == "ask":
        return ask_engine_reply(msgs)
    if sc == "m3invalid":
        return m3invalid_engine_reply(msgs)
    has_assistant = any(m.get("role") == "assistant" for m in msgs)
    if has_assistant:
        return '{"action":"done","summary":"已打开显示设置"}'
    return '{"action":"setting","page":"display"}'


# ---------------------------------------------------------------- P1 行为验证场景（2026-10-02 §17.11）

def openurl_engine_reply(msgs: list) -> str:
    """P1-1：open_url 可执行，但整条流程**不得入库**（R-5）。"""
    actions = sum(1 for m in msgs if m.get("role") == "assistant")
    if actions <= 0:
        return '{"action":"open_url","url":"https://example.com/"}'
    return '{"action":"done","summary":"已打开链接"}'


def ask_engine_reply(msgs: list) -> str:
    """P1-2：先 ask；只有收到「用户回答：」才 done（USER_TIMEOUT 后必须再问，不得执行）。

    收到 USER_TIMEOUT 时回一个**专用回复**：reply 落进 CALL_LOG，于是「超时以系统事件
    回灌、而非伪装成用户话语」有了可断言的证据（测试端读 reply 即可，不必依赖会被
    截断的 user 字段）。
    """
    joined = " ".join(text_of(m.get("content")) for m in msgs if m.get("role") == "user")
    if "用户回答：" in joined:
        return '{"action":"done","summary":"已按用户回答完成"}'
    if "USER_TIMEOUT" in joined:
        return '{"action":"done","summary":"已收到超时事件"}'
    return '{"action":"ask","question":"请确认：要继续执行这项操作吗？"}'


def m3invalid_engine_reply(msgs: list) -> str:
    """P1-3：三个非法参数动作（越界/未知字段/缺参）→ 一个合法动作 → done。

    按服务端调用次序推进（见 _M3_INVALID_SEQ 处注释）。关掉 tool_router 时
    同一序列全部直接执行（旧路径基线）。
    """
    global _m3_pos
    out = _M3_INVALID_SEQ[min(_m3_pos, len(_M3_INVALID_SEQ) - 1)]
    _m3_pos += 1
    return out


# ---------------------------------------------------------------- 操作记忆库场景（flow）
# 为什么单独一个场景：默认场景第一步给的是 `setting`（打开系统设置页），而操作记忆库的
# 可复现白名单**刻意不含** `setting`（系统开关类动作不入库），所以拿默认场景测记忆库
# 永远看不到「已写入操作记忆库」。这里只回 `app`（白名单内）→ 首次任务能入库，
# 再次同款任务就能零模型往返直接复现。

SPEC_FLOW = {
    "v": 1,
    "intent": "open_app",
    "goal": "打开设置",
    "target_app": {"name": "设置", "pkg": "com.android.settings"},
    "slots": {},
    "steps": [{"n": 1, "do": "启动设置", "channel": "app",
               "expect": "设置首页", "guard": "none"}],
    "done_when": "设置页出现",
    "risk": "none",
    "must_ask_before": [],
    "carry_over": [],
    "confidence": 0.9,
}


def flow_engine_reply(msgs: list) -> str:
    has_assistant = any(m.get("role") == "assistant" for m in msgs)
    if has_assistant:
        return '{"action":"done","summary":"已打开设置"}'
    return '{"action":"app","package":"com.android.settings"}'


def integrated_engine_reply(msgs: list) -> str:
    """三功能一体序列：先点测试页「跳过广告」，再打开设置，最后 done。

    第一步按节点表**文本**选编号（与真实模型一致——盲用 index=0 点到的往往是标题），
    锚点命中 PopupGuard 强词表，采集侧必须把它当环境噪音跳过；第二步 app 才是可复现业务路径。
    第二次同任务应完全绕过本函数（调用计数不增加），由记忆库直接执行 app 步。
    """
    actions = sum(1 for m in msgs if m.get("role") == "assistant")
    if actions <= 0:
        # 视觉请求的 content 是**多模态列表**（text + image_url）：直接 re.search 会
        # TypeError → mock 500 → 引擎切网关、真实模型接管（实测「setting wifi」悬案的根因）。
        last_user = next((text_of(m.get("content")) for m in reversed(msgs)
                          if m.get("role") == "user"), "")
        m = re.search(r"\[(\d+)\]\s*跳过广告", last_user)
        idx = int(m.group(1)) if m else 0
        return json.dumps({"action": "tap", "index": idx}, ensure_ascii=False)
    if actions == 1:
        return '{"action":"app","package":"com.android.settings"}'
    return '{"action":"done","summary":"已跳过广告并打开设置"}'


# ---------------------------------------------------------------- A/B 对照模式
# 这一模式下，假模型**只依据提示词里有什么**来决定动作——它模拟的是真实模型
# 最基本的性质：看不见的信息，就不会遵守。
# 于是「转译层有没有把风险标注/完成判据传下去」会直接表现为行为的差异，
# 而两个版本用的是同一份假模型，差异只可能来自提示词本身。

RISK_WORDS = ("pay", "bill", "transfer", "send", "message", "delete")


def ab_engine_reply(msgs: list) -> str:
    has_assistant = any(m.get("role") == "assistant" for m in msgs)
    if has_assistant:
        return '{"action":"done","summary":"已完成"}'

    user = " ".join(text_of(m.get("content")) for m in msgs if m.get("role") == "user")
    m = re.search(r"任务：([^\n]+)", user)
    task = (m.group(1) if m else "").strip().lower()

    is_risky = any(w in task for w in RISK_WORDS)
    # 提示词里有没有明确的「风险等级 + 必须先 ask」——这正是转译层的产物
    told_about_risk = ("风险等级：" in user) and ("ask" in user)

    if is_risky and told_about_risk:
        # 被告知有风险 → 先确认（守则 §7.2 要求的模型侧纪律）
        return '{"action":"ask","question":"这项操作涉及资金/发送，确认继续吗？"}'
    if is_risky:
        # 没人告诉它有风险 → 直接动手（这正是缺转译层时的危险路径）
        return '{"action":"tap","px":0.5,"py":0.5}'
    if "bluetooth" in task:
        return '{"action":"setting","page":"bluetooth"}'
    if "search" in task or "meituan" in task:
        return '{"action":"open_api","target":"美团","intent":"search","param":"奶茶"}'
    return '{"action":"setting","page":"display"}'


def text_of(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        out = []
        for part in content:
            if isinstance(part, dict) and isinstance(part.get("text"), str):
                out.append(part["text"])
        return " ".join(out)
    return ""


def log_call(kind: str, msgs: list, body: dict, content: str) -> None:
    global _seq
    with _lock:
        _seq += 1
        seq = _seq
        user_text = " ".join(text_of(m.get("content")) for m in msgs if m.get("role") == "user")
    rec = {
        "seq": seq,
        "kind": kind,
        "max_tokens": body.get("max_tokens"),
        "model": body.get("model"),
        "user": user_text[:600],
        "reply": content[:600],
        "scene": scene(),
    }
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    except Exception:
        pass
    print(f"[mock] #{seq} {kind} max_tokens={rec['max_tokens']} → {content[:70]!r}", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):   # 静音默认逐请求日志
        pass

    def _send(self, code: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send(200, {"ok": True, "scene": scene(), "calls": _seq})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {}
        msgs = body.get("messages") or []
        sys_txt = " ".join(text_of(m.get("content")) for m in msgs if m.get("role") == "system")
        is_translate = "结构化转译器" in sys_txt
        sc = scene()
        user_txt = " ".join(text_of(m.get("content")) for m in msgs if m.get("role") == "user")
        content = (translator_reply(sc, user_txt) if is_translate
                   else engine_reply(msgs, sc))
        # 调试铁证：每个到达的请求一行（stderr → nohup 日志），排查「请求未到达」类悬案
        print(f"[mock] kind={'translate' if is_translate else 'engine'} body={len(raw)}B "
              f"model={body.get('model')} msgs={len(msgs)} reply={content[:60]!r}",
              file=sys.stderr, flush=True)
        log_call("translate" if is_translate else "engine", msgs, body, content)
        self._send(200, {
            "id": "mock",
            "object": "chat.completion",
            "model": body.get("model", "mock"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 120, "completion_tokens": 60, "total_tokens": 180},
        })

    def do_DELETE(self):
        """清空调用日志（每次验证前重置），并把有状态场景的推进复位。

        复位必须有：同一次验证里会**反复用同一场景**（如 m3invalid 的关网关基线与
        开网关对照），只按「场景名变化」重置会让第二轮的第一次调用直接拿到序列
        末项 done——表现为「任务秒完成、零设备动作、零拒绝日志」（实测踩过）。
        """
        global _m3_pos
        with _lock:
            _m3_pos = 0
        try:
            open(LOG_FILE, "w").close()
        except Exception:
            pass
        self._send(200, {"ok": True})


def main() -> int:
    try:
        os.remove(LOG_FILE)
    except Exception:
        pass
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[mock] 假模型服务已启动 http://0.0.0.0:{PORT}/v1  "
          f"场景文件={SCENE_FILE} 日志={LOG_FILE}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
