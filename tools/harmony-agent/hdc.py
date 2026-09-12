#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
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

    ArkXTest dumpLayout 属性键（真机实测清单，解析层全兼容）：
      text / content-desc（连字符形式）/ id / type / bounds("[x1,y1][x2,y2]")
      clickable / scrollable / enabled / visible / checked / selected（字符串 "true"/"false"）
    """
    if node is None or depth > max_depth or len(out) >= max_nodes:
        return
    attrs = node.get("attributes", node)
    text = attrs.get("text", "") or ""
    desc = (attrs.get("content-desc") or attrs.get("contentDescription")
            or attrs.get("accessibilityDescription") or attrs.get("description") or "")
    bounds = attrs.get("bounds", "") or node.get("bounds", "") or ""
    ctype = attrs.get("type", "") or ""
    clickable = _bool(attrs.get("clickable")) or _bool(attrs.get("longClickable")) \
        or _bool(attrs.get("long-clickable"))
    scrollable = _bool(attrs.get("scrollable"))
    enabled = attrs.get("enabled")
    visible = attrs.get("visible")
    if visible is not None and not _bool(visible):
        return   # 不可见节点不入表（鸿蒙树里常见占位节点）
    if enabled is not None and not _bool(enabled):
        clickable = False
    # bounds 格式兼容: "[x1,y1][x2,y2]"（ArkXTest）或 "x1,y1,x2,y2"
    m = re.search(r"\[?(-?\d+),(-?\d+)\]?\[?(-?\d+),(-?\d+)\]?", bounds)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        interesting = clickable or scrollable or bool(str(text).strip()) or bool(str(desc).strip())
        if interesting and x2 > x1 and y2 > y1:
            label = str(text).strip()
            if not label and not str(desc).strip() and clickable:
                # 鸿蒙可点容器自无文本 → 取子树文本（否则模型只见空容器）
                label = subtree_text(node)
            out.append({
                "index": len(out),
                "text": label[:60],
                "desc": str(desc).strip()[:60],
                "type": str(ctype)[:24],
                "clickable": bool(clickable),
                "scrollable": bool(scrollable),
                # 开关状态（Toggle/Switch/Checkbox/Radio 的选中态）——实机踩坑：
                # 不输出状态时，模型切换开关后无法判断是否成功 → 反复点同一节点直至步数耗尽
                "checked": _bool(attrs.get("checked")),
                "selected": _bool(attrs.get("selected")),
                "cx": (x1 + x2) // 2,
                "cy": (y1 + y2) // 2,
            })
    for child in node.get("children", []) or []:
        walk_nodes(child, out, depth + 1, max_depth, max_nodes)


def subtree_text(node, max_parts=3, limit=48):
    """聚合子树文本：鸿蒙可点容器（Row/Column）自身常无 text，标签在子 Text 节点。
    不聚合则模型只看到一堆空的可点容器，无法决策（实机踩坑）。"""
    parts = []

    def rec(n, depth=0):
        if n is None or depth > 6 or len(parts) >= max_parts:
            return
        a = n.get("attributes", n) if isinstance(n, dict) else {}
        t = (a.get("text") or a.get("description") or "").strip()
        if t and t not in parts:
            parts.append(t)
        for c in (n.get("children", []) if isinstance(n, dict) else []) or []:
            rec(c, depth + 1)

    rec(node)
    return " / ".join(parts[:max_parts])[:limit]


def _bool(v):
    """鸿蒙属性值是字符串 'true'/'false'，也兼容原生 bool。"""
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        return v.strip().lower() == "true"
    return bool(v)


LAST_WINDOW = {}   # 最近一次感知的窗口归属（bundle/ability/page），供 prompt 提供页面上下文


def _extract_window(node, found=None):
    """从控件树提取窗口归属（鸿蒙 dumpLayout 的 bundleName/abilityName/pagePath）。"""
    if found is None:
        found = {}
    if node is None or len(found) >= 3:
        return found
    attrs = node.get("attributes", node) if isinstance(node, dict) else {}
    for key, out in (("bundleName", "bundle"), ("abilityName", "ability"), ("pagePath", "page")):
        if not found.get(out):
            v = attrs.get(key)
            if v:
                found[out] = str(v)[:64]
    for c in (node.get("children", []) if isinstance(node, dict) else []) or []:
        _extract_window(c, found)
    return found


def collect_nodes(serial):
    """感知：返回扁平节点表（可点击优先排序）。同时记录窗口归属到 LAST_WINDOW。"""
    global LAST_WINDOW
    data = dump_layout(serial)
    LAST_WINDOW = _extract_window(data if isinstance(data, dict) else {"children": data})
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


def key_event(serial, name):
    """按键注入。实机核实（HarmonyOS 7.0 模拟器）：参数是**键名**（如 Back/Home），
    不是 Android 式的数字键码（keyEvent 2 / 2050 实测均无效）。"""
    out, rc = shell(serial, f"uitest uiInput keyEvent {name}", timeout=15, check=False)
    if rc != 0:
        raise HdcError(f"keyEvent {name} 失败: {out[:160]}")
    return out


def back(serial):
    return key_event(serial, "Back")


def home(serial):
    return key_event(serial, "Home")


# ---- 截图 ----

def screenshot(serial, local_path, remote="/data/local/tmp/agent_shot.jpeg"):
    """截图并拉回本地。实测（HarmonyOS 7.0 模拟器）：
    `snapshot_display [-i displayId] -f <file>`，**文件名后缀必须是 .jpeg**（.png 会报 invalid）。
    返回本地文件大小（>0 即成功）；失败抛 HdcError。
    """
    if not remote.endswith(".jpeg"):
        remote = remote.rsplit(".", 1)[0] + ".jpeg"   # 后缀强制 .jpeg（命令限制）
    sh(serial, f"rm -f {remote}", timeout=10, check=False)
    out, rc = shell(serial, f"snapshot_display -f {remote}", timeout=25, check=False)
    if rc != 0 or "invalid" in out.lower():
        raise HdcError(f"snapshot_display 失败: {out[:200]}")
    # 校验设备端文件确实生成（避免假阳性）
    sizeOut, _ = shell(serial, f"ls -la {remote}", timeout=10, check=False)
    m = re.search(r"\s(\d+)\s", sizeOut)
    if not m or int(m.group(1)) <= 0:
        raise HdcError(f"截图文件未生成或为空: {sizeOut[:120]}")
    r = subprocess.run([HDC] + (["-t", serial] if serial else []) + ["file", "recv", remote, local_path],
                       capture_output=True, text=True, timeout=40)
    if r.returncode != 0:
        raise HdcError(f"截图拉取失败: {(r.stdout + r.stderr)[:200]}")
    try:
        return os.path.getsize(local_path)
    except OSError as e:
        raise HdcError(f"本地截图文件缺失: {e}")


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
    """返回 (w, h)。实测通道（HarmonyOS 7.0 模拟器）：
    ① hidumper -s RenderService -a screen → "render resolution=1256x2760"（首选）
    ② dumpLayout 根节点 bounds 兜底。
    """
    try:
        out, _ = shell(serial, "hidumper -s RenderService -a screen", timeout=20, check=False)
        m = re.search(r"render resolution=(\d+)x(\d+)", out)
        if m:
            return int(m.group(1)), int(m.group(2))
        m = re.search(r"(\d{3,})x(\d{3,})", out)
        if m:
            return int(m.group(1)), int(m.group(2))
    except Exception:
        pass
    try:
        data = dump_layout(serial)
        root = data[0] if isinstance(data, list) and data else data
        attrs = root.get("attributes", root) if isinstance(root, dict) else {}
        m = re.search(r"\[(-?\d+),(-?\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if m:
            return int(m.group(3)), int(m.group(4))
    except Exception:
        pass
    return 0, 0
