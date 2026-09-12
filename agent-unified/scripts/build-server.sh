#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# build-server.sh — 构建同步服务端（Go 静态单二进制）
#
#   bash scripts/build-server.sh            # 交叉编译 linux/amd64（产物收进 dist/）
#   bash scripts/build-server.sh --local    # 另建一份本机架构二进制（供 e2e-local 用）
#
# 构建参数与 server/deploy/install.sh 完全一致（CGO_ENABLED=0 + -trimpath + -ldflags "-s -w"），
# 所以本地交叉编译出来的二进制与服务器上现编的**等价**——差别只有构建位置。
#
# 关于 Go 工具链：本机没装 Go 也没关系。脚本按以下顺序找：
#   $GO_BIN → go（PATH）→ $HOME/.agent-unified-go/go/bin/go（整合层自备，见 README）
# 全都没有时给出可复制的安装指引并跳过服务端构建（客户端仍可独立构建）。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

SERVER="$UNI/server"
DIST="$UNI/dist"
mkdir -p "$DIST"

find_go() {
  if [ -n "${GO_BIN:-}" ] && [ -x "$GO_BIN" ]; then echo "$GO_BIN"; return; fi
  if command -v go >/dev/null 2>&1; then command -v go; return; fi
  if [ -x "$HOME/.agent-unified-go/go/bin/go" ]; then echo "$HOME/.agent-unified-go/go/bin/go"; return; fi
  echo ""
}

GO=$(find_go)
if [ -z "$GO" ]; then
  cat >&2 <<'EOF'
!! 找不到 Go 工具链，跳过服务端构建（客户端构建不受影响）。

   三种任选其一：
     a) 安装系统 Go：              brew install go
     b) 让整合层自备（不污染系统）：bash scripts/fetch-go.sh
     c) 直接在服务器上现编现装：    bash deploy/deploy-server.sh   # 服务器已 dnf install golang

   注：服务端只依赖标准库，go.mod 要求 go ≥ 1.24（用到 crypto/pbkdf2）。
EOF
  exit 3
fi

echo "Go: $("$GO" version)"
echo

echo "== 1/4 静态检查（注意 pipefail：管道会吃掉退出码，README §8.1）"
set -o pipefail
( cd "$SERVER" && GOFLAGS=-mod=mod "$GO" vet ./... )
echo "   ✓ go vet 通过"

echo
echo "== 2/4 交叉编译 linux/amd64（服务器目标）"
OUT="$DIST/agentlite-unified-${UNIFIED_VERSION}-server-linux-amd64"
( cd "$SERVER" && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    "$GO" build -trimpath -ldflags "-s -w" -o "$OUT" . )
echo "   → $OUT  $(stat -f%z "$OUT") bytes"

echo
echo "== 3/4 本机架构二进制（供 e2e-local 起本地实例验证；可跳过）"
if [ "${1:-}" = "--local" ] || [ "${BUILD_LOCAL:-1}" = "1" ]; then
  LOCAL_OUT="$DIST/agentlite-unified-${UNIFIED_VERSION}-server-$(uname -s | tr 'A-Z' 'a-z')-$(uname -m)"
  ( cd "$SERVER" && CGO_ENABLED=0 "$GO" build -trimpath -ldflags "-s -w" -o "$LOCAL_OUT" . )
  echo "   → $LOCAL_OUT  $(stat -f%z "$LOCAL_OUT") bytes"
  echo "   自检：$("$LOCAL_OUT" -version)"
fi

echo
echo "== 4/4 校验和"
shasum -a 256 "$OUT" | awk '{print $1}' > "$OUT.sha256"
echo "   $(cat "$OUT.sha256")  $(basename "$OUT")"
echo
echo "DONE: $OUT"
echo "部署：bash deploy/deploy-server.sh        （rsync → 远端编译安装 → 冒烟）"
