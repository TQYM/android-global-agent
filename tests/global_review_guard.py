#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""全局代码复核回归门禁：节点资源所有权与账号提交顺序。"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
A11Y = (ROOT / "agent-lite/src/com/dsh/agentlite/AgentA11yService.java").read_text(encoding="utf-8")
PREFS = (ROOT / "agent-lite/src/com/dsh/agentlite/Prefs.java").read_text(encoding="utf-8")

fails = []
passes = []


def check(name: str, condition: bool) -> None:
    (passes if condition else fails).append(name)


active = re.search(r"public String activePackage\(\)\s*\{(.*?)\n    \}", A11Y, re.S)
check("activePackage 在 finally 中回收 root", bool(active and "root.recycle()" in active.group(1)))

service_lifecycle = A11Y[A11Y.find("public void onDestroy()"):A11Y.find("// ---- 感知 ----")]
check("服务解绑与销毁均释放 nodeRefs", service_lifecycle.count("releaseRefs();") >= 2)

set_text = re.search(r"public String setText\(String text, boolean append\)\s*\{(.*?)\n    \}", A11Y, re.S)
check("主屏输入释放 root 与 target", bool(set_text and "target.recycle()" in set_text.group(1)
                                      and "root.recycle()" in set_text.group(1)))

virtual_text = re.search(r"public String setTextOnDisplay\(int displayId, String text, boolean append\)\s*\{(.*?)\n    \}", A11Y, re.S)
check("虚拟屏输入使用 finally 释放节点", bool(virtual_text and "finally" in virtual_text.group(1)
                                      and "target.recycle()" in virtual_text.group(1)
                                      and "root.recycle()" in virtual_text.group(1)))

calibrate = re.search(r"public String calibrate\(\)\s*\{(.*?)\n    \}\n\n    /\*\* 统计节点", A11Y, re.S)
check("校准路径在 finally 中回收 root", bool(calibrate and "finally" in calibrate.group(1)
                                      and "root.recycle()" in calibrate.group(1)))

token_save = re.search(r"public boolean save\(String token\)\s*\{(.*?)\n    \}\n\n    /\*\* 退出登录", (ROOT / "agent-lite/src/com/dsh/agentlite/TokenStore.java").read_text(encoding="utf-8"), re.S)
check("令牌密文保存使用同步提交并检查结果", bool(token_save and ".commit()" in token_save.group(1)
                                               and "if (!committed)" in token_save.group(1)))

save = re.search(r"public boolean saveAccount\(.*?\n    \}\n\n    /\*\* 只刷新额度", PREFS, re.S)
if save:
    body = save.group(0)
    check("空令牌拒绝保存", "token == null || token.isEmpty()" in body)
    save_pos = body.find("tokens.save(token)")
    commit_pos = body.find("e.commit()")
    check("账号元数据提交前先保存加密令牌", save_pos >= 0 and commit_pos >= 0 and save_pos < commit_pos)
    check("元数据提交失败会回滚令牌", "已回滚令牌" in body and "tokens.clear()" in body)
else:
    fails.append("未找到 saveAccount 实现")

print("=" * 72)
print("全局代码复核回归门禁")
print("=" * 72)
for item in passes:
    print("  PASS " + item)
for item in fails:
    print("  FAIL " + item)
print("-" * 72)
print(f"结果：{len(passes)} 通过 / {len(fails)} 失败")
sys.exit(1 if fails else 0)
