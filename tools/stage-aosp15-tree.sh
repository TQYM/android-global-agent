#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TREE_INPUT="${1:-$ROOT/aosp-android-15}"
TREE="$(CDPATH= cd -- "$TREE_INPUT" && pwd)"
DEST="$TREE/system_ext/global_agent"

test -s "$TREE/build/make/envsetup.sh" || {
    echo "not an AOSP source tree: $TREE" >&2
    exit 1
}

mkdir -p "$DEST"
touch "$DEST/.global-agent-staging-root"

rsync -a --delete "$ROOT/android/" "$DEST/android/"
rsync -a --delete "$ROOT/include/" "$DEST/include/"
rsync -a --delete "$ROOT/src/" "$DEST/src/"
mkdir -p "$DEST/config"
rsync -a "$ROOT/config/agent-config.deepseek-v4.ehviewer.json" \
    "$DEST/config/"
rsync -a "$ROOT/Android.bp" "$ROOT/LICENSE" \
    "$ROOT/config/global_agent_product.mk" "$DEST/"

mkdir -p "$TREE/device/global_agent"
rsync -a --delete "$ROOT/aosp15/device/global_agent/" \
    "$TREE/device/global_agent/"

printf 'staged=%s\n' "$DEST"
printf 'product_fragment=%s\n' "$DEST/global_agent_product.mk"
printf 'lunch_target=aosp_global_agent_arm64_phone-trunk_staging-userdebug\n'
printf 'next=build on x86_64 Linux\n'
