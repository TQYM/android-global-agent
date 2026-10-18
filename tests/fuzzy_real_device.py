#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""fuzzy_real_device.py — **真机 + 真模型**下的模糊语义转译验证。

    python3 tests/fuzzy_real_device.py [serial]

这是设计方案附录 D.4 里挂了最久的那个遗留项：**真实模型能否稳定输出合规 FlowSpec**。
本地假模型能证明接线通了，证明不了提示词对真实模型有效——只有真机 + 真 Key 能回答。

两个真机专有的难点，本脚本都处理了：
  1. **中文输入**：`adb shell input text` 不支持非 ASCII，模拟器上剪贴板通道也不可用。
     真机上装了项目自己的 FlorisBoard 缝合桥 `AgentImeBridge`，改用广播
     `com.dsh.agent.IME_COMMIT`（extras: text）注入，日志在
     `/sdcard/Android/data/dev.patrickgold.florisboard/files/bridge.log`。
  2. **安全收手**：真机上跑「交话费 / 发消息」会真的动手。本脚本**只验证到转译 + 前几步**
     就 force-stop，不让敏感操作真正执行完。

指标：转译成功率（结构化 / 退化 / 未产出）、intent 与 risk 判定、端到端耗时。
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import fuzzy_e2e_mock as fe          # noqa: E402  复用设备交互

BRIDGE_LOG = "/sdcard/Android/data/dev.patrickgold.florisboard/files/bridge.log"
IME_BRIDGE = "dev.patrickgold.florisboard/.agent.AgentImeBridge"

# 中文模糊任务：覆盖五类模糊 + 风险语义；全部无空格（便于广播传参）
TASKS = [
    ("R1", "帮我打开蓝牙", "口语填充词"),
    ("R2", "把蓝芽关了", "ASR 错字 + 否定语义"),
    ("R3", "打开设置里的显示", "多步直达"),
    ("R4", "打开美团搜奶查", "错字 + 多步"),
    ("R5", "去公司怎么走", "导航意图"),
    ("R6", "点个奶茶送到公司", "省略表达 + 参数"),
    ("R7", "给妈妈发个消息", "发送风险"),
    ("R8", "交一下话费", "资金风险"),
]

RESULTS: list[dict] = []


def sh(serial: str, cmd: str, timeout: int = 30) -> str:
    # errors="replace"：日志里含中文，按字节截取（tail -c）可能切在多字节字符中间，
    # 严格解码会直接抛 UnicodeDecodeError 把整个测试打断（实测踩过）。
    r = subprocess.run(["adb", "-s", serial, "shell", cmd],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def inject_text(serial: str, text: str) -> bool:
    """经 FlorisBoard 缝合桥注入中文（root 发送以持有所需的 INJECT 权限）。"""
    out = sh(serial, f"su -c 'am broadcast -a com.dsh.agent.IME_COMMIT --es text {text}'")
    time.sleep(1.2)
    # 按行取（tail -n）而不是按字节取，避免切断多字节字符
    tail = sh(serial, f"su -c 'tail -n 3 {BRIDGE_LOG}'")
    return f"rx [{text}]" in tail


def run_one(serial: str, tid: str, task: str, kind: str) -> dict:
    fe.sh(serial, f"am force-stop {fe.PKG}")
    fe.sh(serial, "input keyevent 3")
    time.sleep(1)
    fe.sh(serial, f"am start -n {fe.PKG}/.MainActivity", timeout=25)
    time.sleep(4)
    ui = fe.ensure_main(serial)
    if "etTask" not in ui or "btnRun" not in ui:
        time.sleep(4)
        ui = fe.ensure_main(serial)
    if "etTask" not in ui:
        return {"id": tid, "task": task, "kind": kind, "error": "UI_NOT_READY"}

    # 清空输入框 → 聚焦 → 注入中文
    ex, ey = ui["etTask"]
    fe.sh(serial, f"input tap {ex} {ey}")
    time.sleep(1.2)
    fe.sh(serial, "input keyevent 123")
    for _ in range(60):
        fe.sh(serial, "input keyevent 67")
    time.sleep(0.6)
    if not inject_text(serial, task):
        return {"id": tid, "task": task, "kind": kind, "error": "INJECT_FAILED"}
    time.sleep(0.8)

    fe.sh(serial, "input keyevent 111")        # 收键盘：否则 btnRun 坐标已失效
    time.sleep(1.5)
    ui2 = fe.find_widgets(serial)
    bx, by = ui2.get("btnRun", (ui["btnRun"][0], ui["btnRun"][1]))
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], capture_output=True, timeout=20)
    t0 = time.time()
    fe.sh(serial, f"input tap {bx} {by}")

    # 只等到转译日志 + 前几步就收手（真机上不能让敏感操作真的跑完）
    log, translate_line, steps = "", "", 0
    deadline = time.time() + 45
    while time.time() < deadline:
        time.sleep(2)
        log = fe.dump_log(serial)
        m = re.search(r"流程转译(成功|退化|未产出)[^\n]*", log)
        if m:
            translate_line = m.group(0).strip()
            steps = len(re.findall(r"第 \d+ 步：执行", log))
            if steps >= 2 or time.time() > deadline - 30:
                break
    elapsed = round(time.time() - t0, 1)
    log = fe.dump_log(serial)
    fe.sh(serial, f"am force-stop {fe.PKG}")   # ★ 安全收手

    asked = "向用户提问" in log or "ask" in log
    intent = risk = ""
    m = re.search(r"intent=(\w+) risk=(\w+)", translate_line)
    if m:
        intent, risk = m.group(1), m.group(2)
    verdict = ("结构化" if "流程转译成功" in translate_line
               else "退化" if "流程转译退化" in translate_line
               else "未产出" if "流程转译未产出" in translate_line else "无转译日志")
    return {"id": tid, "task": task, "kind": kind, "verdict": verdict,
            "intent": intent, "risk": risk, "asked": asked,
            "steps": len(re.findall(r"第 \d+ 步：执行", log)),
            "elapsed_s": elapsed, "translate_line": translate_line[:160]}


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "3B15B401NPF00000"
    print("=" * 78)
    print(f"真机 + 真模型 · 模糊语义转译验证（serial={serial}）")
    print("=" * 78)

    prefs = sh(serial, "su -c 'cat /data/data/com.dsh.agentlite/shared_prefs/agent_prefs.xml'")
    model = re.search(r'<string name="model">([^<]*)', prefs)
    planner = re.search(r'<string name="planner_model">([^<]*)', prefs)
    single = re.search(r'<boolean name="single_model_mode" value="(\w+)"', prefs)
    base = re.search(r'<string name="base_url">([^<]*)', prefs)
    print(f"  模型={model.group(1) if model else '?'}  "
          f"规划模型={planner.group(1) if planner else '?'}  "
          f"单模型模式={single.group(1) if single else '?'}")
    print(f"  端点={base.group(1) if base else '?'}")

    if sh(serial, "settings get secure default_input_method").strip() != IME_BRIDGE:
        sh(serial, f"su -c 'ime set {IME_BRIDGE}'")
        time.sleep(2)

    for tid, task, kind in TASKS:
        row = run_one(serial, tid, task, kind)
        if row.get("error"):
            row2 = run_one(serial, tid, task, kind)
            if not row2.get("error"):
                row = row2
        RESULTS.append(row)
        if row.get("error"):
            print(f"  {tid} {task:<12} ✗ {row['error']}")
        else:
            print(f"  {tid} {task:<12} {row['verdict']:<5} "
                  f"intent={row['intent'] or '-':<15} risk={row['risk'] or '-':<6} "
                  f"步数={row['steps']} {row['elapsed_s']}s")

    print("-" * 78)
    ok = [r for r in RESULTS if not r.get("error")]
    struct = [r for r in ok if r["verdict"] == "结构化"]
    print(f"转译结构化成功：{len(struct)}/{len(ok)}"
          f"（{100 * len(struct) // max(1, len(ok))}%）")
    for v in ("退化", "未产出", "无转译日志"):
        n = len([r for r in ok if r["verdict"] == v])
        if n:
            print(f"  {v}：{n}")
    risky = [r for r in ok if r["risk"] in ("money", "send", "delete", "personal_info")]
    if risky:
        print(f"风险任务识别：{len(risky)} 个 → "
              + ", ".join(f"{r['task']}({r['risk']})" for r in risky))
    errs = [r for r in RESULTS if r.get("error")]
    if errs:
        print(f"数据异常：{[r['id'] for r in errs]}")

    pathlib.Path("/tmp/real_device_result.json").write_text(
        json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding="utf-8")
    print("原始数据：/tmp/real_device_result.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
