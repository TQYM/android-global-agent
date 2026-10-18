#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""popup_ad_guard.py — 突发界面（弹窗/广告/权限/异常跳转）提示词门禁（免设备）。

    python3 tests/popup_ad_guard.py

为什么需要（2026-09-19 用户新增需求「skill 无法应对弹窗、广告等突发情况」）：
模型侧的突发界面处置纪律全部写在 `AgentEngine.SCHEMA` 的守则 4（突发界面优先处置）。
提示词是纯文本，容易被后续改动无声删掉或削弱——一旦漂移，模型就会回到
「无视弹窗继续原动作（必落空）」「点进广告主体」「权限弹窗乱授权」的老毛病。

本门禁把守则 4 的**每一类突发界面**变成可检查的断言（PU-1 ~ PU-8）：

  PU-1 守则 4 存在且声明「先于原任务检查」的优先级语义
  PU-2 系统权限：最小必要 + 「仅本次允许/使用时允许」优先 + 敏感权限可拒
  PU-3 广告：只点「跳过/关闭/×」类退出按钮 + 严禁点广告主体/下载/抽奖
  PU-4 广告倒计时：先 wait 再找 + 两轮关不掉 → fail 归因（不许点进广告换进度）
  PU-5 运营弹窗：无关任务点关闭/暂不类按钮 + 改变状态的按钮一律不点
  PU-6 登录墙：先绕过；绕不过 → fail「需要登录」+ 严禁自输账号/密码/验证码
  PU-7 异常跳转：key 4 返回复验 + 严禁进应用商店补救（与守则 7 呼应）
  PU-8 复验标准：弹窗消失、底层界面可见才能恢复原任务；同类两轮无效必须换策略
  PU-11 新手引导/分步指引：判定特征 + 任务相关时跟随（逐步+每步复验）+ 无关时快速跳过
        + 跟随中红线不豁免（授权/登录/付费仍走 a)/d)/守则 5）+ 6 步上限与循环防护

配套一致性（PU-9）：`Prefs.java` 里 FlowSpec 转译用的简短守则仍保留
「弹窗、广告、权限请求优先点关闭/跳过」提示——转译层不得比主提示词更弱。

退出码：0 = 无 FAIL（允许 WARN）；1 = 存在 FAIL。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENGINE = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite" / "AgentEngine.java"
PREFS = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite" / "Prefs.java"
POPUP = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite" / "PopupGuard.java"

FAILS: list[str] = []
WARNS: list[str] = []
PASSES: list[str] = []


def read(p: pathlib.Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except Exception:
        return ""


def check(rule: str, desc: str, ok: bool, why: str = "") -> None:
    if ok:
        PASSES.append(f"{rule} {desc}")
    else:
        FAILS.append(f"{rule} {desc}" + (f" —— {why}" if why else ""))


engine_src = read(ENGINE)
prefs_src = read(PREFS)
popup_src = read(POPUP)
if not engine_src:
    print("找不到 agent-lite 的 AgentEngine.java")
    sys.exit(1)

# ---- 提取 SCHEMA 常量文本（拼接的 Java 字符串常量） -----------------------------
m = re.search(r'private static final String SCHEMA = (.*?);\n\n', engine_src, re.S)
if not m:
    print("没能定位 SCHEMA 常量（正则失配或文件被改写）")
    sys.exit(1)
schema_text = m.group(1)


def has(*needles: str) -> bool:
    """SCHEMA 文本里必须同时出现（子串匹配，适配 \" 转义与原文两种形态）。"""
    return all(n in schema_text for n in needles)


# ---- PU-1 守则 4 存在与优先级语义 ----------------------------------------------
check("PU-1", "守则 4「突发界面优先处置」存在",
      has("4. 突发界面优先处置"))
check("PU-1", "突发界面检查声明先于原任务（每步优先检查）",
      has("每步先于原任务检查"))
check("PU-1", "禁止对被遮挡的底层页面旧编号盲点",
      has("旧编号盲点"))

# ---- PU-2 系统权限 -------------------------------------------------------------
check("PU-2", "权限弹窗按最小必要原则授权",
      has("最小必要原则"))
check("PU-2", "「仅本次允许/使用时允许」优先于长期允许",
      has("仅本次允许", "使用时允许"))
check("PU-2", "敏感权限（定位/通讯录/短信/电话/相机/麦克风）任务没用到可拒绝",
      has("敏感权限", "拒绝/不允许"))

# ---- PU-3 广告点击边界 ----------------------------------------------------------
check("PU-3", "广告只点「跳过/跳过广告/×/关闭」类明确退出按钮",
      has("跳过广告", "关闭"))
check("PU-3", "严禁点击广告主体/领取福利/下载 App/抽奖",
      has("严禁点击广告主体", "下载 App"))

# ---- PU-4 广告倒计时与兜底 -------------------------------------------------------
check("PU-4", "跳过按钮倒计时先 wait 1000~2000ms 再找",
      has("wait 1000~2000ms"))
check("PU-4", "两轮关不掉 → fail 归因「广告无法关闭」（不许点进广告换进度）",
      has("广告无法关闭", "绝不为推进任务而点进广告"))

# ---- PU-5 运营弹窗 ---------------------------------------------------------------
check("PU-5", "运营弹窗（领券/红包/评分/更新/会员）无关任务点关闭/暂不类按钮",
      has("运营弹窗", "暂不"))
check("PU-5", "「立即更新/立即开通/立即领取」等改变状态的一律不点",
      has("改变状态的一律不点"))

# ---- PU-6 登录墙 ------------------------------------------------------------------
check("PU-6", "登录墙先绕过（跳过/暂不登录/游客模式）",
      has("暂不登录", "游客模式"))
check("PU-6", "绕不过 → fail 归因「需要登录」",
      has("需要登录"))
check("PU-6", "严禁自行输入账号/密码/验证码",
      has("严禁自行输入任何账号"))

# ---- PU-7 异常跳转 ----------------------------------------------------------------
check("PU-7", "异常跳转（浏览器/应用商店/下载页）key 4 返回并复验",
      has("异常跳转", "key 4 返回并复验"))
check("PU-7", "严禁进应用商店搜索/安装补救（与守则 7 呼应）",
      has("严禁进应用商店搜索/安装"))
check("PU-7", "守则 7 仍是「打开应用只用 app 动作」（编号联动一致）",
      has("7. 打开应用只用 app 动作"))

# ---- PU-8 复验标准 ----------------------------------------------------------------
check("PU-8", "弹窗消失、底层界面可见才能恢复原任务",
      has("复验标准", "才能恢复原任务动作"))
check("PU-8", "同一广告/弹窗两轮处置无效必须换策略或 fail",
      has("两轮处置无效"))

# ---- PU-9 转译层简短守则不弱化 ------------------------------------------------------
check("PU-9", "Prefs 转译守则仍含「弹窗、广告、权限请求优先点关闭/跳过」",
      "弹窗、广告、权限请求" in prefs_src)

# ---- PU-11 新手引导/分步指引（跟随模式 + 跳过模式） -------------------------------
check("PU-11", "守则 4 标题把「新手引导」列为突发界面类别",
      has("新手引导"))
check("PU-11", "引导判定特征（高亮遮罩/气泡提示/下一步/分步圆点）在位",
      has("高亮遮罩", "分步圆点"))
check("PU-11", "任务相关时跟随引导：按指示点高亮元素或下一步逐步行进",
      has("跟随引导", "被高亮的元素"))
check("PU-11", "跟随中每步复验界面变化后再走下一步",
      has("每步复验界面变化"))
check("PU-11", "任务无关时快速跳过：优先「跳过/跳过引导/×/不需要」",
      has("跳过引导", "不需要"))
check("PU-11", "无跳过入口时连续点「下一步/知道了/开始使用/继续」走完",
      has("开始使用"))
check("PU-11", "跟随中红线不豁免（授权/登录/付费/协议仍按 a)/d) 与守则 5）",
      has("红线不豁免", "守则 5 处理"))
check("PU-11", "引导超过 6 步走不完或循环 → 找跳过入口或 fail「引导无法完成」",
      has("引导无法完成"))

# ---- 编号完整性（守则 1~10 顺序存在，防插入错位） -----------------------------------
# 源码里每条守则都是新开一段 Java 字符串：`"4. 突发界面优先处置…`，
# 故按「开引号 + 编号 + 点 + 空格」提取；\n 与下一段之间隔着 `" +` 拼接符，不能直接连写。
nums = [int(n) for n in re.findall(r'"(\d{1,2})\. ', schema_text)]
check("PU-10", "守则编号 1~10 连续且有序",
      nums == list(range(1, 11)),
      f"实际编号序列：{nums}")

# ---- PU-12 模型守则与零模型复现守卫协同 -----------------------------------------
check("PU-12", "复现守卫覆盖广告/引导明确退出词",
      all(x in popup_src for x in ("关闭广告", "跳过广告", "跳过引导", "close ad", "skip ad")),
      "PopupGuard 词表弱于 SCHEMA 守则 4")
check("PU-12", "复现守卫拒绝任务按钮的包含误匹配（完全相等）",
      "t.equals(FlowPlan.normText(w))" in popup_src)
check("PU-12", "弱关闭词经过任务语义闸门",
      "taskNeedsDismissAction" in popup_src and "isDismissAnchor(cap, task)" in engine_src)
check("PU-12", "单字符关闭图标同时受可点击性与位置约束",
      "!n.clickable" in popup_src and "nearSide" in popup_src and "upper" in popup_src)
check("PU-12", "复现路径每步先处置弹窗、敏感守卫仍优先",
      engine_src.find("SensitiveGuard.scan(nodes)") < engine_src.find("PopupGuard.findDismissable(nodes, currentTask"))

# ---- 输出 --------------------------------------------------------------------
W = 78
print("=" * W)
print("突发界面提示词门禁（SCHEMA 守则 4：弹窗/广告/权限/异常跳转）")
print("=" * W)
for s in PASSES:
    print(f"  PASS  {s}")
for s in WARNS:
    print(f"  WARN  {s}")
for s in FAILS:
    print(f"  FAIL  {s}")
print("-" * W)
print(f"结果：{len(PASSES)} 通过 / {len(WARNS)} 警告 / {len(FAILS)} 失败")
if not FAILS:
    print("突发界面处置守则完整在位 ✅")
sys.exit(1 if FAILS else 0)
