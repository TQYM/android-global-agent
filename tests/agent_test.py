#!/usr/bin/env python3
"""Agent 真机高强度测试 harness。
用法: python3 agent_test.py <tablet|oneplus> <round_no> <task1> [task2 ...]
每个任务: 拉起 App → 清空输入框 → 输入任务 → 点运行 → 轮询 engine.log 判定结果 → 回主页。
输出: 每任务一行 JSON 到 stdout（追加写入 results-<dev>.jsonl）。
"""
import json, re, subprocess, sys, time, os

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

DEVICES = {
    "tablet":   {"serial": "AAQLBB5C03000706", "pkg": "com.dsh.agentlite"},
    "oneplus":  {"serial": "3B15B401NPF00000", "pkg": "com.dsh.agentlite"},
}

def sh(serial, cmd, timeout=30):
    r = subprocess.run([ADB, "-s", serial, "shell", cmd],
                       capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()

def tap(serial, x, y):
    subprocess.run([ADB, "-s", serial, "shell", f"input tap {x} {y}"],
                   capture_output=True, timeout=15)

def engine_log(serial, pkg, lines=400):
    return sh(serial, f"tail -{lines} /sdcard/Android/data/{pkg}/files/engine.log 2>/dev/null")

def wait_result(serial, pkg, start_marker_ts, timeout=180):
    """轮询日志，直到出现任务终态。返回 (verdict, steps, seconds, tail)。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        log = engine_log(serial, pkg)
        if log != last:
            last = log
            m_start = None
            for m in re.finditer(r"(\d\d-\d\d \d\d:\d\d:\d\d) 任务启动", log):
                m_start = m
            if m_start:
                after = log[m_start.start():]
                end = re.search(
                    r"(\d\d-\d\d \d\d:\d\d:\d\d) (任务完成[^\n]*|达到最大步数[^\n]*|任务异常终止[^\n]*|LLM 调用失败[^\n]*|无障碍服务断开[^\n]*|多次重建失败[^\n]*)",
                    after)
                if end:
                    steps = [int(x) for x in re.findall(r"第 (\d+) 步：执行", after)]
                    t0 = to_sec(m_start.group(1)); t1 = to_sec(end.group(1))
                    return end.group(2).strip(), (max(steps) if steps else 0), t1 - t0, after[-300:]
        time.sleep(4)
    return "TIMEOUT", -1, timeout, last[-300:]

def to_sec(ts):
    m, d, rest = ts.split(" ")[0].split("-")[1], None, None
    h, mi, s = ts.split(" ")[1].split(":")
    return int(h) * 3600 + int(mi) * 60 + int(s)

def find_ui(serial, pkg):
    """用 uiautomator dump 找 etTask 与 btnRun 的中心坐标（免手量）。"""
    sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    xml = sh(serial, "cat /sdcard/ui.xml")
    out = {}
    for name, rid in [("etTask", "etTask"), ("btnRun", "btnRun")]:
        m = re.search(r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (pkg, rid), xml)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out[name] = ((x1 + x2) // 2, (y1 + y2) // 2, y2)
    return out

def run_task(serial, pkg, task):
    """执行一个任务，返回结果 dict。"""
    sh(serial, "input keyevent 3")          # 先回主页，清场
    time.sleep(1)
    sh(serial, f"am start -n {pkg}/.MainActivity", timeout=15)
    time.sleep(3)
    ui = find_ui(serial, pkg)
    if "etTask" not in ui or "btnRun" not in ui:
        # 偶发 uiautomator dump 失败（与 a11y 抢占） → 重拉一次再试
        sh(serial, f"am start -n {pkg}/.MainActivity", timeout=15)
        time.sleep(3)
        ui = find_ui(serial, pkg)
    if "etTask" not in ui or "btnRun" not in ui:
        return {"task": task, "verdict": "UI_NOT_FOUND", "steps": -1, "secs": 0}
    ex, ey, _ = ui["etTask"]
    # 清空输入框：按现有文本长度删除（残留会跨任务累积），清空后校验
    for _ in range(3):
        tap(serial, ex, ey)                     # 聚焦（弹 IME）
        time.sleep(1.2)
        sh(serial, "input keyevent 124")        # MOVE_END
        xml = sh(serial, "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && cat /sdcard/ui.xml")
        m = re.search(r'resource-id="%s:id/etTask"[^>]*?text="([^"]*)"' % pkg, xml)
        cur = len(m.group(1)) if m and m.group(1) else 0
        if cur == 0:
            break
        for _ in range(cur + 10):
            sh(serial, "input keyevent 67")     # DEL
    subprocess.run([ADB, "-s", serial, "shell", "input", "text", task.replace(" ", "%s")],
                   capture_output=True, timeout=20)
    time.sleep(1)
    # IME 弹起后布局上移，重新定位运行按钮
    ui2 = find_ui(serial, pkg)
    bx, by = ui2.get("btnRun", ui["btnRun"])[:2]
    tap(serial, bx, by)
    time.sleep(2)
    verdict, steps, secs, tail = wait_result(serial, pkg, None)
    sh(serial, "input keyevent 3")          # 回主页清场
    return {"task": task, "verdict": verdict, "steps": steps, "secs": secs, "tail": tail}

def main():
    dev, rnd = sys.argv[1], int(sys.argv[2])
    tasks = sys.argv[3:]
    d = DEVICES[dev]
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"results-{dev}.jsonl")
    with open(out_path, "a") as f:
        for t in tasks:
            print(f"[{dev} R{rnd}] RUN {t}", flush=True)
            try:
                r = run_task(d["serial"], d["pkg"], t)
            except Exception as e:
                r = {"task": t, "verdict": f"HARNESS_ERR:{e}", "steps": -1, "secs": 0}
            r.update({"dev": dev, "round": rnd})
            print(f"[{dev} R{rnd}] {r['verdict']} steps={r['steps']} secs={r['secs']} :: {t}", flush=True)
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
            f.flush()

if __name__ == "__main__":
    main()
