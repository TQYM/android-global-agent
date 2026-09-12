#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# e2e-local.sh — 本地端到端：把整合后的两端**真的**跑起来对接一次
#
#   bash scripts/e2e-local.sh
#
# 契约校验是静态比对（读代码），本脚本补上**动态**那一半：起一个真的服务端进程，
# 用服务端自带的冒烟脚本打真实 TLS 链路，再用**客户端真实的 TlsPin.java** 验证
# 证书 pin 握手。全程在 127.0.0.1 上，不碰线上，不需要真机。
#
# 四段验证：
#   ① 服务端独立可用：起本地实例 → 跑 server/deploy/smoke-test.sh（账号/同步/额度/兑换全链）
#   ② 客户端 pin 成功：用 TlsPin + 本实例证书 → /health 必须 200
#   ③ pin 真的有效：服务端换成**另一张**证书 → 必须握手失败（证明 pin 不是摆设）
#   ④ 成功来自 pin：不应用 pin（走系统 CA）→ 自签证书必须失败
#
# 需要 Go（无则提示 bash scripts/fetch-go.sh）与 JDK（javac/java）。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

E2E="$UNI/.e2e"
PORT_A="${E2E_PORT:-18443}"
PORT_B="${E2E_PORT_B:-18445}"
HOST=127.0.0.1
CERT_A="$E2E/tls/server.crt"
CERT_B="$E2E/tls-b/server.crt"
GEN="$E2E/javagen"
CLASSES="$E2E/classes"
ADMIN_TOKEN="e2e-$(date +%s)-$RANDOM"

PIDS=()
cleanup() {
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

hr() { printf '%s\n' "============================================================"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
FAILED=0

# ---------- 前置 ----------
GO_BIN="${GO_BIN:-}"
[ -x "$GO_BIN" ] || GO_BIN="$HOME/.agent-unified-go/go/bin/go"
[ -x "$GO_BIN" ] || GO_BIN="$(command -v go || true)"
command -v javac >/dev/null || { echo "✗ 需要 JDK（javac）来编译客户端真实的 TlsPin.java" >&2; exit 1; }
if [ -z "$GO_BIN" ] || [ ! -x "$GO_BIN" ]; then
  echo "✗ 找不到 Go 工具链。本机自备一份：bash scripts/fetch-go.sh" >&2
  exit 3
fi
echo "Go: $("$GO_BIN" version)"

# 端口占用预检（避免连到别人的服务上，得出假结论）
for p in "$PORT_A" "$PORT_B"; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "✗ 端口 $p 已被占用，请先释放（或 E2E_PORT/E2E_PORT_B 换端口）" >&2
    exit 1
  fi
done

rm -rf "$E2E"
mkdir -p "$E2E/data-a" "$E2E/data-b" "$E2E/tls" "$E2E/tls-b" "$GEN" "$CLASSES"

# 证书：A 供正常链路，B 供「换了证书必须失败」的负例
openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
  -keyout "$E2E/tls/server.key" -out "$CERT_A" \
  -subj "/CN=$HOST/O=AgentLite-E2E-A" -addext "subjectAltName=IP:$HOST" 2>/dev/null
openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
  -keyout "$E2E/tls-b/server.key" -out "$CERT_B" \
  -subj "/CN=$HOST/O=AgentLite-E2E-B" -addext "subjectAltName=IP:$HOST" 2>/dev/null
echo "证书A $(shasum -a 256 "$CERT_A" | awk '{print substr($1,1,16)}')…  证书B $(shasum -a 256 "$CERT_B" | awk '{print substr($1,1,16)}')…"

# ---------- 构建服务端（本机架构） ----------
hr; echo "▶ 构建服务端（本机架构，供本地实例）"; hr
BIN="$E2E/agentlite-server"
( cd server && CGO_ENABLED=0 "$GO_BIN" build -trimpath -ldflags "-s -w" -o "$BIN" . )
echo "   $("$BIN" -version)   $(stat -f%z "$BIN") bytes"

start_server() {  # start_server <port> <data> <certdir> → 打印 PID
  "$BIN" -addr "$HOST:$1" -data "$2" -cert "$3/server.crt" -key "$3/server.key" \
         -admin-token "$ADMIN_TOKEN" -upstream-key "" >"$2/server.log" 2>&1 &
  echo $!
}

wait_health() {  # wait_health <port> <cacert>
  for _ in $(seq 1 40); do
    if curl -sS --max-time 2 --cacert "$2" "https://$HOST:$1/health" >/dev/null 2>&1; then return 0; fi
    sleep 0.25
  done
  return 1
}

# ---------- ① 服务端独立可用 ----------
hr; echo "▶ ① 服务端本地实例 + 自带冒烟脚本（真实 TLS 链路）"; hr
PID_A=$(start_server "$PORT_A" "$E2E/data-a" "$E2E/tls"); PIDS+=("$PID_A")
wait_health "$PORT_A" "$CERT_A" || { echo "✗ 服务端未能起来，日志：" >&2; cat "$E2E/data-a/server.log" >&2; exit 1; }
echo "   实例已起：https://$HOST:$PORT_A （pid $PID_A，网关关闭，数据目录 .e2e/data-a）"
echo

set +e
ADMIN_TOKEN="$ADMIN_TOKEN" bash server/deploy/smoke-test.sh "https://$HOST:$PORT_A" "$CERT_A"
SMOKE_RC=$?
set -e
echo
if [ "$SMOKE_RC" = "0" ]; then ok "服务端冒烟全部通过（账号/设备/同步/会合/额度/兑换/管理鉴权）"
else bad "服务端冒烟存在失败项（退出码 $SMOKE_RC）"; fi

# ---------- ② 客户端真实 TlsPin 握手 ----------
hr; echo "▶ ② 用客户端真实源码跑证书 pin"; hr

# 生成客户端构建期会生成的同款 DevConfig / PinnedCa（格式对齐 client/build-mac.sh）
cat > "$GEN/DevConfig.java" <<EOF
package com.dsh.agentlite;
public final class DevConfig {
    public static final String API_KEY = "";
    public static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String MODEL = "qwen3.5-omni-plus";
    public static final String SYNC_URL = "https://$HOST:$PORT_A";
}
EOF
{
  echo 'package com.dsh.agentlite;'
  echo 'public final class PinnedCa {'
  echo '    public static final String PEM ='
  lines=$(wc -l < "$CERT_A" | tr -d ' ')
  i=0
  while IFS= read -r line; do
    i=$((i+1))
    if [ "$i" -eq "$lines" ]; then printf '        "%s\\n";\n' "$line"
    else printf '        "%s\\n" +\n' "$line"; fi
  done < "$CERT_A"
  echo '}'
} > "$GEN/PinnedCa.java"

javac -d "$CLASSES" -encoding UTF-8 \
  client/src/com/dsh/agentlite/TlsPin.java "$GEN/DevConfig.java" "$GEN/PinnedCa.java" \
  tests/pin-smoke/PinSmoke.java
echo "   已编译：客户端 TlsPin.java（原样，未改）+ 生成的 DevConfig/PinnedCa + PinSmoke"
echo

run_pin() {  # run_pin <url> <expect> <说明>
  if java -cp "$CLASSES" PinSmoke "$1" "$2" | sed 's/^/     /'; then
    ok "$3"
  else
    bad "$3"
  fi
}

run_pin "https://$HOST:$PORT_A/health" ok "应用 pin 连本地实例 → 200（信任锚对得上）"

# ---------- ③④ 负例：pin 不是摆设 ----------
hr; echo "▶ ③④ 负例断言（证明这个绿是真的）"; hr
PID_B=$(start_server "$PORT_B" "$E2E/data-b" "$E2E/tls-b"); PIDS+=("$PID_B")
wait_health "$PORT_B" "$CERT_B" || { echo "✗ 第二个实例未起来" >&2; exit 1; }
echo "   第二个实例：https://$HOST:$PORT_B（出示的是另一张证书）"
echo

run_pin "https://$HOST:$PORT_B/health" fail "pin 着证书A 去连证书B → 必须握手失败"
run_pin "https://$HOST:$PORT_A/health" fail-unpinned "不应用 pin（系统 CA）连自签实例 → 必须失败"

# ---------- 汇总 ----------
hr
if [ "$FAILED" = "0" ]; then
  echo "✓ 本地端到端全部通过：服务端独立可用 + 客户端 pin 生效 + 两个负例成立"
  echo "  证据：.e2e/data-a/server.log（服务端日志与全部冒烟断言输出）"
else
  echo "✗ 存在失败项（见上）—— 两端整合尚未真正打通" >&2
fi
hr
exit "$FAILED"
