#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

for script in \
    "$ROOT/tools/run-tests.sh" \
    "$ROOT/tools/check-aidl.sh" \
    "$ROOT/tools/build-android-stub.sh" \
    "$ROOT/tools/push-debug-stub.sh" \
    "$ROOT/deploy/magisk/post-fs-data.sh"; do
    sh -n "$script"
    test -x "$script"
done

xmllint --noout "$ROOT/android/bridge/AndroidManifest.xml"
xmllint --noout "$ROOT/android/bridge/privapp-permissions-com.example.globalagent.xml"

if rg -n \
    -g '!build/**' -g '!outputs/**' \
    -g '*.cpp' -g '*.h' -g '*.java' -g '*.rc' -g '*.te' -g '*.sh' \
    -g '*.aidl' -g 'Android.bp' -g '*.xml' -g '!**/check-project.sh' \
    'setenforce[[:space:]]+0|captureSecureLayers[[:space:]]*=[[:space:]]*true|allow[[:space:]]+system_app[^\n]*uinput|chcon[^\n]*uinput_device|typeattribute[[:space:]]+global_agent_bridge[[:space:]]+coredomain|sk-[A-Za-z0-9]{16,}' \
    "$ROOT"; then
    echo "unsafe policy, secure capture, or credential pattern found" >&2
    exit 1
fi

CAPTURE_SOURCE="$ROOT/src/platform/aosp/aosp_surface_capture.cpp"
if rg -n 'DisplayCaptureArgs|SurfaceComposerClient::captureDisplay' \
    "$CAPTURE_SOURCE"; then
    echo "capture adapter uses the wrong Android 14 permission path" >&2
    exit 1
fi
if ! rg -q 'ScreenshotClient::captureDisplay\(display_ids\.front\(\), listener\)' \
    "$CAPTURE_SOURCE"; then
    echo "root-authorized DisplayId capture path is missing" >&2
    exit 1
fi
