#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# selftest-verifier.sh — 变异测试：证明契约门禁不是橡皮图章
#
#   bash scripts/selftest-verifier.sh
#
# 一个永远报绿的校验器比没有校验器更危险。本脚本往**副本**里注入 6 种真实发生过的
# 漂移，逐一确认 verify-contract.py 会 FAIL，并且失败的是**对应的那一组**检查；
# 每次注入后立刻从原项目复原（trap 兜底，即使中途 Ctrl-C 也不会留下脏副本）。
#
# 注入的漂移都不是假想的，都是这类双端系统上真实会发生的错：
#   客户端改了 KDF 迭代数忘了同步文档 / 换了证书忘了重新打包 APK /
#   服务端删了路由 / 客户端接口拼错 / 远程配置白名单被放宽（安全红线） /
#   服务端错误码改名但客户端文案没跟着改
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

CLIENT_LAN="client/src/com/dsh/agentlite/LanSync.java"
CLIENT_SYNC="client/src/com/dsh/agentlite/SyncClient.java"
CLIENT_PEM="client/assets/agentlite_sync_ca.pem"
SERVER_MAIN="server/main.go"
SERVER_API="server/api.go"

# 复原两件事：副本从原项目重灌 + 证书 pin 从服务端证书重下发
# （证书 pin 被 sync-sources 刻意排除，它由 sync-cert.sh 接管）
restore() {
  bash scripts/sync-sources.sh >/dev/null 2>&1 || true
  bash scripts/sync-cert.sh   >/dev/null 2>&1 || true
}
trap restore EXIT

PASS_N=0; FAIL_N=0
ok()  { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS_N=$((PASS_N+1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL_N=$((FAIL_N+1)); }

# 校验器在指定分组里是否报了 FAIL
group_failed() {  # group_failed <分组名>；校验器发现 FAIL 时会返回非零，故必须显式吞掉
  local out
  out=$(python3 scripts/verify-contract.py --json 2>/dev/null || true)
  printf '%s' "$out" | python3 -c '
import json,sys
g=sys.argv[1]
try:
    d=json.load(sys.stdin)
except Exception:
    print("no"); raise SystemExit(0)
print("yes" if any(r["status"]=="FAIL" and r["group"]==g for r in d["results"]) else "no")
' "$1" || true
}

mutate() { python3 - "$@" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
assert s.count(old) >= 1, "变异锚点未命中：%s ← %r" % (path, old)
open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
}

expect_fail() {  # expect_fail <说明> <期望失败的分组> <变异命令...>
  local desc="$1" group="$2"; shift 2
  restore
  "$@" >/dev/null
  local got; got=$(group_failed "$group")
  if [ "$got" = "yes" ]; then ok "$desc → 被拦住（分组「$group」报 FAIL）"
  else bad "$desc → 未被拦住！分组「$group」没有报 FAIL"; fi
}

hr() { printf '%s\n' "============================================================"; }
hr; echo "契约门禁变异测试（注入真实漂移，看它是否会被拦住）"; hr

# 基线：干净副本必须全绿，否则后面的结论都没意义
restore
if python3 scripts/verify-contract.py --quiet >/dev/null 2>&1; then
  ok "基线：干净副本 26 项全通过"
else
  bad "基线：干净副本就没通过，请先查明（后续结论无效）"
  exit 1
fi
echo

expect_fail "客户端 KDF 迭代数漂移（100000→100001）" "局域网线协议" \
  mutate "$CLIENT_LAN" "KDF_ITERATIONS = 100_000" "KDF_ITERATIONS = 100_001"

expect_fail "换证书后忘了重新打包（pin 与 server.crt 不一致）" "证书 pin" \
  sh -c "printf '\n# stale pin\n' >> '$CLIENT_PEM'"

expect_fail "服务端删掉 /api/v1/sync/pull 路由" "路由覆盖" \
  mutate "$SERVER_MAIN" 'mux.HandleFunc("GET /api/v1/sync/pull"' '//mux.HandleFunc("GET /api/v1/sync/pull"'

expect_fail "客户端把 /api/v1/lan/rendezvous 拼错成 …vous2" "路由覆盖" \
  mutate "$CLIENT_SYNC" '"/api/v1/lan/rendezvous"' '"/api/v1/lan/rendezvous2"'

expect_fail "远程配置白名单被放宽（加进 base_url，安全红线）" "配置白名单" \
  mutate "$CLIENT_SYNC" '"max_steps", "planner_model", "vision", "spec_inject", "spec_clamp"' \
                        '"max_steps", "planner_model", "vision", "spec_inject", "spec_clamp", "base_url"'

expect_fail "服务端把 account_exists 改名（客户端中文提示永不出现）" "错误码" \
  mutate "$SERVER_API" '"account_exists"' '"account_taken"'

echo
hr
restore
if python3 scripts/verify-contract.py --quiet >/dev/null 2>&1 && bash scripts/sync-sources.sh --check >/dev/null 2>&1; then
  ok "复原：副本已从原项目复原，契约全绿且零改动成立"
else
  bad "复原失败 —— 请手动执行 bash scripts/sync-sources.sh"
fi
hr
printf '变异测试：%d 项符合预期 / %d 项异常\n' "$PASS_N" "$FAIL_N"
[ "$FAIL_N" -eq 0 ] || exit 1
echo "结论：契约门禁能拦住 6 类真实漂移，且失败定位到正确的检查分组 ✅"
