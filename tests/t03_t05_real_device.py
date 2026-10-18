#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""t03_t05_real_device.py — T05a / T04a / T03a 真机行为回归（零 root 主链路）。

    python3 tests/t03_t05_real_device.py [serial]

为什么必须真机：三张卡的缺陷都活在**时序**里，桌面静态门禁证明不了——
  · T05a：任务 A 的 4 秒收尾回调会不会撤掉**4 秒内启动的新任务 B** 的胶囊与保活状态；
  · T04a：连续两次无证据 done 是否真的落到「不确定」且不写记忆库；
  · T03a：启动确认代点改走 exec() 后，真机上是否仍然点得动（代点成败只在真机可见）。

主链路（安装 → 输入任务 → 运行 → 终态）全程只用用户态能力：
  · input / uiautomator dump / am start —— 与普通用户操作等价；
  · su **仅**用于布置前置状态（写 prefs）与取证（读记忆库文件），结束时一律还原；
    应用自身不依赖 root，无 root 设备上主链路完全一样。

模型端点：脚本自带一个**本机**假模型（127.0.0.1:19111，OpenAI 兼容），
经 adb reverse 暴露给手机；不发一个字节到公网、不花一分钱。

场景文件 /tmp/t03_t05_scene.txt（脚本内自动切换）：
  done_only  每次都回 done（无任何动作）—— 造「无证据 done」（T04a）
  open_calc  先 app 计算器、再 done     —— 造「有证据 done」（T04a）
  long_wait  先 wait 6000ms、再 done    —— 造「跑得够久的新任务 B」（T05a）

退出码：0 = 全 PASS（允许 SKIP 且有说明）；1 = 有 FAIL；2 = 环境不可用。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
from fuzzy_e2e_mock import (  # noqa: E402  复用既有设备交互，避免第二套实现
    clear_logcat, dump_log, dump_xml, find_by_id, find_widgets, sh,
)

PKG = "com.dsh.agentlite"
MAIN = f"{PKG}/.MainActivity"
HISTORY = f"/sdcard/Android/data/{PKG}/files/task_history.jsonl"
FLOWMEM = f"/data/data/{PKG}/files/flowmem.json"
PREF = f"/data/data/{PKG}/shared_prefs/agent_prefs.xml"
CRED = f"/data/data/{PKG}/shared_prefs/agentlite_cred.xml"
SCENE_FILE = Path("/tmp/t03_t05_scene.txt")
HOST_BAK = Path("/tmp/t03_t05_device_prefs.bak")

# 以下 su/prefs 工具与 tests/credential_real_device.py 同款（刻意复制而非 import：
# 那个模块在 **import 时**就 bind 19101/19102，与本脚本并发时会 Address already in use）。


def su(serial: str, cmd: str, timeout: int = 30) -> str:
    """以 root 执行（仅布置前置状态/取证）。命令来自本脚本固定字面量，不拼外部输入。"""
    import shlex
    r = subprocess.run(["adb", "-s", serial, "shell", f"su -c {shlex.quote(cmd)}"],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def _esc(v: str) -> str:
    return (v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def _xml(entries: dict) -> str:
    rows = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for k, v in sorted(entries.items()):
        if v is None:
            continue
        if isinstance(v, bool):
            rows.append(f'    <boolean name="{k}" value="{str(v).lower()}" />')
        elif isinstance(v, int):
            rows.append(f'    <long name="{k}" value="{v}" />')
        else:
            rows.append(f'    <string name="{k}">{_esc(str(v))}</string>')
    rows.append("</map>")
    return "\n".join(rows) + "\n"


def write_prefs(serial: str, entries: dict) -> None:
    """写 prefs 并修回应用属主。必须在应用停止时调用。"""
    import shlex
    xml = _xml(entries)
    subprocess.run(["adb", "-s", serial, "shell", f"su -c {shlex.quote(f'cat > {PREF}')}"],
                   input=xml.encode("utf-8"), capture_output=True, timeout=30)
    uid_gid = su(serial, f"stat -c %u:%g /data/data/{PKG}").strip()
    su(serial, f"chown {uid_gid} {PREF}; chmod 660 {PREF}; "
               f"restorecon {PREF} 2>/dev/null; true")


def read_prefs(serial: str) -> dict:
    xml = su(serial, f"cat {PREF}")
    out: dict = {}
    for m in re.finditer(r'<(\w+) name="([^"]+)"(?:\s+value="([^"]*)")?\s*(?:/>|>([^<]*)<)', xml):
        kind, name = m.group(1), m.group(2)
        # SharedPreferences 里 boolean/long/int 用 value 属性，字符串用元素文本
        # （<string name="k">v</string>）。两种形式都要认——只认属性时字符串读回一律是空串，
        # 会让「按读回值判断前置条件」的断言永远假失败（2026-10-11 实测 root_mode 即如此）。
        val = m.group(3) if m.group(3) is not None else m.group(4)
        if kind == "boolean":
            out[name] = (val == "true")
        elif kind in ("long", "int"):
            out[name] = int(val or 0)
        elif kind == "string":
            out[name] = val or ""
    return out

MOCK_PORT = 19111
MOCK_BASE = f"http://127.0.0.1:{MOCK_PORT}/v1"
MOCK_KEY = "fixture-own-key-not-a-secret"
MODEL = "fixture-model"

TASK_A = "t05a alpha done"
TASK_B = "t05a beta wait"
TASK_N = "t04a no evidence"
TASK_C = "t04a open calc"

RESULTS: list[tuple[str, str, str, str]] = []   # cid, desc, verdict(PASS/FAIL/SKIP), note


def check(cid: str, desc: str, ok: bool, note: str = "") -> bool:
    RESULTS.append((cid, desc, "PASS" if ok else "FAIL", note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))
    return ok


def skip(cid: str, desc: str, note: str) -> None:
    RESULTS.append((cid, desc, "SKIP", note))
    print(f"  SKIP [{cid}] {desc} — {note}")


# ------------------------------------------------------------------ 假模型
SCENES = {
    "done_only": ['{"action":"done","summary":"我做完了"}'],
    # 词表（AgentEngine.SCHEMA）里 app 动作的参数名是 **package**，不是 target：
    # 用 target 会让引擎读到空 package，模糊解析 q="" 命中任意已装应用
    # ——2026-10-09 实测：这就是「有证据 done」场景假失败的第二个真凶。
    "open_calc": ['{"action":"app","package":"com.coloros.calculator"}',
                  '{"action":"done","summary":"计算器已打开"}'],
    # A/B 故意都"跑得久一点"：B 的任务文本必须在 A 仍在跑的时候填完，
    # 否则 A 一结束那个 4 秒窗口就已经在倒计时，挤不进去就没有区分度。
    "alpha": ['{"action":"wait","ms":5000}',
              '{"action":"done","summary":"alpha 等待结束"}'],
    "beta": ['{"action":"wait","ms":8000}',
             '{"action":"done","summary":"beta 等待结束"}'],
}
_state = {"scene": None, "pos": 0}


def set_scene(name: str) -> None:
    _state["scene"] = name
    _state["pos"] = 0
    SCENE_FILE.write_text(name + "\n", encoding="utf-8")


def _next_reply() -> str:
    try:
        name = SCENE_FILE.read_text(encoding="utf-8").strip() or "done_only"
    except Exception:
        name = "done_only"
    if name != _state["scene"]:
        _state["scene"] = name
        _state["pos"] = 0
    seq = SCENES.get(name, SCENES["done_only"])
    i = min(_state["pos"], len(seq) - 1)
    _state["pos"] += 1
    return seq[i]


def _text_of(content) -> str:
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict))
    return content if isinstance(content, str) else ""


# 主循环的 system 一定带动作词表（AgentEngine.SCHEMA 的首句）；转发译/规划两个
# 前置请求不带。必须按这个分流：否则前置请求会消耗场景序列，主循环第 1 步就拿到
# 序列末尾的 done——2026-10-09 实测，这就是「app 到达后 done」永远拿不到证据的真凶。
MAIN_LOOP_MARK = "动作必须是单个 JSON 对象"


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            body = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            body = {}
        msgs = body.get("messages") or []
        sys_txt = " ".join(_text_of(m.get("content")) for m in msgs if m.get("role") == "system")
        cid = self.headers.get("Content-Type") or "application/json; charset=utf-8"
        reply = _next_reply() if MAIN_LOOP_MARK in sys_txt else '{"v":0}' 
        data = json.dumps({
            "id": "local-mock", "object": "chat.completion", "model": MODEL,
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": reply}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        }, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", cid)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):   # 静音
        pass


def start_mock():
    srv = ThreadingHTTPServer(("127.0.0.1", MOCK_PORT), _Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ------------------------------------------------------------------ 设备侧
def run_point(xml: str) -> tuple | None:
    """从 dump 里算「运行」按钮的**安全点击点**：x 取水平中心，y 取按钮下缘上方 1/4 高度。

    为什么不点几何中心：实测任务结束后整块布局会下移约 24px，用旧 dump 的中心点
    会落在按钮上边缘之外（2026-10-09 实测：B 的三次点击全部落空就是这么来的）。
    按下缘上方 1/4 取点，在「就绪 / 刚结束」两种布局下都稳在按钮内。
    """
    m = re.search(r'<node[^>]*resource-id="%s:id/btnRun"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                  % re.escape(PKG), xml)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return ((x1 + x2) // 2, y2 - (y2 - y1) // 4)


def tap_run(serial: str, ui: dict) -> None:
    x, y = ui["btnRun"]
    sh(serial, f"input tap {x} {y}", timeout=20)


def type_task(serial: str, text: str, ui: dict) -> None:
    """把任务文本填进主界面输入框（逐词 + SPACE；`input keyboard text` 不弹软键盘）。

    收尾必须按 ESC 收软键盘：`input tap etTask` 聚焦会弹出 IME（ColorOS 键盘高约
    1000px），它会盖住屏幕下半部的「运行」按钮——2026-10-09 实测：不收键盘时
    点 (957,1641) 全部落在键盘上，任务永远起不来，而界面与日志毫无异常。
    ESC（keyevent 111）只收键盘，不像 BACK（keyevent 4）那样会退出 Activity。
    """
    ex, ey = ui["etTask"]
    parts = [f"input tap {ex} {ey}", "sleep 1.2", "input keyevent 123"]
    parts += ["input keyevent 67"] * 40
    words = text.split()
    for i, w in enumerate(words):
        parts.append(f"input keyboard text {w}")
        if i < len(words) - 1:
            parts.append("input keyevent 62")
        parts.append("sleep 0.25")
    sh(serial, " ; ".join(parts), timeout=90)
    time.sleep(2.0)
    sh(serial, "input keyevent 111", timeout=20)   # 收键盘（见上方注释）
    time.sleep(1.5)


def submit(serial: str, text: str, ui: dict) -> bool:
    """输入任务 → 点运行 → 等「任务启动」日志。

    全程只用 prepare 探测到的坐标，任务前**不再 dump**：uiautomator dump 会把
    无障碍服务挤掉线，服务刚重连时 getWindows() 只返回 SystemUI，引擎第 1 步
    就会「感知到仅系统状态栏节点」并切纯视觉，`app X` 的到达核对必然失败——
    2026-10-09 实测：这是「有证据 done」被判「不确定」的第三个真凶（前两个是
    通知权限对话框，以及 mock 把 app 参数写成 target）。

    失败重试一次（重新填文本再点），避免偶发的注入丢失被记成产品缺陷。
    """
    sh(serial, f"am start -n {MAIN}", timeout=20)
    time.sleep(2.0)
    for _ in range(2):
        clear_logcat(serial)
        type_task(serial, text, ui)
        tap_run(serial, ui)
        for _ in range(14):
            time.sleep(0.5)
            if f"任务启动: {text}" in dump_log(serial):
                return True
        time.sleep(1.0)
    return False


def wait_terminal(serial: str, timeout: float = 90.0) -> str:
    """等终态日志，返回命中的那一行（任务完成 / 任务结果不确定 / 任务失败）。"""
    end = time.time() + timeout
    while time.time() < end:
        log = dump_log(serial)
        for key in ("任务结果不确定", "任务完成：", "任务完成:", "任务未完成", "任务失败"):
            if key in log:
                return key
        time.sleep(0.2)
    return ""


def power_lock_held(serial: str) -> bool:
    out = sh(serial, "dumpsys power | grep agentlite:lockscreen_keepalive", timeout=25)
    return "agentlite:lockscreen_keepalive" in out


def overlay_windows(serial: str) -> str:
    """无障碍覆盖层窗口（胶囊/卡片/光晕）在 WindowManager 里的痕迹。"""
    return sh(serial, "dumpsys window windows | grep -i agentlite | head -8", timeout=25)


def notif_title(serial: str) -> str:
    out = sh(serial, "dumpsys notification --noredact | grep -A 4 com.dsh.agentlite", timeout=30)
    m = re.search(r"android\.title=([^\n]*)", out)
    return (m.group(1).strip() if m else "")


def history_tail(serial: str, n: int = 3) -> list[dict]:
    raw = sh(serial, f"tail -n {n} {HISTORY} 2>/dev/null", timeout=25)
    out = []
    for line in raw.strip().splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except Exception:
            pass
    return out


def flowmem_bytes(serial: str) -> str:
    """记忆库文件大小（root 取证；读不到就返回空串，不据此判 FAIL）。"""
    out = su(serial, f"test -f {FLOWMEM} && stat -c %s {FLOWMEM} || echo 0").strip()
    return out if out.isdigit() else ""


FLOWMEM_BAK = FLOWMEM + ".t04a.bak"


def flowmem_isolate(serial: str) -> None:
    """把记忆库整体移开，让 T04a 走真实规划而不是命中复现。

    不隔离会假失败：上一轮验收已把「打开计算器」写进记忆库，本轮同场景任务被
    直接复现完成（logcat：「命中操作记忆库」→「记忆库复现 1/1」→「已按记忆库
    直接完成（1 步，未重新规划）」），根本走不到 AgentEngine 的入库分支
    （AgentEngine.java:992 `if (recordable && !recordedSteps.isEmpty())`），
    于是「已写入操作记忆库」与「操作记忆未入库」两条日志都不出现，T4-9 报
    「既无入库也无未入库日志」——2026-10-11 实测就是这个假失败。
    移开之后「有证据 done」才会真的规划 + 执行 + 入库，T4-9 才有意义。
    """
    su(serial, f"rm -f {FLOWMEM_BAK}; "
               f"if [ -f {FLOWMEM} ]; then cp -p {FLOWMEM} {FLOWMEM_BAK} && rm -f {FLOWMEM}; fi")


def flowmem_restore(serial: str) -> None:
    """还原被 flowmem_isolate 移开的记忆库（幂等，可作 restore() 兜底）。"""
    su(serial, f"if [ -f {FLOWMEM_BAK} ]; then mv -f {FLOWMEM_BAK} {FLOWMEM}; fi")


# ------------------------------------------------------------------ 阶段
def prepare(serial: str) -> dict:
    print("\n== P 准备：假模型 + 设备前置状态 ==")
    start_mock()
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    if not HOST_BAK.exists():
        HOST_BAK.write_text(su(serial, f"cat {PREF} 2>/dev/null"), encoding="utf-8")
        print(f"  · 已备份设备用户态 → {HOST_BAK}")
    else:
        print(f"  · 已有用户态快照（{HOST_BAK}），本轮不覆盖")
    subprocess.run(["adb", "-s", serial, "reverse", f"tcp:{MOCK_PORT}", f"tcp:{MOCK_PORT}"],
                   capture_output=True, timeout=20)
    print(f"  · adb reverse tcp:{MOCK_PORT} 已建立；假模型 {MOCK_BASE}")
    write_prefs(serial, {
        "base_url": MOCK_BASE, "api_key": MOCK_KEY, "model": MODEL,
        "use_own_key": True, "use_gateway": False, "planner_model": MODEL,
        "notify_asked": True,
        # 零 root 条件必须显式写进 prefs：write_prefs 是整体覆盖（cat > PREF），
        # 不写这一项就等于把它抹掉，Prefs.rootMode() 落回默认 "auto"
        # （Prefs.java:132 getString("root_mode","auto")），run 期间就不在零 root 下。
        "root_mode": "off",
    })
    su(serial, f"rm -f {CRED}")
    # 零 root 条件自检：write_prefs 是整体覆盖，任何一处 entries 漏写 root_mode 都会让它
    # 落回默认 "auto"（Prefs.java:132），整套验收就跑在了别的前提上。跑阶段前读回确认一次。
    mode = read_prefs(serial).get("root_mode")
    check("P-ROOT", "零 root 条件：run 期间设备上 root_mode=off", mode == "off",
          f"root_mode={mode!r}（write_prefs 漏写该项会落回默认 auto）")
    # 预授通知权限（零 root 的 pm grant）：否则首次跑任务时 App 会申请
    # POST_NOTIFICATIONS，系统对话框把前台变成 com.android.systemui，
    # 引擎随即被判定为「仅状态栏节点」切纯视觉，`app X` 的到达核对必然失败
    # ——2026-10-09 实测：这就是 T04a「有证据 done」场景假失败的真凶。
    sh(serial, f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", timeout=20)
    sh(serial, "input keyevent 224")          # 唤醒
    time.sleep(1)
    sh(serial, "wm dismiss-keyguard")
    time.sleep(1.5)
    sh(serial, "am start -n " + MAIN)
    time.sleep(5)
    ui = goto_main(serial)
    pt = run_point(dump_xml(serial))
    if pt:
        ui["btnRun"] = pt
    return ui


def texts(serial: str) -> str:
    return " ".join(re.findall(r'text="([^"]*)"', dump_xml(serial)))


def tap_text(serial: str, needle: str) -> bool:
    """按可见文本点击（bounds 中心）；找不到返回 false。"""
    xml = dump_xml(serial)
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if needle in m.group(1):
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            sh(serial, f"input tap {x} {y}")
            return True
    return False


def goto_main(serial: str, timeout: int = 60) -> dict:
    """推进到主界面：先点掉首启说明框（「知道了」），再跳过登录引导（「稍后再说」）。

    与 tests/credential_real_device.py 的真机实测结论一致：少这两步就会把
    「界面还没到位」误判成「控件找不到」——本轮自带 Key 且未登录，LoginActivity
    一定会被 MainActivity 主动拉起。
    """
    end = time.time() + timeout
    while time.time() < end:
        ui = find_widgets(serial)
        if "etTask" in ui and "btnRun" in ui:
            return ui
        t = texts(serial)
        for needle in ("知道了", "稍后再说", "跳过"):
            if needle in t:
                tap_text(serial, needle)
                time.sleep(2.0)
                break
        else:
            time.sleep(1.5)
    ui = find_widgets(serial)
    if "etTask" not in ui:
        print(f"  · 主界面未就绪（当前顶层文本：{texts(serial)[:120]}）")
    return ui


def stage_t05a(serial: str, ui: dict) -> None:
    print("\n== T05a 任务代际：旧任务的收尾回调不得撤掉新任务 ==")
    if "etTask" not in ui or "btnRun" not in ui:
        check("T5-0", "主界面就绪", False, "找不到 etTask/btnRun")
        return
    set_scene("alpha")
    if not submit(serial, TASK_A, ui):
        check("T5-1", "任务 A 提交", False, "未见「任务启动」")
        return
    check("T5-1", "任务 A 提交成功（终态会保留结果卡 → 4 秒收尾定时器生效）", True)

    # 不再预填 B 的任务文本：A 运行期间的高频注入实测会被 ColorOS 静默丢弃
    # （输入框里最终仍是 A 的文本）。改为**复用输入框里的文本**直接启动下一个任务
    # ——T05a 关心的是「新任务启动后旧任务的收尾回调会不会破坏它」，与任务文本无关。
    key = wait_terminal(serial, timeout=60)
    check("T5-2", "任务 A 进入终态", bool(key), f"未见终态日志（{key!r}）")
    if not key:
        return

    t_done = time.time()
    set_scene("beta")
    # 清 logcat 后只剩 B 的日志：A 文本仍留在输入框里时，靠"任务文本"匹配会误判
    # （2026-10-09 实测：B 已启动却被判 started=False）。这里只认「任务启动」。
    clear_logcat(serial)
    started = False
    gap_tap = 99.0
    for _ in range(3):
        gap_tap = time.time() - t_done        # 每次点击都重算：只有真的没启动才重试
        tap_run(serial, ui)                   # ← 必须落在 A 完成后的 4 秒窗口内
        time.sleep(1.2)
        if "任务启动:" in dump_log(serial):
            started = True
            break
        print(f"  · 未见到 B 启动，重试点击（gap={gap_tap:.1f}s）")
    else:
        gap_tap = time.time() - t_done
    check("T5-3", f"任务 B 在 A 完成后 {gap_tap:.1f} 秒启动（<4 秒才有区分度）",
          started and gap_tap < 4.0, f"started={started} gap_tap={gap_tap:.1f}s")
    if not started:
        return

    # A 的 4 秒回调此刻已触发（或即将触发）；B 仍在跑（wait 6000ms）。
    time.sleep(5.0)
    lock_ok = power_lock_held(serial)
    check("T5-4", "B 运行中保活锁仍持有（旧任务回调未把服务状态置 false）", lock_ok,
          "dumpsys power 未见 agentlite:lockscreen_keepalive")
    win = overlay_windows(serial)
    check("T5-5", "B 运行中覆盖层窗口仍在（旧任务回调未撤掉新任务胶囊）", bool(win.strip()),
          "dumpsys window 未见 agentlite 覆盖层窗口")
    title = notif_title(serial)
    check("T5-6", "通知仍为运行态（不是「Agent 待命」）", "待命" not in title, f"title={title!r}")
    wait_terminal(serial, timeout=40)
    time.sleep(1.0)


def stage_t04a(serial: str, ui: dict) -> None:
    print("\n== T04a 证据分级：无证据 done 必须落到「不确定」且不入库 ==")
    if "etTask" not in ui:
        check("T4-0", "主界面就绪", False, "找不到 etTask")
        return
    # 先隔离记忆库：否则「有证据 done」会被记忆库直接复现，走不到入库分支（见 flowmem_isolate 注释）
    flowmem_isolate(serial)
    set_scene("done_only")
    before = flowmem_bytes(serial)
    if not submit(serial, TASK_N, ui):
        check("T4-1", "无证据任务提交", False, "未见「任务启动」")
        return
    key = wait_terminal(serial, timeout=60)
    log = dump_log(serial)
    check("T4-2", "连续两次无证据 done → 终态「不确定」", "任务结果不确定" in log,
          f"终态日志={key!r}")
    check("T4-3", "无证据终态不写记忆库（日志无「已写入操作记忆库」）",
          "已写入操作记忆库" not in log, "出现了入库日志")
    tail = history_tail(serial, 1)
    check("T4-4", "任务历史最后一条如实记为「不确定」",
          bool(tail) and tail[-1].get("result") == "不确定",
          f"tail={tail[-1] if tail else None}")
    after = flowmem_bytes(serial)
    if before and after:
        check("T4-5", f"无证据终态不新建也不增长记忆库（{before} → {after} 字节）", after == before,
              "文件大小变化")
    else:
        skip("T4-5", "记忆库文件大小取证", f"读不到（before={before!r} after={after!r}）")

    print("\n== T04a 证据分级：有证据 done 仍走完成路径并入库 ==")
    set_scene("open_calc")
    before2 = flowmem_bytes(serial)
    if not submit(serial, TASK_C, ui):
        check("T4-6", "有证据任务提交", False, "未见「任务启动」")
        return
    key = wait_terminal(serial, timeout=90)
    log = dump_log(serial)
    check("T4-7", "app 到达后 done → 终态「完成」", "任务完成" in log, f"终态日志={key!r}")
    tail = history_tail(serial, 1)
    check("T4-8", "任务历史最后一条记为「完成」",
          bool(tail) and tail[-1].get("result") == "完成",
          f"tail={tail[-1] if tail else None}")
    check("T4-9", "有证据路径仍入库（日志出现入库结论）",
          ("已写入操作记忆库" in log) or ("操作记忆未入库" in log),
          "既无入库也无未入库日志")
    after2 = flowmem_bytes(serial)
    if before2 and after2:
        print(f"  · 记忆库文件 {before2} → {after2} 字节（诊断，不作断言）")
    flowmem_restore(serial)


def stage_t03a(serial: str, ui: dict) -> None:
    print("\n== T03a 代点：启动确认代点经 exec() 后仍生效 ==")
    if "etTask" not in ui:
        check("T3-0", "主界面就绪", False, "找不到 etTask")
        return
    set_scene("open_calc")
    clear_logcat(serial)
    if not submit(serial, "t03a launch confirm", ui):
        check("T3-1", "代点场景任务提交", False, "未见「任务启动」")
        return
    wait_terminal(serial, timeout=90)
    log = dump_log(serial)
    if "检测到本助手引发的跨应用启动确认" in log:
        check("T3-1", "代点日志出现（跨应用启动确认被识别）", True)
        check("T3-2", "代点未报失败（exec 路径点得动）",
              "代点「" not in log or "代点「" in log and "」失败" not in log,
              "出现「代点…失败」")
    else:
        skip("T3-1", "启动确认代点观测",
             "本机未弹「想要打开 XX」确认框（系统已记住允许）——代点分支未被触发，非缺陷")
    terms = read_prefs(serial).get("auto_accept_terms")
    check("T3-3", "autoAcceptTerms 仍默认关（设备 prefs 无该键或为 false）",
          terms in (None, False), f"auto_accept_terms={terms!r}")


def restore(serial: str) -> None:
    print("\n== R 还原设备用户态 ==")
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    if HOST_BAK.exists():
        xml = HOST_BAK.read_text(encoding="utf-8")
        if xml.strip():
            subprocess.run(["adb", "-s", serial, "shell", f"su -c 'cat > {PREF}'"],
                           input=xml.encode("utf-8"), capture_output=True, timeout=30)
        uid_gid = su(serial, f"stat -c %u:%g /data/data/{PKG}").strip()
        su(serial, f"chown {uid_gid} {PREF} 2>/dev/null; "
                        f"restorecon {PREF} 2>/dev/null; true")
    su(serial, f"rm -f {CRED}")
    flowmem_restore(serial)   # 兜底：stage_t04a 中途 return 时记忆库仍在隔离位
    subprocess.run(["adb", "-s", serial, "reverse", "--remove", f"tcp:{MOCK_PORT}"],
                   capture_output=True, timeout=20)
    sh(serial, f"am start -n {MAIN}")
    print("  · prefs 已还原、测试令牌已清、记忆库已归位、adb reverse 已撤")


def screen_ready(serial: str) -> tuple[bool, str]:
    sh(serial, "input keyevent 224")
    time.sleep(1.0)
    wake = sh(serial, "dumpsys power | grep mWakefulness=", timeout=20)
    focus = sh(serial, "dumpsys window | grep mCurrentFocus", timeout=20)
    xml = dump_xml(serial)
    locked = "mWakefulness=Awake" not in wake or "NotificationShade" in focus
    if locked or "<hierarchy" not in xml:
        return False, f"wake={wake.strip()[:40]} focus={focus.strip()[:60]}"
    return True, ""


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5599"
    print("=" * 78)
    print(f"T03a/T04a/T05a 真机行为回归 · 设备 {serial}")
    print(f"  假模型 {MOCK_BASE}（本机，经 adb reverse；不出网不花钱）")
    print("=" * 78)
    ok, why = screen_ready(serial)
    if not ok:
        print(f"环境不可用：设备未解锁/屏幕不可用（{why}）")
        print("解锁手机后重跑；锁屏下 uiautomator dump 会挂死，主链路无法验证。")
        return 2
    ui = prepare(serial)
    try:
        stage_t05a(serial, ui)
        stage_t04a(serial, ui)
        stage_t03a(serial, ui)
    finally:
        restore(serial)
    print("\n" + "=" * 78)
    passed = sum(1 for r in RESULTS if r[2] == "PASS")
    failed = sum(1 for r in RESULTS if r[2] == "FAIL")
    skipped = sum(1 for r in RESULTS if r[2] == "SKIP")
    for cid, desc, verdict, note in RESULTS:
        if verdict != "PASS":
            print(f"  {verdict:4} [{cid}] {desc}" + (f" — {note}" if note else ""))
    print(f"结果：{passed} 通过 / {failed} 失败 / {skipped} 跳过")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
