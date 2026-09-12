#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""font_scale_readability.py — 字体可读性分析：字体缩放下的「装不下」检测

    python3 tests/font_scale_readability.py <sweep目录>     # 由 font_scale_sweep.sh 产出

做法（确定性，不依赖像素取证）：对每个控件算出**文字需要的行高**，与**实测控件框高**比较。

    需要高度 = 声明字号(sp) × 缩放倍数 × 系统字体缩放 × 密度 × 行高系数
    框高     = uiautomator 实测的 bounds 高度（px）

判定：
  · 需要 > 框高          → **裁字**（文字比容器高，上下被切）
  · 需要 > 框高 / 1.18   → 临界（再放大一点就裁）

为什么不用「墨迹高度」做主要依据：字形墨迹的像素测量在真实控件上噪声很大——
按钮填充与卡片底的圆角混色、输入框下划线、离群像素都会污染外框（本工具早期版本据此给出过
「达成率恒为 1/缩放」这种一眼假的结论，已废弃）。行高模型只依赖两个可靠量：
**声明 sp（源码）** 与 **实测框高（uiautomator bounds）**，两者都可复核。

环境变量：SCREEN_DENSITY（默认 420，取样机 dpi）、CJK_LINE_FACTOR（默认 1.35，中文行高系数）
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINE_FACTOR = float(os.environ.get("CJK_LINE_FACTOR", "1.35"))
DENSITY = float(os.environ.get("SCREEN_DENSITY", "420")) / 160.0


# 采集页面 → 布局文件（手机形态；手表另有 activity_*_watch.xml）
PAGE_LAYOUT = {
    "main": "activity_main.xml",
    "settings": "activity_settings.xml",
    "settings_bottom": "activity_settings.xml",
    "login": "activity_login.xml",
    "assist": "activity_assist.xml",
}


def dimen_table():
    """@dimen/xxx → 数值。布局已改用设计令牌，量测工具必须跟着解析引用。"""
    out = {}
    for f in glob.glob(os.path.join(ROOT, "agent-lite", "res", "values", "dimens.xml")):
        for name, val in re.findall(r'<dimen name="([\w_]+)">([^<]+)</dimen>', open(f, encoding="utf-8").read()):
            out[name] = val.strip()
    # 展开 @dimen 互相引用（如 btn_h → @dimen/touch_min）
    for _ in range(3):
        for k, v in list(out.items()):
            if v.startswith("@dimen/"):
                out[k] = out.get(v.split("/")[-1], v)
    return out


DIMENS = None


def num(value, unit):
    """'14sp' 或 '@dimen/text_body' → 14.0；无法解析返回 None。"""
    global DIMENS
    if DIMENS is None:
        DIMENS = dimen_table()
    if value is None:
        return None
    v = value.strip()
    if v.startswith("@dimen/"):
        v = DIMENS.get(v.split("/")[-1], "")
    if v.endswith(unit):
        try:
            return float(v[:-len(unit)])
        except ValueError:
            return None
    return None


def declared_metrics():
    """{布局文件: {resource-id: (textSize_sp, layout_height_dp|None, 文案)}}。

    必须按文件分组：**同一 id 在手机与手表两套布局里声明不同**
    （`btnResetTokens` 手机 28dp、手表 42dp）。早期按 id 建单表，后解析的手表会覆盖手机，
    于是手机侧 74px 的观测被当成「42dp 被裁掉一半」而排除，真正的裁字反而被漏掉。
    """
    out = {}
    for f in glob.glob(os.path.join(ROOT, "agent-lite", "res", "layout", "*.xml")):
        txt = open(f, encoding="utf-8").read()
        per = out.setdefault(os.path.basename(f), {})
        for m in re.finditer(r"<([\w.]+)([^>]*?)/?>", txt, re.S):
            a = dict(re.findall(r'android:(\w+)="([^"]*)"', m.group(2)))
            rid = a.get("id", "").split("/")[-1]
            if not rid or "textSize" not in a:
                continue
            ts = num(a.get("textSize"), "sp")
            hd = num(a.get("layout_height"), "dp")
            per[rid] = (ts, hd, (a.get("text") or a.get("hint") or "")[:20])
    return out


def nodes(xml):
    """[(rid, text, x1,y1,x2,y2, cls)]；**只返回完整可见的控件**。

    被滚动出视口的控件，其 uiautomator bounds 会被裁成「可见的那一截」，框高随之变小——
    若不排除，会被误判成「文字装不下」（实测踩过：42dp 的按钮量出 15px 框高）。
    """
    out = []
    for tag in re.findall(r"<node\b[^>]*>", xml):
        a = dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
        if not a.get("text", "").strip() or not a.get("bounds"):
            continue
        b = re.findall(r"-?\d+", a["bounds"])
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        if x2 <= x1 or y2 <= y1:
            continue
        out.append((a.get("resource-id", "").split("/")[-1], a["text"], x1, y1, x2, y2,
                    a.get("class", "").split(".")[-1]))
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/fontscale"
    if not os.path.isdir(root):
        print("找不到 sweep 目录：%s（先跑 tests/font_scale_sweep.sh）" % root)
        return 2
    scales = sorted((d for d in os.listdir(root) if re.fullmatch(r"[\d.]+", d)), key=float)
    if len(scales) < 2:
        print("至少需要两个缩放档的样本")
        return 2
    pages = sorted({os.path.basename(p)[:-4]
                    for p in glob.glob(os.path.join(root, scales[0], "*.png"))} - {"probe", "sprobe"})
    declared = declared_metrics()

    print("=" * 98)
    print("字体可读性分析（字体缩放）   档位：%s   密度 %.0fdpi   中文行高系数 %.2f"
          % (" / ".join("×" + s for s in scales), DENSITY * 160, LINE_FACTOR))
    print("=" * 98)
    print("口径：需要高度 = 声明字号(sp) × 缩放倍数 × 密度 × 行高系数；与实测控件框高（px）比较")

    obs = {}   # (page, rid) -> {...}
    for page in pages:
        avail = [s for s in scales if os.path.exists(os.path.join(root, s, page + ".xml"))]
        if not avail:
            continue
        print("\n【%s】（可比档位 %s）" % (page, " / ".join("×" + s for s in avail)))
        for s in avail:
            xml = open(os.path.join(root, s, page + ".xml"), encoding="utf-8", errors="replace").read()
            for rid, text, x1, y1, x2, y2, cls in nodes(xml):
                lay = PAGE_LAYOUT.get(page)
                decl = (declared.get(lay, {}) or {}).get(rid) if lay else None
                if decl is None:
                    for per in declared.values():
                        if rid in per:
                            decl = per[rid]
                            break
                if not decl or decl[0] is None:
                    continue
                sp, hd, label = decl
                need = sp * float(s) * DENSITY * LINE_FACTOR
                st = obs.setdefault((page, rid), {"text": text, "sp": sp, "hd": hd, "rows": []})
                st["rows"].append((s, need, y2 - y1))
        for (p_, rid), st in sorted(obs.items()):
            if p_ != page:
                continue
            # 只评估**完整可见**的观测：被 ScrollView 裁掉一半的控件，bounds 只是可见那一截，
            # 直接拿去比会被误判成「文字装不下」（实测踩过：42dp 按钮量出 15px 框高）。
            hmax = max(b for _, _, b in st["rows"])
            decl_px = st["hd"] * DENSITY if st["hd"] else None
            # 只有**声明了固定高度**的控件才可能「装不下」：wrap_content 的文本框会随字号长高，
            # 除非被父容器卡住（那时框高跨档不变，下面用 hmax 判据兜住）。
            if st["hd"] is None and hmax > max(n for _, n, _ in st["rows"]) * 1.05:
                st["full"] = []
                continue
            full = [(s_, n_, b_) for s_, n_, b_ in st["rows"]
                    if b_ >= 0.9 * hmax and (decl_px is None or b_ >= 0.9 * decl_px)]
            st["full"] = full
            if not full:
                continue
            worst = min(b / n for _, n, b in full)
            if worst >= 1.18:
                continue
            flag = "✗ 裁字" if worst < 1.0 else "! 临界"
            print("  %-18s %-9s %s" % (rid[:18], "%gsp%s" % (st["sp"], "" if st["hd"] is None else "/%gdp" % st["hd"]),
                                       "  ".join("×%s %.0f/%.0f" % (s, n, b) for s, n, b in full)))
            print("      → %s（最紧 %.0f%%）  文案：%s" % (flag, 100 * worst, st["text"][:26]))
        print("  （仅列裁字/临界项；其余控件各档均装得下）")

    print("\n" + "=" * 98)
    bad = [((k, v), min(b / n for _, n, b in v.get("full", []))) for k, v in obs.items() if v.get("full")]
    clipped = [(k, v, r) for (k, v), r in bad if r < 1.0]
    tight = [(k, v, r) for (k, v), r in bad if 1.0 <= r < 1.18]
    if clipped:
        print("字体放大后**装不下（裁字）**的控件：%d 个" % len(clipped))
        print("  %-10s %-18s %-9s %-10s %s" % ("页面", "控件", "声明", "起始档位", "最紧一档"))
        for (page, rid), v, ratio in sorted(clipped, key=lambda x: x[2]):
            first = min((s for s, n, b in v["full"] if b < n), key=float)
            t = min(v["full"], key=lambda o: o[2] / o[1])
            print("  %-10s %-18s %-9s ×%-9s 需要 %.0fpx / 框高 %.0fpx（%.0f%%）"
                  % (page, rid[:18], "%gsp" % v["sp"], first, t[1], t[2], 100 * t[2] / t[1]))
    if tight:
        print("\n进入临界区（框高/需要 < 1.18，再放大即裁）：%d 个" % len(tight))
        for (page, rid), v, ratio in sorted(tight, key=lambda x: x[2]):
            print("  %-10s %-18s %gsp  最紧 %.0f%%" % (page, rid[:18], v["sp"], 100 * ratio))
    if not clipped and not tight:
        print("字体缩放：%d 个控件在所有档位下均装得下 ✅" % len(obs))
    print("=" * 98)
    return 1 if clipped else 0


if __name__ == "__main__":
    sys.exit(main())
