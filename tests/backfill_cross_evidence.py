#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""backfill_cross_evidence.py — 给 20 轮交叉测试结果补上「步数」与目标设备证据。

    python3 tests/backfill_cross_evidence.py

背景（2026-09-15）：`cross_20_rounds.py` 首轮跑完时，结果文件里只有成功率、耗时、
服务端指令号、终态与前台包名，**少了目标设备步数**（目标要求的四类证据之一）。
设备上的 engine.log 仍完整覆盖测试时段，所以按「手机端任务启动行（英文原文，可精确匹配）」
切分时间轴，再用时间窗口去手表日志里取对应段，把步数回填进 `tests/results-cross-20.jsonl`。

手机侧日志的任务文本是**英文原文**（adb 输入的就是英文），所以能精确匹配到轮次；
手表侧收到的是模型改写过的中文任务，只能靠"手机段起点 + 窗口"配对。
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "tests" / "results-cross-20.jsonl"
DEVICE_LOG = "/sdcard/Android/data/com.dsh.agentlite/files/engine.log"
PHONE, WATCH = "3B15B401NPF00000", "7d304c5c"

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d) (.*)$")


def pull(serial: str) -> list[tuple[str, str]]:
    """["MM-DD HH:MM:SS", 文本] 列表。"""
    r = subprocess.run(["adb", "-s", serial, "shell", f"cat {DEVICE_LOG}"],
                       capture_output=True, text=True, timeout=90)
    out = []
    for line in (r.stdout or "").splitlines():
        m = TS.match(line.strip())
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def segments(lines: list[tuple[str, str]], start_pat: str) -> list[dict]:
    """按 start_pat 切段；每段含起止时间、步数、终态。"""
    segs: list[dict] = []
    cur: dict | None = None
    for ts, text in lines:
        if re.search(start_pat, text):
            if cur:
                segs.append(cur)
            cur = {"start": ts, "end": ts, "task": text, "steps": 0, "terminal": ""}
            continue
        if cur is None:
            continue
        cur["end"] = ts
        m = re.search(r"第 (\d+) 步", text)
        if m:
            cur["steps"] = max(cur["steps"], int(m.group(1)))
        if re.search(r"任务完成：|任务失败——|任务已被用户停止", text):
            cur["terminal"] = text
    if cur:
        segs.append(cur)
    return segs


def secs(hhmmss: str) -> int:
    h, m, s = hhmmss.split()[-1].split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def main() -> int:
    rows = [json.loads(l) for l in OUT.read_text().splitlines() if l.strip()]
    phone_segs = segments(pull(PHONE), r"任务启动: ")
    watch_segs = segments(pull(WATCH), r"任务启动: ")
    print(f"手机任务段 {len(phone_segs)} 个 / 手表任务段 {len(watch_segs)} 个")

    # ---- 按序对齐（比时间窗口贪心配对可靠）：设备收到一条跨设备指令就启动一次任务，
    #      日志顺序与"实际产生了指令的轮次"顺序一一对应。
    #      但设备日志里还有**测试之前**的历史跨设备段，必须先按测试时间窗过滤，否则整体错位。
    t0 = rows[0]["ts"].split()[-1]
    t1 = secs(rows[-1]["ts"].split()[-1]) + 600
    t1 = f"{t1 // 3600:02d}:{t1 % 3600 // 60:02d}:{t1 % 60:02d}"

    def in_window(s: dict) -> bool:
        return t0 <= s["start"].split()[-1] <= t1

    watch_remote = [s for s in watch_segs if in_window(s) and "跨设备指令" in s["task"]]
    phone_remote = [s for s in phone_segs if in_window(s) and "跨设备指令" in s["task"]]
    expect_watch: list[tuple[int, str]] = []
    for r in rows:
        if r.get("a", {}).get("command_id"):
            expect_watch.append((r["round"], "a"))
        if r.get("b", {}).get("hop2_command_id"):
            expect_watch.append((r["round"], "b"))
    expect_phone = [(r["round"], "b") for r in rows if r.get("b", {}).get("hop2_command_id")]
    print(f"期望手表执行 {len(expect_watch)} 段 / 手表实际跨设备段 {len(watch_remote)} 段")
    print(f"期望手机执行 {len(expect_phone)} 段 / 手机实际跨设备段 {len(phone_remote)} 段")

    by_round = {r["round"]: r for r in rows}
    paired = 0
    for (rd, seg), w in zip(expect_watch, watch_remote):
        tgt = by_round[rd].setdefault(seg, {})
        tgt["exec_steps"] = w["steps"]
        tgt["exec_device"] = "watch" if seg == "a" else "phone"
        tgt["exec_terminal"] = w["terminal"]
        m = re.search(r"com\.[a-z0-9_.]+", w["terminal"] or "")
        if m:
            tgt["exec_foreground_pkg"] = m.group(0)
        paired += 1
    for (rd, seg), p in zip(expect_phone, phone_remote):
        tgt = by_round[rd].setdefault(seg, {})
        tgt["phone_exec_steps"] = p["steps"]
        tgt["phone_exec_terminal"] = p["terminal"]
        m = re.search(r"com\.[a-z0-9_.]+", p["terminal"] or "")
        if m:
            tgt["phone_exec_foreground_pkg"] = m.group(0)

    # A/B 段手机侧下发耗时段的步数（手机作为发起方走了几步才决定 remote）
    for r in rows:
        for seg in ("a", "b"):
            task = r.get(seg, {}).get("task")
            if not task:
                continue
            for s in phone_segs:
                if task in s["task"]:
                    r[seg]["dispatch_steps"] = s["steps"]
                    break

    OUT.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
    have = sum(1 for r in rows for k in ("a", "b") if r.get(k, {}).get("exec_steps") is not None)
    print(f"✓ 已对齐 {paired} 段，结果文件里 {have}/40 段带目标设备执行步数")
    return 0


if __name__ == "__main__":
    sys.exit(main())
