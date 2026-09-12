#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ui_render_readability.py — 从**真实渲染的截图**测量可读性（像素级，不靠推断）。

    python3 tests/ui_render_readability.py <截图目录或成对文件前缀...>

输入是成对的 `<name>.png` + `<name>.xml`（后者是 `uiautomator dump` 的控件树）：

    adb shell screencap -p /sdcard/x.png && adb pull /sdcard/x.png x.png
    adb shell uiautomator dump /sdcard/x.xml && adb pull /sdcard/x.xml x.xml

对控件树里每个有文字的节点，在截图对应区域采样像素，估计**实际**的前景/背景色，
再算 WCAG 对比度。为什么必须实测：静态读 token 只能证明「设计意图」，而用户看到的是：

  · v31 的 Material You 动态取色会把整套色板换成系统给的颜色；
  · 控件样式（Filled/Tonal/Outlined）决定填充色，布局里看不到；
  · 系统深色模式、字体缩放、`SP` 与 `dp` 的差异都只在渲染时成立。

本脚本不依赖任何第三方库：PNG 先由 `sips` 转 BMP，再手工解析像素。
"""
import glob
import os
import re
import struct
import subprocess
import sys
import tempfile


# ─────────────────────────── BMP 读取 ───────────────────────────

DECLARED = None


def load_bmp(path):
    data = open(path, "rb").read()
    if data[:2] != b"BM":
        raise ValueError("不是 BMP：%s" % path)
    off = struct.unpack_from("<I", data, 10)[0]
    w, h = struct.unpack_from("<ii", data, 18)
    bpp = struct.unpack_from("<H", data, 28)[0]
    bottom_up = h > 0
    h = abs(h)
    stride = ((w * bpp // 8) + 3) // 4 * 4
    rows = []
    for y in range(h):
        src = h - 1 - y if bottom_up else y
        base = off + src * stride
        row = []
        for x in range(w):
            p = base + x * (bpp // 8)
            b, g, r = data[p], data[p + 1], data[p + 2]
            row.append((r, g, b))
        rows.append(row)
    return w, h, rows


def png_to_bmp(png, tmpdir):
    out = os.path.join(tmpdir, os.path.basename(png)[:-4] + ".bmp")
    subprocess.run(["sips", "-s", "format", "bmp", png, "--out", out],
                   capture_output=True)
    return out if os.path.exists(out) else None


# ─────────────────────────── 对比度 ───────────────────────────

def luminance(rgb):
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(a, b):
    l1, l2 = luminance(a), luminance(b)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def sample(rows, x1, y1, x2, y2, vertical_band=(0.30, 0.70), horizontal_band=(0.18, 0.82), inset=2):
    """区域内像素颜色直方图。

    **只取竖直中部带**（默认上下各去掉 25%）：MaterialButton 的控件框比视觉填充大，
    边角里是卡片背景；整框采样会把「按钮外的底色」当成前景，算出假的低对比
    （实测踩过：填充 #554937 的返回按钮被判成 1.83:1，实际字色 #e5e3e1 是 5.9:1）。
    中部带里的才是「文字 + 它脚下的底色」。同理，四周各留 18~30% 的边：圆角外的卡片底色
    会落在那个环带里，它比按钮填充更暗，会被误当成文字色（实测把 7.2:1 的按钮算成 1.83:1）。
    """
    hist = {}
    h = len(rows)
    ya = y1 + int((y2 - y1) * vertical_band[0])
    yb = y1 + int((y2 - y1) * vertical_band[1])
    xa = x1 + int((x2 - x1) * horizontal_band[0])
    xb = x1 + int((x2 - x1) * horizontal_band[1])
    for y in range(max(0, ya + inset), min(h, yb - inset)):
        row = rows[y]
        for x in range(max(0, xa + inset), min(len(row), xb - inset)):
            c = row[x]
            hist[c] = hist.get(c, 0) + 1
    return hist


def estimate_fg_bg(hist):
    """背景 = 出现最多的颜色；前景 = 与背景亮度差最大的那一侧里、按**像素数**占优的颜色。

    两个坑都踩过：
      · 按「不同颜色的个数」选极端侧 → 会把按钮外的一整片深色背景当成文字色（假低对比）；
      · 用固定占比阈值筛候选 → 小字号（如两位数字 "50"）的真实字色像素太少被筛掉，
        只剩抗锯齿中间色（假低对比）。这里改为：先拿掩码取「离背景足够远」的像素，
        再按这些像素的**亮度分位数**取字色，抗锯齿只影响边缘。
    """
    if not hist:
        return None, None
    total = sum(hist.values())
    bg = max(hist.items(), key=lambda kv: kv[1])[0]
    lbg = luminance(bg)
    mask = [(c, n) for c, n in hist.items() if abs(luminance(c) - lbg) > 0.05]
    glyph_px = sum(n for _, n in mask)
    if glyph_px < 12:
        return None, None                      # 没有可辨认的文字像素，不做判断
    mask.sort(key=lambda kv: luminance(kv[0]))
    # 依亮度排序后，取「像素更多的那一端」的分位色作为字色
    lo_px = sum(n for c, n in mask if luminance(c) < lbg)
    hi_px = glyph_px - lo_px
    if hi_px >= lo_px:
        # 文字比底色亮：取高亮端累计到一半像素处的颜色
        acc, target = 0, hi_px / 2.0
        fg = mask[-1][0]
        for c, n in reversed(mask):
            if luminance(c) <= lbg:
                continue
            acc += n
            fg = c
            if acc >= target:
                break
    else:
        acc, target = 0, lo_px / 2.0
        fg = mask[0][0]
        for c, n in mask:
            if luminance(c) >= lbg:
                continue
            acc += n
            fg = c
            if acc >= target:
                break
    return fg, bg


# ─────────────────────────── 控件树 ───────────────────────────

NODE_RE = re.compile(r"<node\b[^>]*>")
ATTR_RE = re.compile(r'([\w:-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_nodes(xml):
    """[(text, class, rid, x1,y1,x2,y2)]，只保留有文字的可见节点。

    注意：uiautomator 的属性顺序**不固定**，必须先把整个 `<node …>` 标签切出来再逐属性读。
    早期版本用「固定顺序」的正则，导致 id 与节点错配——把别的节点的 id 贴到这一行，
    于是报告里出现「按钮字色是深色」这种假结论（实测对比才知道是 5.9:1）。
    """
    out = []
    for m in NODE_RE.finditer(xml):
        attrs = dict(ATTR_RE.findall(m.group(0)))
        text = attrs.get("text", "")
        b = BOUNDS_RE.search(attrs.get("bounds", ""))
        if not text.strip() or not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        rid = attrs.get("resource-id", "").split("/")[-1]
        out.append((text, attrs.get("class", ""), rid, x1, y1, x2, y2))
    return out


def declared_texts():
    """源码里声明的 text/hint 原文集合——用来判断渲染出来的「…」是原文还是被截断。"""
    out = set()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for f in glob.glob(os.path.join(root, "agent-lite", "res", "layout", "*.xml")) + \
             glob.glob(os.path.join(root, "agent-lite", "res", "values", "*.xml")):
        try:
            txt = open(f, encoding="utf-8").read()
        except Exception:
            continue
        out |= set(re.findall(r'android:(?:text|hint)="([^"]+)"', txt))
        out |= set(re.findall(r'<string name="[^"]+">([^<]+)</string>', txt))
    return out


def decode_entities(t):
    return (t.replace("&#10;", " / ").replace("&amp;", "&").replace("&quot;", '"')
             .replace("&lt;", "<").replace("&gt;", ">"))


# ─────────────────────────── 主流程 ───────────────────────────

def analyze(png, xml, tmpdir):
    w, h, rows = None, None, None
    bmp = png_to_bmp(png, tmpdir)
    if bmp:
        w, h, rows = load_bmp(bmp)
    print("=" * 90)
    print("页面：%s   %s" % (os.path.basename(png), ("%dx%d" % (w, h)) if w else "（截图读取失败）"))
    print("=" * 90)
    if not rows:
        print("  ! 无法读取像素，跳过")
        return []

    nodes = parse_nodes(open(xml, encoding="utf-8", errors="replace").read())
    global DECLARED
    if DECLARED is None:
        DECLARED = declared_texts()
    print("  有文字的控件：%d 个%s" % (len(nodes), "" if w else "（像素不可用）"))
    print()
    findings = []
    print("  %-30s %8s %8s  %s" % ("文字（截断）", "对比度", "判定", "前景 on 背景 / 控件"))
    print("  " + "-" * 84)
    for text, cls, rid, x1, y1, x2, y2 in nodes:
        hist = sample(rows, x1, y1, x2, y2)
        fg, bg = estimate_fg_bg(hist)
        if not fg or not bg:
            continue
        cr = contrast(fg, bg)
        box_h = y2 - y1
        # 大字判定：材料设计里 ≥18sp 视为大字；这里用像素高度粗判（含行距，按 1.6 折算）
        big = box_h >= 1.6 * 18 * 2.625
        need = 3.0 if big else 4.5
        ok = cr >= need
        shown = decode_entities(text)
        # 「…」只有在源码里没有对应原文时才算截断（本项目多处 hint 原文本身就带省略号）
        trunc = ("…" in shown or "..." in shown) and not any(
            d.replace("&#10;", " ") == text or d.startswith(text.rstrip("…")) for d in DECLARED)
        mark = "✓" if ok else ("✗" if cr < 3.0 else "!")
        print("  %-30s %7.2f %8s  %s on %s  [%s]"
              % ("%s%s" % (shown[:26], " ⚠截断" if trunc else ""), cr, mark,
                 "#%02x%02x%02x" % fg, "#%02x%02x%02x" % bg, rid or cls.split(".")[-1]))
        if not ok:
            findings.append((cr, need, shown, rid, fg, bg))
    print()
    return findings


def main():
    args = sys.argv[1:]
    if not args:
        args = ["/tmp/rd_main", "/tmp/rd_settings", "/tmp/rd_login"]
    pairs = []
    for a in args:
        if os.path.isdir(a):
            for f in sorted(os.listdir(a)):
                if f.endswith(".png") and os.path.exists(os.path.join(a, f[:-4] + ".xml")):
                    pairs.append(os.path.join(a, f[:-4]))
        elif a.endswith(".png"):
            base = a[:-4]
            if os.path.exists(base + ".xml"):
                pairs.append(base)
        else:
            if os.path.exists(a + ".png") and os.path.exists(a + ".xml"):
                pairs.append(a)
    if not pairs:
        print("没有找到成对的 .png/.xml —— 先用 adb screencap + uiautomator dump 各存一份")
        return 2

    tmpdir = tempfile.mkdtemp(prefix="readability-")
    all_findings = []
    for base in pairs:
        all_findings += analyze(base + ".png", base + ".xml", tmpdir)

    print("=" * 90)
    if all_findings:
        print("共 %d 处实测对比度不达标：" % len(all_findings))
        for cr, need, text, rid, fg, bg in sorted(all_findings):
            print("  %5.2f:1 (需 %.1f)  %-34s %s  #%02x%02x%02x on #%02x%02x%02x"
                  % (cr, need, text[:30], rid, *fg, *bg))
        return 1
    print("实测对比度：全部达标 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
