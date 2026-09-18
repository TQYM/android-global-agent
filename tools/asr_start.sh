#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# asr_start.sh — 一键启动局域网离线语音转写服务（配合 agent-lite 的「独立 ASR 端点」）。
#
#   sh tools/asr_start.sh          # 启动（已在跑则提示）
#   sh tools/asr_start.sh stop     # 停止
#   sh tools/asr_start.sh status   # 看状态 + 本机局域网地址
#
# 为什么需要：语音输入不再依赖 LLM 上游（DeepSeek 没有音频能力），而是走本机 mlx-whisper。
# Mac 重启后服务不会自己回来，用这个脚本一条命令拉起；手机端的 ASR 端点填脚本打印的地址。
set -eu

VENV="${ASR_VENV:-/tmp/asr-venv}"
MODEL="${ASR_MODEL:-$HOME/.cache/asr-models/whisper-medium-mlx-4bit}"
PORT="${ASR_PORT:-18888}"
LOG="${ASR_LOG:-/tmp/asr_server.log}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"

lan_ip() {
  ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1"
}

case "${1:-start}" in
  stop)
    pkill -f asr_server.py 2>/dev/null && echo "已停止 ASR 服务" || echo "ASR 服务本来就没在跑"
    ;;
  status)
    if curl -s -m 3 "localhost:$PORT/health" >/dev/null 2>&1; then
      echo "运行中：$(curl -s -m 3 "localhost:$PORT/health")"
    else
      echo "未运行"
    fi
    echo "手机端 ASR 端点应填：http://$(lan_ip):$PORT/v1"
    ;;
  start)
    if curl -s -m 3 "localhost:$PORT/health" >/dev/null 2>&1; then
      echo "已在运行：$(curl -s -m 3 "localhost:$PORT/health")"
    else
      if [ ! -x "$VENV/bin/python" ]; then
        echo "✗ 缺少运行环境，请先执行：" >&2
        echo "    uv venv $VENV --python 3.12 && uv pip install --python $VENV/bin/python mlx-whisper" >&2
        exit 1
      fi
      if [ ! -f "$MODEL/config.json" ]; then
        echo "✗ 缺少模型：$MODEL（应含 config.json 与 weights.npz）" >&2
        exit 1
      fi
      # HF_ENDPOINT：国内直连 huggingface.co 会超时，换镜像；HF_HUB_DISABLE_XET 避免卡在 xet 传输
      HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}" HF_HUB_DISABLE_XET=1 \
      ASR_MODEL="$MODEL" ASR_LANG="${ASR_LANG:-zh}" ASR_PORT="$PORT" \
        nohup "$VENV/bin/python" "$DIR/tools/asr_server.py" > "$LOG" 2>&1 &
      echo "启动中（模型加载约 3-5 秒）…"
      sleep 6
      curl -s -m 5 "localhost:$PORT/health" || { echo "启动失败，见 $LOG"; exit 1; }
    fi
    echo
    echo "手机端 ASR 端点：http://$(lan_ip):$PORT/v1   （模型名随意，服务会忽略）"
    echo "自检：curl -F \"file=@某段16k单声道wav\" http://$(lan_ip):$PORT/v1/audio/transcriptions"
    ;;
  *)
    echo "用法：sh tools/asr_start.sh [start|stop|status]" >&2
    exit 2
    ;;
esac
