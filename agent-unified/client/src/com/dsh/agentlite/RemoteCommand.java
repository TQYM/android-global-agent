// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 跨设备指令：在设备 1 上说「让设备 2 打开 B 站」，设备 2 自动执行。
 *
 * 角色分两半：
 *   - **下发方（设备 1）**：由 AgentEngine 的 {@code remote} 动作调用 {@link #send}；
 *   - **接收方（设备 2）**：{@link #startPolling} 定时来问「有我的指令吗」，
 *     取到就用 AgentEngine 执行，执行完回报结果。
 *
 * 为什么用轮询而不是长连接：客户端会被国产 ROM 杀后台，长连接不可靠；
 * 拉取天然自愈，断线重连不需要额外逻辑。
 *
 * 安全边界（服务端同步强制，客户端不假设服务端会拦）：
 *   - 只接受同账号设备之间的**定向**指令，不支持广播；
 *   - 指令 5 分钟未执行即作废（晚了再执行反而危险：用户早已不在那个语境）；
 *   - 敏感操作（支付/转账/验证码等）在**目标设备上**仍按原纪律 ask 确认——
 *     跨设备指令不继承下发方的任何授权。
 */
public final class RemoteCommand {

    private static final String TAG = "AgentLiteRemote";

    /**
     * 跨设备关键事件**同时**写 logcat 与 engine.log。
     *
     * <p>为什么必须落 engine.log（2026-09-15 跨设备真机测试教训）：此前跨设备事件只走
     * {@code android.util.Log}，而测试脚本、用户「调试台」、事后归档读的都是 engine.log。
     * 结果手机明明下发了指令、手表明明 ack 了 failed，两端 engine.log 里**一行都没有**，
     * 排障时只能靠翻 logcat 与猜——一度被误判成「指令根本没下发」。
     * 高频异常（每轮轮询失败）仍只走 logcat，避免刷爆文件。
     */
    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
        AgentEngine.staticLog("跨设备: " + msg);
    }
    /** 轮询间隔。太短费电，太长用户等得久；20s 是折中。 */
    private static final long POLL_INTERVAL_MS = 20_000;
    /** 收到指令后给任务文本加的标记：让模型知道这不是本机用户当面下达的。 */
    public static final String REMOTE_PREFIX = "[跨设备指令] ";

    /** 当前正在执行的跨设备指令的**发起设备 id**（不在执行跨设备指令时为 null）。
     *  用于引擎的接收侧防回环检查：不许把别人发来的指令原样转回发起方。 */
    public static String runningFromDevice() {
        return sRunningFromDevice;
    }

    private static ScheduledExecutorService sExec;
    private static volatile boolean sPolling;
    /** 正在执行、尚未回报终态的指令 id（跨轮询周期保存）。 */
    private static volatile String sRunningCommandId;
    private static volatile String sRunningFromDevice;
    /** 轮询计数：每 N 轮刷新一次同账号设备列表（见 startPolling）。 */
    private static volatile int sPollRounds;

    private RemoteCommand() { }

    // ---------- 设备列表（下发方用来解析「设备2」指的是谁） ----------

    /** 拉取同账号设备列表并缓存到 Prefs（供提示词注入与指代解析）。 */
    public static void refreshDevices(Context ctx) {
        try {
            JSONObject r = request(ctx, "GET", "/api/v1/account/me", null);
            JSONArray ds = r.optJSONArray("devices");
            if (ds != null) {
                sp(ctx).edit().putString("peer_devices", ds.toString()).apply();
                android.util.Log.i(TAG, "同账号设备列表已刷新：" + ds.length() + " 台");
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "设备列表刷新失败（保留旧缓存）: " + e);
        }
    }

    /** 缓存的设备列表（可能为空数组）。 */
    public static JSONArray cachedDevices(Context ctx) {
        try {
            return new JSONArray(sp(ctx).getString("peer_devices", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /**
     * 把用户口中的设备指代解析成 device_id。
     *
     * 支持三种指代：本机别名（本机/这台/自己）、序号（第 2 台/设备2/2）、
     * 以及名称/型号的子串匹配（"平板" → 设备2-平板）。解析不到返回 null。
     */
    public static String resolveDevice(Context ctx, String spoken) {
        if (spoken == null) return null;
        String s = spoken.trim();
        if (s.isEmpty()) return null;
        String mine = sp(ctx).getString("sync_device_id", "");
        if (s.contains("本机") || s.contains("这台") || s.contains("自己") || s.contains("当前设备")) {
            return mine;
        }
        JSONArray ds = cachedDevices(ctx);
        // 序号：从 "设备2" / "第2台" / 纯数字里抽
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        if (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx >= 1 && idx <= ds.length()) {
                return ds.optJSONObject(idx - 1).optString("device_id", "");
            }
        }
        // 名称 / 型号子串
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (d == null) continue;
            String name = d.optString("name", "");
            String model = d.optString("model", "");
            String brand = d.optString("brand", "");
            if ((!name.isEmpty() && (s.contains(name) || name.contains(s)))
                    || (!model.isEmpty() && s.contains(model))
                    || (!brand.isEmpty() && s.contains(brand))) {
                return d.optString("device_id", "");
            }
        }
        return null;
    }

    /** 给提示词注入的「同账号设备」块；无其他设备时返回空串。 */
    public static String deviceBlock(Context ctx) {
        JSONArray ds = cachedDevices(ctx);
        if (ds.length() == 0) return "";
        String mine = sp(ctx).getString("sync_device_id", "");
        StringBuilder b = new StringBuilder();
        b.append("\n\n【同账号设备】你可以用 remote 动作让**另一台**设备替你执行任务：\n");
        boolean any = false;
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("device_id", "");
            boolean self = id.equals(mine);
            b.append("- [").append(i + 1).append("] ")
             .append(d.optString("name", "未命名"))
             .append("（").append(d.optString("brand", "")).append(' ')
             .append(d.optString("model", "")).append("）")
             .append(self ? " ← 本机" : "").append('\n');
            if (!self) any = true;
        }
        if (!any) return "";
        b.append("用户说「让设备2打开B站」这类话时，输出 ")
         .append("{\"action\":\"remote\",\"device\":\"设备2\",\"task\":\"打开B站\"}。")
         .append("device 可以填上面的序号、名称或型号片段；**只能指定一台设备，不支持群发**。")
         .append("对方设备的用户仍需对敏感操作自行确认。\n");
        return b.toString();
    }

    // ---------- 下发（设备 1 侧） ----------

    /** 向指定设备下发一条任务，返回服务端回执（含 command_id）。 */
    public static JSONObject send(Context ctx, String toDeviceId, String task) throws Exception {
        JSONObject body = new JSONObject();
        body.put("to_device", toDeviceId);
        body.put("task", task);
        JSONObject r = request(ctx, "POST", "/api/v1/device/command", body.toString());
        JSONObject c = r.optJSONObject("command");
        if (c != null) {
            log("已下发指令 " + c.optString("command_id") + " → " + toDeviceId + "：" + task);
        }
        return c == null ? new JSONObject() : c;
    }

    // ---------- 轮询与执行（设备 2 侧） ----------

    /** 启动轮询。重复调用无副作用；未登录时内部跳过。 */
    public static synchronized void startPolling(Context ctx) {
        if (sPolling) return;
        final Context app = ctx.getApplicationContext();
        if (!new Prefs(app).loggedIn()) {
            android.util.Log.i(TAG, "未登录，不启动跨设备指令轮询");
            return;
        }
        sExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentlite-remote");
            t.setDaemon(true);
            return t;
        });
        sExec.scheduleWithFixedDelay(() -> {
            try {
                // 同账号设备列表会变（新设备登录、改名、换机）。不刷新的话
                // deviceBlock() 注入的列表是空的，模型会说「列表里找不到那台设备」
                // 而拒绝下发——真机实测（2026-09-15）：refreshDevices 此前**没有任何调用点**，
                // 缓存永远为空，跨设备因此永远走不通。
                if (sPollRounds++ % 3 == 0) {
                    refreshDevices(app);
                }
                pollOnce(app);
            } catch (Throwable t) {
                android.util.Log.i(TAG, "轮询异常（下轮重试）: " + t);
            }
        }, 3, POLL_INTERVAL_MS / 1000, TimeUnit.SECONDS);
        sPolling = true;
        android.util.Log.i(TAG, "跨设备指令轮询已启动（间隔 " + (POLL_INTERVAL_MS / 1000) + "s）");
    }

    public static synchronized void stopPolling() {
        if (sExec != null) {
            sExec.shutdownNow();
            sExec = null;
        }
        sPolling = false;
        sRunningCommandId = null;
        android.util.Log.i(TAG, "跨设备指令轮询已停止");
    }

    public static boolean isPolling() { return sPolling; }

    private static void pollOnce(Context ctx) {
        Prefs p = new Prefs(ctx);
        if (!p.loggedIn()) return;
        if (p.syncDeviceId().isEmpty()) return;

        AgentEngine engine = AgentEngine.get(ctx);

        // 1) 先看上一轮启动的任务是否已结束：结束了就回报终态
        if (sRunningCommandId != null && !engine.isRunning()) {
            String[] res = lastTaskResult(ctx);
            String status = "完成".equals(res[0]) ? "done" : "failed";
            ack(ctx, sRunningCommandId, status, res[1]);
            log("指令 " + sRunningCommandId + " 回报 " + status + "：" + res[1]);
            sRunningCommandId = null;
            sRunningFromDevice = null;
        }

        // 2) 拉取待执行指令
        JSONArray cmds;
        try {
            JSONObject r = request(ctx, "GET", "/api/v1/device/commands", null);
            cmds = r.optJSONArray("commands");
        } catch (Exception e) {
            android.util.Log.i(TAG, "拉取指令失败: " + e);
            return;
        }
        if (cmds == null || cmds.length() == 0) return;

        for (int i = 0; i < cmds.length(); i++) {
            JSONObject c = cmds.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("command_id", "");
            String task = c.optString("task", "");
            if (id.isEmpty() || task.isEmpty()) continue;

            // 无障碍没开：明确回报失败，别让发起方一直等
            if (AgentA11yService.get() == null) {
                ack(ctx, id, "failed", "目标设备未开启无障碍服务");
                log("收到指令 " + id + " 但本机无障碍未连接 → 已回报 failed（发起方会看到「目标设备未开启无障碍服务」）");
                continue;
            }
            // 引擎正忙：**不回报**，留待下轮重试；5 分钟后自然过期
            if (engine.isRunning()) {
                log("引擎正忙，指令 " + id + " 留待下轮");
                continue;
            }
            if (!engine.start(REMOTE_PREFIX + task)) {
                log("指令 " + id + " 启动失败，留待下轮");
                continue;
            }
            sRunningCommandId = id;
            sRunningFromDevice = c.optString("from_device", "");
            ack(ctx, id, "running", "目标设备已开始执行");
            log("开始执行跨设备指令 " + id + "（来自 " + sRunningFromDevice + "）：" + task);
            return;   // 一次只跑一条，避免多条指令互相打断
        }
    }

    /** 读本机任务历史的最后一条，返回 [结果, 说明]。 */
    private static String[] lastTaskResult(Context ctx) {
        try {
            java.util.List<TaskHistory.Record> rs = TaskHistory.list(ctx);
            if (!rs.isEmpty()) {
                TaskHistory.Record r = rs.get(0);   // list() 是最新在前
                return new String[]{r.result == null ? "" : r.result,
                                    r.detail == null ? "" : r.detail};
            }
        } catch (Exception ignored) { }
        return new String[]{"", "执行结束"};
    }

    private static void ack(Context ctx, String commandId, String status, String detail) {
        try {
            JSONObject body = new JSONObject();
            body.put("command_id", commandId);
            body.put("status", status);
            body.put("detail", detail == null ? "" : detail);
            request(ctx, "POST", "/api/v1/device/command/ack", body.toString());
        } catch (Exception e) {
            android.util.Log.i(TAG, "回报指令状态失败（不影响本地执行）: " + e);
        }
    }

    // ---------- HTTP ----------

    private static SharedPreferences sp(Context ctx) {
        return new Prefs(ctx).sp;
    }

    private static JSONObject request(Context ctx, String method, String path, String body)
            throws Exception {
        Prefs p = new Prefs(ctx);
        String base = SyncClient.serverBase(ctx);
        if (base.isEmpty()) throw new IllegalStateException("未配置服务器地址");
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        TlsPin.apply(conn);   // 自签服务端需要证书 pin
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + p.accountToken());
        conn.setRequestProperty("X-Device-Id", p.syncDeviceId());
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readAll(is);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + text);
        }
        return new JSONObject(text);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
