#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""fuzzy_e2e_mock.py — 在 Android 虚拟机上离线验证「模糊语义转译」接线。

    python3 tests/mock_llm_server.py &                      # 先起假模型服务
    python3 tests/fuzzy_e2e_mock.py [serial] [scene] [task]

为什么要有这个脚本：转译层的纯逻辑由 tests/FuzzyTranslatorTest.java（83 条断言）覆盖，
**接线**却只有真跑一次才看得见——提示词有没有发出去、max_tokens 够不够、
返回的流程有没有被解析并注入引擎、失败时能不能退回原路径。真机 + 真模型既不可重复、
又要花钱、还可能因为模型当天状态而误判；本脚本用本地假模型服务换取确定性。

关于任务文本用 ASCII：`adb shell input text` 不支持非 ASCII，而模拟器上剪贴板
注入通道（`cmd clipboard`）也不可用。任务文本的内容对结论无影响——假模型对任何输入
返回同一份 FlowSpec；归一化层是**纯函数**（零 android 依赖），其中文规则由桌面断言
覆盖，两端行为必然一致。

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
PKG = "com.dsh.agentlite"
SCENE_FILE = "/tmp/mock_llm_scene.txt"
CALL_LOG = "/tmp/mock_llm_calls.jsonl"
MOCK_URL = "http://127.0.0.1:18999/"

RESULTS: list[tuple[str, bool, str]] = []


def sh(serial: str, cmd: str, timeout: int = 25) -> str:
    # errors="replace"：设备侧输出含中文，按字节截断时可能切在多字节字符中间，
    # 严格解码会抛 UnicodeDecodeError 直接打断测试（实测踩过）
    r = subprocess.run(["adb", "-s", serial, "shell", cmd],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, f"{desc}", ok, note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))


def set_scene(scene: str) -> None:
    with open(SCENE_FILE, "w", encoding="utf-8") as f:
        f.write(scene + "\n")


def mock_alive() -> bool:
    import urllib.request
    try:
        with urllib.request.urlopen(MOCK_URL, timeout=3) as r:
            return json.loads(r.read().decode()).get("ok") is True
    except Exception:
        return False


def dump_xml(serial: str) -> str:
    sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return sh(serial, "cat /sdcard/ui.xml")


def find_by_id(xml: str, rid: str):
    m = re.search(r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                  % (re.escape(PKG), re.escape(rid)), xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find_widgets(serial: str) -> dict:
    xml = dump_xml(serial)
    out = {}
    for name in ("etTask", "btnRun"):
        c = find_by_id(xml, name)
        if c:
            out[name] = c
    return out


def tap_id(serial: str, rid: str) -> bool:
    c = find_by_id(dump_xml(serial), rid)
    if not c:
        return False
    sh(serial, f"input tap {c[0]} {c[1]}")
    return True


def ensure_main(serial: str) -> dict:
    """确保停在主界面。

    · 冷启动（尤其 force-stop 之后）App 可能先落在登录/引导页 —— 那里没有 etTask/btnRun，
      脚本会误判成「控件找不到」，故用「跳过」把它推回主界面。
    · **刻意不点 btnBack 兜底**：控件找不到也可能只是布局还没加载完，此时点返回会直接退出
      App，把「等一会儿就好」变成「真出错」（实测踩过）。
    """
    for _ in range(6):
        ui = find_widgets(serial)
        if "etTask" in ui and "btnRun" in ui:
            return ui
        if tap_id(serial, "btnSkip"):
            time.sleep(2.5)
            continue
        time.sleep(2)
    return find_widgets(serial)


def clear_logcat(serial: str) -> None:
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], capture_output=True, timeout=20)


def dump_log(serial: str) -> str:
    r = subprocess.run(["adb", "-s", serial, "logcat", "-d", "-v", "brief"],
                       capture_output=True, text=True, errors="replace", timeout=30)
    return (r.stdout or "") + (r.stderr or "")


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
    scene = sys.argv[2] if len(sys.argv) > 2 else "good"
    task = sys.argv[3] if len(sys.argv) > 3 else "open display settings"

    print("=" * 78)
    print(f"模糊语义转译 · 虚拟机端到端验证（serial={serial} scene={scene}）")
    print("=" * 78)

    check("E2E-0", "假模型服务在线", mock_alive(),
          "先启动 python3 tests/mock_llm_server.py")
    if not mock_alive():
        return 1

    set_scene(scene)
    try:
        os.remove(CALL_LOG)
    except Exception:
        pass
    clear_logcat(serial)

    # 清场 → 启动 → 定位控件
    # 必须让引擎处于空闲：上一个任务若还在循环里，engine.isRunning() 为真时点 btnRun
    # 会变成「停止」而不是「启动」——脚本会安静地验证一个根本没跑的任务。
    sh(serial, f"am force-stop {PKG}")
    # force-stop 会连带断开无障碍绑定，必须重新开启，否则任务第一步就报「无障碍服务断开」
    sh(serial, f"settings put secure enabled_accessibility_services {PKG}/{PKG}.AgentA11yService")
    sh(serial, "settings put secure accessibility_enabled 1")
    time.sleep(2)
    # 清掉可能压在栈顶的其它页面（未登录时点错会进 LoginActivity，那里没有 etTask/btnRun）
    for _ in range(3):
        sh(serial, "input keyevent 4")
        time.sleep(0.6)
    sh(serial, f"am start -n {PKG}/.MainActivity", timeout=20)
    time.sleep(5)
    ui = ensure_main(serial)
    check("E2E-1", "主界面控件可定位（etTask / btnRun）",
          "etTask" in ui and "btnRun" in ui, f"找到：{sorted(ui)}")
    if "etTask" not in ui or "btnRun" not in ui:
        return 1

    def submit(u: dict) -> bool:
        """输入任务并点运行；返回**是否真的启动了任务**（而不是盲信点击成功）。"""
        ex, ey = u["etTask"]
        bx, by = u["btnRun"]
        sh(serial, f"input tap {ex} {ey}")
        time.sleep(1.5)
        sh(serial, "input keyevent 123")          # MOVE_END
        for _ in range(80):
            sh(serial, "input keyevent 67")       # DEL（清残留文本）
        time.sleep(0.5)
        subprocess.run(["adb", "-s", serial, "shell", "input", "text",
                        task.replace(" ", "%s")], capture_output=True, timeout=25)
        time.sleep(1.0)
        # ★ 软键盘弹出会把整页上移，**先前 dump 的坐标全部失效**。
        # 实测踩过：点 btnRun 落在键盘上，任务从未启动而日志里一句报错都没有。
        # 故先用 ESC 收起键盘（顺带把输入法的候选组合文本提交进输入框），再重新定位。
        sh(serial, "input keyevent 111")
        time.sleep(2.0)
        u2 = find_widgets(serial)
        bx, by = u2.get("btnRun", (bx, by))
        sh(serial, f"input tap {bx} {by}")
        time.sleep(5)
        return "任务启动" in dump_log(serial)

    started = submit(ui)
    if not started:
        # 冷启动首轮的 UI 时序偶发抖动（无障碍重绑、布局未稳定）→ 重试一次
        print("  · 首次点击未启动任务，重试一次")
        time.sleep(2)
        started = submit(ensure_main(serial))
    print(f"  · 已提交任务：{task!r}｜引擎已启动={'是' if started else '否'}")
    check("E2E-1b", "任务确实被提交给引擎", started, "两次尝试后仍未启动")

    # 等「转译日志」出现即可（转译在秒级完成，不必等任务跑到终态——
    # 本脚本验证的是接线，跑完整任务是真机回归清单的事）
    deadline = time.time() + 60
    log = ""
    seen_translate = False
    while time.time() < deadline:
        time.sleep(2)
        log = dump_log(serial)
        if re.search(r"流程转译(成功|退化|未产出)", log):
            seen_translate = True
            time.sleep(6)          # 再等几步，让引擎真的动起来
            log = dump_log(serial)
            break
    m = re.search(r"任务完成：.*|任务失败.*|任务未能完成.*|任务超时.*", log)
    terminal = m.group(0).strip() if m else ""
    print(f"  · 转译日志：{'已捕获' if seen_translate else '未捕获'}｜终态：{terminal or '（任务仍在进行/已停止等待）'}")

    # ---------- 断言 ----------
    calls = []
    try:
        with open(CALL_LOG, encoding="utf-8") as f:
            calls = [json.loads(line) for line in f if line.strip()]
    except Exception:
        pass
    trans = [c for c in calls if c.get("kind") == "translate"]
    engine = [c for c in calls if c.get("kind") == "engine"]

    check("E2E-2", "转译请求确实发出（system 命中新提示词）", len(trans) >= 1,
          f"translate 调用数={len(trans)}")
    if trans:
        check("E2E-3", "转译请求的 token 预算已调高（>160，默认是 160）",
              (trans[0].get("max_tokens") or 0) > 160,
              f"max_tokens={trans[0].get('max_tokens')}")
        check("E2E-4", "转译请求带上了用户任务与上下文",
              "用户任务" in (trans[0].get("user") or ""),
              f"user 摘要={ (trans[0].get('user') or '')[:80]!r}")
    check("E2E-5", "引擎主循环正常收到动作请求（转译未阻断执行）", len(engine) >= 1,
          f"engine 调用数={len(engine)}")

    # ★ 最直接的接线证据：转译产物有没有真的进了引擎上下文
    user0 = (engine[0].get("user") if engine else "") or ""
    if scene == "good":
        check("E2E-6", "编译产物已注入引擎上下文（【任务理解】出现在主循环请求里）",
              "【任务理解】" in user0, "主循环请求里没有【任务理解】段")
        check("E2E-7", "结构化转译成功（日志含 intent/risk）",
              "流程转译成功" in log, "未在 logcat 找到「流程转译成功」")
        check("E2E-8", "引擎按产物开始执行动作（未因转译而卡死）",
              bool(re.search(r"第 \d+ 步：执行", log)) or bool(terminal),
              "未见动作执行日志")
    elif scene == "unsafe":
        # ★ 安全闸：缺确认点的资金流程必须被拒，且**不得**进入引擎上下文
        check("E2E-6", "危险流程未进入引擎上下文（没有【任务理解】段）",
              "【任务理解】" not in user0, "危险流程竟然被注入了引擎")
        check("E2E-7", "缺确认点的资金流程被拒（日志无「流程转译成功」）",
              "流程转译成功" not in log, "危险流程竟然通过了校验")
        check("E2E-8", "被拒后仍回退执行（任务未因转译失败而卡死）",
              len(engine) >= 1 and bool(re.search(r"第 \d+ 步", log)),
              f"engine 调用数={len(engine)}")
    elif scene == "prose":
        check("E2E-6", "散文退化产物仍被注入（零损失回退）",
              "【规划简报】" in user0, "散文没有进入引擎上下文")
        check("E2E-7", "日志记录了退化原因",
              "流程转译退化" in log, "未见到退化日志")
        check("E2E-8", "退化后引擎照常执行", len(engine) >= 1, "")
    elif scene == "broken":
        check("E2E-6", "截断的 JSON 被丢弃（未把半成品喂给引擎）",
              "【任务理解】" not in user0, "半成品竟然被注入了")
        check("E2E-7", "日志记录了丢弃原因",
              "流程转译未产出可用结果" in log, "未见到丢弃日志")
        check("E2E-8", "丢弃后引擎照常执行", len(engine) >= 1, "")

    # 关键日志留痕
    print()
    print("  ── 转译相关日志 ──")
    for line in log.splitlines():
        if any(k in line for k in ("流程转译", "规划模型", "任务启动", "任务完成", "任务失败")):
            print("   ", line.strip()[:150])

    fails = sum(1 for r in RESULTS if not r[2])
    print()
    print("-" * 78)
    print(f"结果：{len(RESULTS) - fails} 通过 / {fails} 失败")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
