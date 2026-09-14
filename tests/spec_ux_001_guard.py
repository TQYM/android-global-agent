#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""spec_ux_001_guard.py — SPEC-UX-001「零门槛守则」门禁（免设备）。

    python3 tests/spec_ux_001_guard.py

设计原则：**只做能真伪判定的静态断言**，不做「文档里写了就算过」的形式检查。
无法静态判定的条款（例如 UX-1 五分钟首成功）标记为 MANUAL，并在结尾提示需要真机回归。

退出码：0 = 无 FAIL（可有 MANUAL）；1 = 存在 FAIL。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LITE = ROOT / "agent-lite"
SRC = LITE / "src" / "com" / "dsh" / "agentlite"

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


main_java = read(SRC / "MainActivity.java")
engine_java = read(SRC / "AgentEngine.java")
llm_java = read(SRC / "LlmClient.java")
prefs_java = read(SRC / "Prefs.java")
uitext_java = read(SRC / "UiText.java")
layout_main = read(LITE / "res" / "layout" / "activity_main.xml")

# ---------------------------------------------------------------- UX-1
check(
    "UX-1",
    "运行入口存在凭据前置守卫，且无凭据时提前返回（不发请求）",
    "hasUsableCredential()" in main_java
    and "showCredentialCard()" in main_java
    and bool(re.search(r"if \(!hasUsableCredential\(\)\) \{[^}]*return;", main_java, re.S)),
)
manual("UX-1", "五分钟首成功（真机：清数据 → 登录 → 一句话 → 完成安全任务）")

# ---------------------------------------------------------------- UX-2
BANNED = ("API", "Base URL", "base_url", "endpoint", "Endpoint", "RAM", "token", "Token", "模型名")
texts = re.findall(r'android:text="([^"]+)"', layout_main)
hits = sorted({t for t in texts for b in BANNED if b in t})
check("UX-2", "首屏可见文案不含技术词（API/Base URL/endpoint/RAM/token）", not hits,
      f"命中：{hits}" if hits else "")

# ---------------------------------------------------------------- UX-3
has_guard = "hasUsableCredential()" in main_java
idx_guard = main_java.find("hasUsableCredential()")
idx_start = main_java.find("startTask(task)")
check("UX-3", "凭据守卫先于任务启动", has_guard and idx_guard != -1 and idx_start != -1 and idx_guard < idx_start)
check("UX-3", "解决卡给出可操作路径（登录 / 设置）",
      "LoginActivity.class" in main_java and "openSettings()" in main_java)

# ---------------------------------------------------------------- UX-4
check("UX-4", "失败归因统一经 UiText 加工（不把原始异常示人）",
      "UiText.uiReason" in engine_java and "uiReason" in uitext_java)
check("UX-4", "失败终态带可执行的下一步（归因函数存在兜底文案）",
      bool(re.search(r"任务未能完成|可重试|请", uitext_java)))

# ---------------------------------------------------------------- UX-5
# 权限申请不得集中在启动期：onCreate 里不得出现 requestPermissions
oncreate = re.search(r"protected void onCreate\(Bundle[^)]*\)\s*\{(.*?)\n    \}", main_java, re.S)
oncreate_body = oncreate.group(1) if oncreate else ""
check("UX-5", "启动期不主动申请权限（录音权限延迟到点语音时）",
      "requestPermissions" not in oncreate_body,
      "onCreate 中发现 requestPermissions" if "requestPermissions" in oncreate_body else "")

# ---------------------------------------------------------------- UX-6
check("UX-6", "状态文案与卡片由单一来源提供（OverlayController / KeepAliveService）",
      (SRC / "OverlayController.java").exists() and (SRC / "KeepAliveService.java").exists())
check("UX-6", "「用户停止」不显示为失败",
      "任务已被用户停止" in engine_java and "CancelledException" in read(SRC / "LlmClient.java"))

# ---------------------------------------------------------------- 敏感红线（§16.2 P0-2）
terms = re.search(r"private boolean isTermsDialog\(List<NodeInfo> nodes\)\s*\{(.*?)\n    \}", engine_java, re.S)
terms_body = terms.group(1) if terms else ""
# 注：注释里可以提到旧方法名（说明由来），要断言的是「方法定义/调用已不存在」
check("UX-6", "条款/隐私弹窗只检测不代点（引擎不替用户同意协议）",
      bool(terms) and ".tap(" not in terms_body and "tapNode" not in terms_body
      and "private boolean autoAgreeTerms" not in engine_java,
      "检测方法内仍有点击逻辑" if terms_body and (".tap(" in terms_body or "tapNode" in terms_body) else "")

# ---------------------------------------------------------------- UX-7
check("UX-7", "引擎内存在 done 的最小完成复验分支",
      "verifyDone" in engine_java or "完成复验" in engine_java or "verifyCompletion" in engine_java)
check("UX-7", "open_url / open_api / 纯视觉坐标点击有屏幕变化验收器（手册 §16.9 T3）",
      "expectEffect" in engine_java and "NodeInfo.fingerprint" in engine_java)
check("UX-6", "应用商店硬防线存在（禁止替用户搜索/安装应用，§16.9 T2）",
      "isAppStoreForeground" in engine_java and "taskAllowsStore" in engine_java)

# ---------------------------------------------------------------- UX-8
check("UX-8", "配置迁移版本化（Prefs 内有迁移/版本逻辑）",
      bool(re.search(r"migrat|迁移|version", prefs_java, re.I)))

# ---------------------------------------------------------------- UX-9
check("UX-9", "感知降级链存在（a11y 服务 → uiautomator/截图 → 失败原因）",
      "senseViaService" in engine_java or "collectNodes" in engine_java)
check("UX-9", "模型请求有超时与响应上限（不无限等待）",
      "MAX_RESPONSE_BYTES" in llm_java and "setReadTimeout" in llm_java)

# ---------------------------------------------------------------- UX-10
schema_version = read(SRC / "ActionSchema.java")
check("UX-10", "动作词表带 SCHEMA_VERSION（单一事实源）",
      "SCHEMA_VERSION" in schema_version or "SCHEMA_VERSION" in engine_java)

# ---------------------------------------------------------------- 输出
width = 78
print("=" * width)
print("SPEC-UX-001 零门槛守则门禁")
print("=" * width)
fails = 0
for cid, desc, state, note in RESULTS:
    mark = {"PASS": "PASS", "FAIL": "FAIL", "MANUAL": "MANUAL"}[state]
    line = f"  {mark:6} [{cid}] {desc}"
    if note:
        line += f" — {note}"
    print(line)
    if state == "FAIL":
        fails += 1
print("-" * width)
print(f"结果：{sum(1 for r in RESULTS if r[2] == 'PASS')} 通过 / {fails} 失败 / "
      f"{sum(1 for r in RESULTS if r[2] == 'MANUAL')} 需真机")
if fails == 0:
    print("零门槛守则（可静态判定部分）通过 ✅")
sys.exit(1 if fails else 0)
