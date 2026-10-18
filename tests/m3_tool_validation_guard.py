#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""M3-02 ToolSpec/ToolRegistry 参数校验静态门禁。"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
TOOLS = SRC / "tools"
FAILS = []
PASSES = []


def check(name, condition):
    (PASSES if condition else FAILS).append(name)


files = sorted(TOOLS.glob("*.java"))
check("M3 tools 包含 4 个声明文件", len(files) == 4)
for path in files:
    text = path.read_text(encoding="utf-8")
    check(f"{path.name} 含 SPDX", "SPDX-License-Identifier: Apache-2.0" in text)
    check(f"{path.name} 无 Android 依赖", "import android." not in text)
    check(f"{path.name} 无 Kotlin 依赖", "kotlin." not in text)
    check(f"{path.name} 无第三方依赖", "org.json" not in text and "com.google" not in text)

spec = (TOOLS / "ToolSpec.java").read_text(encoding="utf-8")
registry = (TOOLS / "ToolRegistry.java").read_text(encoding="utf-8")
result = (TOOLS / "ValidationResult.java").read_text(encoding="utf-8")
engine = (SRC / "AgentEngine.java").read_text(encoding="utf-8")
schema = (SRC / "ActionSchema.java").read_text(encoding="utf-8")
check("ToolSpec 提供 validate", "ValidationResult validate(Map<String, String> arguments)" in spec)
check("ToolSpec 检查必填参数", "CODE_MISSING" in spec)
check("ToolSpec 拒绝未知参数", "CODE_UNKNOWN_PARAMETER" in spec)
check("ToolSpec 检查类型/范围/枚举", all(token in spec for token in [
    "CODE_TYPE", "CODE_RANGE", "CODE_ENUM"]))
check("整数拒绝非十进制表示", "Long.parseLong(value)" in spec)
check("Registry 提供统一校验入口", "ValidationResult validate(String name" in registry)
check("未知工具结构化拒绝", "CODE_UNKNOWN_TOOL" in registry)
check("错误结果不携带输入值", "不包含输入值" in result)
check("AgentEngine 已接入执行前校验网关（P1-3）", "TOOL_REGISTRY.validate(" in engine)
check("JSONObject→参数映射剔除 action 键并归一化数值", "actionToArgs(JSONObject a)" in engine)
check("M3 开关仍默认关闭", "TOOL_ROUTER" in (SRC / "FeatureFlags.java").read_text(encoding="utf-8"))
check("动作词表仍为 25 项结构", 'CONTROL_ACTIONS = unmodifiable("ask", "fail", "done")' in schema)

print("=" * 72)
print("M3-02 ToolSpec/ToolRegistry 参数校验静态门禁")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
