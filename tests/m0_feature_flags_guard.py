#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""M0-01 功能开关契约门禁。"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
FLAGS = ROOT / "agent-lite/src/com/dsh/agentlite/FeatureFlags.java"
PREFS = ROOT / "agent-lite/src/com/dsh/agentlite/Prefs.java"

fails = []
passes = []


def check(name: str, condition: bool) -> None:
    (passes if condition else fails).append(name)


src = FLAGS.read_text(encoding="utf-8") if FLAGS.exists() else ""
prefs = PREFS.read_text(encoding="utf-8") if PREFS.exists() else ""

keys = [
    "feature_policy_engine",
    "feature_perceptor_v2",
    "feature_tool_router",
    "feature_replay_guard",
    "feature_trace_recorder",
    "feature_skill_system",
    "feature_provider_registry",
    "feature_virtual_display",
]
check("FeatureFlags 文件存在", bool(src))
check("功能开关契约版本为 1", "SCHEMA_VERSION = 1" in src)
methods = {
    "feature_policy_engine": "policyEngine",
    "feature_perceptor_v2": "perceptorV2",
    "feature_tool_router": "toolRouter",
    "feature_replay_guard": "replayGuard",
    "feature_trace_recorder": "traceRecorder",
    "feature_skill_system": "skillSystem",
    "feature_provider_registry": "providerRegistry",
    "feature_virtual_display": "virtualDisplay",
}
for key, method in methods.items():
    check(f"声明开关键 {key}", f'"{key}"' in src)
    method_body = re.search(rf"boolean {method}\(\)\s*\{{(.*?)\}}", src, re.S)
    check(f"{key} 默认关闭", bool(method_body and re.search(r"enabled\([^,]+, false\)", method_body.group(1))))
    check(f"{key} 有读取方法", f"boolean {method}()" in src)

policy_shadow = re.search(r"boolean policyShadow\(\)\s*\{(.*?)\}", src, re.S)
trace_shadow = re.search(r"boolean traceShadow\(\)\s*\{(.*?)\}", src, re.S)
check("策略影子模式默认开启", bool(policy_shadow and re.search(r"enabled\([^,]+, true\)", policy_shadow.group(1))))
check("轨迹影子模式默认开启", bool(trace_shadow and re.search(r"enabled\([^,]+, true\)", trace_shadow.group(1))))
check("Prefs 提供统一 featureFlags 入口", "FeatureFlags featureFlags()" in prefs)
# 2026-10-02 P1-3：M3 ToolRegistry 已按「受开关控制的执行前网关」接入 AgentEngine。
# 契约从「不出现 featureFlags() 调用」演进为「所有 featureFlags() 读取都必须是
# toolRouter() 开关判定」——开关默认关闭（上文逐键断言），默认运行路径不变。
_engine = (ROOT / "agent-lite/src/com/dsh/agentlite/AgentEngine.java").read_text(encoding="utf-8")
_total = _engine.count("featureFlags().")
_router = _engine.count("featureFlags().toolRouter()")
check("AgentEngine 对 FeatureFlags 的读取仅限 toolRouter 开关判定（默认关）",
      _total == _router and _router >= 1)
check("M3 网关调用位于 toolRouter 开关判定之后（不绕过开关）",
      _engine.find("toolRouter()") >= 0
      and _engine.find("toolRouter()") < _engine.find("TOOL_REGISTRY.validate("))

print("=" * 72)
print("M0-01 功能开关契约门禁")
print("=" * 72)
for item in passes:
    print("  PASS " + item)
for item in fails:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(passes)} 通过 / {len(fails)} 失败")
sys.exit(1 if fails else 0)
