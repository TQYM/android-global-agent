// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** 真实编译账号/同步/远程状态机；只有平台、传输和执行边界使用测试桩。 */
public final class TestsAccountSync {
    private static final String SERVER = "http://account-fixture.invalid";
    private static final String PUSH = "/api/v1/sync/push", CONFIG = "/api/v1/config";
    private static final String COMMANDS = "/api/v1/device/commands", ACK = "/api/v1/device/command/ack";
    private static Path root;
    private static int checks, cases, failures, directories;
    private interface Case { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        boolean equal = expected instanceof Number && actual instanceof Number
                ? ((Number) expected).longValue() == ((Number) actual).longValue()
                : Objects.equals(expected, actual);
        check(equal, message + " expected=" + expected + " actual=" + actual);
    }
    private static Throwable throwsError(Class<? extends Throwable> type, Case action, String message) {
        try { action.run(); }
        catch (Throwable error) {
            check(type.isInstance(error), message + ": unexpected " + error);
            return error;
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName());
    }
    private static void scenario(String name, Case action) throws Exception {
        cases++;
        resetRemote(); Net.reset();
        try {
            action.run(); Net.verify();
            System.out.println("[PASS] " + name);
        } catch (Throwable error) {
            failures++;
            System.err.println("[FAIL] " + name + ": " + error);
            error.printStackTrace(System.err);
        } finally {
            RemoteCommand.stopPolling();
            for (Wire wire : Net.wires) if (wire.disconnected == 0) wire.connection.disconnect();
        }
    }
    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void resetRemote() throws Exception {
        RemoteCommand.stopPolling();
        field(RemoteCommand.class, "sPending").set(null, null);
        AgentEngine.get(null).reset(); AgentA11yService.available = true;
        LanSync.reset(); TlsPin.fail = false; TlsPin.calls = 0;
    }
    private static void poll(Context ctx) throws Exception {
        long generation = field(RemoteCommand.class, "sPollGeneration").getLong(null);
        invoke(RemoteCommand.class, "pollOnce", new Class<?>[]{Context.class, long.class}, ctx, generation);
    }
    private static int[] push(Context ctx, boolean history) throws Exception {
        return (int[]) invoke(SyncClient.class, "pushBatch",
                new Class<?>[]{Context.class, Prefs.class, String.class, boolean.class},
                ctx, new Prefs(ctx), SERVER, history);
    }
    private static boolean config(Context ctx) throws Exception {
        return (Boolean) invoke(SyncClient.class, "pullConfig",
                new Class<?>[]{Context.class, Prefs.class, String.class}, ctx, new Prefs(ctx), SERVER);
    }
    private static Context context() throws Exception {
        Path dir = root.resolve("case-" + (++directories)).normalize();
        if (!dir.getParent().equals(root)) throw new AssertionError("fixture path outside temporary root");
        Files.createDirectory(dir);
        Context ctx = new Context(dir.toFile()); Prefs p = new Prefs(ctx);
        p.setSyncEnabled(false); p.setSyncServer(SERVER + "///");
        check(p.saveAccount("account-A", "fixture-A", "fixture-token-A", 9, 0), "fixture login");
        p.setSyncDeviceId("device-A"); cursors(p, 0, 0, 0); return ctx;
    }
    private static void cursors(Prefs p, int history, int peer, int memory) {
        p.sp.edit().putInt("sync_history_index", history).putInt("sync_peer_index", peer)
                .putInt("sync_memory_index", memory).apply();
    }
    private static void cursorEq(Prefs p, int history, int peer, int memory, String label) {
        eq(history, p.syncHistoryIndex(), label + " history");
        eq(peer, p.sp.getInt("sync_peer_index", -1), label + " peer");
        eq(memory, p.syncMemoryIndex(), label + " memory");
    }
    private static void histories(Context ctx, int count) {
        for (int i = 0; i < count; i++)
            TaskHistory.append(ctx, "fixture-task-" + i, "完成", "detail-" + i, i, 10000L + i, "history-command-" + i);
    }
    private static void memories(Context ctx, int count) {
        for (int i = 0; i < count; i++) MemoryStore.add(ctx, "fixture-memory-" + i);
    }
    private static JSONObject peer(int index) {
        return new JSONObject().put("id", "peer-id-" + index).put("ts", "10-10 12:00:00")
                .put("origin_device_id", "origin-peer").put("data", new JSONObject()
                .put("task", "peer-task-" + index).put("run_id", 20000L + index));
    }
    private static void peers(Context ctx, List<String> lines) throws Exception {
        Files.write(new File(ctx.getFilesDir(), "fixture_peer_records.jsonl").toPath(), lines, StandardCharsets.UTF_8);
    }
    private static void peers(Context ctx, int count) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) lines.add(peer(i).toString());
        peers(ctx, lines);
    }
    private static JSONObject complete(int records) {
        return new JSONObject().put("accepted", records).put("duplicates", 0).put("rejected", 0);
    }
    private static JSONObject cfg(int version, JSONObject values) {
        return new JSONObject().put("config", new JSONObject().put("version", version).put("values", values));
    }
    private static String key(String account, String device) { return "remote_ack." + account + ":" + device; }
    private static JSONObject pending(Context ctx, String account, String device) {
        String raw = new Prefs(ctx).sp.getString(key(account, device), "");
        check(!raw.isEmpty(), "persistent pending exists for " + account + ":" + device);
        return new JSONObject(raw);
    }
    private static JSONObject pending(Context ctx) { return pending(ctx, "account-A", "device-A"); }
    private static JSONObject command(String id, String task) {
        return new JSONObject().put("command_id", id).put("from_device", "sender-device").put("task", task);
    }
    private static Reply inbox(String id) {
        return Net.reply("GET", COMMANDS, 200, new JSONObject().put("commands",
                new JSONArray().put(command(id, "execute " + id))).toString());
    }
    private static Reply ackOk() { return Net.reply("POST", ACK, 200, "{}"); }
    private static void claim(Context ctx, String id) throws Exception {
        inbox(id); ackOk(); poll(ctx);
        eq(1, AgentEngine.get(ctx).executions, "one command executed");
        eq("running", pending(ctx).optString("status"), "persist running before execution");
    }

    private static void httpCases() throws Exception {
        scenario("HTTP UTF-8 fixed length, headers, timeouts, close/disconnect", () -> {
            String text = "中文 payload Ω";
            Reply r = Net.reply("POST", "/json", 201, new JSONObject().put("answer", "Ω".repeat(5000)).toString());
            JSONObject result = AccountHttp.request("POST", SERVER + "/json", text, "fixture-token", "fixture-device");
            Wire w = r.wire;
            eq("Ω".repeat(5000), result.optString("answer"), "read response across 4096-byte chunks");
            eq(text, w.body(), "UTF-8 body");
            eq(text.getBytes(StandardCharsets.UTF_8).length, w.fixedLength, "fixed length is bytes");
            eq(10_000, w.connection.getConnectTimeout(), "connect timeout");
            eq(20_000, w.connection.getReadTimeout(), "read timeout");
            eq("application/json", w.connection.getRequestProperty("Accept"), "Accept");
            eq("application/json; charset=utf-8", w.connection.getRequestProperty("Content-Type"), "Content-Type");
            eq("Bearer fixture-token", w.connection.getRequestProperty("Authorization"), "Bearer");
            eq("fixture-device", w.connection.getRequestProperty("X-Device-Id"), "device header");
            check(w.output.closed && w.input.closed, "both streams close");
            eq(1, w.disconnected, "exactly one disconnect");
        });
        scenario("HTTP GET no body or credential headers", () -> {
            Reply r = Net.reply("GET", "/anonymous", 200, "{}");
            AccountHttp.request("GET", SERVER + "/anonymous", null, "", null);
            check(!r.wire.outputOpened && !r.wire.connection.getDoOutput(), "GET does not acquire output");
            eq(null, r.wire.connection.getRequestProperty("Authorization"), "anonymous Bearer absent");
            eq(null, r.wire.connection.getRequestProperty("X-Device-Id"), "anonymous device absent");
        });
        scenario("HTTP structured error preserves status and kind", () -> {
            Reply r = Net.reply("GET", "/denied", 401, "{\"error\":\"invalid_token\",\"message\":\"fixture expired\"}");
            ApiException e = (ApiException) throwsError(ApiException.class,
                    () -> AccountHttp.request("GET", SERVER + "/denied", null, "fake", "fake"), "401");
            eq(401, e.httpCode, "HTTP status"); eq("invalid_token", e.kind, "error kind");
            eq("fixture expired", e.detail, "error detail"); check(e.isAuthFailure(), "auth failure classification");
            check(r.wire.errorOpened && !r.wire.inputOpened && r.wire.input.closed, "error stream only and closed");
        });
        scenario("HTTP plain error truncated, null error stream supported", () -> {
            Net.reply("GET", "/plain", 503, "x".repeat(250));
            ApiException e = (ApiException) throwsError(ApiException.class,
                    () -> AccountHttp.request("GET", SERVER + "/plain", null, null, null), "plain error");
            eq("", e.kind, "plain error has no invented kind"); eq(200, e.detail.length(), "detail truncation");
            Reply r = Net.reply("GET", "/empty-error", 404, ""); r.nullError = true;
            ApiException empty = (ApiException) throwsError(ApiException.class,
                    () -> AccountHttp.request("GET", SERVER + "/empty-error", null, null, null), "empty error");
            eq(404, empty.httpCode, "empty stream preserves status"); eq("", empty.detail, "empty detail");
        });
        scenario("HTTP 403 device error classification", () -> {
            Net.reply("GET", "/device", 403, "{\"error\":\"device_not_bound\"}");
            ApiException e = (ApiException) throwsError(ApiException.class,
                    () -> AccountHttp.request("GET", SERVER + "/device", null, "fake", "fake"), "unbound");
            check(e.isDeviceUnbound() && !e.isAuthFailure(), "device error distinct from auth error");
        });
        scenario("HTTP malformed successful JSON still closes response/disconnects", () -> {
            Reply r = Net.reply("GET", "/bad-json", 200, "{broken");
            throwsError(IllegalArgumentException.class,
                    () -> AccountHttp.request("GET", SERVER + "/bad-json", null, null, null), "malformed JSON");
            check(r.wire.input.closed, "input closed before parse failure");
        });
        scenario("HTTP response read failure closes stream/disconnects", () -> {
            Reply r = Net.reply("GET", "/read-fail", 200, "{}"); r.readFailure = true;
            throwsError(IOException.class,
                    () -> AccountHttp.request("GET", SERVER + "/read-fail", null, null, null), "read failure");
            check(r.wire.input.closed, "failed input closed");
        });
        scenario("HTTP request write failure closes stream/disconnects", () -> {
            Reply r = Net.reply("POST", "/write-fail", 200, "{}"); r.writeFailure = true;
            throwsError(IOException.class,
                    () -> AccountHttp.request("POST", SERVER + "/write-fail", "{}", null, null), "write failure");
            check(r.wire.output.closed, "failed output closed");
        });
        scenario("HTTP response-code failure disconnects after closing output", () -> {
            Reply r = Net.reply("POST", "/code-fail", 200, "{}"); r.codeFailure = true;
            throwsError(IOException.class,
                    () -> AccountHttp.request("POST", SERVER + "/code-fail", "{}", null, null), "response failure");
            check(r.wire.output.closed, "output closed before response error");
        });
        scenario("HTTP setup failure after open disconnects", () -> {
            Reply r = Net.reply("GET", "/method-fail", 200, "{}"); r.methodFailure = true;
            throwsError(ProtocolException.class,
                    () -> AccountHttp.request("GET", SERVER + "/method-fail", null, null, null), "method setup failure");
            eq(1, r.wire.disconnected, "setup disconnect");
        });
        scenario("HTTP body over 8MiB never opens output or sends a request", () -> {
            Reply r = Net.reply("POST", "/oversized", 200, "{}");
            throwsError(IllegalArgumentException.class,
                    () -> AccountHttp.request("POST", SERVER + "/oversized", "x".repeat((8 << 20) + 1), null, null), "body limit");
            check(!r.wire.outputOpened && !r.wire.responded, "oversized body not transmitted");
        });
        scenario("HTTP body exactly 8MiB retains the boundary", () -> {
            Reply r = Net.reply("POST", "/limit", 200, "{}");
            AccountHttp.request("POST", SERVER + "/limit", "x".repeat(8 << 20), null, null);
            eq(8 << 20, r.wire.fixedLength, "exact limit accepted");
        });
        scenario("HTTPS configures one TLS factory and preserves hostname verifier", () -> {
            Reply r = Net.reply("GET", "/tls", 200, "{}");
            AccountHttp.request("GET", "https://account-fixture.invalid/tls", null, null, null);
            eq(1, TlsPin.calls, "TLS factory requested once");
            eq(1, r.wire.factorySets, "factory set once");
            check(((HttpsURLConnection) r.wire.connection).getHostnameVerifier()
                    == HttpsURLConnection.getDefaultHostnameVerifier(), "hostname verifier unchanged");
        });
        scenario("HTTPS factory failure disconnects without transmitting", () -> {
            Reply r = Net.reply("GET", "/tls-fail", 200, "{}"); TlsPin.fail = true;
            throwsError(IllegalStateException.class,
                    () -> AccountHttp.request("GET", "https://account-fixture.invalid/tls-fail", null, null, null), "TLS config failure");
            check(!r.wire.responded && !r.wire.outputOpened && !r.wire.inputOpened, "no transmission on TLS config failure");
        });
    }

    private static void syncCases() throws Exception {
        scenario("Real Prefs account switch resets all three sync cursors", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); cursors(p, 8, 9, 10);
            p.setSyncCursor(42); p.setSyncConfigVersion(3);
            check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 11, 2), "switch saves");
            cursorEq(p, 0, 0, 0, "account switch"); eq(0, p.syncCursor(), "server cursor reset");
            eq(0, p.syncConfigVersion(), "config version reset"); eq("", p.syncDeviceId(), "rebind required");
            p.selectCredentialChannel(false);
            eq(CredentialRoute.Channel.ACCOUNT, p.credentialRoute(p.model()).channel, "real credential resolver");
            check(p.credentialRoute(p.model()).isUsable(), "fixture account route configured");
            check(!p.featureFlags().toolRouter(), "real feature flags defaults");
        });
        scenario("Real TaskHistory writes run_id/command_id and normal task omits command_id", () -> {
            Context ctx = context();
            TaskHistory.append(ctx, "remote fixture", "完成", "real archive", 3, 987654321L, "archive-command");
            TaskHistory.append(ctx, "local fixture", "失败", "local archive", 1, 987654322L, null);
            List<String> lines = TaskHistory.rawLines(ctx);
            eq(2, lines.size(), "two real JSONL lines");
            JSONObject first = new JSONObject(lines.get(0)), second = new JSONObject(lines.get(1));
            eq(987654321L, first.optLong("run_id"), "exact remote run id");
            eq("archive-command", first.optString("command_id"), "exact remote command id");
            eq(987654322L, second.optLong("run_id"), "exact local run id");
            check(!second.has("command_id"), "local record has no invented remote id");
            eq("local fixture", TaskHistory.list(ctx).get(0).task, "real newest-first list");
            eq(0, Net.wires.size(), "sync-disabled archive does not schedule network");
        });
        scenario("One push batches <=50 local + <=50 peer + <=50 memory, one cursor commit", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 55); peers(ctx, 54); memories(ctx, 52);
            Reply r = Net.reply("POST", PUSH, 200, new JSONObject().put("accepted", 100).put("duplicates", 50).put("rejected", 0).toString());
            int commits = p.sp.commitCalls; int[] delivered = push(ctx, true);
            eq(100, delivered[0], "combined tasks"); eq(50, delivered[1], "bounded memories");
            JSONObject body = r.wire.json(); JSONArray tasks = body.getJSONArray("tasks");
            eq(100, tasks.length(), "two task sources independently capped");
            eq(50, body.getJSONArray("memories").length(), "memory cap");
            check(!tasks.getJSONObject(0).has("origin_device_id"), "local task not assigned peer origin");
            eq("origin-peer", tasks.getJSONObject(50).optString("origin_device_id"), "peer attribution retained");
            eq("peer-id-0", tasks.getJSONObject(50).optString("id"), "peer id retained");
            check(tasks.getJSONObject(0).optString("id").matches("[0-9a-f]{32}"), "local content id shape");
            eq(76, body.getJSONObject("status").optInt("battery"), "status in same request");
            check(body.getJSONObject("status").optBoolean("charging"), "charging status");
            cursorEq(p, 50, 50, 50, "first bounded batch");
            eq(commits + 1, p.sp.commitCalls, "one atomic cursor editor");
            eq(new HashSet<>(Arrays.asList("sync_history_index", "sync_peer_index", "sync_memory_index")), p.sp.lastCommitKeys, "three cursor keys in one commit");
            eq(1, Net.wires.size(), "one POST, not four");
            Reply remainder = Net.reply("POST", PUSH, 200, complete(11).toString());
            int[] next = push(ctx, true);
            eq(9, next[0], "remaining 5 local+4 peer"); eq(2, next[1], "remaining memories");
            eq(9, remainder.wire.json().getJSONArray("tasks").length(), "remaining payload");
            cursorEq(p, 55, 54, 52, "second batch"); eq(2, Net.wires.size(), "two batches exactly two POSTs");
        });
        scenario("Three large sources page by bytes without dropping deferred records", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx);
            List<String> peerLines = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                TaskHistory.append(ctx, "large-" + i, "完成", "x".repeat(120000), 1, i + 1, null);
                peerLines.add(peer(i).put("data", new JSONObject().put("text", "y".repeat(120000))).toString());
                MemoryStore.add(ctx, "\u0001".repeat(100000) + i);
            }
            peers(ctx, peerLines);
            int[][] pages = {{8,8,3}, {2,2,3}, {0,0,3}, {0,0,1}};
            int h = 0, peer = 0, memory = 0;
            Set<String> ids = new HashSet<>();
            for (int[] page : pages) {
                Reply r = Net.reply("POST", PUSH, 200, complete(page[0] + page[1] + page[2]).toString());
                int[] delivered = push(ctx, true);
                eq(page[0] + page[1], delivered[0], "byte-bounded tasks");
                eq(page[2], delivered[1], "byte-bounded memories");
                check(r.wire.fixedLength < (8 << 20), "aggregate wire body below server limit");
                JSONObject body = r.wire.json();
                for (String name : new String[]{"tasks", "memories"}) {
                    JSONArray records = body.getJSONArray(name);
                    for (int i = 0; i < records.length(); i++)
                        check(ids.add(records.getJSONObject(i).getString("id")), "no repeat across byte pages");
                }
                h += page[0]; peer += page[1]; memory += page[2];
                cursorEq(p, h, peer, memory, "only actually sent records committed");
            }
            eq(30, ids.size(), "all three sources fully delivered");
        });
        for (String source : new String[]{"local", "peer", "memory"}) {
            scenario("Oversized " + source + " record leaves every cursor unchanged", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx);
                if (source.equals("local")) TaskHistory.append(ctx, "oversized", "完成", "x".repeat(1100000), 1, 1, null);
                else if (source.equals("peer")) peers(ctx, Arrays.asList(peer(0).put("data", new JSONObject().put("text", "x".repeat(1100000))).toString()));
                else MemoryStore.add(ctx, "x".repeat(400000));
                throwsError(IllegalStateException.class, () -> push(ctx, true), "single-record budget");
                cursorEq(p, 0, 0, 0, "oversized record");
                eq(0, Net.wires.size(), "oversized source does not transmit");
            });
        }
        scenario("LAN includeHistory=false still pushes peer/memory/status and keeps history cursor", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 10); peers(ctx, 2); memories(ctx, 2);
            cursors(p, 7, 0, 0);
            Reply r = Net.reply("POST", PUSH, 200, complete(4).toString()); push(ctx, false);
            JSONObject b = r.wire.json(); eq(2, b.getJSONArray("tasks").length(), "peer tasks remain");
            eq("origin-peer", b.getJSONArray("tasks").getJSONObject(0).optString("origin_device_id"), "only peers uploaded");
            eq(2, b.getJSONArray("memories").length(), "memory upload retained");
            check(b.has("status"), "status upload retained"); cursorEq(p, 7, 2, 2, "LAN batch");
            eq(1, Net.wires.size(), "LAN batch one POST");
        });
        scenario("Full sync LAN success sends rendezvous + one push + config only", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 2); peers(ctx, 1); memories(ctx, 1);
            LanSync.active = true;
            Net.reply("POST", "/api/v1/lan/rendezvous", 200, new JSONObject().put("peers", new JSONArray()
                    .put(new JSONObject().put("device_id", "peer-LAN").put("lan_ips", new JSONArray().put("192.168.8.11")))).toString());
            Reply r = Net.reply("POST", PUSH, 200, complete(2).toString());
            Net.reply("GET", CONFIG, 200, cfg(1, new JSONObject()).toString());
            invoke(SyncClient.class, "syncOnce", new Class<?>[]{Context.class, Prefs.class, String.class}, ctx, p, SERVER);
            eq(1, LanSync.calls, "one LAN exchange boundary call"); eq(2, LanSync.sent.length(), "LAN receives local batch");
            eq(1, r.wire.json().getJSONArray("tasks").length(), "cloud has peer only after LAN");
            check(r.wire.json().has("status"), "cloud status retained");
            cursorEq(p, 2, 1, 1, "full LAN sync"); eq(3, Net.wires.size(), "rendezvous/push/config exact count");
            check(Net.wires.stream().noneMatch(w -> w.url.getPath().contains("sync/pull")), "no records downlink");
        });
        scenario("No new records still sends one status-only batch", () -> {
            Context ctx = context(); Reply r = Net.reply("POST", PUSH, 200, complete(0).toString()); push(ctx, true);
            eq(0, r.wire.json().getJSONArray("tasks").length(), "empty task batch");
            eq(0, r.wire.json().getJSONArray("memories").length(), "empty memory batch");
            check(r.wire.json().has("status"), "status is not dropped");
        });
        List<JSONObject> badReceipts = Arrays.asList(
                new JSONObject().put("accepted", 0).put("duplicates", 0).put("rejected", 3),
                new JSONObject().put("accepted", 2).put("duplicates", 0).put("rejected", 0),
                new JSONObject().put("accepted", 0).put("duplicates", 2).put("rejected", 0),
                new JSONObject().put("accepted", 3).put("duplicates", 0).put("rejected", 1),
                new JSONObject().put("accepted", 3).put("duplicates", 0),
                new JSONObject().put("accepted", -1).put("duplicates", 4).put("rejected", 0),
                new JSONObject().put("accepted", 4).put("duplicates", -1).put("rejected", 0),
                new JSONObject().put("duplicates", 4).put("rejected", 0),
                new JSONObject().put("accepted", 4).put("rejected", 0));
        String[] badNames = {"complete rejection", "partial accepted", "partial duplicates", "nonzero rejected", "missing rejected", "negative accepted", "negative duplicates", "missing accepted", "missing duplicates"};
        for (int i = 0; i < badReceipts.size(); i++) {
            final JSONObject receipt = badReceipts.get(i); final String name = badNames[i];
            scenario("Incomplete/invalid receipt " + name + " does not advance any cursor", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 2); peers(ctx, 2); memories(ctx, 2);
                cursors(p, 1, 1, 1);
                Net.reply("POST", PUSH, 200, receipt.toString());
                throwsError(IllegalStateException.class, () -> push(ctx, true), "invalid receipt");
                cursorEq(p, 1, 1, 1, "rejected receipt"); eq(1, Net.wires.size(), "rejection still only one request");
            });
        }
        scenario("Retry after partial receipt keeps stable IDs and payload", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 1); peers(ctx, 1); memories(ctx, 1);
            Reply first = Net.reply("POST", PUSH, 200, complete(2).toString());
            throwsError(IllegalStateException.class, () -> push(ctx, true), "partial delivery");
            Reply second = Net.reply("POST", PUSH, 200, new JSONObject().put("accepted", 0).put("duplicates", 3).put("rejected", 0).toString());
            push(ctx, true); eq(first.wire.body(), second.wire.body(), "exact same batch retry");
            cursorEq(p, 1, 1, 1, "duplicates count as delivered");
        });
        String[] malformedPeers = {"{broken", "{\"origin_device_id\":\"peer\"}", "{\"data\":{\"task\":\"bad\"}}"};
        for (String bad : malformedPeers) {
            scenario("Malformed peer record stops before POST: " + bad, () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 2); memories(ctx, 2);
                peers(ctx, Arrays.asList(peer(0).toString(), bad)); cursors(p, 1, 1, 1);
                throwsError(Exception.class, () -> push(ctx, true), "bad peer");
                cursorEq(p, 1, 1, 1, "bad peer"); eq(0, Net.wires.size(), "no partial upload before bad peer validation");
            });
        }
        for (boolean memoryFirst : new boolean[]{false, true}) {
            scenario("Cursor commit failure memoryFirst=" + memoryFirst + " leaves old cursors", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 2); peers(ctx, 2); memories(ctx, 2);
                cursors(p, 1, 1, 1); p.sp.failNextCommit(memoryFirst);
                Net.reply("POST", PUSH, 200, complete(3).toString());
                throwsError(IllegalStateException.class, () -> push(ctx, true), "cursor disk failure");
                cursorEq(p, 1, 1, 1, "commit failure memory");
                p.sp.reloadFromDisk(); cursorEq(p, 1, 1, 1, "commit failure restored durable fixture");
            });
        }
        for (String change : Arrays.asList("account", "token", "device")) {
            scenario("Push response with changed " + change + " does not commit stale cursors", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); histories(ctx, 1); peers(ctx, 1); memories(ctx, 1);
                Reply r = Net.reply("POST", PUSH, 200, complete(3).toString());
                r.onResponse = () -> {
                    if (change.equals("account")) {
                        check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 0, 0), "switch account");
                        p.setSyncDeviceId("device-B");
                    } else if (change.equals("token")) {
                        check(p.saveAccount("account-A", "fixture-A", "fixture-token-rotated", 0, 0), "rotate token");
                    } else p.setSyncDeviceId("device-B");
                    cursors(p, 7, 8, 9);
                };
                throwsError(IllegalStateException.class, () -> push(ctx, true), "stale response");
                cursorEq(p, 7, 8, 9, "new owner state");
                eq("Bearer fixture-token-A", r.wire.connection.getRequestProperty("Authorization"), "request uses original credential snapshot");
                eq("device-A", r.wire.connection.getRequestProperty("X-Device-Id"), "request uses original device");
            });
        }
        scenario("Config only GET /config, whitelist application, no server cursor mutation", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); p.setSingleModelMode(false); p.setSyncCursor(91);
            p.sp.edit().putString("base_url", "http://own-fixture.invalid").putString("api_key", "fixture-own-key").apply();
            JSONObject values = new JSONObject().put("max_steps", 60).put("planner_model", "fixture-planner")
                    .put("vision", false).put("spec_inject", false).put("spec_clamp", false)
                    .put("base_url", "http://ignored.invalid").put("api_key", "ignored-key").put("sync_cursor", 999);
            Net.reply("GET", CONFIG, 200, cfg(4, values).toString()); check(config(ctx), "changed config applied");
            eq(60, p.maxSteps(), "whitelisted integer"); eq("fixture-planner", p.plannerModelRaw(), "whitelisted planner");
            check(!p.vision() && !p.specInject() && !p.specClamp(), "whitelisted booleans");
            eq("http://own-fixture.invalid", p.baseUrl(), "outgoing own URL unchanged");
            eq("fixture-own-key", p.apiKey(), "own key unchanged"); eq(91, p.syncCursor(), "records cursor untouched");
            eq(4, p.syncConfigVersion(), "config version persisted"); eq(1, Net.wires.size(), "one config GET only");
        });
        scenario("Unchanged config version performs no repeated preference writes", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); p.setSyncConfigVersion(4);
            int before = p.sp.applyCalls;
            Net.reply("GET", CONFIG, 200, cfg(4, new JSONObject().put("vision", false)).toString());
            check(!config(ctx), "same version ignored"); eq(before, p.sp.applyCalls, "no config writes for same version");
            check(p.vision(), "old value remains");
        });
        scenario("Config account switch rejects old response before applying values", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx);
            Reply r = Net.reply("GET", CONFIG, 200, cfg(4, new JSONObject().put("vision", false)).toString());
            r.onResponse = () -> { check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 0, 0), "config owner switch"); p.setSyncConfigVersion(7); };
            throwsError(IllegalStateException.class, () -> config(ctx), "config session mismatch");
            check(p.vision(), "old config not applied to new account"); eq(7, p.syncConfigVersion(), "new version remains");
        });
    }

    private static void remoteCases() throws Exception {
        scenario("Claim ACK failure retains claim and never executes; successful retry executes once", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx);
            inbox("claim-retry"); Net.reply("POST", ACK, 503, "{\"error\":\"fixture_busy\"}");
            throwsError(ApiException.class, () -> poll(ctx), "claim ACK failure");
            eq(0, engine.attempts, "claim failure does not call start");
            eq("claim", pending(ctx).optString("status"), "claim retained persistently");
            Reply retry = ackOk();
            engine.beforeStart = () -> {
                check(retry.wire.responded && retry.wire.disconnected == 1, "ACK finished before execution");
                eq("running", pending(ctx).optString("status"), "running saved before execution");
            };
            poll(ctx); eq(1, engine.executions, "retry executes only once");
            eq(RemoteCommand.REMOTE_PREFIX + "execute claim-retry", engine.current.task, "command task passed to one engine implementation");
            eq("claim-retry", engine.current.command, "command id passed directly");
            eq(3, Net.wires.size(), "one inbox GET + two claim ACKs");
        });
        scenario("Remote result comes from exact callback, never latest unrelated history; ACK retry one execution", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "own-result");
            eq("sender-device", RemoteCommand.runningFromDevice(), "sender while running");
            TaskHistory.append(ctx, "unrelated successful local task", "完成", "wrong latest detail", 0, 9999, null);
            int before = Net.wires.size(); poll(ctx); eq(before, Net.wires.size(), "running task causes no result guessing");
            engine.finish("完成", "own callback evidence");
            TaskHistory.append(ctx, "another latest local failure", "失败", "also wrong detail", 0, 9998, null);
            JSONObject saved = pending(ctx);
            eq("done", saved.optString("status"), "terminal from callback"); eq(1001, saved.optLong("run_id"), "callback run bound, not history 9999/9998");
            eq("own-result", saved.optString("command_id"), "command bound");
            eq("own callback evidence", saved.optString("detail"), "callback detail retained");
            eq(null, RemoteCommand.runningFromDevice(), "not running after callback");
            Reply first = Net.reply("POST", ACK, 503, "{}");
            throwsError(ApiException.class, () -> poll(ctx), "terminal ACK failure");
            eq("done", pending(ctx).optString("status"), "terminal survives failed ACK");
            check(field(RemoteCommand.class, "sPending").get(null) != null, "pending kept in memory");
            Reply retry = ackOk(); poll(ctx);
            eq(first.wire.body(), retry.wire.body(), "same terminal ACK body on retry");
            eq("done", retry.wire.json().optString("status"), "correct terminal ACK");
            eq("own callback evidence", retry.wire.json().optString("detail"), "not latest history");
            eq(1, engine.executions, "repeated ACK does not execute task twice");
            eq("", new Prefs(ctx).sp.getString(key("account-A", "device-A"), ""), "record removed after successful ACK");
            eq(null, field(RemoteCommand.class, "sPending").get(null), "in-memory record removed");
        });
        scenario("Successful terminal ACK with pending-record removal failure retries ACK without execution", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "delete-retry");
            engine.finish("完成", "saved result"); Reply first = ackOk();
            first.onResponse = () -> p.sp.failNextCommit(false);
            throwsError(IllegalStateException.class, () -> poll(ctx), "pending removal commit failure");
            eq("done", pending(ctx).optString("status"), "persisted pending retained");
            Reply second = ackOk(); poll(ctx); eq(first.wire.body(), second.wire.body(), "duplicate terminal ACK is identical");
            eq(1, engine.executions, "deletion retry not replay");
        });
        for (boolean memoryFirst : new boolean[]{false, true}) {
            scenario("Claim state commit failure memoryFirst=" + memoryFirst + " restores claim and retries without a stalled running", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx);
                inbox("claim-save-retry"); Reply first = ackOk();
                first.onResponse = () -> p.sp.failNextCommit(memoryFirst);
                throwsError(IllegalStateException.class, () -> poll(ctx), "running persistence failure");
                eq(0, engine.attempts, "not started without persisted running");
                eq("claim", pending(ctx).optString("status"), "claim restored after failed running commit");
                Reply retry = ackOk(); poll(ctx);
                eq(1, engine.executions, "next poll retries claim and executes, not stuck");
                eq(first.wire.body(), retry.wire.body(), "same claim ACK retry");
                eq("running", pending(ctx).optString("status"), "running persisted on retry");
            });
        }
        scenario("Callback persistence failure keeps exact memory result; terminal poll saves before ACK", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "callback-save-retry");
            p.sp.failNextCommit(false); engine.finish("完成", "recover exact evidence");
            eq("running", pending(ctx).optString("status"), "failed callback save leaves old persistent record");
            Reply first = Net.reply("POST", ACK, 500, "{}");
            first.onResponse = () -> {
                JSONObject restored = pending(ctx);
                eq("done", restored.optString("status"), "exact terminal is saved before transmitting ACK");
                eq(1001, restored.optLong("run_id"), "callback run restored before ACK");
                eq("recover exact evidence", restored.optString("detail"), "callback detail restored before ACK");
            };
            throwsError(ApiException.class, () -> poll(ctx), "ACK failure after successful persistence retry");
            Reply retry = ackOk(); poll(ctx);
            eq(first.wire.body(), retry.wire.body(), "terminal retry is same exact callback result");
            eq(1, engine.executions, "callback persistence retry never reexecutes");
        });
        scenario("Terminal persistence still failing does not transmit ACK until durable retry succeeds", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "terminal-save-retry");
            engine.finish("完成", "persist before ACK"); p.sp.failNextCommit(false);
            int requests = Net.wires.size();
            throwsError(IllegalStateException.class, () -> poll(ctx), "terminal pre-ACK save failure");
            eq(requests, Net.wires.size(), "no ACK until terminal persistence succeeds");
            Reply retry = ackOk(); poll(ctx);
            eq("persist before ACK", retry.wire.json().optString("detail"), "exact result after persistence retry");
            eq(1, engine.executions, "pre-ACK save failure never reexecutes");
        });
        scenario("Process restart restores running as failed and never replays execution", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "process-running");
            check(p.sp.contains(key("account-A", "device-A")), "running durable record");
            field(RemoteCommand.class, "sPending").set(null, null); engine.running = false;
            Reply r = ackOk(); poll(ctx);
            eq("process-running", r.wire.json().optString("command_id"), "restored command id");
            eq("failed", r.wire.json().optString("status"), "restored running interrupted");
            check(r.wire.json().optString("detail").contains("进程重启"), "restart reason explicit");
            eq(1, engine.executions, "no second execution"); eq(3, Net.wires.size(), "no new inbox GET on restoration");
        });
        scenario("Process restart restores terminal run_id/detail and retries only ACK", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "process-done"); engine.finish("完成", "durable exact evidence");
            field(RemoteCommand.class, "sPending").set(null, null);
            Reply fail = Net.reply("POST", ACK, 500, "{}");
            throwsError(ApiException.class, () -> poll(ctx), "restored terminal ack fails");
            eq(1001, pending(ctx).optLong("run_id"), "restored run id");
            Reply retry = ackOk(); poll(ctx);
            eq(fail.wire.body(), retry.wire.body(), "restored ACK retry identical");
            eq("durable exact evidence", retry.wire.json().optString("detail"), "restored callback detail");
            eq(1, engine.executions, "terminal restart no replay");
        });
        scenario("Wrong command callback rejected; exact callback accepted", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx); claim(ctx, "callback-owner");
            throwsError(IllegalStateException.class,
                    () -> engine.current.deliver("different-command", "完成", "foreign evidence"), "wrong command id");
            eq("running", pending(ctx).optString("status"), "wrong callback does not finish command");
            engine.finish("失败", "correct callback failure");
            eq("failed", pending(ctx).optString("status"), "real failure is terminal failed");
            eq(1001, pending(ctx).optLong("run_id"), "real run id after callback");
            Reply r = ackOk(); poll(ctx); eq("failed", r.wire.json().optString("status"), "failure ACK");
        });
        scenario("No accessibility records explicit failed and never starts engine", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx); AgentA11yService.available = false;
            inbox("no-a11y"); poll(ctx);
            eq(0, engine.attempts, "no A11y no start"); eq(1, Net.wires.size(), "no claim ACK when prerequisite missing");
            eq("failed", pending(ctx).optString("status"), "explicit failed persisted");
            Reply r = ackOk(); poll(ctx); eq("failed", r.wire.json().optString("status"), "explicit failure reported");
            check(r.wire.json().optString("detail").contains("无障碍"), "clear accessibility reason");
        });
        scenario("Engine rejection after claim is explicit failed, not silent completion", () -> {
            Context ctx = context(); AgentEngine engine = AgentEngine.get(ctx); engine.accept = false;
            inbox("engine-reject"); ackOk(); poll(ctx);
            eq(1, engine.attempts, "one start attempt"); eq(0, engine.executions, "engine rejected");
            eq("failed", pending(ctx).optString("status"), "rejection persisted");
            Reply r = ackOk(); poll(ctx); eq("failed", r.wire.json().optString("status"), "rejection ACK failed");
            check(r.wire.json().optString("detail").contains("引擎"), "clear engine rejection reason");
            eq(1, engine.attempts, "no second start attempt");
        });
        scenario("Expired claim 404 clears pending without execution", () -> {
            Context ctx = context(); inbox("expired-claim"); Net.reply("POST", ACK, 404, "{}"); poll(ctx);
            eq(0, AgentEngine.get(ctx).attempts, "expired claim not run");
            eq("", new Prefs(ctx).sp.getString(key("account-A", "device-A"), ""), "expired claim removed");
        });
        for (boolean accountChange : new boolean[]{true, false}) {
            scenario("Owner switch " + (accountChange ? "account" : "device") + " never mixes late callback or terminal ACK", () -> {
                Context ctx = context(); Prefs p = new Prefs(ctx); AgentEngine engine = AgentEngine.get(ctx);
                claim(ctx, "owner-A"); AgentEngine.Execution old = engine.current;
                String nextAccount = accountChange ? "account-B" : "account-A";
                if (accountChange) check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 0, 0), "switch account while running");
                p.setSyncDeviceId("device-B"); int before = Net.wires.size(); poll(ctx);
                eq(before, Net.wires.size(), "other owner waits until original engine finishes");
                engine.finish("完成", "A exact evidence");
                eq("A exact evidence", pending(ctx, "account-A", "device-A").optString("detail"), "original owner's terminal archive");
                inbox("owner-B"); ackOk(); poll(ctx); eq(2, engine.executions, "second owner executed once");
                String newRaw = p.sp.getString(key(nextAccount, "device-B"), "");
                old.deliver(old.command, "完成", "A late callback");
                eq(newRaw, p.sp.getString(key(nextAccount, "device-B"), ""), "late A callback leaves B record untouched");
                engine.finish("失败", "B own failure"); Reply bAck = ackOk(); poll(ctx);
                eq("owner-B", bAck.wire.json().optString("command_id"), "B ACK owns B command");
                eq("B own failure", bAck.wire.json().optString("detail"), "B ACK owns B result");
                eq("device-B", bAck.wire.connection.getRequestProperty("X-Device-Id"), "B device header");
                if (accountChange) check(p.saveAccount("account-A", "fixture-A", "fixture-token-A", 0, 0), "return to A account");
                p.setSyncDeviceId("device-A"); Reply aAck = ackOk(); poll(ctx);
                eq("owner-A", aAck.wire.json().optString("command_id"), "A retained record restored");
                eq("A late callback", aAck.wire.json().optString("detail"), "A callback not B history/result");
                eq("Bearer fixture-token-A", aAck.wire.connection.getRequestProperty("Authorization"), "A request credential");
                eq(2, engine.executions, "owner restoration retries ACK only");
            });
        }
        scenario("Inbox account changes during response: no pending or execution leaked", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); Reply r = inbox("stale-inbox");
            r.onResponse = () -> { check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 0, 0), "inbox switch"); p.setSyncDeviceId("device-B"); };
            throwsError(IllegalStateException.class, () -> poll(ctx), "stale inbox");
            eq(0, AgentEngine.get(ctx).attempts, "stale inbox no start");
            check(!p.sp.contains(key("account-A", "device-A")) && !p.sp.contains(key("account-B", "device-B")), "stale inbox not persisted");
        });
        scenario("Claim ACK account changes during response: execution not started", () -> {
            Context ctx = context(); Prefs p = new Prefs(ctx); inbox("stale-claim"); Reply r = ackOk();
            r.onResponse = () -> { check(p.saveAccount("account-B", "fixture-B", "fixture-token-B", 0, 0), "claim switch"); p.setSyncDeviceId("device-B"); };
            throwsError(IllegalStateException.class, () -> poll(ctx), "stale claim ACK");
            eq(0, AgentEngine.get(ctx).attempts, "stale claim no start");
            eq("claim", pending(ctx, "account-A", "device-A").optString("status"), "A claim retained in A domain");
            check(!p.sp.contains(key("account-B", "device-B")), "no A state persisted under B");
        });
        scenario("Poll generation invalidated during inbox does not claim or execute", () -> {
            Context ctx = context(); Reply r = inbox("revoked-inbox"); r.onResponse = RemoteCommand::stopPolling; poll(ctx);
            eq(0, AgentEngine.get(ctx).attempts, "revoked inbox no start");
            eq(null, field(RemoteCommand.class, "sPending").get(null), "revoked inbox no pending");
        });
        scenario("Poll generation invalidated during claim ACK does not execute", () -> {
            Context ctx = context(); inbox("revoked-claim"); Reply r = ackOk(); r.onResponse = RemoteCommand::stopPolling; poll(ctx);
            eq(0, AgentEngine.get(ctx).attempts, "revoked claim no start");
            eq("claim", pending(ctx).optString("status"), "revoked execution remains unstarted claim");
        });
        scenario("Empty/invalid inbox does not create pending command", () -> {
            Context ctx = context(); Net.reply("GET", COMMANDS, 200, new JSONObject().put("commands", new JSONArray()
                    .put("junk").put(new JSONObject()).put(command("missing-task", ""))).toString());
            poll(ctx); eq(0, AgentEngine.get(ctx).attempts, "invalid inbox ignored");
            eq(null, field(RemoteCommand.class, "sPending").get(null), "no invalid pending");
        });
        scenario("Polling scheduler stopped and its thread reclaimed", () -> {
            Context ctx = context(); RemoteCommand.startPolling(ctx); check(RemoteCommand.isPolling(), "poll started");
            ScheduledExecutorService executor = (ScheduledExecutorService) field(RemoteCommand.class, "sExec").get(null);
            check(executor != null, "scheduler present"); RemoteCommand.stopPolling();
            check(!RemoteCommand.isPolling() && executor.isShutdown(), "polling stopped");
            check(executor.awaitTermination(2, TimeUnit.SECONDS), "scheduler thread terminates");
            eq(null, field(RemoteCommand.class, "sExec").get(null), "scheduler reference cleared");
            eq(0, Net.wires.size(), "stop before initial scheduled network round");
        });
    }

    /** 一次内存连接的响应计划，不包含任何生产请求/同步/命令逻辑。 */
    private static final class Reply {
        final String method, path, text;
        final int code;
        boolean nullError, readFailure, writeFailure, codeFailure, methodFailure;
        Runnable onResponse;
        Wire wire;
        Reply(String method, String path, int code, String text) {
            this.method=method; this.path=path; this.code=code; this.text=text;
        }
    }
    private static final class TrackingInput extends InputStream {
        final ByteArrayInputStream bytes;
        final boolean fail;
        boolean closed;
        TrackingInput(String text, boolean fail) { bytes=new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); this.fail=fail; }
        @Override public int read() throws IOException { if (fail) throw new IOException("fixture response read failure"); return bytes.read(); }
        @Override public int read(byte[] b, int offset, int length) throws IOException {
            if (fail) throw new IOException("fixture response read failure"); return bytes.read(b, offset, length);
        }
        @Override public void close() { closed=true; }
    }
    private static final class TrackingOutput extends OutputStream {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final boolean fail;
        boolean closed;
        TrackingOutput(boolean fail) { this.fail=fail; }
        @Override public void write(int value) throws IOException { if (fail) throw new IOException("fixture request write failure"); bytes.write(value); }
        @Override public void write(byte[] b, int offset, int length) throws IOException {
            if (fail) throw new IOException("fixture request write failure"); bytes.write(b, offset, length);
        }
        @Override public void close() { closed=true; }
    }
    private static final class Wire {
        final URL url;
        final Reply reply;
        final TrackingInput input;
        final TrackingOutput output;
        HttpURLConnection connection;
        int disconnected, fixedLength=-1, factorySets;
        boolean outputOpened, inputOpened, errorOpened, responded;
        Wire(URL url, Reply reply) {
            this.url=url; this.reply=reply;
            input=new TrackingInput(reply.text, reply.readFailure); output=new TrackingOutput(reply.writeFailure);
        }
        String body() { return new String(output.bytes.toByteArray(), StandardCharsets.UTF_8); }
        JSONObject json() { return new JSONObject(body()); }
        void method(String method) throws ProtocolException {
            if (reply.methodFailure) throw new ProtocolException("fixture request method failure");
        }
        int response() throws IOException {
            eq(reply.path, url.getPath(), "expected request path");
            eq(reply.method, connection.getRequestMethod(), "expected request method");
            if (outputOpened) check(output.closed, "output closed before response");
            if (reply.codeFailure) throw new IOException("fixture response code failure");
            if (!responded) { responded=true; if (reply.onResponse != null) reply.onResponse.run(); }
            return reply.code;
        }
        InputStream input() { inputOpened=true; return input; }
        InputStream error() { errorOpened=true; return reply.nullError ? null : input; }
        OutputStream output() { outputOpened=true; return output; }
    }
    private static final class MemoryHttp extends HttpURLConnection {
        final Wire wire;
        MemoryHttp(URL url, Wire wire) { super(url); this.wire=wire; wire.connection=this; }
        @Override public void setRequestMethod(String method) throws ProtocolException { wire.method(method); super.setRequestMethod(method); }
        @Override public void setFixedLengthStreamingMode(int length) { super.setFixedLengthStreamingMode(length); wire.fixedLength=length; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { wire.disconnected++; }
        @Override public int getResponseCode() throws IOException { return wire.response(); }
        @Override public InputStream getInputStream() { return wire.input(); }
        @Override public InputStream getErrorStream() { return wire.error(); }
        @Override public OutputStream getOutputStream() { return wire.output(); }
    }
    private static final class MemoryHttps extends HttpsURLConnection {
        final Wire wire;
        MemoryHttps(URL url, Wire wire) { super(url); this.wire=wire; wire.connection=this; }
        @Override public void setSSLSocketFactory(SSLSocketFactory factory) { super.setSSLSocketFactory(factory); wire.factorySets++; }
        @Override public void setRequestMethod(String method) throws ProtocolException { wire.method(method); super.setRequestMethod(method); }
        @Override public void setFixedLengthStreamingMode(int length) { super.setFixedLengthStreamingMode(length); wire.fixedLength=length; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { wire.disconnected++; }
        @Override public int getResponseCode() throws IOException { return wire.response(); }
        @Override public InputStream getInputStream() { return wire.input(); }
        @Override public InputStream getErrorStream() { return wire.error(); }
        @Override public OutputStream getOutputStream() { return wire.output(); }
        @Override public String getCipherSuite() { return "fixture-no-TLS-handshake"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
    private static final class Net {
        static final Deque<Reply> queue=new ArrayDeque<>();
        static final List<Wire> wires=new ArrayList<>();
        static void install() {
            URL.setURLStreamHandlerFactory(protocol -> {
                if (!protocol.equals("http") && !protocol.equals("https")) return null;
                return new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) throws IOException {
                        if (!url.getHost().equals("account-fixture.invalid")) throw new IOException("unexpected fixture host");
                        Reply reply=queue.pollFirst();
                        if (reply == null) throw new IOException("unexpected request: " + url.getPath());
                        Wire wire=new Wire(url, reply); reply.wire=wire; wires.add(wire);
                        return protocol.equals("https") ? new MemoryHttps(url, wire) : new MemoryHttp(url, wire);
                    }
                };
            });
        }
        static void reset() { queue.clear(); wires.clear(); }
        static Reply reply(String method, String path, int code, String text) {
            Reply reply=new Reply(method, path, code, text); queue.addLast(reply); return reply;
        }
        static void verify() {
            eq(0, queue.size(), "all expected requests used");
            for (Wire wire : wires) {
                eq(1, wire.disconnected, "all connections disconnected exactly once");
                if (wire.outputOpened) check(wire.output.closed, "every acquired request stream closed");
                if (wire.inputOpened || (wire.errorOpened && !wire.reply.nullError))
                    check(wire.input.closed, "every acquired response stream closed");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("temporary data directory required");
        root=Paths.get(args[0]).toRealPath();
        if (!root.getFileName().toString().equals("data") || !root.getParent().getFileName().toString().startsWith("account_sync_"))
            throw new AssertionError("unexpected test temporary root");
        Net.install();
        try { httpCases(); syncCases(); remoteCases(); }
        finally {
            RemoteCommand.stopPolling();
            ExecutorService executor=(ExecutorService) field(SyncClient.class, "EXEC").get(null);
            executor.shutdownNow(); check(executor.awaitTermination(2, TimeUnit.SECONDS), "sync executor reclaimed");
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.isAlive() && (thread.getName().equals("agentlite-sync") || thread.getName().equals("agentlite-remote")))
                    throw new AssertionError("unreclaimed test worker: " + thread.getName());
        }
        System.out.println("账号/同步回归: " + cases + " 场景 / " + checks + " 断言 / " + failures + " 失败");
        System.out.println("边界: HTTP=JVM内存连接(0 socket); Android/SharedPreferences/Keystore/TLS/LAN/AgentEngine=桩; 任务与记忆档案=真实临时文件; 未做APK/真机验收");
        if (failures != 0) System.exit(1);
    }
}
