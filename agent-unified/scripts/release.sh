#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# release.sh — 把 dist/ 里的产物打成一个可交付的整合版发布件
#
#   bash scripts/release.sh
#
# 发布件内容（一个压缩包 = 完整一套系统，不需要再回仓库找东西）：
#   agentlite-unified-<ver>.apk              Android 客户端（含内嵌证书 pin）
#   agentlite-unified-<ver>-server-linux-amd64   同步/额度服务（静态单二进制）
#   server/install.sh + agentlite-server.service 服务端安装与 systemd 单元
#   server/server.crt                        服务端证书（客户端信任锚的来源）
#   RELEASE.json                             版本映射、来源快照、产物校验和
#   SHA256SUMS                               全部产物校验和
#   README.md                                整合版说明（含部署与排障）
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

DIST="$UNI/dist"
NAME="agentlite-unified-${UNIFIED_VERSION}"
STAGE="$DIST/$NAME"
TARBALL="$DIST/$NAME.tar.gz"

[ -f "$DIST/RELEASE.json" ] || { echo "✗ 还没有构建产物，请先：bash scripts/build-all.sh" >&2; exit 1; }

echo "== 1/3 生成发布清单（若缺）"
[ -f "$DIST/SHA256SUMS" ] || bash scripts/build-all.sh >/dev/null

echo "== 2/3 组装发布目录 $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE/server"

copied=0
for f in "$DIST"/$NAME*.apk "$DIST"/$NAME*server-linux-amd64; do
  [ -f "$f" ] || continue
  cp "$f" "$STAGE/"; copied=$((copied+1))
done
[ "$copied" -gt 0 ] || { echo "✗ dist/ 里没有可发布的产物" >&2; exit 1; }

cp "$DIST/RELEASE.json" "$DIST/SHA256SUMS" "$STAGE/"
cp "$UNI/README.md" "$STAGE/README.md"
cp "$UNI/server/tls/server.crt" "$STAGE/server/server.crt"
cp "$UNI/server/deploy/install.sh" "$UNI/server/deploy/agentlite-server.service" "$STAGE/server/"

echo "== 3/3 打包"
( cd "$DIST" && tar czf "$(basename "$TARBALL")" "$NAME" )
shasum -a 256 "$TARBALL" | awk '{print $1}' > "$TARBALL.sha256"

echo
echo "DONE: $TARBALL"
echo "      $(stat -f%z "$TARBALL") bytes   sha256 $(cat "$TARBALL.sha256")"
echo
echo "包内："
tar tzf "$TARBALL" | sed 's/^/    /'
