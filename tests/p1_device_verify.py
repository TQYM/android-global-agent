#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""p1_device_verify.py — §17.11 五项 P1 修复的真机行为验证（root 诊断机）。

    MOCK_PORT=18999 python3 tests/mock_llm_server.py &
    python3 tests/p1_device_verify.py [serial] [阶段…]

定位与 tests/integrated_features_e2e.py 相同：**root 专属增强诊断**，不是门禁，
也不得作为零 root 主链路（§12.1）或发布阻断的证据。零 root 路径 =
`make check` 静态门禁 + 桌面 JVM 回归；本脚本提供 §17.11「待真机」项的行为级证据。

阶段（独立断言，可单跑）：
  A  mock 基线（tool_router=off）
     A1 flow 场景：入库 + 二次零往返（FlowMemory 原子写/过滤改动后的主功能回归）
     A2 openurl 场景：open_url 执行成功 → 整条**不入库** + flowmem.json 无新记录
  B  tool_router=on：m3invalid 场景
     B1 三个非法参数动作被「M3 参数校验拒绝」且无「第 N 步：执行」（设备零动作）
     B2 合法动作（key 4）放行执行；done 收尾；重开网关前后行为对照
  C  ask 三态
     C1 STOPPED：ask 悬窗期点「停止」→ 用户停止终态，不再伪装成回答
     C2 ANSWERED：对话框输入并回答 → 模型收到「用户回答：」→ done
     C3 TIMEOUT：静置 5 分钟 → 第 1 次暂停 + USER_TIMEOUT 结构化标签回灌 → 停止收尾
  D  旧库防线与损坏恢复
     D1 含 open_url 的旧记录不被命中（Log.w 跳过），任务回退模型规划
     D2 捏坏 flowmem.json → .corrupt 备份 + 按空库继续（任务不被拦死）
  E  还原用户态（prefs/flowmem/无障碍），必跑

退出码：0 = 全部 PASS；1 = 存在 FAIL。
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

from fuzzy_e2e_mock import PKG, clear_logcat, dump_log, ensure_main, mock_alive, set_scene, sh  # noqa: E402
from flow_memory_e2e_mock import calls, ensure_a11y, et_task_text, install, wait_for  # noqa: E402
from integrated_features_e2e import (  # noqa: E402
    FLOWMEM, HOST_BAK, HOST_FLOW, PREF, flow_records, prefs_backup, restore, root,
)

RESULTS: list[tuple[str, str, str]] = []   # (id, PASS|FAIL|SKIP, note)
TASK_A1 = "open settings app"
TASK_A2 = "open example link"
TASK_B = "m3 check task"
TASK_C = "ask confirm test"


def check(cid: str, desc: str, ok: bool, note: str = "") -> bool:
    RESULTS.append((cid, "PASS" if ok else "FAIL", note))
    print(f"  {'PASS' if ok else 'FAIL':4} [{cid}] {desc}" + (f" — {note}" if note and not ok else ""))
    return ok


def skip(cid: str, desc: str, note: str = "") -> None:
    """环境受限项：如实记为 SKIP，绝不冒充 PASS（验证纪律）。"""
    RESULTS.append((cid, "SKIP", note))
    print(f"  SKIP [{cid}] {desc}" + (f" — {note}" if note else ""))


# ---------------------------------------------------------------- 基础设施

def prefs_patch_full(serial: str, router: bool) -> None:
    """主机端拼**完整** prefs XML 直推设备（经 /data/local/tmp 中转 + cat > 原地写入）。

    为什么不用 integrated 的设备端 sed 链：实测本机 `adb shell su -c 'sed …'` 会把
    XML 属性引号剥掉一层（`name=base_url`）——SharedPreferences 解析失败后回退重建，
    注入键全部丢失，表现为「任务启动」都不出现（UX-1 凭据守卫拦截）。主机端生成
    完整文件再 push/cat，引号零损耗；router 参数同时落位，避免二次 patch 竞争。
    """
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        "    <boolean name=\"intro_shown\" value=\"true\" />\n"
        "    <boolean name=\"flow_memory\" value=\"true\" />\n"
        "    <boolean name=\"feature_tool_router\" value=\"" + ("true" if router else "false") + "\" />\n"
        "    <string name=\"base_url\">http://127.0.0.1:18999/v1</string>\n"
        "    <string name=\"model\">mock-p1</string>\n"
        "    <string name=\"planner_model\">mock-p1</string>\n"
        "    <string name=\"api_key\">test-mock-key</string>\n"
        "</map>\n"
    )
    root(serial, f"am force-stop {PKG}")
    device_write(serial, xml, PREF)


def prefs_set_router(serial: str, on: bool) -> None:
    """切换 feature_tool_router（同样走主机端完整 XML，理由见 prefs_patch_full）。"""
    prefs_patch_full(serial, on)


def device_write(serial: str, content: str, dest: str) -> None:
    """主机内容 → 设备文件（经 /data/local/tmp 中转 + cat > 原地写入，保属主）。"""
    tmp_host = "/tmp/p1_verify.push"
    with open(tmp_host, "w", encoding="utf-8") as f:
        f.write(content)
    subprocess.run(["adb", "-s", serial, "push", tmp_host, "/data/local/tmp/p1_verify.push"],
                   capture_output=True, timeout=60)
    root(serial, f"cat /data/local/tmp/p1_verify.push > {dest}")


def prep_keep_flowmem(serial: str) -> dict:
    """prepare 的变体：**不删 flowmem**（D 阶段需要保留植入记录），仅重启与定位控件。"""
    sh(serial, f"am force-stop {PKG}")
    ensure_a11y(serial)
    sh(serial, f"am start -n {PKG}/.MainActivity", timeout=20)
    time.sleep(7)
    ui = ensure_main(serial)
    for _ in range(3):
        if "etTask" in ui and "btnRun" in ui:
            break
        sh(serial, "input keyevent 4")
        time.sleep(1.5)
        ui = ensure_main(serial)
    return ui


def reset_calls(serial: str) -> None:
    subprocess.run(["adb", "-s", serial, "shell",
                    "rm -f /sdcard/ui.xml"], capture_output=True, timeout=20)
    clear_logcat(serial)
    try:
        import urllib.request
        req = urllib.request.Request(f"http://127.0.0.1:18999", method="DELETE")
        urllib.request.urlopen(req, timeout=3).read()
    except Exception:
        pass


def engine_call_count() -> int:
    return sum(1 for c in calls() if c.get("kind") == "engine")


def last_engine_user_texts() -> list[str]:
    """引擎调用的 user 侧文本（CALL_LOG 字段为 user，且被截断到 600 字符——
    长上下文里靠后的回灌可能被截掉，所以断言优先用 reply 证据）。"""
    return [c.get("user", "") for c in calls() if c.get("kind") == "engine"]


def engine_replies() -> list[str]:
    return [c.get("reply", "") for c in calls() if c.get("kind") == "engine"]


def find_text_button(serial: str, text: str):
    xml = sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
    m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find_dialog_edit(serial: str):
    xml = sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
    m = re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def type_words(serial: str, text: str) -> None:
    """`input text` 通道（IME），空格用 %s 转义——本验证机实测有效（bash-20 轮
    原版 submit 即此通道，任务真实跑通）；`input keyboard text` 逐词注入在本机
    ColorOS 输入法下会被丢弃（表现为「输入框仍为空」）。"""
    subprocess.run(["adb", "-s", serial, "shell", "input", "text", text.replace(" ", "%s")],
                   capture_output=True, timeout=25)


def submit_patient(serial: str, task: str, timeout: int = 45) -> bool:
    """提交任务并**耐心**等待「任务启动」（复用 flow_memory_e2e_mock.submit 的防坑逻辑，
    但把启动判定从 5 秒一次放宽到 45 秒轮询：实测 mock 冷启动 + a11y 重绑后，
    引擎首个模型往返可能超过 5 秒，紧窗口会把实际在跑的任务误判成没跑起来）。"""
    for attempt in range(3):
        clear_logcat(serial)
        ui = ensure_main(serial)
        if "etTask" not in ui or "btnRun" not in ui:
            time.sleep(2)
            continue
        if not ensure_a11y(serial):
            print("  · 无障碍未连上，等待重绑后重试")
            continue
        ex, ey = ui["etTask"]
        sh(serial, f"input tap {ex} {ey}")
        time.sleep(1.5)
        sh(serial, "input keyevent 123")
        for _ in range(80):
            sh(serial, "input keyevent 67")
        time.sleep(0.5)
        type_words(serial, task)
        time.sleep(0.8)
        sh(serial, "input keyevent 111")
        time.sleep(2.0)
        if not et_task_text(serial):
            print("  · 输入框仍为空，重试提交")
            continue
        ui2 = ensure_main(serial)
        bx, by = ui2.get("btnRun", (ui["btnRun"][0], ui["btnRun"][1]))
        sh(serial, f"input tap {bx} {by}")
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(3)
            if "任务启动" in dump_log(serial):
                return True
        print("  · 点击运行后 45 秒仍未见「任务启动」，重试提交")
    return False


def find_blue_overlay_button(serial: str):
    """截图色块定位悬浮窗「我已完成，交还 AI」按钮（浅蓝实心圆角矩形）。

    为什么不用 uiautomator：`OverlayController` 的卡片是 TYPE_APPLICATION_OVERLAY
    系统窗口，uiautomator 只枚举 Activity 窗口，dump 里根本没有这些节点（实测 dump
    只有 AlertDialog 的「回答/不执行」）。真机上这个按钮**是**用户唯一能点的「交还 AI」，
    所以验证必须靠截图。返回 (x, y) 或 None（无 PIL 时降级为人工）。
    """
    try:
        from PIL import Image
    except ImportError:
        return None
    png = "/tmp/p1_shot.png"
    with open(png, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"],
                       stdout=f, timeout=60)
    im = Image.open(png).convert("RGB")
    w, h = im.size
    px = im.load()
    hits = []
    for y in range(h // 2, h):          # 卡片在屏幕下半部，避开状态栏/标题的蓝色点缀
        n = 0
        for x in range(0, w, 2):
            r, g, b = px[x, y]
            if b > 200 and b - r > 25 and g > 150 and r > 110:
                n += 1
        if n > (w // 2) * 0.6:          # 整行 60% 以上是按钮蓝 → 命中按钮行
            hits.append(y)
    if not hits:
        return None
    yc = hits[len(hits) // 2]
    xs = [x for x in range(0, w, 2)
          if (lambda c: c[2] > 200 and c[2] - c[0] > 25 and c[1] > 150 and c[0] > 110)(px[x, yc])]
    return (xs[len(xs) // 2] if xs else w // 2, yc)


def wait_ask_dialog(serial: str, timeout: int = 90) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find_text_button(serial, "回答") and find_dialog_edit(serial):
            return True
        time.sleep(3)
    return False


# ---------------------------------------------------------------- 阶段 A

def stage_a(serial: str) -> None:
    print("== 阶段 A：mock 基线（tool_router=off） ==")
    prefs_patch_full(serial, router=False)

    # A1 flow 场景：入库 + 二次零往返
    set_scene("flow")
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    root(serial, f"rm -f {FLOWMEM}")
    sh(serial, f"am force-stop {PKG}")
    time.sleep(2)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_A1)
    log, okd = wait_for(serial, "任务完成", timeout=90)
    check("A1-1", "flow 首跑经模型规划完成", ok and okd)
    n1 = len(flow_records(serial))
    check("A1-2", "flow 首跑入库 1 条", n1 == 1, f"records={n1}")
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_A1)
    log, hit = wait_for(serial, "命中操作记忆库", timeout=90)
    zero_round = engine_call_count() == 0
    check("A1-3", "同款任务二次零模型往返（记忆库直接复现）", ok and hit and zero_round,
          f"engine_calls={engine_call_count()}")

    # A2 openurl 场景：执行成功但整条不入库
    set_scene("openurl")
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_A2)
    log, done = wait_for(serial, "任务完成", timeout=90)
    check("A2-1", "open_url 动作执行成功（任务完成）", ok and done)
    # 证据形态说明：captureStep 对 open_url 返回 null → recordable=false 并清空已采集步骤，
    # done 时**根本不进入库分支**（连「操作记忆未入库」都不打），所以否定证据要用
    # 「没有任何入库日志」+「库文件里没有 open_url 步骤」两条，而不是找某句文案。
    dump = root(serial, f"cat {FLOWMEM} 2>/dev/null")
    check("A2-2", "整条不入库：无任何写入日志，且库文件中不含 open_url 步骤",
          "已写入操作记忆库" not in log and "open_url" not in dump,
          f"hasWriteLog={'已写入操作记忆库' in log} urlInStore={'open_url' in dump}")
    n2 = len(flow_records(serial))
    check("A2-3", "flowmem.json 记录数不变（open_url 未落盘）", n2 == n1, f"records={n2}（先前 {n1}）")


# ---------------------------------------------------------------- 阶段 B

def stage_b(serial: str) -> None:
    print("== 阶段 B：tool_router=on 的 M3 执行前网关 ==")
    prefs_set_router(serial, True)

    # B1 基线（关网关）：三个非法动作在旧路径下的行为
    set_scene("m3invalid")
    prefs_set_router(serial, False)
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_B)
    log, done = wait_for(serial, "任务完成", timeout=120)
    exec_off = len(set(re.findall(r"第 (\d+) 步：执行", log)))
    check("B1-1", "基线（网关关）：序列走完且无 M3 拒绝日志", ok and done and "M3 参数校验拒绝" not in log)
    globals()["_B1_EXEC_OFF"] = exec_off

    # B2 网关开：非法动作被拒、合法动作放行
    set_scene("m3invalid")
    prefs_set_router(serial, True)
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_B)
    log, done = wait_for(serial, "任务完成", timeout=120)
    # logcat 的 main/crash 双缓冲会把同一条日志镜像两份（实测每项恰好 ×2）：
    # 统计前先按「拒绝序号+理由」去重，否则会把 3 条误判成 6 条。
    rejects = sorted(set(re.findall(r"M3 参数校验拒绝（第 (\d+) 次）：([^\n]+)", log)))
    execs = sorted(set(re.findall(r"第 \d+ 步：执行 ([^\n]+)", log)))
    exec_steps = sorted(set(re.findall(r"第 (\d+) 步：执行", log)))
    check("B2-1", "任务被网关引导至完成（不再卡死在非法参数）", ok and done)
    check("B2-2", "三个非法动作各被拒绝一次（越界/未知字段/缺参）", len(rejects) == 3,
          f"rejects={rejects}")
    codes = " ".join(r[1] for r in rejects)
    check("B2-3", "拒绝理由覆盖 OUT_OF_RANGE / UNKNOWN_PARAMETER / MISSING_REQUIRED",
          "OUT_OF_RANGE" in codes and "UNKNOWN_PARAMETER" in codes and "MISSING_REQUIRED" in codes,
          f"codes={codes}")
    # 基线对照（B1 的 exec_off）只能作参考：真机 logcat 缓冲会滚动，早期动作日志可能
    # 已被冲掉（实测基线读到 0）。真正的判据是**网关开时的执行步数**：4 个动作里只有
    # 第 4 个合法，所以设备动作必须恰好剩 1 个。
    exec_on = len(exec_steps)
    check("B2-4", "网关开时设备动作恰好只剩合法项（1 个）",
          exec_on == 1, f"exec_on={exec_on}（基线参考 exec_off={globals().get('_B1_EXEC_OFF')}）")
    check("B2-5", "放行的是合法 key 动作", any(e.startswith("key") for e in execs), f"execs={execs}")


# ---------------------------------------------------------------- 阶段 C

def stage_c(serial: str) -> None:
    print("== 阶段 C：ask 三态 ==")
    asked = "向用户提问"

    def no_exec_since_ask(log: str) -> bool:
        """最后一次提问之后不得再有设备动作（待确认操作保持未执行）。"""
        tail = log.rsplit(asked, 1)[-1] if asked in log else log
        return re.search(r"第 \d+ 步：执行", tail) is None

    # C1 SKIPPED（用户主动放弃确认：「不执行」按钮）
    set_scene("ask")
    reset_calls(serial)
    prep_keep_flowmem(serial)
    if submit_patient(serial, TASK_C) and wait_ask_dialog(serial):
        btn = find_text_button(serial, "不执行")
        if btn:
            sh(serial, f"input tap {btn[0]} {btn[1]}")
            log, stopped = wait_for(serial, "用户放弃了这次确认，任务已停止", timeout=60)
            check("C1-1", "放弃确认（「不执行」）→ 独立终态，任务停止", stopped)
            check("C1-2", "放弃确认不被谎报为 5 分钟超时", "5 分钟内回答" not in log,
                  "日志出现超时话术（旧缺陷回归）")
            check("C1-3", "放弃后待确认操作保持未执行", no_exec_since_ask(log))
        else:
            check("C1-1", "放弃确认（「不执行」）→ 独立终态，任务停止", False,
                  "「不执行」按钮未定位到")
    else:
        check("C1-1", "放弃确认（「不执行」）→ 独立终态，任务停止", False, "ask 对话框未出现")

    # C2 ANSWERED
    set_scene("ask")
    reset_calls(serial)
    prep_keep_flowmem(serial)
    if submit_patient(serial, TASK_C) and wait_ask_dialog(serial):
        edit = find_dialog_edit(serial)
        if edit:
            sh(serial, f"input tap {edit[0]} {edit[1]}")
            time.sleep(1.5)
            type_words(serial, "yes go on")
            time.sleep(1.0)
            sh(serial, "input keyevent 111")
            time.sleep(1.0)
            btn = find_text_button(serial, "回答")
            if btn:
                sh(serial, f"input tap {btn[0]} {btn[1]}")
                log, done = wait_for(serial, "任务完成", timeout=90)
                check("C2-1", "回答提交后任务完成", done)
                # mock 的 ask 场景只有**看到**「用户回答：」才会回 done；任务能完成 +
                # reply 命中专用回复 = 回答确实以「用户回答：」形态进了模型上下文。
                fed = any("已按用户回答完成" in r for r in engine_replies()) \
                    or any("用户回答：" in t for t in last_engine_user_texts())
                check("C2-2", "模型收到的上下文含「用户回答：」",
                      fed, f"replies={engine_replies()[-3:]}")
            else:
                check("C2-1", "回答提交后任务完成", False, "「回答」按钮未定位到")
        else:
            check("C2-1", "回答提交后任务完成", False, "对话框输入框未定位到")
    else:
        check("C2-1", "回答提交后任务完成", False, "ask 对话框未出现")

    # C3 TIMEOUT（真实 5 分钟静置）
    set_scene("ask")
    reset_calls(serial)
    prep_keep_flowmem(serial)
    if submit_patient(serial, TASK_C) and wait_ask_dialog(serial):
        print("  · 静置 5 分钟等待真实超时（请勿触碰设备）…")
        log, timed = wait_for(serial, "用户未在 5 分钟内回答确认问题（第 1 次）", timeout=360)
        check("C3-1", "5 分钟无回答 → 结构化 TIMEOUT（第 1 次）", timed)
        check("C3-2", "超时后任务暂停（enterPause 生效）",
              "敏感节点强制接管" in log or "任务暂停" in log)
        check("C3-3", "整个超时窗口内设备零动作（待确认操作未执行）", no_exec_since_ask(log))
        # 「交还 AI」按钮在系统悬浮窗（TYPE_APPLICATION_OVERLAY）上，uiautomator 只枚举
        # Activity 窗口，取不到坐标——这一步需要视力介入（截图定位），脚本如实记为受限。
        give_back = find_text_button(serial, "我已完成，交还 AI") or find_text_button(serial, "交还 AI")
        if give_back:
            sh(serial, f"input tap {give_back[0]} {give_back[1]}")
            time.sleep(8)
            tagged = any("USER_TIMEOUT" in t for t in last_engine_user_texts())
            check("C3-4", "交还后 USER_TIMEOUT 结构化标签回灌（非用户话语）", tagged)
        else:
            btn_overlay = find_blue_overlay_button(serial)
            if btn_overlay:
                sh(serial, f"input tap {btn_overlay[0]} {btn_overlay[1]}")
                log2, given = wait_for(serial, "用户已交还控制权，AI 继续执行", timeout=60)
                # 判据：mock 的 ask 场景只在 user 上下文含 USER_TIMEOUT 时才回
                # 「已收到超时事件」——任务以该摘要完成 = 标签确实以系统事件进了模型上下文。
                tagged = given and any("已收到超时事件" in r for r in engine_replies())
                check("C3-4", "交还后 USER_TIMEOUT 结构化标签回灌（非用户话语）", tagged,
                      f"given={given} replies={engine_replies()[-2:]}")
            else:
                skip("C3-4", "交还后 USER_TIMEOUT 结构化标签回灌（非用户话语）",
                     "无 PIL/未定位到悬浮窗按钮；人工复核路径：截图取浅蓝按钮质心后 input tap，"
                     "判据 = CALL_LOG 的 reply 出现「已收到超时事件」")
        # 收尾：本轮 ask 任务已由「交还 → done」或超时终止收场，重启干净收尾
        sh(serial, f"am force-stop {PKG}")
        time.sleep(2)
    else:
        check("C3-1", "5 分钟无回答 → 结构化 TIMEOUT（第 1 次）", False, "ask 对话框未出现")


# ---------------------------------------------------------------- 阶段 D

def stage_d(serial: str) -> None:
    print("== 阶段 D：旧库防线与损坏恢复 ==")

    # D1 含 open_url 的旧记录不被命中
    fp = os.environ.get("P1_FP", "")
    if not fp:
        check("D1-1", "旧记录不命中（含 open_url）", False, "缺少 P1_FP 环境变量（桌面指纹）")
        return
    now = int(time.time() * 1000)
    rec = {
        "v": 1, "fp": fp, "fp_skel": "", "task": TASK_A2, "normalized": "",
        "intent": "", "app": "", "created": now, "last_used": now,
        "hits": 0, "oks": 1, "fails": 0,
        "steps": [{"act": "open_url", "text": "https://example.com/", "px": 0.5, "py": 0.5}],
    }
    device_write(serial, json.dumps([rec], ensure_ascii=False), FLOWMEM)
    set_scene("openurl")
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_A2)
    log, done = wait_for(serial, "任务完成", timeout=90)
    check("D1-1", "含 open_url 旧记录不命中（无「命中操作记忆库」）",
          done and "命中操作记忆库" not in log)
    check("D1-2", "任务回退模型规划完成（openurl 场景走满）",
          done and engine_call_count() >= 1, f"engine_calls={engine_call_count()}")
    full = subprocess.run(["adb", "-s", serial, "logcat", "-d"],
                          capture_output=True, text=True, timeout=30).stdout or ""
    check("D1-3", "加载侧留下跳过日志（FlowMemory Log.w）", "跳过含 open_url" in full)

    # D2 损坏恢复
    root(serial, f"rm -f {FLOWMEM}")
    root(serial, f"rm -f {FLOWMEM}.corrupt {FLOWMEM}.tmp")
    device_write(serial, '{"broken json!!', FLOWMEM)
    set_scene("flow")
    reset_calls(serial)
    ui = prep_keep_flowmem(serial)
    ok = submit_patient(serial, TASK_A1)
    log, done = wait_for(serial, "任务完成", timeout=90)
    check("D2-1", "损坏库不拦任务（按空库继续并完成）", ok and done)
    corrupt_exists = root(serial, f"ls {FLOWMEM}.corrupt 2>/dev/null && echo YES || echo NO").strip().endswith("YES")
    check("D2-2", "损坏现场已备份为 flowmem.json.corrupt", corrupt_exists)
    n = len(flow_records(serial))
    check("D2-3", "新库重新可写（flow 场景入库 1 条）", n == 1, f"records={n}")


# ---------------------------------------------------------------- 主流程

def main() -> int:
    serial = sys.argv[1] if len(sys.argv) > 1 else ""
    stages = [a for a in sys.argv[2:]] or list("ABCDE")
    if not serial:
        devs = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        serial = next((l.split()[0] for l in devs.splitlines()
                       if l.endswith("device") and "List" not in l), "")
    if not serial:
        print("未找到在线设备")
        return 1
    if not mock_alive():
        print("mock server 未就绪：先 MOCK_PORT=18999 python3 tests/mock_llm_server.py &")
        return 1

    # 备份纪律（本轮踩过）：**绝不**把测试态当用户态备份。上一轮如果没还原干净，
    # 设备 prefs 里会留着 mock 端点，此时再「备份」就会把测试配置固化成还原源，
    # 一轮轮套娃下去（实测三份备份全是 mock 态，最后分不清哪个是真实用户态）。
    # 规则：已有备份就复用；要新建备份，先确认 prefs 不含任何测试痕迹。
    if not os.path.exists(HOST_BAK):
        cur = root(serial, f"cat {PREF} 2>/dev/null")
        if "127.0.0.1" in cur or "test-mock-key" in cur:
            print("  ! 当前 prefs 含 mock 端点（上一轮未还原干净）：拒绝建立备份。")
            print("    请先手工把设备恢复为真实用户态，或显式指定还原源后重跑。")
            return 1
        prefs_backup(serial)
    old_a11y = sh(serial, "settings get secure enabled_accessibility_services").strip()
    subprocess.run(["adb", "-s", serial, "reverse", "tcp:18999", "tcp:18999"],
                   capture_output=True, timeout=30)

    install(serial)
    try:
        if "A" in stages:
            stage_a(serial)
        if "B" in stages:
            stage_b(serial)
        if "C" in stages:
            stage_c(serial)
        if "D" in stages:
            stage_d(serial)
    finally:
        if "E" in stages:
            print("== 阶段 E：还原用户态 ==")
            restore(serial, old_a11y)
            root(serial, f"rm -f {FLOWMEM}.tmp {FLOWMEM}.corrupt /data/local/tmp/prefs.new /data/local/tmp/p1_verify.push; true")
            subprocess.run(["adb", "-s", serial, "reverse", "--remove", "tcp:18999"],
                           capture_output=True, timeout=30)
            print("  已还原 prefs/flowmem/无障碍绑定，移除 adb reverse")

    print("=" * 72)
    print("§17.11 P1 真机行为验证")
    print("=" * 72)
    fails = 0
    for cid, status, note in RESULTS:
        print(f"  {status:4} [{cid}]" + (f" — {note}" if note and status != "PASS" else ""))
        fails += 1 if status == "FAIL" else 0
    print("-" * 72)
    np_ = sum(1 for r in RESULTS if r[1] == "PASS")
    sk = sum(1 for r in RESULTS if r[1] == "SKIP")
    print(f"结果：{np_} 通过 / {fails} 失败 / {sk} 需人工")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
