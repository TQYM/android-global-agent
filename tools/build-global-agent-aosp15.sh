#!/bin/sh
set -eu

if [ "$(uname -s)" != "Linux" ]; then
    echo "AOSP 15 target Soong build requires an x86_64 Linux host or VM" >&2
    exit 2
fi
if [ "$(uname -m)" != "x86_64" ]; then
    echo "this AOSP checkout contains x86_64 Linux host prebuilts" >&2
    exit 2
fi
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 <aosp-tree> <lunch-target> [out-dir]" >&2
    exit 2
fi

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TREE="$(CDPATH= cd -- "$1" && pwd)"
LUNCH_TARGET="$2"
if [ "$#" -eq 3 ]; then
    export OUT_DIR="$3"
fi

"$ROOT/tools/stage-aosp15-tree.sh" "$TREE"
cd "$TREE"
. build/envsetup.sh
lunch "$LUNCH_TARGET"
m global-agentd GlobalAgentBridge GlobalAgentModelGateway \
    privapp-permissions-com.example.globalagent
