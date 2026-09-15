#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
#
# watch_e2e.sh — 手表真机接入 + 登录同账号 + 跨设备双向验证（一键）
#
#   bash tests/watch_e2e.sh
#
# 前提：手表已用 USB/底座连上电脑并开启 ADB 调试（`adb devices` 能看到 OWW261 之类的手表）。
# 手机与手表必须登录**同一账号**——脚本会把手表登录到当前手机所用的账号
# （账号名从手机 prefs 读取；口令需通过环境变量传入，脚本不打印、不落盘）：
#
#   WATCH_KEY='账号密钥' bash tests/watch_e2e.sh
#
# 为什么单列这个脚本：2026-09-15 实测跨设备链路已通（手机↔同账号设备双向），
# 但"真手表执行"这一半缺的只是**手表在线并登录同账号**。把手表侧的准备与验证固化下来，
# 下次手表一插上就能直接验收，不用重新摸索小屏上的登录路径与坐标。
set -u
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
PHONE="${PHONE:-3B15B401NPF00000}"
KEY="${WATCH_KEY:-}"
APK="${APK:-$(cd "$(dirname "$0")/.." && pwd)/agent-lite/build/agent-lite.apk}"

hr() { printf '%s\n' "------------------------------------------------------------"; }
hr; echo "手表真机双向验证"; hr

# 1) 找手表：排除手机序列号后剩下的第一个设备
WATCH=""
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
  [ "$s" = "$PHONE" ] && continue
  WATCH="$s"; break
done
if [ -z "$WATCH" ]; then
  echo "✗ 没找到手表。请确认："
  echo "  1) 手表已放到充电底座/接上 USB；"
  echo "  2) 手表「开发者选项 → USB 调试」已打开；"
  echo "  3) adb devices 能看到它（当前只看到：$(adb devices | awk 'NR>1{printf "%s ", $1}')）"
  exit 2
fi
echo "手表设备: $WATCH ($(adb -s "$WATCH" shell getprop ro.product.model 2>/dev/null | tr -d '\r'))"

# 2) 装最新 APK + 开无障碍（adb 写 secure settings 是标准做法，无需 root）
adb -s "$WATCH" install -r "$APK" 2>&1 | tail -1
adb -s "$WATCH" shell settings put secure enabled_accessibility_services \
    com.dsh.agentlite/com.dsh.agentlite.AgentA11yService
adb -s "$WATCH" shell settings put secure accessibility_enabled 1
sleep 2
echo "无障碍: $(adb -s "$WATCH" shell settings get secure enabled_accessibility_services | tr -d '\r')"

# 3) 登录同账号（账号名与手机一致；口令由调用方通过 WATCH_KEY 提供）
NAME=$(adb -s "$PHONE" shell "su -c \"grep -oE 'name=\\\"account_name\\\">[^<]*' /data/data/com.dsh.agentlite/shared_prefs/agent_prefs.xml\"" 2>/dev/null | sed 's/.*>//' | tr -d '\r')
echo "目标账号: ${NAME:-（读取失败）}"
if [ -z "$KEY" ]; then
  echo "⚠ 未提供 WATCH_KEY，跳出手表登录（其余步骤仍会跑）。"
else
  adb -s "$WATCH" shell am force-stop com.dsh.agentlite; sleep 1
  adb -s "$WATCH" shell am start -n com.dsh.agentlite/.MainActivity >/dev/null 2>&1; sleep 4
  # 设置 → 账号入口 → 登录页（小屏需要先滚动）
  adb -s "$WATCH" shell input tap 362 136; sleep 2.5
  for i in 1 2 3; do adb -s "$WATCH" shell input swipe 233 400 233 120 250; sleep 0.8; done
  A=$(adb -s "$WATCH" shell uiautomator dump /sdcard/_w.xml >/dev/null 2>&1; adb -s "$WATCH" shell cat /sdcard/_w.xml | python3 -c "
import sys,re
for n in re.findall(r'<node[^>]*>', sys.stdin.read()):
    if 'btnAccount' in n:
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n); x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2)")
  [ -n "$A" ] && adb -s "$WATCH" shell input tap $A && sleep 3
  adb -s "$WATCH" shell input swipe 233 380 233 120 250; sleep 1.2
  # 用**实时坐标**填表（小屏滚动后坐标会变，写死坐标会串位——实测教训）
  read -r NX NY KX KY BX BY <<<"$(adb -s "$WATCH" shell uiautomator dump /sdcard/_w2.xml >/dev/null 2>&1; adb -s "$WATCH" shell cat /sdcard/_w2.xml | python3 -c "
import sys,re
pos={}
for n in re.findall(r'<node[^>]*>', sys.stdin.read()):
    rid=re.search(r'resource-id=\"([^\"]*)\"',n).group(1).split('/')[-1]
    if rid in ('etName','etKey','btnLogin'):
        b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n); x1,y1,x2,y2=map(int,b.groups())
        pos[rid]=f'{(x1+x2)//2} {(y1+y2)//2}'
print(pos.get('etName',''), pos.get('etKey',''), pos.get('btnLogin',''))")"
  if [ -n "${NX:-}" ]; then
    adb -s "$WATCH" shell input tap $NX $NY; sleep 0.8
    adb -s "$WATCH" shell input keyevent 123; for i in $(seq 1 20); do adb -s "$WATCH" shell input keyevent 67; done
    adb -s "$WATCH" shell input text "$NAME"; sleep 0.5
    adb -s "$WATCH" shell input tap $KX $KY; sleep 0.8
    adb -s "$WATCH" shell input text "$KEY"; sleep 0.5
    adb -s "$WATCH" shell input keyevent 111; sleep 0.8
    adb -s "$WATCH" shell input tap $BX $BY; sleep 5
    echo "手表登录已提交"
  else
    echo "⚠ 没找到手表登录页输入框，需人工确认坐标"
  fi
fi

# 4) 双向验证：手机 → 手表（手机下发 remote，手表侧应领取）
echo
echo "请现在在手机上跑一条跨设备任务（例如「让手表打开计算器」），"
echo "或在电脑上用服务端 API 以手机身份下发；脚本随后核对手表侧是否领取并执行："
sleep 25
adb -s "$WATCH" logcat -d 2>/dev/null | grep -E "AgentLiteRemote|跨设备" | tail -5
echo
echo "手表侧最近日志（应出现「开始执行跨设备指令」与终态回报）："
adb -s "$WATCH" shell "cat /sdcard/Android/data/com.dsh.agentlite/files/engine.log" 2>/dev/null | tail -6
hr
