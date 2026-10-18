#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""真机三功能一体 E2E：模糊语义 → 弹窗守则采集 → 操作记忆零往返复现。

    MOCK_PORT=18999 python3 tests/mock_llm_server.py &
    python3 tests/integrated_features_e2e.py [serial]

**定位（2026-10-02 审计 P1-4 治理）**：本脚本是 **root 专属增强诊断**，不是门禁，
也不得作为零 root 主链路（§12.1）或发布阻断的证据——`make check` 与发布门禁均
不包含本脚本。零 root 路径用 `tests/flow_memory_e2e_mock.py`（模拟器/免 root），
或应用内测试入口；root 只在**已 root 的诊断机**上用来直读私有目录加速断言：

  · /data/data 下的所有读写都经 `su -c`（命令全部来自脚本内固定白名单路径，
    不拼接模型或外部输入；serial 等参数经 shlex.quote 转义）；
  · 改 prefs 用 `cat >` 原地重定向（`sed -i` 会重建文件、属主变 root，App 读不了）；
  · 测试前备份 agent_prefs.xml 与 flowmem.json，结束后恢复并卸载夹具、还原输入法。

任务文本刻意用 ASCII（adb input text 限制）；弹窗锚点「跳过广告」与转译流程
仍走中文编译链。夹具是对话框主题，浮在 AgentLite 上而不遮挡其底部「运行」按钮。
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
from fuzzy_e2e_mock import CALL_LOG, PKG, clear_logcat, find_widgets, mock_alive, set_scene, sh  # noqa: E402
from flow_memory_e2e_mock import calls, dump_log, ensure_a11y, install, wait_for  # noqa: E402

APK = ROOT / "agent-lite" / "build" / "agent-lite.apk"
FIXTURE_APK = ROOT / ".check-tmp" / "popup-fixture" / "popup-fixture.apk"
FIXTURE_PKG = "com.dsh.fixture"
PREF = f"/data/data/{PKG}/shared_prefs/agent_prefs.xml"
FLOWMEM = f"/data/data/{PKG}/files/flowmem.json"
BAK = "/data/local/tmp"
TASK = "open settings after popup"
RESULTS: list[tuple[str, bool, str]] = []


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, ok, note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))


def root(serial: str, cmd: str, timeout: int = 30) -> str:
    """以 root 执行。模拟器（adb 已 root）直接跑；真机走 su -c '整条命令'。"""
    if serial.startswith("emulator"):
        return sh(serial, cmd, timeout=timeout)
    return sh(serial, "su -c " + shlex.quote(cmd), timeout=timeout)


HOST_BAK = "/tmp/integrated_prefs.host.bak"      # 主机侧备份：设备端 bak 会被下一次
HOST_FLOW = "/tmp/integrated_flowmem.host.bak"    # run 的备份覆盖，实测污染过用户配置


def prefs_backup(serial: str) -> None:
    """备份到**主机**，且**只在首次**写入。

    设备端 /data/local/tmp 备份会被后续 run 的「备份」步骤覆盖：一次 adb 掉线导致
    restore 未执行时，下一轮备份到的就是**已 patch 的 mock 配置**，还原即污染用户设备
    （实测发生过）。主机侧备份 + 不覆盖首次，才能保证永远握有真正的用户态。
    """
    if not os.path.exists(HOST_BAK):
        with open(HOST_BAK, "w", encoding="utf-8") as f:
            f.write(root(serial, f"cat {PREF} 2>/dev/null"))
    if not os.path.exists(HOST_FLOW):
        with open(HOST_FLOW, "w", encoding="utf-8") as f:
            f.write(root(serial, f"cat {FLOWMEM} 2>/dev/null"))


def prefs_patch(serial: str) -> None:
    """把模型端点指到 adb reverse 的本机 mock，并注入**假**凭据越过 UX-1 守卫。

    模拟器全新安装时 api_key 为空——凭据守卫（SPEC-UX-001 UX-1）会在引擎启动前
    就拦下任务，logcat 里连「任务启动」都没有。mock 不校验 key，写入 test-mock-key
    即可；真机上的真实凭据在恢复阶段由备份原样还原。
    """
    py = (
        f"sed 's|<string name=\"base_url\">[^<]*</string>|"
        f"<string name=\"base_url\">http://127.0.0.1:18999/v1</string>|' {PREF} | "
        f"sed 's|<string name=\"model\">[^<]*</string>|<string name=\"model\">mock-integrated</string>|' | "
        f"sed 's|<string name=\"planner_model\">[^<]*</string>|<string name=\"planner_model\">mock-integrated</string>|' | "
        f"sed 's|<string name=\"api_key\">[^<]*</string>|<string name=\"api_key\">test-mock-key</string>|' | "
        # 已登录时 LlmClient.forPrefs 会给 mock 客户端挂「同步服务网关」fallback
        # （LlmClient.java:205-211）：带截图的视觉请求经 adb reverse 偶发失败后
        # 自动切到网关，真实模型接管——实测 mock 只见转译请求、引擎却执行了
        # 真模型给的 setting wifi。清掉 sync_token/account_id 让 loggedIn()=false，
        # 请求**只**打 mock（恢复阶段由备份原样还原）。
        f"sed 's|<string name=\"sync_token\">[^<]*</string>|<string name=\"sync_token\"></string>|' | "
        f"sed 's|<string name=\"account_id\">[^<]*</string>|<string name=\"account_id\"></string>|' "
        f"> {BAK}/prefs.new && cat {BAK}/prefs.new > {PREF}"
    )
    root(serial, py)


def restore(serial: str, old_a11y: str = "") -> None:
    """还原用户 prefs/记忆（主机备份 → 设备），并清掉本轮测试产生的记忆。"""
    if os.path.exists(HOST_BAK) and os.path.getsize(HOST_BAK) > 0:
        subprocess.run(["adb", "-s", serial, "push", HOST_BAK, f"{BAK}/restore.xml"],
                       capture_output=True, timeout=60)
        root(serial, f"cat {BAK}/restore.xml > {PREF}")
    flow = ""
    if os.path.exists(HOST_FLOW):
        flow = open(HOST_FLOW, encoding="utf-8", errors="replace").read().strip()
    if flow:
        with open(HOST_FLOW + ".push", "w", encoding="utf-8") as f:
            f.write(flow)
        subprocess.run(["adb", "-s", serial, "push", HOST_FLOW + ".push", f"{BAK}/flowmem.xml"],
                       capture_output=True, timeout=60)
        root(serial, f"cat {BAK}/flowmem.xml > {FLOWMEM}")
    else:
        root(serial, f"rm -f {FLOWMEM}")
    root(serial, f"rm -f {BAK}/restore.xml {BAK}/flowmem.xml {BAK}/prefs.new; true")
    # attempt_task 会临时摘除无障碍绑定；结束后还原用户原值（日常机的核心能力）
    if old_a11y:
        sh(serial, f"settings put secure enabled_accessibility_services {old_a11y}", timeout=15)
        sh(serial, "settings put secure accessibility_enabled 1", timeout=15)


def flow_records(serial: str) -> list[dict]:
    try:
        return json.loads(root(serial, f"cat {FLOWMEM} 2>/dev/null") or "[]")
    except Exception:
        return []


def type_task(serial: str, task: str, ui: dict) -> None:
    """在 AgentLite 前台输入任务文本（不点运行）。

    注入路径铁律（实测）：`input text` 走 IME 通道，会被模拟器 Gboard **静默丢弃**
    （聚焦成功、布局滚动、字符不见）；必须用 `input keyboard text`（硬件键盘通道），
    且它按空格截断——逐词注入，词间补 keyevent 62（SPACE）。
    输入完用 BACK 收键盘（键盘在场时 BACK 只收键盘、不退 Activity）。
    """
    ex, ey = ui["etTask"]
    sh(serial, f"input tap {ex} {ey}")
    time.sleep(2)
    sh(serial, "input keyevent 123")
    for _ in range(80):
        sh(serial, "input keyevent 67")
    time.sleep(0.4)
    words = task.split()
    for i, w in enumerate(words):
        subprocess.run(["adb", "-s", serial, "shell", "input", "keyboard", "text", w],
                       capture_output=True, timeout=25)
        if i < len(words) - 1:
            sh(serial, "input keyevent 62")     # SPACE
        time.sleep(0.4)
    time.sleep(1.2)
    sh(serial, "input keyevent 4")     # 收软键盘
    time.sleep(2.0)


def top_activity(serial: str) -> str:
    """当前顶层 Activity（dumpsys 判定，零副作用）。

    uiautomator dump 会把无障碍服务挤掉线（A11yGuard 反复拉起，实测 62 次循环），
    服务自身 Bound 稳定——所以本脚本在 a11y 启用后绝不再 dump，坐标全用固定基准。
    """
    out = sh(serial, "dumpsys activity activities 2>/dev/null | grep topResumedActivity")
    m = re.search(r"u0 ([\w.]+)/(\.[\w.$]+|\w+) ", out)
    return f"{m.group(1)}/{m.group(2).lstrip('.')}" if m else ""


def screen_size(serial: str) -> tuple[int, int]:
    out = sh(serial, "wm size").replace("\r", "")
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def fixed_widgets(serial: str) -> dict:
    """基准坐标取自 1080×2400 实测（etTask/btnRun 同排），按实际分辨率等比缩放。"""
    w, h = screen_size(serial)
    sx, sy = w / 1080.0, h / 2400.0
    return {
        "etTask": (int(335 * sx), int(1477 * sy)),
        "btnRun": (int(832 * sx), int(1477 * sy)),
    }


def goto_main(serial: str) -> dict:
    """把 MainActivity 顶到前台（未登录冷启动会先跳登录页，用返回键关掉）。"""
    for _ in range(4):
        sh(serial, f"am start -n {PKG}/.MainActivity", timeout=20)
        time.sleep(3)
        top = top_activity(serial)
        if top.endswith(".MainActivity"):
            return fixed_widgets(serial)
        if "Login" in top:
            sh(serial, "input keyevent 4")     # 关登录页回主页
            time.sleep(2)
    return fixed_widgets(serial)


def widgets_after_a11y(serial: str) -> dict:
    """启用无障碍**之后**取一次真实坐标（横幅消失、布局复位），随即重新绑定服务。

    「无障碍未开启」横幅会把整页下压：启用前 dump 的坐标会整体偏移 ~100px。
    dump 会把服务挤掉线一次，取完坐标立即 ensure_a11y 重绑，之后不再 dump。
    """
    ensure_a11y(serial)
    time.sleep(5)                                   # 等 refreshA11y 清横幅、布局回弹
    ui = find_widgets(serial)                       # dump：真坐标
    ensure_a11y(serial)                             # dump 挤掉线 → 重新绑定
    time.sleep(8)                                   # 给系统重绑时间
    return ui


def assist_widgets(serial: str) -> dict:
    """AssistActivity（快捷指令弹层）的输入框与运行按钮坐标。

    Assist 是对话框主题：输入法弹出会把整个 dialog 上移（实测 1216×2640 位移 731px），
    BACK 收键盘后**精确回位**（不像主页 ScrollView 保持滚动），故用静息基准等比缩放。
    基准取自 1216×2640 真机实测。
    """
    w, h = screen_size(serial)
    sx, sy = w / 1216.0, h / 2640.0
    return {
        "etAssistInput": (int(608 * sx), int(2238 * sy)),
        "btnAssistRun": (int(866 * sx), int(2450 * sy)),
    }


def attempt_task(serial: str, task: str, with_fixture: bool) -> bool:
    """经 AssistActivity 提交任务（终版时序）。

    两个实测铁律：
    1. Assist 必须是 task 里**唯一**的 activity——MainActivity 在场时 uiautomator dump
       永远给主窗口树（active window 机制），而且 A11yGuard 修复循环会不断拉起
       MainActivity 盖掉弹层。所以每轮都 force-stop 后独启 Assist。
    2. 所有 dump 都要在 a11y 启用**之前**完成：dump 会把无障碍服务挤掉线，
       触发 A11yGuard L2 拉起 MainActivity → Assist 被 clearTop 销毁。
       a11y 在最后点「立即执行」前才启用，间隔越短越好。
    注入：`input keyboard text` 逐词 + keyevent 62（IME 通道会被输入法静默丢弃）。
    """
    clear_logcat(serial)
    # 先摘 a11y 绑定再 force-stop：否则 bound service 会被系统立刻重启，
    # A11yGuard 把 MainActivity 拉到 Assist 底下——active window 变成主窗口，
    # dump 与点击全部落错位（实测三轮全败的根因）。摘除后进程安静退出。
    sh(serial, "settings put secure enabled_accessibility_services \"\"", timeout=15)
    sh(serial, "settings put secure accessibility_enabled 0", timeout=15)
    sh(serial, f"am force-stop {PKG}; am force-stop {FIXTURE_PKG}", timeout=25)
    time.sleep(1.5)
    sh(serial, "input keyevent 3")                  # 桌面起步，杜绝 MainActivity 混入
    time.sleep(1.0)
    sh(serial, f"am start -n {PKG}/.AssistActivity", timeout=20)
    time.sleep(5)
    if "AssistActivity" not in top_activity(serial):
        print(f"  · Assist 未在前台（{top_activity(serial)}）")
        return False
    ui = assist_widgets(serial)                     # 静息坐标（a11y 未启用，无干扰）
    ax, ay = ui["etAssistInput"]
    # 输入全链合并为**一条** shell 命令：ColorOS 的 USB 调试在高频小命令下会静默
    # 丢命令（实测：DEL×80 逐条发时 BACK/tap 偶发丢失，键盘没收、点击落空）。
    parts = [f"input tap {ax} {ay}", "sleep 1.5", "input keyevent 123"]
    parts += ["input keyevent 67"] * 30             # 清残留（30 足够，任务文本短）
    for i, w in enumerate(task.split()):
        parts.append(f"input keyboard text {shlex.quote(w)}")
        if i < len(task.split()) - 1:
            parts.append("input keyevent 62")       # SPACE
        parts.append("sleep 0.3")
    # 不按 BACK：硬件键盘事件本身会让 ColorOS 自动收软键盘，多按一次反而关掉弹层
    sh(serial, " ; ".join(parts), timeout=90)
    time.sleep(3.0)
    # ---- 收键盘：dialog 有键盘时按钮被顶到屏幕中部，收起后精确回位（均实测）。
    # 不用 uiautomator 验证回位：Assist 的提示动画让 dump 永远等不到 idle
    # （ERROR: could not get idle state 连续复现）。改为**双坐标点击**兜底：
    # 先点回位坐标；未触发再点键盘态坐标——两个状态总有一个命中。----
    sh(serial, "input keyevent 4", timeout=15)
    time.sleep(3.0)
    ensure_a11y(serial)                             # 最后时刻才启用（引擎的感知依赖）
    if "AssistActivity" not in top_activity(serial):
        print(f"  · a11y 启用后 Assist 被顶掉（{top_activity(serial)}），重试")
        return False
    # 服务 bound ≠ 窗口枚举就绪：连接初期 getWindows() 只返回 SystemUI，引擎第 1 步
    # 的节点表会残缺（模型只能在状态栏里瞎点，弹窗关闭步无从识别）。等它热身。
    time.sleep(5.0)
    bx, by = ui["btnAssistRun"]                     # 回位态坐标（dialog 回位精确）
    kx, ky = bx, int(1719 * (by / 2450.0))          # 键盘态：按钮上移（等比缩放）
    submitted = False
    for cx, cy, tag in ((bx, by, "回位"), (kx, ky, "键盘")):
        for retry in range(3):                      # USB 注入偶发丢失：顶层没变就重发
            print(f"  · tap btnAssistRun({tag}) @ ({cx},{cy})")
            sh(serial, f"input tap {cx} {cy}")
            time.sleep(1.2)
            if "AssistActivity" not in top_activity(serial):
                submitted = True
                break
            time.sleep(2.8)
        if submitted:
            break                                   # 已跳转 MainActivity：点击生效
    if submitted and with_fixture:
        # 夹具必须在**引擎第 1 步感知之前**成为顶层窗口：提交前弹会被 MainActivity
        # NEW_TASK 盖掉，引擎只能看见状态栏（实测模型因此在状态栏里瞎点，弹窗关闭步
        # 自然无从识别）。提交成功后立刻补弹，抢在感知窗口（约 1.5s 后）前面。
        sh(serial, f"am start -n {FIXTURE_PKG}/.PopupFixtureActivity", timeout=20)
        time.sleep(1.0)
        win = sh(serial, "dumpsys window | grep -E 'mCurrentFocus|fixture' | head -6", timeout=20)
        print(f"  · 补弹夹具后窗口现场: {' / '.join(l.strip()[:70] for l in win.splitlines()[:3])}")
    time.sleep(9)
    if "任务启动" in dump_log(serial):
        return True
    print(f"  · Assist 提交后未见「任务启动」（顶层={top_activity(serial)}）")
    return False


def submit_under_popup(serial: str, task: str) -> bool:
    """三合一首次提交（弹窗在场）：最多重试 3 轮。"""
    for i in range(3):
        if attempt_task(serial, task, with_fixture=True):
            return True
        sh(serial, "input keyevent 4")              # 收掉可能的夹具/键盘残留
        time.sleep(1.5)
    return False


def submit_plain(serial: str, task: str) -> bool:
    """第二次提交（无弹窗）：最多重试 3 轮。"""
    for i in range(3):
        if attempt_task(serial, task, with_fixture=False):
            return True
        time.sleep(2)
    return False


def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else "3B15B401NPF00000"
    print("=" * 78)
    print(f"三功能一体真机 E2E（serial={serial}）")
    print("=" * 78)
    if serial.startswith("emulator"):
        # google_apis 镜像（userdebug）：adb root 后 shell 即 root，无需 su
        subprocess.run(["adb", "-s", serial, "root"], capture_output=True, timeout=30)
        time.sleep(2)
    check("INT-0", "本地假模型在线", mock_alive(), "先启动 tests/mock_llm_server.py")
    if not mock_alive():
        return 1
    if not FIXTURE_APK.exists():
        subprocess.run(["bash", str(ROOT / "tests" / "build_popup_fixture.sh")], cwd=ROOT, check=True)
    set_scene("integrated")
    try:
        os.remove(CALL_LOG)
    except OSError:
        pass

    had_mic = False
    suspended = ""
    old_ime = sh(serial, "settings get secure default_input_method").strip()
    old_a11y = sh(serial, "settings get secure enabled_accessibility_services").strip()
    # 真机上「连接至 Windows」(com.microsoft.appmanager) 会在 USB 接入时自己弹到前台，
    # 把提交到一半的 Assist/引擎顶掉（实测第二轮 tap 全打在它身上）。测试期间临时挂起，
    # 结束后 unsuspend 原样恢复——不卸载、不改数据。
    for noisy in ("com.microsoft.appmanager",):
        if f"package:{noisy}" in sh(serial, "pm list packages", timeout=25):
            sh(serial, f"pm suspend --user 0 {noisy}", timeout=25)
            suspended = noisy
    try:
        check("INT-1", "AgentLite APK 安装成功", install(serial))
        # 真机的 adb install 通道会被 ColorOS 包安装器挂起（实测 120s 超时无输出），
        # push + root pm install 即秒装；模拟器仍走 adb install。
        if serial.startswith("emulator"):
            r = subprocess.run(["adb", "-s", serial, "install", "-r", str(FIXTURE_APK)],
                               capture_output=True, text=True, timeout=120)
            ok2 = "Success" in (r.stdout or "")
            detail2 = (r.stdout + r.stderr).strip()[:200]
        else:
            subprocess.run(["adb", "-s", serial, "push", str(FIXTURE_APK),
                            "/data/local/tmp/pf.apk"], capture_output=True, timeout=60)
            out2 = root(serial, "pm install -r /data/local/tmp/pf.apk")
            ok2 = "Success" in out2
            detail2 = out2.strip()[:200]
        check("INT-2", "弹窗夹具 APK 安装成功", ok2, detail2)
        subprocess.run(["adb", "-s", serial, "reverse", "tcp:18999", "tcp:18999"],
                       check=True, capture_output=True)
        prefs_backup(serial)
        prefs_patch(serial)
        base = root(serial, f"grep -o 'base_url\">[^<]*' {PREF}")
        check("INT-3", "设备端点已指向本次 mock", "18999" in base, f"实际：{base.strip()!r}")
        root(serial, f"rm -f {FLOWMEM}")
        # 首次点「运行」会先申请通知权限（SPEC-UX-001 §5 渐进申请）——对话框会把
        # btnRun 的点击吞掉、任务不启动。测试环境预授权，聚焦被测行为本身。
        sh(serial, f"pm grant {PKG} android.permission.POST_NOTIFICATIONS 2>/dev/null; true")
        # AssistActivity 有 RECORD_AUDIO 时会自动开始录音：音量动画让 uiautomator
        # 永远等不到 idle（实测 ERROR: could not get idle state ×N）。测试期间撤销，
        # 结束后按原状态恢复。
        grant_state = sh(serial, f"dumpsys package {PKG} | grep -m1 'RECORD_AUDIO granted='", timeout=15)
        had_mic = "granted=true" in grant_state
        sh(serial, f"pm revoke {PKG} android.permission.RECORD_AUDIO 2>/dev/null; true")
        sh(serial, "settings put system font_scale 1.0")
        sh(serial, f"am force-stop {PKG}; am force-stop {FIXTURE_PKG}")
        time.sleep(2)

        # -------- 第一遍：弹窗在场，模型先关广告再开设置；广告步不得入库 --------
        started = submit_under_popup(serial, TASK)
        check("INT-4", "夹具浮层下任务确实启动", started)
        if not started:
            return 1
        log1, saved = wait_for(serial, r"已写入操作记忆库|任务失败|任务完成|任务已被用户停止", 60)
        check("INT-5", "结构化模糊语义转译成功", "流程转译成功" in log1)
        check("INT-6", "一次性广告步骤被识别且不入库", "操作记忆跳过一次性突发界面步骤" in log1)
        check("INT-7", "业务路径已写入操作记忆库", saved and "已写入操作记忆库" in log1)
        recs = flow_records(serial)
        rec = recs[-1] if recs else {}
        steps = rec.get("steps") or []
        check("INT-8", "记忆记录带转译骨架指纹", bool(rec.get("fp_skel")))
        check("INT-9", "记忆只含业务 app 步、无广告关闭步",
              len(steps) == 1 and steps[0].get("act") == "app"
              and "跳过" not in json.dumps(steps, ensure_ascii=False),
              json.dumps(steps, ensure_ascii=False)[:220])
        n1 = len(calls())
        check("INT-10", "首轮确实经过转译与主模型", n1 >= 3, f"calls={n1}")

        # -------- 第二遍：无弹窗，记忆直接复现，模型调用数一条不增 --------
        sh(serial, "input keyevent 3")            # 回桌面，模拟全新任务起点
        time.sleep(2)
        clear_logcat(serial)
        started2 = submit_plain(serial, TASK)
        check("INT-11", "第二次任务确实启动", started2)
        if not started2:
            return 1
        log2, hit = wait_for(serial, r"命中操作记忆库|任务失败", 90)
        log2b, done = wait_for(serial, r"已按记忆库直接完成|任务失败", 90)
        n2 = len(calls())
        check("INT-12", "第二次命中操作记忆库", hit and "命中操作记忆库" in log2)
        check("INT-13", "第二次由记忆直接完成", done and "已按记忆库直接完成" in log2b)
        check("INT-14", "第二次零模型往返", n2 == n1, f"first={n1}, second={n2}")
        recs2 = flow_records(serial)
        rec2 = recs2[-1] if recs2 else {}
        check("INT-15", "弹窗噪音未给记录增加失败计数", int(rec2.get("fails", -1)) == 0,
              json.dumps(rec2, ensure_ascii=False)[:220])

        print("\n  ── 三功能协同日志摘录 ──")
        for line in (log1 + "\n" + log2 + "\n" + log2b).splitlines():
            if any(k in line for k in ("流程转译", "操作记忆", "记忆库复现", "突发")):
                print("   ", line.strip()[:180])
    finally:
        # 每个恢复动作独立容错：一次 adb 掉线（USB 抖动，实测发生过）曾让第一条失败
        # 直接中断整个还原，把用户设备留在 mock 配置上。恢复链必须**逐条**尽力执行。
        def best_effort(label, fn):
            try:
                fn()
            except Exception as e:                      # noqa: BLE001
                print(f"  ! 还原步骤失败（{label}）：{str(e)[:100]}")

        if suspended:
            best_effort("取消挂起", lambda: sh(serial, f"pm unsuspend --user 0 {suspended}", timeout=25))
        if had_mic:
            best_effort("恢复录音权限",
                        lambda: sh(serial, f"pm grant {PKG} android.permission.RECORD_AUDIO 2>/dev/null; true"))
        best_effort("停止测试应用", lambda: sh(serial, f"am force-stop {PKG}; am force-stop {FIXTURE_PKG}"))
        best_effort("还原用户配置", lambda: restore(serial, old_a11y))
        best_effort("卸载测试夹具", lambda: sh(serial, f"pm uninstall {FIXTURE_PKG}"))
        if old_ime and old_ime not in ("", "null"):
            best_effort("还原输入法", lambda: sh(serial, f"ime set {shlex.quote(old_ime)}"))
        best_effort("撤销端口转发",
                    lambda: subprocess.run(["adb", "-s", serial, "reverse", "--remove", "tcp:18999"],
                                           capture_output=True))

    fails = sum(1 for _, ok, _ in RESULTS if not ok)
    print("-" * 78)
    print(f"结果：{len(RESULTS) - fails} 通过 / {fails} 失败")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
