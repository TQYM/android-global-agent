#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ADB="${ADB:-adb}"
BINARY="$ROOT/build/android-arm64/global-agentd"

if [ ! -x "$BINARY" ]; then
    echo "build the Android stub first: tools/build-android-stub.sh" >&2
    exit 1
fi

"$ADB" push "$BINARY" /data/local/tmp/global-agentd
REMOTE_UID="$("$ADB" shell id -u | tr -d '\r')"
if [ "$REMOTE_UID" = "0" ]; then
    "$ADB" shell chmod 0755 /data/local/tmp/global-agentd
    "$ADB" shell /data/local/tmp/global-agentd \
        --state /data/local/tmp/global-agent-state.bin \
        --iterations 4 --demo-action
else
    "$ADB" shell su -c 'chmod 0755 /data/local/tmp/global-agentd'
    "$ADB" shell su -c '/data/local/tmp/global-agentd --state /data/local/tmp/global-agent-state.bin --iterations 4 --demo-action'
fi
