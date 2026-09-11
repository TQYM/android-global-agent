#!/usr/bin/env python3
"""hdc.py — HarmonyOS HDC 命令封装层（T8 外挂式原型）。

所有命令集中于此，首日真机探测后如需修正命令名/参数只改这一处。
命令集基于 ArkXTest 官方文档与 Open-AutoGLM PR#221 实践整理，**全部待真机核实**。
零第三方依赖（stdlib only）。
"""
import base64
import json
import os
import re
import subprocess
import sys
import time

HDC = os.environ.get("HDC", "hdc")


class HdcError(Exception):
    pass


def sh(serial, args, timeout=30, check=True):
    """执行 hdc 命令，返回 stdout 文本。"""
    cmd = [HDC]
    if serial:
        cmd += ["-t", serial]
    cmd += args
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise HdcError(f"hdc 命令不存在：请安装 DevEco Studio/HDC 工具并加入 PATH，或设置 HDC 环境变量")
    except subprocess.TimeoutExpired:
        raise HdcError(f"hdc 超时: {' '.join(cmd)}")
    if check and r.returncode != 0:
        raise HdcError(f"hdc 失败({r.returncode}): {' '.join(cmd)}\n{r.stderr.strip()[:300]}")
    return r.stdout.strip()


def shell(serial, cmd, timeout=30, check=True):
    """hdc shell 包装。返回 (stdout, exit_code) 或抛 HdcError。"""
    try:
        r = subprocess.run([HDC] + (["-t", serial] if serial else []) + ["shell", cmd],
                           capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        raise HdcError("hdc 命令不存在")
    except subprocess.TimeoutExpired:
        raise HdcError(f"hdc shell 超时: {cmd}")
    if check and r.returncode != 0:
        raise HdcError(f"hdc shell 失败({r.returncode}): {cmd}\n{r.stdout.strip()[:200]}")
    return r.stdout.strip(), r.returncode


# ---- 控件树（感知） ----

DUMP_PATH = "/data/local/tmp/agent_ui.json"


def dump_layout(serial, timeout=30):
    """导出控件树 JSON。返回解析后的 dict（顶层 list 或 dict，以真机为准）。"""
    out, rc = shell(serial, f"uitest dumpLayout -p {DUMP_PATH}", timeout=timeout, check=False)
    if rc != 0 or "success" not in out.lower() and "success" not in out:
        # 部分版本输出 "UI hierarchy dumped to: <path>" 或直接无输出但文件已生成
        pass
    out2, rc2 = shell(serial, f"cat {DUMP_PATH}", timeout=timeout, check=False)
    if rc2 != 0 or not out2.strip():
        raise HdcError(f"dumpLayout 失败/文件不存在：stdout={out[:200]!r}")
    try:
        data = json.loads(out2)
    except json.JSONDecodeError:
        # 某些版本输出 JSON 带 BOM 或外层包装
        m = re.search(r"(\{.*\}|\[.*\])", out2, re.S)
        if not m:
            raise HdcError(f"dumpLayout 输出非 JSON：{out2[:200]!r}")
        data = json.loads(m.group(1))
    return data


def walk_nodes(node, out, depth=0, max_depth=60, max_nodes=400):
    """递归展平控件树 → 扁平节点表（对齐 agent-lite NodeInfo 语义）。

    鸿蒙控件树常见键：attributes.text / bounds / id / type / clickable（以真机 dumpLayout 输出为准，
    解析器对多种键名兼容，首日探测后固化）。
    """
    if node is None or depth > max_depth or len(out) >= max_nodes:
        return
    attrs = node.get("attributes", node)
    text = attrs.get("text", "") or ""
    desc = attrs.get("accessibilityDescription", "") or attrs.get("description", "") or ""
    bounds = node.get("bounds", "") or ""
    clickable = attrs.get("clickable", False) or attrs.get("enabled", False)
    scrollable = attrs.get("scrollable", False)
    # bounds 格式兼容: "x1,y1,x2,y2" 或 "[x1,y1][x2,y2]"
    m = re.search(r"\[?(-?\d+),(-?\d+)\]?\[?(-?\d+),(-?\d+)\]?", bounds)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        interesting = clickable or scrollable or bool(text.strip()) or bool(str(desc).strip())
        if interesting:
            out.append({
                "index": len(out),
                "text": str(text).strip()[:60],
                "desc": str(desc).strip()[:60],
                "clickable": bool(clickable),
                "scrollable": bool(scrollable),
                "cx": (x1 + x2) // 2,
                "cy": (y1 + y2) // 2,
            })
    for child in node.get("children", []) or []:
        walk_nodes(child, out, depth + 1, max_depth, max_nodes)


def collect_nodes(serial):
    """感知：返回扁平节点表（可点击优先排序）。"""
    data = dump_layout(serial)
    roots = data if isinstance(data, list) else data.get("children", data.get("nodes", []))
    if isinstance(roots, dict):
        roots = [roots]
    out = []
    for r in roots:
        walk_nodes(r, out)
    # 可点击 + 有文本的排前面（对齐 agent-lite toPrompt 策略）
    out.sort(key=lambda n: (not n["clickable"], not n["text"]))
    return out


# ---- 执行（注入） ----

def click(serial, x, y):
    shell(serial, f"uitest uiInput click {x} {y}", timeout=15)


def swipe(serial, x1, y1, x2, y2, speed=600):
    shell(serial, f"uitest uiInput swipe {x1} {y1} {x2} {y2} {speed}", timeout=20)


def input_text(serial, text):
    """文本注入。已知坑：中文/特殊字符报错率高（itying 案例）。
    降级链由调用方处理：先试直注，失败改拼音/事件序列/请用户手动。"""
    # 转义空格（uitest 参数切分坑）
    text = text.replace(" ", "%s")
    out, rc = shell(serial, f"uitest uiInput text {text}", timeout=20, check=False)
    if rc != 0:
        raise HdcError(f"uiInput text 失败: {out[:200]}")
    return out


def keyevent(serial, code):
    shell(serial, f"uitest uiInput keyEvent {code}", timeout=15)


def back(serial):
    shell(serial, "uitest uiInput keyEvent 2050", timeout=15, check=False)  # 2050=返回（待核实）


def home(serial):
    shell(serial, "uitest uiInput keyEvent 2049", timeout=15, check=False)  # 2049=主页（待核实）


# ---- 截图 ----

def screenshot(serial, local_path, remote="/data/local/tmp/agent_shot.png"):
    """截图（命令名待真机核实：snapshot_display / snapshot）。返回 True。"""
    out, rc = shell(serial, f"snapshot_display -f {remote}", timeout=20, check=False)
    if rc != 0:
        # 备选命令名
        out, rc = shell(serial, f"snapshot -f {remote}", timeout=20, check=False)
    if rc != 0:
        raise HdcError(f"截图失败: {out[:200]}")
    r = subprocess.run([HDC] + (["-t", serial] if serial else []) + ["file", "recv", remote, local_path],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise HdcError(f"截图拉取失败: {r.stderr[:200]}")
    return True


# ---- 设备与应用 ----

def list_targets():
    try:
        r = subprocess.run([HDC, "list", "targets"], capture_output=True, text=True, timeout=15)
    except FileNotFoundError:
        raise HdcError("hdc 命令不存在：请安装 DevEco Studio/HDC 工具并加入 PATH，或设置 HDC 环境变量")
    return [l.strip() for l in r.stdout.splitlines() if l.strip()]


def start_app(serial, bundle, ability):
    shell(serial, f"aa start -b {bundle} -a {ability}", timeout=20)


def screen_size(serial):
    """返回 (w, h)。经 dumpLayout 根节点 bounds 或 hidumper（首日探测确认）。"""
    try:
        out, _ = shell(serial, "hidumper -s WindowManagerService -a '-a'", timeout=20, check=False)
        m = re.search(r"(\d+)x(\d+)", out)
        if m:
            return int(m.group(1)), int(m.group(2))
    except Exception:
        pass
    data = dump_layout(serial)
    roots = data if isinstance(data, list) else data.get("children", [])
    if roots and isinstance(roots, dict):
        bounds = roots.get("bounds", "")
        m = re.search(r"\[?(-?\d+),(-?\d+)\]?\[?(\d+),(\d+)\]?", bounds)
        if m:
            return int(m.group(3)), int(m.group(4))
    return 0, 0
