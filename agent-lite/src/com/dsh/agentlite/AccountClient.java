// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 账号体系客户端（agentlite-server）：
 *   - 注册 / 登录（/api/v1/account/register、/api/v1/account/login）
 *   - 账号视图（/api/v1/account/me：额度 + 本账号设备列表）
 *   - 设备绑定（/api/v1/device/bind，幂等：同 fingerprint 视为同一台设备）
 *   - 兑换码（/api/v1/redeem）
 *
 * 全部请求走 {@link SyncClient#request} 的同一套 HTTP 实现（含自签证书 pin），
 * 且**只在后台线程执行**，结果在主线程回调——Activity 里可直接改 UI。
 * 注意：设备令牌时代的 /api/v1/device/register 已下线，本类与 SyncClient 都不再调用它。
 */
public final class AccountClient {

    private static final String TAG = "AgentLiteAccount";
    /** 设备上报的应用标识（服务端截断到 32 字符）。 */
    static final String APP_VERSION = "agent-lite";

    /** 账号名规则（与服务端 normalizeAccountName 一致）。 */
    public static final String NAME_RULE = "账号名 3-32 位，仅小写字母、数字、下划线 _、连字符 -";
    /** 账号密钥规则（与服务端 validateAccountKey 一致）。 */
    public static final String KEY_RULE = "账号密钥至少 8 位（建议 12 位以上混合字符）";

    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "agentlite-account");
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AccountClient() { }

    /** 异步回调：onOk / onErr 都在主线程调用。 */
    public interface ApiCallback<T> {
        void onOk(T data);
        void onErr(String message);
    }

    /** 一次后台运算（可能抛 ApiException）。 */
    private interface Work<T> { T run() throws Exception; }

    // ---------- 数据模型 ----------

    /** 注册/登录响应。 */
    public static final class Session {
        public String accountId = "";
        public String name = "";
        public String token = "";
        public long quota;
        public long quotaUsed;

        public boolean valid() { return !token.isEmpty() && !accountId.isEmpty(); }
    }

    /** 账号视角（/account/me）。 */
    public static final class Me {
        public String accountId = "";
        public String name = "";
        public long quota;
        public long quotaUsed;
        public final List<Device> devices = new ArrayList<>();
    }

    /** 本账号下的一台设备。 */
    public static final class Device {
        public String deviceId = "";
        public String name = "";
        public String model = "";
        public String brand = "";
        public int sdk;
        public String appVersion = "";
        public String lastSeenAt = "";
    }

    /** 额度套餐。价格仅用于展示——在线支付尚未接入。 */
    public static final class Pack {
        public String packId, name, note;
        public long units, bonus;
        public double priceCny;
        public long totalUnits() { return units + bonus; }
    }

    /** 一笔买单记录。 */
    public static final class Order {
        public String orderId, packId, packName, status, method;
        public long units;
        public double priceCny;
        public String createdAt, expiresAt, paidAt;
        public boolean isPending() { return "pending".equals(status); }
    }

    /** 套餐列表 + 当前是否已接入在线支付。 */
    public static final class Shop {
        public final List<Pack> packs = new ArrayList<>();
        public boolean onlinePay;          // 服务端告知：在线支付未接入时为 false
        public List<String> payMethods = new ArrayList<>();
    }

    /** 兑换结果。 */
    public static final class Redeem {
        public long unitsAdded;
        public long quota;
    }

    // ---------- 注册 / 登录 ----------

    /** 注册新账号（重名 → 409 account_exists → "账号名已被占用"）。 */
    public static void register(Context ctx, String name, String key, ApiCallback<Session> cb) {
        auth(ctx, "/api/v1/account/register", name, key, cb);
    }

    /** 登录（凭据错误 → 401 bad_credentials）；服务端每次登录都会轮换令牌，旧令牌立即失效。 */
    public static void login(Context ctx, String name, String key, ApiCallback<Session> cb) {
        auth(ctx, "/api/v1/account/login", name, key, cb);
    }

    private static void auth(Context ctx, String path, String name, String key,
                             ApiCallback<Session> cb) {
        final Context app = ctx.getApplicationContext();
        String n = normalizeName(name);
        String k = key == null ? "" : key.trim();
        // 本地先校验：省一次往返，也避免把明显不合规的输入发到服务端
        String localErr0 = nameError(n);
        if (localErr0 == null) localErr0 = keyError(k);
        final String localErr = localErr0;   // lambda 捕获需 final（构建修复）
        if (localErr != null) {
            MAIN.post(() -> cb.onErr(localErr));
            return;
        }
        run(app, () -> doAuth(app, path, n, k), cb);
    }

    private static Session doAuth(Context ctx, String path, String name, String key) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("key", key);
        JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + path,
                body.toString(), null, null);
        Session s = new Session();
        s.accountId = r.optString("account_id", "");
        s.name = r.optString("name", name);
        s.token = r.optString("token", "");
        s.quota = r.optLong("quota", 0);
        s.quotaUsed = r.optLong("quota_used", 0);
        if (!s.valid()) throw new IllegalStateException("服务端响应缺少 token / account_id");
        return s;
    }

    /** 登录成功后落地凭据；换账号时由 Prefs 自动重置同步游标。 */
    public static void saveSession(Prefs p, Session s) {
        p.saveAccount(s.accountId, s.name, s.token, s.quota, s.quotaUsed);
        android.util.Log.i(TAG, "账号已登录: " + s.name + " (" + s.accountId + ")");
    }

    // ---------- 设备绑定 ----------

    /** 异步绑定本机设备。 */
    public static void bindDevice(Context ctx, ApiCallback<String> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> doBind(app), cb);
    }

    /** 同步绑定（供 SyncClient 的后台同步线程复用，必须不在主线程调用）。 */
    public static String bindDeviceSync(Context ctx, Prefs p) throws Exception {
        String id = doBind(ctx);
        if (id.isEmpty()) throw new IllegalStateException("绑定响应缺少 device_id");
        p.setSyncDeviceId(id);
        android.util.Log.i(TAG, "设备已绑定: " + id);
        return id;
    }

    private static String doBind(Context ctx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("fingerprint", SyncClient.deviceFingerprint(ctx));
        body.put("name", Build.MANUFACTURER + " " + Build.MODEL);
        body.put("model", Build.MODEL);
        body.put("brand", Build.MANUFACTURER);
        body.put("sdk", Build.VERSION.SDK_INT);
        body.put("app_version", APP_VERSION);
        JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + "/api/v1/device/bind",
                body.toString(), new Prefs(ctx).accountToken(), null);
        return r.optString("device_id", "");
    }

    // ---------- 账号视图（额度 + 设备列表） ----------

    public static void me(Context ctx, ApiCallback<Me> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> doMe(app), cb);
    }

    private static Me doMe(Context ctx) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject r = SyncClient.request(ctx, "GET", SyncClient.serverBase(ctx) + "/api/v1/account/me",
                null, p.accountToken(), null);
        Me m = new Me();
        m.accountId = r.optString("account_id", "");
        m.name = r.optString("name", "");
        m.quota = r.optLong("quota", 0);
        m.quotaUsed = r.optLong("quota_used", 0);
        JSONArray ds = r.optJSONArray("devices");
        if (ds != null) {
            for (int i = 0; i < ds.length(); i++) {
                JSONObject d = ds.optJSONObject(i);
                if (d == null) continue;
                Device dev = new Device();
                dev.deviceId = d.optString("device_id", "");
                dev.name = d.optString("name", "");
                dev.model = d.optString("model", "");
                dev.brand = d.optString("brand", "");
                dev.sdk = d.optInt("sdk", 0);
                dev.appVersion = d.optString("app_version", "");
                dev.lastSeenAt = shortTime(d.optString("last_seen_at", ""));
                m.devices.add(dev);
            }
        }
        // 落地额度，供断网时设置页仍能显示上次已知数值
        p.setAccountQuota(m.quota, m.quotaUsed);
        if (!m.accountId.isEmpty() && p.accountId().isEmpty()) {
            android.util.Log.i(TAG, "本地缺少 account_id，已按 /account/me 回填");
        }
        return m;
    }

    // ---------- 兑换码 ----------

    public static void redeem(Context ctx, String code, ApiCallback<Redeem> cb) {
        final Context app = ctx.getApplicationContext();
        String c = code == null ? "" : code.trim();
        if (c.isEmpty()) {
            MAIN.post(() -> cb.onErr("请输入兑换码"));
            return;
        }
        run(app, () -> doRedeem(app, c), cb);
    }

    private static Redeem doRedeem(Context ctx, String code) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject body = new JSONObject();
        body.put("code", code);
        JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + "/api/v1/redeem",
                body.toString(), p.accountToken(), null);
        Redeem rd = new Redeem();
        rd.unitsAdded = r.optLong("units_added", 0);
        rd.quota = r.optLong("quota", 0);
        if (rd.quota > 0) p.setAccountQuota(rd.quota, p.accountQuotaUsed());
        return rd;
    }

    // ---------- 套餐与订单（应用内买单） ----------

    public static void shop(Context ctx, ApiCallback<Shop> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> doShop(app), cb);
    }

    private static Shop doShop(Context ctx) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject r = SyncClient.request(ctx, "GET", SyncClient.serverBase(ctx) + "/api/v1/packs",
                null, p.accountToken(), null);
        Shop sh = new Shop();
        sh.onlinePay = r.optBoolean("online_pay", false);
        JSONArray pm = r.optJSONArray("pay_methods");
        if (pm != null) for (int i = 0; i < pm.length(); i++) sh.payMethods.add(pm.optString(i));
        JSONArray ps = r.optJSONArray("packs");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.optJSONObject(i);
                if (o == null) continue;
                Pack pk = new Pack();
                pk.packId = o.optString("pack_id");
                pk.name = o.optString("name");
                pk.note = o.optString("note", "");
                pk.units = o.optLong("units");
                pk.bonus = o.optLong("bonus");
                pk.priceCny = o.optDouble("price_cny", 0);
                sh.packs.add(pk);
            }
        }
        return sh;
    }

    public static void createOrder(Context ctx, String packId, ApiCallback<Order> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> doOrderOp(app, "/api/v1/orders", packId == null ? "{}" : packId, ORDER_CREATE), cb);
    }

    public static void orders(Context ctx, ApiCallback<List<Order>> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> doOrders(app), cb);
    }

    private static List<Order> doOrders(Context ctx) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject r = SyncClient.request(ctx, "GET", SyncClient.serverBase(ctx) + "/api/v1/orders",
                null, p.accountToken(), null);
        List<Order> out = new ArrayList<>();
        JSONArray arr = r.optJSONArray("orders");
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(parseOrder(arr.optJSONObject(i)));
        long q = r.optLong("quota", -1);
        if (q >= 0) p.setAccountQuota(q, p.accountQuotaUsed());
        return out;
    }

    /** 用兑换码支付订单：核销 → 加额度 → 订单完成。这是当前唯一可用的支付方式。 */
    public static void payOrder(Context ctx, String orderId, String code, ApiCallback<Order> cb) {
        final Context app = ctx.getApplicationContext();
        String c = code == null ? "" : code.trim();
        if (c.isEmpty()) { MAIN.post(() -> cb.onErr("请输入兑换码")); return; }
        run(app, () -> doPayOrder(app, orderId, c), cb);
    }

    private static Order doPayOrder(Context ctx, String orderId, String code) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject body = new JSONObject();
        body.put("order_id", orderId);
        body.put("code", code);
        JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + "/api/v1/orders/pay",
                body.toString(), p.accountToken(), null);
        long q = r.optLong("quota", -1);
        if (q >= 0) p.setAccountQuota(q, p.accountQuotaUsed());
        return parseOrder(r.optJSONObject("order"));
    }

    public static void cancelOrder(Context ctx, String orderId, ApiCallback<Order> cb) {
        final Context app = ctx.getApplicationContext();
        run(app, () -> {
            Prefs p = new Prefs(ctx);
            JSONObject body = new JSONObject();
            body.put("order_id", orderId);
            JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + "/api/v1/orders/cancel",
                    body.toString(), p.accountToken(), null);
            return parseOrder(r.optJSONObject("order"));
        }, cb);
    }

    private static final int ORDER_CREATE = 1;

    private static Order doOrderOp(Context ctx, String path, String packId, int kind) throws Exception {
        Prefs p = new Prefs(ctx);
        JSONObject body = new JSONObject();
        body.put("pack_id", packId);
        JSONObject r = SyncClient.request(ctx, "POST", SyncClient.serverBase(ctx) + path,
                body.toString(), p.accountToken(), null);
        return parseOrder(r.optJSONObject("order"));
    }

    private static Order parseOrder(JSONObject o) {
        Order od = new Order();
        if (o == null) return od;
        od.orderId = o.optString("order_id");
        od.packId = o.optString("pack_id");
        od.packName = o.optString("pack_name");
        od.status = o.optString("status");
        od.method = o.optString("method");
        od.units = o.optLong("units");
        od.priceCny = o.optDouble("price_cny", 0);
        od.createdAt = o.optString("created_at");
        od.expiresAt = o.optString("expires_at");
        od.paidAt = o.optString("paid_at");
        return od;
    }

    // ---------- 输入校验 ----------

    /** 规范化账号名（与服务端一致：去空白 + 转小写）。 */
    public static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.US);
    }

    /** 账号名校验；返回 null 表示通过，否则为可读错误。 */
    public static String nameError(String normalized) {
        if (normalized.length() < 3 || normalized.length() > 32) {
            return "账号名长度需 3-32 位（当前 " + normalized.length() + " 位）";
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return "账号名只允许小写字母、数字、下划线 _、连字符 -（非法字符：" + c + "）";
        }
        return null;
    }

    /** 账号密钥校验；返回 null 表示通过。 */
    public static String keyError(String key) {
        if (key.length() < 8) return "账号密钥至少 8 位（当前 " + key.length() + " 位）";
        if (key.length() > 256) return "账号密钥过长（最多 256 位）";
        return null;
    }

    // ---------- 线程调度 ----------

    private static <T> void run(Context app, Work<T> work, ApiCallback<T> cb) {
        EXEC.execute(() -> {
            try {
                T v = work.run();
                MAIN.post(() -> cb.onOk(v));
            } catch (Exception e) {
                android.util.Log.i(TAG, "账号请求失败: " + e);
                // 401 统一在这里落地「登录已失效」，UI 只需读 Prefs.accountInvalid() 就能提示重新登录
                if (e instanceof ApiException && ((ApiException) e).isAuthFailure()) {
                    new Prefs(app).setAccountInvalid(true);
                }
                final String msg = (e instanceof ApiException)
                        ? ((ApiException) e).readable() : ("网络异常：" + e.getClass().getSimpleName());
                MAIN.post(() -> cb.onErr(msg));
            }
        });
    }

    /** 服务端返回的 RFC3339 时间 → "MM-dd HH:mm"（解析失败时原样返回）。 */
    private static String shortTime(String iso) {
        if (iso == null || iso.isEmpty()) return "未知";
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = in.parse(iso.length() > 19 ? iso.substring(0, 19) : iso);
            if (d == null) return iso;
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.US);
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }
}
