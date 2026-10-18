// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

/**
 * 局域网直连同步（P2P）——同账号设备在同一内网时，数据不过云服务器。
 *
 * 动机：同账号多设备（手机 + 平板）常常同处一个内网。若全部流量都绕云端，
 * 既浪费 ECS 的出网流量（阿里云按流量计费），也平白增加延迟。
 *
 * 安全模型（对应线协议文档 §4，两端必须逐字节一致）：
 *   - 对称密钥由**账号密钥**本地派生，服务器只公布参数、**不经手密钥**；
 *     因此即使服务器失陷也无法解密局域网流量。
 *   - 报文一律 AES-256-GCM 封装，一次性覆盖机密性 + 完整性 + 来源认证。
 *   - 抗重放：明文内时间戳 ±120s + (device_id, nonce) 滚动窗口。
 *     仅有随机 nonce 不够——重放旧报文时密文本身仍然合法。
 *
 * 为什么必须加密而不是「明文 HTTP + HMAC」：设备间互相同步必然有一方监听端口，
 * 这正是 SECURITY_REVIEW 里 agentd-apk 8081「绑定 0.0.0.0 + 无鉴权」那类暴露面。
 * AEAD 用一次原语解决鉴权与机密，出错面比「明文 + 另加 HMAC」小。
 */
public final class LanSync {

    private static final String TAG = "AgentLiteLan";
    public static final int DEFAULT_PORT = 18444;
    private static final String PATH = "/lan/v1/sync";
    private static final String AAD_PREFIX = "agentlite-lan-v1|";
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final long TS_WINDOW_SEC = 120;
    private static final int MAX_BODY = 4 << 20; // 请求/响应单信封 4MiB

    /** 局域网密钥派生参数，必须与服务端 lan_key_params 一致。 */
    private static final String KDF_SALT_PREFIX = "agentlite-lan|";
    private static final int KDF_ITERATIONS = 100_000;
    private static final int KDF_KEY_BITS = 256;

    private static final SecureRandom RNG = new SecureRandom();

    /** 已见过的 (device_id, nonce)，用于抗重放。5 分钟滚动窗口。 */
    private static final Map<String, Long> SEEN = Collections.synchronizedMap(new HashMap<>());
    private static final long SEEN_TTL_MS = 5 * 60 * 1000L;

    private static volatile ServerSocket sServer;
    private static volatile Thread sAcceptor;
    private static volatile boolean sRunning;

    private LanSync() { }

    // ---------- 配置读写（复用 Prefs 的 SharedPreferences，键名与 Prefs 约定一致） ----------

    // 说明：设备 ID 的键名是 Prefs 的 "sync_device_id"。早期 LanSync 误读裸键
    // "device_id"，导致发送方 device_id 恒为空、接收方一律回 403，局域网通道
    // 静默永远不通。此处直接读正确键名，不再依赖配置层做镜像。
    private static SharedPreferences sp(Context ctx) {
        return new Prefs(ctx).sp;
    }

    public static boolean enabled(Context ctx) {
        return sp(ctx).getBoolean("lan_enabled", true);
    }

    public static int port(Context ctx) {
        return sp(ctx).getInt("lan_port", DEFAULT_PORT);
    }

    /** 登录成功后调用：从账号密钥派生出局域网对称密钥并保存（不保存账号密钥原文）。 */
    public static void deriveAndStoreKey(Context ctx, String accountId, String accountKey) {
        try {
            byte[] key = deriveKey(accountKey, accountId);
            sp(ctx).edit().putString("lan_key", Base64.encodeToString(key, Base64.NO_WRAP)).apply();
            android.util.Log.i(TAG, "局域网密钥已派生（account=" + accountId + "）");
        } catch (Exception e) {
            android.util.Log.i(TAG, "局域网密钥派生失败: " + e);
        }
    }

    private static byte[] storedKey(Context ctx) {
        String b64 = sp(ctx).getString("lan_key", "");
        if (b64.isEmpty()) return null;
        try {
            return Base64.decode(b64, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 与服务端 lan_key_params 对齐的派生实现（Java 侧用 SecretKeyFactory 复现）。 */
    static byte[] deriveKey(String accountKey, String accountId) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(
                accountKey.toCharArray(),
                (KDF_SALT_PREFIX + accountId).getBytes(StandardCharsets.UTF_8),
                KDF_ITERATIONS, KDF_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // ---------- 本机局域网地址 ----------

    /** 返回本机所有站点本地（内网）IPv4 地址。 */
    public static List<String> lanIPs() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "枚举局域网地址失败: " + e);
        }
        return out;
    }

    // ---------- 加密封装 ----------

    static String seal(byte[] key, String aad, String plaintext) throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        RNG.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] env = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, env, 0, nonce.length);
        System.arraycopy(ct, 0, env, nonce.length, ct.length);
        return Base64.encodeToString(env, Base64.NO_WRAP);
    }

    /** 解密并校验 AAD。密文被篡改、AAD 不匹配或密钥不对都会抛异常。 */
    static String open(byte[] key, String aad, String envelope) throws Exception {
        byte[] env = Base64.decode(envelope, Base64.NO_WRAP);
        if (env.length <= NONCE_LEN) throw new IllegalArgumentException("envelope too short");
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(env, 0, nonce, 0, NONCE_LEN);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] pt = c.doFinal(env, NONCE_LEN, env.length - NONCE_LEN);
        return new String(pt, StandardCharsets.UTF_8);
    }

    // ---------- 抗重放 ----------

    private static boolean freshAndUnseen(String deviceId, String nonceB64, long ts) {
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > TS_WINDOW_SEC) return false;
        long nowMs = System.currentTimeMillis();
        // 顺手清理过期项，避免 map 无界增长
        synchronized (SEEN) {
            if (SEEN.size() > 4096) {
                List<String> dead = new ArrayList<>();
                for (Map.Entry<String, Long> e : SEEN.entrySet()) {
                    if (nowMs - e.getValue() > SEEN_TTL_MS) dead.add(e.getKey());
                }
                for (String k : dead) SEEN.remove(k);
            }
            String k = deviceId + "|" + nonceB64;
            if (SEEN.containsKey(k)) return false;
            SEEN.put(k, nowMs);
        }
        return true;
    }

    // ---------- 局域网服务端 ----------

    /**
     * 在局域网接口上启动同步服务。只绑定站点本地地址，**不绑 0.0.0.0**——
     * 避免在蜂窝/其他接口上产生意外暴露面。
     */
    public static synchronized void start(Context ctx) {
        if (sRunning || !enabled(ctx)) return;
        final Context app = ctx.getApplicationContext();
        if (storedKey(app) == null) {
            android.util.Log.i(TAG, "未派生局域网密钥，暂不启动（需先登录）");
            return;
        }
        final int p = port(app);

        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            try {
                InetAddress bind = null;
                for (String ip : lanIPs()) {
                    try {
                        InetAddress cand = InetAddress.getByName(ip);
                        if (cand.isSiteLocalAddress()) { bind = cand; break; }
                    } catch (Exception ignored) { }
                }
                if (bind == null) {
                    android.util.Log.i(TAG, "无可用局域网地址，跳过启动");
                    return;
                }
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(bind, p), 8);
                sServer = ss;
                sRunning = true;
                android.util.Log.i(TAG, "局域网同步服务已启动 " + bind.getHostAddress() + ":" + p);

                while (sRunning) {
                    Socket s;
                    try {
                        s = ss.accept();
                    } catch (Exception e) {
                        break; // socket 关闭，退出循环
                    }
                    handleConn(s, app);   // 串行处理：局域网内并发极低，串行更简单也更安全
                }
            } catch (Exception e) {
                android.util.Log.i(TAG, "局域网服务异常退出: " + e);
            } finally {
                sRunning = false;
                try { if (ss != null) ss.close(); } catch (Exception ignored) { }
            }
        }, "agentlite-lan");
        t.setDaemon(true);
        sAcceptor = t;
        t.start();
    }

    public static synchronized void stop() {
        sRunning = false;
        try { if (sServer != null) sServer.close(); } catch (Exception ignored) { }
        sServer = null;
        sAcceptor = null;
    }

    public static boolean isRunning() { return sRunning; }

    /** 处理一条局域网连接：解析最小 HTTP，解密请求，合并记录，加密响应。 */
    private static void handleConn(Socket sock, Context ctx) {
        try {
            sock.setSoTimeout(10_000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null) { sock.close(); return; }
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(c + 1).trim()); }
                    catch (Exception ignored) { }
                }
            }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2 || !"POST".equalsIgnoreCase(parts[0])
                    || !PATH.equals(parts[1]) || contentLength <= 0 || contentLength > MAX_BODY) {
                respond(out, 404, "not found");
                sock.close();
                return;
            }
            byte[] body = readN(in, contentLength);
            String envelope = new String(body, StandardCharsets.UTF_8).trim();

            byte[] key = storedKey(ctx);
            if (key == null) { respond(out, 503, "no key"); sock.close(); return; }

            SharedPreferences sp = sp(ctx);
            String myAccount = sp.getString("account_id", "");
            String myDevice = sp.getString("sync_device_id", "");

            // 解密：AAD 用**发送方** device_id，但发送方 id 在密文里，需先解一层。
            // 折中做法：AAD 只用 account_id，device_id 在明文内校验——
            // 这样既避免鸡生蛋问题，又不牺牲来源绑定的强度（明文有 GCM 保护）。
            String aadIn = AAD_PREFIX + myAccount;
            String plain = open(key, aadIn, envelope);
            JSONObject req = new JSONObject(plain);

            String peerDevice = req.optString("device_id", "");
            if (peerDevice.isEmpty() || peerDevice.equals(myDevice)) {
                respond(out, 403, "bad device");
                sock.close();
                return;
            }
            long ts = req.optLong("ts", 0);
            if (!freshAndUnseen(peerDevice, nonceOf(envelope), ts)) {
                android.util.Log.i(TAG, "拒绝重放/过期报文 device=" + peerDevice + " ts=" + ts);
                respond(out, 409, "replay");
                sock.close();
                return;
            }

            // 邻居推来的记录并入本地（与服务端同一套去重 id，重复不会产生副本）
            JSONObject push = req.optJSONObject("push");
            int merged = mergePeerRecords(ctx, push == null ? null : push.optJSONArray("tasks"), peerDevice);

            // 回给对方它还没见过的记录（本机游标之后的部分）
            long since = req.optLong("since", 0);
            JSONObject resp = new JSONObject();
            resp.put("ts", System.currentTimeMillis() / 1000L);
            resp.put("server_seq", since);
            resp.put("merged", merged);
            // LAN 游标与云端 since 分开：两个追加文件各自分页，并交替选取避免来源饥饿。
            // 旧对端不带游标时由服务端轮转，响应丢失的页也会在后续轮转中再次交付。
            JSONObject cursor = req.optJSONObject("lan_cursor");
            boolean legacyPeer = cursor == null;
            String legacyKey = "lan_send_cursor." + myAccount + "." + peerDevice;
            if (legacyPeer) cursor = new JSONObject(sp.getString(legacyKey, "{}"));
            resp.put("device_id", myDevice);
            collectRecordsForPeer(ctx, PEER_RECORD_LIMIT, cursor, legacyPeer, resp);

            String outEnvelope = seal(key, aadIn, resp.toString());
            respond(out, 200, outEnvelope);
            if (legacyPeer) {
                sp.edit().putString(legacyKey, resp.getJSONObject("lan_cursor").toString()).apply();
            }
            sock.close();
        } catch (Exception e) {
            android.util.Log.i(TAG, "局域网连接处理失败: " + e);
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /** 从信封里取 nonce 的 base64（前 12 字节），用于抗重放键。 */
    private static String nonceOf(String envelope) {
        try {
            byte[] env = Base64.decode(envelope, Base64.NO_WRAP);
            byte[] n = new byte[NONCE_LEN];
            System.arraycopy(env, 0, n, 0, Math.min(NONCE_LEN, env.length));
            return Base64.encodeToString(n, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 局域网客户端 ----------

    /**
     * 向邻居拉取/推送。返回成功合并的条数；任何失败都返回 -1（调用方回退到云端）。
     * 刻意**不做重试**：局域网路径是优化项，失败就交给云端，不能让同步卡死。
     */
    public static int syncWithPeer(Context ctx, String peerIp, int peerPort, String peerDeviceId,
                                   JSONArray tasksToPush, long since) {
        if (!enabled(ctx) || peerIp == null || peerIp.isEmpty()) return -1;
        byte[] key = storedKey(ctx);
        if (key == null) return -1;

        Socket sock = null;
        try {
            SharedPreferences sp = sp(ctx);
            String account = sp.getString("account_id", "");
            String myDevice = sp.getString("sync_device_id", "");
            String aad = AAD_PREFIX + account;

            JSONObject req = new JSONObject();
            req.put("ts", System.currentTimeMillis() / 1000L);
            req.put("device_id", myDevice);
            req.put("since", since);
            String cursorKey = "lan_receive_cursor." + account + "." + peerDeviceId;
            req.put("lan_cursor", new JSONObject(sp.getString(cursorKey, "{}")));
            JSONObject push = new JSONObject();
            push.put("tasks", tasksToPush == null ? new JSONArray() : tasksToPush);
            req.put("push", push);

            String envelope = seal(key, aad, req.toString());

            sock = new Socket();
            sock.connect(new InetSocketAddress(peerIp, peerPort), 3000);
            sock.setSoTimeout(8000);
            OutputStream out = sock.getOutputStream();
            byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("POST ").append(PATH).append(" HTTP/1.1\r\n")
                .append("Host: ").append(peerIp).append(':').append(peerPort).append("\r\n")
                .append("Content-Type: text/plain\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();

            InputStream in = sock.getInputStream();
            String status = readLine(in);
            if (status == null || !status.contains("200")) return -1;
            int len = 0;
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int c = l.indexOf(':');
                if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { len = Integer.parseInt(l.substring(c + 1).trim()); } catch (Exception ignored) { }
                }
            }
            if (len <= 0 || len > MAX_BODY) return -1;
            String body = new String(readN(in, len), StandardCharsets.UTF_8).trim();
            JSONObject resp = new JSONObject(open(key, aad, body));

            // 拉取方向：把对端回传、本机还没有的记录转存下来（保留其真实来源），
            // 之后由 SyncClient 带 origin_device_id 转发上云，归属不会串。
            int pulled = mergePeerRecords(ctx, resp.optJSONArray("records"), "");
            JSONObject nextCursor = resp.optJSONObject("lan_cursor");
            if (nextCursor != null) sp.edit().putString(cursorKey, nextCursor.toString()).apply();
            android.util.Log.i(TAG, "局域网同步成功 peer=" + peerDeviceId
                    + " 合并=" + resp.optInt("merged", 0) + " 拉取=" + pulled);
            return resp.optInt("merged", 0) + pulled;
        } catch (Exception e) {
            android.util.Log.i(TAG, "局域网同步失败（回退云端）peer=" + peerIp + ": " + e);
            return -1;
        } finally {
            try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 批量记录邻居经局域网转交来的任务。
     *
     * 刻意**不写进 `task_history.jsonl`**：那份文件语义是「本机产生的历史」，
     * `SyncClient.pushHistory` 会把它整份上行。若把邻居的记录混进去，再上行时
     * 服务端会按 `X-Device-Id` 记成**本机产生**——数据不丢，但「这条是谁产生的」
     * 溯源信息就错了（这正是本次修复要消除的缺陷）。
     *
     * 因此单独存一份并保留来源，由 SyncClient 带 `origin_device_id` 转发上云；
     * 服务端会校验该来源确实属于同一账号才采信。
     *
     * 格式：每行 `{"origin_device_id":"d_x","id":"...","ts":"...","data":{...}}`
     */
    private static final Object PEER_RECORD_LOCK = new Object();

    /** 一批只读一次已有 ID，并在同一锁内追加；写入失败上抛，客户端不提交拉取游标。 */
    private static int mergePeerRecords(Context ctx, JSONArray records, String defaultOrigin) throws Exception {
        if (records == null || records.length() == 0) return 0;
        synchronized (PEER_RECORD_LOCK) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : readAllLines(peerFile(ctx))) {
                String id = new JSONObject(line).optString("id", "");
                if (!id.isEmpty()) seen.add(id);
            }
            int merged = 0;
            try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(peerFile(ctx), true), StandardCharsets.UTF_8))) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject item = records.optJSONObject(i);
                    if (item == null || item.optJSONObject("data") == null) continue;
                    String origin = item.optString("origin_device_id", "");
                    if (origin.isEmpty()) origin = defaultOrigin;
                    String id = item.optString("id", "");
                    if (origin == null || origin.isEmpty() || id.isEmpty() || !seen.add(id)) continue;
                    JSONObject wrap = new JSONObject()
                            .put("origin_device_id", origin)
                            .put("id", id)
                            .put("ts", item.optString("ts", ""))
                            .put("data", item.optJSONObject("data"));
                    writer.write(wrap.toString());
                    writer.newLine();
                    merged++;
                }
            }
            return merged;
        }
    }

    private static java.io.File peerFile(Context ctx) {
        return new java.io.File(ctx.getExternalFilesDir(null), "peer_records.jsonl");
    }

    /** 单次回传上限：局域网带宽便宜，但要有界，避免历史极长时把单包撑爆。 */
    private static final int PEER_RECORD_LIMIT = 500;

    /**
     * 汇总本机可供同伴拉取的记录。
     *
     * **来源归属必须如实保留**：本机历史里的记录来源是本机；此前从别的设备转存来的
     * 记录（peer_records.jsonl）保留其原始来源。这样同伴再转发上云时，
     * 云端仍能记到真正的产生者名下，而不是"最后经手的那台设备"。
     */
    private static JSONObject collectRecordsForPeer(Context ctx, int limit, JSONObject cursor,
                                                     boolean repeat, JSONObject response) throws Exception {
        String myDevice = response.getString("device_id");
        java.util.List<String> mine = readAllLines(historyFile(ctx));
        java.util.List<String> peers;
        synchronized (PEER_RECORD_LOCK) {
            peers = readAllLines(peerFile(ctx));
        }
        int local = cursor.optInt("local", 0);
        int peer = cursor.optInt("peer", 0);
        // 位置带前一条稳定 ID；文件被清空/替换后重新开始，不把新文件当旧文件的尾部。
        if (local < 0 || local > mine.size() || (local > 0 && !cursor.optString("local_id", "")
                .equals(sha256Hex(mine.get(local - 1)).substring(0, 32)))) local = 0;
        if (peer < 0 || peer > peers.size() || (peer > 0 && !cursor.optString("peer_id", "")
                .equals(new JSONObject(peers.get(peer - 1)).optString("id", "")))) peer = 0;
        if (repeat && local == mine.size()) local = 0;
        if (repeat && peer == peers.size()) peer = 0;
        String localId = local == 0 ? "" : sha256Hex(mine.get(local - 1)).substring(0, 32);
        String peerId = peer == 0 ? "" : new JSONObject(peers.get(peer - 1)).optString("id", "");
        boolean nextPeer = cursor.optBoolean("next_peer", false);
        boolean localFull = false, peerFull = false;
        Boolean deferredPeer = null;
        // Base64 长度是 4*ceil((UTF-8 明文 + 12 字节 nonce + 16 字节 tag)/3)。
        int maxPlainBytes = (MAX_BODY / 4) * 3 - NONCE_LEN - GCM_TAG_BITS / 8;
        int recordBytes = 0;
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONObject nextCursor = new JSONObject();
        // 预算只编码小元数据，records 暂用空数组；不反复序列化整份已选记录。
        response.put("records", new JSONArray()).put("lan_cursor", nextCursor).put("lan_has_more", false);
        while (out.length() < limit) {
            boolean localAvailable = !localFull && local < mine.size();
            boolean peerAvailable = !peerFull && peer < peers.size();
            if (!localAvailable && !peerAvailable) break;
            boolean fromPeer = peerAvailable && (nextPeer || !localAvailable);
            JSONObject item;
            if (fromPeer) {
                JSONObject stored = new JSONObject(peers.get(peer));
                item = new JSONObject()
                        .put("origin_device_id", stored.optString("origin_device_id", ""))
                        .put("id", stored.optString("id", ""))
                        .put("ts", stored.optString("ts", ""))
                        .put("data", stored.optJSONObject("data"));
            } else {
                String line = mine.get(local);
                JSONObject data = new JSONObject(line);
                item = new JSONObject()
                        .put("origin_device_id", myDevice)
                        .put("id", sha256Hex(line).substring(0, 32))
                        .put("ts", data.optString("time", ""))
                        .put("data", data);
            }
            nextPeer = !fromPeer;
            String id = item.optString("id", "");
            if (!id.isEmpty() && !item.optString("origin_device_id", "").isEmpty()
                    && item.optJSONObject("data") != null && !seen.contains(id)) {
                int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                nextCursor.put("local", fromPeer ? local : local + 1)
                        .put("peer", fromPeer ? peer + 1 : peer)
                        .put("local_id", fromPeer ? localId : id)
                        .put("peer_id", fromPeer ? id : peerId).put("next_peer", false);
                int metadataBytes = response.toString().getBytes(StandardCharsets.UTF_8).length;
                if ((long) metadataBytes + recordBytes + itemBytes + out.length() > maxPlainBytes) {
                    // 本来源本页放不下时仍尝试另一来源，游标保持在未交付的这条记录之前。
                    if (deferredPeer == null) deferredPeer = fromPeer;
                    if (fromPeer) peerFull = true; else localFull = true;
                    continue;
                }
                out.put(item);
                seen.add(id);
                recordBytes += itemBytes;
            }
            if (fromPeer) { peer++; peerId = id; }
            else { local++; localId = id; }
        }
        if (out.length() == 0 && (localFull || peerFull)) {
            throw new java.io.IOException("单条局域网记录超过 4MiB 信封上限，请缩减该记录后同步。");
        }
        nextCursor.put("local", local).put("peer", peer).put("local_id", localId)
                .put("peer_id", peerId).put("next_peer", false);
        if ((long) response.toString().getBytes(StandardCharsets.UTF_8).length
                + recordBytes + Math.max(0, out.length() - 1) > maxPlainBytes) {
            throw new java.io.IOException("局域网响应元数据超过 4MiB 信封上限。");
        }
        nextCursor.put("next_peer", deferredPeer == null ? nextPeer : deferredPeer);
        return response.put("records", out).put("lan_has_more", local < mine.size() || peer < peers.size());
    }

    /**
     * 内容哈希。**必须与 SyncClient.buildTaskBatch 的方案完全一致**
     * （同样是 sha256(原始行) 取前 32 位十六进制）——两端 id 口径不同的话，
     * 同一条记录会被当成两条，局域网拉取与云端幂等都会失效。
     */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static java.io.File historyFile(Context ctx) {
        return new java.io.File(ctx.getExternalFilesDir(null), "task_history.jsonl");
    }

    private static java.util.List<String> readAllLines(java.io.File f) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!f.exists()) return out;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        }
        return out;
    }

    /** 供 SyncClient 读取尚未转发上云的邻居记录（按写入顺序）。 */
    public static java.util.List<String> pendingPeerRecords(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            synchronized (PEER_RECORD_LOCK) {
                out.addAll(readAllLines(peerFile(ctx)));
            }
        } catch (Exception e) {
            android.util.Log.i(TAG, "读取待转发记录失败: " + e);
        }
        return out;
    }

    // ---------- 最小 HTTP 读写 ----------

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
            if (bos.size() > 8192) throw new IllegalStateException("header too long");
        }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0, r;
        while (off < n && (r = in.read(buf, off, n - off)) > 0) off += r;
        if (off != n) throw new IllegalStateException("short body");
        return buf;
    }

    private static void respond(OutputStream out, int code, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
