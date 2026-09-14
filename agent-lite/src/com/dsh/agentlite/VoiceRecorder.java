// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/** 录音 → 16kHz 单声道 PCM16 WAV（AudioRecord 直出，原生可靠）。 */
public class VoiceRecorder {

    private static final int SR = 16000;
    /**
     * 录音硬上限（手册 §15.2-10）：30 秒。
     *
     * <p>16 kHz 单声道 16 bit = 32 KB/s，30 秒约 960 KB（WAV 再 +44 字节头），
     * 正好压在 ASR 接口 8 MB 与内存可承受的范围内。没有上限时，用户忘记点
     * 结束（或按钮被系统拦截）会一直往 ByteArrayOutputStream 里堆，直到 OOM。
     */
    private static final int MAX_PCM_BYTES = SR * 2 * 30;
    /** 达到上限时的回调（UI 据此提示"已自动结束"），可为 null。 */
    private Runnable onAutoStop;
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean recording;
    private ByteArrayOutputStream pcm;

    /** 注册「达到上限自动结束」的回调。 */
    public void setOnAutoStop(Runnable r) { onAutoStop = r; }

    public synchronized boolean start() {
        if (recording) return false;
        int min = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) return false;
        pcm = new ByteArrayOutputStream();
        recording = true;
        rec.startRecording();
        thread = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) {
                    // 上限保护：超了就停止采集并通知 UI，而不是无限堆内存
                    if (pcm.size() + n > MAX_PCM_BYTES) {
                        int room = MAX_PCM_BYTES - pcm.size();
                        if (room > 0) pcm.write(buf, 0, room);
                        recording = false;
                        Runnable cb = onAutoStop;
                        if (cb != null) {
                            try { cb.run(); } catch (Exception ignored) { }
                        }
                        break;
                    }
                    pcm.write(buf, 0, n);
                }
            }
        }, "voice-rec");
        thread.start();
        return true;
    }

    /** 停止并返回完整 WAV 字节。 */
    public synchronized byte[] stopToWav() {
        recording = false;
        if (rec == null) return null;
        try { thread.join(500); } catch (InterruptedException ignored) { }
        try { rec.stop(); } catch (Exception ignored) { }
        rec.release();
        rec = null;
        byte[] data = pcm.toByteArray();
        return wrapWav(data);
    }

    public boolean isRecording() { return recording; }

    private static byte[] wrapWav(byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        writeStr(out, "RIFF");
        writeInt(out, 36 + pcm.length);
        writeStr(out, "WAVE");
        writeStr(out, "fmt ");
        writeInt(out, 16);          // PCM chunk size
        writeShort(out, 1);         // PCM format
        writeShort(out, 1);         // mono
        writeInt(out, SR);
        writeInt(out, SR * 2);      // byte rate
        writeShort(out, 2);         // block align
        writeShort(out, 16);        // bits
        writeStr(out, "data");
        writeInt(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) {
        for (char c : s.toCharArray()) o.write((byte) c);
    }
    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }
    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }
}
