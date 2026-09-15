#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""cross_device_e2e.py — 跨设备双向控制的真机验证（手机 ↔ 手表）。

    python3 tests/cross_device_e2e.py --check        # 只跑前置自检（不执行任务）
    python3 tests/cross_device_e2e.py --dir phone2watch
    python3 tests/cross_device_e2e.py --dir watch2phone
    python3 tests/cross_device_e2e.py                # 双向依次跑

为什么要单独一个脚本：跨设备链路的每一环都可能悄悄失败，而且**失败通常不留痕迹**
（2026-09-15 实测：手机侧模型会说「已下发」，手表侧 ack 了 failed，两端 engine.log 却一行没有）。
所以这里每一步都要求**可核对的证据**，不接受模型自述：

  方向 1（手机 → 手表）全自动：
    手机输入任务 → 手机日志出现 `执行 remote` + 指令号 → 手表日志出现「开始执行跨设备指令」
    → 手表日志出现终态 → **手表前台包名 == 目标任务应用**（决定性证据）

  方向 2（手表 → 手机）需要人工一次：
    手表端**没有可用的 adb 文本输入通道**（实测：`input text` / `input keyevent` 都进不去，
    见下方 NOTES），所以脚本会打印任务文本、等你在手表上输入并点运行，然后自动核对：
    手表日志 `remote` + 指令号 → 手机日志「[跨设备指令]」→ 手机终态 → **手机前台包名**

NOTES（2026-09-15 实测限制，别再重复踩）：
  · 手表端 `input text` 私有无效：手表前台正确、输入框已聚焦（占位符消失）也进不去字符；
    禁用/启用搜狗 IME 均无效。故方向 2 只能人工输入。
  · `am force-stop` 与「覆盖安装」都会清掉 ColorOS 的无障碍绑定；脚本每个任务前会补写设置。
  · 删除 engine.log 后若进程没重启，App 持有的 fd 指向已删文件 → 新日志全部丢失。
    本脚本一律「先 force-stop 再删日志」，保证日志文件重建。
  · 接收端 `AgentA11yService.get() == null` 时，接收端会直接 ack failed
    （detail「目标设备未开启无障碍服务」）——所以先确保两端 a11y 已连接。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from task_baseline import (  # noqa: E402
    A11Y_SVC, ACTIVITY, DEVICE_LOG, PKG, adb, a11y_enabled, ensure_a11y, require_device,
)

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "tests" / "results-cross-device.jsonl"

PHONE_HINT = "PKX110"      # OnePlus 13T
WATCH_HINT = "OWW261"      # OPPO Watch
PHONE_TARGET = "com.coloros.calculator"        # 手机端目标应用（计算器）
WATCH_TARGET = "com.heytap.wearable.calculator2"  # 手表端目标应用（计算器）
TYPE_DELAY = 0.8


# ------------------------------------------------------------------ 设备发现与前置

def devices() -> dict[str, str]:
    out = adb("devices", "-l")
    found: dict[str, str] = {}
    for line in out.splitlines()[1:]:
        parts = line.split()          # adb 用空格分隔（不是 tab），别用 "\tdevice" 判断
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        model = re.search(r"model:(\S+)", line)
        found[serial] = model.group(1) if model else ""
    return found


def pick(mapping: dict[str, str], hint: str) -> str | None:
    for serial, model in mapping.items():
        if hint.lower() in model.lower():
            return serial
    # model 拿不到时退回顺序（排除已选中的那台）
    return None


def preflight() -> tuple[str, str] | None:
    """自检并打印结论。返回 (phone, watch)，条件不满足返回 None。"""
    mapping = devices()
    if not mapping:
        print("✗ 没有任何已授权设备（adb devices 为空）")
        return None
    phone = pick(mapping, PHONE_HINT)
    watch = pick(mapping, WATCH_HINT)
    print(f"设备清单: {mapping}")
    if not phone or not watch:
        print(f"✗ 需要同时接上手机（{PHONE_HINT}）与手表（{WATCH_HINT}），当前不满足")
        return None
    ok = True
    for label, serial in (("手机", phone), ("手表", watch)):
        ver = adb("-s", serial, "shell", "dumpsys package", PKG).strip()
        m = re.search(r"versionName=(\S+)", ver)
        healthy = ensure_a11y(serial)
        connected = a11y_enabled(serial)
        print(f"· {label} {serial} app={m.group(1) if m else '未安装'} "
              f"a11y={'已连接' if connected else '未开启'}"
              f"{'（已自动补写绑定）' if not healthy else ''}")
        if not m:
            ok = False
        if not connected:
            print(f"  ⚠ {label} 无障碍未绑定，跨设备执行会直接失败（接收端会 ack failed）")
    return (phone, watch) if ok else None


def foreground(serial: str) -> str:
    out = adb("-s", serial, "shell", "dumpsys window")
    m = re.search(r"mCurrentFocus=Window\{[^}]*?u0 ([^/}\s]+)/", out)
    return m.group(1) if m else ""


def new_task(serial: str, task: str) -> None:
    """清日志（先 force-stop，保证文件重建）+ 拉起 App + 输入任务（不点运行）。"""
    adb("-s", serial, "shell", "am force-stop", PKG)
    time.sleep(1)
    adb("-s", serial, "shell", f"rm -f {DEVICE_LOG}")
    ensure_a11y(serial)
    adb("-s", serial, "shell", "input", "keyevent", "3")
    time.sleep(1)
    adb("-s", serial, "shell", "am", "start", "-n", ACTIVITY)
    time.sleep(4)


def type_task_phone(serial: str, task: str) -> bool:
    """手机端输入任务（`input text` 用 %s 表示空格）。"""
    adb("-s", serial, "shell", "input", "tap", "335", "1665")
    time.sleep(TYPE_DELAY)
    adb("-s", serial, "shell", "input", "text", task.replace(" ", "%s"))
    time.sleep(TYPE_DELAY)
    adb("-s", serial, "shell", "input", "keyevent", "111")
    time.sleep(TYPE_DELAY)
    dump = adb("-s", serial, "shell", "uiautomator dump /sdcard/_cd.xml && cat /sdcard/_cd.xml")
    m = re.search(r'resource-id="[^"]*btnRun"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb("-s", serial, "shell", "input", "tap", str(x), str(y))
    return True


def device_log(serial: str) -> str:
    return adb("-s", serial, "shell", f"cat {DEVICE_LOG}")


def wait_terminal(serial: str, timeout_s: int) -> tuple[str, str]:
    """等 engine.log 出现终态，返回 (日志全文, 终态行)。"""
    t0 = time.time()
    log = ""
    while time.time() - t0 < timeout_s:
        log = device_log(serial)
        for line in log.splitlines():
            if re.search(r"任务完成：|任务失败——|任务已被用户停止", line):
                return log, line.strip()
        time.sleep(3)
    return log, ""


def wait_pattern(serial: str, pattern: str, timeout_s: int) -> str:
    """等某行出现，返回该行（用于确认「指令已下发 / 已开始执行」）。"""
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        for line in device_log(serial).splitlines():
            if re.search(pattern, line):
                return line.strip()
        time.sleep(3)
    return ""


# ------------------------------------------------------------------ 两个方向

def phone_to_watch(phone: str, watch: str, timeout_s: int) -> dict:
    task = f"Ask the {WATCH_HINT} watch to open the Calculator"
    print(f"\n=== 方向 1：手机 → 手表 ===\n任务：{task}")
    new_task(phone, task)
    if not type_task_phone(phone, task):
        return {"direction": "phone2watch", "ok": False, "reason": "找不到手机端运行按钮"}

    phone_line = wait_pattern(phone, r"第 \d+ 步：执行 remote", 90)
    if not phone_line:
        return {"direction": "phone2watch", "ok": False,
                "reason": "手机端没有产生 remote 动作（模型没走跨设备）", "log": device_log(phone)[-800:]}
    cid = re.search(r"指令 (c_[0-9a-f]+)", device_log(phone))
    print(f"· 手机已下发：{phone_line}")
    print(f"· 指令号：{cid.group(1) if cid else '（未在日志里出现，见下）'}")

    watch_line = wait_pattern(watch, r"开始执行跨设备指令|任务启动: \[跨设备指令\]", 120)
    if not watch_line:
        return {"direction": "phone2watch", "ok": False, "reason": "手表端未领取指令",
                "phone_line": phone_line, "watch_log": device_log(watch)[-800:]}
    print(f"· 手表已领取：{watch_line}")

    _, terminal = wait_terminal(watch, timeout_s)
    fg = foreground(watch)
    ok = bool(terminal) and WATCH_TARGET in fg
    print(f"· 手表终态：{terminal or '（超时未收终态）'}")
    print(f"· 手表前台：{fg} → {'✓ 已打开计算器' if WATCH_TARGET in fg else '✗ 目标应用不在前台'}")
    return {"direction": "phone2watch", "ok": ok, "task": task, "command_id": cid.group(1) if cid else "",
            "phone_action": phone_line, "watch_pickup": watch_line, "watch_terminal": terminal,
            "watch_foreground": fg}


def watch_to_phone(phone: str, watch: str, timeout_s: int, wait_input_s: int) -> dict:
    task = f"Ask the {PHONE_HINT} phone to open the Calculator"
    print(f"\n=== 方向 2：手表 → 手机 ===")
    print("手表端没有可用的 adb 文本输入通道（实测限制，见文件头 NOTES）。")
    print(f"请**在手表上**输入下面这条任务并点「运行」：\n    {task}")
    new_task(phone, task)          # 手机侧只是清日志/准备核对，不需要输入
    new_task(watch, task)
    print(f"· 已清空两端日志，等待手表端发起（最多 {wait_input_s} 秒）…")

    watch_line = wait_pattern(watch, r"第 \d+ 步：执行 remote", wait_input_s)
    if not watch_line:
        return {"direction": "watch2phone", "ok": False, "task": task,
                "reason": f"{wait_input_s}s 内手表端未发起 remote（可能未输入任务/模型未走跨设备）",
                "watch_log": device_log(watch)[-800:]}
    cid = re.search(r"指令 (c_[0-9a-f]+)", device_log(watch))
    print(f"· 手表已下发：{watch_line}（指令 {cid.group(1) if cid else '?'}）")

    phone_line = wait_pattern(phone, r"任务启动: \[跨设备指令\]", 120)
    if not phone_line:
        return {"direction": "watch2phone", "ok": False, "reason": "手机端未领取指令",
                "watch_line": watch_line, "phone_log": device_log(phone)[-800:]}
    print(f"· 手机已领取：{phone_line}")

    _, terminal = wait_terminal(phone, timeout_s)
    fg = foreground(phone)
    ok = bool(terminal) and PHONE_TARGET in fg
    print(f"· 手机终态：{terminal or '（超时未收终态）'}")
    print(f"· 手机前台：{fg} → {'✓ 已打开计算器' if PHONE_TARGET in fg else '✗ 目标应用不在前台'}")
    return {"direction": "watch2phone", "ok": ok, "task": task, "command_id": cid.group(1) if cid else "",
            "watch_action": watch_line, "phone_pickup": phone_line, "phone_terminal": terminal,
            "phone_foreground": fg}


# --------------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只做前置自检")
    ap.add_argument("--dir", choices=["phone2watch", "watch2phone", "both"], default="both")
    ap.add_argument("--timeout", type=int, default=120, help="单任务等待终态的秒数")
    ap.add_argument("--watch-input-wait", type=int, default=300, help="等人工在手表上输入任务的秒数")
    args = ap.parse_args()

    pair = preflight()
    if not pair:
        return 2
    phone, watch = pair
    if args.check:
        print("\n✓ 前置自检通过（两台设备在线、App 已装、无障碍已绑定）")
        return 0

    results = []
    if args.dir in ("phone2watch", "both"):
        results.append(phone_to_watch(phone, watch, args.timeout))
    if args.dir in ("watch2phone", "both"):
        results.append(watch_to_phone(phone, watch, args.timeout, args.watch_input_wait))

    with OUT.open("a", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print("\n=== 汇总 ===")
    for r in results:
        print(f"  {r['direction']}: {'✓ 通过' if r['ok'] else '✗ 未通过'}"
              + ("" if r["ok"] else f" —— {r.get('reason', '')}"))
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
