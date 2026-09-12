// SPDX-License-Identifier: Apache-2.0

/**
 * PinSmoke —— 用**客户端真实源码**在桌面 JVM 上验证证书 pin 链路。
 *
 * 为什么能在桌面跑：客户端的 TlsPin 刻意不引用任何 android.* API（见其类注释），
 * 所以它可以直接用 javac 编译、用 java 运行。e2e-local.sh 会把
 * client/src/com/dsh/agentlite/TlsPin.java 原样参与编译——测的是真代码，不是复刻品。
 *
 * 三种断言模式（args[1]）：
 *   ok            应用 pin 后必须握手成功（证明信任锚对得上）
 *   fail          应用 pin 但服务端出示的是**另一张**证书 → 必须失败（证明 pin 不是摆设）
 *   fail-unpinned 不应用 pin → 自签证书在系统 CA 下必须失败（证明成功来自 pin，而非默认宽松）
 *
 * 用法： java PinSmoke https://127.0.0.1:18443 ok
 */

// 客户端源码所在的包（DevConfig / PinnedCa / TlsPin 都在这里）
import com.dsh.agentlite.DevConfig;
import com.dsh.agentlite.TlsPin;

public final class PinSmoke {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : DevConfig.SYNC_URL + "/health";
        String expect = args.length > 1 ? args[1] : "ok";

        System.out.println("[PinSmoke] url=" + url + "  expect=" + expect);
        System.out.println("[PinSmoke] 被 pin 的主机=" + TlsPin.pinnedHost()
                + "  covers(url)=" + TlsPin.covers(url));

        int code = -1;
        String body = "";
        String err = null;
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (!"fail-unpinned".equals(expect)) {
                TlsPin.apply(conn);   // ← 客户端真实实现
            }
            code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                body = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        boolean ok = "ok".equals(expect) ? (code == 200) : (err != null);
        System.out.println("[PinSmoke] httpCode=" + code
                + (err == null ? "" : "  error=" + err)
                + (body.isEmpty() ? "" : "  body=" + body.trim()));

        if (ok) {
            System.out.println("[PinSmoke] PASS  (" + expect + ")");
            System.exit(0);
        }
        System.out.println("[PinSmoke] FAIL  期望 " + expect + " 但实际 "
                + (err == null ? "HTTP " + code : "抛错"));
        System.exit(1);
    }

    private PinSmoke() { }
}
