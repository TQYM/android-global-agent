// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 远程信息同步客户端（AgentLite ⇄ agentlite-server）。
 *
 * 设计要点：
 *  1. **不阻塞主线程**：全部网络 IO 在单线程 executor 上串行执行；
 *     MemoryStore.add 是在 UI 线程调用的，这里必须立刻返回。
 *  2. **证书 pin**：普通账号JSON请求共用 AccountHttp 与缓存的 TlsPin.factory()；
 *     构建时内置服务端CA，主机名校验保持开启，不在每次同步重新创建TLS上下文。
 *  3. **账号鉴权**：推送/拉取/局域网会合统一用 `Authorization: Bearer <账号令牌>`，
 *     并在每个设备级接口上带 `X-Device-Id`（服务端会校验设备归属账号）。
 *     账号令牌失效（401）时标记登录失效并回调 UI 引导重新登录，不静默重试。
 *  4. **幂等**：每条记录用内容哈希当 id，服务端按 id 去重；因此"推到一半失败后重推"
 *     不会产生副本。本地用行号游标（sync_history_index / sync_memory_index）避免全量重传。
 *  5. **远程配置白名单**：只接受行为类开关，**显式拒绝 base_url / api_key**——
 *     这正是 SECURITY_REVIEW P1-1「未授权改写 BaseURL → API Key 定向窃取」的利用链，
 *     即便服务端已被信任也不把它变成一条可改写出站地址的通道。
 */
public final class SyncClient {

    private static final String TAG = "AgentLiteSync";
    /** 局域网直连路径的日志标签：与 LanSync 内部一致，便于 `adb logcat -s AgentLiteLan` 一次看全。 */
    private static final String LAN_TAG = "AgentLiteLan";
    private static final int BATCH_LIMIT = 50;
    // 三来源各保留2MiB保守字节预算，另留2MiB给状态/信封，单请求不超服务端8MiB。
    private static final long SOURCE_BYTE_BUDGET = 2L * 1024 * 1024;
    /** 局域网会合端口（服务端只做介绍人，数据不过服务器）。 */
    public static final int LAN_PORT = 18444;

    /** 允许被服务端下发的配置键（白名单，越权键一律忽略）。 */
    private static final String[] CONFIG_WHITELIST = {
            "max_steps", "planner_model", "vision", "spec_inject", "spec_clamp"
    };

    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "agentlite-sync");
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 合并并发触发：一次同步进行中再有触发，只记一个待办，结束后补跑一次。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    // ---------- 登录失效回调 ----------

    /** 账号令牌失效时通知 UI（在主线程回调）。 */
    public interface OnAuthInvalid { void onAuthInvalid(); }

    /** 局域网会合结果（在主线程回调，文本已组装好可直接展示）。 */
    public interface OnLanResult { void onLanResult(String text); }

    private static volatile OnAuthInvalid sAuthListener;

    /** Activity 在前台时注册；onDestroy 里传 null 注销，避免持有已销毁的界面。 */
    public static void setAuthInvalidListener(OnAuthInvalid l) { sAuthListener = l; }

    private SyncClient() { }

    // ---------- 对外触发点 ----------

    /** 任务终态落档后调用（由 TaskHistory.append 内部触发）。 */
    public static void onTaskFinished(Context ctx) {
        trigger(ctx);
    }

    /** 记忆写入后调用（由 MemoryStore.add 内部触发）。 */
    public static void onMemoryAdded(Context ctx) {
        trigger(ctx);
    }

    /** 手动触发一次完整同步。 */
    public static void syncNow(Context ctx) {
        trigger(ctx);
    }

    private static void trigger(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        Prefs p = new Prefs(app);
        if (!p.syncEnabled()) return;
        if (!p.loggedIn()) {
            // 未登录不发任何请求（也不去调用已下线的 /api/v1/device/register）
            if (p.accountToken().isEmpty()) p.setSyncStatus("未登录：请先登录账号");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            PENDING.set(true);   // 已有同步在跑，登记一次补跑
            return;
        }
        EXEC.execute(() -> {
            try {
                runSync(app);
            } catch (Throwable t) {
                android.util.Log.i(TAG, "同步异常: " + t);
                new Prefs(app).setSyncStatus("异常: " + t.getClass().getSimpleName());
            } finally {
                RUNNING.set(false);
                if (PENDING.compareAndSet(true, false)) trigger(app);
            }
        });
    }

    // ---------- 主流程 ----------

    private static void runSync(Context ctx) {
        Prefs p = new Prefs(ctx);
        String server = serverBase(ctx);
        if (server.isEmpty()) {
            p.setSyncStatus("未配置服务器地址");
            return;
        }
        if (!p.loggedIn()) return;

        try {
            if (p.syncDeviceId().isEmpty()) bindDevice(ctx, p);
            if (p.syncDeviceId().isEmpty()) return;   // 绑定失败：状态已写入，下次再试
            syncOnce(ctx, p, server);
        } catch (ApiException e) {
            if (e.isAuthFailure()) {
                markAuthInvalid(ctx, p, "登录已失效：" + e.readable());
                return;
            }
            if (e.isDeviceUnbound()) {
                // 服务端换了账号或设备被解绑：清掉本机 device_id 重绑一次再同步
                p.setSyncDeviceId("");
                bindDevice(ctx, p);
                if (p.syncDeviceId().isEmpty()) return;
                try {
                    syncOnce(ctx, p, server);
                } catch (Exception e2) {
                    p.setSyncStatus("同步失败：" + readable(e2));
                }
                return;
            }
            android.util.Log.i(TAG, "同步失败: " + e);
            p.setSyncStatus("同步失败：" + e.readable());
        } catch (Exception e) {
            android.util.Log.i(TAG, "同步失败: " + e);
            p.setSyncStatus("同步失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 一次完整同步：**局域网优先，云端回退**。
     *
     * 顺序：
     *  1) 先走局域网：会合拿到同账号同出口的邻居，逐个 peer、逐个 ip 尝试 LanSync.syncWithPeer；
     *     任一成功（返回值 >= 0）即认为本批次数据已由局域网通道交付，**跳过云端 push/pull**；
     *  2) 局域网不可用（无邻居 / 全部 peer 不通 / 会合请求本身失败 / 未派生密钥 / 无内网地址）
     *     → 完全落到原有云端逻辑，一步不少。
     *
     * 鉴权类失败（401/403）仍然按老规矩直接抛出交给 runSync 处理。
     */
    private static void syncOnce(Context ctx, Prefs p, String server) throws Exception {
        LanOutcome lan = tryLanFirst(ctx, p, server);
        if (lan.ok) {
            android.util.Log.i(LAN_TAG, "任务历史走局域网直连 peer=" + lan.peerId + "@" + lan.peerIp
                    + " 合并=" + lan.merged + " 条，本轮跳过任务历史上行");
        }

        // 一次上传 tasks + peer tasks + memories + status；LAN 成功仅跳过本机历史。
        int[] pushed = pushBatch(ctx, p, server, !lan.ok);
        int pushedTasks = pushed[0] + (lan.ok ? lan.merged : 0);
        int pushedMems = pushed[1];

        boolean pulled;
        try {
            pulled = pullConfig(ctx, p, server);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            android.util.Log.i(TAG, "配置下行失败: " + e);
            pulled = false;
        }

        if (pushedTasks > 0 || pushedMems > 0 || pulled) {
            p.setSyncStatus((lan.ok ? "局域网+" + lan.merged + " · " : "")
                    + "已同步 · 任务+" + pushedTasks + " 记忆+" + pushedMems
                    + (pulled ? " · 配置已更新" : "")
                    + " · " + nowStamp());
        } else {
            p.setSyncStatus("已连接 · 无新增 · " + nowStamp());
        }
        p.setSyncLastOk(System.currentTimeMillis());
    }

    // ---------- 局域网优先（LanSync 接线） ----------

    /** 局域网直连的尝试结果。ok=true 表示本批次已由局域网通道交付，本次不再走云端。 */
    private static final class LanOutcome {
        boolean ok;
        int merged;          // 对端合并（入库）的条数
        String peerId = "";
        String peerIp = "";
    }

    /**
     * 局域网优先的一次尝试。**任何失败都只是返回 ok=false，绝不抛出**——
     * 局域网是优化项，判断失败必须无条件回退云端，不能让同步卡死。
     *
     * 会合（/api/v1/lan/rendezvous）是设备级接口，走云端拿邻居列表；
     * 数据本身只在设备之间走 LanSync 的加密通道。
     */
    private static LanOutcome tryLanFirst(Context ctx, Prefs p, String server) {
        final LanOutcome r = new LanOutcome();
        try {
            if (!LanSync.enabled(ctx)) {
                android.util.Log.i(LAN_TAG, "局域网同步已在配置中关闭，走云端");
                return r;
            }
            if (server.isEmpty() || !p.loggedIn() || p.syncDeviceId().isEmpty()) {
                return r;   // 未登录/未绑定设备：会合本身就需要账号令牌 + X-Device-Id
            }
            // 没有站点本地地址（例如只有蜂窝网络）时连邻居都不可能存在，直接省掉一次会合请求
            List<String> myIps = LanSync.lanIPs();
            if (myIps.isEmpty()) {
                android.util.Log.i(LAN_TAG, "本机无局域网地址，走云端");
                return r;
            }

            int[] endOut = new int[1];
            JSONArray tasks = buildTaskBatch(ctx, p, endOut);
            if (tasks == null) {
                // LanSync 的线协议只承载任务记录：没有新增任务就没有可交付的数据，
                // 这一轮直接走云端（顺带把记忆/状态/配置拉全），避免"空交换"把云端整轮跳过。
                android.util.Log.i(LAN_TAG, "无新增任务，本轮走云端");
                return r;
            }

            JSONObject rv = rendezvous(ctx, p, myIps);
            JSONArray peers = rv.optJSONArray("peers");
            if (peers == null || peers.length() == 0) {
                android.util.Log.i(LAN_TAG, "同账号暂无同网设备，走云端");
                return r;
            }

            for (int i = 0; i < peers.length(); i++) {
                JSONObject peer = peers.optJSONObject(i);
                if (peer == null) continue;
                String peerId = peer.optString("device_id", "");
                if (peerId.isEmpty() || peerId.equals(p.syncDeviceId())) continue;   // 跳过自己
                int port = peer.optInt("lan_port", LanSync.DEFAULT_PORT);
                if (port < 1 || port > 65535) port = LanSync.DEFAULT_PORT;

                JSONArray ips = peer.optJSONArray("lan_ips");
                if (ips == null || ips.length() == 0) {
                    android.util.Log.i(LAN_TAG, "邻居 " + peerId + " 未上报局域网地址，换下一个");
                    continue;
                }
                // lan_ips 可能有多个（多网卡/多网段）：逐个试，第一个成功的即用
                for (int j = 0; j < ips.length(); j++) {
                    String ip = ips.optString(j, "");
                    if (ip.isEmpty()) continue;
                    int merged = LanSync.syncWithPeer(ctx, ip, port, peerId, tasks, p.syncCursor());
                    if (merged >= 0) {
                        // 记录已由对端入库（两端同一套内容哈希去重），本地游标推进到本批次末尾；
                        // 对端下次上行时会把它并进云端，因此云端不会漏数据。
                        p.setSyncHistoryIndex(endOut[0]);
                        r.ok = true;
                        r.merged = merged;
                        r.peerId = peerId;
                        r.peerIp = ip;
                        android.util.Log.i(LAN_TAG, "局域网直连成功 peer=" + peerId + "@" + ip + ":"
                                + port + " 推送=" + tasks.length() + " 合并=" + merged + " 条");
                        return r;
                    }
                    android.util.Log.i(LAN_TAG, "邻居 " + peerId + " 的 " + ip + ":" + port
                            + " 直连失败（返回 -1），换下一个地址");
                }
            }
            android.util.Log.i(LAN_TAG, "所有邻居直连均失败，回退云端");
        } catch (Throwable t) {
            // 包括会合请求的网络异常：一律静默回退，不影响云端同步
            android.util.Log.i(LAN_TAG, "局域网优先路径异常，回退云端: " + t);
        }
        return r;
    }

    /**
     * 组装待上行的任务批次（内容哈希 id + 原始记录 data），
     * 局域网推送与云端 push 共用同一份载荷，保证两条通道的数据形状一致。
     *
     * @param endOut 长度 >=1 的数组，回传本批次结束后的行号游标
     * @return 待上行批次；**没有新增记录时返回 null**（调用方保持"不发空请求"的原语义）
     */
    private static JSONArray buildTaskBatch(Context ctx, Prefs p, int[] endOut) throws Exception {
        List<String> lines = TaskHistory.rawLines(ctx);
        int idx = p.syncHistoryIndex();
        if (idx > lines.size()) idx = 0;          // 历史被清空/截断，游标自愈
        if (idx >= lines.size()) return null;
        JSONArray tasks = new JSONArray();
        int cap = Math.min(lines.size(), idx + BATCH_LIMIT), end = idx;
        long bytes = 0;
        for (int i = idx; i < cap; i++) {
            String line = lines.get(i);
            long size = 2L * line.getBytes(StandardCharsets.UTF_8).length + 1024;
            if (size > SOURCE_BYTE_BUDGET) throw new IllegalStateException("单条任务超出同步字节预算，游标保持不变");
            if (bytes + size > SOURCE_BYTE_BUDGET) break;
            bytes += size;
            JSONObject o = new JSONObject(line);
            JSONObject item = new JSONObject();
            item.put("id", sha256Hex(line).substring(0, 32));
            item.put("ts", o.optString("time"));
            item.put("data", o);              // 原始记录整条上行，服务端/对端不解析
            tasks.put(item);
            end = i + 1;
        }
        endOut[0] = end;
        return tasks;
    }

    /** 401：把登录状态标记为失效，并通知前台界面提示重新登录。 */
    private static void markAuthInvalid(Context ctx, Prefs p, String why) {
        p.setAccountInvalid(true);
        p.setSyncStatus(why + "（" + nowStamp() + "）");
        final OnAuthInvalid l = sAuthListener;
        if (l != null) MAIN.post(l::onAuthInvalid);
        android.util.Log.i(TAG, "账号令牌失效: " + why);
    }

    private static String readable(Exception e) {
        if (e instanceof ApiException) return ((ApiException) e).readable();
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---------- 设备绑定 ----------

    /** 账号已登录但本机尚未绑定时补绑（幂等：同一 fingerprint 视为同一台设备）。 */
    private static void bindDevice(Context ctx, Prefs p) {
        try {
            AccountClient.bindDeviceSync(ctx, p);
        } catch (ApiException e) {
            if (e.isAuthFailure()) {
                markAuthInvalid(ctx, p, "登录已失效：" + e.readable());
            } else {
                p.setSyncStatus("设备绑定失败：" + e.readable());
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "设备绑定失败: " + e);
            p.setSyncStatus("设备绑定失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 设备指纹：ANDROID_ID 已按应用签名隔离，足以区分设备且不涉及隐私。
     * 账号登录/注册与同步补绑共用同一算法，保证服务端把两次绑定识别为同一台设备。
     */
    public static String deviceFingerprint(Context ctx) {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) { }
        return sha256Hex(androidId + "|" + Build.MANUFACTURER + "|" + Build.MODEL
                + "|" + Build.DEVICE + "|agentlite");
    }

    // ---------- 上行 ----------

    private static int[] pushBatch(Context ctx, Prefs p, String server, boolean includeHistory) throws Exception {
        final String account, token, device;
        final int oldHistory, oldPeer, oldMemory;
        synchronized (p.sp) {
            account = p.accountId(); token = p.accountToken(); device = p.syncDeviceId();
            oldHistory = p.syncHistoryIndex(); oldPeer = p.sp.getInt("sync_peer_index", 0); oldMemory = p.syncMemoryIndex();
        }
        int[] historyEnd = {oldHistory};
        JSONArray tasks = includeHistory ? buildTaskBatch(ctx, p, historyEnd) : null;
        if (tasks == null) tasks = new JSONArray();
        int localCount = tasks.length();

        List<String> peerLines = LanSync.pendingPeerRecords(ctx);
        int peerIndex = oldPeer;
        if (peerIndex > peerLines.size()) peerIndex = 0;
        int peerCap = Math.min(peerLines.size(), peerIndex + BATCH_LIMIT), peerEnd = peerIndex;
        long peerBytes = 0;
        for (int i = peerIndex; i < peerCap; i++) {
            String line = peerLines.get(i);
            long size = 2L * line.getBytes(StandardCharsets.UTF_8).length + 1024;
            if (size > SOURCE_BYTE_BUDGET) throw new IllegalStateException("单条同伴记录超出同步字节预算，游标保持不变");
            if (peerBytes + size > SOURCE_BYTE_BUDGET) break;
            peerBytes += size;
            JSONObject w = new JSONObject(line);
            JSONObject data = w.optJSONObject("data");
            String origin = w.optString("origin_device_id", "");
            if (data == null || origin.isEmpty()) throw new IllegalStateException("同伴记录缺少来源或数据，游标保持不变");
            JSONObject item = new JSONObject();
            String id = w.optString("id", "");
            item.put("id", id.isEmpty() ? sha256Hex(peerLines.get(i)).substring(0, 32) : id);
            item.put("ts", w.optString("ts", ""));
            item.put("data", data);
            item.put("origin_device_id", origin);
            tasks.put(item);
            peerEnd = i + 1;
        }

        List<MemoryStore.Item> all = MemoryStore.list(ctx);
        int memoryIndex = oldMemory;
        if (memoryIndex > all.size()) memoryIndex = 0;
        int memoryCap = Math.min(all.size(), memoryIndex + BATCH_LIMIT), memoryEnd = memoryIndex;
        JSONArray memories = new JSONArray();
        long memoryBytes = 0;
        for (int i = memoryIndex; i < memoryCap; i++) {
            MemoryStore.Item it = all.get(i);
            // 最坏控制字符需6字节JSON转义；无需为算大小再编码整份消息。
            long size = 6L * (it.time.getBytes(StandardCharsets.UTF_8).length
                    + it.text.getBytes(StandardCharsets.UTF_8).length) + 1024;
            if (size > SOURCE_BYTE_BUDGET) throw new IllegalStateException("单条记忆超出同步字节预算，游标保持不变");
            if (memoryBytes + size > SOURCE_BYTE_BUDGET) break;
            memoryBytes += size;
            JSONObject item = new JSONObject();
            item.put("id", sha256Hex(it.time + "|" + it.text).substring(0, 32));
            item.put("ts", it.time);
            JSONObject data = new JSONObject();
            data.put("time", it.time);
            data.put("text", it.text);
            item.put("data", data);
            memories.put(item);
            memoryEnd = i + 1;
        }
        JSONObject status = new JSONObject();
        status.put("battery", batteryPct(ctx));
        status.put("charging", isCharging(ctx));
        status.put("a11y", A11yGuard.isEnabledFlag(ctx));
        status.put("model", p.model());
        status.put("tokens_total", p.totalTokens());
        status.put("root_mode", p.rootMode());
        status.put("app", "agent-lite");
        JSONObject body = new JSONObject();
        body.put("tasks", tasks);
        body.put("memories", memories);
        body.put("status", status);
        if (!account.equals(p.accountId()) || !token.equals(p.accountToken()) || !device.equals(p.syncDeviceId()))
            throw new IllegalStateException("同步会话已变化");
        JSONObject response = AccountHttp.request("POST", server + "/api/v1/sync/push", body.toString(), token, device);
        int accepted = response.optInt("accepted", -1), duplicates = response.optInt("duplicates", -1);
        long delivered = (long) accepted + duplicates;
        if (accepted < 0 || duplicates < 0 || response.optInt("rejected", -1) != 0
                || delivered != tasks.length() + memories.length())
            throw new IllegalStateException("同步批次未被完整接收，游标保持不变");
        synchronized (p.sp) {
            if (!account.equals(p.accountId()) || !token.equals(p.accountToken()) || !device.equals(p.syncDeviceId()))
                throw new IllegalStateException("同步响应所属会话已变化");
            android.content.SharedPreferences.Editor cursor = p.sp.edit()
                    .putInt("sync_peer_index", peerEnd).putInt("sync_memory_index", memoryEnd);
            if (includeHistory && localCount > 0) cursor.putInt("sync_history_index", historyEnd[0]);
            if (!cursor.commit()) {
                // Android commit(false) 仍可能已更新内存；同步还原，不能把失败当已上传。
                p.sp.edit().putInt("sync_history_index", oldHistory).putInt("sync_peer_index", oldPeer)
                        .putInt("sync_memory_index", oldMemory).commit();
                throw new IllegalStateException("同步游标保存失败，已还原内存游标");
            }
        }
        return new int[]{tasks.length(), memories.length()};
    }

    // ---------- 下行 ----------

    private static boolean pullConfig(Context ctx, Prefs p, String server) throws Exception {
        final String account = p.accountId(), token = p.accountToken(), device = p.syncDeviceId();
        JSONObject response = AccountHttp.request("GET", server + "/api/v1/config", null, token, device);
        if (!account.equals(p.accountId()) || !token.equals(p.accountToken()) || !device.equals(p.syncDeviceId()))
            throw new IllegalStateException("配置响应所属会话已变化");
        JSONObject cfg = response.optJSONObject("config");
        if (cfg == null) return false;
        int ver = cfg.optInt("version", 0);
        if (ver == 0 || ver == p.syncConfigVersion()) return false;   // 版本未变，不重复应用

        JSONObject values = cfg.optJSONObject("values");
        if (values == null) return false;
        List<String> applied = new ArrayList<>();
        for (String key : CONFIG_WHITELIST) {
            if (!values.has(key)) continue;
            applyConfigKey(p, key, values.get(key));
            applied.add(key);
        }
        p.setSyncConfigVersion(ver);
        if (!applied.isEmpty()) {
            android.util.Log.i(TAG, "远程配置已应用: " + applied);
        } else {
            android.util.Log.i(TAG, "远程配置 v" + ver + " 无可应用的白名单键（"
                    + joinKeys(values) + "）");
        }
        return !applied.isEmpty();
    }

    /** 只落地白名单内的行为开关。base_url / api_key 永不下发——见类注释第 5 条。 */
    private static void applyConfigKey(Prefs p, String key, Object v) {
        switch (key) {
            case "max_steps":
                if (v instanceof Number) p.sp.edit().putInt("max_steps", ((Number) v).intValue()).apply();
                break;
            case "planner_model":
                if (v instanceof String) p.setPlannerModel((String) v);
                break;
            case "vision":
                if (v instanceof Boolean) p.sp.edit().putBoolean("vision", (Boolean) v).apply();
                break;
            case "spec_inject":
                if (v instanceof Boolean) p.setSpecInject((Boolean) v);
                break;
            case "spec_clamp":
                if (v instanceof Boolean) p.setSpecClamp((Boolean) v);
                break;
            default:
                break;   // 白名单外：忽略
        }
    }

    // ---------- 局域网会合 ----------

    /**
     * 上报本机局域网位置并取回同账号同网段的邻居设备。
     * 请求带 X-Device-Id（设备级接口），结果在主线程回调。
     */
    public static void rendezvousNow(Context ctx, OnLanResult cb) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            String text;
            try {
                text = doRendezvous(app);
            } catch (Exception e) {
                android.util.Log.i(TAG, "局域网会合失败: " + e);
                text = "局域网会合失败：" + readable(e);
            }
            final String out = text;
            if (cb != null) MAIN.post(() -> cb.onLanResult(out));
        });
    }

    /**
     * 会合请求本体（同步方法，必须在后台线程调用）：上报本机局域网地址与端口，
     * 取回同账号、同公网出口的邻居列表。云端同步路径与设置页的"局域网会合"按钮共用这一份实现。
     */
    private static JSONObject rendezvous(Context ctx, Prefs p, List<String> ips) throws Exception {
        JSONArray arr = new JSONArray();
        for (String ip : ips) arr.put(ip);
        JSONObject body = new JSONObject();
        body.put("lan_ips", arr);
        body.put("port", LanSync.port(ctx));   // 与 LanSync 实际监听端口保持一致
        return AccountHttp.request("POST", serverBase(ctx) + "/api/v1/lan/rendezvous",
                body.toString(), p.accountToken(), p.syncDeviceId());
    }

    private static String doRendezvous(Context ctx) throws Exception {
        Prefs p = new Prefs(ctx);
        String server = serverBase(ctx);
        if (server.isEmpty()) return "未配置服务器地址";
        if (!p.loggedIn()) return "未登录：请先登录账号";
        if (p.syncDeviceId().isEmpty()) {
            bindDevice(ctx, p);
            if (p.syncDeviceId().isEmpty()) return "本机尚未绑定到账号，无法会合";
        }

        List<String> ips = lanIPv4();
        JSONObject r = rendezvous(ctx, p, ips);
        int myPort = LanSync.port(ctx);

        String mine = ips.isEmpty() ? "未取到局域网 IP" : String.join(", ", ips);
        JSONArray peers = r.optJSONArray("peers");
        if (peers == null || peers.length() == 0) {
            return "同账号暂无同网设备\n本机局域网地址：" + mine + " : " + myPort;
        }
        StringBuilder sb = new StringBuilder("同网设备 " + peers.length() + " 台：");
        for (int i = 0; i < peers.length(); i++) {
            JSONObject d = peers.optJSONObject(i);
            if (d == null) continue;
            JSONArray pip = d.optJSONArray("lan_ips");
            String ip = (pip != null && pip.length() > 0) ? pip.optString(0, "?") : "?";
            sb.append("\n· ").append(d.optString("name", "设备"))
                    .append("（").append(d.optString("model", "未知"))
                    .append("） ").append(ip).append(':').append(d.optInt("lan_port", 0))
                    .append(" · ").append(d.optLong("seen_sec", 0)).append(" 秒前");
        }
        sb.append("\n本机：").append(mine).append(" : ").append(myPort);
        return sb.toString();
    }

    /** 枚举本机非回环 IPv4 地址（P2P 直连用；<=8 个，服务端另有截断）。 */
    private static List<String> lanIPv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String h = a.getHostAddress();
                    if (h == null || !h.contains(".")) continue;   // 只取 IPv4
                    if (!out.contains(h)) out.add(h);
                    if (out.size() >= 8) return out;
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------- HTTP ----------

    /** 服务器地址（去掉尾部斜杠）；未配置时返回空串。 */
    public static String serverBase(Context ctx) {
        String s = new Prefs(ctx).syncServer();
        if (s == null) return "";
        return trimSlash(s.trim());
    }

    /**
     * 发一次 JSON 请求并解析响应。
     *
     * @param token    账号令牌；为空则不带 Authorization 头（注册/登录用）
     * @param deviceId 设备级接口必填，非空时带 X-Device-Id 头
     * @throws ApiException 非 2xx 响应（含服务端 error 字段）
     */
    // ---------- 状态采集与工具 ----------

    private static int batteryPct(Context ctx) {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return -1;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return scale > 0 ? level * 100 / scale : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isCharging(Context ctx) {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int s = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return s == BatteryManager.BATTERY_STATUS_CHARGING
                    || s == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 安全拼接 JSON 键名。空对象必须走这里——直接 keys().next() 会抛
     * NoSuchElementException（服务端默认下发 {"version":1,"values":{}}，
     * 早期版本因此每次首拉都误报"配置下行失败"）。
     */
    private static String joinKeys(JSONObject o) {
        if (o == null || o.length() == 0) return "values 为空";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.next());
        }
        return sb + " 被忽略（不在白名单内）";
    }

    private static String nowStamp() {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
