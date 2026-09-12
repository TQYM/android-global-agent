#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# AgentLite 同步服务冒烟测试（账号体系版）
#
# 用法:
#   bash deploy/smoke-test.sh https://8.133.225.105:18443 tls/server.crt
#   ADMIN_TOKEN=xxx bash deploy/smoke-test.sh ...     # 追加兑换码管理接口用例
set -uo pipefail

BASE="${1:-https://8.133.225.105:18443}"
CACERT="${2:-}"

if [ -n "$CACERT" ] && [ -f "$CACERT" ]; then
    TLS=(--cacert "$CACERT")
    echo "TLS: 使用 CA 证书 $CACERT（含主机名校验）"
else
    TLS=(-k)
    echo "TLS: 跳过校验（-k）"
fi
echo "目标: $BASE"
echo

PASS=0; FAIL=0
ok()  { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }

jget() {
    python3 -c '
import json,sys
cur=json.loads(sys.argv[1])
for k in sys.argv[2].split("."):
    if k=="": continue
    cur = cur[int(k)] if k.isdigit() else cur[k]
print(cur if not isinstance(cur,(dict,list)) else json.dumps(cur,ensure_ascii=False))
' "$1" "$2" 2>/dev/null
}
jlen() { python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print(len(d[sys.argv[2]]))' "$1" "$2" 2>/dev/null; }

req() {
    local m="$1" p="$2" body="${3:-}" tok="${4:-}" dev="${5:-}"
    local args=(-sS --max-time 20 -X "$m" "${TLS[@]}" -H 'Content-Type: application/json')
    [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
    [ -n "$dev" ] && args+=(-H "X-Device-Id: $dev")
    [ -n "$body" ] && args+=(--data "$body")
    curl "${args[@]}" "$BASE$p"
}
code() {
    local m="$1" p="$2" body="${3:-}" tok="${4:-}" dev="${5:-}"
    local args=(-s -o /dev/null -w '%{http_code}' --max-time 20 -X "$m" "${TLS[@]}" -H 'Content-Type: application/json')
    [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
    [ -n "$dev" ] && args+=(-H "X-Device-Id: $dev")
    [ -n "$body" ] && args+=(--data "$body")
    curl "${args[@]}" "$BASE$p"
}

TS=$(date +%s)
ACC="t$TS"; KEY="key-$TS-abcdefgh"
ACC2="u$TS"; KEY2="key2-$TS-abcdefgh"

echo "[1] 健康检查"
H=$(req GET /health)
if [ "$(jget "$H" ok)" = "True" ]; then
    ok "GET /health → ok（账号 $(jget "$H" accounts) / 设备 $(jget "$H" devices)）"
else
    bad "健康检查失败: $H"; exit 1
fi

echo "[2] 账号体系"
R=$(req POST /api/v1/account/register "{\"name\":\"$ACC\",\"key\":\"$KEY\"}")
TOK=$(jget "$R" token); AID=$(jget "$R" account_id); Q0=$(jget "$R" quota)
[ -n "$TOK" ] && ok "注册成功 account_id=$AID 初始额度=$Q0" || { bad "注册失败: $R"; exit 1; }
[ "$(jget "$R" lan_key_params.algorithm)" = "PBKDF2WithHmacSHA256" ] \
    && ok "返回局域网密钥派生参数（PBKDF2WithHmacSHA256）" || bad "缺少 lan_key_params"

C=$(code POST /api/v1/account/register "{\"name\":\"$ACC\",\"key\":\"$KEY\"}")
[ "$C" = "409" ] && ok "同名账号重复注册 → 409" || bad "期望 409，实际 $C"

C=$(code POST /api/v1/account/login "{\"name\":\"$ACC\",\"key\":\"wrong-key-xxxxxxxx\"}")
[ "$C" = "401" ] && ok "密钥错误登录 → 401" || bad "期望 401，实际 $C"

L=$(req POST /api/v1/account/login "{\"name\":\"$ACC\",\"key\":\"$KEY\"}")
TOK2=$(jget "$L" token)
if [ -n "$TOK2" ] && [ "$TOK2" != "$TOK" ]; then ok "登录成功且令牌已轮换"; else bad "登录异常: $L"; fi
C=$(code GET /api/v1/account/me "" "$TOK")
[ "$C" = "401" ] && ok "旧令牌已失效 → 401" || bad "旧令牌仍可用（期望 401，实际 $C）"
TOK="$TOK2"

echo "[3] 设备绑定"
FPA="fpA-$TS"; FPB="fpB-$TS"
RA=$(req POST /api/v1/device/bind "{\"fingerprint\":\"$FPA\",\"name\":\"手机A\",\"model\":\"PKX110\",\"brand\":\"OnePlus\",\"sdk\":36,\"app_version\":\"smoke\"}" "$TOK")
DA=$(jget "$RA" device_id)
[ -n "$DA" ] && ok "设备 A 绑定成功 $DA" || { bad "绑定失败: $RA"; exit 1; }
RB=$(req POST /api/v1/device/bind "{\"fingerprint\":\"$FPB\",\"name\":\"平板B\",\"model\":\"YLP-W00\",\"brand\":\"HONOR\",\"sdk\":37,\"app_version\":\"smoke\"}" "$TOK")
DB=$(jget "$RB" device_id)
[ -n "$DB" ] && ok "设备 B 绑定成功 $DB（同账号）" || bad "绑定失败: $RB"

RB2=$(req POST /api/v1/device/bind "{\"fingerprint\":\"$FPA\",\"name\":\"手机A\",\"model\":\"PKX110\",\"brand\":\"OnePlus\",\"sdk\":36,\"app_version\":\"smoke\"}" "$TOK")
if [ "$(jget "$RB2" device_id)" = "$DA" ] && [ "$(jget "$RB2" created)" = "False" ]; then
    ok "同指纹重复绑定复用 device_id（created=false）"
else
    bad "重复绑定异常: $RB2"
fi

echo "[4] 账号级信息共享"
TSN=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PA=$(req POST /api/v1/sync/push "{\"tasks\":[{\"id\":\"ta-$TS\",\"ts\":\"$TSN\",\"data\":{\"goal\":\"A 的任务\"}}],\"status\":{\"battery\":66}}" "$TOK" "$DA")
[ "$(jget "$PA" accepted)" = "1" ] && ok "设备 A 上行 1 条（accepted=1）" || bad "A 上行异常: $PA"

PULLB=$(req GET "/api/v1/sync/pull?since=0" "" "$TOK" "$DB")
NB=$(jlen "$PULLB" records)
[ "${NB:-0}" -ge 1 ] && ok "★ 设备 B 拉到设备 A 的记录（共 $NB 条）——同账号共享生效" \
    || bad "设备 B 未看到 A 的数据（records=$NB）: $PULLB"

ORIGIN=$(python3 -c 'import json,sys;r=json.loads(sys.argv[1])["records"];print(r[0]["device_id"] if r else "")' "$PULLB" 2>/dev/null)
[ "$ORIGIN" = "$DA" ] && ok "记录带来源 device_id 溯源（来源=$ORIGIN）" || bad "溯源字段异常: $ORIGIN"

PB=$(req POST /api/v1/sync/push "{\"memories\":[{\"id\":\"mb-$TS\",\"data\":{\"text\":\"B 的记忆\"}}]}" "$TOK" "$DB")
[ "$(jget "$PB" accepted)" = "1" ] && ok "设备 B 上行记忆" || bad "B 上行异常: $PB"
PULLA=$(req GET "/api/v1/sync/pull?since=0" "" "$TOK" "$DA")
NA=$(jlen "$PULLA" records)
[ "${NA:-0}" -ge 2 ] && ok "★ 设备 A 也拉到了 B 的记录（共 $NA 条）——双向共享" || bad "A 未看到 B 的数据: $NA"

PA2=$(req POST /api/v1/sync/push "{\"tasks\":[{\"id\":\"ta-$TS\",\"ts\":\"$TSN\"}]}" "$TOK" "$DA")
[ "$(jget "$PA2" duplicates)" = "1" ] && ok "重复推送 duplicates=1（幂等）" || bad "幂等异常: $PA2"

echo "[5] 跨账号越权防护"
R2=$(req POST /api/v1/account/register "{\"name\":\"$ACC2\",\"key\":\"$KEY2\"}")
TOK_2=$(jget "$R2" token)
C=$(code POST /api/v1/sync/push "{\"tasks\":[{\"id\":\"x\",\"data\":{}}]}" "$TOK_2" "$DA")
[ "$C" = "403" ] && ok "★ 账号2令牌 + 账号1设备ID → 403（越权被拦）" || bad "期望 403，实际 $C"
C=$(code POST /api/v1/sync/push "{\"tasks\":[{\"id\":\"x\",\"data\":{}}]}" "$TOK")
[ "$C" = "400" ] && ok "缺 X-Device-Id → 400" || bad "期望 400，实际 $C"
C=$(code GET /api/v1/sync/pull "" "$TOK_2" "$DA")
[ "$C" = "403" ] && ok "账号2 拉取账号1 的记录 → 403" || bad "跨账号拉取未被拦（实际 $C）"

echo "[6] 局域网会合"
LANA='["192.168.5.11"]'; LANB='["192.168.5.12"]'
RV=$(req POST /api/v1/lan/rendezvous "{\"lan_ips\":$LANA,\"port\":18444}" "$TOK" "$DA")
[ "$(jget "$RV" same_lan)" = "False" ] && ok "设备 A 先上报 → 暂无邻居（same_lan=false）" || bad "首报异常: $RV"
RV=$(req POST /api/v1/lan/rendezvous "{\"lan_ips\":$LANB,\"port\":18444}" "$TOK" "$DB")
PEERS=$(jlen "$RV" peers)
[ "${PEERS:-0}" -ge 1 ] && ok "★ 设备 B 看到同账号邻居 $PEERS 台（同公网出口→判定同内网）" || bad "未发现邻居: $RV"
RV=$(req POST /api/v1/lan/rendezvous "{\"lan_ips\":$LANA,\"port\":18444}" "$TOK" "$DA")
PN=$(python3 -c 'import json,sys;p=json.loads(sys.argv[1])["peers"];print(p[0]["lan_ips"][0] if p else "")' "$RV" 2>/dev/null)
[ "$PN" = "192.168.5.12" ] && ok "★ 设备 A 拿到 B 的局域网地址 $PN（会合成功）" || bad "会合地址异常: $PN / $RV"
C=$(code POST /api/v1/lan/rendezvous "{\"lan_ips\":[],\"port\":18444}" "$TOK_2" "$DA")
[ "$C" = "403" ] && ok "越权设备ID上报局域网 → 403" || bad "期望 403，实际 $C"

echo "[7] 额度与兑换码"
ME=$(req GET /api/v1/account/me "" "$TOK")
[ "$(jlen "$ME" devices)" = "2" ] && ok "account/me 返回 2 台设备" || bad "设备列表异常: $ME"

if [ -n "${ADMIN_TOKEN:-}" ]; then
    CG=$(curl -sS --max-time 20 "${TLS[@]}" -X POST -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" --data "{\"count\":2,\"units\":1000,\"batch\":\"smoke-$TS\"}" "$BASE/api/v1/admin/codes")
    C1=$(python3 -c 'import json,sys;print(json.loads(sys.argv[1])["codes"][0])' "$CG" 2>/dev/null)
    [ -n "$C1" ] && ok "管理接口发放兑换码（样例 $C1）" || bad "发码失败: $CG"
    RC=$(req POST /api/v1/redeem "{\"code\":\"$C1\"}" "$TOK")
    ADD=$(jget "$RC" units_added); BAL=$(jget "$RC" quota)
    [ "$ADD" = "1000" ] && ok "★ 兑换成功 +$ADD，余额=$BAL" || bad "兑换异常: $RC"
    C=$(code POST /api/v1/redeem "{\"code\":\"$C1\"}" "$TOK")
    [ "$C" = "404" ] && ok "★ 同一兑换码二次核销 → 404（防双花）" || bad "双花未被拦（实际 $C）"
    C=$(code POST /api/v1/redeem "{\"code\":\"AGT-0000-0000-0000\"}" "$TOK")
    [ "$C" = "404" ] && ok "不存在的兑换码 → 404" || bad "期望 404，实际 $C"
    C=$(code POST /api/v1/admin/codes "{\"count\":1,\"units\":10}")
    [ "$C" = "401" ] && ok "无管理令牌调用管理接口 → 401" || bad "期望 401，实际 $C"
else
    echo "  （未设 ADMIN_TOKEN，跳过管理接口与兑换用例）"
fi

echo "[8] 额度网关接口面"
C=$(code POST /v1/chat/completions "{\"model\":\"m\",\"messages\":[]}")
[ "$C" = "401" ] && ok "chat/completions 无令牌 → 401" || bad "期望 401，实际 $C"
C=$(code GET /v1/models "")
[ "$C" = "401" ] && ok "/v1/models 无令牌 → 401" || bad "期望 401，实际 $C"
C=$(code POST /v1/chat/completions "{\"model\":\"m\",\"messages\":[]}" "$TOK")
# 断言的是「鉴权是否通过」，不是上游是否成功：
#   401 = 鉴权失败（有问题）
#   503 = 网关未启用（未配上游密钥）
#   402 = 已鉴权但额度耗尽
#   2xx = 转发成功
#   其它 4xx/5xx = 上游返回的错误被原样透传（例如模型名不存在），同样说明鉴权已过
if [ "$C" = "401" ]; then
    bad "带账号令牌仍返回 401 —— 网关鉴权有问题"
elif [ "$C" = "503" ]; then
    ok "网关未启用（未配置上游密钥），但鉴权已通过"
elif [ "$C" = "402" ]; then
    ok "网关已鉴权但额度不足 → 402"
elif [ "${C#2}" != "$C" ]; then
    ok "网关已启用并转发成功 → $C"
else
    ok "网关已鉴权，上游返回 $C 被原样透传（鉴权通过，属预期）"
fi
C=$(code POST /api/v1/device/bind "{\"fingerprint\":\"x\"}")
[ "$C" = "401" ] && ok "设备绑定无令牌 → 401" || bad "期望 401，实际 $C"

echo
echo "──────────────────────────────"
printf '通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
echo "全部通过 ✅"
