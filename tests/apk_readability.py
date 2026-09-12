#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""apk_readability.py — 面向「可读性」的 APK 静态体检（只读，不修改 APK）。

    python3 tests/apk_readability.py [APK路径]

检查对象是**打包后的 APK 本身**（不是源码）：布局走 `aapt2 dump xmltree` 读编译后的二进制
XML，颜色走 `aapt2 dump resources` 读资源表——这样查的就是用户真正拿到的那只包。

五项可读性指标（阈值取自 WCAG 2.1 与 Android 无障碍建议）：

  A. 字号        正文 < 12sp 偏小、< 11sp 不可接受；辅助说明允许 11sp 但需注明
  B. 对比度      WCAG AA：正文 ≥ 4.5:1，大字（≥18sp 或 ≥14sp 粗体）与图标 ≥ 3:1
  C. 截断风险    singleLine/maxLines=1 但没有 ellipsize → 长文案会被硬切且无省略号提示
  D. 触控目标    Android 建议 ≥ 48dp（最小 44dp）；小于此值易点错
  E. 硬编码颜色  绕过主题 token 的字面色值 → 深色/浅色与 Material You 下可能失去对比

退出码：0 = 无「不可接受」项；1 = 存在 A<11sp 或 B<3:1 或 D<44dp 的硬违规。
"""
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "agent-unified", "dist", "agentlite-unified-0.7.0-android.apk")

ANDROID_NS = "http://schemas.android.com/apk/res/android:"

# 允许 11sp 的辅助说明文案（字号小但有理由；其余 11sp 视为偏小）
HINT_OK = ("hint", "说明", "状态", "提示", "免责", "版本", "时间", "步", "服务器")

SEV_ORDER = {"严重": 0, "偏小": 1, "提示": 2}


def aapt2():
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    cands = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if not cands:
        print("✗ 找不到 aapt2（需要 Android SDK build-tools）", file=sys.stderr)
        sys.exit(2)
    return cands[-1]


def run(*args):
    r = subprocess.run(list(args), capture_output=True, text=True)
    return r.stdout


# ───────────────────────────── 资源解析 ─────────────────────────────

def parse_colors(apk, a2):
    """{资源id十六进制: 默认色值} 与 {id: v31 的动态色引用}。"""
    out = run(a2, "dump", "resources", apk)
    colors, dynamic = {}, {}
    cur = None
    for line in out.splitlines():
        m = re.match(r"\s*resource (0x[0-9a-f]+) color/(\S+)", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r"\s*\(\) (#[0-9a-fA-F]{6,8})", line)
        if m and cur:
            colors[cur] = m.group(1).lower()
            continue
        m = re.match(r"\s*\(v31\) (@0x[0-9a-f]+)", line)
        if m and cur:
            dynamic[cur] = m.group(1)
    return colors, dynamic


def color_names(apk, a2):
    """{0x7f05xxxx: token名}——M3 的 `on_*` 配对语义要靠名字判断。"""
    out = run(a2, "dump", "resources", apk)
    return {m.group(1): m.group(2) for m in re.finditer(r"resource (0x[0-9a-f]+) color/(\S+)", out)}


def id_names_drawable(apk, a2):
    """{0x7f02xxxx: drawable 名}。"""
    out = run(a2, "dump", "resources", apk)
    return {m.group(1): m.group(2) for m in re.finditer(r"resource (0x[0-9a-f]+) drawable/(\S+)", out)}


def id_names(apk, a2):
    """{0x7f08xxxx: 名字}——报告里用名字比十六进制可读得多。"""
    out = run(a2, "dump", "resources", apk)
    return {m.group(1): m.group(2) for m in re.finditer(r"resource (0x[0-9a-f]+) id/(\S+)", out)}


def parse_theme_tokens(apk, a2):
    """M3 token 名 → 颜色资源 id（如 colorPrimary → 0x7f0500xx）。"""
    out = run(a2, "dump", "resources", apk)
    tokens = {}
    for m in re.finditer(r"(color[A-Za-z]+)\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", out):
        tokens[m.group(1)] = m.group(2)
    return tokens


def resolve_color(val, colors, seen=None):
    """把属性值解析成 #rrggbb：既支持 @0x… 资源引用，也支持字面量。"""
    if val is None:
        return None
    v = val.strip()
    if re.fullmatch(r"#[0-9a-fA-F]{6,8}", v):
        return v.lower()
    m = re.fullmatch(r"@(0x[0-9a-f]+)", v)
    if m:
        return colors.get(m.group(1))
    return None


# ───────────────────────────── XML 树解析 ─────────────────────────────

def parse_xmltree(text):
    """把 aapt2 dump xmltree 的缩进树解析成 [{tag, attrs, line, children}]。"""
    root, stack = [], []
    for raw in text.splitlines():
        if not raw.strip():
            continue
        indent = len(raw) - len(raw.lstrip())
        s = raw.strip()
        m = re.match(r"E: (\S+) \(line=(\d+)\)", s)
        if m:
            node = {"tag": m.group(1), "line": int(m.group(2)), "attrs": {}, "children": []}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            (stack[-1][1]["children"] if stack else root).append(node)
            stack.append((indent, node))
            continue
        m = re.match(r"A: \S+:(\w+)\(0x[0-9a-f]+\)=(.*)$", s)
        if m and stack:
            stack[-1][1]["attrs"][m.group(1)] = m.group(2)
    return root


def drawable_solid(apk, a2, drawable_id, colors, cache):
    """把 @drawable/xxx 解析成它的 <solid> 颜色（shape drawable 常见于本项目卡片/按钮）。"""
    if drawable_id in cache:
        return cache[drawable_id]
    res = None
    out = run(a2, "dump", "resources", apk)
    m = re.search(r"resource 0x[0-9a-f]+ drawable/(\S+)\n(?:\s*\([^)]*\)[^\n]*\n)*", out)
    # 直接用 id 反查名字：dump resources 里 drawable 与 color 可能同 id 段，这里按名字遍历
    name = None
    for mm in re.finditer(r"resource (0x[0-9a-f]+) drawable/(\S+)", out):
        if mm.group(1) == drawable_id:
            name = mm.group(2)
            break
    if name:
        tree = parse_xmltree(run(a2, "dump", "xmltree", apk, "--file", "res/drawable/%s.xml" % name))
        for node in walk(tree):
            if node["tag"] == "solid":
                res = resolve_color(node["attrs"].get("color"), colors)
                break
    cache[drawable_id] = res
    return res


def walk(nodes):
    for n in nodes:
        yield n
        yield from walk(n["children"])


# ───────────────────────────── 对比度 ─────────────────────────────

def luminance(hexcolor):
    h = hexcolor.lstrip("#")
    if len(h) == 8:
        h = h[2:]          # 去掉 alpha
    r, g, b = (int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))

    def lin(c):
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(fg, bg):
    l1, l2 = luminance(fg), luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


# ───────────────────────────── 主检查 ─────────────────────────────

def nearest_bg(node_stack):
    for n in reversed(node_stack):
        c = n["attrs"].get("background")
        if c:
            return c
    return None


# android:textColorHint 的属性 id（aapt2 dump 里样式项按数字 id 打印，不写名字）
ATTR_TEXT_COLOR_HINT = "0x0101009a"


def tokens_hint(apk, a2):
    """主题里 android:textColorHint 指向的颜色资源 id。

    样式项在 `aapt2 dump resources` 里是按**数字属性 id** 打印的（如 `0x01010036=@color/…`），
    不写属性名，所以这里按 id 找。
    """
    out = run(a2, "dump", "resources", apk)
    # 必须限定在**本项目自己的主题块**里找：整份 dump 里 AppCompat/Material 的库主题也定义了
    # android:textColorHint（abc_hint_foreground_material_dark…），全局 search 会先命中它们。
    blocks = re.split(r"\n    resource ", out)
    own = [b for b in blocks if re.match(r"0x7f[0-9a-f]+ style/Theme\.", b)]
    for b in own + blocks:
        m = re.search(re.escape(ATTR_TEXT_COLOR_HINT) + r"=@(?:color/)?(0x7f[0-9a-f]+|[\w]+)", b)
        if m:
            break
    else:
        return None
    v = m.group(1)
    if v.startswith("0x"):
        return v
    for cid, name in color_names(apk, a2).items():
        if name == v:
            return cid
    return None


def scan_secrets(apk):
    """在 dex 字符串里找常见的密钥形态——发布 APK 不该带这些。"""
    import tempfile
    import zipfile
    pats = [(r"sk-[A-Za-z0-9._-]{20,}", "API Key（sk- 开头）"),
            (r"AKIA[0-9A-Z]{16}", "AWS Access Key"),
            (r"gh[pousr]_[A-Za-z0-9]{20,}", "GitHub Token")]
    tmp = tempfile.mkdtemp(prefix="apk-secret-")
    hits = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if not re.match(r"classes\d*\.dex", n):
                continue
            z.extract(n, tmp)
            data = open(os.path.join(tmp, n), "rb").read()
            for pat, kind in pats:
                for m in re.finditer(pat.encode(), data):
                    s = m.group(0).decode("ascii", "replace")
                    masked = s[:12] + "……" + s[-4:]
                    if (kind, masked) not in hits:
                        hits.append((kind, masked))
    return hits


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.exists(apk):
        print("✗ 找不到 APK：%s" % apk, file=sys.stderr)
        return 2
    a2 = aapt2()
    colors, dynamic = parse_colors(apk, a2)
    tokens = parse_theme_tokens(apk, a2)
    ids = id_names(apk, a2)
    cnames = color_names(apk, a2)
    by_name = {v: k for k, v in cnames.items()}
    layouts = sorted(n for n in run(a2, "dump", "resources", apk).splitlines())
    # 只取本项目自己的布局：以源码目录为准（APK 里还有 AppCompat/Material 的库布局，
    # 如 abc_cascading_menu_item_layout——那不是我们的 UI，混进来只会制造噪声）
    src_layouts = {os.path.basename(x)[:-4]
                   for x in glob.glob(os.path.join(ROOT, "agent-lite", "res", "layout", "*.xml"))}
    all_layouts = re.findall(r"resource 0x[0-9a-f]+ layout/(\S+)", run(a2, "dump", "resources", apk))
    names = [n for n in all_layouts if n in src_layouts]
    skipped = len(all_layouts) - len(names)

    print("=" * 78)
    print("APK 可读性体检   %s" % os.path.basename(apk))
    print("=" * 78)
    print("颜色资源 %d 个（其中 %d 个在 v31 走 Material You 动态色）" % (len(colors), len(dynamic)))
    print("本 App 布局 %d 个（另有 %d 个库自带布局不参与评估）" % (len(names), skipped))
    print()

    fonts, trunc, touch, hard, contrasts = [], [], [], [], []
    cache = {}

    for lay in names:
        tree = parse_xmltree(run(a2, "dump", "xmltree", apk, "--file", "res/layout/%s.xml" % lay))

        def visit(nodes, stack):
            for n in nodes:
                st = stack + [n]
                a = n["attrs"]
                where = "%s.xml:%d" % (lay, n["line"])
                iid = a.get("id", "").replace("@", "")
                label = "%s %s" % (n["tag"].split(".")[-1], ids.get(iid, iid))

                # A. 字号
                ts = a.get("textSize")
                if ts and ts.endswith("sp"):
                    size = float(ts[:-2])
                    textish = "text" in a or n["tag"].endswith(("TextView", "Button", "Switch"))
                    if textish and size < 12:
                        hinty = any(k in (a.get("text", "") + a.get("hint", "")) for k in HINT_OK)
                        sev = "严重" if size < 11 else ("提示" if hinty else "偏小")
                        fonts.append((sev, size, label, where, a.get("text", a.get("hint", ""))[:18]))

                # C. 截断风险：单行/限行但无 ellipsize
                single = a.get("singleLine") == "true" or a.get("maxLines") == "1"
                if single and not a.get("ellipsize") and "text" in a:
                    trunc.append((label, where, a.get("text", "")[:24]))

                # D. 触控目标：可点控件的高度
                h = a.get("layout_height", "")
                clicky = n["tag"].endswith(("Button", "ImageView")) or a.get("clickable") == "true"
                if clicky and h.endswith("dp"):
                    dp = float(h[:-2])
                    if dp < 44:
                        touch.append((dp, label, where))

                # E. 硬编码颜色
                for k in ("textColor", "background", "tint"):
                    v = a.get(k)
                    if v and re.fullmatch(r"#[0-9a-fA-F]{6,8}", v):
                        hard.append((k, v, label, where))

                # B. 对比度：文字色 vs 最近祖先背景
                fg_val = a.get("textColor")
                bg_val = a.get("background") or nearest_bg(stack)
                fg = resolve_color(fg_val, colors)
                bg = resolve_color(bg_val, colors)
                if bg is None and bg_val and bg_val.startswith("@0x7f"):
                    bg = drawable_solid(apk, a2, bg_val[1:], colors, cache)
                # M3 语义：文字色若叫 color_on_X，它就该坐在 color_X 上（填充/容器由样式提供，
                # 布局里看不到）。不按这个配对算，就会把「深紫字 + 浅紫底」误判成低对比。
                fg_id = (fg_val or "").lstrip("@")
                fg_token = cnames.get(fg_id, "")
                if fg_token.startswith("color_on_"):
                    paired = "color_" + fg_token[len("color_on_"):]
                    pid = by_name.get(paired)
                    if pid and pid in colors:
                        bg, bg_val = colors[pid], "@" + pid
                if fg and bg:
                    cr = contrast(fg, bg)
                    ts_v = a.get("textSize", "")
                    big = ts_v.endswith("sp") and float(ts_v[:-2]) >= 18
                    need = 3.0 if big else 4.5
                    contrasts.append((cr, need, label, where, fg, bg))
                visit(n["children"], st)

        visit(tree, [])

    # ── 汇总 ──
    print("A. 字号（正文 ≥ 12sp 为宜；< 11sp 不可接受）")
    if not fonts:
        print("   ✓ 未发现偏小字号")
    else:
        phone = [f for f in fonts if "watch" not in f[3]]
        watch = [f for f in fonts if "watch" in f[3]]
        print("   分布：手机页 %d 处（最小于 %.1fsp）｜手表页 %d 处（最小于 %.1fsp）"
              % (len(phone), min([f[1] for f in phone], default=99),
                 len(watch), min([f[1] for f in watch], default=99)))
        print("   %-8s %-6s %s" % ("区间", "数量", "说明"))
        for lo, hi, note in ((0, 10, "手表圆形小屏；Android 穿戴建议 ≥ 12sp"),
                             (10, 11, "手机页出现即偏小"),
                             (11, 12, "偏小/辅助文案可接受")):
            n = len([f for f in fonts if lo <= f[1] < hi])
            if n:
                print("   %-8s %-6d %s" % ("%g-%gsp" % (lo, hi), n, note))
        worst = sorted(fonts, key=lambda x: x[1])[:6]
        print("   最小 6 处：")
        for sev, size, label, where, text in worst:
            print("     %s %5.1fsp  %-26s %-32s %s" % ("✗" if size < 11 else "!", size, label, where, text))
    print()

    print("B. 对比度（WCAG AA：正文 ≥ 4.5:1，大字 ≥ 3:1）")
    fails = [c for c in contrasts if c[0] < c[1]]
    if not fails:
        print("   ✓ 已解析的 %d 组前景/背景均达标（最低 %.2f:1）"
              % (len(contrasts), min([c[0] for c in contrasts], default=99)))
    for cr, need, label, where, fg, bg in sorted(fails):
        mark = "✗" if cr < 3.0 else "!"
        print("   %s %5.2f:1 (需 %.1f)  %-28s %-30s %s on %s" % (mark, cr, need, label, where, fg, bg))
    print()

    print("C. 截断风险（单行/限行但未设 ellipsize）")
    if not trunc:
        print("   ✓ 未发现硬切风险")
    for label, where, text in trunc[:12]:
        print("   ! %-28s %-34s %s" % (label, where, text))
    if len(trunc) > 12:
        print("   … 另有 %d 处" % (len(trunc) - 12))
    print()

    print("D. 触控目标（Android 建议 ≥ 48dp；44dp 为可接受下限）")
    if not touch:
        print("   ✓ 可点控件高度均达 44dp")
    else:
        bands = ((0, 32, "明显偏小：单手点按易误触"),
                 (32, 40, "偏小：紧凑工具条可接受，主操作按钮不宜"),
                 (40, 44, "接近下限：可接受但建议补齐到 44dp"))
        for lo, hi, note in bands:
            n = len([t for t in touch if lo <= t[0] < hi])
            if n:
                print("   %-8s %-5d %s" % ("%g-%gdp" % (lo, hi), n, note))
        worst = [t for t in touch if t[0] < 32]
        if worst:
            print("   明显偏小（< 32dp）的具体控件：")
            for dp, label, where in sorted(worst):
                print("     ✗ %5.1fdp  %-26s %s" % (dp, label, where))
            print("   （其余 %d 处为 32–43dp，见上表分档）" % (len(touch) - len(worst)))
    print()

    print("E. 硬编码颜色（绕过主题 token，深色/Material You 下可能失去对比）")
    print("   布局内字面色值：%d 处" % len(hard))
    for k, v, label, where in hard[:8]:
        print("   · %-10s %-9s %-28s %s" % (k, v, label, where))
    if len(hard) > 8:
        print("   … 另有 %d 处" % (len(hard) - 8))
    print()

    # F. 提示文字（hint）对比度：色来自主题 android:textColorHint，承载面是输入框 drawable，
    #    两者都在布局之外，静态读布局看不到——但正是实测中最容易翻车的一处。
    print("F. 提示文字（hint）对比度")
    hint_id = tokens_hint(apk, a2)                       # 颜色资源 id
    hint_hex = colors.get(hint_id) if hint_id else None
    # 输入框填充色直接读 bg_input 这个 drawable 的 <solid>：比查 M3 token 名可靠——
    # token 名不一定在本项目主题里定义，而 drawable 才是实际生效的那份。
    fill_hex = None
    for did, dname in id_names_drawable(apk, a2).items():
        if dname == "bg_input":
            fill_hex = drawable_solid(apk, a2, did, colors, cache)
            break
    if hint_hex and fill_hex:
        hc = contrast(hint_hex, fill_hex)
        mark = "✓" if hc >= 4.5 else ("✗" if hc < 3.0 else "!")
        print("   %s hint %s on 输入框填充 %s → %.2f:1（WCAG AA 正文需 4.5:1）"
              % (mark, hint_hex, fill_hex, hc))
        hint_fail = hc < 4.5
    else:
        print("   · 未能解析（hint=%s / 输入框填充=%s）" % (hint_hex, fill_hex))
        hint_fail = False
    print()

    # G. 发布面泄露检查：APK 里能不能直接读出密钥（"APK 可读性"的另一层含义）
    print("G. 发布面泄露（APK 内是否为明文可读）")
    secrets = scan_secrets(apk)
    if secrets:
        for kind, sample in secrets:
            print("   ✗ 发现 %s：%s（任何拿到 APK 的人都能 strings 提取）" % (kind, sample))
    else:
        print("   ✓ 未在 dex 中发现 API Key / 令牌类明文")
    debuggable = "application-debuggable" in run(a2, "dump", "badging", apk)
    print("   %s android:debuggable 未开启" % ("✗" if debuggable else "✓"))
    print()

    severe = [f for f in fonts if f[0] == "严重"]
    hard_fail = [c for c in contrasts if c[0] < 3.0] + [t for t in touch if t[0] < 44]
    print("-" * 78)
    print("汇总：字号问题 %d（严重 %d）｜对比度不达标 %d（<3:1 共 %d）｜截断风险 %d｜触控不足 %d｜硬编码色 %d"
          % (len(fonts), len(severe), len(fails), len(hard_fail) - len([t for t in touch if t[0] < 44]),
             len(trunc), len(touch), len(hard)))
    print("-" * 78)
    if severe or hard_fail or hint_fail or secrets:
        print("存在硬违规（字号 < 11sp / 对比度 < 3:1 / 触控 < 44dp / hint < 4.5:1 / APK 内含明文密钥）")
        return 1
    print("无硬违规（偏小与提示项建议人工确认）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
