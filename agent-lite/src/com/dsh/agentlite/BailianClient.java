// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 调用 Model Studio（百炼）`CreateApiKey`，用用户本地 AK 换一把 DashScope API Key。
 *
 * <p>这是充值改版流程里「自动创建 Key」那一步（`PURCHASE_REDIRECT_DESIGN` §2）。
 * 请求形状全部已实测确认，见设计文档 §5.1：
 * <pre>
 *   POST https://modelstudio.cn-beijing.aliyuncs.com/modelstudio/apikeys
 *   x-acs-action: CreateApiKey      x-acs-version: 2026-02-10
 *   query: workspaceId(选填) / description(选填)
 *   成功: {"apiKey":{"apiKeyValue":"sk-ws-…","apiKeyId":…,"workspaceId":…}}
 * </pre>
 *
 * <p><b>本类承担两条实测踩出来的红线</b>：
 * <ol>
 *   <li><b>成功判定不能只看 HTTP 状态码</b>：网关认证失败时可能返回 <b>HTTP 200</b> 配
 *       {@code {"Code":"…","Success":false}}。只看状态码会把失败当成功，写一个空 Key 进
 *       {@code Prefs} —— 用户以为充值成功、实际不可用。故 {@link #create} 必须同时满足
 *       {@code Success==true} **且** {@code apiKeyValue} 非空。</li>
 *   <li><b>只签 6 个确定性请求头</b>：绝不把 {@code user-agent}/{@code content-type}/{@code accept}
 *       放进签名集合。若 HTTP 库在签名之后才补齐或改写它们，服务端算出的规范化头就与客户端不一致
 *       → {@code SignatureDoesNotMatch}，极难排查。</li>
 * </ol>
 */
public final class BailianClient {

    private static final String TAG = "AgentLiteBailian";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 控制面 endpoint（**不是**推理用的 dashscope 域名，两者不可混用）。 */
    private static final String HOST = "modelstudio.cn-beijing.aliyuncs.com";
    private static final String PATH = "/modelstudio/apikeys";
    private static final String ACTION = "CreateApiKey";
    private static final String LIST_ACTION = "ListApiKeys";
    private static final String VERSION = "2026-02-10";

    private BailianClient() {}

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** 创建成功后的 Key 信息。 */
    public static final class CreatedKey {
        /** ← 要写进 {@code Prefs.apiKey} 的那把 Key（形如 {@code sk-ws-…}）。 */
        public final String apiKeyValue;
        public final long apiKeyId;
        public final String workspaceId;
        public final String description;

        CreatedKey(String apiKeyValue, long apiKeyId, String workspaceId, String description) {
            this.apiKeyValue = apiKeyValue;
            this.apiKeyId = apiKeyId;
            this.workspaceId = workspaceId;
            this.description = description;
        }
    }

    /** 结果回调（回主线程）。 */
    public interface Callback {
        void onOk(CreatedKey key);
        /** @param message 面向用户的中文说明；@param retryable 是否值得让用户重试 */
        void onErr(String message, boolean retryable);
    }

    /** 列表回调（回主线程）。 */
    public interface ListCallback {
        void onOk(java.util.List<PurchaseFlow.KeyInfo> keys);
        void onErr(String message, boolean retryable);
    }

    /**
     * 列出账号下已有的 API Key（用于「返回 App 后是否真的新增了 Key」的判定）。
     *
     * <p>为什么需要它：官方购买页不支持自定义 scheme 回跳，若只靠 `onResume` 猜时间窗，
     * 会出现「用户切出去看了眼微信再回来」被误判为「已付款」的假阳性，接着就去调
     * {@link #create}。改为比对官方侧的真实差异，不依赖时序
     * （`PURCHASE_REDIRECT_DESIGN` §5.3 缺口 1 的决策）。
     *
     * <p>只取 id/gmtCreate/description/disabled，**不落 Key 明文**——判定差异用不到它，
     * 少碰一次就少一次泄露机会。
     */
    public static void list(Context ctx, ListCallback cb) {
        new Thread(() -> {
            AkStore store = new AkStore(ctx);
            if (!store.has()) {
                post(() -> cb.onErr("尚未配置阿里云 AccessKey", false));
                return;
            }
            char[] secret = store.loadSecret();
            if (secret == null || secret.length == 0) {
                post(() -> cb.onErr("AccessKey 已失效，请重新录入", false));
                return;
            }
            try {
                AliyunV3Signer.Request req = buildListRequest(store.akId(), secret);
                AliyunV3Signer.Signed signed = AliyunV3Signer.sign(req);
                HttpResult r = send(req, signed);

                JSONObject o = parse(r.body);
                if (o == null) {
                    post(() -> cb.onErr("读取 API Key 列表失败（HTTP " + r.status + "）", r.status >= 500));
                    return;
                }
                boolean ok = o.optBoolean("success", false) || o.optBoolean("Success", false);
                if (!ok) {
                    String code = firstNonEmpty(o.optString("code", ""), o.optString("Code", ""));
                    String msg = firstNonEmpty(o.optString("message", ""), o.optString("Message", ""));
                    final boolean retry = isRetryable(code);
                    final String friendly = friendlyError(code, msg);
                    post(() -> cb.onErr(friendly, retry));
                    return;
                }
                org.json.JSONArray arr = o.optJSONArray("apiKeys");
                java.util.List<PurchaseFlow.KeyInfo> out = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject k = arr.optJSONObject(i);
                        if (k == null) continue;
                        out.add(new PurchaseFlow.KeyInfo(
                                k.optLong("apiKeyId", 0L),
                                k.optLong("gmtCreate", 0L),
                                k.optString("description", ""),
                                k.optInt("disabled", 0) == 1));
                    }
                }
                post(() -> cb.onOk(out));
            } catch (Exception e) {
                android.util.Log.w(TAG, "ListApiKeys 失败：" + e.getClass().getSimpleName());
                post(() -> cb.onErr("读取 API Key 列表失败（网络异常）", true));
            } finally {
                AkStore.wipe(secret);
            }
        }, "bailian-list-keys").start();
    }

    private static AliyunV3Signer.Request buildListRequest(String akId, char[] secret) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date());

        Map<String, String> query = new LinkedHashMap<>();
        query.put("maxResults", "100");
        query.put("orderBy", "gmtCreate");
        query.put("order", "DESC");

        Map<String, String> headers = new TreeMap<>();
        headers.put("x-acs-action", LIST_ACTION);
        headers.put("x-acs-date", date);
        headers.put("x-acs-signature-nonce", randomNonce());
        headers.put("x-acs-version", VERSION);

        return new AliyunV3Signer.Request(
                akId, secret, "GET", HOST, PATH, LIST_ACTION, VERSION, date,
                query, "", headers);
    }

    /**
     * 用本地 AK 创建一把 API Key。
     *
     * <p>AK 从 {@link AkStore} 读取；未配置或 Keystore 密钥已失效时直接回调错误
     * （此时 {@code AkStore} 已自动清空存储，调用方应引导用户重新录入）。
     *
     * @param workspaceId 业务空间 ID，传 null/空 = 落到账号默认业务空间
     */
    public static void create(Context ctx, String workspaceId, Callback cb) {
        new Thread(() -> {
            AkStore store = new AkStore(ctx);

            if (!store.has()) {
                post(() -> cb.onErr("尚未配置阿里云 AccessKey，无法自动创建 API Key", false));
                return;
            }

            char[] secret = store.loadSecret();
            if (secret == null || secret.length == 0) {
                // loadSecret() 失败时已 clear()，这里说明"为什么"而不是报网络错
                post(() -> cb.onErr("AccessKey 已失效（可能因系统锁屏/恢复出厂导致加密密钥作废），请重新录入", false));
                return;
            }

            try {
                AliyunV3Signer.Request req = buildRequest(store.akId(), secret, workspaceId);
                AliyunV3Signer.Signed signed = AliyunV3Signer.sign(req);
                HttpResult r = send(req, signed);
                handleResponse(r, cb);
            } catch (Exception e) {
                // 只记异常类型，绝不记 AK/SK 或请求头内容
                android.util.Log.w(TAG, "CreateApiKey 调用失败：" + e.getClass().getSimpleName());
                post(() -> cb.onErr("创建 API Key 失败（网络异常），请检查网络后重试", true));
            } finally {
                AkStore.wipe(secret);   // 明文密钥用完即抹
            }
        }, "bailian-create-key").start();
    }

    // ------------------------------------------------------------------
    // 组装请求
    // ------------------------------------------------------------------

    private static AliyunV3Signer.Request buildRequest(String akId, char[] secret, String workspaceId) {
        // ISO8601 UTC，官方要求格式 yyyy-MM-dd'T'HH:mm:ss'Z'，且必须是请求发出前 15 分钟内的时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date());

        // x-acs-signature-nonce：防重放。文档标"可选"，但官方两个 Java 示例都带且签，故带上。
        String nonce = randomNonce();

        Map<String, String> query = new LinkedHashMap<>();
        if (workspaceId != null && !workspaceId.trim().isEmpty()) {
            query.put("workspaceId", workspaceId.trim());
        }
        query.put("description", "AgentLite 自动创建");

        // 只放这 6 个确定性头进签名集合（host 由 sign() 补，此处给其余 5 个）
        Map<String, String> headers = new TreeMap<>();
        headers.put("x-acs-action", ACTION);
        headers.put("x-acs-date", date);
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-version", VERSION);
        // x-acs-content-sha256 由 sign() 按实际空正文自动写入

        return new AliyunV3Signer.Request(
                akId, secret, "POST", HOST, PATH, ACTION, VERSION, date,
                query, "", headers);
    }

    private static String randomNonce() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return AliyunV3Signer.hex(b);
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    private static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    private static HttpResult send(AliyunV3Signer.Request req, AliyunV3Signer.Signed signed)
            throws Exception {
        StringBuilder urlSb = new StringBuilder("https://").append(HOST).append(PATH);
        String qs = AliyunV3Signer.canonicalQueryString(req.query);
        if (!qs.isEmpty()) urlSb.append('?').append(qs);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlSb.toString()).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);

            // 设置参与签名的头（host 由 HTTP 栈按 URL 自动设置，不手动加，避免重复 host 头）
            for (Map.Entry<String, String> e : signed.headers.entrySet()) {
                if ("host".equalsIgnoreCase(e.getKey())) continue;
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            conn.setRequestProperty("Authorization", signed.authorization);
            // 注意：不设 User-Agent / Accept —— 它们不进签名，设了也不会影响签名正确性，
            // 但少设一个就少一个"库改写头部导致签名不一致"的机会。
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            // 空正文（参数都在 query）
            conn.setFixedLengthStreamingMode(0);
            try (OutputStream os = conn.getOutputStream()) {
                os.flush();
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            return new HttpResult(status, body);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析响应。
     *
     * <p>两套字段风格都要认：失败时网关给 PascalCase（{@code Code}/{@code Message}/{@code Success}），
     * 成功时 metadata 描述的是 camelCase（{@code apiKey.apiKeyValue}）。
     */
    private static void handleResponse(HttpResult r, Callback cb) {
        JSONObject o = parse(r.body);

        if (o != null) {
            // ---- 成功路径：必须 Success==true 且 Key 非空，缺一不可 ----
            boolean success = o.optBoolean("Success", false) || o.optBoolean("success", false);
            JSONObject apiKey = o.optJSONObject("apiKey");
            String keyValue = apiKey == null ? null : apiKey.optString("apiKeyValue", "");
            if (success && keyValue != null && !keyValue.isEmpty()) {
                long id = apiKey.optLong("apiKeyId", 0L);
                String ws = apiKey.optString("workspaceId", "");
                String desc = apiKey.optString("description", "");
                post(() -> cb.onOk(new CreatedKey(keyValue, id, ws, desc)));
                return;
            }
            if (keyValue != null && !keyValue.isEmpty()) {
                // 有 Key 但没标 success：宁可当失败，也不要把来路不明的 Key 写进去
                post(() -> cb.onErr("服务端返回了 Key 但未标记成功，为安全起见不予采用，请重试", true));
                return;
            }

            String code = firstNonEmpty(o.optString("Code", ""), o.optString("code", ""));
            String msg = firstNonEmpty(o.optString("Message", ""), o.optString("message", ""));
            String friendly = friendlyError(code, msg);
            boolean retryable = isRetryable(code);
            post(() -> cb.onErr(friendly, retryable));
            return;
        }

        // 完全没有结构化响应
        final int st = r.status;
        post(() -> cb.onErr("创建 API Key 失败（HTTP " + st + "），请稍后重试", st >= 500));
    }

    /** 把网关错误码翻成用户能看懂、且知道下一步该做什么的中文。 */
    private static String friendlyError(String code, String msg) {
        switch (code == null ? "" : code) {
            case "InvalidAccessKeyId.NotFound":
                return "AccessKey 不存在或已删除——请在「更改 API」里重新录入";
            case "SignatureDoesNotMatch":
                return "签名校验失败（AccessKeySecret 可能不对），请重新录入 AccessKey";
            case "Forbidden":
            case "NoPermission":
            case "Forbidden.RAM":
                return "AccessKey 权限不足——需要为该 RAM 用户授予 modelstudio:CreateApiKey 权限";
            case "InvalidVersion":
                return "接口版本不被支持（客户端需更新）";
            case "Throttling":
            case "Throttling.User":
                return "请求过于频繁，请稍后重试";
            default:
                if (msg != null && !msg.isEmpty()) return "创建 API Key 失败：" + msg;
                return "创建 API Key 失败（" + code + "）";
        }
    }

    private static boolean isRetryable(String code) {
        if (code == null) return true;
        switch (code) {
            case "InvalidAccessKeyId.NotFound":
            case "SignatureDoesNotMatch":
            case "Forbidden":
            case "NoPermission":
            case "Forbidden.RAM":
                return false;   // 重试一百次也一样，必须改配置
            default:
                return true;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
    }

    private static void post(Runnable r) {
        MAIN.post(r);
    }
}
