#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# ui_text_guard.sh — 面向用户文案的合规门禁（守则 §12.4 第 5 项）
#
#   bash tests/ui_text_guard.sh
#
# 守护《软件交互逻辑规范守则》里两条**面向用户**的强制条款。它们此前都被违反过，
# 而违反的表现只出现在真机屏幕上，靠人工回归很难盯住：
#
#   T-4（§9）归因由模型给出、引擎统一加工，**必须不**向用户暴露堆栈或内部字段名；
#   O-3（§11）进度文案与内部标签分离，**内部标签必须不**直接暴露给用户。
#
# 两层断言：
#   A. 静态（结构前提）：唯一加工点存在、无「前缀 + 异常原文」拼装、映射表覆盖完整；
#   B. 行为（真实输入 → 真实输出）：在桌面 JVM 上原样编译运行 UiText（它刻意不依赖
#      android API），用真实异常原文与全部内部标签断言输出——见 tests/ui_text_test/。
#
# 为什么两层都要：静态 grep 只能证明「代码长这样」，证明不了「输出是对的」。
# 行为测试补上后半句——它真的抓到过静态检查漏掉的三处缺陷（JVM 英文残留、
# 技术串判定看错阶段、裸动作名 `app` 无文案）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/agent-lite/src/com/dsh/agentlite"
ENGINE="$SRC/AgentEngine.java"
UITEXT="$SRC/UiText.java"
TESTDIR="$ROOT/tests/ui_text_test"

PASS=0; FAIL=0
ok()  { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }

for f in "$ENGINE" "$UITEXT"; do
  [ -f "$f" ] || { echo "✗ 找不到 $f" >&2; exit 1; }
done

echo "============================================================"
echo "面向用户文案合规门禁（守则 T-4 / O-3）"
echo "============================================================"
echo "A. 静态：结构前提"
echo

# ── A1. 不得把异常原文**拼接**进用户文案 ───────────────────────────────────
# failTask 内部统一走 UiText.uiReason()，所以 `failTask(t.getMessage())` 这种单独透传是
# 安全的（会被洗净）；真正会漏的是「前缀 + 原文」这种手工拼装——原文会原样出现在
# 卡片/胶囊/通知里，正是 T-4 要挡的形态。
hits=$(grep -nE 'failTask\([^;]*\+[^;]*getMessage\(\)|failTask\([^;]*getMessage\(\)[^;]*\+' "$ENGINE" || true)
if [ -z "$hits" ]; then
  ok "T-4：没有把异常原文手工拼进终态文案的调用"
else
  bad "T-4：存在「前缀 + 异常原文」拼装（原文会直接展示给用户）"
  printf '%s\n' "$hits" | sed 's/^/        /'
fi

# ── A2. failTask 必须走唯一加工点 ──────────────────────────────────────────
if sed -n '/private void failTask(/,/^    }/p' "$ENGINE" | grep -q 'UiText.uiReason('; then
  ok "T-4：failTask（唯一出口）统一走 UiText.uiReason() 加工"
else
  bad "T-4：failTask 未调用 UiText.uiReason()，加工点缺失"
fi

# ── A3. 三个面向用户的出口不得出现 getMessage() ─────────────────────────────
hits=$(grep -nE '(showFail|setCapsuleFail|updateProgress)\([^;]*getMessage\(\)' "$ENGINE" || true)
if [ -z "$hits" ]; then
  ok "T-4：失败卡片 / 胶囊 / 通知三处均不含异常原文"
else
  bad "T-4：存在把异常原文送进用户可见出口的调用"
  printf '%s\n' "$hits" | sed 's/^/        /'
fi

# ── A4. UiText 必须保持「无 android 依赖」，否则行为测试跑不起来 ──────────────
if grep -qE '^[[:space:]]*import[[:space:]]+android\.' "$UITEXT"; then
  bad "UiText 引用了 android.* —— 桌面 JVM 行为测试将无法运行"
else
  ok "UiText 不依赖 android.*（与 TlsPin 同款取舍，可在桌面原样验证）"
fi

# ── A5. 枚举式：映射表必须覆盖 exec() 标签与 SCHEMA 动作名 ───────────────────
EXEC_BODY=$(awk '/private String exec\(/{f=1} f&&/^    (private|public|protected|static|final)/&&!/private String exec\(/{exit} f{print}' "$ENGINE")
LABELS=$(printf '%s\n' "$EXEC_BODY" | grep -oE 'return "[A-Za-z_][A-Za-z0-9_]*' | sed 's/return "//' | sort -u)
# SCHEMA 是 Java 转义字符串，源码里写作 {\"action\":\"open_api\"
SCHEMA_ACTIONS=$(grep -oE '\{\\"action\\":\\"[a-z_]+' "$ENGINE" | sed 's/.*\\"//' | sort -u)
MISSING=""
for l in $LABELS $SCHEMA_ACTIONS; do
  grep -qE "startsWith\(\"$l|equals\(\"$l" "$UITEXT" || MISSING="$MISSING $l"
done
if [ -z "$MISSING" ]; then
  ok "O-3：exec() 的 $(printf '%s\n' "$LABELS" | wc -l | tr -d ' ') 类标签与 SCHEMA 的 $(printf '%s\n' "$SCHEMA_ACTIONS" | wc -l | tr -d ' ') 个动作名全部有友好文案"
else
  bad "O-3：以下标签/动作名没有友好文案（新增后忘了补映射）:$MISSING"
fi

# ── A6. 兜底不得回原文 ────────────────────────────────────────────────────
if sed -n '/public static String friendlyLabel(/,/^    }/p' "$UITEXT" | grep -qE '^[[:space:]]*return raw;'; then
  bad "O-3：friendlyLabel 仍以 return raw 兜底（内部标签会直接显示给用户）"
else
  ok "O-3：friendlyLabel 兜底不返回内部标签原文"
fi

echo
echo "B. 行为：桌面 JVM 上真实输入 → 真实输出"
echo
if ! command -v javac >/dev/null 2>&1; then
  bad "未找到 javac —— 无法运行行为测试（本机需要 JDK）"
else
  CLASSES="$TESTDIR/classes"
  rm -rf "$CLASSES"; mkdir -p "$CLASSES"
  if javac -encoding UTF-8 -d "$CLASSES" "$UITEXT" "$TESTDIR/UiTextTest.java" 2>"$TESTDIR/javac.log"; then
    if java -cp "$CLASSES" UiTextTest | sed 's/^/  /'; then
      PASS=$((PASS+1))
    else
      FAIL=$((FAIL+1))
    fi
  else
    bad "UiText + UiTextTest 编译失败（详见 $TESTDIR/javac.log）"
    sed 's/^/        /' "$TESTDIR/javac.log" | head -20
  fi
fi

echo "------------------------------------------------------------"
printf '通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
echo "面向用户文案合规 ✅（守则 T-4 / O-3）"
