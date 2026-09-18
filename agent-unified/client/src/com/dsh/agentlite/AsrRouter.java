// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;

/**
 * 语音转写的**统一入口**：本地离线优先，逐级回退。
 *
 * <p>回退链（2026-09-18 用户明确要求「语音转写改成离线的功能」+「不要依赖 Mac」后定型）：
 * <ol>
 *   <li><b>本地 whisper.cpp</b>（模型已下载时）—— 完全离线、不依赖任何外部设备；</li>
 *   <li><b>独立 ASR 端点</b>（设置里配了 asr_base_url 时）—— 例如自建/局域网服务；</li>
 *   <li><b>主模型音频通道</b>（原有逻辑）—— 仅当上游模型本身支持音频时才有用。</li>
 * </ol>
 * 这样任何一环失效都不会让语音整条功能哑掉，且默认路径是离线的。
 */
public final class AsrRouter {

    private static WhisperAsr sLocal;

    /**
     * 云端转写的**冷却截止时间**。用户要求「优先 GLM，不可用时用本地」——若不做冷却，
     * 云端一旦不可用，每次语音都要先等一次超时/401 才回退，体验很差。失败后 5 分钟内直接
     * 走本地离线，期间不做无谓重试；5 分钟后再试一次云端，恢复即自动切回。
     */
    private static volatile long sCloudCooldownUntil;
    private static final long CLOUD_COOLDOWN_MS = 5 * 60 * 1000L;

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
        // 0) 云端 ASR（配了端点即优先：更快更准、不占手机算力）
        String cloudBase = prefs.asrBaseUrl();
        long now = System.currentTimeMillis();
        if (!cloudBase.isEmpty() && now >= sCloudCooldownUntil) {
            try {
                LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
                String text = c.transcribe(wav, prefs.asrModel(), cloudBase, prefs.asrApiKey());
                if (text != null && !text.isEmpty()) {
                    sCloudCooldownUntil = 0;                       // 云端健康，解除冷却
                    // 成功也要记一行：否则排查时无法判断这次到底走的云端还是本地
                    AgentEngine.staticLog("云端转写成功（" + cloudBase + " / " + prefs.asrModel()
                            + "）：" + (text.length() > 30 ? text.substring(0, 30) + "…" : text));
                    return text;
                }
                sCloudCooldownUntil = now + CLOUD_COOLDOWN_MS;
                AgentEngine.staticLog("云端转写返回空，冷却 5 分钟，改用本地离线");
            } catch (Throwable t) {
                sCloudCooldownUntil = now + CLOUD_COOLDOWN_MS;
                AgentEngine.staticLog("云端转写不可用（" + t.getMessage() + "），冷却 5 分钟内改用本地离线");
            }
        } else if (!cloudBase.isEmpty()) {
            AgentEngine.staticLog("云端转写冷却中（还剩 "
                    + ((sCloudCooldownUntil - now) / 1000) + "s），本轮用本地离线");
        }
        // 1) 本地离线
        if (localReady(ctx)) {
            try {
                if (sLocal == null) sLocal = new WhisperAsr();
                if (sLocal.load(ctx)) {
                    float[] pcm = WhisperAsr.wavToPcm(wav);
                    String text = sLocal.transcribe(pcm);
                    if (text != null && !text.isEmpty()) return text;
                    AgentEngine.staticLog("本地转写返回空（可能是静音或过短）");
                }
            } catch (Throwable t) {
                AgentEngine.staticLog("本地转写异常，回退在线通道: " + t);
            }
        } else if (!WhisperAsr.available()) {
            AgentEngine.staticLog("本地转写库未打包（ABI 不匹配），走在线通道");
        } else {
            AgentEngine.staticLog("本地模型未下载，走在线通道（可在设置里下载离线模型）");
        }
        // 2/3) 独立端点 → 主模型音频
        LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
        return c.transcribe(wav, prefs.asrModel(), prefs.asrBaseUrl(), prefs.asrApiKey());
    }

    /** 预热：App 启动后台调一次，免去首次说话时的模型加载等待。 */
    public static void warmUp(final Context ctx) {
        if (!localReady(ctx)) return;
        new Thread(() -> {
            try {
                if (sLocal == null) sLocal = new WhisperAsr();
                sLocal.load(ctx.getApplicationContext());
            } catch (Throwable ignored) {
            }
        }, "asr-warmup").start();
    }
}
