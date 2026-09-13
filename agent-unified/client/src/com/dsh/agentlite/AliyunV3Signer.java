// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 阿里云 OpenAPI V3 签名（ACS3-HMAC-SHA256）—— 纯标准库实现，**不含任何 android.* API**。
 *
 * <p>之所以刻意不碰 Android：这段代码只要有一个字节签错，线上表现就是一句没头没脑的
 * {@code SignatureDoesNotMatch}，而排查它必须反复真机试错。抽成纯 Java 就能在桌面 JVM 上
 * 用固定向量反复回归（见 {@code tests/AliyunV3SignerTest.java}），改一行跑一秒。
 *
 * <p><b>算法链</b>（源：百炼官方《ROA风格请求体&签名机制》
 * https://help.aliyun.com/zh/model-studio/roa-style-request-body-signature-mechanism ）：
 * <pre>
 * CanonicalRequest = HTTPRequestMethod + '\n' + CanonicalURI + '\n' + CanonicalQueryString + '\n'
 *                  + CanonicalHeaders + '\n' + SignedHeaders + '\n' + HashedRequestPayload
 * StringToSign     = "ACS3-HMAC-SHA256" + '\n' + HexEncode(SHA256(CanonicalRequest))
 * Signature        = HexEncode(HMAC-SHA256(AccessKeySecret, StringToSign))
 * Authorization    = "ACS3-HMAC-SHA256 Credential=<AK ID>,SignedHeaders=<...>,Signature=<...>"
 * </pre>
 *
 * <p><b>两级签名，别搞混</b>：先对规范化请求做 SHA256（摘要，不是 MAC），再拿这个摘要串做
 * HMAC-SHA256。V2 那套「AccessKeySecret + "&" 拼接、HMAC-SHA1」在这里**不适用**。
 *
 * <p><b>安全红线</b>：本类只做纯函数计算，**不存储、不打印、不落盘 AK/SK**。
 * secret 用完即随栈帧释放；{@link Signed} 只缓存签名结果，不含密钥。
 * 上层存放 AK 的密文由 {@code AkStore}（待实现）用 Android Keystore 保护。
 */
public final class AliyunV3Signer {

    /** 签名协议。V3 只支持 ACS3-HMAC-SHA256，MD5/SHA1 系已废弃。 */
    public static final String ALGORITHM = "ACS3-HMAC-SHA256";

    private AliyunV3Signer() {}

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 计算签名并返回完整结果（含中间串，便于失败时定位是哪一环错的）。
     *
     * @param req 请求描述；{@link Request#body} 为 null 视为空串
     * @return 签名结果，可直接用于组装 HTTP 请求
     * @throws Exception 摘要/HMAC 不可用时（实际不会发生，标准算法必然存在）
     */
    public static Signed sign(Request req) throws Exception {
        String body = req.body == null ? "" : req.body;
        String contentSha256 = hex(sha256(body.getBytes(StandardCharsets.UTF_8)));

        // x-acs-content-sha256 必须与实际正文一致，且必须参与签名。
        // 由本方法统一写入，避免调用方漏填或填错——这是最常见的签名失败原因。
        TreeMap<String, String> headers = new TreeMap<>(req.headers);
        headers.put("x-acs-content-sha256", contentSha256);
        if (req.date != null && !req.date.isEmpty()) {
            headers.put("x-acs-date", req.date);
        }
        if (req.action != null && !req.action.isEmpty()) {
            headers.put("x-acs-action", req.action);
        }
        if (req.version != null && !req.version.isEmpty()) {
            headers.put("x-acs-version", req.version);
        }
        // host 必须进签名，否则网关侧校验不过
        headers.put("host", req.host);

        String canonicalRequest = canonicalRequest(
                req.method, req.uri, req.query, headers, contentSha256);
        String stringToSign = ALGORITHM + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        String signature;
        byte[] keyBytes = charsToUtf8(req.accessKeySecret);
        try {
            byte[] mac = hmacSha256(keyBytes, stringToSign.getBytes(StandardCharsets.UTF_8));
            signature = hex(mac);
        } finally {
            java.util.Arrays.fill(keyBytes, (byte) 0);   // 密钥字节用完即抹
        }

        String signedHeaders = String.join(";", headers.keySet());
        String authorization = ALGORITHM
                + " Credential=" + req.accessKeyId
                + ",SignedHeaders=" + signedHeaders
                + ",Signature=" + signature;

        return new Signed(headers, signedHeaders, signature, authorization,
                canonicalRequest, stringToSign, contentSha256);
    }

    /**
     * 构造规范化请求字符串。
     *
     * <p>按官方伪代码逐行拼接。抽成可见方法是为了单测能逐段比对——签名错了的时候，
     * 「哪一行不对」比「签名不对」有用得多。
     */
    public static String canonicalRequest(String method,
                                         String uri,
                                         Map<String, String> query,
                                         Map<String, String> headers,
                                         String contentSha256) {
        TreeMap<String, String> sortedHeaders = new TreeMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sortedHeaders.put(e.getKey().toLowerCase(java.util.Locale.US),
                    e.getValue() == null ? "" : e.getValue().trim());
        }

        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : sortedHeaders.entrySet()) {
            canonicalHeaders.append(percentEncode(e.getKey())).append(':')
                    .append(e.getValue()).append('\n');
        }
        String signedHeaders = String.join(";", sortedHeaders.keySet());

        return method.toUpperCase(java.util.Locale.US) + "\n"
                + canonicalUri(uri) + "\n"
                + canonicalQueryString(query) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + contentSha256;
    }

    /** 规范化 URI：按 {@code /} 分段，逐段 RFC3986 编码；空路径用 {@code /}。 */
    public static String canonicalUri(String uri) {
        if (uri == null || uri.isEmpty()) return "/";
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            sb.append('/').append(percentEncode(seg));
        }
        // split 对前导 '/' 会产生一个空段，导致 "//xxx"，修掉
        String out = sb.toString();
        return out.startsWith("//") ? out.substring(1) : out;
    }

    /** 规范化查询串：key 升序，key/value 均 RFC3986 编码，用 {@code &} 连接。 */
    public static String canonicalQueryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            sorted.put(percentEncode(e.getKey()), percentEncode(e.getValue() == null ? "" : e.getValue()));
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join("&", parts);
    }

    /**
     * RFC3986 百分号编码。
     *
     * <p>只放行 {@code A-Z a-z 0-9 - _ . ~}，其余一律 {@code %XX}（大写十六进制）。
     * 注意空格编成 {@code %20} 而不是 {@code +}——用 {@code URLEncoder} 会得到 {@code +}，
     * 签名必然对不上。UTF-8 字节逐字节编码。
     */
    public static String percentEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 摘要原语
    // ------------------------------------------------------------------

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /**
     * char[] → UTF-8 字节。
     *
     * <p>刻意不经 {@code new String(chars)}：那会产生一个不可清零的明文 String。
     * 这里逐码点手工编码，中间不出现 String。
     */
    public static byte[] charsToUtf8(char[] chars) {
        if (chars == null) return new byte[0];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(chars.length * 2);
        for (int i = 0; i < chars.length; i++) {
            int c = chars[i];
            if (c < 0x80) {
                bos.write(c);
            } else if (c < 0x800) {
                bos.write(0xC0 | (c >> 6));
                bos.write(0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate((char) c) && i + 1 < chars.length
                    && Character.isLowSurrogate(chars[i + 1])) {
                int cp = Character.toCodePoint((char) c, chars[++i]);
                bos.write(0xF0 | (cp >> 18));
                bos.write(0x80 | ((cp >> 12) & 0x3F));
                bos.write(0x80 | ((cp >> 6) & 0x3F));
                bos.write(0x80 | (cp & 0x3F));
            } else {
                bos.write(0xE0 | (c >> 12));
                bos.write(0x80 | ((c >> 6) & 0x3F));
                bos.write(0x80 | (c & 0x3F));
            }
        }
        return bos.toByteArray();
    }

    /** 小写十六进制（官方要求 HexEncode 用小写）。 */
    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /** 一次待签名请求的描述。字段名与官方公共请求头一一对应。 */
    public static final class Request {
        public final String accessKeyId;
        /** AccessKeySecret。**只在本方法栈内使用，禁止外传/记录。** 用 char[] 而非 String：
         *  String 不可清零，密钥明文会一直留在堆上直到 GC；char[] 用完可立刻抹掉。 */
        public final char[] accessKeySecret;
        public final String method;
        /** Endpoint，同时作为 host 头参与签名，如 {@code bailian.aliyuncs.com}。 */
        public final String host;
        /** 资源路径，如 {@code /modelstudio/apikeys}。 */
        public final String uri;
        /** API 名称，写入 {@code x-acs-action}。 */
        public final String action;
        /** API 版本，写入 {@code x-acs-version}。 */
        public final String version;
        /** ISO8601 UTC 时间，写入 {@code x-acs-date}，格式 {@code yyyy-MM-dd'T'HH:mm:ss'Z'}。 */
        public final String date;
        /** 查询参数（会被规范化排序）。 */
        public final Map<String, String> query;
        /** 请求体（JSON 串），null 视为空串。 */
        public final String body;
        /** 额外请求头；公共头由 {@link #sign} 统一补齐，不必在此重复。 */
        public final Map<String, String> headers;

        public Request(String accessKeyId, char[] accessKeySecret, String method, String host,
                       String uri, String action, String version, String date,
                       Map<String, String> query, String body, Map<String, String> headers) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.method = method;
            this.host = host;
            this.uri = uri;
            this.action = action;
            this.version = version;
            this.date = date;
            this.query = query;
            this.body = body;
            this.headers = headers == null ? new TreeMap<>() : headers;
        }
    }

    /** 签名结果。含中间串，便于出问题时直接比对。 */
    public static final class Signed {
        /** 实际参与签名的全部请求头（已含公共头），发送时照此设置。 */
        public final Map<String, String> headers;
        public final String signedHeaders;
        public final String signature;
        /** 直接塞进 HTTP 的 {@code Authorization} 头。 */
        public final String authorization;
        /** 中间串，仅供调试/单测。 */
        public final String canonicalRequest;
        public final String stringToSign;
        public final String contentSha256;

        Signed(Map<String, String> headers, String signedHeaders, String signature,
               String authorization, String canonicalRequest, String stringToSign,
               String contentSha256) {
            this.headers = headers;
            this.signedHeaders = signedHeaders;
            this.signature = signature;
            this.authorization = authorization;
            this.canonicalRequest = canonicalRequest;
            this.stringToSign = stringToSign;
            this.contentSha256 = contentSha256;
        }
    }
}
