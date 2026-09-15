#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""watch_ui.py — 小屏（手表）UI 原语：按 resource-id 定位、点击、输入、滚动到位。

    python3 tests/watch_ui.py dump          # 打印当前界面节点（手表）
    python3 tests/watch_ui.py tap btnAccount
    python3 tests/watch_ui.py login         # 用 /tmp 下的测试账号登录手表

为什么要这个工具（2026-09-15 实测教训）：466x466 的手表上，scroll 之后所有元素坐标都会变，
`watch_e2e.sh` 里写死的 `input tap 362 136` / `233 452` 一旦滚动状态不同就点到别处，
甚至触发系统「上滑返回」把手势吃掉。按 id 实时定位 + 每步 dump 校验，才能稳定复现。

坐标搜索顺序：resource-id → text 前缀。找不到时返回 None，由调用方决定是否滚动后再试。
"""

from __future__ import annotations

import re
import subprocess
import sys
import time

WATCH = "7d304c5c"          # OPPO OWW261（466x466）
SCREEN_H = 466              # 手表屏幕高度，用于避开底部系统手势区
PKG = "com.dsh.agentlite"
ACTIVITY = f"{PKG}/.MainActivity"
DUMP_XML = "/sdcard/_watch_ui.xml"
#: 滑动起止点避开屏幕边缘（手表边缘是系统手势区，会被「上滑返回」吃掉）
SWIPE_X = 233
SWIPE_FROM_Y = 380
SWIPE_TO_Y = 180


def sh(*args: str, serial: str = WATCH, timeout: int = 30) -> str:
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def dump(serial: str = WATCH) -> tuple[dict, dict]:
    """返回 (按 id 索引, 按文本索引)，值为 (cx, cy, (x1, y1, x2, y2))。"""
    sh("shell", f"uiautomator dump {DUMP_XML} >/dev/null 2>&1", serial=serial)
    xml = sh("shell", f"cat {DUMP_XML}", serial=serial)
    by_id: dict[str, tuple] = {}
    by_text: dict[str, tuple] = {}
    for n in re.findall(r"<node[^>]*>", xml):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        hit = ((x1 + x2) // 2, (y1 + y2) // 2, (x1, y1, x2, y2))
        rid = re.search(r'resource-id="([^"]*)"', n)
        if rid and rid.group(1):
            by_id[rid.group(1).split("/")[-1]] = hit
        txt = re.search(r'text="([^"]*)"', n)
        if txt and txt.group(1).strip():
            by_text.setdefault(txt.group(1).strip(), hit)
    return by_id, by_text


def _locate(rid: str, serial: str = WATCH, scrolls: int = 0) -> tuple | None:
    """按 id（或文本）定位，必要时向上滚动；返回 (cx, cy, bounds)。"""
    hit = None
    for attempt in range(scrolls + 1):
        by_id, by_text = dump(serial)
        hit = by_id.get(rid) or by_text.get(rid)
        if hit or attempt == scrolls:
            break
        scroll(serial)
    return hit


def find(rid: str, serial: str = WATCH, scrolls: int = 0) -> tuple[int, int] | None:
    hit = _locate(rid, serial, scrolls)
    return (hit[0], hit[1]) if hit else None


def tap(rid: str, serial: str = WATCH, scrolls: int = 0) -> bool:
    """按 id（或文本）点击；scrolls>0 时先向上滚动最多 N 次直到出现。

    小屏上按钮常被挤到屏幕最底部（`btnAccount` 实测 y=441/466），那里是系统手势区——
    直接点会被系统吃掉（表现为「点了没反应」或直接返回）。所以把落点 clamp 进按钮内部的安全区。
    """
    hit = _locate(rid, serial, scrolls)
    if not hit:
        return False
    cx, cy, (x1, y1, x2, y2) = hit
    ty = min(max(cy, y1 + 6), y2 - 6)
    ty = min(max(ty, 56), SCREEN_H - 46)
    if not (y1 <= ty <= y2):
        ty = y1 + 6 if y1 + 6 >= 56 else y2 - 6
    sh("shell", "input", "tap", str(cx), str(ty), serial=serial)
    time.sleep(0.6)
    return True


def scroll(serial: str = WATCH, times: int = 1) -> None:
    for _ in range(times):
        sh("shell", "input", "swipe", str(SWIPE_X), str(SWIPE_FROM_Y), str(SWIPE_X), str(SWIPE_TO_Y), "300",
           serial=serial)
        time.sleep(0.7)


def back(serial: str = WATCH) -> None:
    sh("shell", "input", "keyevent", "4", serial=serial)
    time.sleep(1.5)


def type_into(rid: str, text: str, serial: str = WATCH, clear: bool = True, scrolls: int = 0) -> bool:
    """聚焦输入框并输入（先清空：MOVE_END + 回退 40 次，小屏上够用）。"""
    if not tap(rid, serial, scrolls):
        return False
    time.sleep(0.8)
    if clear:
        sh("shell", "input", "keyevent", "123", serial=serial)
        for _ in range(40):
            sh("shell", "input", "keyevent", "67", serial=serial)
    sh("shell", "input", "text", text.replace(" ", "%s"), serial=serial)
    time.sleep(0.8)
    return True


def root_of(serial: str = WATCH) -> str:
    """当前页面的根布局 id（rootLogin / rootSettings / rootLayout），用于判断是否被手势弹走。"""
    by_id, _ = dump(serial)
    for key in ("rootLogin", "rootSettings", "rootLayout"):
        if key in by_id:
            return key
    return ""


def open_settings(serial: str = WATCH) -> str:
    """进入设置页；失败则重启 App 再试一次。返回根布局 id。"""
    for attempt in range(2):
        if root_of(serial) == "rootLayout":
            tap("btnConfig", serial)
            time.sleep(2)
        if root_of(serial) == "rootSettings":
            return "rootSettings"
        sh("shell", "am", "force-stop", PKG, serial=serial)
        time.sleep(1)
        sh("shell", "am", "start", "-n", ACTIVITY, serial=serial)
        time.sleep(3.5)
    return root_of(serial)


def text_of(rid: str, serial: str = WATCH) -> str:
    sh("shell", f"uiautomator dump {DUMP_XML} >/dev/null 2>&1", serial=serial)
    xml = sh("shell", f"cat {DUMP_XML}", serial=serial)
    for n in re.findall(r"<node[^>]*>", xml):
        r = re.search(r'resource-id="([^"]*)"', n)
        if r and r.group(1).split("/")[-1] == rid:
            t = re.search(r'text="([^"]*)"', n)
            return t.group(1) if t else ""
    return ""


# --------------------------------------------------------------------- 具体流程

def login(name: str, key: str, serial: str = WATCH) -> bool:
    """手表登录：设置 → 账号 → 填账号名/密钥 → 登录。返回是否提交成功。"""
    if open_settings(serial) != "rootSettings":
        print("  ✗ 进不了设置页")
        return False
    if not tap("btnAccount", serial, scrolls=5) or root_of(serial) != "rootLogin":
        print("  ✗ 进不了登录页（账号按钮未命中）")
        return False
    time.sleep(1.5)
    if not type_into("etName", name, serial, scrolls=3):
        print("  ✗ 找不到账号名输入框（etName）")
        return False
    if not type_into("etKey", key, serial, scrolls=3):
        print("  ✗ 找不到密钥输入框（etKey）")
        return False
    if not tap("btnLogin", serial, scrolls=3):
        print("  ✗ 找不到登录按钮（btnLogin）")
        return False
    time.sleep(6)          # 登录 + 同步首次拉取
    return True


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]
    serial = sys.argv[3] if len(sys.argv) > 3 else WATCH
    if cmd == "dump":
        by_id, by_text = dump(serial)
        for rid, hit in sorted(by_id.items()):
            print(f"{rid:20} {hit[0]:>4},{hit[1]:<4} {hit[2]}")
        return 0
    if cmd == "tap":
        ok = tap(sys.argv[2], serial, scrolls=4)
        print("✓ 已点击" if ok else "✗ 未找到", sys.argv[2])
        return 0 if ok else 1
    if cmd == "login":
        name = open("/tmp/.al_test_name").read().strip()
        key = open("/tmp/.al_test_key").read().strip()
        ok = login(name, key, serial)
        by_id, _ = dump(serial)
        cur = by_id.get("tvCurrent", by_id.get("tvWatchAccount"))
        print("登录提交:", ok, "| 当前账号行:", cur)
        return 0 if ok else 1
    print("未知命令:", cmd)
    return 2


if __name__ == "__main__":
    sys.exit(main())
