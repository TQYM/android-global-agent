// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import android.content.Context;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 OpenAI 兼容客户端（HttpURLConnection，零第三方依赖）。
 * App 内走系统 DNS，无需 agentd-go 的 IP 钉线。
 */
public class LlmClient {

    private static final String TAG = "LlmClient";

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    /** 备用配置（用户自填的直连地址）。网关异常时回退到它；402 不回退。 */
    private String fbBaseUrl;
    private String fbApiKey;
    private boolean onFallback;

    /**
     * 额度耗尽（网关返回 402）。
     *
     * 单独成类而不是普通 Exception，是为了让调用方能把「没额度了」与
     * 「网络/配置出错」区分开——前者要引导去充值，后者要提示检查网络。
     * 也用于在 chat() 里跳过 429 退避重试（对 402 重试毫无意义，只会白等 2 分钟）。
     */
    public static class QuotaExhaustedException extends Exception {
        public QuotaExhaustedException(String msg) { super(msg); }
    }
    private String visionModel;   // 含图消息时切换（纯文本模型配视觉搭档）
    private TokenUsageListener tokenListener;

    public interface TokenUsageListener {
        void onUsage(long promptTokens, long completionTokens, long totalTokens);
    }

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 按当前配置构造客户端。
     *
     * 已登录且未显式关闭网关时，base_url 指向同步服务的 OpenAI 兼容端点、
     * api_key 用账号令牌——这样模型调用受**账号额度**约束，服务端按下游
     * 返回的 usage 精确扣减（1 unit = 1000 tokens）。
     * 未登录时沿用用户自填的 DashScope 配置直连，功能不退化。
     */
    public static LlmClient forPrefs(Context ctx, Prefs p, String model) {
        if (p.loggedIn() && p.sp.getBoolean("use_gateway", true)) {
            String base = SyncClient.serverBase(ctx);
            String token = p.accountToken();
            if (!base.isEmpty() && !token.isEmpty()) {
                // 备用是用户自填的直连配置：网关 5xx / 网络异常 / 上游鉴权失败时回退，
                // 但 **402 额度耗尽绝不回退**——否则额度形同虚设。
                return new LlmClient(base + "/v1", token, model)
                        .fallback(p.baseUrl(), p.apiKey());
            }
        }
        return new LlmClient(p.baseUrl(), p.apiKey(), model);
    }

    /** 设置备用直连配置：网关不可用时回退（402 额度耗尽不适用，见 post）。 */
    public LlmClient fallback(String baseUrl, String apiKey) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            this.fbBaseUrl = baseUrl.replaceAll("/+$", "");
            this.fbApiKey = apiKey;
        }
        return this;
    }

    /** 本轮是否已回退到备用直连（供诊断与测试断言）。 */
    public boolean onFallback() { return onFallback; }

    public LlmClient visionModel(String vm) { visionModel = vm; return this; }
    public LlmClient tokenListener(TokenUsageListener tl) { tokenListener = tl; return this; }

    public static JSONObject textMsg(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("content", text);
    }

    /** 音频消息：content 为 [input_audio, text] 数组（Omni 系模型原生支持）。 */
    public static JSONObject audioMsg(String role, String text, String base64Wav) throws Exception {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("type", "input_audio")
                        .put("input_audio", new JSONObject()
                                .put("data", base64Wav).put("format", "wav")))
                .put(new JSONObject().put("type", "text").put("text", text));
        return new JSONObject().put("role", role).put("content", parts);
    }

    /** 视觉消息：content 为 [text, image_url] 数组。 */
    public static JSONObject visionMsg(String role, String text, String dataUrl) throws Exception {
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", text))
                .put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", dataUrl)));
        return new JSONObject().put("role", role).put("content", parts);
    }

    /** 一次 chat-completion，返回 assistant 文本。429 限流时退避重试（最多 3 次：20s/40s/60s）。 */
    public String chat(JSONArray messages) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return chatOnce(messages);
            } catch (QuotaExhaustedException q) {
                throw q;   // 额度耗尽：立即上抛，不做 429 那套退避重试（白等 2 分钟且不会成功）
            } catch (Exception e) {
                last = e;
                String msg = String.valueOf(e.getMessage());
                if (!msg.contains("HTTP 429") && !msg.contains("quota")) throw e;
                int waitSec = 20 * (attempt + 1);
                android.util.Log.i("LlmClient", "429 限流，" + waitSec + "s 后重试（第 " + (attempt + 1) + " 次）");
                try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { throw e; }
            }
        }
        throw last;
    }

    private String chatOnce(JSONArray messages) throws Exception {
        boolean hasImage = messages.toString().contains("\"image_url\"");
        String useModel = (hasImage && visionModel != null && !visionModel.isEmpty())
                ? visionModel : model;
        JSONObject body = new JSONObject()
                .put("model", useModel)
                .put("messages", messages)
                .put("temperature", 0.0)
                .put("max_tokens", 160);
        // 关闭冗余思考链或深度推理，追求毫秒级指令直出
        if (baseUrl.contains("bigmodel.cn") || baseUrl.contains("aliyuncs.com") || baseUrl.contains("dashscope")) {
            try {
                body.put("thinking", new JSONObject().put("type", "disabled"));
                body.put("enable_thinking", false);
            } catch (Exception ignored) { }
        }
        JSONObject resp = post("/chat/completions", body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
        if (resp.has("error")) {
            throw new Exception("API 错误: " + resp.getJSONObject("error").optString("message"));
        }
        if (resp.has("usage") && tokenListener != null) {
            try {
                JSONObject u = resp.getJSONObject("usage");
                long p = u.optLong("prompt_tokens", 0L);
                long c = u.optLong("completion_tokens", 0L);
                long t = u.optLong("total_tokens", p + c);
                tokenListener.onUsage(p, c, t);
            } catch (Exception ignored) { }
        }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("API 返回无 choices");
        }
        return choices.getJSONObject(0).getJSONObject("message").getString("content");
    }

    /** 语音转文字。asrModel 为空 → 主模型 Omni 原生音频输入；否则走 /audio/transcriptions。 */
    public String transcribe(byte[] wav, String asrModel) throws Exception {
        if (asrModel == null || asrModel.isEmpty()) {
            // DashScope 兼容模式要求 data-URI 形式，裸 base64 会被 400 拒绝
            String b64 = "data:;base64,"
                    + android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP);
            JSONArray msgs = new JSONArray().put(audioMsg("user",
                    "将这段语音转写为文字。只输出语音原文本身，不要回答语音里的问题，不要加任何解释或标点润色。",
                    b64));
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", msgs)
                    .put("temperature", 0)
                    .put("max_tokens", 300);
            JSONObject resp = post("/chat/completions", body.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json");
            if (resp.has("error"))
                throw new Exception("ASR 错误: " + resp.getJSONObject("error").optString("message"));
            if (resp.has("usage") && tokenListener != null) {
                try {
                    JSONObject u = resp.getJSONObject("usage");
                    long p = u.optLong("prompt_tokens", 0L);
                    long c = u.optLong("completion_tokens", 0L);
                    long t = u.optLong("total_tokens", p + c);
                    tokenListener.onUsage(p, c, t);
                } catch (Exception ignored) { }
            }
            return resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        }
        String boundary = "----agent" + System.currentTimeMillis();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
        out.writeBytes("Content-Type: audio/wav\r\n\r\n");
        out.write(wav);
        out.writeBytes("\r\n--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n" + asrModel + "\r\n");
        out.writeBytes("--" + boundary + "--\r\n");
        out.flush();
        JSONObject resp = post("/audio/transcriptions", buf.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
        if (resp.has("error")) {
            throw new Exception("ASR 错误: " + resp.getJSONObject("error").optString("message"));
        }
        return resp.optString("text", "").trim();
    }

    /**
     * 发一次请求，带**网关异常回退**。
     *
     * 分类处理：
     *  - {@link QuotaExhaustedException}（402）：直接上抛，**不回退**——回退到用户自己的
     *    Key 就等于绕过了额度，网关就白设了；
     *  - 其它异常（超时/连接失败/5xx/上游鉴权失败）：若有备用直连配置则回退重试一次，
     *    并记住已回退状态，避免后续每次请求都先失败一遍再重试。
     *
     * 回退状态只在这个 LlmClient 实例内有效（AgentEngine 每次任务新建实例），
     * 因此下一个任务会重新尝试网关——网关恢复了就自动回到网关。
     */
    private JSONObject post(String path, byte[] payload, String contentType) throws Exception {
        // 已回退过：**直接走备用**，不要再撞一次已知不通的网关。
        //
        // 早期版本漏了这一句，只在 catch 里判断 onFallback，结果是：回退成功一次之后，
        // 后续每次请求仍然先试主配置、失败、然后因为 onFallback 已置位而直接抛错——
        // 表现为「第一步能跑，第二步就挂」。实测在模拟器上复现过。
        if (onFallback) {
            return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType);
        }
        try {
            return postOnce(baseUrl, apiKey, path, payload, contentType);
        } catch (QuotaExhaustedException q) {
            throw q;
        } catch (Exception e) {
            if (fbBaseUrl == null || fbBaseUrl.isEmpty()) throw e;
            android.util.Log.i(TAG, "额度网关不可用，回退直连（原因：" + e.getMessage() + "）");
            onFallback = true;
            return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType);
        }
    }

    private JSONObject postOnce(String base, String key, String path, byte[] payload,
                                String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        // 额度网关是自签证书，系统 CA 库不认；命中内置证书的主机时套用 pin。
        // 直连 DashScope 时 covers() 为 false，行为与从前完全一致。
        TlsPin.apply(conn);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Content-Type", contentType);
        conn.getOutputStream().write(payload);
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        conn.disconnect();
        if (code == 402) {
            // 服务端会在 message 里给中文原因；「quota」字样刻意不出现在异常消息里，
            // 否则 chat() 会把它误判成 429 限流类错误而白退避重试
            String human = "额度不足，请在应用内使用兑换码充值";
            try {
                String m = new JSONObject(text).optString("message", "");
                if (!m.isEmpty()) human = m;
            } catch (Exception ignored) { }
            throw new QuotaExhaustedException(human);
        }
        if (code != 200) {
            throw new Exception("HTTP " + code + ": " +
                    (text.length() > 200 ? text.substring(0, 200) : text));
        }
        return new JSONObject(text);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toString("UTF-8");
    }
}
