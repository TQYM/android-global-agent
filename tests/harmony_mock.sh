#!/bin/bash
# 鸿蒙版 mock 回归：无真机/模拟器时验证 harmony_agent 全链路（真实 LLM + 模拟 hdc 设备）
# 用法: sh tests/harmony_mock.sh [任务文本] [最大步数]
# 通过条件: agent 报 done 且 mock 设备状态 bt_on=true（点击真的命中了开关）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK="${1:-打开设置里的蓝牙并开启}"
STEPS="${2:-12}"
KEY_FILE="$ROOT/agent-lite/dev.local"

if [ ! -f "$KEY_FILE" ]; then echo "缺少 $KEY_FILE（内置 key）"; exit 2; fi
KEY=$(grep -E "^(API_KEY|DASHSCOPE_API_KEY|key)=" "$KEY_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' \r')
BASE=$(grep -E "^BASE_URL=|^base_url=" "$KEY_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' \r')
BASE="${BASE:-https://dashscope.aliyuncs.com/compatible-mode/v1}"
if [ -z "$KEY" ]; then echo "dev.local 未找到 key"; exit 2; fi

rm -f /tmp/fake_hdc_state.json; rm -rf /tmp/fake_hdc_dev
export HDC="$ROOT/tools/harmony-agent/fake_hdc.py"
export AGENT_API_KEY="$KEY" AGENT_BASE_URL="$BASE"

echo "=== 鸿蒙 mock 回归：$TASK ==="
OUT=$(python3 "$ROOT/tools/harmony-agent/harmony_agent.py" --task "$TASK" --serial FAKE01 --max-steps "$STEPS" 2>&1)
echo "$OUT" | tail -12

if ! echo "$OUT" | grep -q "RESULT: done"; then echo "❌ 任务未完成"; exit 1; fi
BT=$(python3 -c "import json;print(json.load(open('/tmp/fake_hdc_state.json')).get('bt_on'))" 2>/dev/null || echo missing)
if [ "$BT" != "True" ]; then echo "❌ mock 设备状态未变更（bt_on=$BT）——点击未命中开关"; exit 1; fi
echo "✅ mock 回归通过：任务完成且设备状态 bt_on=true（点击命中验证）"
