#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# sync-cert.sh — 证书 pin 下发：server/tls/server.crt → client/assets/agentlite_sync_ca.pem
#
#   bash scripts/sync-cert.sh            下发（必要时）
#   bash scripts/sync-cert.sh --check    只校验是否已一致
#
# 为什么需要它：客户端连的是**自签证书**，系统 CA 库不认，因此必须把服务端证书
# 作为信任锚内嵌进 APK（构建期由 client/build-mac.sh 生成 PinnedCa 常量，运行时由
# TlsPin 使用）。这一步在原始流程里是**手工**的（server README §5 那句
# 「证书公钥需复制到客户端」），整合层把它变成单一入口：
#
#     服务端证书 = 唯一的真相源，客户端那份永远是它的副本。
#
# 注意：换证书 = 换信任锚，必须**重新构建并重装 APK**，否则 App 会连不上同步服务
# （TlsPin 只信任内置这一张，主机名校验也保持默认开启）。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$UNI/server/tls/server.crt"
DST="$UNI/client/assets/agentlite_sync_ca.pem"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

[ -f "$SRC" ] || { echo "✗ 找不到服务端证书：$SRC" >&2; exit 1; }
[ -f "$DST" ] || { echo "✗ 找不到客户端 pin 文件：$DST" >&2; exit 1; }

h() { shasum -a 256 "$1" | awk '{print $1}'; }
SRC_H=$(h "$SRC"); DST_H=$(h "$DST")

echo "服务端证书 : $SRC"
echo "             sha256 $SRC_H"
echo "客户端 pin : $DST"
echo "             sha256 $DST_H"

if [ "$SRC_H" = "$DST_H" ]; then
  echo "✓ 已一致（客户端信任锚 = 服务端证书）"
  exit 0
fi

if [ "$CHECK" = "1" ]; then
  echo "✗ 不一致：客户端仍 pin 着旧证书，APK 连不上同步服务" >&2
  echo "  修复：bash scripts/sync-cert.sh && bash scripts/build-all.sh" >&2
  exit 1
fi

cp "$SRC" "$DST"
echo "→ 已下发新证书到客户端"
echo "  服务端证书 SAN："
openssl x509 -in "$SRC" -noout -ext subjectAltName 2>/dev/null | sed 's/^/    /' || true
echo
echo "!! 信任锚已变化：必须重新构建 APK 并覆盖安装，旧 APK 会连不上。"
