// SPDX-License-Identifier: Apache-2.0
//
// 局域网对端模拟器 —— 用于验证 AgentLite 客户端的 P2P 同步链路。
//
// 为什么需要它：真实对端要求两台设备同处一个可达内网。手边的设备里
// OnePlus 在 Wi-Fi、OWW261 走 VPN，凑不出合法的 P2P 对端。
// 用桌面 JVM 实现一份**独立的对端**，让手机真的连过来，比两台同类设备互连
// 更能验证协议实现是否正确（相当于跨实现互操作测试）。
//
// 用法： javac LanPeerSim.java && java LanPeerSim <account_id> <account_key> [port]
// 然后让手机同步；本程序会把手机发来的请求解密并打印。
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class LanPeerSim {
    static final String SALT_PREFIX = "agentlite-lan|";
    static final String AAD_PREFIX = "agentlite-lan-v1|";
    static final int ITER = 100_000, BITS = 256, NONCE = 12, TAG = 128;

    static byte[] derive(String key, String accountId) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(key.toCharArray(),
                (SALT_PREFIX + accountId).getBytes(StandardCharsets.UTF_8), ITER, BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally { spec.clearPassword(); }
    }

    static String open(byte[] key, String aad, String env) throws Exception {
        byte[] b = Base64.getDecoder().decode(env);
        byte[] nonce = new byte[NONCE];
        System.arraycopy(b, 0, nonce, 0, NONCE);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG, nonce));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(b, NONCE, b.length - NONCE), StandardCharsets.UTF_8);
    }

    static String seal(byte[] key, String aad, String pt) throws Exception {
        byte[] nonce = new byte[NONCE];
        new java.security.SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG, nonce));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(pt.getBytes(StandardCharsets.UTF_8));
        byte[] env = new byte[NONCE + ct.length];
        System.arraycopy(nonce, 0, env, 0, NONCE);
        System.arraycopy(ct, 0, env, NONCE, ct.length);
        return Base64.getEncoder().withoutPadding().encodeToString(env);
    }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) { if (b == '\n') break; if (b != '\r') bos.write(b); }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: java LanPeerSim <account_id> <account_key> [port]");
            System.exit(1);
        }
        String accountId = args[0], accountKey = args[1];
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 18444;
        // 自身 device_id：回传记录时要声明真实来源，否则设备转发上云时归属会串
        String selfDeviceId = args.length > 3 ? args[3] : "d_peersim";
        byte[] key = derive(accountKey, accountId);
        String aad = AAD_PREFIX + accountId;

        ServerSocket ss = new ServerSocket(port, 8,
                java.net.InetAddress.getByName(System.getProperty("bind", "0.0.0.0")));
        System.out.println("LAN_PEER_READY 监听 " + ss.getLocalSocketAddress()
                + "  account=" + accountId);
        System.out.flush();

        while (true) {
            Socket s = ss.accept();
            try {
                s.setSoTimeout(10_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String reqLine = readLine(in);
                if (reqLine == null) { s.close(); continue; }
                int len = 0;
                String l;
                while ((l = readLine(in)) != null && !l.isEmpty()) {
                    int c = l.indexOf(':');
                    if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                        len = Integer.parseInt(l.substring(c + 1).trim());
                    }
                }
                byte[] body = new byte[len];
                int off = 0, r;
                while (off < len && (r = in.read(body, off, len - off)) > 0) off += r;

                String plain = open(key, aad, new String(body, StandardCharsets.UTF_8).trim());
                System.out.println("PEER_RECEIVED " + plain);

                // 回一个同样封装的响应，并**回传一条本端持有的记录**，
                // 用来验证拉取方向与来源归属是否被完整保留。
                long now = System.currentTimeMillis() / 1000;
                String task = "来自桌面模拟对端的任务";
                String data = "{\"time\":\"09-12 21:00:00\",\"task\":\"" + task
                        + "\",\"result\":\"完成\",\"detail\":\"跨实现拉取验证\",\"steps\":1}";
                String recId = "peerrec" + Long.toHexString(Math.abs(data.hashCode()));
                String resp = "{\"ts\":" + now + ",\"server_seq\":0,\"merged\":0,"
                        + "\"device_id\":\"" + selfDeviceId + "\","
                        + "\"records\":[{\"origin_device_id\":\"" + selfDeviceId + "\","
                        + "\"id\":\"" + recId + "\",\"ts\":\"09-12 21:00:00\","
                        + "\"data\":" + data + "}]}";
                String env = seal(key, aad, resp);
                byte[] payload = env.getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                        + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
                System.out.println("PEER_REPLIED ok");
                System.out.flush();
            } catch (Exception e) {
                System.out.println("PEER_ERROR " + e);
                System.out.flush();
            } finally {
                try { s.close(); } catch (Exception ignored) { }
            }
        }
    }
}
