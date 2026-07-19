#!/system/bin/sh
set -eu

MODDIR="${0%/*}"
DATA="/data/misc/global_agent"
BIN="$MODDIR/bin/global-agentd"

set_context_if_known() {
    # A Magisk staging environment may run before the product policy is loaded.
    chcon "$1" "$2" 2>/dev/null || true
}

mkdir -p "$DATA"
chown 0:0 "$DATA"
chmod 0700 "$DATA"
set_context_if_known u:object_r:global_agent_data_file:s0 "$DATA"

if [ -f "$BIN" ]; then
    chown 0:0 "$BIN"
    chmod 0755 "$BIN"
    set_context_if_known u:object_r:agentd_exec:s0 "$BIN"
fi
