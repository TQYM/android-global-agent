#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# build-all.sh — 一条命令构建整合版两端（这是整合层的主入口）
#
#   bash scripts/build-all.sh                 # 契约门禁 → 证书下发 → 配置下发 → 两端构建 → 清单
#   bash scripts/build-all.sh --skip-server   # 只出 APK（本机没 Go 时常用）
#   bash scripts/build-all.sh --no-verify     # 跳过契约门禁（不推荐，仅调试用）
#
# 顺序不是随意的：
#   ① 契约校验必须最先 —— 两端不一致时，构建出来的东西装到机器上只会「静默不通」；
#   ② 证书必须在客户端构建**之前**下发 —— 它会被编译成 PinnedCa 常量；
#   ③ 配置（dev.local）也必须在客户端构建之前写 —— 它决定 APK 连哪个服务端。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

SKIP_CLIENT=0; SKIP_SERVER=0; DO_VERIFY=1
for a in "$@"; do
  case "$a" in
    --skip-client) SKIP_CLIENT=1 ;;
    --skip-server) SKIP_SERVER=1 ;;
    --no-verify)   DO_VERIFY=0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done

hr() { printf '%s\n' "============================================================"; }
step() { hr; echo "▶ $*"; hr; }

DIST="$UNI/dist"
mkdir -p "$DIST"

echo "整合版 $UNIFIED_VERSION   client $CLIENT_VERSION_NAME ($CLIENT_PACKAGE)  ↔  server $SERVER_VERSION"
echo "服务端地址 $SYNC_URL    局域网端口 $LAN_PORT"

# ① 契约门禁
if [ "$DO_VERIFY" = "1" ]; then
  step "① 两端契约门禁"
  if ! python3 scripts/verify-contract.py; then
    echo >&2
    echo "✗ 契约校验未通过 —— 构建已中止。" >&2
    echo "  两端不一致时构建出来的包会在真机上「静默不通」，所以这里必须拦住。" >&2
    exit 1
  fi
  # 客户端侧另有面向用户文案门禁（守则 §12.4 第 5 项）：T-4 归因不泄露内部信息、O-3 内部标签不外泄。
  # 它守护的是「只在真机屏幕上才看得见」的违规，所以和契约校验放在同一道闸里。
  if [ -f "$UNI/../tests/ui_text_guard.sh" ]; then
    if ! bash "$UNI/../tests/ui_text_guard.sh"; then
      echo >&2
      echo "✗ 面向用户文案合规门禁未通过 —— 构建已中止（守则 T-4 / O-3）。" >&2
      exit 1
    fi
  else
    echo "（未找到 tests/ui_text_guard.sh，跳过文案门禁）"
  fi
  # 控件类型一致性（守则 §12.1「产物可安装启动」）：布局换成 Material 控件后 Java 转型未跟
  # 会 ClassCastException——只有真机点进那个页面才会暴露，所以也放进同一道闸。
  if [ -f "$UNI/../tests/ui_widget_guard.py" ]; then
    if ! python3 "$UNI/../tests/ui_widget_guard.py"; then
      echo >&2
      echo "✗ 控件类型一致性门禁未通过 —— 构建已中止（守则 §12.1）。" >&2
      exit 1
    fi
  else
    echo "（未找到 tests/ui_widget_guard.py，跳过控件门禁）"
  fi
else
  echo "（--no-verify：已跳过契约门禁）"
fi

# ② 证书 pin 下发（客户端构建前必须完成）
step "② 证书 pin 下发"
bash scripts/sync-cert.sh --check || bash scripts/sync-cert.sh

# ③ 配置下发（客户端构建前必须完成）
step "③ 客户端构建配置下发（unified.conf + local.env → client/dev.local）"
bash scripts/gen-client-env.sh

# ④ 客户端
if [ "$SKIP_CLIENT" = "0" ]; then
  step "④ 构建 Android 客户端"
  bash scripts/build-client.sh
else
  echo "（--skip-client：跳过客户端构建）"
fi

# ⑤ 服务端
if [ "$SKIP_SERVER" = "0" ]; then
  step "⑤ 构建同步服务端"
  if ! bash scripts/build-server.sh; then
    rc=$?
    if [ "$rc" = "3" ]; then
      echo "（本机无 Go 工具链 → 已跳过服务端构建；可在服务器上现编：bash deploy/deploy-server.sh）"
    else
      exit "$rc"
    fi
  fi
else
  echo "（--skip-server：跳过服务端构建）"
fi

# ⑤.5 发布面泄露告警（非阻断）：dist/ 是发布件，内置开发者密钥等于把 Key 一起发出去
if [ -f "$DIST/agentlite-unified-${UNIFIED_VERSION}-android.apk" ]; then
  step "⑤.5 发布面泄露自检"
  if python3 "$UNI/../tests/apk_readability.py" "$DIST/agentlite-unified-${UNIFIED_VERSION}-android.apk" 2>/dev/null | grep -q "发现 API Key"; then
    echo "!! 警告：本次 APK 内**内置了明文 API Key**（来自 local.env / agent-lite/dev.local 的借用）。" >&2
    echo "   仅自用测试可以接受；一旦对外分发（含竞赛提交、Releases），任何人都能 strings 提取。" >&2
    echo "   发布形态：把 agent-unified/local.env 里的 API_KEY 留空后重跑本脚本，或在 App 内手填。" >&2
  else
    echo "✓ APK 内未发现明文密钥"
  fi
fi

# ⑥ 发布清单
step "⑥ 发布清单"
python3 - "$DIST" "$UNI" <<'PY'
import hashlib, json, os, subprocess, sys, datetime, glob
dist, uni = sys.argv[1], sys.argv[2]
conf = {}
for line in open(os.path.join(uni, "unified.conf"), encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1); conf[k.strip()] = v.strip()

def git(*a):
    try:
        return subprocess.run(["git", "-C", os.path.dirname(uni)] + list(a),
                              capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return ""
def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for c in iter(lambda: fh.read(65536), b""):
            h.update(c)
    return h.hexdigest()

artifacts = []
SKIP = {"SHA256SUMS", "RELEASE.json"}
for p in sorted(glob.glob(os.path.join(dist, "*"))):
    if (os.path.basename(p) in SKIP or p.endswith((".sha256", ".json", ".txt", ".tar.gz"))
            or os.path.isdir(p)):
        continue
    artifacts.append({
        "file": os.path.basename(p),
        "bytes": os.path.getsize(p),
        "sha256": sha256_file(p),
    })

lock_path = os.path.join(uni, "sources.lock.json")
manifest = {
    "unified_version": conf.get("UNIFIED_VERSION"),
    "built_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "repo_head": git("rev-parse", "HEAD"),
    "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
    "components": {
        "client": {
            "package": conf.get("CLIENT_PACKAGE"),
            "version_name": conf.get("CLIENT_VERSION_NAME"),
            "version_code": conf.get("CLIENT_VERSION_CODE"),
            "source": "agent-lite（原样副本，整合层未改写）",
        },
        "server": {
            "version": conf.get("SERVER_VERSION"),
            "source": "agentlite-server（原样副本，整合层未改写）",
        },
    },
    "endpoint": {
        "sync_url": conf.get("SYNC_URL"),
        "server_host": conf.get("SERVER_HOST"),
        "server_port": conf.get("SERVER_PORT"),
        "lan_port": conf.get("LAN_PORT"),
    },
    "upstream_snapshot": json.load(open(lock_path, encoding="utf-8")) if os.path.exists(lock_path) else None,
    "artifacts": artifacts,
    "notes": [
        "两端均为原项目逐字节副本：整合层只做配置/证书下发与契约校验，不修改功能与逻辑。",
        "APK 内嵌的证书 pin 必须与服务端 tls/server.crt 一致；换证书必须重新打包 APK。",
        "服务端 linux/amd64 二进制与服务器上现编等价（同为 CGO_ENABLED=0 + -trimpath + -s -w）。",
    ],
}
out = os.path.join(dist, "RELEASE.json")
json.dump(manifest, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(out, "a", encoding="utf-8").write("\n")

with open(os.path.join(dist, "SHA256SUMS"), "w", encoding="utf-8") as f:
    for a in artifacts:
        f.write("%s  %s\n" % (a["sha256"], a["file"]))
print("RELEASE.json：%d 个产物" % len(artifacts))
for a in artifacts:
    print("  %-58s %8.2f MB" % (a["file"], a["bytes"] / 1048576))
PY

hr
echo "✓ 整合版构建完成   dist/"
ls -1 "$DIST" | sed 's/^/    /'
hr
echo "发布件打包：bash scripts/release.sh"
echo "本地端到端：bash scripts/e2e-local.sh"
echo "远程部署  ：bash deploy/deploy-server.sh"
