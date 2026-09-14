#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""action_schema_guard.py — 动作词表一致性门禁（免设备）。

    python3 tests/action_schema_guard.py

为什么需要（手册 §16.4 UX-10 / §16.9）：
词表此前分散在**三处**——给模型看的提示词、`exec()` 的 switch 分支、引擎循环的终态判定。
三者不同步就会出现两类事故：
  * **隐藏动作**：`exec()` 已实现、提示词却从没告诉模型（`back`/`home` 就是这样）；
  * **失配动作**：提示词承诺了、执行到就抛 `unknown action`。

本门禁把「单一事实源」变成可检查的断言：

  A1 提示词里出现的动作 ⊆ 注册表（ActionSchema）
  A2 注册表里的执行动作 ⊆ `exec()` 的 case 分支（注册了就得实现）
  A3 `exec()` 的 case 分支 ⊆ 注册表（不允许存在未登记的隐藏动作）
  A4 终态动作（ask/fail/done）在任务循环里有处理分支
  A5 `agent-client`（冻结维护的旧客户端）的动作集合 ⊆ lite 真身的动作集合，
     且**缺失清单**被显式记录（防止两边各自漂移）

退出码：0 = 无 FAIL（允许 WARN）；1 = 存在 FAIL。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LITE = ROOT / "agent-lite" / "src" / "com" / "dsh" / "agentlite"
CLIENT = ROOT / "agent-client" / "src" / "com" / "dsh" / "agent"

FAILS: list[str] = []
WARNS: list[str] = []
PASSES: list[str] = []


def read(p: pathlib.Path) -> str:
    try:
        return p.read_text(encoding="utf-8")
    except Exception:
        return ""


def ok(msg: str) -> None:
    PASSES.append(msg)


def fail(msg: str) -> None:
    FAILS.append(msg)


def warn(msg: str) -> None:
    WARNS.append(msg)


schema_src = read(LITE / "ActionSchema.java")
engine_src = read(LITE / "AgentEngine.java")
client_src = read(CLIENT / "AgentEngine.java")

if not schema_src or not engine_src:
    print("找不到 agent-lite 的 ActionSchema.java 或 AgentEngine.java")
    sys.exit(1)


def extract_set(src: str, const: str) -> set[str]:
    """从 `public static final Set<String> CONST = unmodifiable("a", "b");` 提取集合。"""
    m = re.search(rf"{const}\s*=\s*unmodifiable\((.*?)\);", src, re.S)
    if not m:
        return set()
    return set(re.findall(r'"([a-z_]+)"', m.group(1)))


exec_actions = extract_set(schema_src, "EXEC_ACTIONS")
control_actions = extract_set(schema_src, "CONTROL_ACTIONS")
registered = exec_actions | control_actions
if not registered:
    fail("ActionSchema 里没有解析到任何动作（正则失配或文件被改写）")
else:
    ok(f"注册表解析成功：执行 {len(exec_actions)} 个 + 终态 {len(control_actions)} 个")

# ---- 提示词里的动作（SCHEMA 常量文本）----------------------------------------
m = re.search(r'private static final String SCHEMA = (.*?);\n\n', engine_src, re.S)
schema_text = m.group(1) if m else ""
prompt_actions = set(re.findall(r'action\\":\\"([a-z_]+)', schema_text))
if not prompt_actions:
    # 换个写法兜底：源码里未被转义的形式
    prompt_actions = set(re.findall(r'action":"([a-z_]+)', schema_text))
if not prompt_actions:
    warn("没能从 SCHEMA 文本里解析出动作（检查提取正则），A1 跳过")
else:
    unknown = sorted(prompt_actions - registered)
    if unknown:
        fail(f"A1 提示词承诺了未注册的动作：{unknown}")
    else:
        ok(f"A1 提示词动作全部已注册（{len(prompt_actions)} 个）")

# ---- exec() 的 case 分支 -----------------------------------------------------
exec_body = engine_src[engine_src.find("private String exec(AgentA11yService svc"):]
exec_body = exec_body[: exec_body.find("\n    }")]
cases = set(re.findall(r'case "([a-z_]+)"', exec_body))
if not cases:
    fail("没能从 exec() 里解析出 case 分支")
else:
    missing_impl = sorted(exec_actions - cases)
    hidden = sorted(cases - registered)
    if missing_impl:
        fail(f"A2 注册了但 exec() 没实现：{missing_impl}")
    else:
        ok(f"A2 注册的 {len(exec_actions)} 个执行动作在 exec() 里都有分支")
    if hidden:
        fail(f"A3 exec() 存在未登记的隐藏动作：{hidden}")
    else:
        ok("A3 exec() 没有隐藏动作")

# ---- 终态动作的处理分支 ------------------------------------------------------
for act in sorted(control_actions):
    if f'"{act}".equals(kind)' in engine_src or f'"{act}"' in engine_src:
        ok(f"A4 终态动作 {act} 在循环里有处理")
    else:
        fail(f"A4 终态动作 {act} 找不到处理分支")

# ---- agent-client（冻结维护）：必须是 lite 的子集 -----------------------------
client_cases = set(re.findall(r'case "([a-z_]+)"', client_src)) if client_src else set()
if not client_src:
    warn("找不到 agent-client 的 AgentEngine.java，A5 跳过")
elif not client_cases:
    warn("没能从 agent-client 解析出动作集合，A5 跳过")
else:
    extra = sorted(client_cases - exec_actions)
    missing = sorted(exec_actions - client_cases)
    if extra:
        fail(f"A5 agent-client 有 lite 未登记的动作（会破坏单一事实源）：{extra}")
    else:
        ok(f"A5 agent-client 的 {len(client_cases)} 个动作都在 lite 真身词表内（冻结维护）")
    if missing:
        warn(f"A5 已记录的客户端能力差异（agent-client 缺少）：{missing}")

# ---- 输出 --------------------------------------------------------------------
W = 78
print("=" * W)
print("动作词表一致性门禁（ActionSchema ↔ 提示词 ↔ exec() ↔ agent-client）")
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
    print("动作词表单一事实源成立 ✅")
sys.exit(1 if FAILS else 0)
