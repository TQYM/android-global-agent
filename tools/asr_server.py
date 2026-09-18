#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""asr_server.py — 局域网离线语音转写服务（OpenAI 兼容 `POST /audio/transcriptions`）。

为什么需要这个（2026-09-18 实测）：App 的语音输入原本依赖「主模型原生音频输入」（qwen-omni）。
上游切成 DeepSeek 之后，`deepseek-flash` **既不吃 `input_audio`**（400：unknown variant `input_audio`）
**也没有 `/audio/transcriptions`**（404），而设备上 `asr_model` 为空 → 语音必然失败。
ColorOS 又没有暴露系统 `RecognizerIntent` 服务（`voice_recognition_service=null`），
所以这里用 mlx-whisper 在 Mac 上本地转写：零云 key、零费用、离线可用。

手机端 `VoiceRecorder` 录的正是 **16kHz 单声道 PCM16 WAV**，与 whisper 原生要求一致，
因此本服务不依赖 ffmpeg（自己解 WAV 头即可）。

启动（首次会下载模型，约 1.6GB，缓存在 ~/.cache/huggingface）：
    uv venv /tmp/asr-venv --python 3.12 && uv pip install --python /tmp/asr-venv/bin/python mlx-whisper
    /tmp/asr-venv/bin/python tools/asr_server.py

环境变量：
    ASR_PORT   监听端口（默认 18888）
    ASR_HOST   监听地址（默认 0.0.0.0，局域网可达）
    ASR_MODEL  HuggingFace 模型（默认 mlx-community/whisper-large-v3-turbo）
    ASR_LANG   语言（默认 zh；设 auto 走自动检测）

自检：
    curl -s localhost:18888/health
    curl -s -F "file=@sample.wav" -F "model=whisper" localhost:18888/audio/transcriptions
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

PORT = int(os.environ.get("ASR_PORT", "18888"))
HOST = os.environ.get("ASR_HOST", "0.0.0.0")
MODEL = os.environ.get("ASR_MODEL", "mlx-community/whisper-large-v3-turbo")
LANG = os.environ.get("ASR_LANG", "zh")

_mlx = None
_lock = threading.Lock()
_state = {"ready": False, "loading": True, "error": "", "took_s": 0.0}


def load_model() -> None:
    """后台预热：首次调用会下载并载入模型，别把 HTTP 线程卡住。"""
    global _mlx
    t0 = time.time()
    try:
        import mlx_whisper
        _mlx = mlx_whisper
        mlx_whisper.transcribe(np.zeros(16000, dtype=np.float32),   # 1s 静音，仅用于触发加载
                               path_or_hf_repo=MODEL, language=None, verbose=False)
        _state.update(ready=True, loading=False, took_s=round(time.time() - t0, 1))
        print(f"[asr] 模型就绪：{MODEL}（{_state['took_s']}s）", flush=True)
    except Exception as e:                                        # noqa: BLE001
        _state.update(loading=False, error=f"{type(e).__name__}: {e}")
        print(f"[asr] 模型加载失败：{_state['error']}", flush=True)


def wav_to_float32(data: bytes) -> np.ndarray:
    """16k 单声道 PCM16 WAV → float32[-1,1]；其它格式做最小化归一（多声道取均值 + 线性重采样）。"""
    with wave.open(io.BytesIO(data), "rb") as w:
        ch, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(frames)
    if width != 2:
        raise ValueError(f"只支持 16bit PCM，收到 {width * 8}bit")
    a = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(axis=1)
    if rate != 16000:                                             # whisper 要求 16k
        n = int(len(a) * 16000 / rate)
        a = np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a).astype(np.float32)
    return a


def parse_multipart(body: bytes, boundary: bytes) -> dict[str, bytes]:
    """极简 multipart/form-data 解析（Python 3.13 起标准库没有 cgi 了）。"""
    out: dict[str, bytes] = {}
    for chunk in body.split(b"--" + boundary):
        if not chunk.strip() or chunk.strip() == b"--":
            continue
        head, sep, data = chunk.partition(b"\r\n\r\n")
        if not sep:
            continue
        m = re.search(rb'name="([^"]+)"', head)
        if not m:
            continue
        if data.endswith(b"\r\n"):
            data = data[:-2]
        out[m.group(1).decode("utf-8", "replace")] = data
    return out


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:                                     # noqa: N802
        if self.path.startswith("/health"):
            self._send(200, {"ok": _state["ready"], **{k: v for k, v in _state.items()},
                             "model": MODEL, "lang": LANG})
        else:
            self._send(404, {"error": {"message": "not found"}})

    def do_POST(self) -> None:                                    # noqa: N802
        # 兼容两种写法：客户端把端点配成 http://host:port 或 http://host:port/v1 都能用
        # （App 会拼成 /v1/audio/transcriptions，实测漏了这层兼容会 404）。
        path = self.path.split("?")[0].rstrip("/")
        if not path.endswith("/audio/transcriptions"):
            self._send(404, {"error": {"message": f"unknown path {self.path}"}})
            return
        if _mlx is None:
            self._send(503, {"error": {"message": "模型尚未就绪：" + (_state["error"] or "加载中")}})
            return
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r"boundary=([^;]+)", ctype)
        if not m:
            self._send(400, {"error": {"message": "需要 multipart/form-data"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        parts = parse_multipart(body, m.group(1).strip('"').encode())
        wav = parts.get("file", b"")
        if not wav:
            self._send(400, {"error": {"message": "缺少 file 字段"}})
            return
        try:
            audio = wav_to_float32(wav)
            t0 = time.time()
            with _lock:                                           # mlx 模型非线程安全，串行化
                r = _mlx.transcribe(audio, path_or_hf_repo=MODEL,
                                    language=None if LANG == "auto" else LANG,
                                    # 不加这句 whisper 对中文常输出繁体（实测「大開設置裡的無線網絡頁面」）
                                    initial_prompt="以下是普通话的句子。",
                                    temperature=0.0, condition_on_previous_text=False,
                                    verbose=False)
            text = (r.get("text") or "").strip()
            took = round(time.time() - t0, 2)
            print(f"[asr] {len(audio) / 16000:.1f}s 音频 → {took}s：{text[:60]}", flush=True)
            self._send(200, {"text": text, "took_s": took})
        except Exception as e:                                    # noqa: BLE001
            self._send(500, {"error": {"message": f"{type(e).__name__}: {e}"}})

    def log_message(self, fmt: str, *args) -> None:                # 降噪：只留我们自己的日志
        return


def main() -> int:
    print(f"[asr] 监听 {HOST}:{PORT}，模型 {MODEL}（后台加载中…）", flush=True)
    threading.Thread(target=load_model, daemon=True).start()
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
