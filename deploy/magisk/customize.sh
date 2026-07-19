#!/system/bin/sh

SKIPUNZIP=0

ui_print "- Global Agent portable-core debug helper"

if [ "$ARCH" != "arm64" ]; then
    abort "! This package supports arm64 devices only"
fi

if [ "$API" -lt 34 ]; then
    abort "! Android 14 / API 34 or newer is required"
fi

if [ "$API" -gt 34 ]; then
    ui_print "! Built for API 34; newer releases are smoke-test only"
fi

set_perm "$MODPATH/bin/global-agentd" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
