#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""normalize_layout_tokens.py — 把布局里的内联尺寸收敛到设计令牌（一次性迁移工具）

    python3 tools/normalize_layout_tokens.py [--dry-run]

背景：布局此前内联硬写字号/高度/间距，全 App 出现 9 种字号、12 种控件高度、9 种间距取值，
同一角色的控件在不同页面尺寸不一致。本脚本按**元素类型**把它们映射到 `res/values/dimens.xml`
里的令牌（按钮 44dp、输入框 52dp、正文 14sp、标签 13sp、间距 4/8/12/16dp 网格）。

只改属性值，不动结构与顺序，改动可逐条复核。幂等：再次运行不再产生变化。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "agent-lite", "res", "layout")

PHONE = ["activity_main.xml", "activity_settings.xml", "activity_login.xml", "activity_assist.xml"]
WATCH = ["activity_main_watch.xml", "activity_watch_settings.xml"]

# 字号映射（手机）：把散落值收敛到 12/13/14/16/22 五档
PHONE_TEXT = {
    "9": "@dimen/text_caption", "10": "@dimen/text_caption", "11": "@dimen/text_label",
    "12": "@dimen/text_body", "13": "@dimen/text_body", "14": "@dimen/text_body",
    "15": "@dimen/text_section", "16": "@dimen/text_section", "20": "@dimen/text_title",
    "22": "@dimen/text_title",
}
# 字号映射（手表）：小屏可比手机小，但不低于 12sp
WATCH_TEXT = {
    "9": "@dimen/text_watch_label", "10": "@dimen/text_watch_label", "11": "@dimen/text_watch_label",
    "12": "@dimen/text_watch_body", "13": "@dimen/text_watch_body",
    "15": "@dimen/text_body", "16": "@dimen/text_section", "20": "@dimen/text_title",
}
# 控件高度（按钮类）
BTN_H = {
    "26": "@dimen/btn_h", "28": "@dimen/btn_h", "32": "@dimen/btn_h", "34": "@dimen/btn_h",
    "36": "@dimen/btn_h", "38": "@dimen/btn_h", "40": "@dimen/btn_h", "42": "@dimen/btn_h",
    "44": "@dimen/btn_h", "46": "@dimen/btn_h_primary", "48": "@dimen/btn_h_primary",
}
BTN_H_WATCH = {k: "@dimen/btn_h_watch" for k in ("26", "28", "32", "34", "36", "38", "40", "42")}
BTN_H_WATCH.update({"44": "@dimen/btn_h", "48": "@dimen/btn_h_primary"})
# 间距：收敛到 4/8/12/16 网格
SPACE = {
    "1": "@dimen/space_xs", "2": "@dimen/space_xs", "3": "@dimen/space_xs", "4": "@dimen/space_xs",
    "5": "@dimen/space_s", "6": "@dimen/space_s", "8": "@dimen/space_s",
    "10": "@dimen/space_m", "12": "@dimen/space_m",
    "14": "@dimen/space_l", "16": "@dimen/space_l", "18": "@dimen/space_l", "20": "@dimen/page_margin",
    "24": "@dimen/space_xl",
}

ELEM_RE = re.compile(r"<([A-Za-z][\w.]*)((?:\s+[^<>]*?)?)(/?)>", re.S)
ATTR_RE = re.compile(r'([\w:.-]+)="([^"]*)"')


def is_button(tag):
    return tag.endswith(("Button",)) or tag == "Button"


def is_input(tag):
    return tag.endswith(("EditText", "AutoCompleteTextView"))


def convert(name, value, tag, is_watch):
    """返回新值或 None（不改）。"""
    if name == "android:textSize" and value.endswith("sp"):
        num = value[:-2]
        table = WATCH_TEXT if is_watch else PHONE_TEXT
        return table.get(num)
    if name == "android:layout_height" and value.endswith("dp"):
        num = value[:-2]
        if is_button(tag):
            return (BTN_H_WATCH if is_watch else BTN_H).get(num)
        if is_input(tag):
            return "@dimen/input_h" if num in ("40", "42", "44", "46", "48", "52") else None
        if num in ("26", "28", "32", "34", "36", "38"):      # 其它控件的过小高度一并收敛
            return "@dimen/btn_h"
        return None
    if (name.startswith("android:layout_margin") or name.startswith("android:padding")) \
            and value.endswith("dp"):
        return SPACE.get(value[:-2])
    return None


def process(path, is_watch, dry):
    src = open(path, encoding="utf-8").read()
    stats = {}

    def repl(m):
        tag, attrs, close = m.group(1), m.group(2), m.group(3)
        changed = False

        def arepl(am):
            nonlocal changed
            name, value = am.group(1), am.group(2)
            new = convert(name, value, tag, is_watch)
            if new and new != value:
                stats[name] = stats.get(name, 0) + 1
                changed = True
                return '%s="%s"' % (name, new)
            return am.group(0)

        new_attrs = ATTR_RE.sub(arepl, attrs)
        return "<%s%s%s>" % (tag, new_attrs, close) if changed else m.group(0)

    out = ELEM_RE.sub(repl, src)
    if out != src and not dry:
        open(path, "w", encoding="utf-8").write(out)
    return stats


def main():
    dry = "--dry-run" in sys.argv
    total = {}
    for name in PHONE + WATCH:
        p = os.path.join(RES, name)
        if not os.path.exists(p):
            continue
        st = process(p, name in WATCH, dry)
        n = sum(st.values())
        total[name] = n
        print("  %-28s %3d 处属性%s" % (name, n, "（试运行未写入）" if dry else ""))
    print("\n合计 %d 处" % sum(total.values()))


if __name__ == "__main__":
    main()
