#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""fuzzy_ab_compare.py — A/B 对照：**有转译层** vs **无转译层**，同一任务集。

    python3 tests/mock_llm_server.py &                 # 先起假模型服务
    echo ab > /tmp/mock_llm_scene.txt
    python3 tests/fuzzy_ab_compare.py [serial] [apkA] [apkB]

实验设计（唯一变量 = APK 版本）：

  · 两个 APK 由**同一份源码**构建，B 版本只把 `planTask` 回退为旧的 3 行散文实现、
    并移除三个转译类；`LlmClient` 等其余文件逐字节相同（构建前用 diff 核对过）。
  · 同一台设备、同一批任务、同一个假模型服务、同一份设备配置。
  · 假模型（`MOCK_MODE=ab`）**只看提示词里有什么**来决定动作——它模拟真实模型最
    基本的性质：看不见的信息就不会遵守。因此两版的行为差异只可能来自提示词内容。

测量：
  ① 提示词信息完备度（客观、可复现）：结构化目标 / 路径建议 / 完成判据 / 风险标注 / 确认点要求；
  ② 行为后果（模拟）：风险任务是否被要求确认，还是被直接动手；
  ③ 成本：提示词字符数、模型请求次数、端到端耗时。

局限（必须一并阅读）：假模型是**规则化的**，不能代表真实模型的智能。
本实验能证明的是「信息有没有传下去、传下去会怎样」，**不能**证明真实任务的成功率差异——
那需要真机 + 真 Key（见设计方案附录 D.4）。
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import fuzzy_e2e_mock as fe          # noqa: E402  复用设备交互的既有实现

CALL_LOG = os.environ.get("MOCK_LOG_FILE", "/tmp/mock_llm_calls_ab.jsonl")
SCENE_FILE = os.environ.get("MOCK_SCENE_FILE", "/tmp/mock_llm_scene_ab.txt")
MOCK_PORT = os.environ.get("MOCK_PORT", "18998")
MOCK_URL = f"http://127.0.0.1:{MOCK_PORT}/"
OUT = pathlib.Path("/tmp/ab_compare.json")


def mock_alive() -> bool:
    """本脚本用**独立端口与独立场景文件**，避免与其它会话抢同一个 mock 实例
    （实测踩过：并发会话把 /tmp/mock_llm_scene.txt 改成了别的场景，实验直接失真）。"""
    import urllib.request
    try:
        with urllib.request.urlopen(MOCK_URL, timeout=3) as r:
            return json.loads(r.read().decode()).get("ok") is True
    except Exception:
        return False

# 相同复杂度的一组任务：覆盖直达 / 开关 / 多步参数 / 资金风险 / 发送风险
TASKS = [
    ("T1", "open display settings", "单步直达"),
    ("T2", "turn off bluetooth", "开关 + 否定语义"),
    ("T3", "search milk tea in meituan", "多步 + 参数"),
    ("T4", "pay the phone bill", "资金风险"),
    ("T5", "send message to mom", "发送风险"),
]

# 提示词完备度清单：这些正是转译层存在的理由
PROMPT_ITEMS = [
    ("结构化目标", "目标："),
    ("路径建议", "路径建议："),
    ("完成判据", "完成判据："),
    ("风险标注", "风险等级："),
    ("确认点要求", "必须先 ask"),
    ("路径非命令声明", "不是命令"),
]


def read_calls() -> list:
    try:
        with open(CALL_LOG, encoding="utf-8") as f:
            return [json.loads(x) for x in f if x.strip()]
    except Exception:
        return []


def clear_calls() -> None:
    try:
        os.remove(CALL_LOG)
    except Exception:
        pass


def submit(serial: str, task: str, ui: dict) -> bool:
    """输入任务并点运行；返回是否真的启动（复用 e2e 脚本里踩过的坑经验）。"""
    if "etTask" not in ui or "btnRun" not in ui:
        return False
    ex, ey = ui["etTask"]
    bx, by = ui["btnRun"]
    fe.sh(serial, f"input tap {ex} {ey}")
    time.sleep(1.5)
    fe.sh(serial, "input keyevent 123")
    for _ in range(80):
        fe.sh(serial, "input keyevent 67")
    time.sleep(0.5)
    subprocess.run(["adb", "-s", serial, "shell", "input", "text",
                    task.replace(" ", "%s")], capture_output=True, timeout=25)
    time.sleep(1.0)
    fe.sh(serial, "input keyevent 111")       # 收键盘：否则 btnRun 坐标已失效
    time.sleep(2.0)
    u2 = fe.find_widgets(serial)
    bx, by = u2.get("btnRun", (bx, by))
    fe.sh(serial, f"input tap {bx} {by}")
    # 轮询确认启动，最长 12s。**不能用固定短等待**：判早了会误判成"没启动"而重试，
    # 而重试的第二次点击落在运行中的引擎上 = 停止任务，结果整个任务数据为空（实测踩过）。
    for _ in range(6):
        time.sleep(2)
        if "任务启动" in fe.dump_log(serial):
            return True
    return False


def ensure_a11y(serial: str) -> bool:
    """确保无障碍服务**真的绑定**了。

    只写 `settings put` 是不够的：新建/重启过的模拟器上，写进去的设置可能被清掉，
    而服务没绑定时的表现是「点运行毫无反应、logcat 一句日志都没有」（引擎直接拒绝启动），
    排查起来毫无线索。故必须 dumpsys 验证绑定，失败就重设并重启 App 触发绑定。
    """
    for _ in range(3):
        out = fe.sh(serial, "dumpsys accessibility 2>/dev/null | grep -c 'Agent Lite'")
        if out.strip().isdigit() and int(out.strip()) > 0:
            return True
        fe.sh(serial, f"settings put secure enabled_accessibility_services {fe.PKG}/{fe.PKG}.AgentA11yService")
        fe.sh(serial, "settings put secure accessibility_enabled 1")
        time.sleep(2)
        fe.sh(serial, f"am start -n {fe.PKG}/.MainActivity", timeout=20)
        time.sleep(5)
    return False


def run_one(serial: str, tid: str, task: str, kind: str) -> dict:
    """跑一个任务，返回测量记录。"""
    fe.sh(serial, f"am force-stop {fe.PKG}")
    if not ensure_a11y(serial):
        return {"id": tid, "task": task, "kind": kind, "error": "A11Y_NOT_BOUND"}
    # ★ 先回桌面：上一个任务若执行过 setting/app 类动作，系统设置页会压在最上层，
    # 此时 am start 也未必把它盖下去，脚本会在「系统设置」里找 etTask 而永远找不到。
    fe.sh(serial, "input keyevent 3")
    time.sleep(1.5)
    fe.sh(serial, f"am start -n {fe.PKG}/.MainActivity", timeout=20)
    time.sleep(4)
    ui = fe.ensure_main(serial)
    if "etTask" not in ui:                 # 冷启动首轮偶发未就绪 → 再等一轮
        time.sleep(4)
        ui = fe.ensure_main(serial)
    if "etTask" not in ui:
        return {"id": tid, "task": task, "kind": kind, "error": "UI_NOT_READY"}

    clear_calls()
    t0 = time.time()
    started = submit(serial, task, ui)
    if not started:
        ui2 = fe.ensure_main(serial)
        started = submit(serial, task, ui2) if "etTask" in ui2 else False
    if not started:
        return {"id": tid, "task": task, "kind": kind, "error": "NOT_STARTED"}

    # 等到出现 ask / done / fail 或超时（ask 会阻塞等待用户，必须及时收手）
    deadline = time.time() + 30
    acted = []
    while time.time() < deadline:
        time.sleep(2)
        calls = read_calls()
        eng = [c for c in calls if c.get("kind") == "engine"]
        if eng:
            acted = [json.loads(c["reply"]) if c["reply"].strip().startswith("{") else {}
                     for c in eng if c.get("reply", "").strip().startswith("{")]
            last = acted[-1].get("action") if acted else ""
            if last in ("ask", "done", "fail"):
                break
    elapsed = round(time.time() - t0, 1)

    calls = read_calls()
    trans = [c for c in calls if c.get("kind") == "translate"]
    eng = [c for c in calls if c.get("kind") == "engine"]
    prompt = eng[0].get("user", "") if eng else ""
    actions = []
    for c in eng:
        txt = (c.get("reply") or "").strip()
        if txt.startswith("{"):
            try:
                actions.append(json.loads(txt).get("action", "?"))
            except Exception:
                actions.append("?")

    # 提示词完备度
    items = {name: (marker in prompt) for name, marker in PROMPT_ITEMS}

    fe.sh(serial, f"am force-stop {fe.PKG}")     # ask 阻塞时及时收场
    return {
        "id": tid, "task": task, "kind": kind,
        "started": True,
        "has_translate_call": len(trans) >= 1,
        "translate_max_tokens": (trans[0].get("max_tokens") if trans else None),
        "prompt_chars": len(prompt),
        "prompt_items": items,
        "engine_calls": len(eng),
        "actions": actions,
        "asked_confirmation": "ask" in actions,
        "elapsed_s": elapsed,
    }


def run_version(serial: str, label: str, apk: str) -> list:
    print(f"\n{'=' * 78}\n版本 {label}：{apk}\n{'=' * 78}")
    r = subprocess.run(["adb", "-s", serial, "install", "-r", "-d", apk],
                       capture_output=True, text=True, timeout=300)
    if "Success" not in (r.stdout or ""):
        print(f"  ✗ 安装失败：{(r.stdout or '') + (r.stderr or '')}")
        return []
    print("  已安装")
    # 预热：装完 APK 的首次冷启动 + 无障碍重绑最慢（实测首轮任务必失败），
    # 先空跑一轮把 UI 拉起来，避免把冷启动开销算进第一个任务。
    fe.sh(serial, f"am force-stop {fe.PKG}")
    ok_a11y = ensure_a11y(serial)
    fe.sh(serial, f"am start -n {fe.PKG}/.MainActivity", timeout=20)
    time.sleep(6)
    fe.ensure_main(serial)
    print(f"  已预热（无障碍绑定={'是' if ok_a11y else '否'})")
    rows = []
    for tid, task, kind in TASKS:
        row = run_one(serial, tid, task, kind)
        # 冷启动首轮、或 UI 时序抖动会造成空数据；空数据进对照表等于造假，必须重跑
        if row.get("error") or not row.get("prompt_chars"):
            print(f"  {tid} 数据异常（{row.get('error') or '无提示词'}），重试一次…")
            row2 = run_one(serial, tid, task, kind)
            if not row2.get("error") and row2.get("prompt_chars"):
                row = row2
        rows.append(row)
        if row.get("error"):
            print(f"  {tid} {kind:16} ✗ {row['error']}")
        else:
            hits = sum(1 for v in row["prompt_items"].values() if v)
            print(f"  {tid} {kind:16} 提示词 {row['prompt_chars']:4}字 "
                  f"完备 {hits}/{len(PROMPT_ITEMS)} 动作={','.join(row['actions']) or '-'} "
                  f"{row['elapsed_s']}s")
    return rows


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    root = pathlib.Path(__file__).resolve().parent.parent
    apk_a = sys.argv[2] if len(sys.argv) > 2 else str(root / "agent-lite" / "build" / "agent-lite.apk")
    apk_b = sys.argv[3] if len(sys.argv) > 3 else "/tmp/agent-lite-nostl/build/agent-lite.apk"

    for p in (apk_a, apk_b):
        if not pathlib.Path(p).exists():
            print(f"✗ 找不到 APK：{p}")
            return 1
    if not mock_alive():
        print(f"✗ 假模型服务未启动："
              f"MOCK_PORT={MOCK_PORT} MOCK_SCENE_FILE={SCENE_FILE} "
              f"MOCK_LOG_FILE={CALL_LOG} python3 tests/mock_llm_server.py &")
        return 1

    with open(SCENE_FILE, "w", encoding="utf-8") as f:
        f.write("ab\n")

    res_a = run_version(serial, "A-含转译层", apk_a)
    res_b = run_version(serial, "B-无转译层", apk_b)

    # ---------------- 对比 ----------------
    print(f"\n{'=' * 78}\n对照结果\n{'=' * 78}")
    print(f"{'任务':<6}{'类型':<18}{'A 完备':<9}{'B 完备':<9}{'A 提示词':<10}{'B 提示词':<10}")
    a_items = b_items = 0
    a_chars = b_chars = 0
    for ra, rb in zip(res_a, res_b):
        if ra.get("error") or rb.get("error"):
            continue
        ha = sum(1 for v in ra["prompt_items"].values() if v)
        hb = sum(1 for v in rb["prompt_items"].values() if v)
        a_items += ha
        b_items += hb
        a_chars += ra["prompt_chars"]
        b_chars += rb["prompt_chars"]
        print(f"{ra['id']:<6}{ra['kind']:<18}{ha}/{len(PROMPT_ITEMS):<7}"
              f"{hb}/{len(PROMPT_ITEMS):<7}{ra['prompt_chars']:<10}{rb['prompt_chars']:<10}")

    n = max(1, len([r for r in res_a if not r.get("error")]))
    print("-" * 78)
    print(f"提示词完备度合计：A {a_items} 项 / B {b_items} 项（满 {n * len(PROMPT_ITEMS)}）")
    print(f"提示词字符合计：  A {a_chars} / B {b_chars}（+{a_chars - b_chars}，"
          f"约 +{(a_chars - b_chars) / max(1, n):.0f} 字/任务）")

    risk_tasks = ("T4", "T5")
    for label, rows in (("A-含转译层", res_a), ("B-无转译层", res_b)):
        asked = [r["id"] for r in rows if not r.get("error")
                 and r["id"] in risk_tasks and r["asked_confirmation"]]
        direct = [r["id"] for r in rows if not r.get("error")
                  and r["id"] in risk_tasks and not r["asked_confirmation"]]
        print(f"风险任务行为（{label}）：先确认={asked or '无'}｜未确认直接动手={direct or '无'}")

    api = [r for r in res_a if r.get("has_translate_call")]
    print(f"转译请求：A 组 {len(api)}/{len(res_a)} 个任务发出"
          f"（max_tokens={api[0]['translate_max_tokens'] if api else '-'}）｜"
          f"B 组 0（该版本无转译层）")

    OUT.write_text(json.dumps({"a": res_a, "b": res_b}, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    print(f"\n原始数据：{OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
