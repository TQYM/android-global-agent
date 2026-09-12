#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# gen-cert.sh — 生成服务端自签证书（SAN 必须含服务地址，否则客户端主机会校验失败）
#
#   bash deploy/gen-cert.sh 127.0.0.1                 # 本地/测试：写到 .e2e/tls/
#   bash deploy/gen-cert.sh 8.133.225.105 --write-repo # 追加写入 server/tls/（改生产信任锚！）
#   bash deploy/gen-cert.sh 8.133.225.105 --remote     # 直接在 aliyun-ecs 上生成到 /srv/...
#
# 为什么要有这个脚本：证书是**两端唯一的信任锚**——服务端用它监听，客户端把同一张
# 证书编进 APK 做 pin。手工 openssl 很容易漏掉 SAN，症状是「App 连不上但 curl -k 正常」。
# 本脚本把 SAN 与后续动作一起固化下来。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

HOST="${1:-}"
MODE="${2:-local}"
[ -n "$HOST" ] || { echo "用法：bash deploy/gen-cert.sh <IP或域名> [--write-repo|--remote]" >&2; exit 2; }

# SAN：是 IP 就写 IP:，否则写 DNS:
if printf '%s' "$HOST" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'; then
  SAN="IP:$HOST,IP:127.0.0.1,DNS:localhost"
else
  SAN="DNS:$HOST,DNS:localhost,IP:127.0.0.1"
fi

gen() {  # gen <outdir>
  local out="$1"
  mkdir -p "$out"
  if [ -f "$out/server.crt" ] && [ "$2" != "--force" ]; then
    echo "已存在：$out/server.crt（要重签加 --force，注意会作废所有已装 APK 的 pin）"
    return 0
  fi
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout "$out/server.key" -out "$out/server.crt" \
    -subj "/CN=$HOST/O=AgentLite" \
    -addext "subjectAltName=$SAN"
  chmod 600 "$out/server.key"
  chmod 644 "$out/server.crt"
  echo "✓ 生成 $out/server.crt"
  echo "    SAN: $SAN"
  echo "    sha256: $(shasum -a 256 "$out/server.crt" | awk '{print $1}')"
}

case "$MODE" in
  --remote)
    echo "在 $SERVER_SSH_HOST 上生成证书 → $SERVER_SRV_DIR/tls/"
    ssh "$SERVER_SSH_HOST" "mkdir -p '$SERVER_SRV_DIR/tls' && cd '$SERVER_SRV_DIR/tls' && \
      openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
        -keyout server.key -out server.crt -subj '/CN=$HOST/O=AgentLite' \
        -addext 'subjectAltName=$SAN' && chmod 600 server.key && chmod 644 server.crt && \
      echo '✓ 服务器证书就绪' && openssl x509 -in server.crt -noout -text | grep -A1 'Subject Alternative Name'"
    echo
    echo "下一步：把服务器证书同步回客户端 pin，再重新打包 APK"
    echo "    scp $SERVER_SSH_HOST:$SERVER_SRV_DIR/tls/server.crt server/tls/server.crt"
    echo "    bash scripts/sync-cert.sh && bash scripts/build-all.sh"
    ;;
  --write-repo)
    echo "!! 这会替换 server/tls/server.crt —— 即整条链路的信任锚。"
    gen "$UNI/server/tls" --force
    bash scripts/sync-cert.sh
    echo
    echo "必须重新构建并重装 APK，否则已装的 App 会连不上："
    echo "    bash scripts/build-all.sh"
    ;;
  *)
    gen "$UNI/.e2e/tls"
    echo
    echo "（本地/测试用证书。生产证书用 --remote 或 --write-repo）"
    ;;
esac
