#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 局域网 P2P 跨实现验证。
#
# 思路：真机对真机的 P2P 需要两台设备同处可达内网，手头设备凑不齐
# （OWW261 走了 VPN、Wi-Fi 关闭）。因此改用**桌面 JVM 写一份独立的对端实现**
# （tests/lan_peer_sim/LanPeerSim.java），让设备真的连过来——这比同类设备互连
# 更能验证协议实现是否正确。
#
# 步骤：
#   1) 注册账号 A，绑定设备 E（给被测设备用）与设备 P（代表桌面模拟对端）
#   2) 让 P 上报 lan_ips=[本机局域网 IP]，于是服务端在会合时把它发给 E
#   3) 在本地启动 LanPeerSim 监听
#   4) 用 UI 自动化在被测设备上登录账号 A → 触发同步 → 观察对端是否收到密文并解出明文
#
# 用法: bash tests/verify_lan_p2p.sh [serial]   （默认 emulator-5554）
set -uo pipefail

SERIAL="${1:-emulator-5554}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="https://8.133.225.105:18443"
CACERT="$ROOT/agentlite-server/tls/server.crt"
SIM_DIR="$ROOT/tests/lan_peer_sim"
PORT=18444

command -v adb >/dev/null 2>&1 || export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
MAC_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1)
[ -n "$MAC_IP" ] || { echo "✗ 取不到本机局域网 IP"; exit 1; }

echo "═══ 局域网 P2P 跨实现验证 ═══"
echo "  被测设备: $SERIAL"
echo "  对端模拟器: $MAC_IP:$PORT"
echo

PY() { python3 -c "$1"; }

echo "[1] 注册账号 + 绑定两台设备"
TS=$(date +%s)
ACCT="lan$TS"; KEY="lankey$RANDOM$RANDOM"
REG=$(curl -sS --max-time 15 --cacert "$CACERT" -X POST "$SERVER/api/v1/account/register" \
        -H 'Content-Type: application/json' -d "{\"name\":\"$ACCT\",\"key\":\"$KEY\"}")
TOK=$(echo "$REG" | PY 'import json,sys;print(json.load(sys.stdin)["token"])')
AID=$(echo "$REG" | PY 'import json,sys;print(json.load(sys.stdin)["account_id"])')
[ -n "$TOK" ] || { echo "  ✗ 注册失败: $REG"; exit 1; }
echo "  账号 $AID ($ACCT)"

# 设备 P：代表桌面模拟对端
RP=$(curl -sS --max-time 15 --cacert "$CACERT" -X POST "$SERVER/api/v1/device/bind" \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
      -d "{\"fingerprint\":\"peer-sim-$TS\",\"name\":\"桌面模拟对端\",\"model\":\"LanPeerSim\",\"brand\":\"Desktop\",\"sdk\":34}")
PID=$(echo "$RP" | PY 'import json,sys;print(json.load(sys.stdin)["device_id"])')
echo "  设备 P（模拟对端）: $PID"

echo "[2] 让 P 上报局域网位置（$MAC_IP:$PORT）"
RV=$(curl -sS --max-time 15 --cacert "$CACERT" -X POST "$SERVER/api/v1/lan/rendezvous" \
      -H "Authorization: Bearer $TOK" -H "X-Device-Id: $PID" -H 'Content-Type: application/json' \
      -d "{\"lan_ips\":[\"$MAC_IP\"],\"port\":$PORT}")
echo "  已登记"

echo "[3] 启动对端模拟器"
SIM_LOG=$(mktemp)
( cd "$SIM_DIR" && java LanPeerSim "$AID" "$KEY" "$PORT" ) > "$SIM_LOG" 2>&1 &
SIM_PID=$!
sleep 2
grep -q LAN_PEER_READY "$SIM_LOG" && echo "  ✓ 已监听（日志 $SIM_LOG）" || { echo "  ✗ 启动失败:"; cat "$SIM_LOG"; kill $SIM_PID 2>/dev/null; exit 1; }

cleanup() { kill $SIM_PID 2>/dev/null; }
trap cleanup EXIT

echo "[4] 在被测设备上登录该账号（真实 UI）"
python3 "$ROOT/tests/android_ui.py" "$SERIAL" login "$ACCT" "$KEY" 2>&1 | sed 's/^/  /'

echo "[5] 触发一次同步（force-stop 后启动，走 onCreate 钩子）"
adb -s "$SERIAL" shell am start -S -n com.dsh.agentlite/.MainActivity >/dev/null 2>&1
echo "  等待局域网握手（最多 40s）…"
GOT=""
for i in $(seq 1 20); do
  if grep -q PEER_RECEIVED "$SIM_LOG"; then GOT=1; break; fi
  sleep 2
done

echo
echo "═══ 结果 ═══"
if [ -n "$GOT" ]; then
  echo "✅ 对端收到并成功解密了设备发来的报文："
  grep PEER_RECEIVED "$SIM_LOG" | sed 's/^/   /'
  grep -q PEER_REPLIED "$SIM_LOG" && echo "✅ 对端回包也被设备方向接受（PEER_REPLIED）"
else
  echo "❌ 40s 内对端未收到任何 P2P 报文"
  echo "--- 对端日志 ---"; sed 's/^/   /' "$SIM_LOG"
  echo "--- 设备端局域网日志 ---"
  adb -s "$SERIAL" logcat -d -s AgentLiteLan:* 2>/dev/null | tail -15 | sed 's/^/   /'
fi
echo
echo "（账号 $ACCT / 设备 P $PID 留在服务端，便于复查）"
