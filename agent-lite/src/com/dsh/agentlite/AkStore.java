// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 阿里云 AccessKey 的本地加密存储。
 *
 * <p><b>红线</b>（`PURCHASE_REDIRECT_DESIGN` §6）：AK 绝不离开设备——
 * 不上传服务器、不入日志、不进崩溃报告。本类是这条红线的唯一落点，
 * 所以它**只提供存取两个动作，不提供任何 getter 把密钥变成可打印的字符串**
 * （{@link #loadSecret()} 返回 char[]，调用方用完应 {@link #wipe(char[])}）。
 *
 * <p><b>加密方式</b>：AES-256-GCM，密钥由 Android Keystore 生成，
 * 私钥材料**永不进入 App 进程内存**（硬件支持时驻留 TEE/StrongBox）。
 * SharedPreferences 里只落三样东西：
 * <pre>
 *   ak_id         明文——AccessKeyId 本身不是秘密（它出现在每个请求的 Authorization 头里）
 *   ak_secret_enc Base64(IV ‖ GCM 密文)，IV 每次写入随机 12 字节
 *   ak_saved_at   写入时间戳（毫秒），仅用于界面展示"何时配置的"，不含敏感信息
 * </pre>
 *
 * <p><b>Keystore 失效的处理</b>：用户改锁屏密码、恢复出厂、清应用数据后，
 * Keystore 里的密钥可能被永久作废（{@code KeyPermanentlyInvalidatedException}）。
 * 此时**必须清空并让用户重新录入**，而不是抛异常崩掉或返回一段解不开的乱码——
 * 否则用户会看到一个"配置过了但永远用不了"的僵尸状态。
 */
public final class AkStore {

    private static final String PREF_FILE = "agentlite_ak";
    private static final String KEY_ID = "ak_id";
    private static final String KEY_SECRET_ENC = "ak_secret_enc";
    private static final String KEY_SAVED_AT = "ak_saved_at";

    /** Keystore 别名。改名等于让老用户的 AK 全部失效，别乱动。 */
    private static final String KEYSTORE_ALIAS = "agentlite_ak_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences sp;
    private final Context appCtx;

    public AkStore(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.sp = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 是否已配置 AK（**只说明有记录**，不代表密钥仍可解密——解密失败见 {@link #loadSecret()}）。 */
    public boolean has() {
        String id = sp.getString(KEY_ID, null);
        String enc = sp.getString(KEY_SECRET_ENC, null);
        return id != null && !id.isEmpty() && enc != null && !enc.isEmpty();
    }

    /** AccessKeyId。可安全展示/记录（它本来就在每个请求头里明文出现）。 */
    public String akId() {
        return sp.getString(KEY_ID, "");
    }

    /** 配置时间（epoch ms），0 = 未配置。 */
    public long savedAt() {
        return sp.getLong(KEY_SAVED_AT, 0L);
    }

    /**
     * 取出 AccessKeySecret（解密后的 char[]）。
     *
     * @return 密钥字符数组；**未配置、或 Keystore 密钥已失效时会返回 null 并自动清空存储**
     *         （调用方据此引导用户重新录入，不要当成"网络错误"处理）
     */
    public char[] loadSecret() {
        if (!has()) return null;
        String enc = sp.getString(KEY_SECRET_ENC, null);
        try {
            byte[] blob = Base64.decode(enc, Base64.NO_WRAP);
            if (blob.length <= GCM_IV_BYTES) { clear(); return null; }

            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
            byte[] ct = new byte[blob.length - GCM_IV_BYTES];
            System.arraycopy(blob, GCM_IV_BYTES, ct, 0, ct.length);

            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = c.doFinal(ct);
            char[] out = new String(plain, StandardCharsets.UTF_8).toCharArray();
            java.util.Arrays.fill(plain, (byte) 0);   // 明文副本用完即抹
            return out;
        } catch (Exception e) {
            // Keystore 密钥被作废 / 密文损坏：不可恢复，清空让用户重录。
            // 注意：这里**只记异常类型，不记任何密钥材料**。
            android.util.Log.w("AgentLiteAk", "AK 解密失败，已清空待重录："
                    + e.getClass().getSimpleName());
            clear();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 保存 AK。
     *
     * @param akId     AccessKeyId，明文存
     * @param secret   AccessKeySecret，加密存；传 null/空则等同 {@link #clear()}
     * @return true = 已安全落盘；false = 加密不可用（此时**不会**落任何明文，调用方应报错而非静默降级）
     */
    public boolean save(String akId, char[] secret) {
        if (akId == null || akId.trim().isEmpty() || secret == null || secret.length == 0) {
            clear();
            return false;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new java.security.SecureRandom().nextBytes(iv);

            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = new String(secret).getBytes(StandardCharsets.UTF_8);
            byte[] ct = c.doFinal(plain);
            java.util.Arrays.fill(plain, (byte) 0);

            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);

            sp.edit()
                    .putString(KEY_ID, akId.trim())
                    .putString(KEY_SECRET_ENC, Base64.encodeToString(blob, Base64.NO_WRAP))
                    .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    .apply();
            return true;
        } catch (Exception e) {
            // 宁可失败也不能明文落盘——这是本类的存在意义
            android.util.Log.w("AgentLiteAk", "AK 加密失败，未保存："
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    /** 清空 AK 记录。切换账号、Key 作废、用户主动移除时调用。 */
    public void clear() {
        sp.edit()
                .remove(KEY_ID)
                .remove(KEY_SECRET_ENC)
                .remove(KEY_SAVED_AT)
                .apply();
        // Keystore 里的密钥也一并删掉：不留给下一次加密复用，
        // 避免"换了账号但密钥还是老的"这种难查的问题。
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEYSTORE_ALIAS)) ks.deleteEntry(KEYSTORE_ALIAS);
        } catch (Exception ignored) {
            // 删不掉不影响正确性：密文已经清了
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 取（或首次生成）Keystore 里的 AES 密钥。 */
    private SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);

        java.security.Key existing = ks.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 刻意**不**要求用户认证：AK 需要在后台自动签名，
                // 若设 setUserAuthenticationRequired(true)，锁屏时无人值守的创建流程会失败。
                .build());
        return kg.generateKey();
    }

    /** 用完密钥后抹掉 char[]，减少明文在堆上的驻留时间。 */
    public static void wipe(char[] secret) {
        if (secret != null) java.util.Arrays.fill(secret, '\0');
    }
}
