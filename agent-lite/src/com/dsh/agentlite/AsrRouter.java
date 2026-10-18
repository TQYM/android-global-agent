// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;

/**
 * 语音转写的唯一入口：配置独立端点时只请求该端点，否则只使用本地离线模型。
 * 一次录音只选择一次实现；空结果和异常直接交给调用方，不重试、不切换通道。
 */
public final class AsrRouter {

    private static WhisperAsr sLocal;

    private AsrRouter() { }

    /** 本地转写是否可用（库已打包且模型已就绪）。 */
    public static boolean localReady(Context ctx) {
        return WhisperAsr.available() && WhisperAsr.modelReady(ctx);
    }

    /**
     * 语音 → 文字。
     *
     * @param wav VoiceRecorder 录的 16k/单声道/PCM16 WAV
     * @return 识别文本（可能为空串，调用方按「识别结果为空」处理）
     */
    public static String transcribe(Context ctx, Prefs prefs, byte[] wav) throws Exception {
        String asrBase = prefs.asrBaseUrl();
        if (!asrBase.isEmpty()) {
            String asrModel = prefs.asrModel();
            String asrKey = prefs.asrApiKey();
            // 只传 ASR 自己的配置：空 ASR key 也不借用主模型的付费凭据。
            // 独立端点分支只发一次 /audio/transcriptions，不进入 chat 重试或备用通道。
            LlmClient client = new LlmClient(asrBase, asrKey, asrModel);
            return client.transcribe(wav, asrModel, asrBase, asrKey);
        }

        if (!WhisperAsr.available()) {
            throw new IllegalStateException("本地转写库未就绪，请检查应用的设备架构。");
        }
        if (!WhisperAsr.modelReady(ctx)) {
            throw new IllegalStateException("本地语音模型未下载，请在设置中下载离线模型。");
        }
        synchronized (AsrRouter.class) {
            if (sLocal == null) sLocal = new WhisperAsr();
            if (!sLocal.load(ctx)) {
                throw new IllegalStateException("本地语音模型加载失败，请检查离线模型。");
            }
            return sLocal.transcribe(WhisperAsr.wavToPcm(wav));
        }
    }

    /** 仅预热当前选定的本地模式，和转写共用同一个模型实例。 */
    public static void warmUp(final Context ctx) {
        if (!new Prefs(ctx).asrBaseUrl().isEmpty() || !localReady(ctx)) return;
        new Thread(() -> {
            try {
                synchronized (AsrRouter.class) {
                    if (sLocal == null) sLocal = new WhisperAsr();
                    sLocal.load(ctx.getApplicationContext());
                }
            } catch (Throwable t) {
                AgentEngine.staticLog("本地转写预热失败: " + t);
            }
        }, "asr-warmup").start();
    }
}
