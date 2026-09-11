#!/usr/bin/env python3
"""watch_check10.py — 手表修复后的 10 轮稳定性检查（OWW261）。

每轮 5 项：
  1) 无障碍服务：已绑定且无 crashed 标记
  2) 无障碍跳转：「系统无障碍」按钮 → 系统无障碍设置页 → 返回
  3) 主页 UI：极简主页关键控件齐全（tvStatus/etTask/btnMic/btnRun/btnConfig）
  4) 设置页：设置入口 → WatchSettingsActivity → 返回
  5) 端到端任务：adb auto_run → engine.log 出现「任务完成」

用法: python3 tests/watch_check10.py [serial] [rounds]
结果: tests/results-watch-check10.jsonl（append）
"""
import json
import os
import re
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.dsh.agentlite"
SVC_SHORT = "Agent Lite"
ENGINE_LOG = f"/sdcard/Android/data/{PKG}/files/engine.log"
UI_XML = "/sdcard/ui.xml"

TASK_POOL = ["go_home", "swipe_up", "open_weather", "swipe_down"]


def sh(serial, *args, timeout=40):
    r = subprocess.run([ADB, "-s", serial] + list(args), capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip(), r.returncode


def shell(serial, cmd, timeout=40):
    return sh(serial, "shell", cmd, timeout=timeout)


def resumed(serial):
    out, _ = shell(serial, "dumpsys activity activities | grep -m1 mResumedActivity")
    m = re.search(r"u0 ([^\s}]+)", out)
    return m.group(1) if m else "?"


def dump_ui(serial):
    shell(serial, f"rm -f {UI_XML}")
    shell(serial, "uiautomator dump " + UI_XML)
    out, _ = shell(serial, f"cat {UI_XML}")
    return out


def center_of(xml, rid):
    """返回控件中心坐标（属性顺序无关）。"""
    for node in re.finditer(r"<node[^>]*/?>", xml):
        n = node.group(0)
        if f"{PKG}:id/{rid}" in n:
            b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                if x2 > x1 and y2 > y1:
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def scroll_to_top(serial):
    """把当前页面滚回顶部（滚动位置会在检查项之间残留）。"""
    for _ in range(3):
        shell(serial, "input swipe 233 120 233 400 250")
        time.sleep(0.6)


def scroll_until(serial, rid, max_scrolls=6):
    """向下滚动查找控件，返回 (pos, xml)。"""
    xml = dump_ui(serial)
    pos = center_of(xml, rid)
    for _ in range(max_scrolls):
        if pos:
            return pos, xml
        shell(serial, "input swipe 233 330 233 110 300")
        time.sleep(1.4)
        xml = dump_ui(serial)
        pos = center_of(xml, rid)
    return pos, xml


def check_a11y(serial):
    out, _ = shell(serial, "dumpsys accessibility")
    bound = SVC_SHORT in out and "Bound services:{Service" in out
    m = re.search(r"Crashed services:\{([^}]*)\}", out)
    crashed = bool(m and m.group(1).strip())
    en, _ = shell(serial, "settings get secure accessibility_enabled")
    ok = bound and not crashed and en.strip() == "1"
    return ok, f"bound={bound} crashed={crashed} enabled={en.strip()}"


def check_a11y_nav(serial):
    """经设置页「无障碍服务」按钮验证跳转（主页横幅提示行仅在服务异常时出现）。"""
    shell(serial, f"am start -n {PKG}/.MainActivity")
    time.sleep(2)
    scroll_to_top(serial)
    pos, _ = scroll_until(serial, "btnConfig", max_scrolls=3)
    if not pos:
        return False, "设置入口不可见"
    shell(serial, f"input tap {pos[0]} {pos[1]}")
    time.sleep(3)
    if "WatchSettingsActivity" not in resumed(serial):
        shell(serial, "input keyevent 4")
        time.sleep(2)
        return False, "设置页未打开"
    pos, xml = scroll_until(serial, "btnA11y", max_scrolls=6)
    if not pos:
        shell(serial, "input keyevent 4")
        time.sleep(2)
        return False, "无障碍按钮不可见"
    shell(serial, f"input tap {pos[0]} {pos[1]}")
    time.sleep(3)
    fg = resumed(serial)
    shell(serial, "input keyevent 4")      # 无障碍页 → 设置页
    time.sleep(2)
    shell(serial, "input keyevent 4")      # 设置页 → 主页
    time.sleep(2)
    ok = "settings" in fg and "Accessibility" in fg
    return ok, f"前台={fg}"


def check_home_ui(serial):
    shell(serial, f"am start -n {PKG}/.MainActivity")
    time.sleep(2)
    scroll_to_top(serial)
    xml = dump_ui(serial)
    forbidden = [k for k in ("svLog", "ivScreen", "btnHistory", "btnMemory", "btnEco", "btnAuth",
                             "flScreen", "llConfig", "btnDebug")
                 if f"{PKG}:id/{k}" in xml]
    # 上半屏：顶栏状态 + 设置入口
    top_need = ["tvStatus", "btnConfig"]
    missing = [k for k in top_need if f"{PKG}:id/{k}" not in xml]
    # 下半屏：任务区（无障碍横幅占位时需滚动才可见）
    bottom_need = ["etTask", "btnMic", "btnRun"]
    if any(f"{PKG}:id/{k}" not in xml for k in bottom_need):
        shell(serial, "input swipe 233 350 233 120 400")
        time.sleep(2)
        xml2 = dump_ui(serial)
        missing += [k for k in bottom_need if f"{PKG}:id/{k}" not in xml2]
        for k in ("svLog", "ivScreen", "btnHistory", "btnMemory", "btnEco", "btnAuth"):
            if f"{PKG}:id/{k}" in xml2 and k not in forbidden:
                forbidden.append(k)
    ok = not missing and not forbidden
    return ok, f"缺={missing or '无'} 残留={set(forbidden) or '无'}"


def check_settings_nav(serial):
    shell(serial, f"am start -n {PKG}/.MainActivity")
    time.sleep(2)
    scroll_to_top(serial)
    pos, _ = scroll_until(serial, "btnConfig", max_scrolls=3)
    if not pos:
        return False, "设置入口不可见"
    shell(serial, f"input tap {pos[0]} {pos[1]}")
    time.sleep(3)
    fg = resumed(serial)
    ok_page = "WatchSettingsActivity" in fg
    shell(serial, "input keyevent 4")
    time.sleep(2)
    return ok_page, f"前台={fg}"


def check_task(serial, task, timeout=90):
    shell(serial, f"rm -f {ENGINE_LOG}")
    shell(serial, f"am start -n {PKG}/.MainActivity --es task {task} --ez auto_run true")
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(5)
        out, _ = shell(serial, f"tail -6 {ENGINE_LOG} 2>/dev/null")
        if "任务完成：" in out:
            m = re.search(r"任务完成：(.*)", out)
            return True, (m.group(1).strip()[:60] if m else "已完成")
        if "任务失败" in out:
            m = re.search(r"任务失败[—:：]*(.*)", out)
            return False, "FAIL: " + (m.group(1).strip()[:60] if m else "")
    return False, "timeout"


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else "7d304c5c"
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results-watch-check10.jsonl")
    print(f"=== 手表 {rounds} 轮检查 @ {serial} ===", flush=True)
    total_pass = total = 0
    for rnd in range(1, rounds + 1):
        task = TASK_POOL[(rnd - 1) % len(TASK_POOL)]
        print(f"\n----- 第 {rnd}/{rounds} 轮（任务 {task}）-----", flush=True)
        results = []
        for name, fn in (("服务状态", lambda: check_a11y(serial)),
                         ("无障碍跳转", lambda: check_a11y_nav(serial)),
                         ("主页UI", lambda: check_home_ui(serial)),
                         ("设置页跳转", lambda: check_settings_nav(serial)),
                         ("端到端任务", lambda: check_task(serial, task))):
            try:
                ok, detail = fn()
            except Exception as e:
                ok, detail = False, f"{type(e).__name__}: {e}"
            results.append({"check": name, "ok": ok, "detail": detail})
            total += 1
            total_pass += 1 if ok else 0
            print(f"  {'✅' if ok else '❌'} {name:<10} {detail}", flush=True)
        with open(out_path, "a") as f:
            f.write(json.dumps({"round": rnd, "task": task, "results": results},
                               ensure_ascii=False) + "\n")
    print(f"\n=== 总计 {total_pass}/{total} 项通过 ===")
    print(f"明细: {out_path}")


if __name__ == "__main__":
    main()
