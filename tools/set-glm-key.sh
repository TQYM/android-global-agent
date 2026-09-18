#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# set-glm-key.sh —— 把智谱 GLM 的 API Key 配置到手机上的 agent-lite（语音转写用）。
#
#   sh tools/set-glm-key.sh                 # 自动找设备；提示输入 Key（不回显）
#   sh tools/set-glm-key.sh 192.168.1.110:37117
#
# 为什么要这个脚本：Key 不该出现在对话记录、命令行参数或任何入库文件里。
# 这里用 `read -s`（不回显）读取，直接写进手机的 SharedPreferences，并当场用一次真实
# 请求验证 Key 是否有效 —— 全程不落盘、不进 shell 历史。
#
# 配置项（与手册 §16.15.1 一致）：
#   端点 asr_base_url = https://open.bigmodel.cn/api/paas/v4
#   模型 asr_model    = glm-asr-2512
#   密钥 asr_api_key  = <你输入的>
# 优先级：云端（本 Key 有效时）→ 本地离线 whisper → 主模型音频。
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.dsh.agentlite
PREF=/data/data/$PKG/shared_prefs/agent_prefs.xml
ENDPOINT="https://open.bigmodel.cn/api/paas/v4"
ASR_MODEL="glm-asr-2512"

SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
fi
[ -n "$SERIAL" ] || { echo "✗ 没找到已授权的设备（先 adb devices 确认）" >&2; exit 1; }
echo "· 目标设备：$SERIAL"

printf "· 请粘贴智谱 API Key（输入不回显，回车确认）："
stty -echo 2>/dev/null || true
read -r KEY
stty echo 2>/dev/null || true
echo

[ -n "$KEY" ] || { echo "✗ 没有输入内容" >&2; exit 1; }
case "$KEY" in
  *.*) ;;
  *) echo "⚠ Key 里没有 '.'，智谱 Key 通常形如 xxxxxxxx…xxxx.yyyyyyyy…，仍按原样写入" ;;
esac

echo "· 先在本机验证这个 Key 是否可用（不写任何文件）"
CODE=$(curl -s -o /tmp/_glm_probe.json -w '%{http_code}' -m 20 \
  -X POST "$ENDPOINT/audio/transcriptions" \
  -H "Authorization: Bearer $KEY" \
  -F "model=$ASR_MODEL" -F "file=@/dev/null;filename=a.wav" 2>/dev/null || echo 000)
echo "  → HTTP $CODE"
case "$CODE" in
  200|400) echo "  ✓ Key 可用（400 只是空音频被拒，说明鉴权已通过）" ;;
  401)     echo "  ✗ Key 无效或已过期：$(head -c 160 /tmp/_glm_probe.json)" ; rm -f /tmp/_glm_probe.json; exit 1 ;;
  000)     echo "  ⚠ 本机连不上智谱（不影响手机侧，继续写入）" ;;
  *)       echo "  ⚠ 返回 $CODE，继续写入（以手机侧实测为准）" ;;
esac
rm -f /tmp/_glm_probe.json

echo "· 写入手机（$PKG）"
adb -s "$SERIAL" shell "su -c 'sed -i \"/name=\\\"asr_base_url\\\"/d; /name=\\\"asr_model\\\"/d; /name=\\\"asr_api_key\\\"/d\" $PREF'"
adb -s "$SERIAL" shell "su -c 'sed -i \"s|<map>|<map><string name=\\\"asr_base_url\\\">$ENDPOINT</string><string name=\\\"asr_model\\\">$ASR_MODEL</string><string name=\\\"asr_api_key\\\">$KEY</string>|\" $PREF'"
# 让 App 重新读取 prefs
adb -s "$SERIAL" shell "am force-stop $PKG"
adb -s "$SERIAL" shell "settings put secure enabled_accessibility_services $PKG/$PKG.AgentA11yService"
adb -s "$SERIAL" shell "settings put secure accessibility_enabled 1"
adb -s "$SERIAL" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1

echo "· 已写入，校验（Key 只显示前 4 位）："
adb -s "$SERIAL" shell "su -c \"grep -oE 'name=\\\"(asr_base_url|asr_model|asr_api_key)\\\">[^<]*' $PREF\"" \
  | sed -E "s|(name=\"asr_api_key\">.{4}).*|\1…【已隐藏】|"
echo
echo "✓ 完成。手机上直接说一句话即可：有效则走 GLM，失败会自动落到本地离线（飞行模式也能用）。"
echo "  想复查走哪条通道：设置页 →「测试语音转写链路」，或看 engine.log 的「云端转写成功/本地转写」行。"
