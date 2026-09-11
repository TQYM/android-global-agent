#!/bin/bash
# 异形屏适配真机回归矩阵（规划书 §6 标准任务集 + 形态专项）
# 用法: sh tests/plan-display-matrix.sh [oneplus|tablet|all]
# 前置: 设备 USB 已连接并开启开发者调试。
#
# 阶段 A: 装机 + 无障碍绑定 + 形态核对 + 坐标校准
# 阶段 B: 标准任务集（harness 驱动，结果写入 tests/results-<dev>.jsonl）
# 阶段 C: 平板专项（旋转 + 分屏，人工观察 engine.log / 设备形态卡）
set -e
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
APK="agent-lite/build/agent-lite.apk"
PKG="com.dsh.agentlite"
SVC="$PKG/com.dsh.agentlite.AgentA11yService"
TARGET="${1:-all}"

ONE="3B15B401NPF00000"      # 一加 13T（中置挖孔，root）
TAB="AAQLBB5C03000706"      # 荣耀 MagicPad3 Pro（横屏平板，无 root）

ensure_a11y() {   # $1=serial  荣耀/MagicOS 重装后绑定会被清空
  $ADB -s "$1" shell settings put secure enabled_accessibility_services "$SVC" 2>/dev/null || true
  $ADB -s "$1" shell settings put secure accessibility_enabled 1 2>/dev/null || true
  sleep 2
  cur=$($ADB -s "$1" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
  echo "[$1] a11y = $cur"
}

phase_a() {   # $1=serial $2=label
  echo "=== [$2] 阶段 A: 装机 + 形态核对 + 校准 ==="
  $ADB -s "$1" install -r "$APK" | tail -1
  ensure_a11y "$1"
  $ADB -s "$1" shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 4
  # 形态卡：读 tvDisplay 文本
  $ADB -s "$1" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" \
    | python3 -c "
import re,sys
xml=sys.stdin.read()
m=re.search(r'resource-id=\"com.dsh.agentlite:id/tvDisplay\"[^>]*?text=\"([^\"]*)\"', xml)
print('设备形态卡:'); print((m.group(1).replace('\\\\n','\n') if m else '未找到 tvDisplay'))
" 
  # 校准按钮：dump 定位后点击，再读形态卡下半部分
  $ADB -s "$1" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" \
    | python3 -c "
import re,sys
xml=sys.stdin.read()
m=re.search(r'resource-id=\"com.dsh.agentlite:id/btnDisplayCalib\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', xml)
if m:
    x1,y1,x2,y2=map(int,m.groups()); print(f'{(x1+x2)//2} {(y1+y2)//2}')
" > /tmp/calib_xy.txt
  if [ -s /tmp/calib_xy.txt ]; then
    read cx cy < /tmp/calib_xy.txt
    $ADB -s "$1" shell input tap "$cx" "$cy"
    sleep 3
    $ADB -s "$1" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" \
      | python3 -c "
import re,sys
xml=sys.stdin.read()
m=re.search(r'resource-id=\"com.dsh.agentlite:id/tvDisplay\"[^>]*?text=\"([^\"]*)\"', xml)
print('校准结果:'); print((m.group(1).replace('\\\\n','\n') if m else '未找到'))
"
  else
    echo "[$2] 校准按钮未定位（配置区可能未展开，点「配置」后再跑一次）"
  fi
}

phase_b() {   # $1=dev key  $2=round no
  echo "=== [$1] 阶段 B: 标准任务集 R$2 ==="
  python3 tests/agent_test.py "$1" "$2" \
    "open settings" "open bilibili" "open gallery" "open calculator" \
    "open browser" "open wechat" "open bluetooth settings page" \
    "turn up the volume once"
}

phase_c_tablet() {   # 平板专项：旋转 + 分屏（人工观察）
  echo "=== [tablet] 阶段 C: 旋转/分屏专项 ==="
  $ADB -s "$TAB" shell wm user-rotation lock 1; sleep 2
  echo "[tablet] 已锁横屏，跑一条任务（期望日志出现形态指纹变化/正常完成）"
  python3 tests/agent_test.py tablet 91 "open settings"
  $ADB -s "$TAB" shell wm user-rotation lock 0; sleep 2
  echo "[tablet] 分屏：设置 + 浏览器上下分屏后跑任务（期望感知注入【分屏/小窗】）"
  $ADB -s "$TAB" shell am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 2
  $ADB -s "$TAB" shell am start -n "$PKG/.MainActivity" --windowingMode 4 >/dev/null 2>&1 || echo "(分屏启动失败则手动分屏)"
  echo "[tablet] 分屏已布置，人工观察设备形态卡 activeWinBounds 注入（跑任务时看 engine.log/提示词）"
}

case "$TARGET" in
  oneplus) phase_a "$ONE" "oneplus";  phase_b oneplus 1 ;;
  tablet)  phase_a "$TAB" "tablet";   phase_b tablet 1; phase_c_tablet ;;
  all)     phase_a "$ONE" "oneplus";  phase_b oneplus 1
           phase_a "$TAB" "tablet";   phase_b tablet 1; phase_c_tablet ;;
  *) echo "用法: sh tests/plan-display-matrix.sh [oneplus|tablet|all]"; exit 1 ;;
esac
echo "=== 完成。结果: tests/results-<dev>.jsonl ==="
