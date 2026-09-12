#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# font_scale_sweep.sh — 字体缩放压测取样：在不同系统字体大小下把各页面截下来
#
#   bash tests/font_scale_sweep.sh [序列号] [输出目录]
#
# 为什么要压测字体缩放：本项目全部字号都用 sp（好），但**不少控件是固定高度**（layout_height=28/32/36dp）。
# 用户把系统字体调大后，文字比容器还高 → 被裁掉一半，而这在 1.0× 下完全看不出来。
# Android 用户的字体设置分布里，放大 1.15~1.5 倍很常见，2.0 倍也不罕见。
#
# 输出：<输出目录>/<scale>/<page>.png + .xml，供 font_scale_readability.py 分析。
set -uo pipefail

# 与仓库其他测试脚本一致：自己把 platform-tools 补进 PATH
command -v adb >/dev/null 2>&1 || export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

SERIAL="${1:-emulator-5554}"
OUT="${2:-/tmp/fontscale}"
APK="${3:-$(cd "$(dirname "$0")/.." && pwd)/agent-unified/dist/agentlite-unified-0.7.0-android.apk}"
ADB=(adb -s "$SERIAL")
SCALES="${SCALES:-1.0 1.15 1.3 1.5 1.8 2.0}"
PKG=com.dsh.agentlite

sh() { "${ADB[@]}" "$@"; }

tap_id() {  # tap_id <dump-file> <resource-id-suffix>
  python3 - "$1" "$2" <<'PY'
import re, sys, subprocess
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for tag in re.findall(r"<node\b[^>]*>", xml):
    a = dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
    if a.get("resource-id", "").endswith("/" + sys.argv[2]) and a.get("bounds"):
        b = re.findall(r"-?\d+", a["bounds"])
        if len(b) == 4:
            x = (int(b[0]) + int(b[2])) // 2
            y = (int(b[1]) + int(b[3])) // 2
            print("%d %d" % (x, y))
            break
PY
}

dump_ok() {  # 断言这次 dump 真的产出了内容（uiautomator 会因「未进入空闲态」失败而留下旧文件）
  sh shell rm -f "/sdcard/$1.xml" >/dev/null 2>&1
  sh shell uiautomator dump "/sdcard/$1.xml" >/dev/null 2>&1
  sh shell cat "/sdcard/$1.xml" 2>/dev/null | grep -q "<hierarchy"
}

capture() {  # capture <dir> <page>
  sh shell rm -f "/sdcard/$2.png" >/dev/null 2>&1
  sh shell screencap -p "/sdcard/$2.png" >/dev/null
  dump_ok "$2" || { echo "    （$2 的控件树 dump 失败，本档作废）"; return 1; }
  sh pull "/sdcard/$2.png" "$1/$2.png" >/dev/null 2>&1
  sh pull "/sdcard/$2.xml" "$1/$2.xml" >/dev/null 2>&1
}

echo "设备 $SERIAL  字体缩放序列：$SCALES"
echo "安装被测包：$APK"
sh install -r "$APK" 2>&1 | tail -1

for s in $SCALES; do
  dir="$OUT/$s"
  mkdir -p "$dir"
  sh shell settings put system font_scale "$s" >/dev/null
  sleep 1
  # 字体缩放生效需要 Activity 重建：直接 force-stop 后重启最稳
  sh shell am force-stop "$PKG" >/dev/null
  sh shell pm clear "$PKG" >/dev/null 2>&1     # 清掉历史账号会话，首启流程才可复现
  sleep 1
  sh shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep "${SETTLE:-6}"

  # 走到主界面为止：未登录时 App 每次启动都先落在登录页，且首启还会叠权限/说明弹窗。
  # 必须**确认真到了主界面**（出现 btnSettings）才取样，否则会抓到登录页而当成功。
  arrived=0
  for _ in $(seq 1 ${PROBE_TRIES:-10}); do
    dump_ok probe || { sleep 1; continue; }
    sh pull /sdcard/probe.xml "$dir/probe.xml" >/dev/null 2>&1
    if grep -q "/btnSettings" "$dir/probe.xml" 2>/dev/null; then arrived=1; break; fi
    if grep -q "permissioncontroller" "$dir/probe.xml" 2>/dev/null; then
      c=$(tap_id "$dir/probe.xml" "permission_allow_button"); [ -z "$c" ] && c=$(tap_id "$dir/probe.xml" "allow_button")
      [ -n "$c" ] && sh shell input tap $c >/dev/null
      sleep 1.5; continue
    fi
    if grep -q "稍后再说" "$dir/probe.xml" 2>/dev/null; then
      c=$(tap_id "$dir/probe.xml" btnSkip); [ -n "$c" ] && sh shell input tap $c >/dev/null
      sleep 3; continue
    fi
    # 「登录已失效」等历史会话弹窗：点「稍后」关掉即可
    if grep -q "登录已失效" "$dir/probe.xml" 2>/dev/null; then
      c=$(python3 - "$dir/probe.xml" <<'PY3'
import re, sys
xml=open(sys.argv[1],encoding="utf-8",errors="replace").read()
for tag in re.findall(r"<node\b[^>]*>", xml):
    a=dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
    if a.get("text","") in ("稍后","取消") and a.get("bounds"):
        b=re.findall(r"-?\d+", a["bounds"]); print("%d %d"%((int(b[0])+int(b[2]))//2,(int(b[1])+int(b[3]))//2)); break
PY3
)
      [ -n "$c" ] && sh shell input tap $c >/dev/null
      sleep 2; continue
    fi
    if grep -q "知道了" "$dir/probe.xml" 2>/dev/null; then
      c=$(python3 - "$dir/probe.xml" <<'PY2'
import re, sys
xml=open(sys.argv[1],encoding="utf-8",errors="replace").read()
for tag in re.findall(r"<node\b[^>]*>", xml):
    a=dict(re.findall(r'([\w:-]+)="([^"]*)"', tag))
    if a.get("text","")=="知道了" and a.get("bounds"):
        b=re.findall(r"-?\d+", a["bounds"]); print("%d %d"%((int(b[0])+int(b[2]))//2,(int(b[1])+int(b[3]))//2)); break
PY2
)
      [ -n "$c" ] && sh shell input tap $c >/dev/null
      sleep 1.5; continue
    fi
    sleep 2
  done
  [ "$arrived" = "1" ] || { echo "  ×$s  !! 未能到达主界面，跳过本档"; continue; }

  okcap=0
  for _ in 1 2 3; do
    if capture "$dir" main && grep -q "/btnSettings" "$dir/main.xml" 2>/dev/null; then okcap=1; break; fi
    sleep 1.5
  done
  if [ "$okcap" != "1" ]; then
    echo "  ×$s  !! 主界面取样未通过校验，跳过本档。当前页面文字："
    python3 - "$dir/probe.xml" <<'PY2' 2>/dev/null | sed 's/^/       /'
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    ts = [t for t in re.findall(r'text="([^"]+)"', xml) if t.strip()]
    print(" | ".join(ts)[:160] or "（无文字）")
except Exception as e:
    print("（探针不可读：%s）" % e)
PY2
    continue
  fi

  c=$(tap_id "$dir/main.xml" btnSettings)
  if [ -n "$c" ]; then
    sh shell input tap $c >/dev/null; sleep 2.5
    dump_ok sprobe && sh pull /sdcard/sprobe.xml "$dir/sprobe.xml" >/dev/null 2>&1
    if grep -q "账号与额度" "$dir/sprobe.xml" 2>/dev/null; then
      capture "$dir" settings
      # 再滚到底部取一张：设置页是 ScrollView，底部控件（28dp 小按钮）若只在顶部取样，
      # 会被容器裁掉可见部分，导致它们永远得不到评估。
      sh shell input swipe 540 1800 540 600 300 >/dev/null; sleep 1
      sh shell input swipe 540 1800 540 600 300 >/dev/null; sleep 1.5
      capture "$dir" settings_bottom
      echo "  ×$s  main + settings(+底部) 已取样"
    else
      echo "  ×$s  main 已取样（设置页未打开）"
    fi
  else
    echo "  ×$s  main 已取样（未找到设置入口）"
  fi
done

sh shell settings put system font_scale 1.0 >/dev/null
sh shell am force-stop "$PKG" >/dev/null
echo "已恢复 font_scale=1.0；样本在 $OUT"
