#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
OUTPUT="${1:-$HOME/Desktop/GlobalAgent-KernelSU-v0.4.0-arm64-debug.zip}"
BINARY="$ROOT/build/android-arm64/global-agentd"
SOURCE="$ROOT/deploy/magisk"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/global-agent-kernelsu.XXXXXX")"

cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT INT TERM

if [ ! -x "$BINARY" ]; then
    echo "missing Android arm64 stub; run tools/build-android-stub.sh" >&2
    exit 1
fi

mkdir -p "$STAGE/bin"
cp "$SOURCE/module.prop" "$STAGE/module.prop"
cp "$SOURCE/customize.sh" "$STAGE/customize.sh"
cp "$SOURCE/post-fs-data.sh" "$STAGE/post-fs-data.sh"
cp "$SOURCE/action.sh" "$STAGE/action.sh"
cp "$SOURCE/README.md" "$STAGE/README.md"
cp -R "$SOURCE/webroot" "$STAGE/webroot"
cp "$BINARY" "$STAGE/bin/global-agentd"
chmod 0755 "$STAGE/customize.sh" "$STAGE/post-fs-data.sh" \
    "$STAGE/action.sh" "$STAGE/bin/global-agentd"

mkdir -p "$(dirname -- "$OUTPUT")"
rm -f "$OUTPUT"
(cd "$STAGE" && zip -q -r -X "$OUTPUT" .)
unzip -t "$OUTPUT" >/dev/null

printf 'package=%s\n' "$OUTPUT"
printf 'sha256=%s\n' "$(shasum -a 256 "$OUTPUT" | awk '{print $1}')"
