#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
STRICT=0

if [ "${1:-}" = "--strict" ]; then
    STRICT=1
elif [ "$#" -ne 0 ]; then
    echo "usage: $0 [--strict]" >&2
    exit 2
fi

missing_sdk=0
for api in 34 35 36; do
    jar="$SDK_ROOT/platforms/android-$api/android.jar"
    if [ -f "$jar" ]; then
        printf 'sdk_api_%s=installed\n' "$api"
    else
        printf 'sdk_api_%s=missing\n' "$api"
        missing_sdk=1
    fi
done

for required in \
    "$ROOT/android/bridge/AndroidManifest.xml" \
    "$ROOT/src/platform/aosp/aosp_surface_capture.cpp" \
    "$ROOT/src/platform/aosp/bridge_input_injector.cpp" \
    "$ROOT/docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md"; do
    test -s "$required"
done

rg -q 'Android 14/15/16' \
    "$ROOT/docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md"
rg -q 'ScreenshotClient::captureDisplay\(display_ids\.front\(\), listener\)' \
    "$ROOT/src/platform/aosp/aosp_surface_capture.cpp"
rg -q 'platform_apis:[[:space:]]*true' "$ROOT/Android.bp"
rg -q 'foregroundServiceType="microphone"|microphone FGS' \
    "$ROOT/docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md"

printf 'portable_min_api=34\n'
printf 'portable_max_api=36\n'
printf 'private_aosp_adapter=exact-tree-required\n'

if [ "$STRICT" -eq 0 ]; then
    printf 'exact_tree_check=not-run-use---strict\n'
    if [ "$missing_sdk" -ne 0 ]; then
        echo "compatibility note: install missing SDK platforms before the full API matrix build" >&2
    fi
    exit 0
fi

if [ "$missing_sdk" -ne 0 ]; then
    echo "strict compatibility check requires SDK platforms 34, 35, and 36" >&2
    exit 1
fi

for api in 34 35 36; do
    case "$api" in
        34) tree="${AOSP_TREE_34:-}" ;;
        35) tree="${AOSP_TREE_35:-}" ;;
        36) tree="${AOSP_TREE_36:-}" ;;
    esac
    if [ -z "$tree" ] || [ ! -d "$tree/frameworks/base" ] || \
        [ ! -d "$tree/frameworks/native" ] || \
        [ ! -d "$tree/system/sepolicy" ]; then
        echo "strict compatibility check requires AOSP_TREE_$api" >&2
        exit 1
    fi

    rg -q 'class PhoneWindowManager' \
        "$tree/frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java"
    rg -q 'class SurfaceControl' \
        "$tree/frameworks/base/core/java/android/view/SurfaceControl.java"
    rg -q 'injectInputEvent' \
        "$tree/frameworks/base/core/java/android/hardware/input" \
        "$tree/frameworks/base/services/core/java/com/android/server/input"
    rg -q 'captureDisplay|ScreenshotClient' \
        "$tree/frameworks/native/libs/gui" \
        "$tree/frameworks/native/services/surfaceflinger"
    rg -q 'neverallow|typeattribute' "$tree/system/sepolicy"

    printf 'aosp_api_%s=source-entrypoints-present\n' "$api"
done
printf 'exact_tree_check=passed\n'
