package com.dsh.agent;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

/** 录音 → 16kHz 单声道 PCM16 WAV（AudioRecord 直出，原生可靠）。 */
public class VoiceRecorder {

    private static final int SR = 16000;
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean recording;
    private ByteArrayOutputStream pcm;

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
                if (n > 0) pcm.write(buf, 0, n);
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
