#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/26.1.10909125}"
OUT="$ROOT/build/aidl-boundary"

"$ROOT/tools/check-aidl.sh"

CLANG=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++; do
    if test -x "$candidate"; then
        CLANG="$candidate"
        break
    fi
done
if test -z "$CLANG"; then
    echo "Android NDK clang++ not found under $NDK" >&2
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

"$CLANG" \
    --target=aarch64-none-linux-android34 \
    --sysroot="$(dirname "$(dirname "$CLANG")")/sysroot" \
    -std=c++20 \
    -Wall -Wextra -Werror -Wpedantic \
    -I"$ROOT/include" \
    -I"$ROOT/src/platform/aosp" \
    -I"$ROOT/build/aidl-ndk/include" \
    -c "$ROOT/src/platform/aosp/agent_binder_service.cpp" \
    -o "$OUT/agent_binder_service.o"

echo "API 34 arm64 AIDL service boundary compiled successfully"
