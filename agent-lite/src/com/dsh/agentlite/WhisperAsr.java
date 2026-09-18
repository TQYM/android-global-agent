// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 手机本地**离线**语音转写（whisper.cpp，代码见 {@code agent-lite/jni/whisper_jni.cpp}）。
 *
 * <p><b>为什么要有它</b>（2026-09-18 的两次返工）：
 * <ol>
 *   <li>语音原先复用「主模型原生音频输入」，上游换成 DeepSeek 后该能力消失
 *       （{@code input_audio} 被 400 拒绝、{@code /audio/transcriptions} 404）；</li>
 *   <li>改走 Mac 上的局域网 whisper 服务后，Mac 一关机/换网/换 IP，语音立刻失效——
 *       用户明确要求「不要依赖 Mac」，而且这正是真实踩到的故障（Mac 换了网络，手机与它不通）。</li>
 * </ol>
 * 所以这里把 whisper.cpp 直接编进 APK：**完全离线、不依赖任何外部设备、不需要 root**。
 * 唯一的联网动作是**首次下载模型**（约 180MB，之后永久离线可用）。
 *
 * <p><b>模型</b>：默认 {@code ggml-small-q5_1}（中文识别质量与体积的平衡点，实测手机端
 * 识别 10 秒音频在 2~4 秒量级）。下载源优先用项目自己的服务器，失败再退回 hf-mirror。
 */
public final class WhisperAsr {

    private static final String TAG = "AgentAsr";

    /** 默认模型文件名（放 getExternalFilesDir 下，卸载即清）。 */
    public static final String MODEL_FILE = "ggml-small-q5_1.bin";

    /** 模型下载源（按顺序尝试）：先项目服务器，再国内镜像。 */
    private static final String[] MODEL_URLS = {
            "https://8.133.225.105:18443/asr/" + MODEL_FILE,
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/" + MODEL_FILE,
    };

    static {
        try {
            System.loadLibrary("whisper");   // 依赖 libggml.so / libggml-base.so / libggml-cpu.so
            System.loadLibrary("agentasr");
            sLibOk = true;
        } catch (Throwable t) {
            // 同时写 engine.log：排查时 logcat 缓冲经常已经滚掉，而 engine.log 是落盘的
            Log.w(TAG, "本地转写库加载失败: " + t);
            try {
                AgentEngine.staticLog("离线转写库加载失败: " + t);
            } catch (Throwable ignored) {
            }
            sLibOk = false;
        }
    }

    private static boolean sLibOk;

    private long ctx;
    private String loadedPath = "";

    /** 转写库是否可用（ABI 不匹配或未打包 .so 时为 false，调用方应回退到在线通道）。 */
    public static boolean available() {
        return sLibOk;
    }

    public boolean isReady() {
        return ctx != 0;
    }

    /** 模型文件（可能尚未下载）。 */
    public static File modelFile(android.content.Context ctx) {
        return new File(ctx.getExternalFilesDir(null), MODEL_FILE);
    }

    public static boolean modelReady(android.content.Context ctx) {
        File f = modelFile(ctx);
        return f.isFile() && f.length() > 50L * 1024 * 1024;   // 小于 50MB 视为没下完
    }

    /**
     * 确保模型存在（不存在则下载）。**这是唯一的联网步骤**，只需一次。
     *
     * @param onProgress 进度回调（0~100，-1 表示未知总长）
     * @return 模型文件；失败返回 null
     */
    public static File ensureModel(android.content.Context ctx, Progress onProgress) {
        File dst = modelFile(ctx);
        if (modelReady(ctx)) return dst;
        File tmp = new File(dst.getAbsolutePath() + ".part");
        for (String url : MODEL_URLS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                TlsPin.apply(c);
                long total = c.getContentLength();
                int code = c.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "模型下载 " + url + " → HTTP " + code);
                    continue;
                }
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        if (onProgress != null) {
                            onProgress.onProgress(total > 0 ? (int) (got * 100 / total) : -1, got, total);
                        }
                    }
                }
                if (tmp.length() > 50L * 1024 * 1024 && tmp.renameTo(dst)) {
                    Log.i(TAG, "模型就绪: " + dst + " (" + dst.length() / 1048576 + "MB)");
                    return dst;
                }
            } catch (Throwable t) {
                Log.w(TAG, "模型下载失败(" + url + "): " + t);
            } finally {
                if (c != null) c.disconnect();
                if (tmp.exists() && !tmp.renameTo(dst)) tmp.delete();
            }
        }
        return null;
    }

    public interface Progress {
        void onProgress(int percent, long got, long total);
    }

    /** 加载模型（首次 1~3 秒，之后复用同一 context）。 */
    public synchronized boolean load(android.content.Context ctx) {
        if (!sLibOk) return false;
        File f = modelFile(ctx);
        if (!f.isFile()) return false;
        if (ctx != null && this.ctx != 0 && f.getAbsolutePath().equals(loadedPath)) return true;
        release();
        long h = nativeInit(f.getAbsolutePath());
        if (h != 0) {
            this.ctx = h;
            loadedPath = f.getAbsolutePath();
        }
        return h != 0;
    }

    /** 16kHz 单声道 float PCM（[-1,1]）→ 文本；未加载返回空串。 */
    public synchronized String transcribe(float[] pcm) {
        if (ctx == 0 || pcm == null || pcm.length == 0) return "";
        long t0 = System.currentTimeMillis();
        String text = nativeTranscribe(ctx, pcm);
        AgentEngine.staticLog("本地转写: " + String.format("%.1fs 音频 → %.1fs：%s",
                pcm.length / 16000.0, (System.currentTimeMillis() - t0) / 1000.0,
                text.length() > 40 ? text.substring(0, 40) + "…" : text));
        return text == null ? "" : text.trim();
    }

    public synchronized void release() {
        if (ctx != 0) {
            nativeFree(ctx);
            ctx = 0;
            loadedPath = "";
        }
    }

    /**
     * WAV（VoiceRecorder 录的正是 16k/单声道/PCM16）→ float PCM。
     * 只解标准的 PCM16，遇到其它格式返回空数组（宁可不识别也不要给错数据）。
     */
    public static float[] wavToPcm(byte[] wav) {
        if (wav == null || wav.length < 44) return new float[0];
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952) return new float[0];            // "RIFF"
        int pos = 12;
        int channels = 1, bits = 16, rate = 16000, dataOff = -1, dataLen = 0;
        while (pos + 8 <= wav.length) {
            int id = b.getInt(pos);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id == 0x20746d66) {                                     // "fmt "
                channels = b.getShort(body + 2) & 0xFFFF;
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14) & 0xFFFF;
            } else if (id == 0x61746164) {                               // "data"
                dataOff = body;
                dataLen = Math.min(size, wav.length - body);
                break;
            }
            pos = body + size + (size & 1);
        }
        if (dataOff < 0 || bits != 16 || dataLen <= 0) return new float[0];
        int frames = dataLen / 2 / Math.max(channels, 1);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += b.getShort(dataOff + (i * channels + c) * 2);
            }
            out[i] = (sum / (float) channels) / 32768f;
        }
        if (rate != 16000 && frames > 0) {                              // 线性重采样到 16k
            int n = (int) (frames * 16000L / rate);
            float[] rs = new float[Math.max(n, 1)];
            for (int i = 0; i < rs.length; i++) {
                float src = i * (float) rate / 16000f;
                int i0 = (int) src;
                int i1 = Math.min(i0 + 1, frames - 1);
                float frac = src - i0;
                rs[i] = out[Math.min(i0, frames - 1)] * (1 - frac) + out[i1] * frac;
            }
            return rs;
        }
        return out;
    }

    private static native long nativeInit(String modelPath);

    private static native String nativeTranscribe(long handle, float[] pcm);

    private static native void nativeFree(long handle);
}
