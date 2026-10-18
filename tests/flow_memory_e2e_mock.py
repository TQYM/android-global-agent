#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""flow_memory_e2e_mock.py — 在 Android 虚拟机上离线验证「操作记忆库：记一次，之后直接复现」。

    python3 tests/mock_llm_server.py &                       # 先起假模型服务
    python3 tests/flow_memory_e2e_mock.py [serial] [task]    # 场景由本脚本设为 flow

为什么必须有这个脚本：记忆库的纯逻辑由 tests/FlowMemoryTest.java（48 条断言）覆盖，
**但"记一次、之后直接复现就能完成操作"是接线行为**——逐步采集有没有真的触发、
`done` 时有没有真的入库、第二次有没有真的跳过模型。这些只有把 App 真跑两遍才看得见。

验证四条（第 3 条是要害）：

  1. 第一次任务成功后，日志出现「已写入操作记忆库」——每一步成功操作确实进了库；
  2. 第二次同一句话：出现「命中操作记忆库」与「已按记忆库直接完成」；
  3. ★ 第二次**零模型往返**：假模型服务的调用日志**一条都不新增**。这才叫"直接复现"；
     若新增了，说明它只是"又规划了一遍"，用户要的东西并没有做出来；
  4. `flowmem.json` 真的落盘且含那条记录（可直接 cat 出来给人看）。

退出码：0 = 全部断言通过；1 = 有断言失败。
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))

from fuzzy_e2e_mock import (  # noqa: E402  复用既有 UI 操作设施，避免第二套实现
    CALL_LOG,
    PKG,
    clear_logcat,
    dump_log,
    ensure_main,
    mock_alive,
    set_scene,
    sh,
)

APK = ROOT / "agent-lite" / "build" / "agent-lite.apk"
FLOWMEM = f"/data/data/{PKG}/files/flowmem.json"

RESULTS: list[tuple[str, bool, str]] = []


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, desc, ok, note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))


def install(serial: str) -> bool:
    r = subprocess.run(["adb", "-s", serial, "install", "-r", str(APK)],
                       capture_output=True, text=True, timeout=180)
    return "Success" in (r.stdout or "")


def calls() -> list[dict]:
    try:
        with open(CALL_LOG, encoding="utf-8") as f:
            return [json.loads(x) for x in f if x.strip()]
    except Exception:
        return []


def et_task_text(serial: str) -> str:
    """读任务输入框里的文本（用来确认输入真的进去了，而不是盲信 input text 成功）。"""
    xml = sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
    m = re.search(r'resource-id="%s:id/etTask"[^>]*text="([^"]*)"' % re.escape(PKG), xml)
    if not m:
        m = re.search(r'text="([^"]*)"[^>]*resource-id="%s:id/etTask"' % re.escape(PKG), xml)
    return (m.group(1) if m else "").strip()


def submit(serial: str, task: str, ui: dict) -> bool:
    """输入任务并点运行；返回是否**真的启动了任务**。

    这里比 fuzzy_e2e_mock.py 那版更严，因为实测踩了三次坑（每次都会误判成"产品缺陷"）：
      · 用**过期坐标**点击 → 落在旁边的「设置」按钮上，App 跳进 SettingsActivity，
        输入框里一个字都没有，日志里没有任何报错；
      · 软键盘弹出把整页上移 → 先前 dump 的 btnRun 坐标失效；
      · `input text` 在焦点丢失时静默丢弃文本。
    所以：每次点击前重新 dump 定位，并在点「运行」之前**校验输入框里确实有文本**。
    """
    for _ in range(4):
        # ★ 每次尝试前清日志：`logcat -d` 是**全量**缓冲，上一次尝试（乃至上一次任务）的
        #   「任务启动」会让本次判断凭空通过。实测踩过：第二次提交其实没跑起来，
        #   却因为读到第一次的日志而 PASS，于是"零模型往返"也一起假通过。
        clear_logcat(serial)
        ui = ensure_main(serial)
        if "etTask" not in ui or "btnRun" not in ui:
            time.sleep(2)
            continue
        if not ensure_a11y(serial):
            print("  · 无障碍未连上（点了也不会跑），等待重绑后重试")
            continue
        ex, ey = ui["etTask"]
        sh(serial, f"input tap {ex} {ey}")
        time.sleep(1.5)
        sh(serial, "input keyevent 123")            # MOVE_END
        for _ in range(80):
            sh(serial, "input keyevent 67")         # DEL 清残留
        time.sleep(0.5)
        subprocess.run(["adb", "-s", serial, "shell", "input", "text", task.replace(" ", "%s")],
                       capture_output=True, timeout=25)
        time.sleep(1.2)
        sh(serial, "input keyevent 111")            # ESC 收键盘（坐标随之复位）
        time.sleep(2.0)
        if not et_task_text(serial):
            print("  · 输入框仍为空，重试提交")
            continue
        ui2 = ensure_main(serial)
        bx, by = ui2.get("btnRun", (ui["btnRun"][0], ui["btnRun"][1]))
        sh(serial, f"input tap {bx} {by}")
        time.sleep(5)
        if "任务启动" in dump_log(serial):
            return True
        print("  · 点击运行后未见「任务启动」，重试提交")
        time.sleep(2)
    return False


def wait_for(serial: str, pattern: str, timeout: int = 150) -> tuple[str, bool]:
    """等到日志出现某条关键行（或超时）。返回（最后一次日志, 是否等到）。"""
    deadline = time.time() + timeout
    log = ""
    while time.time() < deadline:
        time.sleep(3)
        log = dump_log(serial)
        if re.search(pattern, log):
            return log, True
    return log, False


def a11y_connected(serial: str) -> bool:
    """无障碍是否真的连上了。

    这是引擎 `start()` 的第一道门：没连上时 `btnRun` 点了也不会跑，日志里**一句「任务启动」都没有**
    （只有一行「无障碍服务未开启」），很容易被误读成"点击没生效"。
    只有明确显示「未开启」才算没连——已连接时该提示是 GONE，dump 里根本不存在。
    """
    xml = sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
    m = re.search(r'resource-id="%s:id/tvA11y"[^>]*text="([^"]*)"' % re.escape(PKG), xml)
    return not (m and "未开启" in m.group(1))


def ensure_a11y(serial: str, tries: int = 4) -> bool:
    """把无障碍拉起来并**确认系统层面真的启用了**。

    ★ 这里刻意**不做 `am force-stop`**：force-stop 会让 Android 把该应用的
    无障碍服务从启用列表里摘掉（系统既定行为）。第一版重试逻辑正是靠 force-stop 重启应用，
    结果每一轮重试都把刚设好的绑定又拆掉，表现为「点了运行毫无反应」——
    排查方向被自己的重试逻辑带偏了整整一轮。重新 `settings put` 即可，无需重启应用。
    """
    for _ in range(tries):
        svc = sh(serial, "settings get secure enabled_accessibility_services").strip()
        on = sh(serial, "settings get secure accessibility_enabled").strip()
        if PKG in svc and on == "1" and a11y_connected(serial):
            return True
        sh(serial, f"settings put secure enabled_accessibility_services {PKG}/{PKG}.AgentA11yService")
        sh(serial, "settings put secure accessibility_enabled 1")
        time.sleep(4)
    return False


def prepare(serial: str) -> dict:
    """清场 → 启动 → 定位控件，并**确认无障碍真的连上**。

    四处环境残留必须自己收拾干净，否则脚本的失败会看起来像产品缺陷（实测各踩过一次）：
      · `font_scale` 被此前的可读性扫测留成 2.0 → 主页被撑长，任务输入框被挤出可视区，
        dump 里根本没有 `etTask`，脚本报「控件找不到」；
      · 设备端点残留指向**另一个 mock 实例**的端口 → 任务"完成"了但假模型一条调用都没收到；
      · 无障碍在重新安装 / force-stop 后不再绑定，且系统会把反复掉线的服务从启用列表里摘掉
        → 点「运行」毫无反应；
      · 冷启动可能停在登录 / 引导页 → 那里没有 `etTask` / `btnRun`。
    """
    sh(serial, f"am force-stop {PKG}")
    # 清场包含**删掉记忆库文件**：上一次运行的记录会让本次首跑就直接命中
    sh(serial, f"rm -f {FLOWMEM}")
    # 端点对齐：设备上可能残留指向**另一个 mock 实例**的端口。实测踩过——设备连 18998、
    # 新实例在 18999，于是"任务完成"了而假模型一条调用都没收到，排查方向被彻底带偏
    # （表现为：任务正常完成，但没有任何日志能解释它跟谁说的话）。
    sh(serial, f"sed -i 's|:18998/|:18999/|' /data/data/{PKG}/shared_prefs/agent_prefs.xml")
    sh(serial, "settings put system font_scale 1.0")

    ui: dict = {}
    for attempt in range(3):
        ensure_a11y(serial)
        for _ in range(3):
            sh(serial, "input keyevent 4")
            time.sleep(0.6)
        sh(serial, f"am start -n {PKG}/.MainActivity", timeout=20)
        time.sleep(7)
        ui = ensure_main(serial)
        for _ in range(3):                 # 登录页/横幅的时机抖动：再推两轮
            if "etTask" in ui and "btnRun" in ui:
                break
            sh(serial, "input keyevent 4")
            time.sleep(1.5)
            ui = ensure_main(serial)
        if "etTask" in ui and "btnRun" in ui and ensure_a11y(serial):
            return ui
        print(f"  · 第 {attempt + 1} 轮未就绪（控件={sorted(ui)}），重启应用再试")
        sh(serial, f"am force-stop {PKG}")     # 只在控件找不到时重启（重启后 a11y 需重设）
        time.sleep(3)
    return ui


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    task = sys.argv[2] if len(sys.argv) > 2 else "open settings"

    print("=" * 78)
    print(f"操作记忆库 · 虚拟机端到端验证（serial={serial}）")
    print("=" * 78)

    check("FM-0", "假模型服务在线", mock_alive(), "先启动 python3 tests/mock_llm_server.py")
    if not mock_alive():
        return 1

    set_scene("flow")           # 只回可复现白名单内的动作，首次任务才入得了库
    clear_logcat(serial)
    try:
        os.remove(CALL_LOG)
    except Exception:
        pass

    if APK.exists():
        print(f"  · 安装 {APK.name} …")
        check("FM-0b", "APK 安装成功", install(serial))
    else:
        check("FM-0b", "APK 已构建（agent-lite/build/agent-lite.apk）", False,
              "先跑 bash agent-lite/build-mac.sh")

    ui = prepare(serial)
    base = sh(serial, f"grep -o 'base_url\">[^<]*' /data/data/{PKG}/shared_prefs/agent_prefs.xml")
    check("FM-1a", "设备模型端点指向本次 mock（18999）", "18999" in base,
          f"实际：{base.strip()!r}——端点不对时「任务完成」将无法归因")
    check("FM-1", "主界面控件可定位（etTask / btnRun）",
          "etTask" in ui and "btnRun" in ui, f"找到：{sorted(ui)}")
    if "etTask" not in ui or "btnRun" not in ui:
        return 1

    # ---------------- 第一次：正常执行 → 应入库 ----------------
    started = submit(serial, task, ui)
    if not started:
        print("  · 首次点击未启动任务，重试一次")
        time.sleep(2)
        started = submit(serial, task, ensure_main(serial))
    check("FM-2", "首次任务确实被提交给引擎", started, "两次尝试后仍未启动")
    if not started:
        return 1

    log1, saved = wait_for(serial, r"已写入操作记忆库|任务失败|任务未能完成")
    n1 = len(calls())
    check("FM-3", "首次任务：每一步成功操作已写入记忆库", saved,
          "未见到「已写入操作记忆库」——检查动作是否落在可复现白名单外")
    check("FM-4", "首次任务确实调用了模型（对照组）", n1 > 0, f"模型调用数={n1}")
    body = sh(serial, f"cat {FLOWMEM} 2>/dev/null")
    check("FM-5", "记忆库文件已落盘且含步骤", '"steps"' in body and '"fp"' in body,
          f"文件内容={body[:120]!r}")
    print(f"  · 记忆库内容：{body[:300]}")

    # ---------------- 第二次：同一句话 → 应直接复现 ----------------
    time.sleep(3)
    clear_logcat(serial)          # 只看本次提交之后的日志（否则会读到第一次的「任务启动」）
    ui2 = ensure_main(serial)
    started2 = submit(serial, task, ui2)
    check("FM-6", "第二次任务确实被提交", started2, "未启动")
    log2, hit = wait_for(serial, r"命中操作记忆库")
    log2b, done = wait_for(serial, r"已按记忆库直接完成")
    n2 = len(calls())

    check("FM-7", "第二次任务命中操作记忆库", hit, "未见到「命中操作记忆库」")
    check("FM-8", "第二次任务直接复现完成（未重新规划）", done,
          "未见到「已按记忆库直接完成」")
    # ★ 要害：零模型往返。若这里失败，说明"复现"其实又走了一遍模型——用户要的东西没做出来。
    check("FM-9", "第二次任务零模型往返（模型调用数不增加）", n2 == n1,
          f"第一次后={n1}，第二次后={n2}（多出 {n2 - n1} 次）")

    print()
    print("  ── 记忆库相关日志 ──")
    for line in (log1 + "\n" + log2 + "\n" + log2b).splitlines():
        if any(k in line for k in ("记忆库", "任务启动", "任务完成", "任务失败")):
            print("   ", line.strip()[:150])

    fails = sum(1 for r in RESULTS if not r[2])
    print()
    print("-" * 78)
    print(f"结果：{len(RESULTS) - fails} 通过 / {fails} 失败")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
