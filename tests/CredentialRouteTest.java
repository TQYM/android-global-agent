// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 对实际 Prefs / LlmClient 及主页方法做行为回归；不运行 Android UI 或真实网络。 */
public final class CredentialRouteTest {
    private static int passed, failed;
    private static final String OWN_KEY = "fixture-own-key-not-a-secret";
    private static final String ACCOUNT_TOKEN = "fixture-account-token-not-a-secret";
    private static final List<FakeConnection> requests = new ArrayList<>();
    private static int responseCode = 200;

    private static final class Fixture {
        final Context ctx = new Context();
        final Prefs p = new Prefs(ctx);
        Fixture key(String key) { p.setApiKey(key); return this; }
        Fixture account() {
            if (!p.saveAccount("fixture-user", "测试账号", ACCOUNT_TOKEN, 0, 0))
                throw new AssertionError("假账号保存失败");
            return this;
        }
        Fixture choice(boolean own) { p.sp.edit().putBoolean("use_own_key", own).apply(); return this; }
        Fixture disabled() { p.sp.edit().putBoolean("use_gateway", false).apply(); return this; }
        Fixture invalid() { p.setAccountInvalid(true); return this; }
    }

    private static final class FakeConnection extends HttpURLConnection {
        FakeConnection(URL u) { super(u); }
        @Override public void connect() {}
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() throws IOException {
            if (CredentialRouteTest.responseCode == -1) throw new IOException("固定离线故障");
            return new ByteArrayOutputStream();
        }
        @Override public int getResponseCode() { return CredentialRouteTest.responseCode; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream("invalid_api_key".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) { passed++; System.out.println("PASS " + label); }
        else { failed++; System.out.println("FAIL " + label); }
    }

    private static Object field(LlmClient c, String name) throws Exception {
        Field f = LlmClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(c);
    }

    /** 预期 null 表示不可请求；own / account 表示付费主体。 */
    private static void row(String label, Fixture fixture, String expected) throws Exception {
        requests.clear();
        Map<String,?> before = fixture.p.sp.getAll();
        boolean available = new CredentialEntryHarness(fixture.p).hasUsableCredential();
        check(label + "：主页放行一致", available == (expected != null));
        LlmClient c = null;
        try { c = LlmClient.forPrefs(fixture.ctx, fixture.p, fixture.p.model()); }
        catch (IllegalStateException e) {
            check(label + "：拒绝原因不含假密钥", !e.getMessage().contains(OWN_KEY)
                    && !e.getMessage().contains(ACCOUNT_TOKEN));
        }
        check(label + "：工厂可用性一致", (c != null) == (expected != null));
        if (c != null && expected != null) {
            boolean gateway = "account".equals(expected);
            check(label + "：端点与付费主体一致",
                    Boolean.valueOf(gateway).equals(field(c, "primaryIsGateway"))
                    && (gateway ? "https://gateway.example.invalid/v1" : DevConfig.BASE_URL)
                            .equals(field(c, "baseUrl")));
            check(label + "：凭据与模型快照一致",
                    (gateway ? ACCOUNT_TOKEN : OWN_KEY).equals(field(c, "apiKey"))
                    && "deepseek-flash".equals(field(c, "model")));
            check(label + "：没有登记其他付费通道", field(c, "fbBaseUrl") == null);
        }
        check(label + "：解析不修改用户选择", before.equals(fixture.p.sp.getAll()));
        check(label + "：解析不发请求", requests.isEmpty());
    }

    private static Exception post(LlmClient client) throws Exception {
        Method m = LlmClient.class.getDeclaredMethod("post", String.class, byte[].class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(client, "/chat/completions", "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            return null;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) return (Exception) e.getCause();
            throw e;
        }
    }

    public static void main(String[] args) throws Exception {
        // 所有 http(s) 都交给内存连接；即使路由错了也不可能访问外网或付费端点。
        URL.setURLStreamHandlerFactory(protocol -> new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL u) {
                if (!"https".equals(u.getProtocol()) && !"http".equals(u.getProtocol()))
                    throw new AssertionError("禁止未知协议");
                FakeConnection c = new FakeConnection(u);
                requests.add(c);
                return c;
            }
        });
        row("新装无凭据", new Fixture(), null);
        row("默认登录无 Key", new Fixture().account(), "account");
        row("旧版仅存 Key 无显式偏好", new Fixture().key(OWN_KEY), "own");
        row("默认两类凭据齐备保留自带优先", new Fixture().key(OWN_KEY).account(), "own");
        row("明确自带却缺 Key 不改用账号", new Fixture().choice(true).account(), null);
        row("明确账号且有自带 Key", new Fixture().choice(false).key(OWN_KEY).account(), "account");
        row("明确账号却未登录不改用 Key", new Fixture().choice(false).key(OWN_KEY), null);
        row("账号失效不改用 Key", new Fixture().choice(false).key(OWN_KEY).account().invalid(), null);
        row("关闭网关的账号选择拒绝请求", new Fixture().choice(false).key(OWN_KEY).account().disabled(), null);
        row("默认无 Key 且网关关闭", new Fixture().account().disabled(), null);
        row("自带不依赖网关开关", new Fixture().key(OWN_KEY).account().disabled(), "own");
        row("失效账号不影响已选自带", new Fixture().choice(true).key(OWN_KEY).account().invalid(), "own");
        row("空白 Key 视为未配置", new Fixture().key("  \t ").account(), "account");
        row("明确自带空白 Key 不兜底", new Fixture().choice(true).key(" \t").account(), null);
        row("null Key 视为未配置", new Fixture().key(null).account(), "account");
        Fixture f = new Fixture().key(OWN_KEY);
        f.p.sp.edit().putString("base_url", "").apply();
        row("自带地址为空", f, null);
        f = new Fixture().key(OWN_KEY).account();
        f.p.sp.edit().putString("base_url", "file:///tmp/not-a-service").apply();
        row("无效协议不切账号", f, null);
        f = new Fixture().account();
        f.p.setSyncServer("");
        row("网关地址为空", f, null);
        f = new Fixture().account();
        f.p.setSyncServer("https://");
        row("网关地址缺主机", f, null);
        f = new Fixture().account();
        f.p.sp.edit().putString("model", " ").apply();
        row("模型为空不请求", f, null);
        f = new Fixture().account();
        f.p.sp.edit().remove("account_id").apply();
        row("半登录无账号 ID", f, null);
        f = new Fixture().account();
        new TokenStore(f.ctx).clear();
        row("半登录无令牌", f, null);
        f = new Fixture().choice(false).key(OWN_KEY).account();
        f.p.clearAccount();
        row("退出账号不切自带", f, null);

        // 保存/重建 Prefs 不应抹掉明确选择；登录本身不替用户改变付费主体。
        f = new Fixture().choice(true);
        f.account();
        check("登录保留自带选择", f.p.sp.contains("use_own_key") && f.p.useOwnKey());
        check("重建 Prefs 保留明确缺 Key 的阻断", !new CredentialEntryHarness(new Prefs(f.ctx)).hasUsableCredential());
        f = new Fixture().key(OWN_KEY);
        f.account();
        check("登录不伪造显式选择", !f.p.sp.contains("use_own_key"));

        // 保存配置不自动同意切换，特别是新填/删除旧 Key 的升级场景。
        f = new Fixture().account();
        f.p.save(DevConfig.BASE_URL, OWN_KEY, DevConfig.MODEL, "", true, 50, "fixture");
        row("保存新 Key 保留账号直到主动选择", f, "account");
        f.p.selectCredentialChannel(true);
        row("主动选择后使用自己的配置", f, "own");
        f.p.save(DevConfig.BASE_URL, "", DevConfig.MODEL, "", true, 50, "fixture");
        row("清空已选 Key 不自动切账号", f, null);
        f.p.sp.edit().putBoolean("use_gateway", false).apply();
        f.p.selectCredentialChannel(false);
        row("主动选账号解除旧网关关闭标志", f, "account");
        check("解析快照文本不含凭据", !f.p.credentialRoute(f.p.model()).toString().contains(ACCOUNT_TOKEN));

        // 设置修改只能影响下一任务；规划器继承正在执行任务的凭据快照。
        f = new Fixture().key(OWN_KEY).account().choice(true);
        LlmClient taskClient = LlmClient.forPrefs(f.ctx, f.p, f.p.model());
        f.p.selectCredentialChannel(false);
        LlmClient planner = taskClient.forModel("planner-fixture");
        check("规划器继承任务通道和凭据", Boolean.FALSE.equals(field(planner, "primaryIsGateway"))
                && OWN_KEY.equals(field(planner, "apiKey")) && DevConfig.BASE_URL.equals(field(planner, "baseUrl")));
        check("规划器模型明确且无备用通道", "planner-fixture".equals(field(planner, "model"))
                && field(planner, "fbBaseUrl") == null);
        row("切换只改变下一任务的工厂结果", f, "account");
        taskClient.cancel();
        check("已取消任务派生的规划器保持取消", taskClient.forModel("planner-fixture").isCancelled());

        // 实际请求层故障注入：不能触发第二种额度；同通道 429 退避不在本测试中等待。
        for (boolean own : new boolean[]{true, false}) {
            for (int code : new int[]{200, 401, 402, 403, 429, 500, -1}) {
                f = new Fixture().key(OWN_KEY).account().choice(own);
                LlmClient c = LlmClient.forPrefs(f.ctx, f.p, f.p.model());
                requests.clear(); responseCode = code;
                Exception err = post(c);
                String label = (own ? "自带" : "账号") + " 请求 " + code;
                check(label + "：成功/失败不误报", (err == null) == (code == 200));
                if (code == 402) {
                    check(label + "：欠费归因到正确主体", own
                            ? err instanceof LlmClient.UpstreamRejectedException
                              && !((LlmClient.UpstreamRejectedException) err).viaGateway
                            : err instanceof LlmClient.QuotaExhaustedException);
                }
                check(label + "：只请求选中通道", requests.size() == 1
                        && (own ? "own.example.invalid" : "gateway.example.invalid")
                                .equals(requests.get(0).getURL().getHost()));
                check(label + "：不跨付费主体回退", !c.onFallback());
                check(label + "：关闭自动重定向", requests.size() == 1
                        && !requests.get(0).getInstanceFollowRedirects());
                check(label + "：鉴权发给正确端点", requests.size() == 1
                        && ("Bearer " + (own ? OWN_KEY : ACCOUNT_TOKEN))
                                .equals(requests.get(0).getRequestProperty("Authorization")));
            }
        }
        f = new Fixture().key(OWN_KEY).account();
        LlmClient c = LlmClient.forPrefs(f.ctx, f.p, f.p.model());
        requests.clear(); c.cancel();
        check("取消不发请求且不回退", post(c) instanceof LlmClient.CancelledException
                && requests.isEmpty() && !c.onFallback());
        check("日志不泄漏固定测试凭据", android.util.Log.messages.stream()
                .noneMatch(s -> s.contains(OWN_KEY) || s.contains(ACCOUNT_TOKEN)));
        System.out.println("凭据行为回归：" + passed + " 通过 / " + failed + " 失败；网络连接均为 JVM 内存替身");
        if (failed > 0) System.exit(1);
    }
}
