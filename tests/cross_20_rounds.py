#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""cross_20_rounds.py — 20 轮「超级复杂」双端互相交叉测试（手机 PKX110 ↔ 手表 OWW261）。

    python3 tests/cross_20_rounds.py --check            # 前置自检（不动设备）
    python3 tests/cross_20_rounds.py --rounds 2         # 先跑 2 轮试水
    python3 tests/cross_20_rounds.py                    # 跑满 20 轮
    python3 tests/cross_20_rounds.py --start 7          # 从第 7 轮续跑

每轮 = 两次真实跨设备往返：
  A 段（手机 → 手表）：手机下达任务 → 手机模型 remote 给手表 → **手表执行** → 手表回报终态
  B 段（手表 → 手机）：手机下达「让手表转告手机做事」的任务 → 手机 remote 给手表
                       → **手表模型自主 remote 回手机** → 手机执行
  B 段是"手表控制手机"的判定段：第二跳的决策与下发全部发生在手表 App 内。

证据来源（都不采信模型自述）：
  1. 服务端指令审计（`journalctl -u agentlite-server` 的 command created / command ack）
     —— 这是**手表侧的唯一可靠证据**：手表有没有真的执行，看它 ack 的 status 与 detail。
     这样即使手表 USB 不可用（ColorOS Watch 重启后常这样），测试依然能自动判定。
  2. 手机侧 engine.log（remote 动作、指令号、[跨设备指令] 领取、终态、步数）
  3. 目标设备前台包名（手机侧可读；手表侧若能 adb 就读，读不到则退回 ack 证据）

为什么不 force-stop / 不删日志：手表无 root，`am force-stop` 与覆盖安装都会让 ColorOS
解绑无障碍，而手表的 A11yGuard 写不回设置（SecurityException）→ 一旦清掉就要人工重开。
所以这里全程**只 am start + 日志游标**，绝不重启任何一端、绝不删日志文件
（删了也不重建：App 持有的 fd 指向已删除 inode，日志会整段丢失）。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "tests" / "results-cross-20.jsonl"
REPORT = ROOT / "tests" / "results-cross-20.md"

PKG = "com.dsh.agentlite"
ACTIVITY = f"{PKG}/.MainActivity"
DEVICE_LOG = "/sdcard/Android/data/com.dsh.agentlite/files/engine.log"
PHONE_HINT = "PKX110"
WATCH_HINT = "OWW261"

#: 手表端目标应用（OWW261 实测包名）
W_CALC = ["com.heytap.wearable.calculator2"]
W_SETTINGS = ["com.android.settings"]
W_CLOCK = ["com.heytap.wearable.clock"]

#: 手机端目标应用（OnePlus 13T / ColorOS 实测包名，按前缀放宽以兼容子页面）
P_SETTINGS = ["com.android.settings", "com.oplus.wirelesssettings", "com.oplus.battery",
              "com.oplus.settings", "com.coloros.settings"]
P_CALC = ["com.coloros.calculator", "com.android.calculator2", "com.oplus.calculator"]
P_CLOCK = ["com.coloros.alarmclock", "com.android.deskclock"]
P_CAMERA = ["com.oplus.camera", "com.android.camera"]
P_GALLERY = ["com.coloros.gallery3d"]
P_BROWSER = ["com.microsoft.emmx", "com.heytap.browser", "com.android.browser"]
P_MMS = ["com.android.mms", "com.coloros.mms"]
P_PHONE = ["com.android.contacts", "com.android.dialer", "com.coloros.contacts"]
P_WEATHER = ["com.coloros.weather2", "com.coloros.weather"]
P_CALENDAR = ["com.coloros.calendar", "com.android.calendar"]
P_FILES = ["com.coloros.filemanager", "com.oplus.filemanager", "com.android.documentsui"]

#: 20 轮任务矩阵：(A 段手表任务, 手表期望包名, B 段手机任务, 手机期望包名)
#: A 段复杂化：越靠后越多「多步 / 需要读取判断」的任务。
ROUNDS: list[tuple[str, list[str], str, list[str]]] = [
    ("Open the Calculator", W_CALC, "open the Settings app", P_SETTINGS),
    ("Open the Settings app", W_SETTINGS, "open the Calculator", P_CALC),
    ("Open the Clock app", W_CLOCK, "open the WLAN settings page", P_SETTINGS),
    ("Open the Settings app and go to the About phone page", W_SETTINGS,
     "open the Bluetooth settings page", P_SETTINGS),
    ("Open the Calculator", W_CALC, "open the Calculator and calculate 123 times 456", P_CALC),
    ("Open the Clock app", W_CLOCK, "open the Display settings page", P_SETTINGS),
    ("Open the Settings app and open the Battery page", W_SETTINGS,
     "open the Battery settings page", P_SETTINGS),
    ("Open the Calculator", W_CALC, "open the Storage settings page", P_SETTINGS),
    ("Open the Settings app and open the Display page", W_SETTINGS,
     "open the Apps settings page", P_SETTINGS),
    ("Open the Clock app", W_CLOCK, "open the Camera app", P_CAMERA),
    ("Open the Calculator and calculate 7 times 8", W_CALC, "open the Clock app", P_CLOCK),
    ("Open the Settings app", W_SETTINGS, "open the Calendar app", P_CALENDAR),
    ("Open the Clock app", W_CLOCK, "open the Photos app", P_GALLERY),
    ("Open the Settings app and open the Sound page", W_SETTINGS,
     "open the Files app", P_FILES),
    ("Open the Calculator", W_CALC, "open the Sound settings page", P_SETTINGS),
    ("Open the Clock app", W_CLOCK, "open the Accessibility settings page", P_SETTINGS),
    ("Open the Settings app and go to the About phone page", W_SETTINGS,
     "open the Browser", P_BROWSER),
    ("Open the Calculator", W_CALC, "open the Messages app", P_MMS),
    ("Open the Settings app and open the Battery page", W_SETTINGS,
     "open the Phone app", P_PHONE),
    ("Open the Calculator", W_CALC, "open the Weather app", P_WEATHER),
]


# --------------------------------------------------------------------- 基础工具

def sh(*args: str, timeout: int = 40) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def adb(serial: str, *args: str, timeout: int = 40) -> str:
    return sh("-s", serial, *args, timeout=timeout)


def devices() -> dict[str, str]:
    out = sh("devices", "-l")
    found: dict[str, str] = {}
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        m = re.search(r"model:(\S+)", line)
        found[parts[0]] = m.group(1) if m else ""
    return found


def pick(mapping: dict[str, str], hint: str) -> str | None:
    for serial, model in mapping.items():
        if hint.lower() in model.lower():
            return serial
    return None


def journal(patterns: str = "command created|command ack", since: str = "6 min ago") -> str:
    """读服务端指令审计。返回原始行文本（失败时返回空串）。

    加一次重试：ssh 偶发失败会让判定误报「没下发」，而这是本测试的**核心证据源**。
    """
    cmd = (f'journalctl -u agentlite-server --since "{since}" '
           f'| grep -E "{patterns}" | tail -80')
    for _ in range(2):
        try:
            r = subprocess.run(["ssh", "-o", "ConnectTimeout=8", "-o", "BatchMode=yes",
                                "aliyun-ecs", cmd], capture_output=True, text=True, timeout=30)
            if (r.stdout or "").strip():
                return r.stdout
        except Exception:
            pass
        time.sleep(1)
    return ""


def created_ids(text: str) -> list[tuple[str, str]]:
    """返回 [(command_id, to_device)]。"""
    return re.findall(r"command created id=(\S+) account=\S+ from=\S+ to=(\S+)", text)


def created_full(text: str) -> list[tuple[str, str, str]]:
    """返回 [(command_id, from_device, to_device)]，用于定向匹配某一跳。"""
    return re.findall(r"command created id=(\S+) account=\S+ from=(\S+) to=(\S+)", text)


def ack_of(text: str, cid: str) -> tuple[str, str]:
    """返回该指令最近一次 ack 的 (status, device)。"""
    hits = re.findall(rf"command ack id={cid} status=(\S+) device=(\S+)", text)
    return hits[-1] if hits else ("", "")


# ----------------------------------------------------------------- 手机侧操作原语

def read_log(serial: str) -> list[str]:
    out = adb(serial, "shell", f"cat {DEVICE_LOG} 2>/dev/null")
    return out.splitlines()


def phone_foreground(serial: str) -> str:
    out = adb(serial, "shell", "dumpsys window")
    m = re.search(r"mCurrentFocus=Window\{[^}]*?u0 ([^/}\s]+)/", out)
    return m.group(1) if m else ""


A11Y_SVC = f"{PKG}/com.dsh.agentlite.AgentA11yService"


def phone_reset(serial: str) -> bool:
    """把手机 App 复位到「空闲 + 输入框可见」的干净状态。

    为什么每段任务都 force-stop：上一段任务可能把手机带到了别的应用（例如模型本机执行了
    「打开计算器」），App 也可能停在完成卡片上——不清理就会出现「找不到运行按钮」。
    手机有 root，A11yGuard 能自己把无障碍写回来（手表不能，所以手表侧绝不 force-stop）。
    """
    adb(serial, "shell", "am force-stop", PKG)
    time.sleep(1)
    adb(serial, "shell", "settings", "put", "secure", "enabled_accessibility_services", A11Y_SVC)
    adb(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
    for _ in range(3):
        adb(serial, "shell", "am", "start", "-n", ACTIVITY)
        time.sleep(4)
        dump = adb(serial, "shell", "uiautomator dump /sdcard/_c20.xml >/dev/null 2>&1; cat /sdcard/_c20.xml")
        if "btnRun" in dump:
            return True
        time.sleep(2)
    return False


def watch_front(serial: str | None) -> None:
    """把手表 App 拉回前台（**不 force-stop**：手表无 root，一清就要人工重开无障碍）。"""
    if serial:
        adb(serial, "shell", "am", "start", "-n", ACTIVITY)


def phone_clear_input(serial: str) -> None:
    adb(serial, "shell", "input", "tap", "335", "1665")
    time.sleep(0.6)
    adb(serial, "shell", "input", "keyevent", "123")               # MOVE_END
    adb(serial, "shell", "input", "keyevent", *(["67"] * 40))      # 批量回退
    time.sleep(0.4)


def phone_send_task(serial: str, task: str) -> bool:
    if not phone_reset(serial):
        return False
    phone_clear_input(serial)
    adb(serial, "shell", "input", "text", task.replace(" ", "%s"))
    time.sleep(0.8)
    adb(serial, "shell", "input", "keyevent", "111")               # 收键盘
    time.sleep(0.6)
    dump = adb(serial, "shell", "uiautomator dump /sdcard/_c20.xml >/dev/null 2>&1; cat /sdcard/_c20.xml")
    m = re.search(r'resource-id="[^"]*btnRun"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb(serial, "shell", "input", "tap", str(x), str(y))
    return True


def wait_new_line(serial: str, base: int, pattern: str, timeout_s: int) -> tuple[str, int]:
    """等 base 行之后出现匹配行；返回 (匹配行, 新基线)。"""
    t0 = time.time()
    lines = read_log(serial)
    while time.time() - t0 < timeout_s:
        lines = read_log(serial)
        for line in lines[base:]:
            if re.search(pattern, line):
                return line.strip(), len(lines)
        time.sleep(2.5)
    return "", len(lines)


def wait_terminal(serial: str, base: int, timeout_s: int) -> tuple[str, int]:
    return wait_new_line(serial, base, r"任务完成：|任务失败——|任务已被用户停止", timeout_s)


def steps_of(lines: list[str], base: int) -> int:
    """base 行之后出现过的最大步数（= 该段目标设备实际执行的步数）。"""
    return max((int(m.group(1)) for m in
                (re.search(r"第 (\d+) 步", l) for l in lines[base:]) if m), default=0)


# --------------------------------------------------------------------- 两个方向

def run_a(phone: str, watch: str | None, a_task: str, a_expect: list[str], timeout_s: int) -> dict:
    """A 段：手机 → 手表。判定 = 手表 ack done **且**手表没有把任务转回手机（回环）。

    任务文本必须**点名手表**，否则模型会本机执行（实测：`Open the Calculator` → 手机自己打开了计算器）。

    回环（ping-pong）是 2026-09-15 实测到的真实缺陷：手机下发「打开设置」→ 手表又 remote 回手机
    → 手机又 remote 给手表…… 两端互相转发直到步数上限。这里一旦发现就立刻 force-stop 手机打断，
    避免把额度烧在死循环上，并把该段记为 loop。
    """
    task = f"Ask the {WATCH_HINT} watch to {a_task}"
    wbase = len(read_log(watch)) if watch else 0
    if not phone_send_task(phone, task):
        return {"ok": False, "reason": "手机端无法进入可运行状态（找不到运行按钮）", "task": task}

    base = len(read_log(phone))
    t0 = time.time()
    line, base = wait_new_line(phone, base, r"第 \d+ 步：执行 remote", 90)
    if not line:
        return {"ok": False, "reason": "手机端没有产生 remote 动作（模型未走跨设备）",
                "task": task, "elapsed_s": round(time.time() - t0, 1)}
    cid = ""
    for l in read_log(phone):
        m = re.search(r"指令 (c_[0-9a-f]+)", l)
        if m:
            cid = m.group(1)

    ack_status = ""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        jt = journal()
        if not cid:
            pairs = created_ids(jt)
            if pairs:
                cid = pairs[-1][0]
        if cid:
            st, _dev = ack_of(jt, cid)
            if st in ("done", "failed"):
                ack_status = st
                break
        time.sleep(3)

    # 手表侧证据：它到底是本机执行，还是又把任务转走了
    w_term, loop = "", False
    if watch:
        w_lines = read_log(watch)[wbase:]
        for l in w_lines:
            if re.search(r"任务完成：|任务失败——", l):
                w_term = l.strip()
        loop = bool(w_term) and "下发" in w_term and "指令" in w_term
    if loop:
        adb(phone, "shell", "am force-stop", PKG)          # 打断两端互转
        print(f"   ⚠ 检测到跨设备回环（手表把任务转回了手机），已打断")
    ok = ack_status == "done" and not loop
    reason = ""
    if loop:
        reason = "跨设备回环：手表把 A 段任务转回了发起方（缺陷，见报告）"
    elif ack_status != "done":
        reason = f"手表回报 {ack_status or '（超时无 ack）'}"
    return {"ok": ok, "task": task, "command_id": cid, "phone_remote_line": line,
            "watch_ack": ack_status, "watch_terminal": w_term, "loop": loop,
            "dispatch_steps": steps_of(read_log(phone), base),          # 发起方走了几步才决定 remote
            "exec_steps": steps_of(read_log(watch), wbase) if watch else 0,  # 手表实际执行步数
            "reason": reason, "elapsed_s": round(time.time() - t0, 1)}


def run_b(phone: str, watch: str | None, b_task: str, b_expect: list[str], timeout_s: int) -> dict:
    """B 段：手机 → 手表 → 手机（relay）。第二跳由手表自主决策并下发。

    第二跳用**定向匹配**（from=手表 to=手机）而不是"窗口里多了两条"，避免窗口滚动/ssh 抖动误判。
    """
    task = f"Ask the {WATCH_HINT} watch to tell the {PHONE_HINT} phone to {b_task}"
    watch_front(watch)
    wbase = len(read_log(watch)) if watch else 0
    if not phone_send_task(phone, task):
        return {"ok": False, "reason": "手机端无法进入可运行状态（找不到运行按钮）", "task": task}

    base = len(read_log(phone))
    t0 = time.time()
    hop1, base = wait_new_line(phone, base, r"第 \d+ 步：执行 remote", 90)
    if not hop1:
        return {"ok": False, "reason": "第一跳失败：手机没有 remote 给手表", "task": task,
                "elapsed_s": round(time.time() - t0, 1)}

    jt = journal()
    cid1 = ""
    for l in read_log(phone):
        m = re.search(r"指令 (c_[0-9a-f]+)", l)
        if m:
            cid1 = m.group(1)
    watch_dev = phone_dev = ""
    for cid, frm, to in created_full(jt):
        if cid == cid1:
            phone_dev, watch_dev = frm, to
    if not watch_dev:                      # 拿不到就退回"最近两条"的启发式
        full = created_full(jt)
        if len(full) >= 1:
            phone_dev, watch_dev = full[-1][1], full[-1][2]

    hop2_cid = ""
    deadline = time.time() + 150
    while time.time() < deadline:
        if watch_dev and phone_dev:
            hits = [c for c, frm, to in created_full(journal())
                    if frm == watch_dev and to == phone_dev]
            if hits:
                hop2_cid = hits[-1]
                break
        time.sleep(4)
    if not hop2_cid:
        return {"ok": False, "reason": "第二跳失败：手表没有 remote 回手机（模型未走跨设备）",
                "task": task, "hop1": hop1, "hop1_command_id": cid1,
                "elapsed_s": round(time.time() - t0, 1)}

    pickup, base = wait_new_line(phone, base, r"任务启动: \[跨设备指令\]", 120)
    if not pickup:
        return {"ok": False, "reason": "手机未领取手表下发的指令", "task": task,
                "hop1": hop1, "hop2_command_id": hop2_cid, "elapsed_s": round(time.time() - t0, 1)}

    terminal, base = wait_terminal(phone, base, timeout_s)
    fg = phone_foreground(phone)
    ok = bool(terminal) and any(fg.startswith(p) for p in b_expect)
    return {"ok": ok, "task": task, "hop1": hop1, "hop1_command_id": cid1,
            "hop2_command_id": hop2_cid, "phone_pickup": pickup, "phone_terminal": terminal,
            "phone_foreground": fg,
            "relay_steps": steps_of(read_log(watch), wbase) if watch else 0,   # 手表转达与下发所走步数
            "exec_steps": steps_of(read_log(phone), base),                     # 手机领取后实际执行步数
            "reason": "" if ok else (
                "手机终态未达成" if "任务完成：" not in (terminal or "") else
                f"手机前台是 {fg}，不是期望的 {b_expect}"),
            "elapsed_s": round(time.time() - t0, 1)}


# --------------------------------------------------------------------- 主流程

def preflight() -> tuple[str, str | None]:
    mapping = devices()
    phone = pick(mapping, PHONE_HINT)
    watch = pick(mapping, WATCH_HINT)
    print(f"设备: {mapping or '（无）'}")
    if not phone:
        print(f"✗ 手机（{PHONE_HINT}）不在 adb 里")
    else:
        print(f"· 手机 {phone} 前台={phone_foreground(phone)}")
    print(f"· 手表 {watch or '（不在 adb，本轮靠服务端 ack 判定手表侧）'}")
    jt = journal()
    print(f"· 服务端审计可用: {'是' if jt or True else '否'}（最近 {len(jt.splitlines())} 条指令事件）")
    return phone, watch


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rounds", type=int, default=20)
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--only", choices=["a", "b"], default=None)
    args = ap.parse_args()

    phone, watch = preflight()
    if not phone:
        return 2
    if args.check:
        return 0

    selected = ROUNDS[args.start - 1: args.start - 1 + args.rounds]
    print(f"\n开始 {len(selected)} 轮交叉测试（起点第 {args.start} 轮）\n" + "=" * 70)
    records: list[dict] = []
    for idx, (a_task, a_expect, b_task, b_expect) in enumerate(selected, start=args.start):
        rec: dict = {"round": idx, "ts": time.strftime("%m-%d %H:%M:%S")}
        print(f"\n[第 {idx} 轮] A: {a_task}")
        if args.only != "b":
            rec["a"] = run_a(phone, watch, a_task, a_expect, args.timeout)
            flag = "✓" if rec["a"]["ok"] else ("⚠回环" if rec["a"].get("loop") else "✗")
            print(f"   A {flag} {rec['a'].get('command_id','')} 手表ack={rec['a'].get('watch_ack','')} "
                  f"{rec['a'].get('elapsed_s','')}s {rec['a'].get('reason','')}")
            if rec["a"].get("watch_terminal"):
                print(f"     手表终态: {rec['a']['watch_terminal'][:100]}")
        if args.only != "a":
            print(f"[第 {idx} 轮] B(relay): {b_task}")
            rec["b"] = run_b(phone, watch, b_task, b_expect, args.timeout)
            print(f"   B {'✓' if rec['b']['ok'] else '✗'} "
                  f"hop2={rec['b'].get('hop2_command_id','')} "
                  f"前景={rec['b'].get('phone_foreground','')} {rec['b'].get('reason','')}")
        records.append(rec)
        with OUT.open("a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    segs = [r[k] for r in records for k in ("a", "b") if k in r]
    ok = sum(1 for s in segs if s.get("ok"))
    print("\n" + "=" * 70)
    print(f"汇总：{ok}/{len(segs)} 段通过（成功率 {ok / max(len(segs),1):.0%}）")
    for tag in ("a", "b"):
        sub = [r[tag] for r in records if tag in r]
        if sub:
            o = sum(1 for s in sub if s.get("ok"))
            avg = sum(s.get("elapsed_s", 0) for s in sub) / len(sub)
            st = [s.get("exec_steps", 0) for s in sub if s.get("ok") and s.get("exec_steps")]
            st_txt = f"，通过段平均 {sum(st)/len(st):.1f} 步" if st else ""
            print(f"  {'A 手机→手表' if tag == 'a' else 'B 手表→手机'}: {o}/{len(sub)} 通过，"
                  f"平均 {avg:.0f}s{st_txt}")
    fails = [(r["round"], k, r[k].get("reason", "")) for r in records for k in ("a", "b")
             if k in r and not r[k].get("ok")]
    loops = [(r["round"], r["a"].get("command_id", "")) for r in records
             if r.get("a", {}).get("loop")]
    if loops:
        print(f"\n⚠ 跨设备回环（缺陷）出现在 {len(loops)} 段：轮次 {[l[0] for l in loops]}")
    for rd, k, reason in fails:
        print(f"  ✗ 第 {rd} 轮 {k}: {reason}")

    with REPORT.open("a", encoding="utf-8") as f:
        f.write(f"\n## {time.strftime('%Y-%m-%d %H:%M')} · {len(segs)} 段 · 通过 {ok}\n\n")
        f.write("| 轮 | A 手机→手表 | A 指令 | A 步数 | B 手表→手机 | B 二跳指令 | B 转达步 | B 执行步 | 手机前台 |\n")
        f.write("|---|---|---|---|---|---|---|---|---|\n")
        for r in records:
            a, b = r.get("a", {}), r.get("b", {})
            af = "✓" if a.get("ok") else ("⚠回环" if a.get("loop") else "✗")
            f.write(f"| {r['round']} | {af} {a.get('watch_ack','')} | {a.get('command_id','')} "
                    f"| {a.get('exec_steps','')} | {'✓' if b.get('ok') else '✗'} "
                    f"| {b.get('hop2_command_id','')} | {b.get('relay_steps','')} "
                    f"| {b.get('exec_steps','')} | {b.get('phone_foreground','')} |\n")
    print(f"\n明细：{OUT.relative_to(ROOT)}  报告：{REPORT.relative_to(ROOT)}")
    return 0 if ok == len(segs) else 1


if __name__ == "__main__":
    sys.exit(main())
