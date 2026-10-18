#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""T05a 任务代际防护静态门禁：旧任务的延迟回调不得改写新任务状态。

两处缺陷（2026-10-09 核验，方案 §2）：
  ① OverlayController.taskEnded(keepCard=true) 的 4 秒 postDelayed 无条件撤胶囊，
     缺 15 秒分支那样的 taskActive 守卫 → A 任务收尾定时器撤掉 B 任务胶囊；
  ② AgentEngine.start 的 finally 延迟回调未绑定任务代际 → 旧任务把新任务前台服务置 false。

纯静态：只读 agent-lite 源码，不需要 SDK/设备/网络。
"""
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from java_source_util import read_java   # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
FAILS = []
PASSES = []


def check(name, condition):
    (PASSES if condition else FAILS).append(name)


def extract_body(text, signature):
    """按大括号配对抽取方法体（含首尾大括号）；找不到返回 None。"""
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
                return text[start:k + 1]
    return None


def call_snippets(body, callee):
    """抽取 callee( ... ) 的完整调用片段（含配对右括号）。"""
    out = []
    pos = 0
    while True:
        i = body.find(callee + "(", pos)
        if i < 0:
            return out
        start = body.find("(", i)
        depth = 0
        for k in range(start, len(body)):
            if body[k] == "(":
                depth += 1
            elif body[k] == ")":
                depth -= 1
                if depth == 0:
                    out.append(body[i:k + 1])
                    pos = k + 1
                    break
        else:
            return out


overlay_src = read_java(SRC / "OverlayController.java")
engine_src = read_java(SRC / "AgentEngine.java")

# ---- ① OverlayController.taskEnded：延迟回调必须带代际/活跃守卫 ----
task_ended = extract_body(overlay_src, "public void taskEnded(boolean keepCard)")
check("taskEnded 方法体可提取", task_ended is not None)
if task_ended:
    delays = call_snippets(task_ended, "postDelayed")
    check("taskEnded 保留两个延迟分支（4 秒胶囊 / 15 秒卡片）", len(delays) == 2)
    for n, snippet in enumerate(delays, 1):
        check(f"taskEnded 第 {n} 个 postDelayed 带 taskActive 守卫",
              "taskActive" in snippet)
    joined = "\n".join(delays)
    check("4 秒分支仍撤胶囊", "removeView(capsule)" in joined)
    check("15 秒分支仍撤卡片（语义未改）", "hideCardSync()" in joined)
    check("15 秒窗口字面量未被改写", "15000" in task_ended)
    check("4 秒窗口字面量未被改写", "4000" in task_ended)

# ---- ② AgentEngine：延迟回调绑定任务代际 ----
check("声明任务代际计数器 RUN_SEQ",
      re.search(r"static\s+final\s+AtomicLong\s+RUN_SEQ\s*=\s*new\s+AtomicLong\(\)", engine_src)
      is not None)
check("代际计数器来自 java.util.concurrent.atomic",
      "import java.util.concurrent.atomic.AtomicLong;" in engine_src)

start_body = extract_body(engine_src, "public synchronized boolean start(String task, String commandId, ResultListener completion)")
check("start 方法体可提取", start_body is not None)
if start_body:
    check("接受任务时取号（incrementAndGet 赋给局部变量）",
          re.search(r"final\s+long\s+\w+\s*=\s*RUN_SEQ\.incrementAndGet\(\)", start_body)
          is not None)

# finally 里的延迟回调：postDelayed 片段含 updateState 时必须含代际比较
stale = []
for snippet in call_snippets(engine_src, "postDelayed"):
    if "updateState" not in snippet:
        continue
    if not re.search(r"\w+\s*==\s*RUN_SEQ\.get\(\)", snippet):
        stale.append(snippet)
check("AgentEngine 的 postDelayed+updateState 组合绑定代际（旧任务不得改写新任务）",
      not stale)
check("KeepAliveService.updateState(app, false) 仍存在（收尾语义未删）",
      "KeepAliveService.updateState(app, false)" in engine_src)

print("=" * 72)
print("T05a 任务代际防护静态门禁")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
