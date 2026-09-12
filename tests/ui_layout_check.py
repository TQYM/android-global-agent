#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ui_layout_check.py — 界面几何体检：文字/控件是否越界、被裁、重叠

    python3 tests/ui_layout_check.py <dump.xml> [<dump.xml> ...]

对每个 uiautomator dump 检查四类问题（都是「文字渲染出去」的具体形态）：

  1. **越出父容器**：控件 bounds 超出其直接父容器 —— 文字会画到卡片/按钮外面；
  2. **越出屏幕**：bounds 超出屏幕宽高 —— 直接看不见或被系统裁掉；
  3. **被裁文字**：文字节点高度小于其字号所需行高（固定高度装不下），或宽度不足且为单行；
  4. **重叠**：两个都有文字且互不为祖先关系的控件矩形相交 —— 视觉上文字叠在一起。

退出码：0 = 无问题；1 = 有问题项。
"""
import re
import sys
import xml.etree.ElementTree as ET

# uiautomator 的 bounds 是**屏幕像素**，需要密度换算才能和字号比较；由调用方给出
DENSITY = 3.5
LINE_FACTOR = 1.25


def bounds(node):
    b = node.get("bounds")
    if not b:
        return None
    v = list(map(int, re.findall(r"-?\d+", b)))
    return v if len(v) == 4 else None


def ancestors(node, parents):
    """向上收集祖先节点 id。"""
    out = []
    cur = parents.get(id(node))
    while cur is not None:
        out.append(id(cur))
        cur = parents.get(id(cur))
    return out


def check(path):
    try:
        root = ET.fromstring(open(path, encoding="utf-8", errors="replace").read())
    except Exception as e:
        print("  ! 解析失败：%s" % e)
        return []
    parents = {}
    for p in root.iter("node"):
        for c in p:
            parents[id(c)] = p

    screen = None
    for n in root.iter("node"):
        b = bounds(n)
        if b and b[0] == 0 and b[1] == 0:
            screen = b
            break

    problems = []
    text_nodes = []
    for n in root.iter("node"):
        b = bounds(n)
        if not b:
            continue
        rid = n.get("resource-id", "").split("/")[-1]
        cls = n.get("class", "").split(".")[-1]
        txt = (n.get("text") or "").strip()
        if txt:
            text_nodes.append((n, b, rid, cls, txt))

        # ① 越出父容器（跳过屏幕根与全屏容器）
        p = parents.get(id(n))
        pb = bounds(p) if p is not None else None
        if pb and not (pb[0] == 0 and pb[1] == 0):
            if b[0] < pb[0] - 1 or b[1] < pb[1] - 1 or b[2] > pb[2] + 1 or b[3] > pb[3] + 1:
                problems.append(("越出父容器", rid or cls, txt[:24], b, pb))

        # ② 越出屏幕
        if screen:
            if b[0] < -1 or b[2] > screen[2] + 1:
                problems.append(("越出屏幕(横向)", rid or cls, txt[:24], b, screen))
            if b[3] > screen[3] + 1 and b[1] < screen[3]:
                problems.append(("越出屏幕(纵向)", rid or cls, txt[:24], b, screen))

    # ③ 被裁文字：高度不足以容纳一行（按字号无法得知时用「高度 < 1.25×字号」的保守判据）
    #    这里只报「有文字但高度小于 14dp」这种明显异常，避免误报
    for n, b, rid, cls, txt in text_nodes:
        h_dp = (b[3] - b[1]) / DENSITY
        if h_dp < 14:
            problems.append(("文字框过矮(疑似被裁)", rid or cls, txt[:24], b, None))

    # ④ 重叠（仅同层级文字控件）
    for i in range(len(text_nodes)):
        for j in range(i + 1, len(text_nodes)):
            n1, b1, r1, c1, t1 = text_nodes[i]
            n2, b2, r2, c2, t2 = text_nodes[j]
            if id(n1) in ancestors(n2, parents) or id(n2) in ancestors(n1, parents):
                continue
            if b1[0] < b2[2] - 2 and b2[0] < b1[2] - 2 and b1[1] < b2[3] - 2 and b2[1] < b1[3] - 2:
                problems.append(("文字重叠", "%s/%s" % (r1 or c1, r2 or c2),
                                 ("%s ↔ %s" % (t1[:12], t2[:12])), b1, b2))
    return problems


def main():
    global DENSITY
    import os
    DENSITY = float(os.environ.get("SCREEN_DENSITY", "3.5")) / 160.0
    files = sys.argv[1:]
    if not files:
        print("用法：ui_layout_check.py <dump.xml> ...")
        return 2
    total = 0
    for f in files:
        print("=" * 78)
        print("检查：%s" % f)
        print("=" * 78)
        ps = check(f)
        if not ps:
            print("  ✓ 未发现越界 / 被裁 / 重叠")
        for kind, who, txt, a, b in ps[:20]:
            print("  ✗ %-20s %-18s %-28s %s%s" % (kind, who[:18], txt, a,
                                                  ("  父/另一：%s" % b) if b else ""))
        if len(ps) > 20:
            print("  … 另有 %d 处" % (len(ps) - 20))
        total += len(ps)
    print("\n合计问题：%d 处" % total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
