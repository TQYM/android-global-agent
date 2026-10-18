#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""T03a 动作入口收敛静态门禁：引擎内一切触摸都必须经 exec()。

背景（方案 §3）：autoConfirmLaunch（启动确认代点）与 autoAgreeTerms（无限制模式
协议代点）曾直接调 svc.tap / svc.tapNode，绕过 exec() 的坐标修正、日志与失败回报。
本门禁把"直接触摸"收敛为结构约束：**全文件仅允许 exec() 方法体内出现
svc.tap( / svc.tapNode(**，代点方法体必须经 exec() 下发。

纯静态：只读 agent-lite 源码，不需要 SDK/设备/网络。
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from java_source_util import read_java   # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
FAILS = []
PASSES = []


def check(name, condition):
    (PASSES if condition else FAILS).append(name)


def body_span(text, signature):
    """返回 (start, end) 方法体大括号区间；找不到返回 None。"""
    idx = text.find(signature)
    if idx < 0:
        return None
    start = text.find("{", idx + len(signature) - 1)
    if start < 0:
        return None
    depth = 0
    for k in range(start, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return (start, k + 1)
    return None


def body_text(text, signature):
    span = body_span(text, signature)
    return None if span is None else text[span[0]:span[1]]


engine = read_java(SRC / "AgentEngine.java")
prefs = read_java(SRC / "Prefs.java")

exec_span = body_span(engine, "private String exec(AgentA11yService svc, JSONObject a, List<NodeInfo> currentNodes)")
check("exec() 方法体可定位", exec_span is not None)

# ---- ① 全文件：直接触摸只允许出现在 exec() 内 ----
# 受约束例外：injectViaClipboard 的 root 可选通道里那次"聚焦点击"。
# 理由：该处坐标已由 resolvePoint 算好，而 resolvePoint 的 DisplaySpec.applyOffset
# 是**非幂等**加法（DisplaySpec.java:68），再经 exec 会二次偏移，属本卡范围外的行为风险；
# 例外必须仍处于 RootShell.available(app) 守卫之下、且同方法内保留零 root 降级
# （clipPending + 长按引导），否则门禁 FAIL。
inject_span = body_span(engine, "private boolean injectViaClipboard(AgentA11yService svc, JSONObject a, String text)")
check("injectViaClipboard 方法体可定位", inject_span is not None)
root_exempt_ok = False
if inject_span:
    inject_body = engine[inject_span[0]:inject_span[1]]
    root_exempt_ok = ("RootShell.available(app)" in inject_body
                      and "clipPending = true;" in inject_body)

if exec_span:
    outside = []
    for callee in ("svc.tap(", "svc.tapNode("):
        pos = 0
        while True:
            i = engine.find(callee, pos)
            if i < 0:
                break
            if not (exec_span[0] <= i < exec_span[1]):
                in_exempt = (root_exempt_ok and inject_span is not None
                             and inject_span[0] <= i < inject_span[1]
                             and "RootShell.available(app)" in engine[inject_span[0]:i])
                if not in_exempt:
                    line = engine[:i].count("\n") + 1
                    outside.append(f"{callee}@L{line}")
            pos = i + 1
    check(f"svc.tap( / svc.tapNode( 仅在 exec() 内（越界点：{outside or '无'}）",
          not outside)
    check("exec() 内仍保留节点直点与手势兜底两条路径",
          "svc.tapNode(" in engine[exec_span[0]:exec_span[1]]
          and "svc.tap(" in engine[exec_span[0]:exec_span[1]])

# ---- ② 两个代点方法：不得直接触摸，且必须经 exec() ----
confirm = body_text(engine, "private boolean autoConfirmLaunch(AgentA11yService svc, List<NodeInfo> nodes)")
check("autoConfirmLaunch 方法体可提取", confirm is not None)
if confirm:
    check("autoConfirmLaunch 无直接 svc.tap/svc.tapNode",
          "svc.tap(" not in confirm and "svc.tapNode(" not in confirm)
    check("autoConfirmLaunch 经 exec() 下发点击", "exec(svc," in confirm)

terms = body_text(engine, "private boolean autoAgreeTerms(AgentA11yService svc, List<NodeInfo> nodes)")
check("autoAgreeTerms 方法体可提取", terms is not None)
if terms:
    check("autoAgreeTerms 无直接 svc.tap/svc.tapNode",
          "svc.tap(" not in terms and "svc.tapNode(" not in terms)
    check("autoAgreeTerms 两处代点都经 exec()（勾选框 + 同意按钮）",
          terms.count("exec(svc,") >= 2)

# ---- ③ 代点触发条件与开关语义不得被本卡改动 ----
if confirm:
    check("启动确认代点仍只认三种允许文案",
          all(s in confirm for s in ("仅本次允许", "允许", "30天内允许")))
    check("启动确认代点仍要求弹窗由本助手拉起",
          "想要打开" in confirm)
if terms:
    check("协议代点仍由 autoAcceptTerms 开关守卫（默认关语义保留）",
          "autoAcceptTerms()" in terms.split("\n")[0] or "autoAcceptTerms()" in terms)
    check("协议代点仍先勾选后点同意（两轮遍历保留）",
          terms.count("for (NodeInfo n : nodes)") >= 2)
check('Prefs.autoAcceptTerms 默认 false（开关语义不得被本卡改动）',
      'autoAcceptTerms() { return sp.getBoolean("auto_accept_terms", false); }' in prefs)

print("=" * 72)
print("T03a 动作入口收敛静态门禁（代点进 exec）")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
