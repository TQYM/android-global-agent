#!/usr/bin/env python3
"""watch_task.py — 手表（OPPO Watch OWW261）标准任务集矩阵 runner。

手表 UI 输入区在屏外（手机布局未适配），统一走 adb am start --es task --ez auto_run true 通道；
结果判定轮询 engine.log 的「任务完成/任务失败」行。

用法:
  python3 tests/watch_task.py [serial]            # 跑全部 8 条
  python3 tests/watch_task.py [serial] 1 3        # 只跑第 1、3 条
结果: tests/results-watch.jsonl（append）
"""
import json
import subprocess
import sys
import time
import os
import re

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.dsh.agentlite"
ENGINE_LOG = "/sdcard/Android/data/com.dsh.agentlite/files/engine.log"

TASKS = [
    "open_settings",     # 系统设置（上轮 fail：手表桌面无设置图标，看 LLM 能否 swipe 探索）
    "open_weather",      # 天气 app
    "swipe_up",          # 上滑通知/控制中心
    "swipe_down",        # 下滑
    "open_health",       # 健康 app
    "go_home",           # 回到表盘
    "open_appmarket",    # 应用市场
    "check_alarms",      # 闹钟
]


def sh(serial, *args, timeout=30):
    r = subprocess.run([ADB, "-s", serial] + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip(), r.returncode


def read_log(serial):
    out, _ = sh(serial, "shell", f"cat {ENGINE_LOG}", timeout=15)
    return out


def run_task(serial, task, timeout=100):
    sh(serial, "shell", f"rm -f {ENGINE_LOG}")
    sh(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity",
       "--es", "task", task, "--ez", "auto_run", "true")
    t0 = time.time()
    steps, result, detail = 0, "timeout", ""
    while time.time() - t0 < timeout:
        time.sleep(5)
        log = read_log(serial)
        if not log:
            continue
        steps = max(steps, len(re.findall(r"第 \d+ 步", log)))
        m = re.findall(r"任务完成：(.*)", log)
        if m:
            result, detail = "done", m[-1].strip()
            break
        m = re.findall(r"任务失败[:：](.*)", log)
        if m:
            result, detail = "fail", m[-1].strip()
            break
        if "已达最大步数" in log or "最大步数" in log:
            result, detail = "fail", "max steps"
            break
    return {"task": task, "result": result, "steps": steps,
            "detail": detail[:120], "secs": round(time.time() - t0, 1)}


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else "7d304c5c"
    only = [int(x) for x in sys.argv[2:]] or list(range(1, len(TASKS) + 1))
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results-watch.jsonl")
    print(f"=== 手表任务集 @ {serial}：{len(only)} 条 ===")
    ok = 0
    for i in only:
        task = TASKS[i - 1]
        print(f"\n[{i}/{len(only)}] {task} ...", flush=True)
        r = run_task(serial, task)
        r["idx"] = i
        ok += r["result"] == "done"
        print(f"  → {r['result'].upper()} {r['steps']}步 {r['secs']}s  {r['detail']}")
        with open(out_path, "a") as f:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
        time.sleep(3)
    print(f"\n=== 完成 {ok}/{len(only)} ===")
    print(f"结果已追加: {out_path}")


if __name__ == "__main__":
    main()
