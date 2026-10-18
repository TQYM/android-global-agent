#!/usr/bin/env bash
# adb-wifi.sh — 有线引导 → 无线常驻连接（OnePlus/Android 通用）
#
# 背景：本机有活动的 Network Extension（蓝猫VPN），会拦截「第三方进程」直连局域网设备
#       （adb 报 `failed to connect ...: No route to host`，python/node 同样 EHOSTUNREACH），
#       而系统自带 nc/curl/bash/perl 可正常直连。故走本地 perl 中继：adb 连 127.0.0.1，
#       中继再落到手机的 WiFi 地址，链路本身仍然走 WiFi。
#
# 用法:
#   ./adb-wifi.sh              # 建立/恢复无线连接（自动探测 USB 设备做 tcpip）
#   ./adb-wifi.sh --watch      # 建立后常驻守护，掉线自动重连（Ctrl-C 退出）
#   ./adb-wifi.sh --stop       # 断开无线连接并停止中继
#
# 可覆盖的环境变量:
#   DEV_PORT=5555      手机侧 adbd TCP 端口
#   LOCAL_PORT=5599    本机中继监听端口
#   DEV_IP=192.168.1.106  手机 WiFi 地址（无 USB 时使用）
#   SERIAL=<序列号>    指定 USB 设备（默认自动探测）
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELAY="$SELF_DIR/adbrelay.pl"
PERL_BIN="/usr/bin/perl"
DEV_PORT="${DEV_PORT:-5555}"
LOCAL_PORT="${LOCAL_PORT:-5599}"
LOCAL_ADDR="127.0.0.1:${LOCAL_PORT}"
LOG="${TMPDIR:-/tmp}/adbrelay.${LOCAL_PORT}.log"
IPFILE="${TMPDIR:-/tmp}/adbrelay.${LOCAL_PORT}.ip"

# 日志一律走 stderr：resolve_ip 等函数以 stdout 返回“数据”，不能混入日志
log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }

usb_serial() { adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 !~ /:/ {print $1; exit}'; }
relay_pid()  { pgrep -f "adbrelay.pl ${LOCAL_PORT} " 2>/dev/null | head -1; }

device_ip() {   # $1 = serial
  adb -s "$1" shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -1
}

stop_relay() {
  local pids
  pids="$(pgrep -f "adbrelay.pl ${LOCAL_PORT} " 2>/dev/null)"
  if [ -n "$pids" ]; then
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null
    sleep 0.3
  fi
}

start_relay() {   # $1 = 手机 IP
  local ip="$1" pid
  pid="$(relay_pid)"
  if [ -n "$pid" ]; then
    if ps -o command= -p "$pid" 2>/dev/null | grep -q "${ip} ${DEV_PORT}"; then
      return 0
    fi
    log "目标变化，重启中继"
    stop_relay
  fi
  log "启动中继 ${LOCAL_ADDR} -> ${ip}:${DEV_PORT}"
  nohup "$PERL_BIN" "$RELAY" "$LOCAL_PORT" "$ip" "$DEV_PORT" >>"$LOG" 2>&1 &
  disown 2>/dev/null || true
  sleep 1
}

is_online() { [ "$(adb -s "$LOCAL_ADDR" get-state 2>/dev/null)" = "device" ]; }
connect_dev() { adb connect "$LOCAL_ADDR" 2>&1 | tail -1; }

resolve_ip() {
  local serial ip=""
  serial="${SERIAL:-$(usb_serial)}"
  if [ -n "$serial" ]; then
    log "USB 设备: $serial → 切换 adbd 到 TCP ${DEV_PORT}"
    adb -s "$serial" tcpip "$DEV_PORT" >/dev/null 2>&1
    sleep 2
    for _ in 1 2 3 4 5; do
      ip="$(device_ip "$serial")"
      [ -n "$ip" ] && break
      sleep 1
    done
  fi
  [ -z "$ip" ] && ip="${DEV_IP:-$(cat "$IPFILE" 2>/dev/null || true)}"
  # 只保留 IPv4 字面量，防止上游输出混入换行/提示文本
  ip="$(printf '%s' "$ip" | grep -oE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  [ -n "$ip" ] && printf '%s' "$ip" >"$IPFILE"
  printf '%s' "$ip"
}

establish() {
  local ip
  ip="$(resolve_ip)"
  if [ -z "$ip" ]; then
    log "无法确定手机 IP：请插上 USB 线，或显式指定 DEV_IP=<手机IP>"
    return 1
  fi
  printf '%s' "$ip" >"$IPFILE"
  start_relay "$ip" || return 1
  connect_dev
  sleep 1
  if is_online; then
    log "无线连接就绪：adb -s ${LOCAL_ADDR} …（手机 ${ip}:${DEV_PORT}，链路走 WiFi）"
    return 0
  fi
  log "无线连接未就绪：检查手机 adbd 是否在 ${ip}:${DEV_PORT} 监听 / 中继日志 ${LOG}"
  return 1
}

watch_loop() {
  log "守护模式：每 5s 检查，掉线自动重连（Ctrl-C 退出）"
  while true; do
    if ! is_online; then
      log "检测到掉线，重连…"
      connect_dev >/dev/null 2>&1
      [ -z "$(relay_pid)" ] && start_relay "$(cat "$IPFILE" 2>/dev/null)"
    fi
    sleep 5
  done
}

case "${1:-}" in
  --stop)
    adb disconnect "$LOCAL_ADDR" 2>&1 | tail -1
    stop_relay
    log "已断开 ${LOCAL_ADDR} 并停止中继"
    ;;
  --watch)
    establish && watch_loop
    ;;
  *)
    establish
    ;;
esac
