#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
CMAKE="$SDK_ROOT/cmake/3.22.1/bin/cmake"
NINJA="$SDK_ROOT/cmake/3.22.1/bin/ninja"

"$CMAKE" -S "$ROOT" -B "$ROOT/build/host" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_BUILD_TYPE=Debug \
    -DGLOBAL_AGENT_ENABLE_SANITIZERS=ON
"$CMAKE" --build "$ROOT/build/host" -j 4
"$CMAKE" --build "$ROOT/build/host" --target test
"$ROOT/tools/build-aidl-boundary-stub.sh"
"$ROOT/tools/check-project.sh"
