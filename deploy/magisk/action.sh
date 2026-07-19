#!/system/bin/sh
set -eu

MODDIR="${0%/*}"
DATA="/data/misc/global_agent"
BIN="$MODDIR/bin/global-agentd"

mkdir -p "$DATA"
chown 0:0 "$DATA"
chmod 0700 "$DATA"

echo "Global Agent portable-core smoke test"
echo "This does not capture the screen or inject device input."
exec "$BIN" --state "$DATA/debug-state.bin" \
    --iterations 4 --interval-ms 5 --demo-action
