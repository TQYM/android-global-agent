#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
PKG="com.example.globalagent.gateway"
AUTHORITY="$PKG.config"

test -x "$ADB" || {
    echo "adb not found: $ADB" >&2
    exit 1
}
if test -z "$SERIAL"; then
    SERIAL="$($ADB devices | sed -n '2s/[[:space:]].*//p')"
fi
test -n "$SERIAL" || {
    echo "no ADB device available" >&2
    exit 1
}

APK="$("$ROOT/tools/build-model-gateway-debug-apk.sh")"
PROBE_APK="$("$ROOT/tools/build-model-gateway-probe-apk.sh")"
"$ADB" -s "$SERIAL" wait-for-device
SDK="$($ADB -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
test "$SDK" -ge 34 || {
    echo "API 34 or newer is required, found $SDK" >&2
    exit 1
}
"$ADB" -s "$SERIAL" root >/dev/null
"$ADB" -s "$SERIAL" wait-for-device
ENFORCING="$($ADB -s "$SERIAL" shell getenforce | tr -d '\r')"
test "$ENFORCING" = "Enforcing" || {
    echo "SELinux Enforcing is required, found $ENFORCING" >&2
    exit 1
}
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
"$ADB" -s "$SERIAL" install -r "$PROBE_APK" >/dev/null

PACKAGE_DUMP="$($ADB -s "$SERIAL" shell dumpsys package "$PKG" | tr -d '\r')"
printf '%s\n' "$PACKAGE_DUMP" | \
    rg -q 'android.permission.INTERNET: granted=true'
REQUESTED="$(printf '%s\n' "$PACKAGE_DUMP" | \
    sed -n '/requested permissions:/,/install permissions:/p' | \
    sed -n 's/^[[:space:]]*\(android\.permission\.[A-Z_]*\)$/\1/p')"
test "$REQUESTED" = "android.permission.INTERNET" || {
    echo "gateway must request only INTERNET, found: $REQUESTED" >&2
    exit 1
}
for permission in INJECT_EVENTS READ_FRAME_BUFFER CAPTURE_VIDEO_OUTPUT; do
    if printf '%s\n' "$PACKAGE_DUMP" | rg -q "android.permission.$permission"; then
        echo "gateway unexpectedly requests android.permission.$permission" >&2
        exit 1
    fi
done

CONFIG='{"schemaVersion":2,"runtime":"openclaw-host","dryRun":true,"providers":{"openai-primary":{"kind":"openai-responses","apiBase":"https://api.openai.com/v1","credentialRef":"keystore://global_agent_openai","model":"gpt-5.6-sol","reasoningEffort":"low","visionDetail":"low"}},"agents":{"planner":{"provider":"openai-primary","timeoutMs":900},"verifier":{"provider":"openai-primary","timeoutMs":600,"enabled":false}},"privacy":{"sendImage":"ask-once-per-session","redactNotifications":true,"redactKeyboard":true,"allowPackages":["com.android.settings"],"retainScreenshots":false},"limits":{"maxActionsPerPlan":8,"maxOutputTokens":1200,"maxImageLongEdge":1280,"maxRetries":1,"maxRequestsPerMinute":12,"dailyTokenBudget":200000,"endToEndDeadlineMs":2000},"tools":["observe_screen","tap","verify"]}'
CONFIG="$(cat "$ROOT/config/agent-config.deepseek-v4.ehviewer.json")"
ENCODED="$(printf '%s' "$CONFIG" | base64 | tr -d '\n')"
RESULT="$($ADB -s "$SERIAL" shell content call \
    --uri "content://$AUTHORITY" \
    --method import_public_config \
    --extra "config_b64:s:$ENCODED" | tr -d '\r')"
printf '%s\n' "$RESULT" | rg -q 'status=ok'
printf '%s\n' "$RESULT" | rg -q 'schema_version=2'
printf '%s\n' "$RESULT" | rg -q 'provider_count=1'

BAD_METHOD="$($ADB -s "$SERIAL" shell content call \
    --uri "content://$AUTHORITY" \
    --method read_public_config \
    --extra "config_b64:s:$ENCODED" 2>&1 || true)"
printf '%s\n' "$BAD_METHOD" | rg -q 'unsupported public config method'

MALFORMED="$(printf '%s' '{}' | base64 | tr -d '\n')"
BAD_CONFIG="$($ADB -s "$SERIAL" shell content call \
    --uri "content://$AUTHORITY" \
    --method import_public_config \
    --extra "config_b64:s:$MALFORMED" 2>&1 || true)"
printf '%s\n' "$BAD_CONFIG" | rg -q 'invalid-public-config:missing-field'

BIND_PROBE="$($ADB -s "$SERIAL" shell am start -W \
    -a com.example.globalagent.gatewayprobe.BIND \
    -n com.example.globalagent.gatewayprobe/.GatewayBindProbeActivity | \
    tr -d '\r')"
printf '%s\n' "$BIND_PROBE" | rg -q 'Status: ok'
PROBE_RESULT="$($ADB -s "$SERIAL" shell cat \
    /data/user/0/com.example.globalagent.gatewayprobe/files/bind-result.txt | \
    tr -d '\r')"
case "$PROBE_RESULT" in
    security-rejected|bind-failed) ;;
    *) echo "unprivileged probe unexpectedly bound: $PROBE_RESULT" >&2; exit 1 ;;
esac

UID_LINE="$($ADB -s "$SERIAL" shell cmd package list packages -U "$PKG" | \
    sed -n 's/.* uid://p' | head -n 1 | tr -d '\r')"
if test -z "$UID_LINE" || test "$UID_LINE" -eq 1000; then
    echo "gateway must have an independent non-system UID" >&2
    exit 1
fi
DOMAIN="$($ADB -s "$SERIAL" shell ps -AZ | \
    sed -n "/$PKG/p" | head -n 1 | awk '{print $1}')"
case "$DOMAIN" in
    u:r:untrusted_app*|u:r:platform_app*) ;;
    *) echo "unexpected gateway SELinux domain: ${DOMAIN:-missing}" >&2; exit 1 ;;
esac

STORED="$($ADB -s "$SERIAL" shell cat \
    "/data/user/0/$PKG/files/agent-config-v2.json" | tr -d '\r')"
test "$STORED" = "$CONFIG"

printf 'serial=%s\napi=%s\nselinux=%s\nuid=%s\ndomain=%s\nunauthorized_bind=%s\nconfig_import=passed\n' \
    "$SERIAL" "$SDK" "$ENFORCING" "$UID_LINE" "$DOMAIN" "$PROBE_RESULT"
