#!/usr/bin/env python3
"""harmony_task.py — 鸿蒙（HarmonyOS NEXT）标准任务集矩阵 runner。

用法:
  python3 tests/harmony_task.py [serial] [序号...]      # 默认跑全部
  python3 tests/harmony_task.py 127.0.0.1:5555 1 3
结果: tests/results-harmony.jsonl（append，gitignore）
"""
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools", "harmony-agent"))
import hdc            # noqa: E402
import harmony_agent  # noqa: E402

# (任务, 最大步数, 说明)
TASKS = [
    ("打开设置", 14, "基础导航"),
    ("在当前页面开启全天深色模式", 10, "状态切换（Toggle 本体 + 状态复核）"),
    ("进入WLAN页面查看开关状态，然后返回上一页", 14, "多级导航 + 状态读取 + 返回"),
    ("打开设置里的系统页面，然后返回设置主页", 16, "子页导航 + 返回"),
    ("在设置里找到并打开关于本机（可能需要向下滚动），然后返回", 20, "滚动查找 + 返回"),
    ("回到桌面，然后打开图库", 14, "跨应用"),
    ("打开设置，然后回到桌面", 14, "跨应用往返"),
    ("查看当前界面有哪些可点的设置项，列出前三个，然后返回上一页", 14, "信息读取"),
]


def run_one(serial, idx, task, max_steps, settle):
    t0 = time.time()
    result = harmony_agent.run(task, serial, BASE, KEY, MODEL, max_steps,
                               verbose=False, settle=settle)
    ok = result.startswith("done")
    return {"idx": idx, "task": task, "result": result[:160], "ok": ok,
            "secs": round(time.time() - t0, 1)}


def main():
    global BASE, KEY, MODEL
    serial = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1:5555"
    only = [int(x) for x in sys.argv[2:]] or list(range(1, len(TASKS) + 1))

    dev_local = os.path.join(ROOT, "agent-lite", "dev.local")
    KEY = ""
    BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    for line in open(dev_local):
        if line.startswith(("API_KEY=", "DASHSCOPE_API_KEY=", "key=")):
            KEY = line.split("=", 1)[1].strip().strip('"\'')
        elif line.startswith(("BASE_URL=", "base_url=")):
            BASE = line.split("=", 1)[1].strip().strip('"\'')
    MODEL = os.environ.get("AGENT_MODEL", "qwen3.5-flash")
    settle = float(os.environ.get("AGENT_SETTLE", "3"))

    out_path = os.path.join(ROOT, "tests", "results-harmony.jsonl")
    print(f"=== 鸿蒙标准任务集 @ {serial}：{len(only)} 条（模型 {MODEL}）===")
    passed = 0
    for i in only:
        task, steps, note = TASKS[i - 1]
        print(f"\n[{i}/{len(TASKS)}] {task}  （{note}）", flush=True)
        r = run_one(serial, i, task, steps, settle)
        passed += r["ok"]
        mark = "✅" if r["ok"] else "❌"
        print(f"  {mark} {r['secs']}s  {r['result']}")
        with open(out_path, "a") as f:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
        time.sleep(2)
    print(f"\n=== 通过 {passed}/{len(only)} ===")
    print(f"结果: {out_path}")


if __name__ == "__main__":
    main()
