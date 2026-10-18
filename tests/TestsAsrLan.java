// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 原样编译并执行生产入口；平台桩位于 asr_lan_regression.py，LAN 真正走 TCP。 */
public final class TestsAsrLan {
    private static Path fixtures;
    private static int assertions;
    private static int cases;
    private static final byte[] WAV = {82, 73, 70, 70, 0, 1, 2, 3};
    private static final byte[] LAN_KEY = new byte[32];
    private static final String ACCOUNT = "fixture-account";
    private static final String AAD = "agentlite-lan-v1|" + ACCOUNT;
    private static final String CLIENT_CURSOR = "lan_receive_cursor." + ACCOUNT + ".device-server";
    private static final Method MERGE = method("mergePeerRecords", Context.class, JSONArray.class, String.class);
    private static final Method COLLECT = method("collectRecordsForPeer", Context.class,
            int.class, JSONObject.class, boolean.class, JSONObject.class);
    private static final Method HANDLE = method("handleConn", Socket.class, Context.class);

    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Checked action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (Exception expected) { failed = true; }
        check(failed, message);
    }
    private static void run(String name, Checked action) throws Exception {
        action.run();
        cases++;
        System.out.println("PASS " + name);
    }
    private static Method method(String name, Class<?>... types) {
        try {
            Method result = LanSync.class.getDeclaredMethod(name, types);
            result.setAccessible(true);
            return result;
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }
    private static Context context(String name, String device) throws Exception {
        Path directory = Files.createTempDirectory(fixtures, name + "-");
        Context context = new Context(directory.toFile());
        new Prefs(context).sp.edit().putString("account_id", ACCOUNT)
                .putString("sync_device_id", device).putBoolean("lan_enabled", true)
                .putString("lan_key", java.util.Base64.getEncoder().encodeToString(LAN_KEY)).apply();
        return context;
    }
    private static Path history(Context context) { return context.filesRoot.toPath().resolve("task_history.jsonl"); }
    private static Path peers(Context context) { return context.filesRoot.toPath().resolve("peer_records.jsonl"); }
    private static void writeLines(Path file, List<String> lines) throws Exception {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
    private static List<String> lines(Path file) throws Exception {
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
    }
    private static List<String> localLines(String prefix, int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(new JSONObject().put("time", "fixture-time-" + i)
                .put("task", prefix + i).put("result", "中文结果\n第二行").toString());
        return lines;
    }
    private static JSONObject record(String id, String origin) {
        JSONObject result = new JSONObject().put("id", id).put("ts", "fixture-ts-" + id)
                .put("data", new JSONObject().put("task", id).put("result", "原始结果中文"));
        if (origin != null) result.put("origin_device_id", origin);
        return result;
    }
    private static List<String> peerLines(String prefix, int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(record(prefix + i, "device-original").toString());
        return lines;
    }
    private static JSONArray batch(String prefix, int count, String origin) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < count; i++) result.put(record(prefix + i, origin));
        return result;
    }
    private static int merge(Context context, JSONArray records, String origin) throws Exception {
        return (Integer) invoke(MERGE, context, records, origin);
    }
    private static JSONObject page(Context context, int limit, JSONObject cursor, boolean repeat) throws Exception {
        JSONObject response = new JSONObject().put("device_id", "device-local")
                .put("ts", System.currentTimeMillis() / 1000L).put("server_seq", Long.MAX_VALUE).put("merged", 0);
        return (JSONObject) invoke(COLLECT, context, limit, cursor, repeat, response);
    }
    private static String localId(String line) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.substring(0, 32);
    }

    private static final class Response {
        final int code;
        final String text;
        final boolean failWrite;
        Response(int code, String text, boolean failWrite) {
            this.code = code; this.text = text; this.failWrite = failWrite;
        }
    }
    private static final class Capture extends HttpURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final Response response;
        boolean disconnected;
        Capture(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public OutputStream getOutputStream() throws IOException {
            if (response.failWrite) throw new IOException("fixture connection failure");
            return body;
        }
        @Override public int getResponseCode() { return response.code; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(response.text.getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        String payload() { return new String(body.toByteArray(), StandardCharsets.UTF_8); }
    }
    private static final List<Capture> requests = new CopyOnWriteArrayList<>();
    private static volatile Response response;
    private static void reply(int code, String text) {
        requests.clear();
        response = new Response(code, text, false);
    }
    private static Capture onlyRequest() {
        check(requests.size() == 1, "一次调用只建立一个指定 HTTP 请求，actual=" + requests.size());
        Capture request = requests.get(0);
        check(request.disconnected, "连接在成功和失败路径都关闭");
        return request;
    }
    private static void installTransport() {
        URL.setURLStreamHandlerFactory(protocol -> {
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
            return new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    Capture capture = new Capture(url, response);
                    requests.add(capture);
                    return capture;
                }
            };
        });
    }
    private static void resetAsr() throws Exception {
        WhisperAsr.reset();
        Field local = AsrRouter.class.getDeclaredField("sLocal");
        local.setAccessible(true);
        local.set(null, null);
        reply(200, "{\"text\":\"远端转写 fixture\"}");
    }
    private static Prefs localPrefs(Context context) {
        Prefs prefs = new Prefs(context);
        prefs.sp.edit().putString("api_key", "fixture-main-payer-key")
                .putString("base_url", "https://main.example.invalid/v1")
                .putString("model", "fixture-main-model").putString("asr_base_url", "").apply();
        return prefs;
    }
    private static Prefs remotePrefs(Context context, String key) {
        Prefs prefs = localPrefs(context);
        prefs.sp.edit().putString("asr_base_url", " https://asr.example.invalid/v1/ ")
                .putString("asr_api_key", key).putString("asr_model", "fixture-asr-model").apply();
        return prefs;
    }
    private static void asrLocal() throws Exception {
        Context context = context("asr-local", "device-local");
        Prefs prefs = localPrefs(context);
        resetAsr();
        check("本地转写 fixture".equals(AsrRouter.transcribe(context, prefs, WAV)), "本地结果原样交给调用方");
        check(WhisperAsr.transcribeCalls == 1 && requests.isEmpty(), "本地只调用一次，不借主模型通道");
        resetAsr();
        WhisperAsr.result = "";
        check(AsrRouter.transcribe(context, prefs, WAV).isEmpty(), "本地空结果直接返回");
        check(WhisperAsr.transcribeCalls == 1 && requests.isEmpty(), "空结果不切到远端");
        resetAsr();
        WhisperAsr.library = false;
        fails(() -> AsrRouter.transcribe(context, prefs, WAV), "本地库缺失明确上抛");
        check(requests.isEmpty() && WhisperAsr.transcribeCalls == 0, "缺库不调用远端");
        resetAsr();
        WhisperAsr.model = false;
        fails(() -> AsrRouter.transcribe(context, prefs, WAV), "本地模型缺失明确上抛");
        check(requests.isEmpty() && WhisperAsr.loadCalls == 0, "缺模型不调用远端或加载");
        resetAsr();
        WhisperAsr.loads = false;
        fails(() -> AsrRouter.transcribe(context, prefs, WAV), "加载失败明确上抛");
        check(requests.isEmpty() && WhisperAsr.transcribeCalls == 0, "加载失败不切换实现");
        resetAsr();
        WhisperAsr.failure = new IOException("fixture local failure");
        fails(() -> AsrRouter.transcribe(context, prefs, WAV), "本地执行异常直接上抛");
        check(WhisperAsr.transcribeCalls == 1 && requests.isEmpty(), "异常不重试或切换");
    }
    private static void asrRemote() throws Exception {
        Context context = context("asr-remote", "device-local");
        Prefs prefs = remotePrefs(context, "fixture-asr-key");
        resetAsr();
        WhisperAsr.library = WhisperAsr.model = false;
        check("远端转写 fixture".equals(AsrRouter.transcribe(context, prefs, WAV)), "显式远端独立于本地可用性");
        Capture request = onlyRequest();
        check("https://asr.example.invalid/v1/audio/transcriptions".equals(request.getURL().toString()), "仅请求指定 ASR 端点");
        check("Bearer fixture-asr-key".equals(request.getRequestProperty("Authorization")), "仅携带 ASR 密钥");
        check(request.payload().contains("name=\"model\"\r\n\r\nfixture-asr-model\r\n"), "只发 ASR 模型，不收敛到主模型");
        check(request.getConnectTimeout() == 6000 && request.getReadTimeout() == 20000, "保留 ASR 短超时");
        check(WhisperAsr.instances == 0 && WhisperAsr.transcribeCalls == 0, "远端不创建本地实例");
        resetAsr();
        prefs = remotePrefs(context, "");
        AsrRouter.transcribe(context, prefs, WAV);
        check("Bearer ".equals(onlyRequest().getRequestProperty("Authorization")), "空 ASR key 不借主模型 key");
        resetAsr();
        reply(200, "{\"text\":\"\"}");
        check(AsrRouter.transcribe(context, prefs, WAV).isEmpty(), "远端空结果直接返回");
        onlyRequest();
        check(WhisperAsr.transcribeCalls == 0, "远端空结果不切到本地");
        for (int status : new int[] {400, 401, 402, 403, 429, 500}) {
            for (int attempt = 0; attempt < 2; attempt++) {
                resetAsr();
                reply(status, "{\"error\":{\"message\":\"fixture rejection\"}}");
                final Prefs selected = prefs;
                fails(() -> AsrRouter.transcribe(context, selected, WAV), "远端错误直接返回失败 status=" + status);
                onlyRequest();
                check(WhisperAsr.transcribeCalls == 0 && WhisperAsr.instances == 0,
                        "失败同次无第二请求、无本地；下一次仍只请求指定端点");
            }
        }
        resetAsr();
        reply(200, "not-json");
        final Prefs selected = prefs;
        fails(() -> AsrRouter.transcribe(context, selected, WAV), "格式错误直接失败");
        onlyRequest();
        check(WhisperAsr.transcribeCalls == 0, "格式错误不切到本地");
        resetAsr();
        response = new Response(200, "{}", true);
        fails(() -> AsrRouter.transcribe(context, selected, WAV), "网络写入失败直接上抛");
        onlyRequest();
        check(WhisperAsr.transcribeCalls == 0, "网络异常不切到本地");
        resetAsr();
        AsrRouter.warmUp(context);
        check(WhisperAsr.instances == 0 && WhisperAsr.loadCalls == 0, "远端模式不预热本地模型");
    }
    private static void asrConcurrent() throws Exception {
        Context context = context("asr-concurrent", "device-local");
        Prefs prefs = localPrefs(context);
        resetAsr();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) calls.add(() -> AsrRouter.transcribe(context, prefs, WAV));
            for (Future<String> result : workers.invokeAll(calls)) check("本地转写 fixture".equals(result.get()), "并发本地结果");
        } finally {
            workers.shutdown();
            check(workers.awaitTermination(2, TimeUnit.SECONDS), "ASR 测试线程已回收");
        }
        check(WhisperAsr.instances == 1 && WhisperAsr.transcribeCalls == 24 && requests.isEmpty(), "共享本地模型只初始化一次");
    }

    private static final class CountedMessages extends JSONArray {
        int encodes;
        @Override public String toString() { encodes++; return super.toString(); }
    }
    private static void imageDetection() throws Exception {
        String chatResult = "{\"choices\":[{\"message\":{\"content\":\"fixture chat\"}}]}";
        reply(200, chatResult);
        CountedMessages image = new CountedMessages();
        image.put(LlmClient.visionMsg("user", "看图", "data:image/jpeg;base64," + "A".repeat(2 << 20)));
        LlmClient client = new LlmClient("https://chat.example.invalid/v1", "fixture-chat-key", "fixture-text-model")
                .visionModel("fixture-vision-model");
        check("fixture chat".equals(client.chat(image)), "图消息实际执行 chat");
        JSONObject sent = new JSONObject(onlyRequest().payload());
        check("fixture-vision-model".equals(sent.optString("model")), "真实 image_url content 选择视觉模型");
        check(image.encodes == 1, "2MiB Base64 消息只在真正请求时编码一次，actual=" + image.encodes);
        reply(200, chatResult);
        CountedMessages text = new CountedMessages();
        text.put(new JSONObject().put("role", "user").put("image_url", "metadata-not-a-content-part")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text")
                        .put("text", "此处仅谈论 image_url 字段"))));
        text.put("invalid-message");
        text.put(new JSONObject().put("role", "user").put("content", "普通文字"));
        text.put(new JSONObject().put("role", "user").put("content", new JSONArray().put(null)
                .put("invalid-part").put(new JSONObject().put("type", "input_audio")
                        .put("image_url", "audio-metadata"))));
        client.chat(text);
        check("fixture-text-model".equals(new JSONObject(onlyRequest().payload()).optString("model")), "只检查 content.type，不因同名字字段误用视觉模型");
        check(text.encodes == 1, "文字消息也只序列化一次");
    }

    private static void batchDedup() throws Exception {
        Context context = context("lan-batch", "device-local");
        writeLines(peers(context), peerLines("record-", 1200));
        JSONArray incoming = batch("record-", 1600, "device-original");
        incoming.put(record("record-1300", "device-relay"));
        incoming.put(record("new-direct", null));
        incoming.put(record("", "device-original"));
        incoming.put(new JSONObject().put("id", "no-data").put("origin_device_id", "device-original"));
        incoming.put("invalid-item");
        context.directoryLookups.set(0);
        check(merge(context, incoming, "device-sender") == 401, "一批同时去重既存 ID 和批内重复 ID");
        check(context.directoryLookups.get() == 2, "1605 条输入只解析一次读文件路径和一次写路径，不逐条扫描");
        List<String> stored = lines(peers(context));
        check(stored.size() == 1601, "最终只有 1601 个有效唯一 ID");
        Set<String> ids = new HashSet<>();
        for (String line : stored) {
            JSONObject item = new JSONObject(line);
            check(ids.add(item.optString("id")), "稳定 ID 不重复");
            check(item.optString("ts").equals("fixture-ts-" + item.optString("id")), "时间戳原样保留");
            check(item.getJSONObject("data").optString("task").equals(item.optString("id")), "原始 data 不变");
            check(item.optString("origin_device_id").equals("new-direct".equals(item.optString("id"))
                    ? "device-sender" : "device-original"), "转发保留真正 origin，而不是当前发送设备");
        }
        context.directoryLookups.set(0);
        check(merge(context, incoming, "device-other-sender") == 0, "整批重放不追加");
        check(context.directoryLookups.get() == 2 && lines(peers(context)).size() == 1601, "重放扫描仍是常数次路径读取");
        Context missingOrigin = context("lan-no-origin", "device-local");
        check(merge(missingOrigin, new JSONArray().put(record("unknown-origin", null)), "") == 0, "下行来源为空不污染记录");
        Context storageFailure = context("lan-read-failure", "device-local");
        Files.createDirectory(peers(storageFailure));
        fails(() -> merge(storageFailure, batch("failed-", 2, "device-original"), ""), "读取失败上抛，不当成空文件");
    }
    private static void concurrentDedup() throws Exception {
        Context context = context("lan-concurrent", "device-local");
        JSONArray incoming = batch("same-", 180, "device-original");
        ExecutorService workers = Executors.newFixedThreadPool(6);
        int total = 0;
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) calls.add(() -> merge(context, incoming, "device-relay"));
            for (Future<Integer> result : workers.invokeAll(calls)) total += result.get();
        } finally {
            workers.shutdown();
            check(workers.awaitTermination(2, TimeUnit.SECONDS), "LAN 合并测试线程已回收");
        }
        check(total == 180 && lines(peers(context)).size() == 180, "并发同批只写入一份");
        check(LanSync.pendingPeerRecords(context).size() == 180, "云转发读取完整写入快照");
    }
    private static void boundedPaging() throws Exception {
        Context context = context("lan-page", "device-local");
        List<String> local = localLines("local-", 650);
        writeLines(history(context), local);
        writeLines(peers(context), peerLines("forwarded-", 750));
        JSONObject cursor = new JSONObject();
        Set<String> seen = new HashSet<>();
        int pageCount = 0;
        boolean more;
        do {
            JSONObject response = page(context, 500, cursor, false);
            JSONArray records = response.getJSONArray("records");
            check(records.length() <= 500, "合并后的最终总数不超过 500");
            int forwarded = 0;
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                check(seen.add(item.optString("id")), "新协议分页不重发已消费记录");
                if ("device-original".equals(item.optString("origin_device_id"))) forwarded++;
            }
            check(forwarded > 0, "本机记录超过 limit 也不会饿死转发数据");
            cursor = response.getJSONObject("lan_cursor");
            more = response.optBoolean("lan_has_more");
            pageCount++;
            check(pageCount <= 3, "1400 条在三页内完整交付");
        } while (more);
        check(seen.size() == 1400 && pageCount == 3, "650 本机 + 750 转发完整分页");
        for (String line : local) check(seen.contains(localId(line)), "本机内容哈希稳定");
        for (int i = 0; i < 750; i++) check(seen.contains("forwarded-" + i), "第三设备转发 ID 原样交付");
        check(page(context, 500, cursor, false).getJSONArray("records").length() == 0, "追平游标不重发整份文件");
        Files.write(history(context), localLines("later-", 1), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        JSONObject later = page(context, 500, cursor, false);
        check(later.getJSONArray("records").length() == 1, "追平后只交付新增本机记录");
        JSONObject progressed = later.getJSONObject("lan_cursor");
        // 长度相同的日志替换也需借稳定 ID 识别，而不只是检查 offset 越界。
        writeLines(history(context), localLines("replacement-", 651));
        JSONObject replaced = page(context, 500, progressed, false);
        check(replaced.getJSONArray("records").length() == 500, "日志同长度替换重置该来源的游标");
        check(replaced.getJSONObject("lan_cursor").optInt("peer", -1) == 750, "仅重置替换的文件，不重发另一来源");
        writeLines(history(context), localLines("truncated-", 1));
        JSONObject truncated = page(context, 500, progressed, false);
        check(truncated.getJSONArray("records").length() == 1, "日志清空/缩短后重置该来源");
        Context duplicate = context("lan-cross-duplicate", "device-local");
        List<String> one = localLines("same-data-", 1);
        writeLines(history(duplicate), one);
        writeLines(peers(duplicate), Arrays.asList(record(localId(one.get(0)), "device-original").toString()));
        check(page(duplicate, 500, new JSONObject(), false).getJSONArray("records").length() == 1,
                "最终合并页也去掉跨来源重复 ID");
    }
    private static void fairOneRecordAndLegacy() throws Exception {
        Context context = context("lan-fair", "device-local");
        writeLines(history(context), localLines("always-growing-", 25));
        writeLines(peers(context), peerLines("forward-one-", 25));
        JSONObject cursor = new JSONObject();
        for (int i = 0; i < 8; i++) {
            JSONObject response = page(context, 1, cursor, false);
            JSONArray records = response.getJSONArray("records");
            check(records.length() == 1, "limit=1 精确有界");
            check(records.getJSONObject(0).optString("origin_device_id").equals(i % 2 == 0
                    ? "device-local" : "device-original"), "limit=1 持续新增本机记录仍交替送转发数据");
            cursor = response.getJSONObject("lan_cursor");
            Files.write(history(context), localLines("new-at-" + i + "-", 5), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        Context legacy = context("lan-legacy-page", "device-local");
        writeLines(history(legacy), localLines("old-local-", 650));
        writeLines(peers(legacy), peerLines("old-forward-", 750));
        cursor = new JSONObject();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            JSONObject response = page(legacy, 500, cursor, true);
            JSONArray records = response.getJSONArray("records");
            check(records.length() <= 500, "旧对端最终回包也有总上限");
            for (int j = 0; j < records.length(); j++) seen.add(records.getJSONObject(j).optString("id"));
            cursor = response.getJSONObject("lan_cursor");
        }
        check(seen.size() == 1400, "旧对端通过服务器轮转覆盖 limit 之后的所有转发记录");
    }

    private static void byteBoundedPaging() throws Exception {
        Context context = context("lan-bytes", "device-local");
        String largeText = "界".repeat(530000); // UTF-8 1.59MiB：单条可传，合并两条超过信封上限。
        List<String> local = new ArrayList<>();
        List<String> forwarded = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            local.add(new JSONObject().put("time", "bytes-time").put("task", "large-local-" + i)
                    .put("result", largeText).toString());
            JSONObject item = record("large-forward-" + i, "device-original");
            item.getJSONObject("data").put("result", largeText);
            forwarded.add(item.toString());
        }
        writeLines(history(context), local);
        writeLines(peers(context), forwarded);
        JSONObject cursor = new JSONObject();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            JSONObject response = page(context, 500, cursor, false);
            JSONArray records = response.getJSONArray("records");
            check(records.length() == 1, "按 UTF-8 + 元数据 + AEAD/Base64 字节预算截页，而非仅条数");
            JSONObject item = records.getJSONObject(0);
            check(seen.add(item.optString("id")), "字节分页不跳过/重复记录");
            check(item.optString("origin_device_id").equals(i % 2 == 0 ? "device-local" : "device-original"),
                    "本页放不下的来源下页优先；大记录也不饿死转发");
            check(item.getJSONObject("data").optString("result").equals(largeText), "大型中文 UTF-8 data 完整保留");
            String envelope = LanSync.seal(LAN_KEY, AAD, response.toString());
            check(envelope.length() <= (4 << 20), "实际最终 AES-GCM/Base64 信封不超过 4MiB");
            check(new JSONObject(LanSync.open(LAN_KEY, AAD, envelope)).getJSONArray("records").length() == 1,
                    "字节有界回包实际解密并解析");
            cursor = response.getJSONObject("lan_cursor");
        }
        check(seen.size() == 6 && page(context, 500, cursor, false).getJSONArray("records").length() == 0,
                "大记录全量交付且追平后不重发");
        Context oversize = context("lan-oversized-item", "device-local");
        writeLines(history(oversize), Arrays.asList(new JSONObject().put("task", "oversize")
                .put("result", "X".repeat(4 << 20)).toString()));
        writeLines(peers(oversize), peerLines("still-deliverable-", 1));
        JSONObject delivered = page(oversize, 500, new JSONObject(), false);
        check(delivered.getJSONArray("records").length() == 1
                        && delivered.getJSONArray("records").getJSONObject(0).optString("id").equals("still-deliverable-0"),
                "单条超大本机记录不阻断另一来源的可交付记录");
        check(delivered.getJSONObject("lan_cursor").optInt("local", -1) == 0, "超大记录没有被静默消费");
        fails(() -> page(oversize, 500, delivered.getJSONObject("lan_cursor"), false),
                "仅剩单条超过协议上限时显式失败，不静默丢弃或永久返回空成功页");
        Context metadata = context("lan-oversized-meta", "device-local");
        JSONObject hugeMetadata = new JSONObject().put("device_id", "D".repeat(4 << 20));
        fails(() -> invoke(COLLECT, metadata, 500, new JSONObject(), false, hugeMetadata),
                "最终预算也包括真实响应元数据，而不是只计算 records");
        Context server = context("lan-byte-server", "device-server");
        Context client = context("lan-byte-client", "device-client");
        writeLines(history(server), local.subList(0, 2));
        writeLines(peers(server), forwarded.subList(0, 2));
        try (Listener listener = new Listener(server)) {
            for (int i = 0; i < 4; i++) check(LanSync.syncWithPeer(client, "127.0.0.1", listener.port(),
                    "device-server", null, 0) == 1, "真实 TCP 按字节分页且既有 4MiB 接收守卫通过");
            check(LanSync.pendingPeerRecords(client).size() == 4, "真实 TCP 大记录四页完整存入");
            for (String line : lines(peers(client))) check(new JSONObject(line).getJSONObject("data")
                    .optString("result").equals(largeText), "真实 TCP 大记录 UTF-8 内容未截断");
        }
    }

    private static final class Listener implements AutoCloseable {
        final ServerSocket socket;
        final Thread worker;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Listener(Context context) throws Exception {
            socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket connection = socket.accept();
                        invoke(HANDLE, connection, context);
                    } catch (Throwable error) {
                        if (!socket.isClosed()) failure.set(error);
                        break;
                    }
                }
            }, "asr-lan-test-listener");
            worker.start();
        }
        int port() { return socket.getLocalPort(); }
        @Override public void close() throws Exception {
            socket.close();
            worker.join(2000);
            check(!worker.isAlive(), "真实 LAN socket/监听线程已关闭");
            if (failure.get() != null) throw new AssertionError("LAN listener failed", failure.get());
        }
    }
    private static JSONObject legacyExchange(int port, String device, long since) throws Exception {
        JSONObject request = new JSONObject().put("ts", System.currentTimeMillis() / 1000L)
                .put("device_id", device).put("since", since);
        String envelope = LanSync.seal(LAN_KEY, AAD, request.toString());
        byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(4000);
            OutputStream output = socket.getOutputStream();
            output.write(("POST /lan/v1/sync HTTP/1.1\r\nHost: fixture\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
            byte[] raw = socket.getInputStream().readAllBytes();
            String response = new String(raw, StandardCharsets.UTF_8);
            int separator = response.indexOf("\r\n\r\n");
            check(response.startsWith("HTTP/1.1 200") && separator > 0, "旧协议真实 TCP 响应 200");
            String responseEnvelope = response.substring(separator + 4);
            check(responseEnvelope.length() < (4 << 20), "普通回包在既有信封接收上限内");
            return new JSONObject(LanSync.open(LAN_KEY, AAD, responseEnvelope));
        }
    }
    private static void tcpRoundtrip() throws Exception {
        Context server = context("lan-server", "device-server");
        Context client = context("lan-client", "device-client");
        writeLines(history(server), localLines("tcp-local-", 620));
        writeLines(peers(server), peerLines("tcp-forward-", 600));
        JSONArray push = batch("direct-push-", 4, null);
        try (Listener listener = new Listener(server)) {
            int first = LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", push, Long.MAX_VALUE);
            check(first >= 500 && first <= 504, "真实 TCP 第一页最多 500 下行 + 4 上行");
            SharedPreferences prefs = new Prefs(client).sp;
            JSONObject firstCursor = new JSONObject(prefs.getString(CLIENT_CURSOR, "{}"));
            check(firstCursor.optInt("local", 0) > 0 && firstCursor.optInt("peer", 0) > 0,
                    "云 since=Long.MAX_VALUE 不过滤 LAN；LAN 独立位置推进两来源");
            int second = LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", push, Long.MAX_VALUE);
            check(second > 0 && second <= 500, "真实 TCP 第二页上行重放幂等");
            int third = LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", push, Long.MAX_VALUE);
            check(third > 0 && third <= 500, "真实 TCP 第三页限制最终下行总量");
            check(LanSync.pendingPeerRecords(client).size() == 1224, "真实 TCP 620 本机 + 600 转发 + 4 推送完整合并");
            check(LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", push, Long.MAX_VALUE) == 0,
                    "真实 TCP 追平后没有重复读回");
            Set<String> stored = new HashSet<>();
            for (String line : lines(peers(client))) {
                JSONObject item = new JSONObject(line);
                check(stored.add(item.optString("id")), "真实 TCP 最终存储幂等");
                if (item.optString("id").startsWith("tcp-forward-"))
                    check("device-original".equals(item.optString("origin_device_id")), "多跳转发不覆盖真正 origin");
                if (item.optString("id").startsWith("direct-push-"))
                    check("device-client".equals(item.optString("origin_device_id")), "直接推送无 origin 时补发送设备");
            }
            Set<String> legacyIds = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                JSONObject response = legacyExchange(listener.port(), "device-old-client", Long.MAX_VALUE);
                check(response.optLong("server_seq", 0) == Long.MAX_VALUE, "旧 cloud cursor 字段语义保留");
                JSONArray records = response.getJSONArray("records");
                check(records.length() <= 500, "旧对端真实回包也不超过 500");
                for (int j = 0; j < records.length(); j++) legacyIds.add(records.getJSONObject(j).optString("id"));
            }
            check(legacyIds.size() == 1224, "旧协议真实 TCP 多页轮转覆盖全量记录");
            String committed = prefs.getString(CLIENT_CURSOR, "");
            server.failDirectory = true;
            check(LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", null, 0) == -1,
                    "真实 TCP 服务端存储失败显式失败");
            check(committed.equals(prefs.getString(CLIENT_CURSOR, "")), "响应失败不推进客户端游标");
            server.failDirectory = false;
            Files.write(history(server), localLines("after-failure-", 1), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            client.failDirectory = true;
            check(LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", null, 0) == -1,
                    "客户端合并存储失败显式失败");
            check(committed.equals(prefs.getString(CLIENT_CURSOR, "")), "合并失败不提交服务器返回的新游标");
            client.failDirectory = false;
            check(LanSync.syncWithPeer(client, "127.0.0.1", listener.port(), "device-server", null, 0) == 1,
                    "恢复存储后重试同一未提交页，不丢记录");
        }
    }
    public static void main(String[] args) throws Exception {
        fixtures = Path.of(args[0]).toAbsolutePath().normalize();
        check(Files.isDirectory(fixtures), "fixture root 已由回归入口创建");
        Arrays.fill(LAN_KEY, (byte) 37);
        installTransport();
        run("F05 local: success/empty/library/model/load/error, no channel switch", TestsAsrLan::asrLocal);
        run("F05 remote: explicit endpoint/key/model, one request on all failures", TestsAsrLan::asrRemote);
        run("F05 concurrent local: one shared model instance", TestsAsrLan::asrConcurrent);
        run("F09 actual chat: 2MiB message encoded once; content type only", TestsAsrLan::imageDetection);
        run("F11 batch: one ID scan, stable id/origin, batch replay", TestsAsrLan::batchDedup);
        run("F11 concurrent batch: one append per stable id", TestsAsrLan::concurrentDedup);
        run("F11 bounded pages: 1400 records, combined cap, cursor reset", TestsAsrLan::boundedPaging);
        run("F11 limit=1 fairness and legacy rotation", TestsAsrLan::fairOneRecordAndLegacy);
        run("F11 final 4MiB envelope: UTF-8 budget, deferred-source fairness, real TCP", TestsAsrLan::byteBoundedPaging);
        run("F11 real TCP/AES-GCM: page/merge/legacy/error cursor commit", TestsAsrLan::tcpRoundtrip);
        System.out.println("ASR/LAN regression: " + cases + " cases, " + assertions + " assertions PASS");
    }
}
