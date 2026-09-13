#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# iOS 端构建与验证（对应 Android 侧的 agent-lite/build-mac.sh）。
#
# 设计取舍：**默认只跑离线可验证的部分**。
# 端到端测试需要一个可达的服务端，不适合当作默认动作——
# 一个"跑一次要几分钟且依赖外部进程"的默认脚本，最后一定会被跳过。
# 因此拆成两个开关：
#   bash build-mac.sh            → 构建 + 离线单测（快，无依赖）
#   bash build-mac.sh --e2e      → 追加端到端（需先起本地服务端，见手册 §8.1）
#
# 用法:
#   bash ios/build-mac.sh [--e2e] [--server <base_url>] [--cert <server.crt>]

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/ios"

DO_E2E=0
SERVER_BASE="${AGENTLITE_E2E_BASE:-https://127.0.0.1:18443}"
SERVER_CERT="${AGENTLITE_E2E_CERT:-$ROOT/_local/ios-e2e/tls/server.crt}"

while [ $# -gt 0 ]; do
  case "$1" in
    --e2e)    DO_E2E=1; shift ;;
    --server) SERVER_BASE="$2"; shift 2 ;;
    --cert)   SERVER_CERT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

FAILED=0
say()  { printf '%s\n' "$*"; }
ok()   { printf '✓ %s\n' "$*"; }
bad()  { printf '✗ %s\n' "$*"; FAILED=1; }

say "═══ iOS 端构建与验证 ═══"
say "  目录: $ROOT/ios"
say ""

# ---------- 1. 构建 ----------
say "[1/4] swift build"
if swift build 2>&1 | tail -3; then
  ok "构建通过"
else
  bad "构建失败"
  exit 1
fi
say ""

# ---------- 2. 平台可达性（iOS / macOS 双平台编译） ----------
say "[2/4] 交叉编译检查（macOS SDK + iOS SDK）"
# 为什么单独查 iOS SDK：核心库在 macOS 上能跑不代表能在 iOS 上编译——
# iOS 专属差异（可用 API、链接的 framework）只有真正对着 iPhoneOS SDK 编才暴露。
IOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || true)"
if [ -n "$IOS_SDK" ]; then
  # 只编核心库（--target AgentLiteCore）：CLI 是 macOS 用的命令行外壳，
  # 链进 iOS 目标没有意义，反而会触发 sysroot 相关的链接告警、掩盖真正的编译错误。
  IOS_BUILD_OUT="$(swift build --target AgentLiteCore --triple arm64-apple-ios16.0 --sdk "$IOS_SDK" --scratch-path .build/ios-check 2>&1)"
  if printf '%s' "$IOS_BUILD_OUT" | grep -qE "Build of target: 'AgentLiteCore' complete"; then
    ok "iOS 目标（arm64-apple-ios16.0）编译通过"
  else
    bad "iOS 目标编译失败 —— 核心库引入了仅 macOS 可用的 API？"
    printf '%s\n' "$IOS_BUILD_OUT" | grep -E "error" | head -10
  fi
  rm -rf .build/ios-check
else
  say "  · 跳过：未找到 iPhoneOS SDK（未装 Xcode）"
fi
say ""

# ---------- 3. 离线单测（密码学交叉验证 + 协议 + 红线） ----------
say "[3/4] 离线测试（CryptoTests + ProtocolTests）"
OFFLINE_OUT="$(swift test --filter 'CryptoTests|ProtocolTests' 2>&1)"
if printf '%s' "$OFFLINE_OUT" | grep -qE "Executed [0-9]+ tests, with 0 failures"; then
  # 取最后一个 "Executed N tests" —— SwiftPM 会为每个 suite 各打一行，
  # 第一行只是第一个 suite，用它会把总数报小。
  N=$(printf '%s' "$OFFLINE_OUT" | grep -oE "Executed [0-9]+ tests" | tail -1 | grep -oE "[0-9]+")
  ok "离线测试全绿（$N 项）"
else
  bad "离线测试有失败"
  printf '%s\n' "$OFFLINE_OUT" | grep -E "error:" | head -10
fi
say ""

# ---------- 4. 端到端（可选） ----------
say "[4/4] 端到端测试"
if [ "$DO_E2E" -eq 0 ]; then
  say "  · 跳过（加 --e2e 启用；需先起本地服务端，见手册 §8.1）"
else
  if [ ! -f "$SERVER_CERT" ]; then
    bad "找不到服务端证书: $SERVER_CERT（用 --cert 指定）"
  elif ! curl -sS --max-time 5 --cacert "$SERVER_CERT" "$SERVER_BASE/health" >/dev/null 2>&1; then
    bad "服务端不可达: $SERVER_BASE（先按手册 §8.1 起本地服务端）"
  else
    ok "服务端可达: $SERVER_BASE"
    E2E_OUT="$(AGENTLITE_E2E_BASE="$SERVER_BASE" AGENTLITE_E2E_CERT="$SERVER_CERT" \
               swift test --filter EndToEndTests 2>&1)"
    if printf '%s' "$E2E_OUT" | grep -qE "Executed [0-9]+ tests, with 0 failures"; then
      N=$(printf '%s' "$E2E_OUT" | grep -oE "Executed [0-9]+ tests" | tail -1 | grep -oE "[0-9]+")
      ok "端到端全绿（$N 项，真连服务端）"
    else
      bad "端到端有失败"
      printf '%s\n' "$E2E_OUT" | grep -E "error:" | head -10
    fi
  fi
fi
say ""

if [ "$FAILED" -eq 0 ]; then
  ok "全部通过"
else
  bad "存在失败项"
fi
exit "$FAILED"
