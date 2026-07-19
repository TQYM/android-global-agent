#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK="$SDK_ROOT/ndk/26.1.10909125"
CMAKE="$SDK_ROOT/cmake/3.22.1/bin/cmake"
NINJA="$SDK_ROOT/cmake/3.22.1/bin/ninja"

"$CMAKE" -S "$ROOT" -B "$ROOT/build/android-arm64" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-34 \
    -DANDROID_STL=c++_static \
    -DGLOBAL_AGENT_BUILD_TESTS=OFF \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo
"$CMAKE" --build "$ROOT/build/android-arm64" -j 4
