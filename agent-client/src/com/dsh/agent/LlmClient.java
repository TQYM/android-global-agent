package com.dsh.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 OpenAI 兼容客户端（HttpURLConnection，零第三方依赖）。
 * App 内走系统 DNS，无需 agentd-go 的 IP 钉线。
 */
public class LlmClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private String visionModel;   // 含图消息时切换（纯文本模型配视觉搭档）

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    public LlmClient visionModel(String vm) { visionModel = vm; return this; }

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

    /** 一次 chat-completion，返回 assistant 文本。 */
    public String chat(JSONArray messages) throws Exception {
        boolean hasImage = messages.toString().contains("\"image_url\"");
        String useModel = (hasImage && visionModel != null && !visionModel.isEmpty())
                ? visionModel : model;
        JSONObject body = new JSONObject()
                .put("model", useModel)
                .put("messages", messages)
                .put("temperature", 0.1)
                .put("max_tokens", 300);
        if (baseUrl.contains("bigmodel.cn")) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
        }
        JSONObject resp = post("/chat/completions", body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
        if (resp.has("error")) {
            throw new Exception("API 错误: " + resp.getJSONObject("error").optString("message"));
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

    private JSONObject post(String path, byte[] payload, String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", contentType);
        conn.getOutputStream().write(payload);
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        conn.disconnect();
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
