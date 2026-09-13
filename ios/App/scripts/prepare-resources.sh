#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 为模拟器/真机联调准备"服务端证书"资源。
#
# 为什么需要这一步：证书 pin 的前提是**证书在 App 包里**（拿不到证书时 App 明确
# 拒绝连接，而不是退回不校验 —— 见 ServerConfigProvider）。因此证书必须是构建输入。
#
# ★ 但证书**不入库**：
#   - 生产证书属于部署产物，随包分发的是它，不是仓库里的副本；
#   - 本地联调证书是一次性生成的，入库只会让"这份证书是哪来的"变得含糊。
#   所以：脚本生成 → 只落在 ios/App/Resources/（已 gitignore）→ 构建时打进包。
#
# 用法:
#   bash ios/App/scripts/prepare-resources.sh                 # 生成自签开发证书（默认）
#   bash ios/App/scripts/prepare-resources.sh --from <crt>    # 用现成证书（如线上 server.crt）

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
RES_DIR="$ROOT/ios/App/Resources"
CERT="$RES_DIR/server.crt"
KEY="$RES_DIR/server.key"     # 私钥只给本地服务端用；App 只需要公钥证书

FROM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$RES_DIR"

if [ -n "$FROM" ]; then
  [ -f "$FROM" ] || { echo "✗ 找不到证书: $FROM" >&2; exit 1; }
  cp "$FROM" "$CERT"
  echo "✓ 已使用现成证书: $FROM"
  echo "  ⚠️ 请自行确认它的 SAN 覆盖你要连的地址（否则主机名校验会失败）"
else
  if [ -f "$CERT" ] && [ "${FORCE:-0}" != "1" ]; then
    echo "✓ 开发证书已存在（FORCE=1 可强制重建）: $CERT"
  else
    # SAN 必须覆盖模拟器要连的地址：模拟器与宿主共享网络栈 → 127.0.0.1 即可；
    # 真机走局域网 → 需要宿主局域网 IP（自动探测后写入）。
    LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
    SAN="IP:127.0.0.1,DNS:localhost"
    [ -n "$LAN_IP" ] && SAN="$SAN,IP:$LAN_IP"

    openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
      -keyout "$KEY" -out "$CERT" \
      -subj "/CN=127.0.0.1/O=AgentLite-Dev" \
      -addext "subjectAltName=$SAN" >/dev/null 2>&1
    chmod 600 "$KEY"
    echo "✓ 已生成开发证书（SAN: $SAN）"
    [ -n "$LAN_IP" ] && echo "  · 真机联调可用地址: https://$LAN_IP:18443"
  fi
fi

# 打印 SPKI 指纹：这是 App 侧真正比对的值（不是整证书哈希）
FP=$(python3 - "$CERT" <<'PY'
import base64, hashlib, re, subprocess, sys, tempfile, os
path = sys.argv[1]
raw = open(path, 'rb').read()
if b'BEGIN CERTIFICATE' in raw:
    b64 = re.sub(rb'-----(BEGIN|END) CERTIFICATE-----', b'', raw)
    der = base64.b64decode(re.sub(rb'\s+', b'', b64))
else:
    der = raw
# 用 openssl 提取公钥 DER（避免在脚本里自研 ASN.1 解析）
with tempfile.NamedTemporaryFile(delete=False, suffix='.der') as f:
    f.write(der); tmp = f.name
try:
    pem = subprocess.run(['openssl', 'x509', '-inform', 'DER', '-in', tmp, '-pubkey', '-noout'],
                         capture_output=True, check=True).stdout
    spki = subprocess.run(['openssl', 'pkey', '-pubin', '-outform', 'DER'],
                          input=pem, capture_output=True, check=True).stdout
finally:
    os.unlink(tmp)
print(base64.b64encode(hashlib.sha256(spki).digest()).decode())
PY
)
echo "  SPKI SHA-256 (供 App 比对): $FP"
echo "✓ 资源就绪: $RES_DIR"
