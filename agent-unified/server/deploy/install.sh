#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 在服务器上构建并安装 agentlite-server。
# 用法： sudo bash deploy/install.sh [源码目录]
set -euo pipefail

SRC_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
BIN=/usr/local/bin/agentlite-server
DATA_DIR=/var/lib/agentlite-server
BASE_DIR=/srv/agentlite-server
UNIT=/etc/systemd/system/agentlite-server.service

echo "== 源码目录: $SRC_DIR"
cd "$SRC_DIR"

echo "== 构建（CGO_ENABLED=0，静态单二进制）"
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o "$BIN" .
chmod 755 "$BIN"
"$BIN" -version

echo "== 目录与权限"
mkdir -p "$DATA_DIR" "$BASE_DIR/tls"
chmod 700 "$DATA_DIR"

if [ ! -f "$BASE_DIR/tls/server.crt" ]; then
    echo "!! 缺少 TLS 证书 $BASE_DIR/tls/server.crt —— 先执行证书生成步骤" >&2
    exit 1
fi

if [ ! -f "$DATA_DIR/config.json" ]; then
    echo '{"version":1,"values":{}}' > "$DATA_DIR/config.json"
    chmod 600 "$DATA_DIR/config.json"
    echo "== 初始化 config.json"
fi

echo "== 安装 systemd 单元"
install -m 644 "$SRC_DIR/deploy/agentlite-server.service" "$UNIT"
systemctl daemon-reload
systemctl enable agentlite-server >/dev/null
systemctl restart agentlite-server
sleep 1

echo "== 状态"
systemctl --no-pager --lines=5 status agentlite-server || true
echo
echo "== 本机健康检查"
if command -v curl >/dev/null 2>&1; then
    curl -sk https://127.0.0.1:18443/health || true
else
    python3 - <<'PY' || true
import ssl, urllib.request
ctx = ssl._create_unverified_context()
print(urllib.request.urlopen("https://127.0.0.1:18443/health", context=ctx, timeout=5).read().decode())
PY
fi
echo
echo "完成。"
