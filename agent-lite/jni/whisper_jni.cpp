// SPDX-License-Identifier: Apache-2.0
//
// whisper_jni.cpp —— 离线语音转写的 JNI 层（whisper.cpp）。
//
// 设计取舍：
//  · 只用 whisper.h 的 C API，不复用官方 examples/whisper.android 的 Kotlin/Gradle 工程
//    （本项目是纯 Java + 手写构建脚本，不引入 Gradle 与第三方依赖）。
//  · use_gpu=false：手机上的 GPU 后端不稳（不同 SoC 差异大），CPU 4 线程实测更快更省电。
//  · no_context=true：语音是「说一句、识别一句」，带上文会把上一次的内容带进结果。
//  · initial_prompt 用简体先验：不加这句 whisper 对中文常输出繁体（Mac 侧实测过）。

#include <jni.h>
#include <android/log.h>
#include <string>

#include "whisper.h"

#define LOG_TAG "AgentAsr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dsh_agentlite_WhisperAsr_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("模型加载%s", ctx ? "成功" : "失败");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_dsh_agentlite_WhisperAsr_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                   jfloatArray pcm) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || pcm == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(pcm);
    jfloat *buf = env->GetFloatArrayElements(pcm, nullptr);
    if (buf == nullptr) return env->NewStringUTF("");

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime   = false;
    p.print_progress   = false;
    p.print_timestamps = false;
    p.print_special    = false;
    p.translate        = false;
    p.language         = "zh";
    p.n_threads        = 6;      // 手机普遍 8 核；4 线程实测 6s 音频要 8.6s，太慢
    p.no_context       = true;
    // 关掉「温度回退」重试：whisper 默认在解码不佳时用更高温度反复重跑，
    // 语音指令是短句，宁可一次出结果 —— 实测这一步就是 8.6s 里的主要开销。
    p.temperature_inc  = 0.0f;
    p.single_segment   = true;   // 语音输入永远是短句
    p.initial_prompt   = "以下是普通话的句子。";

    std::string out;
    const int64_t t0 = (int64_t) (clock() );
    if (whisper_full(ctx, p, buf, n) == 0) {
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (t) out += t;
        }
    } else {
        LOGI("whisper_full 返回失败");
    }
    (void) t0;
    env->ReleaseFloatArrayElements(pcm, buf, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_dsh_agentlite_WhisperAsr_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

}  // extern "C"
