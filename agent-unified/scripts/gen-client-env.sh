#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# gen-client-env.sh — 把整合层的配置下发到**客户端原生的注入点** client/dev.local
#
#   bash scripts/gen-client-env.sh
#
# 为什么是 dev.local：client/build-mac.sh（原项目脚本，一行未改）在编译前会
# `[ -f dev.local ] && . ./dev.local`，随后把 API_KEY / BASE_URL / MODEL / SYNC_URL
# 生成为 DevConfig.java。这就是原项目**自带**的注入点，整合层只负责喂值，
# 不碰它的构建逻辑，也不碰任何 Java 源码。
#
# 取值优先级（高 → 低）：
#   SYNC_URL   : 环境变量 > local.env > unified.conf
#   API_KEY    : 环境变量 > local.env > ../agent-lite/dev.local（只读借用）> 空
#   BASE_URL   : 环境变量 > local.env > 原脚本默认值
#   MODEL      : 环境变量 > local.env > 原脚本默认值
#
# 密钥只落到 dev.local（已被 .gitignore 忽略、权限 0600），不会进入 dist/ 发布件。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT="$UNI/client"
ROOT="$(cd "$UNI/.." && pwd)"

# ---------- 1. 读 unified.conf（单一真相源）----------
set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a
: "${SYNC_URL:=https://${SERVER_HOST}:${SERVER_PORT}}"

# ---------- 2. 可选覆盖：local.env（gitignore）----------
LOCAL_ENV="$UNI/local.env"
if [ -f "$LOCAL_ENV" ]; then
  # 先记下命令行的环境变量，避免被 local.env 覆盖
  _env_api="${API_KEY:-}"; _env_base="${BASE_URL:-}"; _env_model="${MODEL:-}"; _env_sync="${SYNC_URL_EXPLICIT:-}"
  set -a
  # shellcheck disable=SC1090
  . "$LOCAL_ENV"
  set +a
  [ -n "$_env_api" ] && API_KEY="$_env_api"
  [ -n "$_env_base" ] && BASE_URL="$_env_base"
  [ -n "$_env_model" ] && MODEL="$_env_model"
  [ -n "$_env_sync" ] && SYNC_URL="$_env_sync"
  echo "读入 local.env（gitignore，不入 git）"
fi

# ---------- 3. 兜底：只读借用原项目的 dev.local（绝不复制进统一目录以外）----------
BORROWED=""
if [ -z "${API_KEY:-}" ] && [ -f "$ROOT/agent-lite/dev.local" ]; then
  _b_api=$(sed -n 's/^API_KEY=//p' "$ROOT/agent-lite/dev.local" | head -1)
  _b_base=$(sed -n 's/^BASE_URL=//p' "$ROOT/agent-lite/dev.local" | head -1)
  _b_model=$(sed -n 's/^MODEL=//p' "$ROOT/agent-lite/dev.local" | head -1)
  [ -n "$_b_api" ] && API_KEY="$_b_api"
  [ -z "${BASE_URL:-}" ] && [ -n "$_b_base" ] && BASE_URL="$_b_base"
  [ -z "${MODEL:-}" ] && [ -n "$_b_model" ] && MODEL="$_b_model"
  BORROWED="（借用自 agent-lite/dev.local，只读）"
fi

# ---------- 4. 默认值（与原 build-mac.sh 内联默认保持一致）----------
: "${BASE_URL:=https://dashscope.aliyuncs.com/compatible-mode/v1}"
: "${MODEL:=qwen3.5-omni-plus}"
: "${API_KEY:=}"

# ---------- 5. 落盘 ----------
umask 077
cat > "$CLIENT/dev.local" <<EOF
# 由 agent-unified/scripts/gen-client-env.sh 生成 —— 请勿手工编辑，也勿入 git。
# 真相源：agent-unified/unified.conf（地址）+ local.env（密钥）
API_KEY=${API_KEY}
BASE_URL=${BASE_URL}
MODEL=${MODEL}
SYNC_URL=${SYNC_URL}
EOF
chmod 600 "$CLIENT/dev.local"

mask() { [ -z "$1" ] && echo "(空：APK 内不内置 Key，需在 App 内手填)" || echo "${1:0:6}…${1: -4}（已内置 ${#1} 字符）"; }
echo "写入 $CLIENT/dev.local $BORROWED"
echo "  SYNC_URL = $SYNC_URL   ← unified.conf / local.env"
echo "  BASE_URL = $BASE_URL"
echo "  MODEL    = $MODEL"
echo "  API_KEY  = $(mask "$API_KEY")"
if [ -z "$API_KEY" ]; then
  echo "  提示：未提供 API_KEY 时构建出的 APK 不含密钥（发布形态），装机后在 App 内填写即可。"
fi
