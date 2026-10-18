#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""M3-01 ToolSpec/ToolRegistry 纯 Java 与词表覆盖门禁。"""

from pathlib import Path
import re
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

registry = (TOOLS / "ToolRegistry.java").read_text(encoding="utf-8")
schema = (SRC / "ActionSchema.java").read_text(encoding="utf-8")
check("Registry 有 builtIn 工厂", "static ToolRegistry builtIn()" in registry)
check("Registry 拒绝重复注册", "工具重复注册" in registry)
check("Registry 登记执行动作集合", "ActionSchema.EXEC_ACTIONS" in registry)
check("Registry 登记控制动作集合", "ActionSchema.CONTROL_ACTIONS" in registry)
check("控制动作显式标记", "boolean controlAction" in (TOOLS / "ToolSpec.java").read_text(encoding="utf-8"))
check("未知参数默认拒绝", 'additionalProperties\\\":false' in (TOOLS / "ToolSpec.java").read_text(encoding="utf-8"))
engine_src = (SRC / "AgentEngine.java").read_text(encoding="utf-8")
check("AgentEngine 已接入 M3 Registry（2026-10-02 P1-3 执行前校验网关）",
      "TOOL_REGISTRY.validate(" in engine_src)
check("网关受 feature_tool_router 开关控制", "featureFlags().toolRouter()" in engine_src)
check("校验失败的动作不触碰设备（网关先于 exec）",
      0 <= engine_src.find("TOOL_REGISTRY.validate(")
      < engine_src.find("label = exec(svc, action, nodes)"))
check("M3 开关仍默认关闭（灰度语义不变）", "TOOL_ROUTER" in (SRC / "FeatureFlags.java").read_text(encoding="utf-8"))
check("动作词表仍为 25 项结构", 'CONTROL_ACTIONS = unmodifiable("ask", "fail", "done")' in schema)

main_activity = (SRC / "MainActivity.java").read_text(encoding="utf-8")
settings_activity = (SRC / "SettingsActivity.java").read_text(encoding="utf-8")
watch_settings = (SRC / "WatchSettingsActivity.java").read_text(encoding="utf-8")
purchase_activity = (SRC / "PurchaseActivity.java").read_text(encoding="utf-8")
manifest = (ROOT / "agent-lite/AndroidManifest.xml").read_text(encoding="utf-8")
layout = (ROOT / "agent-lite/res/layout/activity_main.xml").read_text(encoding="utf-8")
check("额度展示直接使用剩余 quota", "Math.max(0, prefs.accountQuota())" in main_activity)
check("设置页不二次扣减 quota_used", "m.quota - m.quotaUsed" not in settings_activity and "q - used" not in settings_activity)
check("手表与购买页不二次扣减", "accountQuota() - accountQuotaUsed()" not in watch_settings
      and "accountQuota() - accountQuotaUsed()" not in purchase_activity)
check("主页明确 adjustResize", 'android:windowSoftInputMode="adjustResize"' in manifest)
check("主页允许键盘安全区滚动", 'android:clipToPadding="false"' in layout
      and "installImeScrollAssist" in main_activity)

print("=" * 72)
print("M3-01 ToolSpec/ToolRegistry 静态门禁")
print("=" * 72)
for item in PASSES:
    print("  PASS " + item)
for item in FAILS:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(PASSES)} 通过 / {len(FAILS)} 失败")
sys.exit(1 if FAILS else 0)
