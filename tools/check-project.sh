#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

for script in \
    "$ROOT/tools/run-tests.sh" \
    "$ROOT/tools/check-aidl.sh" \
    "$ROOT/tools/check-api-compat.sh" \
    "$ROOT/tools/check-java-api-matrix.sh" \
    "$ROOT/tools/build-aidl-boundary-stub.sh" \
    "$ROOT/tools/build-android-stub.sh" \
    "$ROOT/tools/build-model-gateway-debug-apk.sh" \
    "$ROOT/tools/build-model-gateway-probe-apk.sh" \
    "$ROOT/tools/check-aosp15-exact-tree.sh" \
    "$ROOT/tools/stage-aosp15-tree.sh" \
    "$ROOT/tools/build-global-agent-aosp15.sh" \
    "$ROOT/tools/verify-model-gateway-avd.sh" \
    "$ROOT/tools/package-kernelsu.sh" \
    "$ROOT/tools/validation-metadata.sh" \
    "$ROOT/tools/push-debug-stub.sh" \
    "$ROOT/deploy/magisk/customize.sh" \
    "$ROOT/deploy/magisk/post-fs-data.sh" \
    "$ROOT/deploy/magisk/action.sh"; do
    sh -n "$script"
    test -x "$script"
done

"$ROOT/tools/check-api-compat.sh"
"$ROOT/tools/check-java-api-matrix.sh"
if [ -n "${AOSP_TREE_35:-}" ]; then
    "$ROOT/tools/check-aosp15-exact-tree.sh" "$AOSP_TREE_35"
fi

xmllint --noout "$ROOT/android/bridge/AndroidManifest.xml"
xmllint --noout "$ROOT/android/bridge/privapp-permissions-com.example.globalagent.xml"
xmllint --noout "$ROOT/android/model-gateway/AndroidManifest.xml"
xmllint --noout "$ROOT/android/model-gateway/res/xml/network_security_config.xml"

if rg -n 'INJECT_EVENTS|READ_FRAME_BUFFER|CAPTURE_VIDEO_OUTPUT' \
    "$ROOT/android/model-gateway/AndroidManifest.xml"; then
    echo "ModelGateway manifest must not request privileged capture/input permissions" >&2
    exit 1
fi
if rg -n 'android.permission.INTERNET' "$ROOT/android/bridge/AndroidManifest.xml"; then
    echo "bridge manifest must remain offline" >&2
    exit 1
fi
if sed -n '/name: "GlobalAgentModelGateway"/,/^}/p' "$ROOT/Android.bp" | \
    rg -n 'certificate:[[:space:]]*"platform"|privileged:[[:space:]]*true'; then
    echo "ModelGateway must not use the platform certificate or privileged install" >&2
    exit 1
fi
if ! rg -q 'android:permission="com.example.globalagent.permission.OPEN_MODEL_SESSION"' \
    "$ROOT/android/model-gateway/AndroidManifest.xml"; then
    echo "ModelGateway v2 service must require the bridge signature permission" >&2
    exit 1
fi

test -s "$ROOT/PROJECT_PROGRESS.md"
test -s "$ROOT/PROJECT_LOG.md"
test -s "$ROOT/PROJECT_ISSUES.md"
test -s "$ROOT/docs/MODEL_API_GATEWAY.md"
test -f "$ROOT/android/aidl/com/example/globalagent/v2/CaptureGrant.aidl"
test -f "$ROOT/android/aidl/com/example/globalagent/v2/PerceptionEnvelope.aidl"
test -f "$ROOT/android/aidl/com/example/globalagent/v2/ActionPlan.aidl"
if ! rg -q 'const int PROTOCOL_VERSION = 2;' \
    "$ROOT/android/aidl/com/example/globalagent/v2/IV2GlobalAgent.aidl"; then
    echo "protocol v2 AIDL version contract is missing" >&2
    exit 1
fi
rg -q '^type global_agent_v2_service, service_manager_type;' \
    "$ROOT/android/sepolicy/types.te"
rg -q '^add_service\(agentd, global_agent_v2_service\)' \
    "$ROOT/android/sepolicy/agentd.te"
rg -q '^allow global_agent_bridge global_agent_v2_service:service_manager find;' \
    "$ROOT/android/sepolicy/global_agent_bridge.te"
rg -Fqx 'neverallow { domain -global_agent_bridge } global_agent_v2_service:service_manager find;' \
    "$ROOT/android/sepolicy/global_agent_bridge.te"
v2_find_rule_count="$(rg -n \
    '^allow [^ ]+ global_agent_v2_service:service_manager find;' \
    "$ROOT/android/sepolicy" -g '*.te' | wc -l | tr -d ' ')"
if test "$v2_find_rule_count" != "1"; then
    echo "global_agent_v2 service lookup must be granted only to the bridge" >&2
    exit 1
fi
rg -q '^global_agent_v2[[:space:]]+u:object_r:global_agent_v2_service:s0$' \
    "$ROOT/android/sepolicy/service_contexts"
test -f "$ROOT/deploy/magisk/webroot/index.html"
test -f "$ROOT/deploy/magisk/webroot/styles.css"
test -f "$ROOT/deploy/magisk/webroot/app.js"
if command -v node >/dev/null 2>&1; then
    node --check "$ROOT/deploy/magisk/webroot/app.js"
fi
if rg -n 'https?://|<script[^>]+src="https?://' \
    "$ROOT/deploy/magisk/webroot/index.html" \
    "$ROOT/deploy/magisk/webroot/styles.css" \
    "$ROOT/deploy/magisk/webroot/app.js"; then
    echo "KernelSU WebUI must remain fully offline" >&2
    exit 1
fi

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
