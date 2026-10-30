#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# sync-sources.sh — 把两个原项目的源码**逐字节**同步进 agent-unified/。
#
#   bash scripts/sync-sources.sh           同步（原项目 → 统一目录）
#   bash scripts/sync-sources.sh --check   只校验，不改动（CI / 发布前门禁）
#
# ── 整合第一原则：不修改任何功能与逻辑 ──────────────────────────────────────
# 统一目录里的 client/ 与 server/ 是原项目的**原样副本**，同步过程只做复制，
# 不做任何改写、补丁、格式化或重命名。--check 用 rsync itemize 干跑对比，
# 任何差异（新增 / 删除 / 内容变化 / 类型变化）都会非零退出。
#
# ── 两处刻意排除（都不是「源码」，且都有专职脚本接管）──────────────────────
#   1) dev.local          客户端构建期注入的密钥文件，**不入 git**，由
#                         scripts/gen-client-env.sh 从 unified.conf + local.env 生成。
#   2) assets/agentlite_sync_ca.pem
#                         客户端内嵌的证书 pin，**真相源是服务端证书**
#                         （server/tls/server.crt），由 scripts/sync-cert.sh 下发。
#                         排除它，是为了让证书轮换只有一个入口。
#   另有 build/ build-m3/ 等构建产物被排除（可再生，且体积大）。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$UNI/.." && pwd)"
CLIENT_SRC="$ROOT/agent-lite"
SERVER_SRC="$ROOT/agentlite-server"

# 3) vendor/asr/lib/  离线语音转写的原生库（18MB 构建产物，源项目里已被
#                      .gitignore 排除、由 build-mac.sh 本机准备）。它不是源码，
#                      两端都不入库，比较时一并跳过——否则干净检出后副本会
#                      因「源没有、副本有」被判成改写。
CLIENT_EXCLUDES=(--exclude 'build/' --exclude 'build-m3/' --exclude 'dev.local'
                 --exclude 'assets/agentlite_sync_ca.pem' --exclude '.DS_Store'
                 --exclude 'vendor/asr/lib/')
SERVER_EXCLUDES=(--exclude 'agentlite-server' --exclude '.DS_Store')

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

for d in "$CLIENT_SRC" "$SERVER_SRC"; do
  [ -d "$d" ] || { echo "✗ 找不到原项目：$d" >&2; exit 1; }
done

hr() { printf '%s\n' "------------------------------------------------------------"; }

if [ "$CHECK" = "1" ]; then
  hr; echo "sync-sources --check：副本 vs 原项目（逐字节）"; hr
  fail=0; rewritten=0
  for pair in "client:$CLIENT_SRC:$UNI/client" "server:$SERVER_SRC:$UNI/server"; do
    name="${pair%%:*}"; rest="${pair#*:}"; src="${rest%%:*}"; dst="${rest#*:}"
    # 过滤 `.d..t....`（仅目录时间戳不同）：macOS 自带的 openrsync 不回写目录 mtime，
    # 这类噪声与被比较的「内容」无关；真正关心的新增 / 删除 / 内容变化都保留。
    if [ "$name" = "client" ]; then
      diff_out=$(rsync -ain --delete "${CLIENT_EXCLUDES[@]}" "$src/" "$dst/" 2>/dev/null | grep -vE '^\.d\.\.t' || true)
    else
      diff_out=$(rsync -ain --delete "${SERVER_EXCLUDES[@]}" "$src/" "$dst/" 2>/dev/null | grep -vE '^\.d\.\.t' || true)
    fi
    if [ -n "$diff_out" ]; then
      # 区分两种方向完全相反的情况，避免把「上游更新了」误报成「整合层改写了副本」
      if printf '%s' "$diff_out" | grep -qE '^\*deleting|^<f'; then
        rewritten=1
        echo "✗ $name 的副本**被改写/多出文件**（整合原则被破坏）："
        echo "    复原：bash scripts/sync-sources.sh"
      else
        echo "✗ $name 的上游已更新，副本落后（不是改写，但会让构建带上旧源码）："
        echo "    同步：bash scripts/sync-sources.sh"
      fi
      printf '%s\n' "$diff_out" | sed 's/^/    /'
      fail=1
    else
      echo "✓ $name 与原项目逐字节一致（排除 dev.local / 证书 pin / 构建产物）"
    fi
  done
  hr
  if [ "$fail" = "0" ]; then
    echo "结论：统一目录未对任何一端做过改写 ✓"
  elif [ "$rewritten" = "1" ]; then
    echo "结论：副本被改写 —— 整合原则被破坏（复原：bash scripts/sync-sources.sh）✗" >&2
    exit 1
  else
    echo "结论：副本本身忠实（未改写），只是上游已前进 —— 同步后重试构建 ✗" >&2
    exit 1
  fi
  exit 0
fi

hr; echo "sync-sources：原项目 → agent-unified/{client,server}"; hr
echo "  agent-lite/         → client/"
echo "  agentlite-server/   → server/"
echo

rsync -a --delete "${CLIENT_EXCLUDES[@]}" "$CLIENT_SRC/" "$UNI/client/"
rsync -a --delete "${SERVER_EXCLUDES[@]}" "$SERVER_SRC/" "$UNI/server/"

echo "✓ 已同步"
echo "    client 文件数：$(find "$UNI/client" -type f | wc -l | tr -d ' ')"
echo "    server 文件数：$(find "$UNI/server" -type f | wc -l | tr -d ' ')"

# 记录来源快照，供发布清单溯源（上游有一次未提交改动也如实记录）
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  head_sha=$(git -C "$ROOT" rev-parse HEAD)
  branch=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)
  dirty_c=$(git -C "$ROOT" status --porcelain -- agent-lite | wc -l | tr -d ' ')
  dirty_s=$(git -C "$ROOT" status --porcelain -- agentlite-server | wc -l | tr -d ' ')
  python3 - "$UNI/sources.lock.json" "$head_sha" "$branch" "$dirty_c" "$dirty_s" <<'PY'
import json, sys, datetime
path, sha, branch, dc, ds = sys.argv[1:6]
lock = {
    "note": "由 scripts/sync-sources.sh 生成：记录本次同步的上游来源。",
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "repo_head": sha,
    "branch": branch,
    "upstream": {
        "agent-lite": {"path": "agent-lite", "uncommitted_entries": int(dc)},
        "agentlite-server": {"path": "agentlite-server", "uncommitted_entries": int(ds)},
    },
}
json.dump(lock, open(path, "w"), ensure_ascii=False, indent=2)
open(path, "a").write("\n")
print("✓ 写入 sources.lock.json（HEAD %s，未提交条目 client=%s server=%s）" % (sha[:8], dc, ds))
PY
else
  echo "（非 git 检出，跳过 sources.lock.json）"
fi
hr
echo "下一步：bash scripts/build-all.sh"
