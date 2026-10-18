#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""M0-02 四类契约的零依赖与版本静态门禁。"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
CONTRACTS = SRC / "contracts"
FAILS = []
PASSES = []


def check(name, condition):
    (PASSES if condition else FAILS).append(name)


files = sorted(CONTRACTS.glob("*.java"))
check("contracts 包存在", len(files) == 5)
for path in files:
    text = path.read_text(encoding="utf-8")
    check(f"{path.name} 含 SPDX", "SPDX-License-Identifier: Apache-2.0" in text)
    check(f"{path.name} 无 Android 依赖", "import android." not in text)
    check(f"{path.name} 无 org.json 依赖", "org.json" not in text)
    check(f"{path.name} 无第三方 JSON 库", "com.google.gson" not in text and "com.fasterxml.jackson" not in text)

versions = (SRC / "ContractVersions.java").read_text(encoding="utf-8")
check("四类版本常量存在", all(token in versions for token in [
    "SNAPSHOT_SCHEMA_VERSION = 1",
    "TOOL_SCHEMA_VERSION = 1",
    "TRACE_SCHEMA_VERSION = 1",
    'MACRO_SCHEMA_VERSION = "3.0"',
]))
check("Trace 默认截图策略冻结", "TRACE_SCREENSHOT_DEFAULT = true" in versions)
check("Trace 仅本地策略冻结", "TRACE_LOCAL_ONLY = true" in versions)
check("Trace 固定容量轮转冻结", "TRACE_MAX_RUNS = 20" in versions and "TRACE_MAX_TOTAL_BYTES" in versions)

snapshot = (CONTRACTS / "ScreenSnapshot.java").read_text(encoding="utf-8")
tool = (CONTRACTS / "ToolInvocation.java").read_text(encoding="utf-8")
trace = (CONTRACTS / "TraceEvent.java").read_text(encoding="utf-8")
replay = (CONTRACTS / "ScreenState.java").read_text(encoding="utf-8")
check("密码掩码固定", 'PASSWORD_MASK = "[password]"' in snapshot)
check("工具终态集合存在", all(token in tool for token in ["STATE_SUCCESS", "STATE_ERROR", "STATE_CANCELLED"]))
check("Trace 八类事件存在", all(token in trace for token in ["PERCEPTION", "LLM_REQUEST", "LLM_RESPONSE", "TOOL_CALL", "APPROVAL", "VERIFICATION", "TERMINAL", "ERROR"]))
check("旧流程兼容标记存在", "LEGACY_UNGUARDED" in replay and "allowsLegacyReplay" in replay)

print("=" * 72)
print("M0-02 四类契约静态门禁")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
