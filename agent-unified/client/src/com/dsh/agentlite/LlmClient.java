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
    /** 主/备通道是否为「账号额度网关」——决定失败归因该指向哪一侧（手册 §16.2 P0-4）。 */
    private boolean primaryIsGateway;
    private boolean fallbackIsGateway;

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

    /**
     * 用户主动停止导致的取消（不是网络故障）。
     *
     * <p>单独成类，是为了让上层把「我让你停的」与「网络出错」分开：前者不该报错、
     * 不该触发网关回退、也不该被算作任务失败。
     */
    public static class CancelledException extends Exception {
        public CancelledException(String msg) { super(msg); }
    }

    /**
     * 上游明确拒绝了请求（欠费 / 密钥失效 / 无权限 / 模型不存在）。
     *
     * <p>与「网络故障」完全两回事：网络故障重试可能成功，而欠费重试一百次也一样。
     * 单独成类是为了让引擎给出**能照着做**的归因（手册 §16.2 P0-4），
     * 而不是笼统地甩一句「请检查网络与 API 配置」。
     */
    public static class UpstreamRejectedException extends Exception {
        /** 面向用户的人话原因（不含云厂商原文）。 */
        public final String humanReason;
        /** 是否值得换一条通道重试（例如自备 Key 欠费 → 改用账号额度）。 */
        public final boolean retryOnOtherChannel;
        /** 拒绝发生在**账号额度网关**通道上（true）还是用户自备配置上（false）。 */
        public final boolean viaGateway;

        UpstreamRejectedException(String humanReason, String rawDetail,
                                  boolean retryOnOtherChannel, boolean viaGateway) {
            super(rawDetail);
            this.humanReason = humanReason;
            this.retryOnOtherChannel = retryOnOtherChannel;
            this.viaGateway = viaGateway;
        }
    }

    /**
     * 把上游错误体翻译成人话（只认关键词，不做猜测）。
     *
     * <p>典型来源：DashScope 欠费返回 {@code "type":"Arrearage"}；OpenAI 兼容网关
     * 密钥错误返回 {@code invalid_api_key}；模型名不存在返回 {@code model_not_found}。
     */
    static UpstreamRejectedException classifyUpstream(int code, String text, boolean viaGateway) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.US);
        if (t.contains("arrearage") || t.contains("overdue") || t.contains("insufficient")
                || t.contains("balance") || t.contains("欠费") || t.contains("余额不足")) {
            return new UpstreamRejectedException(
                    "接口账号余额不足（欠费），模型无法调用", text, true, viaGateway);
        }
        if (t.contains("invalid_api_key") || t.contains("incorrect api key")
                || t.contains("invalid api key") || t.contains("unauthorized")
                || t.contains("authentication")) {
            return new UpstreamRejectedException(
                    "接口密钥无效或已失效（可能被撤销或填错）", text, true, viaGateway);
        }
        if (t.contains("model_not_found") || t.contains("model not found")
                || t.contains("does not exist") || t.contains("no such model")) {
            return new UpstreamRejectedException(
                    "所选的模型名在接口方不存在或未开通", text, false, viaGateway);
        }
        if (t.contains("access denied") || t.contains("permission")
                || t.contains("forbidden") || t.contains("not authorized")) {
            return new UpstreamRejectedException(
                    "接口账号没有调用该模型的权限", text, false, viaGateway);
        }
        if (code == 401 || code == 403) {
            return new UpstreamRejectedException("接口拒绝了本次调用（密钥或权限问题）", text, true, viaGateway);
        }
        return new UpstreamRejectedException("接口方拒绝了本次请求", text, false, viaGateway);
    }

    /** 单次响应体上限。正常响应是几 KB 的 JSON，超过 1MB 只可能是异常或恶意上游。 */
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    /**
     * 网络调用线程池。
     *
     * <p>存在的唯一理由是让「取消」不必等阻塞中的 socket：请求在池线程里跑，
     * 主循环轮询结果并检查取消标志（见 {@link #chatCancellable}）。
     * daemon 线程 + cached 池：空闲线程自动回收，也不会拖住进程退出。
     */
    private static final java.util.concurrent.ExecutorService NET_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "llm-net");
                t.setDaemon(true);
                return t;
            });

    /** 取消检查间隔。越小越即时、越大越省电；200ms 对交互已无感。 */
    private static final long CANCEL_POLL_MS = 200;

    /** 正在进行的连接（供 {@link #cancel()} 断开）。 */
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    /**
     * 取消当前与后续请求：断开正在读的连接并置位取消标志。
     *
     * <p>此前 {@code AgentEngine.stop()} 只能置一个标志位，而一次读最长等 120 秒
     * （手册 §15.2-2）——用户点了「停止」却要盯着屏幕等两分钟才动。{@code disconnect()}
     * 会让阻塞中的 read 立刻抛 IOException，再在这里被识别成 {@link CancelledException}。
     */
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = active;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    /** 是否已被取消（引擎在循环里据此快速退出）。 */
    public boolean isCancelled() { return cancelled; }

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
        // 官方购买模式：用户自己买了 token 资源包、用自己的 Key。
        // 此时**必须让开网关**——否则网关一直顶在前面，刚写入的那张 Key 永远用不上
        // （PURCHASE_REDIRECT_DESIGN 决策 4：这批用户关闭 use_gateway）。
        if (p.useOwnKey()) {
            LlmClient own = new LlmClient(p.baseUrl(), p.apiKey(), model);
            // 自带 Key 是高级路径，但它一旦欠费/失效，用户会完全用不了——而此时
            // 账号额度往往还可用。把它登记为备用通道：上游拒绝且"换通道可能有用"时自动改用
            // 账号额度，并在日志里说清楚（手册 §16.2 P0-3：优先让普通用户用账号额度）。
            if (p.loggedIn() && !p.accountInvalid()) {
                String gwBase = SyncClient.serverBase(ctx);
                String token = p.accountToken();
                if (!gwBase.isEmpty() && !token.isEmpty()) {
                    own.fallback(gwBase + "/v1", token);
                    own.fallbackIsGateway = true;
                }
            }
            return own;
        }
        if (p.loggedIn() && p.sp.getBoolean("use_gateway", true)) {
            String base = SyncClient.serverBase(ctx);
            String token = p.accountToken();
            if (!base.isEmpty() && !token.isEmpty()) {
                // 备用是用户自填的直连配置：网关 5xx / 网络异常 / 上游鉴权失败时回退，
                // 但 **402 额度耗尽绝不回退**——否则额度形同虚设。
                LlmClient gw = new LlmClient(base + "/v1", token, model)
                        .fallback(p.baseUrl(), p.apiKey());
                gw.primaryIsGateway = true;
                return gw;
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
            if (cancelled) throw new CancelledException("任务已停止");
            try {
                return chatCancellable(messages);
            } catch (QuotaExhaustedException q) {
                throw q;   // 额度耗尽：立即上抛，不做 429 那套退避重试（白等 2 分钟且不会成功）
            } catch (CancelledException ce) {
                throw ce;  // 用户取消：同理，不该退避重试
            } catch (Exception e) {
                last = e;
                String msg = String.valueOf(e.getMessage());
                if (!msg.contains("HTTP 429") && !msg.contains("quota")) throw e;
                int waitSec = 20 * (attempt + 1);
                android.util.Log.i("LlmClient", "429 限流，" + waitSec + "s 后重试（第 " + (attempt + 1) + " 次）");
                try {
                    Thread.sleep(waitSec * 1000L);
                } catch (InterruptedException ie) {
                    // 恢复中断标志：被停止/接管打断时不该吞掉这个信号
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    /**
     * 把一次请求交给网络线程执行，主线程轮询等待——这样「取消」不必等 socket。
     *
     * <p>为什么不能直接同步调用：{@code stop()} 里的 {@code disconnect()} 只能中断
     * <b>已建立连接</b>的请求；连接阶段（地址不可达、DNS 慢）不响应它。真机实测
     * （2026-09-14）：点停止后仍要等满 15 秒 connect 超时才退出，用户看到的是
     * 「按了停止没反应」。轮询把两者解耦：主循环最多 200ms 就退出，滞留的 socket
     * 在后台自然收尾，其返回值被丢弃（cancelled 已置位）。
     */
    private String chatCancellable(JSONArray messages) throws Exception {
        java.util.concurrent.Future<String> f = NET_POOL.submit(() -> chatOnce(messages));
        for (;;) {
            if (cancelled) {
                f.cancel(true);
                throw new CancelledException("任务已停止");
            }
            try {
                return f.get(CANCEL_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                // 正常：继续轮询，既等结果也等取消
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new Exception(String.valueOf(cause));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                throw new CancelledException("任务被中断");
            }
        }
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
        // 关闭思考链：智谱/通义用 thinking+enable_thinking；DeepSeek 实测**只认**
        // {"thinking":{"type":"disabled"}}（enable_thinking / chat_template_kwargs 无效），
        // 不关的话思考会把 max_tokens 吃满、content 返回空串（2026-09-15 实测）。
        // 走账号额度网关时 URL 不含这些域名，由服务端在转发前注入同样的字段。
        if (baseUrl.contains("bigmodel.cn") || baseUrl.contains("aliyuncs.com")
                || baseUrl.contains("dashscope") || baseUrl.contains("deepseek")) {
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
            // 统一的「空结果」出口：上层据此给出可理解的失败原因，而不是拿空串去解析
            // （手册 §15.2-10：空 choices / 空文本 / 非 JSON 的恢复策略要一致）
            throw new Exception("模型返回空结果（choices 为空）");
        }
        JSONObject first = choices.optJSONObject(0);
        JSONObject msg = first == null ? null : first.optJSONObject("message");
        String content = msg == null ? "" : msg.optString("content", "");
        if (content == null || content.trim().isEmpty()) {
            throw new Exception("模型返回空内容（可能触发内容策略或达到长度上限）");
        }
        return content;
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
            return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType, fallbackIsGateway);
        }
        try {
            return postOnce(baseUrl, apiKey, path, payload, contentType, primaryIsGateway);
        } catch (QuotaExhaustedException q) {
            // 账号额度耗尽（402）：若用户配置了自己的接口，改用它。
            //
            // 原设计刻意不回退，怕"绕过额度"；但那会把用户彻底卡死——他明明有一份
            // 自己付费的配置，却因为账号额度用完而完全用不了（2026-09-15 真机实测：
            // 额度归零后所有任务在第一步就 402）。回退消耗的是用户自己的额度，
            // 不损害服务方计费，符合手册 §16「降低技术门槛」。
            if (!onFallback && fbBaseUrl != null && !fbBaseUrl.isEmpty()) {
                android.util.Log.i(TAG, "账号额度不足，改用你自己配置的接口");
                onFallback = true;
                return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType, fallbackIsGateway);
            }
            throw q;
        } catch (CancelledException ce) {
            throw ce;   // 取消不是「网关不可用」，绝不能因此回退到用户自己的 Key
        } catch (UpstreamRejectedException ure) {
            // 上游拒绝只有「换个通道可能好」时才回退（欠费/密钥失效 → 换账号额度有意义；
            // 模型名不存在 → 换通道也一样失败，直接上抛）
            if (!ure.retryOnOtherChannel || fbBaseUrl == null || fbBaseUrl.isEmpty()) throw ure;
            android.util.Log.i(TAG, "上游拒绝（" + ure.humanReason + "），改用备用通道");
            onFallback = true;
            try {
                return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType, fallbackIsGateway);
            } catch (UpstreamRejectedException ure2) {
                // 两条通道都被上游拒绝：用户照着「换通道」的建议做也没用，
                // 归因必须两边都说清楚，否则他会在两个设置之间来回改（§16.2 P0-4）
                throw new UpstreamRejectedException(
                        ure.humanReason + "；备用通道同样不可用：" + ure2.humanReason,
                        ure2.getMessage(), false, ure2.viaGateway);
            }
        } catch (Exception e) {
            if (fbBaseUrl == null || fbBaseUrl.isEmpty()) throw e;
            android.util.Log.i(TAG, "额度网关不可用，回退直连（原因：" + e.getMessage() + "）");
            onFallback = true;
            return postOnce(fbBaseUrl, fbApiKey, path, payload, contentType, fallbackIsGateway);
        }
    }

    private JSONObject postOnce(String base, String key, String path, byte[] payload,
                                String contentType, boolean viaGateway) throws Exception {
        if (cancelled) throw new CancelledException("任务已停止");
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
        active = conn;
        try {
            // 输出流必须关闭：不关会一直占着连接，服务端也可能等不到请求结束。
            java.io.OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                try { os.close(); } catch (Exception ignored) { }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
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
            if (code == 400 || code == 401 || code == 403) {
                // 上游明确拒绝：给出人话归因（细节仍在异常 message 里，只进日志）
                throw classifyUpstream(code, text, viaGateway);
            }
            if (code != 200) {
                throw new Exception("HTTP " + code + ": " +
                        (text.length() > 200 ? text.substring(0, 200) : text));
            }
            return new JSONObject(text);
        } catch (java.io.IOException e) {
            // cancel() 会 disconnect 掉连接，读阻塞会立刻抛 IOException——
            // 这里把「我们自己取消的」与真正的网络故障分开，后者才该触发回退。
            if (cancelled) throw new CancelledException("任务已停止");
            throw e;
        } finally {
            // 所有路径（含 402 抛出、JSON 解析失败、取消）都要断开，
            // 否则长任务里的网络异常会持续泄漏连接与 fd（手册 §15.2-3）。
            active = null;
            conn.disconnect();
        }
    }

    /** 读完响应体并关闭流；同时给响应体设上限，避免异常上游把内存吃光。 */
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n, total = 0;
            while ((n = is.read(tmp)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new java.io.IOException("响应体超过 1MB 上限");
                }
                buf.write(tmp, 0, n);
            }
            return buf.toString("UTF-8");
        } finally {
            try { is.close(); } catch (Exception ignored) { }
        }
    }
}
