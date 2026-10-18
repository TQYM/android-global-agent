#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""T04a done 证据分级门禁：真值表行为测试 + 终态/入库结构守卫。

两半合成一个脚本：
  ① 桌面 JVM：从 AgentEngine.java **抽取真实的 judgeDone 与三档常量**编译直测
     （抽取法照 tests/credential_route_guard.py；禁止用手写替身冒充行为测试）；
  ② 静态结构：UNPROVEN 终态必须是「不确定」且 recordable=false、且**不进记忆库**，
     而 PROVEN/WEAK 仍走现行完成路径并保留入库。

纯本地：不需要 SDK/设备/网络。
"""
from pathlib import Path
import os
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from java_source_util import read_java   # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
OUT = ROOT / ".check-tmp/done-evidence"
FAILS = []
PASSES = []


def check(name, condition):
    (PASSES if condition else FAILS).append(name)


def body_span(text, signature):
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


engine = read_java(SRC / "AgentEngine.java")

# ---------- ① 抽取真实判定函数并编译直测 ----------
consts = dict(re.findall(r"static\s+final\s+int\s+(DONE_PROVEN|DONE_WEAK|DONE_UNPROVEN)\s*=\s*(-?\d+)\s*;", engine))
check("三档常量齐备（DONE_PROVEN/DONE_WEAK/DONE_UNPROVEN）", len(consts) == 3)
check("三档常量取值互不相同", len(set(consts.values())) == 3)

judge = re.search(
    r"\n    static int judgeDone\(boolean sawFgChange, String expectPkg, String activePkg\) \{.*?\n    \}",
    engine, re.S)
check("judgeDone 静态判定存在（无 android 依赖，可桌面直测）", judge is not None)
judge_body = judge.group(0) if judge else ""
check("judgeDone 不引入 android 依赖", "android." not in judge_body)

if judge and len(consts) == 3:
    harness = ("package com.dsh.agentlite;\n\n"
               "public final class DoneJudgeHarness {\n"
               + "".join(f"    static final int {k} = {v};\n" for k, v in consts.items())
               + "\n"
               + judge_body.strip("\n") + "\n}\n")
    src = OUT / "src/com/dsh/agentlite/DoneJudgeHarness.java"
    src.parent.mkdir(parents=True, exist_ok=True)
    src.write_text(harness, encoding="utf-8")
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    rc = subprocess.run(
        ["javac", "--release", os.getenv("JAVA_RELEASE", "11"), "-encoding", "UTF-8",
         "-d", str(classes), str(src), str(ROOT / "tests/DoneEvidenceTest.java")],
        cwd=ROOT).returncode
    if rc != 0:
        FAILS.append("judgeDone 抽取代码无法编译")
    else:
        rc = subprocess.run(
            ["java", "-cp", str(classes), "com.dsh.agentlite.DoneEvidenceTest"], cwd=ROOT).returncode
        check("judgeDone 真值表全部通过（PROVEN/WEAK/UNPROVEN 三档语义）", rc == 0)

# ---------- ② 终态与入库结构守卫 ----------
done_span = body_span(engine, 'if ("done".equals(kind)) {')
check("done 分支可定位", done_span is not None)
if done_span:
    done_body = engine[done_span[0]:done_span[1]]
    check("done 分支接入证据分级（调 judgeDone）", "judgeDone(" in done_body)
    check("追问条件由 UNPROVEN 判定驱动（不再是裸前台变化判断）",
          "DONE_UNPROVEN" in done_body and "verdict" in done_body)
    check("二次 done 不再无条件放行（UNPROVEN 有独立终态分支）",
          re.search(r"if\s*\(\s*verdict\s*==\s*DONE_UNPROVEN\s*\)", done_body) is not None)

    m = re.search(r"if\s*\(\s*verdict\s*==\s*DONE_UNPROVEN\s*\)\s*\{", done_body)
    if m:
        unproven = body_span(done_body, "if (verdict == DONE_UNPROVEN)")
        block = done_body[unproven[0]:unproven[1]] if unproven else ""
        check("UNPROVEN 置 recordable=false（不入库）", "recordable = false" in block)
        check("UNPROVEN 不调用 FlowMemory.commit", "FlowMemory.commit" not in block)
        check("UNPROVEN 终态记为「不确定」", '"不确定"' in block)
        check("UNPROVEN 用卡片的「不确定」语义位（showUncertain，不是 showDone）",
              "showUncertain(" in block and "showDone(" not in block)
        check("UNPROVEN 文案如实说明无证据（不含「完成」字样）",
              "未观察到" in block and "完成" not in block)
        check("UNPROVEN 仍以 return 收口（不会掉进完成路径）",
              re.search(r"return;", block) is not None)
    else:
        FAILS.append("找不到 UNPROVEN 分支")

    # 完成路径（PROVEN/WEAK）保持现行语义：入库仍在
    check("PROVEN/WEAK 仍走现行完成路径并保留入库",
          "FlowMemory.commit(" in done_body and 'history("完成"' in done_body)
    check("追问仍只发生一次（doneVerifyAsked 保留）", "doneVerifyAsked" in done_body)

# 不确定文案守则：终态文案不得出现「完成」字样
unproven_msgs = re.findall(r'"([^"]*)"', engine)
check("引擎内「不确定」相关终态文案不含「完成」字样",
      not any("完成" in s and "不确定" in s for s in unproven_msgs))

print("=" * 72)
print("T04a done 证据分级门禁（真值表 + 终态/入库结构）")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
