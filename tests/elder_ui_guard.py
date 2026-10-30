#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""elder_ui_guard.py — 适老化前端门禁（免设备）。

    python3 tests/elder_ui_guard.py

手机形态的界面面向老年人与不便使用手机的人（设计令牌见 agent-lite/res/values/elder.xml）。
本门禁守住改版时定下的硬指标，防止后续改动把它们悄悄改回去：

  E-1 不丢功能：改版前每个页面的全部控件 id 仍然存在（Java 绑定与逻辑原样复用）；
      手机版与手表版登录页的 id 集合一致（除仅手机有的「显示密钥」）。
  E-2 字够大：手机页面里可见文字的字号 ≥ 18sp（显式写的与样式继承的都算）。
  E-3 好点按：Button / EditText / Switch / CheckBox 的最小高度 ≥ 60dp，且不写死高度
      （写死高度在系统字体放大后会裁字）。
  E-4 看得清：适老化色板的文字/背景对比度 ≥ 4.5:1（正文 ≥ 7:1），边框 ≥ 3:1。
  E-5 不混用：手机页面不引用深色主题令牌（@color/color_* / @dimen/text_*），隐藏的调试区除外。
  E-6 手表不受影响：主题切换只在手机形态发生；手表布局文件不引用适老化令牌。
  E-7 弹窗与危险操作：主页对话框统一经 ElderUi.show 放大；清空 / 重置类操作先二次确认。

退出码：0 = 全部通过；1 = 存在失败。
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
LITE = ROOT / "agent-lite"
RES = LITE / "res"
LAYOUT = RES / "layout"
SRC = LITE / "src" / "com" / "dsh" / "agentlite"
A = "{http://schemas.android.com/apk/res/android}"

RESULTS: list[tuple[str, str, bool, str]] = []


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, desc, ok, note))


def read(p: pathlib.Path) -> str:
    return p.read_text(encoding="utf-8") if p.exists() else ""


# ─────────────────────────── 资源解析 ───────────────────────────
def load_values() -> tuple[dict, dict, dict]:
    colors, dimens, styles = {}, {}, {}
    for f in (RES / "values").glob("*.xml"):
        root = ET.parse(f).getroot()
        for el in root:
            name = el.get("name")
            if el.tag == "color":
                colors[name] = (el.text or "").strip()
            elif el.tag == "dimen":
                dimens[name] = (el.text or "").strip()
            elif el.tag == "style":
                items = {i.get("name"): (i.text or "").strip() for i in el.findall("item")}
                styles[name] = (el.get("parent"), items)
    return colors, dimens, styles


COLORS, DIMENS, STYLES = load_values()


def resolve_dimen(v: str | None) -> float | None:
    """把 '20sp' / '@dimen/x' 解析成数值（不区分 sp 与 dp）。"""
    seen = 0
    while v and v.startswith("@dimen/") and seen < 8:
        v = DIMENS.get(v[len("@dimen/"):])
        seen += 1
    if not v:
        return None
    m = re.match(r"([\d.]+)(sp|dp|px)$", v)
    return float(m.group(1)) if m else None


def style_chain(name: str | None) -> list[dict]:
    """样式继承链（显式 parent 与点号隐式父样式都算），近的在前。"""
    out, guard = [], 0
    while name and guard < 10:
        guard += 1
        name = name.replace("@style/", "")
        if name not in STYLES:
            break
        parent, items = STYLES[name]
        out.append(items)
        if parent:
            name = parent if not parent.startswith("@android:") else None
        elif "." in name:
            name = name.rsplit(".", 1)[0]
        else:
            name = None
    return out


def attr(el: ET.Element, key: str) -> str | None:
    """控件属性：先看布局里显式写的，再沿样式链找。"""
    v = el.get(A + key)
    if v is not None:
        return v
    for items in style_chain(el.get("style")):
        if "android:" + key in items:
            return items["android:" + key]
    return None


def luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    if len(h) == 8:   # AARRGGBB：本门禁只比较不透明色
        h = h[2:]
    rgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def contrast(a: str, b: str) -> float:
    la, lb = luminance(COLORS[a]), luminance(COLORS[b])
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def ids_of(path: pathlib.Path) -> set[str]:
    return set(re.findall(r'@\+id/(\w+)', read(path)))


def hidden_debug_ids(root: ET.Element) -> set[int]:
    """visibility=gone 且只为内部逻辑保留的调试容器（截屏回显 / 日志）下的全部节点。"""
    skip: set[int] = set()

    def walk(el: ET.Element, inside: bool) -> None:
        here = inside or (el.get(A + "visibility") == "gone"
                          and any(c.get(A + "id") in ("@+id/flScreen", "@+id/svLog") for c in el))
        if here:
            skip.add(id(el))
        for c in el:
            walk(c, here)

    walk(root, False)
    return skip


PHONE_LAYOUTS = ["activity_main", "activity_settings", "activity_login",
                 "activity_purchase", "activity_assist"]
WATCH_LAYOUTS = ["activity_main_watch", "activity_watch_settings", "activity_login_watch"]

# 改版前（v0.7.0，c3df7a7）各页面的控件 id：只允许新增，不允许丢
ORIGINAL_IDS = {
    "activity_main": "a11yBanner btnA11yBanner btnAuth btnConfig btnEco btnHistory btnMemory btnMic "
                     "btnRun etTask flScreen ivScreen mainContent rootLayout svLog tvA11y tvAccount "
                     "tvLog tvStatus vTap",
    "activity_settings": "btnA11y btnAccountAction btnAsrTest btnAutoStart btnBack btnBatteryIgnore btnBuy "
                         "btnDisplayCalib btnDisplayRefresh btnLanRendezvous btnLogout btnRedeem "
                         "btnRefreshQuota btnResetTokens btnSave btnSyncNow btnSyncSave etAsr etAsrBase "
                         "etAsrKey etBase etKey etMaxSteps etModel etPlanner etRedeem etSyncServer "
                         "rootSettings settingsContent swSingleModel swSync swVision tvAccount tvAsrHint "
                         "tvDevices tvDisplay tvKeepAlive tvQuota tvSingleModelHint tvSyncHint "
                         "tvSyncStatus tvTokens",
    "activity_login": "btnBack btnLogin btnRegister btnSkip etKey etName loginContent rootLogin "
                      "tvCurrent tvMsg tvRuleKey tvRuleName tvSyncTarget",
    "activity_purchase": "btnBack btnCancel btnPay etAkId etCode orderArea packsContainer purchaseContent "
                         "rootPurchase swSingleModelBuy tvAkLabel tvAkSecretLabel tvCurrentOrder tvMsg "
                         "tvOrders tvPayHint tvQuota",
    "activity_assist": "assistDialog assistRoot btnAssistClose btnAssistMic btnAssistRun etAssistInput "
                       "tvAssistHint tvAssistTitle",
}

# ─────────────────────────── E-1 不丢功能 ───────────────────────────
for name, want in ORIGINAL_IDS.items():
    have = ids_of(LAYOUT / f"{name}.xml")
    missing = sorted(set(want.split()) - have)
    check("E-1", f"{name}.xml 保留改版前全部控件 id（{len(want.split())} 个）", not missing,
          f"缺少：{missing}" if missing else "")

phone_login, watch_login = ids_of(LAYOUT / "activity_login.xml"), ids_of(LAYOUT / "activity_login_watch.xml")
diff = (phone_login - {"cbShowKey"}) ^ watch_login
check("E-1", "手机版 / 手表版登录页 id 集合一致（LoginActivity 两套布局共用一份逻辑）", not diff,
      f"差异：{sorted(diff)}" if diff else "")

# ─────────────────────────── E-2 / E-3 / E-5 逐控件 ───────────────────────────
TEXT_TAGS = {"TextView", "Button", "EditText", "Switch", "CheckBox"}
TOUCH_TAGS = {"Button", "EditText", "Switch", "CheckBox"}
small, short, fixed, dark = [], [], [], []
for name in PHONE_LAYOUTS:
    path = LAYOUT / f"{name}.xml"
    root = ET.parse(path).getroot()
    skip = hidden_debug_ids(root)
    for el in root.iter():
        if id(el) in skip:
            continue
        tag = el.tag.rsplit(".", 1)[-1]
        label = f"{name}:{(el.get(A + 'id') or tag).replace('@+id/', '')}"
        for k, v in el.attrib.items():
            if v.startswith("@color/color_") or v.startswith("@dimen/text_"):
                dark.append(f"{label} {k.replace(A, 'android:')}={v}")
        if tag in TEXT_TAGS:
            size = resolve_dimen(attr(el, "textSize"))
            if size is None or size < 18:
                small.append(f"{label}={size}")
        if tag in TOUCH_TAGS:
            mh = resolve_dimen(attr(el, "minHeight"))
            if mh is None or mh < 60:
                short.append(f"{label}={mh}")
            h = el.get(A + "layout_height")
            if h not in ("wrap_content", "match_parent"):
                fixed.append(f"{label}={h}")

check("E-2", "手机页面可见文字字号全部 ≥ 18sp", not small, f"偏小：{small}" if small else "")
check("E-3", "按钮 / 输入框 / 开关最小高度全部 ≥ 60dp", not short, f"偏矮：{short}" if short else "")
check("E-3", "按钮 / 输入框 / 开关不写死高度（字体放大不裁字）", not fixed, f"写死：{fixed}" if fixed else "")
check("E-5", "手机页面不引用深色主题令牌（隐藏调试区除外）", not dark, f"命中：{dark[:6]}" if dark else "")

# ─────────────────────────── E-4 对比度 ───────────────────────────
PAIRS = [  # (前景, 背景, 下限, 用途)
    ("elder_text", "elder_card", 7.0, "正文"),
    ("elder_text", "elder_bg", 7.0, "页面正文"),
    ("elder_text_2", "elder_card", 7.0, "说明文字"),
    ("elder_text_2", "elder_bg", 7.0, "页面说明文字"),
    ("elder_hint", "elder_card", 4.5, "输入提示"),
    ("elder_on_primary", "elder_primary", 4.5, "主按钮"),
    ("elder_on_primary", "elder_primary_pressed", 4.5, "主按钮按下"),
    ("elder_on_primary", "elder_danger", 4.5, "停止 / 退出按钮"),
    ("elder_primary", "elder_card", 4.5, "次按钮文字"),
    ("elder_primary", "elder_primary_soft", 4.5, "次按钮按下"),
    ("elder_ok_text", "elder_ok_bg", 4.5, "正常状态"),
    ("elder_warn_text", "elder_warn_bg", 4.5, "提醒卡"),
    ("elder_err_text", "elder_err_bg", 4.5, "异常状态"),
    ("elder_danger", "elder_card", 4.5, "对话框危险按钮"),
    ("elder_disabled_text", "elder_disabled_bg", 4.5, "禁用按钮"),
    ("elder_border", "elder_card", 3.0, "输入框边框"),
]
low = []
for fg, bg, need, use in PAIRS:
    if fg not in COLORS or bg not in COLORS:
        low.append(f"{use}: 缺少色值 {fg}/{bg}")
        continue
    r = contrast(fg, bg)
    if r < need:
        low.append(f"{use} {fg}/{bg}={r:.2f}<{need}")
check("E-4", f"适老化色板 {len(PAIRS)} 组前景/背景对比度达标（正文 ≥7:1，其余 ≥4.5:1，边框 ≥3:1）",
      not low, "；".join(low))
v31 = read(RES / "values-v31" / "colors.xml")
check("E-4", "适老化色板不被 v31 莫奈动态取色覆盖（动态色无法保证对比度）", "elder_" not in v31)

# ─────────────────────────── E-6 手表不受影响 ───────────────────────────
leak = [n for n in WATCH_LAYOUTS if re.search(r"@(color|dimen|style|drawable)/(elder_|Elder|bg_elder|btn_elder)",
                                              read(LAYOUT / f"{n}.xml"))]
check("E-6", "手表布局不引用适老化令牌（手表观感零改动）", not leak, f"命中：{leak}" if leak else "")
main = read(SRC / "MainActivity.java")
check("E-6", "主页只在手机形态切换适老化主题",
      "if (!watchUi) ElderUi.applyTheme(this);" in main
      and main.find("ElderUi.applyTheme(this)") < main.find("setContentView("))
elder = read(SRC / "ElderUi.java")
check("E-6", "ElderUi 以 UiForm.isWatch 判定形态（与主页 / 登录页 / 设置页同一套规则）",
      "!UiForm.isWatch(c)" in elder)
login = read(SRC / "LoginActivity.java")
check("E-6", "登录页手表形态仍用改版前的原布局",
      "watch ? R.layout.activity_login_watch : R.layout.activity_login" in login)

# ─────────────────────────── E-7 弹窗与危险操作 ───────────────────────────
bare = len(re.findall(r"new android\.app\.AlertDialog\.Builder\(this\)", main))
wrapped = len(re.findall(r"ElderUi\.show\(new android\.app\.AlertDialog\.Builder\(this\)", main))
check("E-7", "主页全部对话框经 ElderUi.show 放大", bare > 0 and bare == wrapped,
      f"共 {bare} 处，放大 {wrapped} 处")
check("E-7", "主页对话框不再用小字号的 setItems 列表", ".setItems(" not in main)
for act in ("TaskHistory.clear(this)", "store.clearAll()", "FlowMemory.clear(this)", "eco.reset()"):
    i = main.find(act)
    window = main[max(0, i - 400):i]
    check("E-7", f"危险操作 {act} 之前有二次确认", i >= 0 and "confirm(" in window)

# ─────────────────────────── 输出 ───────────────────────────
width = 78
print("=" * width)
print("适老化前端门禁（手机形态）")
print("=" * width)
fails = 0
for cid, desc, ok, note in RESULTS:
    line = f"  {'PASS' if ok else 'FAIL':6} [{cid}] {desc}"
    if note and not ok:
        line += f" — {note}"
    print(line)
    fails += 0 if ok else 1
print("-" * width)
print(f"结果：{len(RESULTS) - fails} 通过 / {fails} 失败")
if fails:
    sys.exit(1)
print("适老化前端门禁 ✅")
