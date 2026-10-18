// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** 账号服务唯一 JSON HTTP 底座；同步、账号、远程指令只组织各自的业务载荷。 */
final class AccountHttp {
    private AccountHttp() { }

    static JSONObject request(String method, String url, String body,
                              String token, String deviceId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                // 缓存同一内置 CA 的工厂；构建失败直接抛错，主机名校验保持默认。
                ((HttpsURLConnection) conn).setSSLSocketFactory(TlsPin.factory());
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (deviceId != null && !deviceId.isEmpty()) conn.setRequestProperty("X-Device-Id", deviceId);
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                if (payload.length > (8 << 20)) throw new IllegalArgumentException("账号请求体超过8MiB");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(payload); }
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) throw parseError(code, text);
            return new JSONObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ApiException parseError(int code, String text) {
        String kind = "", message = "";
        try {
            JSONObject error = new JSONObject(text);
            kind = error.optString("error", "");
            message = error.optString("message", "");
        } catch (Exception ignored) { }
        if (message.isEmpty()) message = text == null ? "" : text.trim();
        if (message.length() > 200) message = message.substring(0, 200);
        return new ApiException(code, kind, message);
    }
}
