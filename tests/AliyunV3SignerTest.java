// SPDX-License-Identifier: Apache-2.0
//
// 桌面 JVM 回归测试：AliyunV3Signer 不含任何 android.* API，可脱离 Android 直接跑。
//   javac -d /tmp/v3test tests/AliyunV3SignerTest.java agent-lite/src/com/dsh/agentlite/AliyunV3Signer.java
//   java  -cp /tmp/v3test AliyunV3SignerTest
//
// 说明：AK/SK 全部是**假值**，仅用于固定向量回归——真凭据永不入仓（见 .gitignore 注释）。

import com.dsh.agentlite.AliyunV3Signer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class AliyunV3SignerTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        // ---------- 1. RFC3986 编码 ----------
        eq("空格 → %20（不是 +）", "%20", AliyunV3Signer.percentEncode(" "));
        eq("加号 → %2B", "%2B", AliyunV3Signer.percentEncode("+"));
        eq("星号 → %2A", "%2A", AliyunV3Signer.percentEncode("*"));
        eq("波浪号不编码", "~", AliyunV3Signer.percentEncode("~"));
        eq("斜杠被编码", "%2F", AliyunV3Signer.percentEncode("/"));
        eq("冒号被编码", "%3A", AliyunV3Signer.percentEncode(":"));
        eq("非保留字符原样", "AZaz09-_.~", AliyunV3Signer.percentEncode("AZaz09-_.~"));
        eq("UTF-8 中文逐字节编码", "%E4%B8%AD%E6%96%87", AliyunV3Signer.percentEncode("中文"));
        eq("十六进制大写", "%2F", AliyunV3Signer.percentEncode("/"));

        // ---------- 2. 规范化 URI ----------
        eq("空路径 → /", "/", AliyunV3Signer.canonicalUri(""));
        eq("null 路径 → /", "/", AliyunV3Signer.canonicalUri(null));
        eq("普通路径保持", "/modelstudio/apikeys", AliyunV3Signer.canonicalUri("/modelstudio/apikeys"));
        eq("前导斜杠不重复", "/a/b", AliyunV3Signer.canonicalUri("/a/b"));
        eq("剥掉查询串", "/modelstudio/apikeys", AliyunV3Signer.canonicalUri("/modelstudio/apikeys?x=1"));
        eq("段内特殊字符被编码", "/a%20b/c", AliyunV3Signer.canonicalUri("/a b/c"));

        // ---------- 3. 规范化查询串 ----------
        Map<String, String> q = new LinkedHashMap<>();
        q.put("workspaceId", "ws-8af73c50f5596193");
        q.put("description", "test");
        eq("查询串按 key 升序",
                "description=test&workspaceId=ws-8af73c50f5596193",
                AliyunV3Signer.canonicalQueryString(q));
        eq("空查询串", "", AliyunV3Signer.canonicalQueryString(null));
        Map<String, String> qSp = new LinkedHashMap<>();
        qSp.put("k", "a b");
        eq("查询值里的空格 → %20", "k=a%20b", AliyunV3Signer.canonicalQueryString(qSp));

        // ---------- 4. CanonicalRequest 逐行形状 ----------
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "bailian.aliyuncs.com");
        headers.put("x-acs-action", "CreateApiKey");
        headers.put("x-acs-version", "2026-02-10");
        headers.put("x-acs-date", "2026-09-13T10:00:00Z");
        headers.put("x-acs-content-sha256", "EMPTY_SHA");

        String cr = AliyunV3Signer.canonicalRequest(
                "post", "/modelstudio/apikeys", q, headers, "EMPTY_SHA");
        String[] lines = cr.split("\n", -1);

        eq("第 1 行 = 大写方法", "POST", lines[0]);
        eq("第 2 行 = 规范化 URI", "/modelstudio/apikeys", lines[1]);
        eq("第 3 行 = 规范化查询串", "description=test&workspaceId=ws-8af73c50f5596193", lines[2]);
        eq("第 4 行 = 首个 header（字典序 host 优先）", "host:bailian.aliyuncs.com", lines[3]);
        eq("header 行以冒号紧贴、无空格", "x-acs-action:CreateApiKey", lines[4]);
        // header 名按字典序：host < x-acs-action < x-acs-content-sha256 < x-acs-date < x-acs-version
        eq("SignedHeaders 字典序分号连接",
                "host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-version", lines[9]);
        eq("末行 = 正文摘要", "EMPTY_SHA", lines[10]);
        eq("共 11 行（方法/URI/查询/5 header/签名头/摘要 + 空行分隔）", "11", String.valueOf(lines.length));
        yes("CanonicalHeaders 段以换行结尾后再接空行", cr.contains("x-acs-version:2026-02-10\n\nhost;"));

        // ---------- 5. 端到端签名：形状与确定性 ----------
        AliyunV3Signer.Request req = new AliyunV3Signer.Request(
                "AKID_FAKE_FOR_TEST", "SECRET_FAKE_FOR_TEST".toCharArray(),
                "POST", "bailian.aliyuncs.com", "/modelstudio/apikeys",
                "CreateApiKey", "2026-02-10", "2026-09-13T10:00:00Z",
                q, "", new TreeMap<>());

        AliyunV3Signer.Signed s1 = AliyunV3Signer.sign(req);
        AliyunV3Signer.Signed s2 = AliyunV3Signer.sign(req);

        yes("相同输入 → 相同签名（纯函数）", s1.signature.equals(s2.signature));
        yes("签名是 64 位小写十六进制",
                s1.signature.matches("[0-9a-f]{64}"));
        yes("Authorization 前缀正确", s1.authorization.startsWith("ACS3-HMAC-SHA256 Credential=AKID_FAKE_FOR_TEST,SignedHeaders="));
        yes("Authorization 含 Signature 字段", s1.authorization.contains(",Signature=" + s1.signature));
        yes("公共头被自动补齐",
                s1.headers.containsKey("x-acs-content-sha256")
                        && s1.headers.containsKey("x-acs-date")
                        && s1.headers.containsKey("x-acs-action")
                        && s1.headers.containsKey("x-acs-version")
                        && s1.headers.containsKey("host"));
        eq("空正文摘要 = SHA256(\"\")",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                s1.contentSha256);
        eq("Authorization 里的 SignedHeaders 与实际签名集合一致",
                s1.signedHeaders, extractSignedHeaders(s1.authorization));
        yes("SignedHeaders 含 host", s1.signedHeaders.contains("host"));
        yes("SignedHeaders 不含 authorization",
                !s1.signedHeaders.toLowerCase().contains("authorization"));

        // 两级签名的形状：StringToSign = 算法名 + \n + 摘要（64 位十六进制）
        String[] sts = s1.stringToSign.split("\n", -1);
        eq("StringToSign 第 1 行 = 算法名", "ACS3-HMAC-SHA256", sts[0]);
        eq("StringToSign 第 2 行 = CanonicalRequest 的 SHA256", "64", String.valueOf(sts[1].length()));
        eq("StringToSign 只有 2 行", "2", String.valueOf(sts.length));

        // 独立复算一遍签名链，确认实现没有自洽的错（用 JDK 原语重走官方伪代码）
        String expectSig = independentSign("SECRET_FAKE_FOR_TEST", s1.canonicalRequest);
        eq("与独立复算的签名一致", expectSig, s1.signature);

        // 改一个字节，签名必须变（防止把某段漏进签名）
        AliyunV3Signer.Request req2 = new AliyunV3Signer.Request(
                "AKID_FAKE_FOR_TEST", "SECRET_FAKE_FOR_TEST".toCharArray(),
                "POST", "bailian.aliyuncs.com", "/modelstudio/apikeys",
                "CreateApiKey", "2026-02-10", "2026-09-13T10:00:01Z",   // 只改 1 秒
                q, "", new TreeMap<>());
        yes("x-acs-date 变更 → 签名变更", !AliyunV3Signer.sign(req2).signature.equals(s1.signature));

        AliyunV3Signer.Request req3 = new AliyunV3Signer.Request(
                "AKID_FAKE_FOR_TEST", "SECRET_FAKE_FOR_TEST".toCharArray(),
                "POST", "bailian.aliyuncs.com", "/modelstudio/apikeys",
                "CreateApiKey", "2026-02-10", "2026-09-13T10:00:00Z",
                q, "{\"a\":1}", new TreeMap<>());                        // 加正文
        AliyunV3Signer.Signed s3 = AliyunV3Signer.sign(req3);
        yes("正文变更 → 签名变更", !s3.signature.equals(s1.signature));
        yes("正文变更 → content-sha256 变更", !s3.contentSha256.equals(s1.contentSha256));

        // ---------- 6. 官方固定向量：锁定「不派生密钥」这一条 ----------
        // 来源：阿里云通用 V3 规范文档自带的示例向量（AccessKeyId/SECRET 均为占位值）。
        // 官方给定 HashedCanonicalRequest 与最终 Signature，用于验证 Secret 的用法。
        // 说明：官方示例的完整请求头未公布，无法重建 CanonicalRequest 原文，
        //       但 `(HashedCanonicalRequest → Signature)` 这对值已足够锁死密钥派生方式。
        final String OFFICIAL_HASHED_CR =
                "7ea06492da5221eba5297e897ce16e55f964061054b7695beedaac1145b1e259";
        final String OFFICIAL_SIGNATURE =
                "06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0";

        String officialSts = "ACS3-HMAC-SHA256\n" + OFFICIAL_HASHED_CR;
        eq("官方向量：原始 secret 直接 HMAC-SHA256 → 命中官方签名",
                OFFICIAL_SIGNATURE,
                AliyunV3Signer.hex(AliyunV3Signer.hmacSha256(
                        "YourAccessKeySecret".getBytes(StandardCharsets.UTF_8),
                        officialSts.getBytes(StandardCharsets.UTF_8))));
        yes("官方向量：V2 式 secret+\"&\" 派生 **不**命中（证明 V3 不派生）",
                !OFFICIAL_SIGNATURE.equals(AliyunV3Signer.hex(AliyunV3Signer.hmacSha256(
                        "YourAccessKeySecret&".getBytes(StandardCharsets.UTF_8),
                        officialSts.getBytes(StandardCharsets.UTF_8)))));
        yes("官方向量：ACS3 后缀派生 **不**命中",
                !OFFICIAL_SIGNATURE.equals(AliyunV3Signer.hex(AliyunV3Signer.hmacSha256(
                        "YourAccessKeySecretACS3".getBytes(StandardCharsets.UTF_8),
                        officialSts.getBytes(StandardCharsets.UTF_8)))));
        yes("官方签名是小写 hex（不是 base64）", OFFICIAL_SIGNATURE.matches("[0-9a-f]{64}"));

        // ---------- 7. 打印一个固定向量，供人工跨实现比对 ----------
        System.out.println();
        System.out.println("---- 固定向量（AK/SK 为假值，可安全外传比对）----");
        System.out.println("CanonicalRequest:\n" + s1.canonicalRequest.replace("\n", "\\n\n"));
        System.out.println("StringToSign: " + s1.stringToSign.replace("\n", "\\n"));
        System.out.println("Signature:    " + s1.signature);
        System.out.println("Authorization: " + s1.authorization);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 用 JDK 原语独立重走一遍官方伪代码——不调用被测类的任何签名方法，避免自证。 */
    private static String independentSign(String secret, String canonicalRequest) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String hashedCanonical = hexLower(md.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String stringToSign = "ACS3-HMAC-SHA256\n" + hashedCanonical;

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hexLower(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexLower(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String extractSignedHeaders(String auth) {
        int i = auth.indexOf("SignedHeaders=");
        int j = auth.indexOf(",Signature=", i);
        return auth.substring(i + "SignedHeaders=".length(), j);
    }

    private static void eq(String name, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "\n       期望: " + want + "\n       实际: " + got); }
    }

    private static void yes(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }
}
