#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TREE_INPUT="${1:-$ROOT/aosp-android-15}"
TREE="$(CDPATH= cd -- "$TREE_INPUT" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/26.1.10909125}"
OUT="$ROOT/build/exact-aosp15-boundary"

for required in \
    .repo/manifest.xml \
    build/make/envsetup.sh \
    build/soong/soong_ui.bash \
    frameworks/base/Android.bp \
    frameworks/native/libs/gui/include/gui/SurfaceComposerClient.h \
    frameworks/native/libs/binder/ndk/include_platform/android/binder_ibinder_platform.h \
    system/sepolicy/Android.bp; do
    test -s "$TREE/$required" || {
        echo "incomplete AOSP 15 tree: missing $required" >&2
        exit 1
    }
done

tag="$(git -C "$TREE/build/make" tag --points-at HEAD | \
    sed -n '/^android-15\.0\.0_r[0-9][0-9]*$/p' | head -1)"
test -n "$tag" || {
    echo "build/make HEAD is not an android-15.0.0_r* tag" >&2
    exit 1
}

rg -q 'captureDisplay\(DisplayId, const gui::CaptureArgs&' \
    "$TREE/frameworks/native/libs/gui/include/gui/SurfaceComposerClient.h"
rg -q 'AIBinder_setRequestingSid' \
    "$TREE/frameworks/native/libs/binder/ndk/include_platform/android/binder_ibinder_platform.h"
rg -q 'AIBinder_getCallingSid' \
    "$TREE/frameworks/native/libs/binder/ndk/include_platform/android/binder_ibinder_platform.h"

"$ROOT/tools/check-aidl.sh"

CLANG=""
for candidate in "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++; do
    if test -x "$candidate"; then
        CLANG="$candidate"
        break
    fi
done
test -n "$CLANG" || {
    echo "Android NDK clang++ not found under $NDK" >&2
    exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT"

compile() {
    source_file="$1"
    output_file="$2"
    "$CLANG" \
        --target=aarch64-none-linux-android34 \
        --sysroot="$(dirname "$(dirname "$CLANG")")/sysroot" \
        -std=c++20 \
        -Wall -Wextra -Werror -Wpedantic \
        -I"$TREE/frameworks/native/libs/binder/ndk/include_platform" \
        -I"$TREE/system/logging/liblog/include" \
        -I"$ROOT/include" \
        -I"$ROOT/src/platform/aosp" \
        -I"$ROOT/build/aidl-ndk/include" \
        -c "$ROOT/$source_file" \
        -o "$OUT/$output_file"
}

compile src/platform/aosp/agent_binder_service.cpp agent_binder_service.o
compile src/platform/aosp/agent_binder_registration.cpp agent_binder_registration.o
compile src/platform/aosp/v2_platform_agent_service.cpp v2_platform_agent_service.o
compile src/platform/aosp/v2_platform_agent_registration.cpp v2_platform_agent_registration.o

printf 'aosp_tree=%s\n' "$TREE"
printf 'aosp_tag=%s\n' "$tag"
printf 'binder_calling_sid=exact-tree-compiled\n'
printf 'capture_signature=android15-capture-args-present\n'
