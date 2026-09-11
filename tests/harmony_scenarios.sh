#!/bin/bash
# 鸿蒙 mock 场景矩阵：无真机/模拟器时验证 harmony_agent 的四类关键路径
# 场景与判定：
#   bt           打开蓝牙        → 期望 done 且设备状态 bt_on=true（点击命中开关）
#   text_input   搜索（需输入）   → 期望 done 且 opened=true（中文失败后英文降级成功）
#   swipe_needed 打开屏外应用     → 期望 done 且 opened=true（滑动后命中目标）
#   not_found    打开不存在的目标 → 期望 fail（如实归因，禁止幻觉 done）
# 用法: sh tests/harmony_scenarios.sh [场景名...]（默认全部）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY_FILE="$ROOT/agent-lite/dev.local"
[ -f "$KEY_FILE" ] || { echo "缺少 $KEY_FILE"; exit 2; }
KEY=$(grep -E "^(API_KEY|DASHSCOPE_API_KEY|key)=" "$KEY_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' \r')
[ -n "$KEY" ] || { echo "dev.local 无 key"; exit 2; }

export HDC="$ROOT/tools/harmony-agent/fake_hdc.py"
export AGENT_API_KEY="$KEY"
export AGENT_BASE_URL="${AGENT_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"

run_case() {   # $1=场景 $2=任务 $3=期望(done|fail) $4=状态字段 $5=期望值
  local sc="$1" task="$2" want="$3" field="$4" wantval="$5"
  rm -f /tmp/fake_hdc_state.json
  local out
  out=$(FAKE_SCENARIO="$sc" python3 "$ROOT/tools/harmony-agent/harmony_agent.py" \
        --task "$task" --serial FAKE01 --max-steps 10 2>&1) || true
  local got
  got=$(echo "$out" | grep -oE "RESULT: (done|fail)" | awk '{print $2}')
  local state=""
  if [ -n "$field" ]; then
    state=$(python3 -c "import json;print(json.load(open('/tmp/fake_hdc_state.json')).get('$field'))" 2>/dev/null || echo "?")
  fi
  local ok=1
  [ "$got" = "$want" ] || ok=0
  if [ -n "$field" ] && [ "$state" != "$wantval" ]; then ok=0; fi
  if [ "$ok" = "1" ]; then
    echo "✅ $sc: $got${state:+ / $field=$state}（${task}）"
    return 0
  else
    echo "❌ $sc: got=$got want=$want${field:+ | $field=$state want=$wantval}"
    echo "$out" | tail -6 | sed 's/^/     /'
    return 1
  fi
}

echo "=== 鸿蒙 mock 场景矩阵 ==="
pass=0; fail=0
if [ $# -gt 0 ]; then SCENARIOS="$*"; else SCENARIOS="bt text_input swipe_needed not_found"; fi
for sc in $SCENARIOS; do
  case "$sc" in
    bt)           run_case bt "打开设置里的蓝牙并开启" done bt_on True    && pass=$((pass+1)) || fail=$((fail+1)) ;;
    text_input)   run_case text_input "在设置里搜索蓝牙" done opened True  && pass=$((pass+1)) || fail=$((fail+1)) ;;
    swipe_needed) run_case swipe_needed "打开目标应用" done opened True    && pass=$((pass+1)) || fail=$((fail+1)) ;;
    not_found)    run_case not_found "打开蓝牙设置" fail "" ""              && pass=$((pass+1)) || fail=$((fail+1)) ;;
    *) echo "未知场景: $sc" ;;
  esac
done
echo "=== 通过 $pass / 失败 $fail ==="
[ "$fail" = "0" ]
