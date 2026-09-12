#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# deploy-server.sh — 一键把同步服务端部署到 ECS（把 server/README §5 的 5 步手工流程收成一条命令）
#
#   bash deploy/deploy-server.sh              # 只打印计划，不动线上（默认行为）
#   bash deploy/deploy-server.sh --yes        # 真的部署
#   bash deploy/deploy-server.sh --yes --smoke-only   # 只跑线上冒烟回归
#
# 为什么默认不动线上：这是一条会重启线上服务的破坏性操作。计划里把每一步、每个目标
# 路径都打出来，确认无误再 --yes。
#
# 它做的事情（完全沿用服务端原有流程，不改变服务端任何逻辑）：
#   ① 两端契约校验（不一致就别上）
#   ② rsync 源码 → $SERVER_SRV_DIR/src
#   ③ 远端 go 环境检查 + 证书存在性检查
#   ④ 远端执行 server/deploy/install.sh（现编现装 + systemd 重启）
#   ⑤ 本地跑 server/deploy/smoke-test.sh 打真实公网链路（全部断言，失败即非零退出）
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

YES=0; SMOKE_ONLY=0
for a in "$@"; do
  case "$a" in
    --yes|-y) YES=1 ;;
    --smoke-only) SMOKE_ONLY=1 ;;
    *) echo "未知参数：$a（可用 --yes / --smoke-only）" >&2; exit 2 ;;
  esac
done

HOST="$SERVER_SSH_HOST"
SRC_REMOTE="$SERVER_SRV_DIR/src"
BASE="$SYNC_URL"
CRT="$UNI/server/tls/server.crt"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

hr() { printf '%s\n' "------------------------------------------------------------"; }

hr; echo "计划：把同步服务端部署到 $HOST"; hr
cat <<EOF
  目标主机   : $HOST（$SERVER_HOST）
  源码目录   : $SRC_REMOTE
  数据目录   : $SERVER_DATA_DIR
  敏感配置   : $SERVER_ENV_FILE（0600，不写进 systemd 单元）
  证书       : $SERVER_SRV_DIR/tls/server.crt（SAN 必须含 $SERVER_HOST）
  监听       : 0.0.0.0:$SERVER_PORT
  客户端 pin : client/assets/agentlite_sync_ca.pem（sha256 $(shasum -a 256 "$CRT" 2>/dev/null | awk '{print substr($1,1,16)}')…）

  步骤：① 契约校验 → ② rsync 源码 → ③ 远端环境检查 → ④ install.sh（现编现装+重启）
        → ⑤ 公网真实链路冒烟
EOF
hr

echo
echo "① 两端契约校验"
python3 scripts/verify-contract.py --quiet || { echo "✗ 契约不一致，拒绝部署" >&2; exit 1; }

if [ "$SMOKE_ONLY" = "1" ]; then
  echo
  echo "⑤ 仅冒烟（--smoke-only）"
  ADMIN_TOKEN="$ADMIN_TOKEN" bash server/deploy/smoke-test.sh "$BASE" "$CRT"
  exit $?
fi

if [ "$YES" != "1" ]; then
  echo
  echo "以上是计划（未执行任何线上操作）。确认后加 --yes："
  echo "    bash deploy/deploy-server.sh --yes"
  exit 0
fi

echo
echo "② rsync 源码 → $HOST:$SRC_REMOTE"
ssh "$HOST" "mkdir -p '$SRC_REMOTE'"
rsync -az --delete \
  --exclude 'agentlite-server' --exclude '.DS_Store' \
  "$UNI/server/" "$HOST:$SRC_REMOTE/"
echo "   ✓ 已同步（$(find "$UNI/server" -type f | wc -l | tr -d ' ') 个文件）"

echo
echo "③ 远端环境检查"
ssh "$HOST" "set -e
  command -v go >/dev/null || { echo '✗ 远端没有 go：dnf install -y golang'; exit 1; }
  echo \"   go: \$(go version)\"
  [ -f '$SERVER_SRV_DIR/tls/server.crt' ] || { echo '✗ 缺证书：先跑 bash deploy/gen-cert.sh $SERVER_HOST --remote'; exit 1; }
  [ -f '$SERVER_SRV_DIR/tls/server.key' ] || { echo '✗ 缺私钥 server.key'; exit 1; }
  echo '   证书: 就绪'
  [ -f '$SERVER_ENV_FILE' ] || echo '   ! $SERVER_ENV_FILE 不存在：额度网关与管理接口将关闭（只影响充值与发码）'
  echo '   安全组: 请确认入方向 TCP $SERVER_PORT 已放行'"

echo
echo "④ 远端安装（install.sh：CGO_ENABLED=0 现编 → systemd 重启）"
ssh "$HOST" "cd '$SRC_REMOTE' && bash deploy/install.sh"

echo
echo "⑤ 公网真实链路冒烟（$(basename "$CRT")）"
if [ -n "$ADMIN_TOKEN" ]; then
  ADMIN_TOKEN="$ADMIN_TOKEN" bash server/deploy/smoke-test.sh "$BASE" "$CRT"
else
  echo "   （未提供 ADMIN_TOKEN：跳过兑换码管理接口用例，其余照跑）"
  bash server/deploy/smoke-test.sh "$BASE" "$CRT"
fi

hr
echo "✓ 部署完成"
echo "   状态：ssh $HOST systemctl status agentlite-server"
echo "   日志：ssh $HOST journalctl -u agentlite-server -f"
echo "   注意：若本次换过证书，必须重新构建并重装 APK：bash scripts/build-all.sh"
hr
