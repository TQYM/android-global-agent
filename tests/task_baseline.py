#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""task_baseline.py — 固定任务集的模型行为基线采集（手册 §16.9 T1）。

    python3 tests/task_baseline.py --selftest      # 不需要设备：验证日志解析器
    python3 tests/task_baseline.py                 # 需要真机：跑固定任务集
    python3 tests/task_baseline.py --only 3        # 只跑前 3 个任务（调试用）

为什么需要（§16.9 T1）：先把「模型判断错 / 感知错 / 执行错 / 复验错 / 网络错」分开，
再决定改什么。没有基线就改提示词，等于闭着眼睛调参。

采集内容（每个任务一行 JSON，追加写入 tests/results-baseline.jsonl）：
  任务文本 · 动作序列 · 步数 · 耗时 · 终态 · 完成证据 · 前台包名 · 商店误跳 · 重复动作

设计约束：**不改业务配置、不注入凭据、不用 root**；任务全部是「打开 X」这类
低风险动作（§16.9 T1：固定 20 个低风险任务）。唯一写入的一项是**无障碍绑定**
（`settings put secure enabled_accessibility_services`）——`am force-stop` 会清掉它，
不补回来每条任务都会在「感知不到节点」下空跑，把环境故障记成模型失败（2026-09-15 实测）。
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
OUT = ROOT / "tests" / "results-baseline.jsonl"
DEVICE_LOG = "/sdcard/Android/data/com.dsh.agentlite/files/engine.log"
PKG = "com.dsh.agentlite"
ACTIVITY = f"{PKG}/.MainActivity"
A11Y_SVC = f"{PKG}/com.dsh.agentlite.AgentA11yService"

#: 固定任务集（英文，因为 `adb shell input text` 不支持中文）。
#: 全部是「打开某个应用 / 某个设置页」——低风险、无副作用、结果可肉眼核对。
TASKS = [
    "Open the Calculator",
    "Open the Settings app",
    "Open the WLAN settings page",
    "Open the Bluetooth settings page",
    "Open the Display settings page",
    "Open the Sound settings page",
    "Open the Battery settings page",
    "Open the Storage settings page",
    "Open the Apps settings page",
    "Open the Photos app",
    "Open the Browser",
    "Open the Messages app",
    "Open the Phone app",
    "Open the Weather app",
    "Open the Clock app",
    "Open the Camera app",
    "Open the Calendar app",
    "Open the Files app",
    "Open the About phone settings page",
    "Open the Accessibility settings page",
]

#: 应用商店包名特征（误跳判定用）
STORE_MARKERS = ("vending", "appmarket", "qqdownloader", "wandoujia", "market")

STEP_RE = re.compile(r"第 (\d+) 步：执行 (.+?)$")
DONE_RE = re.compile(r"任务完成：(.+)$")
FAIL_RE = re.compile(r"任务失败——(.+)$")
STOP_RE = re.compile(r"任务已被用户停止|任务已停止")


def parse_log(text: str) -> dict:
    """把一次任务的 engine.log 解析成结构化结果（纯函数，可自测）。"""
    steps: list[dict] = []
    done = fail = stopped = None
    for line in text.splitlines():
        line = line.strip()
        m = STEP_RE.search(line)
        if m:
            steps.append({"step": int(m.group(1)), "action": m.group(2).strip()})
            continue
        m = DONE_RE.search(line)
        if m:
            done = m.group(1).strip()
            continue
        m = FAIL_RE.search(line)
        if m:
            fail = m.group(1).strip()
            continue
        if STOP_RE.search(line):
            stopped = True

    actions = [s["action"] for s in steps]
    # 重复动作：连续 3 次及以上完全相同
    repeat_run = 0
    max_repeat = 0
    prev = None
    for a in actions:
        repeat_run = repeat_run + 1 if a == prev else 1
        max_repeat = max(max_repeat, repeat_run)
        prev = a
    store_hit = any(any(mk in a.lower() for mk in STORE_MARKERS) for a in actions)

    if done:
        status = "done"
    elif fail:
        status = "fail"
    elif stopped:
        status = "stopped"
    else:
        status = "timeout"
    return {
        "status": status,
        "summary": done or fail or "",
        "steps": max((s["step"] for s in steps), default=0),
        "actions": actions,
        "max_repeat": max_repeat,
        "store_hit": store_hit,
    }


# ----------------------------------------------------------------- 真机驱动


def adb(*args: str, timeout: int = 30) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def require_device() -> str:
    out = adb("devices")
    lines = [l for l in out.splitlines()[1:] if l.strip().endswith("device")]
    if not lines:
        print("✗ 没有已授权的设备（adb devices 为空）。先连上真机再跑基线。")
        sys.exit(2)
    return lines[0].split()[0]


def a11y_enabled(serial: str) -> bool:
    cur = adb("-s", serial, "shell", "settings get secure enabled_accessibility_services")
    return "com.dsh.agentlite" in cur


def ensure_a11y(serial: str) -> bool:
    """保证无障碍服务已绑定。返回 True 表示本来就好、无需修复。

    为什么必须做（手册 §9 坑表 + 2026-09-15 实测）：`am force-stop` 会让 ColorOS/MagicOS
    清空 `enabled_accessibility_services`，之后每条任务都在「感知不到节点」的状态下跑，
    表现为假性 timeout（旧批次 20 条里有 2 条死于此），会把环境故障误记成模型失败。
    `settings put secure` 是标准 adb 做法，不需要 root。
    """
    if a11y_enabled(serial):
        return True
    adb("-s", serial, "shell", "settings put secure enabled_accessibility_services", A11Y_SVC)
    adb("-s", serial, "shell", "settings put secure accessibility_enabled", "1")
    time.sleep(2)
    return False


def foreground(serial: str) -> str:
    out = adb("-s", serial, "shell", "dumpsys activity activities")
    for line in out.splitlines():
        if "topResumedActivity" in line or "ResumedActivity" in line:
            m = re.search(r"u0 ([^ }]+)", line)
            if m:
                return m.group(1)
    return ""


def classify(res: dict) -> str:
    """把失败归因到「环境 / 通道 / 模型决策 / 未收终态」，供 T1 区分问题来源。"""
    if res["status"] == "done":
        return "ok"
    if res["status"] == "stopped":
        return "user_stop"
    if res["status"] == "env_a11y":
        return "env:a11y"
    summary = res.get("summary", "")
    if "模型调用失败" in summary or "通道" in summary or "额度" in summary:
        return "channel"
    if res["status"] == "timeout":
        return "no_action" if res.get("steps", 0) == 0 else "no_terminal"
    return "model_or_env"


def run_task(serial: str, task: str, timeout_s: int = 90, batch: str = "") -> dict:
    adb("-s", serial, "shell", f"rm -f {DEVICE_LOG}")
    adb("-s", serial, "shell", "am force-stop", PKG)
    healthy = ensure_a11y(serial)          # force-stop 会清掉绑定，每条任务后必须补
    if not a11y_enabled(serial):
        return {"status": "env_a11y", "summary": "无障碍服务无法绑定（环境故障，非模型问题）",
                "steps": 0, "actions": [], "max_repeat": 0, "store_hit": False,
                "task": task, "elapsed_s": 0.0, "foreground": "", "batch": batch,
                "a11y_recovered": not healthy}
    adb("-s", serial, "shell", "input", "keyevent", "3")   # 回桌面
    time.sleep(1)
    adb("-s", serial, "shell", "am start", "-n", ACTIVITY)
    time.sleep(3)

    # 输入任务（空格用 %s 转义，符合 `input text` 约定）
    adb("-s", serial, "shell", "input", "tap", "335", "1665")
    time.sleep(0.8)
    adb("-s", serial, "shell", "input", "text", task.replace(" ", "%s"))
    time.sleep(0.8)
    adb("-s", serial, "shell", "input", "keyevent", "111")   # 收起键盘
    time.sleep(0.8)

    # 找运行按钮（键盘收起后位置可能变）
    dump = adb("-s", serial, "shell", "uiautomator dump /sdcard/_bl.xml && cat /sdcard/_bl.xml")
    m = re.search(r'resource-id="[^"]*btnRun"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not m:
        return {"status": "error", "summary": "找不到运行按钮", "steps": 0, "actions": [],
                "task": task, "batch": batch, "a11y_recovered": not healthy}

    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2

    t0 = time.time()
    adb("-s", serial, "shell", "input", "tap", str(x), str(y))
    log = ""
    while time.time() - t0 < timeout_s:
        time.sleep(2)
        log = adb("-s", serial, "shell", f"cat {DEVICE_LOG}")
        if re.search(r"任务完成：|任务失败——|任务已被用户停止", log):
            break
    elapsed = time.time() - t0

    res = parse_log(log)
    res.update({"task": task, "elapsed_s": round(elapsed, 1), "foreground": foreground(serial),
                "batch": batch, "a11y_recovered": not healthy})
    # 前台应用就是商店 → 也算误跳
    if any(mk in res["foreground"].lower() for mk in STORE_MARKERS):
        res["store_hit"] = True
    if res["status"] == "timeout":
        adb("-s", serial, "shell", "am force-stop", PKG)   # 超时兜底：别让它继续动
    res["cause"] = classify(res)
    return res


def summarize(rows: list[dict]) -> dict:
    n = len(rows)
    if n == 0:
        return {}
    ok = sum(1 for r in rows if r["status"] == "done")
    store = sum(1 for r in rows if r.get("store_hit"))
    rep = sum(1 for r in rows if r.get("max_repeat", 0) >= 3)
    causes: dict[str, int] = {}
    for r in rows:
        c = r.get("cause") or classify(r)
        causes[c] = causes.get(c, 0) + 1
    return {
        "tasks": n,
        "success_rate": round(ok / n, 3),
        "store_hit_rate": round(store / n, 3),
        "repeat_action_rate": round(rep / n, 3),
        "avg_steps": round(sum(r.get("steps", 0) for r in rows) / n, 1),
        "avg_elapsed_s": round(sum(r.get("elapsed_s", 0) for r in rows) / n, 1),
        "a11y_recovered": sum(1 for r in rows if r.get("a11y_recovered")),
        "causes": causes,
    }


# --------------------------------------------------------------------- 入口

SAMPLE_LOG = """09-15 06:00:01 任务启动: Open the Calculator (model=deepseek-flash +vision)
09-15 06:00:03 第 1 步：感知到 0 个节点
09-15 06:00:04 第 1 步：执行 open_api: 计算器 navigate ()
09-15 06:00:06 第 2 步：感知到 34 个节点
09-15 06:00:07 第 2 步：执行 tap#5(直点)
09-15 06:00:09 任务完成：已打开计算器
09-15 06:00:09 overlay: 完成卡片与灵动胶囊已就绪"""


def selftest() -> int:
    r = parse_log(SAMPLE_LOG)
    assert r["status"] == "done", r
    assert r["steps"] == 2, r
    assert r["actions"] == ["open_api: 计算器 navigate ()", "tap#5(直点)"], r
    assert r["max_repeat"] == 1, r
    assert r["store_hit"] is False, r

    store = parse_log(SAMPLE_LOG.replace(
        "第 2 步：执行 tap#5(直点)", "第 2 步：执行 tap#9(直点)\n09-15 06:00:08 第 3 步：执行 app: com.android.vending"))
    assert store["store_hit"] is True, store

    rep = parse_log(SAMPLE_LOG.replace(
        "第 2 步：执行 tap#5(直点)",
        "第 2 步：执行 tap#5(直点)\n09-15 06:00:08 第 3 步：执行 tap#5(直点)\n09-15 06:00:09 第 4 步：执行 tap#5(直点)"))
    assert rep["max_repeat"] == 3, rep

    fail = parse_log(SAMPLE_LOG.replace("任务完成：已打开计算器", "任务失败——两条模型通道都不可用"))
    assert fail["status"] == "fail", fail

    timeout = parse_log("09-15 06:00:01 任务启动: x\n09-15 06:00:03 第 1 步：感知到 0 个节点")
    assert timeout["status"] == "timeout", timeout

    # 归因分类：done 之外要能指认「环境 / 通道 / 模型」三类
    assert classify({"status": "done"}) == "ok"
    assert classify({"status": "env_a11y"}) == "env:a11y"
    assert classify({"status": "fail", "summary": "模型调用失败——请检查网络与 API 配置后重试"}) == "channel"
    assert classify({"status": "timeout", "steps": 0}) == "no_action"
    assert classify({"status": "timeout", "steps": 4}) == "no_terminal"
    assert classify({"status": "fail", "summary": "无法打开目标应用"}) == "model_or_env"

    s = summarize([
        {"status": "done", "steps": 2, "elapsed_s": 8.0, "a11y_recovered": True},
        {"status": "env_a11y", "steps": 0, "elapsed_s": 0.0, "cause": "env:a11y"},
    ])
    assert s["success_rate"] == 0.5 and s["a11y_recovered"] == 1, s
    assert s["causes"] == {"ok": 1, "env:a11y": 1}, s

    print("✓ 解析器自测通过（done/fail/timeout/store_hit/repeat 五类判定 + 失败归因）")
    print(f"任务集共 {len(TASKS)} 个（手册 §16.9 T1 要求 20 个低风险任务）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="不需要设备，只验证解析器")
    ap.add_argument("--only", type=int, default=0, help="只跑前 N 个任务")
    ap.add_argument("--timeout", type=int, default=90, help="单任务超时秒数")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    serial = require_device()
    if not ensure_a11y(serial):
        print("· 无障碍绑定缺失，已用 adb 重新绑定（force-stop 会清掉它，属已知环境坑）")
    batch = time.strftime("%m-%d %H:%M")
    tasks = TASKS[: args.only] if args.only else TASKS
    print(f"设备 {serial} · 批次 {batch} · 任务数 {len(tasks)} · 结果写入 {OUT.relative_to(ROOT)}")
    rows = []
    for i, task in enumerate(tasks, 1):
        print(f"[{i}/{len(tasks)}] {task} … ", end="", flush=True)
        r = run_task(serial, task, args.timeout, batch)
        rows.append(r)
        print(f"{r['status']}({r.get('cause','?')}) 步数={r.get('steps')} 耗时={r.get('elapsed_s')}s "
              f"前台={r.get('foreground','')} 商店={r.get('store_hit')}")
        with OUT.open("a", encoding="utf-8") as f:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("\n汇总：", json.dumps(summarize(rows), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
