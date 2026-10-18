#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""flow_memory_guard.py — 操作记忆库门禁（免设备）。

    python3 tests/flow_memory_guard.py

对应设计：docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md §4.4 / §5.4 / §9.3
与用户要求「每一步成功执行的操作进记忆库，之后直接复现就能完成操作」。

设计原则与 spec_ux_001_guard.py / fuzzy_flow_guard.py 一致：**只做能真伪判定的静态断言**。
「复现到底能不能点对」无法静态判定——那由 tests/FlowMemoryTest.java（桌面 JVM）与真机回归负责；
本脚本守的是**那些一旦被改掉就会静默出事的结构性事实**：

  · 纯逻辑层被掺进 android 依赖 → 桌面回归直接编不过，等于测试名存实亡；
  · 默认开关被改成 false → 用户要的"默认就会记住"悄悄消失，还没人看得出来；
  · 入库点从 done 挪到别处 → 失败路径也会被当成"成功经验"照做；
  · 复现失败后不回退模型 → 一条过时记录能把任务彻底卡死；
  · 白名单被放宽（收进 remote/open_api/open_url）→ 会照抄上次的收款人/金额/外部链接
    再执行一遍（R-5：直达/跨设备/DeepLink 动作整条路径不得入库）；
  · 凭据闸门被删 → 验证码/密码写进明文 JSON；
  · 复现路径绕过敏感守卫 → 记忆库变成一条绕过红线检查的捷径。

退出码：0 = 无 FAIL（可有 MANUAL）；1 = 存在 FAIL。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite"

RESULTS: list[tuple[str, str, str, str]] = []   # (id, 描述, 状态, 备注)


def read(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception:
        return ""


def check(cid: str, desc: str, ok: bool, note: str = "") -> None:
    RESULTS.append((cid, desc, "PASS" if ok else "FAIL", note))


def manual(cid: str, desc: str, note: str = "") -> None:
    RESULTS.append((cid, desc, "MANUAL", note))


def strip_comments(java: str) -> str:
    """去掉注释与字符串字面量，只留**代码骨架**。

    必要性：本门禁要判定的是"代码里有没有引用 X"，而源码里到处都在**用注释解释**
    为什么不用 X（例如 FlowMemory 的类注释写明「刻意不接 SyncClient」）。不剥注释的话，
    一份把理由写得越清楚的代码越容易被自己的门禁误报——实测踩过：FM-1/FM-8 两条
    全部因为注释里出现 "org.json" / "SyncClient" 而假失败。
    """
    out = []
    i, n = 0, len(java)
    while i < n:
        c = java[i]
        if c == '/' and i + 1 < n and java[i + 1] == '/':
            while i < n and java[i] != '\n':
                i += 1
        elif c == '/' and i + 1 < n and java[i + 1] == '*':
            i += 2
            while i + 1 < n and not (java[i] == '*' and java[i + 1] == '/'):
                i += 1
            i += 2
        elif c in ('"', "'"):
            quote = c
            i += 1
            while i < n and java[i] != quote:
                if java[i] == '\\':
                    i += 1
                i += 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


plan = read(SRC / "FlowPlan.java")
memory = read(SRC / "FlowMemory.java")
engine = read(SRC / "AgentEngine.java")
prefs = read(SRC / "Prefs.java")
main_ui = read(SRC / "MainActivity.java")
watch_ui = read(SRC / "WatchSettingsActivity.java")
makefile = read(ROOT / "Makefile")

plan_code = strip_comments(plan)
memory_code = strip_comments(memory)
engine_code = strip_comments(engine)

# ---------------------------------------------------------------- FM-1 纯逻辑边界
check("FM-1", "FlowPlan 零 android / org.json 依赖（可桌面直跑回归）",
      bool(plan) and "import android" not in plan and "import org.json" not in plan,
      "FlowPlan 掺进了设备依赖，桌面回归会失去意义" if plan else "找不到 FlowPlan.java")
check("FM-1", "锚点匹配是纯函数（复现成败的核心判断可单测）",
      "public static int matchAnchor(" in plan)

# ---------------------------------------------------------------- FM-2 默认开启
check("FM-2", "记忆库默认开启（用户要的是「默认就会记住」）",
      bool(re.search(r'public boolean flowMemory\(\)\s*\{\s*return sp\.getBoolean\("flow_memory",\s*true\)',
                     prefs)),
      "Prefs#flowMemory 的默认值不是 true")
check("FM-2", "存在关闭通道（关掉即回到每次都重新规划）",
      "setFlowMemory" in prefs and "setFlowMemory" in main_ui)

# ---------------------------------------------------------------- FM-3 只在 done 入库
done_block = ""
i = engine.find('if ("done".equals(kind))')
if i >= 0:
    done_block = engine[i:i + 2600]
check("FM-3", "入库点只在终态 done（失败路径不得被当成成功经验）",
      "FlowMemory.commit(" in done_block,
      "done 分支里没有调用 FlowMemory.commit" if i >= 0 else "找不到 done 分支")
check("FM-3", "fail 分支不写入记忆库",
      bool(re.search(r'if \("fail"\.equals\(kind\)\)\s*\{[^}]*failTask', engine))
      and "FlowMemory.commit" not in engine[engine.find('if ("fail".equals(kind))'):][:600])
check("FM-3", "采集遇到白名单外动作即整条作废（不写半条流程）",
      "recordable = false;" in engine and "recordedSteps.clear();" in engine)

# ---------------------------------------------------------------- FM-4 复现必有回退
check("FM-4", "命中后直接复现（跳过转译与逐步推理）",
      "FlowMemory.match(app, task" in engine and "replayFlow(memo, maxSteps)" in engine)
check("FM-4", "复现失败 → 记一次失败并继续走模型规划（不把任务判死）",
      "FlowMemory.markFail(app, memo.fp);" in engine
      and engine.find("FlowMemory.markFail(app, memo.fp);") < engine.find("String plan = planTask(prefs, task);"),
      "复现失败后没有回退到模型规划")
check("FM-4", "复现成功才计入成功（统计不虚高）",
      "FlowMemory.markOk(app, memo.fp);" in engine)
check("FM-4", "复现中途连续两步屏幕无变化即放弃（界面已变）",
      "noChange >= 2" in engine, "缺少「界面已变」的判断，会一直照旧坐标点下去")
check("FM-4", "复现复用既有 exec 执行层（不另造一条执行路径）",
      re.search(r"label = exec\(svc, act, nodes\);", engine) is not None)

# ---------------------------------------------------------------- FM-5 红线：敏感守卫
replay_body = ""
j = engine.find("private int replayFlow(")
if j >= 0:
    replay_body = engine[j:j + 12000]
check("FM-5", "复现路径同样过敏感场景守卫（记忆库不是免检通道）",
      "SensitiveGuard.scan(nodes)" in replay_body,
      "复现里少了 SensitiveGuard，等于给红线开了一条后门" if replay_body else "找不到 replayFlow")

# ---------------------------------------------------------------- FM-6 白名单边界
wl = ""
k = plan.find("REPLAYABLE = Collections")
if k >= 0:
    wl = plan[k:plan.find(";", k)]
for banned in ("remote", "open_api", "open_url", "list_apps", "ask", "fail", "done", "setting"):
    check("FM-6", f"白名单不含 {banned}（照做会重复副作用/依赖当次语境）",
          f'"{banned}"' not in wl, f"{banned} 出现在 REPLAYABLE 里")
check("FM-6", "旧库含 open_url 的记录不再加载（R-5 迁移防线）",
      "hasOpenUrl(r)" in memory, "list() 未过滤含 open_url 步骤的旧记录")
check("FM-6", "采集侧按白名单判定（不是只靠入库时兜底）",
      "FlowPlan.REPLAYABLE.contains(kind)" in engine)

# 还原动作的白名单兜底在 stepToAction 里（它定义在 replayFlow 之后，不能共用切片）
to_action = ""
m = engine.find("private JSONObject stepToAction(")
if m >= 0:
    to_action = engine[m:m + 3200]
check("FM-6", "还原动作时白名单外的动作一律放弃复现",
      "default:" in to_action and "return null;" in to_action,
      "stepToAction 的 default 分支没有放弃复现" if to_action else "找不到 stepToAction")

# ---------------------------------------------------------------- FM-7 凭据红线
check("FM-7", "入库前检查输入内容是否疑似凭据",
      "hasCredential(s.text)" in plan,
      "rejectReason 里少了凭据检查")
check("FM-7", "确认无凭据闸门被绕过：commit 走 rejectReason",
      "FlowPlan.rejectReason(steps)" in memory or "rejectReason(steps)" in memory)
check("FM-7", "密码框（secret）不采集文本锚点",
      "n.secret ? \"\"" in engine, "secret 节点的文本会进记忆库")

# ---------------------------------------------------------------- FM-8 隐私边界
check("FM-8", "操作记忆不上云（代码里不引用 SyncClient）",
      "SyncClient" not in memory_code,
      "FlowMemory 引用了 SyncClient：操作轨迹会被同步到账号")
check("FM-8", "本地可查可删（记忆库页列出并支持删除/清空）",
      "FlowMemory.list(" in main_ui and "FlowMemory.clear(" in main_ui,
      "记忆库页没有列出操作记忆")

# ---------------------------------------------------------------- FM-9 落地完整性
check("FM-9", "手表端同样可查可删",
      "FlowMemory.list(" in watch_ui, "手表端记忆库页未接入")
check("FM-9", "桌面 JVM 回归存在（tests/FlowMemoryTest.java）",
      (ROOT / "tests" / "FlowMemoryTest.java").exists())
check("FM-9", "Makefile 已挂载本门禁与桌面回归",
      "flow_memory_guard.py" in makefile and "FlowMemoryTest" in makefile)
check("FM-9", "转译层产出流程骨架（同款不同参命中的前提）",
      "r.skeleton = FuzzyFlow.skeleton(" in read(SRC / "SemanticTranslator.java"))
manual("FM-9", "真机验证：同一任务连说两次，第二次应零模型往返直接完成",
       "需要设备：看 engine.log 的「命中操作记忆库」与步数")

# ---------------------------------------------------------------- FM-10 三功能协同：弹窗噪音不得污染/贬值流程
popup = read(SRC / "PopupGuard.java")
a11y = read(SRC / "AgentA11yService.java")
check("FM-10", "采集侧跳过一次性弹窗步骤，但保留其余可复现步骤",
      "PopupGuard.isDismissAnchor(cap, task)" in engine
      and "recordedSteps.add(cap);" in engine,
      "采集仍会把关弹窗的 tap 写入流程，或误把整条流程作废")
check("FM-10", "复现每步在还原业务动作前先检查突发界面",
      replay_body.find("PopupGuard.findDismissable(nodes, currentTask") >= 0
      and replay_body.find("PopupGuard.findDismissable(nodes, currentTask")
          < replay_body.find("stepToAction(s, nodes"),
      "复现仍是先点被遮挡的底层节点、失败后才看弹窗")
check("FM-10", "环境打断有独立三态，不与路径失效混为一次 markFail",
      "REPLAY_INTERRUPTED" in engine and "replayed == REPLAY_STALE" in engine)
check("FM-10", "弹窗处置额度耗尽返回 INTERRUPTED（不贬值好记录）",
      "突发界面处置已到上限" in replay_body and "return REPLAY_INTERRUPTED;" in replay_body)
check("FM-10", "全是旧弹窗步时拒绝伪报完成",
      "executed <= 0" in replay_body and "return REPLAY_INTERRUPTED;" in replay_body)
check("FM-10", "复现成功前核验最终前台证据",
      "expectedFinal" in replay_body and "actualFinal" in replay_body)
check("FM-10", "无节点/无坐标点击不会写入必坏记录",
      "s.anchor.isEmpty() && s.rid.isEmpty() && s.cls.isEmpty() && !s.hasPoint()" in engine)
check("FM-10", "系统权限窗纳入感知，且暴露顶层窗口只读探测",
      "TYPE_SYSTEM" in a11y and "topWindowIsSystem()" in a11y)
check("FM-10", "PopupGuard 有独立桌面 JVM 回归并挂入 Makefile",
      (ROOT / "tests" / "PopupGuardTest.java").exists()
      and "PopupGuardTest" in makefile and bool(popup))

# ---------------------------------------------------------------- FM-11 落盘健壮性（2026-10-02 审计 P1-B）
check("FM-11", "落盘走临时文件 + fsync + rename 原子提交",
      "flowmem.json.tmp" in memory and "getFD().sync()" in memory,
      "进程死亡/磁盘异常会把整个库截断成半个 JSON")
check("FM-11", "损坏文件备份为 .corrupt（可区分「没有记录」与「记录损坏」）",
      "flowmem.json.corrupt" in memory, "读取损坏仍按空库静默处理")
check("FM-11", "写失败留诊断日志（不再是静默空操作）",
      "记忆库写入失败" in memory, "写失败完全不可见")

# ---------------------------------------------------------------- 输出
width = 78
print("=" * width)
print("操作记忆库门禁（FlowMemory / FlowPlan）")
print("=" * width)
fails = 0
for cid, desc, state, note in RESULTS:
    line = f"  {state:6} [{cid}] {desc}"
    if note and state != "PASS":
        line += f" — {note}"
    print(line)
    if state == "FAIL":
        fails += 1
print("-" * width)
print(f"结果：{sum(1 for r in RESULTS if r[2] == 'PASS')} 通过 / {fails} 失败 / "
      f"{sum(1 for r in RESULTS if r[2] == 'MANUAL')} 待真机")
if fails == 0:
    print("操作记忆库（可静态判定部分）通过 ✅")
sys.exit(1 if fails else 0)
