// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 定向跨设备任务；结果来自本次执行回调，ACK成功前保留待回报记录。 */
public final class RemoteCommand {
    private static final String TAG = "AgentLiteRemote";
    private static final long POLL_INTERVAL_MS = 20_000;
    private static final long RESULT_TTL_MS = 24 * 60 * 60 * 1000L;
    public static final String REMOTE_PREFIX = "[跨设备指令] ";
    private static ScheduledExecutorService sExec;
    private static volatile boolean sPolling;
    private static volatile long sPollGeneration;
    private static volatile int sPollRounds;
    private static volatile PendingCommand sPending;

    private RemoteCommand() { }
    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
        AgentEngine.staticLog("跨设备: " + msg);
    }

    /** 唯一待执行/待回报状态；不持久化 token，账号/设备决定记录归属。 */
    private static final class PendingCommand {
        final String id, from, task, account, device;
        volatile String status = "claim", detail = "目标设备已领取指令";
        volatile long runId, updatedAt = System.currentTimeMillis();
        PendingCommand(JSONObject c, String ownerAccount, String ownerDevice) {
            id = c.optString("command_id", "");
            from = c.optString("from_device", "");
            task = c.optString("task", "");
            account = ownerAccount; device = ownerDevice;
        }
        String key() { return "remote_ack." + account + ":" + device; }
        boolean matches(Prefs p) { return account.equals(p.accountId()) && device.equals(p.syncDeviceId()); }
        boolean terminal() { return "done".equals(status) || "failed".equals(status); }
        synchronized void save(Context ctx) throws Exception {
            JSONObject value = new JSONObject();
            value.put("command_id", id); value.put("from_device", from); value.put("task", task);
            value.put("account_id", account); value.put("device_id", device);
            value.put("status", status); value.put("detail", detail);
            value.put("run_id", runId); value.put("updated_at", updatedAt);
            if (!sp(ctx).edit().putString(key(), value.toString()).commit())
                throw new IllegalStateException("指令回报状态保存失败");
        }
        synchronized void finish(Context ctx, long seq, String result, String message) throws Exception {
            runId = seq;
            detail = message == null ? "" : message;
            updatedAt = System.currentTimeMillis();
            status = "完成".equals(result) ? "done" : "failed"; // 最后发布终态，读者看到同一结果的全部字段。
            save(ctx);
        }
    }

    public static String runningFromDevice() {
        PendingCommand run = sPending;
        return run != null && "running".equals(run.status) ? run.from : null;
    }

    public static void refreshDevices(Context ctx) {
        try {
            JSONObject r = request(ctx, "GET", "/api/v1/account/me", null);
            JSONArray ds = r.optJSONArray("devices");
            if (ds != null) sp(ctx).edit().putString("peer_devices", ds.toString()).apply();
        } catch (Exception e) { android.util.Log.i(TAG, "设备列表刷新失败: " + e); }
    }
    public static JSONArray cachedDevices(Context ctx) {
        try { return new JSONArray(sp(ctx).getString("peer_devices", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    public static String resolveDevice(Context ctx, String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return null;
        String s = spoken.trim();
        String mine = sp(ctx).getString("sync_device_id", "");
        if (s.contains("本机") || s.contains("这台") || s.contains("自己") || s.contains("当前设备")) return mine;
        JSONArray ds = cachedDevices(ctx);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        if (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            JSONObject d = idx >= 1 && idx <= ds.length() ? ds.optJSONObject(idx - 1) : null;
            if (d != null) return d.optString("device_id", "");
        }
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (d == null) continue;
            String name = d.optString("name", ""), model = d.optString("model", ""), brand = d.optString("brand", "");
            if ((!name.isEmpty() && (s.contains(name) || name.contains(s)))
                    || (!model.isEmpty() && s.contains(model)) || (!brand.isEmpty() && s.contains(brand)))
                return d.optString("device_id", "");
        }
        return null;
    }
    public static String deviceBlock(Context ctx) {
        JSONArray ds = cachedDevices(ctx);
        String mine = sp(ctx).getString("sync_device_id", "");
        StringBuilder b = new StringBuilder("\n\n【同账号设备】你可以用 remote 动作让**另一台**设备替你执行任务：\n");
        boolean any = false;
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (d == null) continue;
            boolean self = d.optString("device_id", "").equals(mine);
            b.append("- [").append(i + 1).append("] ").append(d.optString("name", "未命名"))
                    .append("（").append(d.optString("brand", "")).append(' ')
                    .append(d.optString("model", "")).append("）").append(self ? " ← 本机" : "").append('\n');
            if (!self) any = true;
        }
        if (!any) return "";
        return b.append("用户说「让设备2打开B站」这类话时，输出 ")
                .append("{\"action\":\"remote\",\"device\":\"设备2\",\"task\":\"打开B站\"}。")
                .append("device 可以填序号、名称或型号片段；**只能指定一台设备，不支持群发**。")
                .append("对方设备的用户仍需对敏感操作自行确认。\n").toString();
    }
    public static JSONObject send(Context ctx, String toDeviceId, String task) throws Exception {
        JSONObject body = new JSONObject().put("to_device", toDeviceId).put("task", task);
        JSONObject c = request(ctx, "POST", "/api/v1/device/command", body.toString()).optJSONObject("command");
        if (c != null) log("已下发指令 " + c.optString("command_id") + " → " + toDeviceId + "：" + task);
        return c == null ? new JSONObject() : c;
    }

    public static synchronized void startPolling(Context ctx) {
        if (sPolling || !new Prefs(ctx).loggedIn()) return;
        Context app = ctx.getApplicationContext();
        final long generation = ++sPollGeneration;
        sExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentlite-remote"); t.setDaemon(true); return t;
        });
        sPolling = true;
        sExec.scheduleWithFixedDelay(() -> {
            if (generation != sPollGeneration) return;
            try {
                if (sPollRounds++ % 3 == 0) refreshDevices(app);
                pollOnce(app, generation);
            } catch (Throwable t) { android.util.Log.i(TAG, "轮询异常（保留状态，下轮重试）: " + t); }
        }, 3, POLL_INTERVAL_MS / 1000, TimeUnit.SECONDS);
    }
    public static synchronized void stopPolling() {
        ++sPollGeneration;
        if (sExec != null) { sExec.shutdownNow(); sExec = null; }
        sPolling = false; // 不清除执行对象；任务结束回调仍归档原账号，重启后可重试ACK。
    }
    public static boolean isPolling() { return sPolling; }

    private static PendingCommand restore(Context ctx, Prefs p, String account, String device) throws Exception {
        String raw = p.sp.getString("remote_ack." + account + ":" + device, "");
        if (raw.isEmpty()) return null;
        JSONObject value = new JSONObject(raw);
        PendingCommand run = new PendingCommand(value, account, device);
        if (!run.account.equals(value.optString("account_id")) || !run.device.equals(value.optString("device_id")))
            throw new IllegalStateException("指令记录归属不匹配");
        run.status = value.optString("status", "claim"); run.detail = value.optString("detail", "");
        run.runId = value.optLong("run_id"); run.updatedAt = value.optLong("updated_at");
        if ("running".equals(run.status)) run.finish(ctx, run.runId, "失败", "应用进程重启，本次执行已中断");
        return run;
    }

    private static void pollOnce(Context ctx, long generation) throws Exception {
        Prefs p = new Prefs(ctx);
        if (generation != sPollGeneration || !p.loggedIn() || p.syncDeviceId().isEmpty()) return;
        final String account, token, device;
        synchronized (p.sp) { account = p.accountId(); token = p.accountToken(); device = p.syncDeviceId(); }
        AgentEngine engine = AgentEngine.get(ctx);
        PendingCommand run = sPending;
        if (run != null && !run.matches(p)) {
            if (engine.isRunning()) return;
            sPending = null; run = null;
        }
        if (run == null) { run = restore(ctx, p, account, device); sPending = run; }
        if (run == null) {
            if (engine.isRunning()) return;
            JSONArray cmds = request(ctx, "GET", "/api/v1/device/commands", null).optJSONArray("commands");
            if (generation != sPollGeneration || cmds == null || !account.equals(p.accountId())
                    || !token.equals(p.accountToken()) || !device.equals(p.syncDeviceId())) return;
            for (int i = 0; i < cmds.length(); i++) {
                JSONObject c = cmds.optJSONObject(i);
                if (c == null || c.optString("command_id").isEmpty() || c.optString("task").isEmpty()) continue;
                run = new PendingCommand(c, account, device);
                run.save(ctx);
                sPending = run;
                break;
            }
        }
        if (run == null || !run.matches(new Prefs(ctx)) || generation != sPollGeneration) return;
        if (run.terminal()) {
            run.save(ctx); // 首次落盘失败时先重试持久化，再回报服务端。
            if (System.currentTimeMillis() - run.updatedAt > RESULT_TTL_MS) {
                log("指令 " + run.id + " 回报窗口已过期，保留任务归档");
            } else {
                ack(ctx, run, run.status, run.detail); // 失败抛出，不清除待回报记录。
                log("指令 " + run.id + " run=" + run.runId + " 回报 " + run.status + "：" + run.detail);
            }
            if (!sp(ctx).edit().remove(run.key()).commit()) throw new IllegalStateException("指令回报游标保存失败");
            if (sPending == run) sPending = null;
            return;
        }
        if ("running".equals(run.status) || engine.isRunning()) return;
        if (AgentA11yService.get() == null) {
            run.finish(ctx, 0, "失败", "目标设备未开启无障碍服务");
            return;
        }
        // 领取ACK成功并持久化执行状态后才启动，响应丢失时先重试领取，不重复执行动作。
        try { ack(ctx, run, "running", "目标设备已领取指令"); }
        catch (ApiException e) {
            if (e.httpCode == 404) {
                if (!sp(ctx).edit().remove(run.key()).commit()) throw new IllegalStateException("过期指令清理失败");
                if (sPending == run) sPending = null;
                log("指令 " + run.id + " 已过期或服务端已移除，未启动执行");
                return;
            }
            throw e;
        }
        if (!run.matches(new Prefs(ctx)) || generation != sPollGeneration) return;
        run.status = "running";
        try { run.save(ctx); }
        catch (Exception e) {
            run.status = "claim";
            try { run.save(ctx); } catch (Exception restoreError) { e.addSuppressed(restoreError); }
            throw e; // 未启动执行，下一轮仍按领取态处理。
        }
        final PendingCommand executing = run;
        if (!engine.start(REMOTE_PREFIX + run.task, run.id, (seq, id, result, detail) -> {
            if (!executing.id.equals(id)) throw new IllegalStateException("指令执行结果归属不匹配");
            try { executing.finish(ctx, seq, result, detail); }
            catch (Exception e) { android.util.Log.i(TAG, "终态持久化失败，保留内存结果供重试: " + e); }
        })) executing.finish(ctx, 0, "失败", "引擎未接受本次执行");
        else log("开始执行跨设备指令 " + run.id + "（来自 " + run.from + "）：" + run.task);
    }

    private static void ack(Context ctx, PendingCommand run, String status, String detail) throws Exception {
        if (!run.matches(new Prefs(ctx))) throw new IllegalStateException("指令所属会话已变化");
        JSONObject body = new JSONObject().put("command_id", run.id).put("status", status).put("detail", detail);
        request(ctx, "POST", "/api/v1/device/command/ack", body.toString());
    }
    private static SharedPreferences sp(Context ctx) { return new Prefs(ctx).sp; }
    private static JSONObject request(Context ctx, String method, String path, String body) throws Exception {
        Prefs p = new Prefs(ctx);
        String base = SyncClient.serverBase(ctx);
        if (base.isEmpty()) throw new IllegalStateException("未配置服务器地址");
        String account = p.accountId(), token = p.accountToken(), device = p.syncDeviceId();
        JSONObject response = AccountHttp.request(method, base + path, body, token, device);
        Prefs current = new Prefs(ctx);
        if (!account.equals(current.accountId()) || !token.equals(current.accountToken()) || !device.equals(current.syncDeviceId()))
            throw new IllegalStateException("指令响应所属会话已变化");
        return response;
    }
}
