// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * 同步服务的证书 pin。
 *
 * 服务端用的是自签证书，系统 CA 库不认，因此必须自带信任锚。
 * 证书 PEM 由构建期从 `assets/agentlite_sync_ca.pem` 生成为 `PinnedCa` 常量，
 * 于是这里**不需要 Context**——LlmClient 这类无 Context 的类也能直接 pin。
 *
 * 本类刻意不引用任何 android.* API，因此可以在桌面 JVM 上原样编译运行，
 * 用真实连接验证 pin 逻辑（见 tests/pin_smoke）。
 */
public final class TlsPin {

    private static volatile SSLSocketFactory sFactory;
    private static volatile String sHost;

    private TlsPin() { }

    /** 被 pin 的主机名，从构建期注入的同步服务地址里取。 */
    public static String pinnedHost() {
        String h = sHost;
        if (h != null) return h;
        try {
            h = new URL(DevConfig.SYNC_URL).getHost();
        } catch (Exception e) {
            h = "";
        }
        sHost = h;
        return h;
    }

    /** 该 URL 是否由内置证书覆盖。 */
    public static boolean covers(String url) {
        String host = pinnedHost();
        if (host.isEmpty()) return false;
        try {
            return host.equalsIgnoreCase(new URL(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /** 惰性构建只信任内置证书的 SSLSocketFactory。 */
    public static SSLSocketFactory factory() throws Exception {
        SSLSocketFactory f = sFactory;
        if (f != null) return f;
        synchronized (TlsPin.class) {
            if (sFactory != null) return sFactory;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate ca = cf.generateCertificate(
                    new ByteArrayInputStream(PinnedCa.PEM.getBytes(StandardCharsets.UTF_8)));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("agentlite-sync", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            sFactory = ctx.getSocketFactory();
            return sFactory;
        }
    }

    /**
     * 若目标属于内置证书覆盖的主机则套用 pin。
     * 主机名校验保持默认开启（服务端证书 SAN 已含服务 IP），不放松。
     */
    public static void apply(HttpURLConnection conn) {
        if (conn == null) return;
        if (!(conn instanceof HttpsURLConnection)) return;
        if (!covers(conn.getURL().toString())) return;
        try {
            ((HttpsURLConnection) conn).setSSLSocketFactory(factory());
        } catch (Exception e) {
            System.err.println("[AgentLiteTls] 证书 pin 应用失败: " + e);
        }
    }
}
