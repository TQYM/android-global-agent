#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# build-client.sh — 构建 Android 客户端（产物收进 dist/）
#
#   bash scripts/build-client.sh
#
# 本脚本**不重新实现**客户端构建：它只做三件事——下发证书 pin、
# 调客户端自己的 client/build-mac.sh（一行未改）、对产物做硬断言后收进 dist/。
#
# 两项硬断言来自服务端 README §8.1 的实盘教训：
#   1. **构建新鲜度**：源码比 APK 新 → 说明这次构建没把你的改动物化，直接失败；
#   2. **内容真在包里**：APK 字节数会巧合重复、完全不可信，必须解出 dex 后 grep 标记。
set -euo pipefail

UNI="$(cd "$(dirname "$0")/.." && pwd)"
cd "$UNI"

set -a
# shellcheck disable=SC1090
. "$UNI/unified.conf"
set +a

CLIENT="$UNI/client"
DIST="$UNI/dist"
OUT="$DIST/agentlite-unified-${UNIFIED_VERSION}-android.apk"

mkdir -p "$DIST"

echo "== 1/3 证书 pin 下发（构建期要把它编成 PinnedCa）"
bash scripts/sync-cert.sh --check || bash scripts/sync-cert.sh

echo
echo "== 2/3 调用客户端原生构建脚本 client/build-mac.sh（未改动一行）"
( cd "$CLIENT" && sh build-mac.sh )

APK="$CLIENT/build/agent-lite.apk"
[ -f "$APK" ] || { echo "✗ 客户端构建未产出 $APK" >&2; exit 1; }

echo
echo "== 3/3 产物硬断言"

# 4.1 构建新鲜度：源码/资源/清单不得比 APK 更新（README §8.1）
NEWER=$(find "$CLIENT/src" "$CLIENT/res" "$CLIENT/AndroidManifest.xml" "$CLIENT/assets" \
        -newer "$APK" -type f 2>/dev/null | head -5 || true)
if [ -n "$NEWER" ]; then
  echo "✗ 有源文件比 APK 新 —— 这次构建没有覆盖全部改动：" >&2
  printf '    %s\n' $NEWER >&2
  exit 1
fi
echo "   ✓ 新鲜度：src/res/manifest/assets 均不晚于 APK"

# 4.2 内容真在包里：解 dex 后 grep 类描述符（APK 字节数不可信）
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$APK" 'classes*.dex' -d "$TMP"
DEX_COUNT=$(ls "$TMP"/classes*.dex 2>/dev/null | wc -l | tr -d ' ')
MARKER=$(cat "$TMP"/classes*.dex | grep -ac "com/dsh/agentlite/AgentEngine" || true)
if [ "$DEX_COUNT" -lt 1 ] || [ "$MARKER" -lt 1 ]; then
  echo "✗ dex 内容断言失败：dex 数=$DEX_COUNT，AgentEngine 类描述符命中=$MARKER" >&2
  exit 1
fi
echo "   ✓ 内容：$DEX_COUNT 个 dex，AgentEngine 类描述符在包内命中 $MARKER 次"

# 4.3 版本一致性：APK 内的 versionName/versionCode 必须与 unified.conf 的映射一致
BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)
if [ -x "$BT/aapt2" ]; then
  BADGING=$("$BT/aapt2" dump badging "$APK" 2>/dev/null || true)
  VN=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  PKG=$(printf '%s\n' "$BADGING" | sed -n "s/package: name='\([^']*\)'.*/\1/p" | head -1)
  VC=$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)
  if [ "$PKG" != "$CLIENT_PACKAGE" ] || [ "$VN" != "$CLIENT_VERSION_NAME" ] || [ "$VC" != "$CLIENT_VERSION_CODE" ]; then
    echo "✗ 版本映射与 unified.conf 不符：包名=$PKG versionName=$VN versionCode=$VC" >&2
    echo "  期望：$CLIENT_PACKAGE / $CLIENT_VERSION_NAME / $CLIENT_VERSION_CODE" >&2
    exit 1
  fi
  echo "   ✓ 版本：$PKG  versionName=$VN  versionCode=$VC（与 unified.conf 映射一致）"
fi

# 4.4 配置真的进包了：内嵌同步地址的主机名必须出现在 dex 里。
#     否则 APK 会连错服务端，而症状（同步不通）要到真机上才暴露。
#     取值以 client/dev.local 为准——那才是 build-mac.sh 实际消费的值。
EFFECTIVE_SYNC="$SYNC_URL"
if [ -f "$CLIENT/dev.local" ]; then
  _dl=$(sed -n 's/^SYNC_URL=//p' "$CLIENT/dev.local" | head -1)
  [ -n "$_dl" ] && EFFECTIVE_SYNC="$_dl"
fi
HOST_EXPECT=$(printf '%s' "$EFFECTIVE_SYNC" | sed -E 's#^[a-z]+://##; s#[:/].*$##')
HOST_RE=$(printf '%s' "$HOST_EXPECT" | sed 's/\./\\./g')
HOST_HITS=$(cat "$TMP"/classes*.dex | grep -ac "$HOST_RE" || true)
if [ -z "$HOST_EXPECT" ] || [ "$HOST_HITS" -lt 1 ]; then
  echo "✗ 内嵌同步地址（$EFFECTIVE_SYNC）未出现在 dex 中：配置注入没生效" >&2
  echo "   检查：bash scripts/gen-client-env.sh 是否在构建前执行过" >&2
  exit 1
fi
echo "   ✓ 配置：内嵌同步地址主机 $HOST_EXPECT 已在 dex 中命中 $HOST_HITS 次"

cp "$APK" "$OUT"
shasum -a 256 "$OUT" | awk '{print $1}' > "$OUT.sha256"
echo
echo "DONE: $OUT"
echo "      $(stat -f%z "$OUT") bytes   sha256 $(cat "$OUT.sha256")"
