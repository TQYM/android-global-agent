#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""credential_real_device.py — 凭据路由（T01/T02）真机行为回归，零 root 主链路。

    python3 tests/credential_real_device.py [serial] [阶段…]

为什么要有它：桌面 JVM 回归（tests/credential_route_guard.py）证明了**纯逻辑**，
证明不了真机上的三件事——首启到底把用户推到哪一页、点「运行」究竟有没有发请求、
请求带着哪个凭据去了哪个端点。后两者只有把 App 真正跑起来才看得见。

设备侧只用两种能力：
  · uiautomator dump / input tap / input text —— 与普通用户操作等价（零 root）；
  · su 直读直写 shared_prefs —— **仅用于布置前置状态与取证**，应用自身不依赖 root，
    主链路（首启 → 选择来源 → 跑任务）在无 root 设备上完全一样。
    写 prefs 一律在应用停止时用 `cat >` 原地重定向（`sed -i` 会重建文件、属主变 root，
    App 读不到，实测踩过），写完修回应用属主。

模型端点：脚本自带两个**本机**假端点，不发一个字节到公网、不花钱：
  · 19001 = 「自己的配置」端点（用户 Base URL 指过来）
  · 19002 = 「账号网关」端点（prefs.sync_server 指过来）
每个到达的请求记录 端口/路径/Authorization/模型名，按注入状态码回复。
**关键断言语义**：故障注入下必须**只**打中选中的那个端点——跨端点即付费主体漂移。

阶段（可单跑）：
  A 首启与登录态：新装默认走账号；已登录无 Key 必须放行
  B 自己的配置：有 Key 放行；缺 Key/坏地址停住且**不**自动切账号
  C 账号通道：显式账号时旧 Key 不兜底；关闭/失效的准确提示
  D 模型缺失
  E 故障归因：402 归到正确主体；401/429/500 不跨通道
  F 设置页保存后的显式选择卡（保存 ≠ 同意换付费主体）
  G 还原设备用户态

退出码：0 = 全部 PASS；1 = 有 FAIL；2 = 环境不可用。
"""
from __future__ import annotations

import json
import re
import shlex
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
from fuzzy_e2e_mock import (  # noqa: E402  复用既有设备交互，避免第二套实现
    clear_logcat, dump_log, dump_xml, ensure_main, find_widgets, sh,
)

PKG = "com.dsh.agentlite"
PREF = f"/data/data/{PKG}/shared_prefs/agent_prefs.xml"
CRED = f"/data/data/{PKG}/shared_prefs/agentlite_cred.xml"
MAIN = f"{PKG}/.MainActivity"
SETTINGS = f"{PKG}/.SettingsActivity"
HISTORY = f"/sdcard/Android/data/{PKG}/files/task_history.jsonl"   # 外部存储，零 root 可读

OWN_PORT, GW_PORT = 19101, 19102   # 避开 18999（共享 mock）与 19001/19002（本机既有隧道）
OWN_BASE = f"http://127.0.0.1:{OWN_PORT}/v1"
GW_BASE = f"http://127.0.0.1:{GW_PORT}"
OWN_KEY = "fixture-own-key-not-a-secret"
ACC_TOKEN = "fixture-account-token-not-a-secret"
ACC_ID = "fixture-user"
MODEL = "fixture-model"
TASK = "open settings"
REPLY = '{"action":"done","summary":"凭据路由真机验证"}'
HOST_BAK = Path("/tmp/credential_device_prefs.bak")

RESULTS: list[tuple[str, str, str]] = []


def check(cid: str, desc: str, ok: bool, note: str = "") -> bool:
    RESULTS.append((cid, "PASS" if ok else "FAIL", note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))
    return ok


# ---------------------------------------------------------------- 设备侧
def su(serial: str, cmd: str, timeout: int = 30) -> str:
    """以 root 执行（仅布置前置状态/取证）。命令来自本脚本固定字面量，不拼外部输入。"""
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


def backup_once(serial: str) -> None:
    """用户态只备份一次：重复运行不得覆盖第一份真实快照。"""
    if HOST_BAK.exists():
        print(f"  · 已有用户态快照（{HOST_BAK}），本轮不覆盖")
        return
    HOST_BAK.write_text(su(serial, f"cat {PREF} 2>/dev/null"), encoding="utf-8")
    print(f"  · 已备份设备用户态 → {HOST_BAK}")


def restore(serial: str) -> None:
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    for port in (OWN_PORT, GW_PORT):
        subprocess.run(["adb", "-s", serial, "reverse", "--remove", f"tcp:{port}"],
                       capture_output=True, timeout=15)
    if HOST_BAK.exists():
        xml = HOST_BAK.read_text(encoding="utf-8")
        if xml.strip():
            subprocess.run(["adb", "-s", serial, "shell", f"su -c {shlex.quote(f'cat > {PREF}')}"],
                           input=xml.encode("utf-8"), capture_output=True, timeout=30)
        else:
            su(serial, f"rm -f {PREF}")
        uid_gid = su(serial, f"stat -c %u:%g /data/data/{PKG}").strip()
        su(serial, f"chown {uid_gid} {PREF} 2>/dev/null; restorecon {PREF} 2>/dev/null; true")
    su(serial, f"rm -f {CRED}")
    print("  · 已还原设备用户态、清掉测试令牌、撤掉 adb reverse 隧道")


def goto_main(serial: str, timeout: int = 30) -> dict:
    """推进到主界面：先点掉首启说明框（「知道了」），再跳过登录引导（「稍后再说」）。

    两处都是真机上实测的必经之路，少一步就会把「界面还没到位」误判成「凭据卡片没出现」：
      · MainActivity 首启弹「Agent 操作手机说明」，按钮是「知道了」，不是 btnSkip；
      · 未登录时 MainActivity 400ms 后主动拉起 LoginActivity，其跳过按钮才是 btnSkip。
    """
    end = time.time() + timeout
    while time.time() < end:
        ui = find_widgets(serial)
        if "etTask" in ui and "btnRun" in ui:
            return ui
        t = texts(serial)
        if "知道了" in t:
            tap_text(serial, "知道了")
            time.sleep(1.5)
            continue
        if "稍后再说" in t:
            tap_text(serial, "稍后再说")
            time.sleep(1.5)
            continue
        time.sleep(1.5)
    return find_widgets(serial)


def scenario(serial: str, entries: dict) -> None:
    """停应用 → 清测试令牌 → 写前置状态 → 推进到主界面。"""
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    su(serial, f"rm -f {CRED}")
    write_prefs(serial, entries)
    clear_logcat(serial)
    sh(serial, f"am start -n {MAIN}")
    time.sleep(4)
    goto_main(serial)


def texts(serial: str) -> str:
    return " ".join(re.findall(r'text="([^"]*)"', dump_xml(serial)))


def wait_text(serial: str, needle: str, timeout: int = 14) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        if needle in texts(serial):
            return True
        time.sleep(1)
    return False


def tap_run(serial: str, task: str = TASK) -> bool:
    """在主页输入任务并点「运行」。

    返回是否**真的启动**：以「假端点收到非转译请求」为准，而不是读 logcat——
    OPPO 真机 logcat 噪声极大，`logcat -d` 常把应用那行刷掉，会假报「没启动」。
    停住类用例本来就该零请求，故该判据对两个方向都成立。
    """
    ui = goto_main(serial)
    if "etTask" not in ui or "btnRun" not in ui:
        return False
    ex, ey = ui["etTask"]
    sh(serial, f"input tap {ex} {ey}")
    time.sleep(1.2)
    sh(serial, "input keyevent 123")
    for _ in range(60):
        sh(serial, "input keyevent 67")
    subprocess.run(["adb", "-s", serial, "shell", "input", "text", task.replace(" ", "%s")],
                   capture_output=True, timeout=25)
    time.sleep(1.0)
    sh(serial, "input keyevent 111")
    time.sleep(1.5)
    ui2 = ensure_main(serial)
    bx, by = ui2.get("btnRun", ui["btnRun"])
    sh(serial, f"input tap {bx} {by}")
    time.sleep(5)
    return bool(hits(OWN) or hits(GW))


def _find(xml: str, label: str, rid: str | None):
    if rid:
        m = re.search(r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                      % (re.escape(PKG), re.escape(rid)), xml)
    else:
        m = (re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml)
             or re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="%s"' % re.escape(label), xml))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def tap_text(serial: str, label: str) -> bool:
    c = _find(dump_xml(serial), label, None)
    if not c:
        return False
    sh(serial, f"input tap {c[0]} {c[1]}")
    time.sleep(2.5)
    return True


def tap_scrolled(serial: str, label: str, rid: str | None = None, tries: int = 9) -> bool:
    """点击可能不在首屏的控件：找不到就上滑再找。

    设置页很长（账号 → 额度 → 模型与 API 配置 → 保存），「保存配置」在屏幕外。
    真机上不滚动直接点，点击会落在别的控件上——而**保存成功 ≠ 弹了选择卡**，
    于是失败会被误读成"选择卡没实现"（实测踩过）。
    """
    for _ in range(tries):
        c = _find(dump_xml(serial), label, rid)
        if c and 150 < c[1] < 2350:
            sh(serial, f"input tap {c[0]} {c[1]}")
            time.sleep(2.5)
            return True
        sh(serial, "input swipe 608 1900 608 800 400")   # 内容上移
        time.sleep(1.2)
    return False


# ---------------------------------------------------------------- 本机假端点
def _text_of(content) -> str:
    if isinstance(content, list):
        return " ".join(p.get("text", "") for p in content if isinstance(p, dict))
    return content if isinstance(content, str) else ""


class _Srv(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port: int, label: str):
        super().__init__(("127.0.0.1", port), _Handler)
        self.label, self.records, self.code = label, [], [200]


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {}
        msgs = body.get("messages") or []
        sys_txt = " ".join(_text_of(m.get("content")) for m in msgs if m.get("role") == "system")
        self.server.records.append({
            "port": self.server.server_address[1], "label": self.server.label,
            "path": self.path, "auth": self.headers.get("Authorization", ""),
            "model": body.get("model", ""), "translate": "结构化转译器" in sys_txt,
        })
        code = self.server.code[0]
        if code != 200:
            self._send(code, {"error": {"message": f"注入故障 {code}"},
                              "message": f"注入故障 {code}"})
            return
        self._send(200, {"id": "local-mock", "object": "chat.completion",
                         "model": body.get("model", "fixture"),
                         "choices": [{"index": 0, "finish_reason": "stop",
                                      "message": {"role": "assistant", "content": REPLY}}],
                         "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}})

    def _send(self, code: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):  # 静音
        pass


OWN = _Srv(OWN_PORT, "自己的配置")
GW = _Srv(GW_PORT, "账号网关")


def reset_servers(code: int = 200) -> None:
    for s in (OWN, GW):
        s.records.clear()
        s.code[0] = code


def hits(srv: _Srv) -> list[dict]:
    """**任务模型请求**：只算 /chat/completions。

    已登录时 App 还会后台轮询账号网关（跨设备指令 RemoteCommand / LanSync），
    那些请求同样落在 sync_server 上——实测把 1 条轮询误读成「缺 Key 时偷跑账号额度」。
    判据必须按路径切开，否则测试自己制造假缺陷。
    """
    return [r for r in srv.records if r["path"].endswith("/chat/completions")]


def aux(srv: _Srv) -> int:
    """非任务请求数（后台轮询等），仅作诊断输出，不参与断言。"""
    return len([r for r in srv.records if not r["path"].endswith("/chat/completions")])


# ---------------------------------------------------------------- 解锁前置
def screen_ready(serial: str) -> tuple[bool, str]:
    """主链路要求设备已解锁；未解锁则退环境码 2。

    锁屏设备上有两重危险：`uiautomator dump` 会超时（`fuzzy_e2e_mock.dump_xml` 走
    `subprocess.run(timeout=25)`，锁屏下直接抛 TimeoutExpired 打断脚本）；更隐蔽的是
    `/data/data`（CE 存储）在设备锁定期间不挂载，`write_prefs` 会静默落空，后续阶段就跑在
    错误前提上——「未登录」本来就是被测状态之一，于是可能产出一份看着合理的「通过」。
    所以这里宁可拒绝运行，也不在锁屏设备上给证据。
    """
    sh(serial, "input keyevent 224")          # KEYCODE_WAKEUP
    time.sleep(1.0)
    wake = sh(serial, "dumpsys power | grep mWakefulness=").strip()
    if "mWakefulness=Awake" not in wake:
        return False, f"屏幕未点亮（{wake[:40]}）"
    dream = sh(serial, "dumpsys window | grep -E 'mDreamingLockscreen|mShowingLockscreen'").strip()
    if "true" in dream:
        return False, f"锁屏未解（{dream[:80]}）"
    focus = sh(serial, "dumpsys window | grep mCurrentFocus").strip()
    if "NotificationShade" in focus:
        return False, f"通知栏遮挡（{focus[:60]}）"
    pkg_dir = PREF.rsplit("/shared_prefs", 1)[0]
    if "MOUNTED" not in su(serial, f"ls {pkg_dir} >/dev/null 2>&1 && echo MOUNTED || echo LOCKED"):
        return False, "CE 存储未挂载（设备仍处于锁定态）"
    return True, ""


# ---------------------------------------------------------------- 前置状态
def base_entries(**over) -> dict:
    e = {
        "base_url": OWN_BASE, "api_key": "", "model": MODEL,
        "sync_server": GW_BASE, "planner_model": MODEL,
        # 零 root 条件必须显式写进 prefs：write_prefs 是整体覆盖（cat > PREF），
        # 不写这一项就等于把它抹掉，Prefs.rootMode() 落回默认 "auto"
        # （Prefs.java:132 getString("root_mode","auto")），run 期间就不在零 root 下。
        "root_mode": "off",
    }
    e.update({k: v for k, v in over.items() if v is not None})
    for k, v in over.items():
        if v is None:
            e.pop(k, None)
    return e


def logged_in() -> dict:
    return {"account_id": ACC_ID, "account_token": ACC_TOKEN}


# ---------------------------------------------------------------- 阶段
def stage_a(serial: str) -> None:
    print("\n== A 首启与登录态 ==")
    # A1 全新安装：无 use_own_key、无 Key、未登录 → 账号通道 → 先登录
    scenario(serial, base_entries())
    tap_run(serial)
    check("A1", "新装无 Key 未登录 → 提示先登录账号",
          wait_text(serial, "使用账号额度需要先登录", 10), texts(serial)[:200])
    check("A1b", "解决卡主按钮是「登录账号」而非配置 Key",
          "登录账号" in texts(serial), texts(serial)[:200])
    check("A1c", "未发任何任务模型请求", not hits(OWN) and not hits(GW),
          f"own={len(hits(OWN))} gw={len(hits(GW))}")

    # A2 已登录、无 Key、**没有** use_own_key（升级用户）→ 必须放行（原缺陷点）
    reset_servers()
    scenario(serial, base_entries(**logged_in()))
    started = tap_run(serial)
    check("A2", "已登录无 Key 且无历史选择 → 任务真启动（原缺陷）", started,
          dump_log(serial)[-400:])
    check("A2b", "请求打到账号网关端点", bool(hits(GW)), f"own={len(hits(OWN))} gw={len(hits(GW))}")
    check("A2c", "未打到自己的配置端点", not hits(OWN), f"own={len(hits(OWN))}")
    check("A2d", "账号通道携带账号令牌（不是空串）",
          any(r["auth"] == f"Bearer {ACC_TOKEN}" for r in hits(GW)),
          str([r["auth"] for r in hits(GW)])[:120])

    # A3 账号通道被用户关掉
    scenario(serial, base_entries(use_gateway=False, **logged_in()))
    tap_run(serial)
    check("A3", "账号通道关闭 → 提示可主动重新启用",
          wait_text(serial, "账号通道已关闭", 10), texts(serial)[:200])

    # A4 令牌失效
    scenario(serial, base_entries(account_invalid=True, **logged_in()))
    tap_run(serial)
    check("A4", "令牌失效 → 提示重新登录（不是配置问题）",
          wait_text(serial, "登录已失效", 10), texts(serial)[:200])


def stage_b(serial: str) -> None:
    print("\n== B 自己的配置 ==")
    reset_servers()
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY))
    started = tap_run(serial)
    check("B1", "显式自带且 Key 齐备 → 任务启动", started, dump_log(serial)[-300:])
    check("B1b", "请求打到自己的端点", bool(hits(OWN)), f"own={len(hits(OWN))} gw={len(hits(GW))}")
    check("B1c", "自带凭据只发给自己的端点", any(r["auth"] == f"Bearer {OWN_KEY}" for r in hits(OWN)),
          str([r["auth"] for r in hits(OWN)])[:120])
    check("B1d", "没有打到账号网关", not hits(GW), f"gw={len(hits(GW))}")

    # B2 显式自带但 Key 空：即使账号看似可用也必须停住（不自动切）
    reset_servers()
    scenario(serial, base_entries(use_own_key=True, api_key="", **logged_in()))
    tap_run(serial)
    check("B2", "显式自带缺 Key → 停住并说清原因",
          wait_text(serial, "还没有填好密钥", 10), texts(serial)[:200])
    check("B2b", "不自动改用账号额度（零任务请求）", not hits(OWN) and not hits(GW),
          f"own={len(hits(OWN))} gw={len(hits(GW))} 后台轮询={aux(GW)}")
    check("B2c", "解决卡提供主动选择入口", "选择使用方式" in texts(serial), texts(serial)[:200])

    # B3 地址不合法
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY, base_url="not-a-url"))
    tap_run(serial)
    check("B3", "自带地址不完整 → 提示检查设置",
          wait_text(serial, "服务地址不完整", 10), texts(serial)[:200])


def stage_c(serial: str) -> None:
    print("\n== C 账号通道 ==")
    # C1 显式账号 + 机器上还留着旧 Key + 未登录 → 旧 Key 不得兜底
    reset_servers()
    scenario(serial, base_entries(use_own_key=False, api_key=OWN_KEY))
    started = tap_run(serial)
    check("C1", "显式账号未登录 → 不得用旧 Key 兜底启动", not started, dump_log(serial)[-300:])
    check("C1b", "提示先登录", wait_text(serial, "使用账号额度需要先登录", 10), texts(serial)[:200])
    check("C1c", "零任务请求（既没打网关也没打自带端点）",
          not hits(OWN) and not hits(GW), f"own={len(hits(OWN))} gw={len(hits(GW))}")

    # C2 显式账号 + 已登录 → 只走网关，且带账号令牌
    reset_servers()
    scenario(serial, base_entries(use_own_key=False, api_key=OWN_KEY, **logged_in()))
    started = tap_run(serial)
    check("C2", "显式账号已登录 → 任务启动", started, dump_log(serial)[-300:])
    check("C2b", "只用账号网关端点", bool(hits(GW)) and not hits(OWN),
          f"own={len(hits(OWN))} gw={len(hits(GW))}")
    check("C2c", "带的是账号令牌而非本机旧 Key",
          any(r["auth"] == f"Bearer {ACC_TOKEN}" for r in hits(GW))
          and not any(OWN_KEY in r["auth"] for r in GW.records),
          str([r["auth"] for r in hits(GW)])[:120])


def stage_d(serial: str) -> None:
    print("\n== D 模型缺失 ==")
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY, model=""))
    tap_run(serial)
    check("D1", "模型名为空 → 提示配置不完整",
          wait_text(serial, "模型配置不完整", 10), texts(serial)[:200])


def hist_lines(serial: str) -> int:
    """当前历史行数。用来把「本次场景新写的那条」和上一轮遗留记录分开。

    task_history.jsonl 是**追加**的，跨运行累积——直接 tail 会把上轮的失败记录读进来，
    于是「本次文案里没有『兑换码』」这种断言会被历史污染成假失败（实测踩过）。
    """
    out = sh(serial, f"wc -l < {HISTORY} 2>/dev/null").strip().split()
    try:
        return int(out[0])
    except (IndexError, ValueError):
        return 0


def _fail_text(serial: str, since: int = 0, timeout: int = 45) -> str:
    """读终态文案：**优先任务历史文件，回退 logcat**。

    失败结果走的是悬浮卡片（OverlayController.showFail），不在 uiautomator 能 dump 的窗口树里。
    两个可用证据源，取稳的那个：
      · `task_history.jsonl` 在**外部存储**——零 root 可读、每次终态都落盘、不受日志缓冲滚动影响；
      · logcat 的「任务失败——…」——OPPO 真机噪声大，实测偶发读不到（E3 就空过一次）。
    这条边界要在报告里写明：脚本断言的是**文案内容**，不是卡片的视觉呈现。
    """
    end = time.time() + timeout
    hist, log = "", ""
    while time.time() < end:
        hist = sh(serial, f"tail -n +{since + 1} {HISTORY} 2>/dev/null").strip()
        if '"result":"失败"' in hist or '"result": "失败"' in hist:
            return hist
        log = sh(serial, "logcat -d -s AgentEngine:I -v brief | tail -80")
        if "任务失败" in log:
            return log
        time.sleep(3)
    return hist or log


def shot(serial: str, name: str) -> None:
    """留一张用户视角截图（悬浮卡片不在窗口树里，截图是唯一视觉证据）。"""
    p = ROOT / ".check-tmp" / "device" / f"{name}.png"
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"],
                       stdout=f, timeout=60)
    print(f"    · 截图 → {p.relative_to(ROOT)}")


def stage_e(serial: str) -> None:
    print("\n== E 故障归因与不跨通道 ==")
    # E1 自带端点 402：必须归到自己的服务账号，不能引导去应用内充值
    reset_servers(402)
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY))
    n0 = hist_lines(serial)
    tap_run(serial)
    t = _fail_text(serial, n0)
    shot(serial, "E1-自带402")
    check("E1", "自带欠费 → 归因到自己的服务账号", "自己的模型服务额度不足" in t, t[-300:])
    check("E1b", "不引导用应用内兑换码充值", "兑换码" not in t, t[-300:])
    check("E1c", "只打自己的端点（未溢出到账号）", bool(hits(OWN)) and not hits(GW),
          f"own={len(hits(OWN))} gw={len(hits(GW))}")
    check("E1d", "终态文案不泄漏测试凭据", OWN_KEY not in t and ACC_TOKEN not in t, t[-200:])

    # E2 自带 401
    reset_servers(401)
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY))
    n0 = hist_lines(serial)
    tap_run(serial)
    t = _fail_text(serial, n0)
    shot(serial, "E2-自带401")
    check("E2", "自带鉴权失败 → 给出终态归因",
          '"result":"失败"' in t or "任务失败" in t, t[-300:])
    check("E2b", "不跨到账号网关", not hits(GW), f"gw={len(hits(GW))}")
    check("E2c", "不泄漏测试凭据", OWN_KEY not in t and ACC_TOKEN not in t, t[-200:])

    # E3 账号端点 402
    reset_servers(402)
    scenario(serial, base_entries(use_own_key=False, **logged_in()))
    n0 = hist_lines(serial)
    tap_run(serial)
    t = _fail_text(serial, n0)
    shot(serial, "E3-账号402")
    check("E3x", "账号通道任务确实发出请求（与文案断言区分开）", bool(hits(GW)),
          f"gw={len(hits(GW))}")
    check("E3", "账号欠费 → 归因到账号额度", "账号额度" in t or "额度不足" in t, t[-300:])
    check("E3b", "只打网关，不溢出到自带端点", not hits(OWN), f"own={len(hits(OWN))}")

    # E4 服务端 500：网络/服务故障不得换主体
    reset_servers(500)
    scenario(serial, base_entries(use_own_key=True, api_key=OWN_KEY, **logged_in()))
    tap_run(serial)
    time.sleep(8)
    check("E4", "500 不跨付费主体", bool(hits(OWN)) and not hits(GW),
          f"own={len(hits(OWN))} gw={len(hits(GW))}")
    check("E4b", "通道选择未被偷偷改写",
          read_prefs(serial).get("use_own_key") is True,
          str(read_prefs(serial).get("use_own_key")))


def stage_f(serial: str) -> None:
    print("\n== F 设置页保存后的显式选择卡 ==")
    # 起点：已登录、显式账号主体（保存配置不应把主体改成自带）
    scenario(serial, base_entries(use_own_key=False, api_key="", **logged_in()))
    check("F0", "前置：当前主体为账号额度",
          read_prefs(serial).get("use_own_key") is False,
          str(read_prefs(serial).get("use_own_key")))

    sh(serial, f"am start -n {SETTINGS}")
    time.sleep(3.5)
    # 填入自己的配置并保存：保存动作本身不应同意切换付费主体
    tap_scrolled(serial, "", rid="etBase")
    x, y = _center(serial, "etBase")
    if x:
        sh(serial, f"input tap {x} {y}")
        time.sleep(1)
        sh(serial, "input keyevent 123")
        for _ in range(80):
            sh(serial, "input keyevent 67")
        # 注意：adb input text 里 %s 代表**空格**，不能拿来转义斜杠/冒号（实测把 URL 输成带空格的废串）
        subprocess.run(["adb", "-s", serial, "shell", "input", "text", OWN_BASE],
                       capture_output=True, timeout=25)
        time.sleep(1)
        sh(serial, "input keyevent 111")
        time.sleep(1)
    kx, ky = _center(serial, "etKey")
    if kx:
        sh(serial, f"input tap {kx} {ky}")
        time.sleep(1)
        sh(serial, "input keyevent 123")
        for _ in range(80):
            sh(serial, "input keyevent 67")
        subprocess.run(["adb", "-s", serial, "shell", "input", "text", OWN_KEY],
                       capture_output=True, timeout=25)
        time.sleep(1)
        sh(serial, "input keyevent 111")
        time.sleep(1)
    saved = tap_scrolled(serial, "保存配置", rid="btnSave")
    check("F1", "设置页保存 → 弹出显式额度选择卡",
          saved and wait_text(serial, "选择额度来源", 12), texts(serial)[:240])
    check("F1b", "选择卡给出三种明确选项",
          all(s in texts(serial) for s in ("使用账号额度", "使用自己的配置", "暂不切换")),
          texts(serial)[:240])
    check("F1c", "说明云端处理（不谎称只在本机）",
          "并非只在本机处理" in texts(serial), texts(serial)[:240])

    # 「暂不切换」= 保持原主体
    tap_text(serial, "暂不切换")
    check("F2", "点「暂不切换」→ 主体仍是账号额度（保存 ≠ 同意换通道）",
          read_prefs(serial).get("use_own_key") is False,
          str(read_prefs(serial).get("use_own_key")))

    # 主动选自带
    sh(serial, f"am start -n {SETTINGS}")
    time.sleep(3)
    tap_scrolled(serial, "保存配置", rid="btnSave")
    wait_text(serial, "选择额度来源", 12)
    tap_text(serial, "使用自己的配置")
    p = read_prefs(serial)
    check("F3", "主动点「使用自己的配置」→ 主体才切换", p.get("use_own_key") is True, str(p.get("use_own_key")))

    # 主动选账号：同时重新启用被关掉的网关标志
    su(serial, f"rm -f {CRED}")
    write_prefs(serial, base_entries(use_own_key=True, api_key="", use_gateway=False, **logged_in()))
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    sh(serial, f"am start -n {SETTINGS}")
    time.sleep(3)
    tap_scrolled(serial, "保存配置", rid="btnSave")
    wait_text(serial, "选择额度来源", 12)
    tap_text(serial, "使用账号额度")
    p = read_prefs(serial)
    check("F4", "主动点「使用账号额度」→ 切换并重新启用账号通道",
          p.get("use_own_key") is False and p.get("use_gateway") is True,
          f"own={p.get('use_own_key')} gateway={p.get('use_gateway')}")


def _center(serial: str, rid: str):
    xml = dump_xml(serial)
    m = re.search(r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                  % (re.escape(PKG), re.escape(rid)), xml)
    if not m:
        return (0, 0)
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


# ---------------------------------------------------------------- 主流程
def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else None
    if not serial:
        r = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=20)
        devs = [l.split()[0] for l in r.stdout.splitlines()[1:] if "\tdevice" in l]
        if not devs:
            print("环境不可用：未连接设备", file=sys.stderr)
            return 2
        serial = devs[0]
    stages = [a for a in sys.argv[1:] if re.fullmatch(r"[A-Fa-f]", a)]
    want = {s.upper() for s in stages} or set("ABCDEF")

    print("=" * 78)
    print(f"凭据路由真机回归 · 设备 {serial} · 阶段 {''.join(sorted(want))}")
    print("=" * 78)
    print(f"  自己的端点 {OWN_BASE}   账号网关 {GW_BASE}（均为本机假端点，不出网）")

    ok, why = screen_ready(serial)
    if not ok:
        print(f"环境不可用：{why}", file=sys.stderr)
        print("解锁手机后重跑。锁屏下 uiautomator dump 会超时打断脚本；/data/data（CE 存储）"
              "在设备锁定期间不挂载，写前置状态会静默落空，后续阶段将跑在错误前提上。",
              file=sys.stderr)
        return 2

    threading.Thread(target=OWN.serve_forever, daemon=True).start()
    threading.Thread(target=GW.serve_forever, daemon=True).start()
    for port in (OWN_PORT, GW_PORT):
        subprocess.run(["adb", "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"],
                       capture_output=True, timeout=20)
    back = subprocess.run(["adb", "-s", serial, "reverse", "--list"],
                          capture_output=True, text=True, timeout=20).stdout
    if f"tcp:{OWN_PORT}" not in back or f"tcp:{GW_PORT}" not in back:
        print(f"环境不可用：adb reverse 未建立\n{back}", file=sys.stderr)
        return 2

    backup_once(serial)
    # 零 root 条件自检：write_prefs 是整体覆盖（cat > PREF），任何一处 entries 漏写 root_mode
    # 都会让它落回默认 "auto"（Prefs.java:132 getString("root_mode","auto")），整套「零 root
    # 主链路」的验收就跑了在别的前提上。跑阶段前先落一次前置并读回确认。
    sh(serial, f"am force-stop {PKG}")
    time.sleep(1)
    write_prefs(serial, base_entries())
    _mode = read_prefs(serial).get("root_mode")
    check("P0", "零 root 条件：run 期间设备上 root_mode=off", _mode == "off",
          f"root_mode={_mode!r}（write_prefs 漏写该项会落回默认 auto）")
    try:
        if "A" in want:
            stage_a(serial)
        if "B" in want:
            stage_b(serial)
        if "C" in want:
            stage_c(serial)
        if "D" in want:
            stage_d(serial)
        if "E" in want:
            stage_e(serial)
        if "F" in want:
            stage_f(serial)
    finally:
        sh(serial, f"am force-stop {PKG}")
        if "G" in want or True:
            restore(serial)

    p = sum(1 for _, r, _ in RESULTS if r == "PASS")
    f = sum(1 for _, r, _ in RESULTS if r == "FAIL")
    print(f"\n凭据路由真机回归：{p} 通过 / {f} 失败")
    if f:
        print("失败项：")
        for cid, r, note in RESULTS:
            if r == "FAIL":
                print(f"  · [{cid}] {note[:160]}")
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main())
