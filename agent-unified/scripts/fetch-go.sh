#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# fetch-go.sh — 让整合层自备一份 Go 工具链（不污染系统 PATH、不装 brew）
#
#   bash scripts/fetch-go.sh            # 装到 $HOME/.agent-unified-go
#   GO_VERSION=1.24.6 bash scripts/fetch-go.sh
#
# 为什么需要：服务端只依赖标准库，但本机常常没装 Go。构建服务端需要 go ≥ 1.24
# （用了 crypto/pbkdf2）。装到用户目录即可，卸载就是删目录。
set -e

UNI=$(cd "$(dirname "$0")/.." && pwd)
GO_VERSION="${GO_VERSION:-1.24.6}"
GO_DIR="${GO_DIR:-$HOME/.agent-unified-go}"

OS=$(uname -s | tr 'A-Z' 'a-z')
case "$(uname -m)" in
  arm64|aarch64) ARCH=arm64 ;;
  x86_64|amd64)  ARCH=amd64 ;;
  *) echo "不支持的架构：$(uname -m)" >&2; exit 1 ;;
esac
FILE="go${GO_VERSION}.${OS}-${ARCH}.tar.gz"
URL="https://mirrors.aliyun.com/golang/${FILE}"

echo "目标：$GO_DIR/go  版本 go$GO_VERSION  ($OS/$ARCH)"
echo "来源：$URL"

if [ -x "$GO_DIR/go/bin/go" ]; then
  echo "已存在：$("$GO_DIR/go/bin/go" version)"
  echo "（要换版本：rm -rf $GO_DIR 后重跑）"
  exit 0
fi

mkdir -p "$GO_DIR"
TMP="$GO_DIR/$FILE"
echo "下载中…"
curl -fSL --progress-bar -o "$TMP" "$URL"
tar xzf "$TMP" -C "$GO_DIR"
rm -f "$TMP"

echo "✓ $("$GO_DIR/go/bin/go" version)"
echo
echo "go.mod 要求 go ≥ 1.24，本版本满足。构建服务端："
echo "    bash $UNI/scripts/build-server.sh"
echo "（build-server.sh 会自动发现该工具链，无需改 PATH）"
