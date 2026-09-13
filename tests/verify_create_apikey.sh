#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 打通 CreateApiKey 的真实请求形状（PURCHASE_REDIRECT_DESIGN §5.1 的卡点 1.2）。
#
# 用法（在服务器或任意有 python3 的机器上）：
#   export ALIYUN_AK_ID='你的 AccessKeyId'      # 建议 RAM 子账号，最小权限 bailian:CreateApiKey
#   export ALIYUN_AK_SECRET='你的 AccessKeySecret'
#   bash tests/verify_create_apikey.sh
#   bash tests/verify_create_apikey.sh --show-key    # 需要看到新 Key 明文时
#
# 安全设计：
#   - AK/SK 只从环境变量读，**不写文件、不进命令行参数**（避免落 shell history / ps）。
#   - 签名后的 Authorization 头**只存在于 python 进程内存**，不落任何临时文件。
#   - 脚本自身不含真凭据，可安全入 git。
#   - 默认遮蔽响应里的 apiKeyValue。要看明文才加 --show-key。

set -euo pipefail

SHOW_KEY=0
[[ "${1:-}" == "--show-key" ]] && SHOW_KEY=1

: "${ALIYUN_AK_ID:?请先 export ALIYUN_AK_ID}"
: "${ALIYUN_AK_SECRET:?请先 export ALIYUN_AK_SECRET}"

HOST="${ALIYUN_BAILIAN_HOST:-modelstudio.cn-beijing.aliyuncs.com}"
ACTION="CreateApiKey"
VERSION="2026-02-10"
API_PATH="/modelstudio/apikeys"
export WORKSPACE_ID="${BAILIAN_WORKSPACE_ID:-}"     # 留空 = 落到默认业务空间
export DESC="${BAILIAN_KEY_DESC:-AgentLite 自建}"
export HOST ACTION VERSION API_PATH SHOW_KEY

cat <<EOF
== CreateApiKey 打通验证 ==
host=$HOST  path=$API_PATH  version=$VERSION  workspace=${WORKSPACE_ID:-<默认>}

EOF

python3 <<'PY'
import hmac, hashlib, os, sys, json, datetime, secrets, urllib.parse, urllib.request

AK_ID     = os.environ["ALIYUN_AK_ID"]
AK_SECRET = os.environ["ALIYUN_AK_SECRET"]
host      = os.environ["HOST"]
action    = os.environ["ACTION"]
version   = os.environ["VERSION"]
path      = os.environ["API_PATH"]
ws        = os.environ["WORKSPACE_ID"]
desc      = os.environ["DESC"]
show_key  = os.environ["SHOW_KEY"] == "1"

EMPTY = hashlib.sha256(b"").hexdigest()


def pe(s):
    """RFC3986：只放行 A-Za-z0-9-_.~，其余 %XX"""
    return urllib.parse.quote(str(s), safe="-_.~")


def build(ak_id, ak_secret, headers, canonical_query, body=""):
    """按官方伪代码构造 CanonicalRequest → StringToSign → Signature → Authorization"""
    payload_hash = hashlib.sha256(body.encode()).hexdigest()
    hdrs = dict(headers)
    hdrs["x-acs-content-sha256"] = payload_hash
    canonical_headers = "".join(f"{k}:{hdrs[k]}\n" for k in sorted(hdrs))
    signed_headers = ";".join(sorted(hdrs))
    cr = "\n".join(["POST", path, canonical_query, canonical_headers, signed_headers, payload_hash])
    sts = "ACS3-HMAC-SHA256\n" + hashlib.sha256(cr.encode()).hexdigest()
    sig = hmac.new(ak_secret.encode(), sts.encode(), hashlib.sha256).hexdigest()
    auth = f"ACS3-HMAC-SHA256 Credential={ak_id},SignedHeaders={signed_headers},Signature={sig}"
    return hdrs, auth, cr, sts, sig


# ---------- 1) 固定向量自检：用假 AK/SK 验证算法链，与 Java 侧对齐 ----------
vec_headers = {
    "host": "bailian.aliyuncs.com",
    "x-acs-action": "CreateApiKey",
    "x-acs-date": "2026-09-13T10:00:00Z",
    "x-acs-version": "2026-02-10",
}
_, _, _, _, vec_sig = build(
    "AKID_FAKE_FOR_TEST", "SECRET_FAKE_FOR_TEST",
    vec_headers, "description=test&workspaceId=ws-8af73c50f5596193")

WANT = "b8831a855536c83b6143577b80b3805242f8fee5f76ae5b4f0731f6b1bff78e7"
ok = vec_sig == WANT
print(f"固定向量自检（对齐 Java 侧）: {'OK' if ok else '不一致！'}")
if not ok:
    print(f"  期望 {WANT}\n  实际 {vec_sig}")
    print("  → python 与 Java 算法链不一致，先别打真实请求")
    sys.exit(1)
print()

# ---------- 2) 真实请求 ----------
date  = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
nonce = secrets.token_hex(16)

query = {}
if ws:
    query["workspaceId"] = ws
if desc:
    query["description"] = desc
canonical_query = "&".join(f"{pe(k)}={pe(query[k])}" for k in sorted(query))

headers = {
    "host": host,
    "x-acs-action": action,
    "x-acs-date": date,
    "x-acs-signature-nonce": nonce,
    "x-acs-version": version,
}
hdrs, auth, cr, sts, sig = build(AK_ID, AK_SECRET, headers, canonical_query)

url = f"https://{host}{path}" + (f"?{canonical_query}" if canonical_query else "")
print("== 发送请求（AK 未出现在任何文件/命令行里）==")
print("POST", url)
print()

req = urllib.request.Request(url, data=b"", method="POST")
req.add_header("Authorization", auth)
for k, v in hdrs.items():
    if k != "host":
        req.add_header(k, v)
req.add_header("Content-Type", "application/json")

try:
    with urllib.request.urlopen(req, timeout=30) as r:
        status, raw = r.status, r.read().decode("utf-8", "replace")
except urllib.error.HTTPError as e:
    status, raw = e.code, e.read().decode("utf-8", "replace")
except Exception as e:
    print("!! 网络层失败:", e)
    print("   → 检查 host 是否可达；默认 bailian.aliyuncs.com 已实测可达")
    sys.exit(2)

print("== 响应 ==")
print(f"HTTP {status}")
try:
    d = json.loads(raw)
    if not show_key and isinstance(d, dict) and isinstance(d.get("apiKey"), dict) \
            and "apiKeyValue" in d["apiKey"]:
        d["apiKey"]["apiKeyValue"] = "<已遮蔽，加 --show-key 查看>"
    print(json.dumps(d, ensure_ascii=False, indent=2))
except Exception:
    import re
    print(re.sub(r'(sk-[A-Za-z0-9._\-]{4})[A-Za-z0-9._\-]+', r'\1<已遮蔽>', raw))

# ---------- 3) 判读 ----------
# host 判定依据（2026-09-13 假凭据矩阵实测，4 个 host × 2 个 version）：
#   modelstudio.cn-beijing.aliyuncs.com → 404 InvalidAccessKeyId.NotFound
#       = 标准 OpenAPI 网关，**已进到凭据查找阶段** → host/version/action 全部正确 ✅【当前默认】
#   bailian.aliyuncs.com                → 200 + {"Code":"100016","Message":"API令牌认证失败"}
#       = 另一套（老百炼）网关的扁平错误码。**陷阱**：它对 2023-12-29（明显非 ModelStudio
#         版本）也返回同样的 100016，说明该网关不校验版本 → 用它做探测会得出"版本被承认"的
#         错误结论。曾据此误判 host，已纠正。
#   bailian.cn-beijing.aliyuncs.com     → 400 InvalidVersion（不认此版本）
#   modelstudio.aliyuncs.com            → DNS 不存在（regional 规则要求带 region）
try:
    _d = json.loads(raw)
except Exception:
    _d = {}
code = str(_d.get("Code", "")) if isinstance(_d, dict) else ""
success = _d.get("Success") if isinstance(_d, dict) else None
got_key = isinstance(_d.get("apiKey"), dict) and bool(_d["apiKey"].get("apiKeyValue"))

print("== 判读 ==")
# 成功必须以 Success=true 且 apiKey.apiKeyValue 存在为准 —— **HTTP 200 不作数**：
# 认证失败也返回 200（bailian 网关），404 也可能只是 key 不存在。专门写死，避免误报「打通了」。
if status == 200 and success is True and got_key:
    print("  [OK] 形状全部确认：host / path / version / 参数 / 返回字段名都已验证。")
    print("       → 下一步：按此实现 BailianClient.java（解析 apiKey.apiKeyValue）")
elif code == "InvalidAccessKeyId.NotFound" or code == "100016" or success is False:
    print("  [凭据] 网关路由正确（已进到凭据查找阶段），但 AK 无效/不存在 → 还差一把有效 AK。")
    print(f"         Code={code} Message={_d.get('Message','')}")
    print("         → 这**不是**签名错：签名不是这一层校验的（AK 不存在时网关无法取到 secret")
    print("           去验签，所以签名错误也会表现为这个错）。换有效 RAM 子账号 AK 再跑。")
elif code == "InvalidVersion":
    print("  [host] 不对：该网关不认这个 version → 换 modelstudio.cn-beijing.aliyuncs.com")
elif code == "SignatureDoesNotMatch":
    print("  [签名] 有偏差 —— 看下面中间串，逐行比对官方伪代码")
elif "Forbidden" in code or "Permission" in code:
    print("  [权限] AK 有效但权限不足，需要 RAM 自定义策略 bailian:CreateApiKey（或 modelstudio:*）")
else:
    print(f"  [?] 未归类：HTTP {status} Code={code} —— 请人工看上面的响应体")

if not (status == 200 and success is True and got_key):
    print()
    print("  ---- 本次的 CanonicalRequest（AK 已剔除，可安全贴出）----")
    print(cr)
    print("  ---- StringToSign ----")
    print(sts)
    print("  ---- Signature ----")
    print(sig)
PY
