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
 * 账号令牌（Bearer）的本地加密存储。
 *
 * <p><b>为什么有这类</b>：账号令牌覆盖全部业务接口（同步、额度、跨设备指令、
 * 兑换码），等价于账号密码。此前 {@code Prefs} 把它明文写进普通
 * SharedPreferences（手册 §15.1 P1-2）——只要有 root 或一次备份导出，
 * 令牌就完整泄露，而服务端**只存 sha256**，说明设计上它本来就该是"不可还原"的。
 *
 * <p><b>做法与 {@link AkStore} 一致</b>：AES-256-GCM，密钥由 Android Keystore
 * 生成（硬件支持时驻留 TEE），SharedPreferences 只落 Base64(IV ‖ 密文)。
 * 别名与 AK **刻意分开**（{@code agentlite_account_token_v1}）：退出登录清掉
 * 令牌时不应顺手作废 AK，反之亦然。
 *
 * <p><b>迁移</b>：旧版本把明文写在 {@code agent_prefs} 的 {@code account_token}
 * 里。{@link Prefs#accountToken()} 首次读取时会尝试「加密落盘 → 删除明文」，
 * 只有加密成功才删——加密不可用时宁可暂时保留旧值，也不让用户凭空掉线。
 *
 * <p><b>Keystore 失效</b>（改锁屏密码/恢复出厂/清数据）时无法解密：直接清空并
 * 让用户重新登录，而不是把一段解不开的乱码当成令牌反复送给服务端。
 */
public final class TokenStore {

    private static final String TAG = "AgentLiteToken";
    private static final String PREF_FILE = "agentlite_cred";
    private static final String KEY_ENC = "account_token_enc";
    private static final String KEY_SAVED_AT = "account_token_saved_at";

    /** Keystore 别名。改名等于让已登录用户全部掉线，别乱动。 */
    private static final String KEYSTORE_ALIAS = "agentlite_account_token_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /**
     * 进程内缓存。令牌被 {@code LlmClient}/{@code SyncClient} 每步读取，
     * 每次都走一次 Keystore 解密没有必要；缓存随进程消亡，不额外扩大暴露面。
     */
    private static final Object LOCK = new Object();
    private static boolean sLoaded = false;
    private static String sCache = "";

    private final SharedPreferences sp;

    public TokenStore(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 是否存在加密令牌（**不代表可解密**——见 {@link #load()}）。 */
    public boolean has() {
        String enc = sp.getString(KEY_ENC, null);
        return enc != null && !enc.isEmpty();
    }

    /** 写入时间（epoch ms），0 = 未记录。 */
    public long savedAt() {
        return sp.getLong(KEY_SAVED_AT, 0L);
    }

    /**
     * 取出令牌明文。
     *
     * @return 令牌；未登录、或 Keystore 密钥已失效时返回空串（此时存储已被清空，
     *         调用方应按"未登录"处理并引导重新登录）
     */
    public String load() {
        synchronized (LOCK) {
            if (sLoaded) return sCache;
        }
        String out = "";
        String enc = sp.getString(KEY_ENC, "");
        if (enc != null && !enc.isEmpty()) {
            try {
                out = decrypt(enc);
            } catch (Exception e) {
                // 只记异常类型，不记任何令牌材料
                android.util.Log.w(TAG, "账号令牌解密失败，已清空待重新登录："
                        + e.getClass().getSimpleName());
                clearStorage();
                out = "";
            }
        }
        synchronized (LOCK) {
            sCache = out;
            sLoaded = true;
        }
        return out;
    }

    /**
     * 加密保存令牌。
     *
     * @return true = 已加密落盘；false = 加密不可用（此时**不落任何明文**，
     *         调用方应告知用户"安全存储不可用"，而不是静默降级）
     */
    public boolean save(String token) {
        if (token == null || token.isEmpty()) {
            clear();
            return false;
        }
        try {
            String enc = encrypt(token);
            boolean committed = sp.edit()
                    .putString(KEY_ENC, enc)
                    .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                    .commit();
            if (!committed) {
                android.util.Log.w(TAG, "账号令牌密文落盘失败");
                return false;
            }
            synchronized (LOCK) {
                sCache = token;
                sLoaded = true;
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "账号令牌加密失败，未保存：" + e.getClass().getSimpleName());
            return false;
        }
    }

    /** 退出登录、令牌失效时调用：密文、Keystore 别名与缓存一起清。 */
    public void clear() {
        clearStorage();
        synchronized (LOCK) {
            sCache = "";
            sLoaded = true;
        }
    }

    private void clearStorage() {
        sp.edit().remove(KEY_ENC).remove(KEY_SAVED_AT).apply();
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

    private String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        // **不**由调用方提供 IV：Android Keystore 生成的密钥默认
        // randomizedEncryptionRequired=true，此时自带 IV 会被直接拒绝
        // （真机实测：InvalidAlgorithmParameterException，见 2026-09-14 真机日志）。
        // 正确做法是让 Keystore 生成随机 IV，再取出来与密文一起存。
        c.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = c.getIV();
        if (iv == null || iv.length == 0) {
            throw new IllegalStateException("Keystore 未返回 IV");
        }
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    private String decrypt(String enc) throws Exception {
        byte[] blob = Base64.decode(enc, Base64.NO_WRAP);
        if (blob.length <= GCM_IV_BYTES) throw new IllegalStateException("blob too short");
        byte[] iv = new byte[GCM_IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
        byte[] ct = new byte[blob.length - GCM_IV_BYTES];
        System.arraycopy(blob, GCM_IV_BYTES, ct, 0, ct.length);
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = c.doFinal(ct);
        String out = new String(plain, StandardCharsets.UTF_8);
        java.util.Arrays.fill(plain, (byte) 0);   // 明文副本用完即抹
        return out;
    }

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
                // 与 AkStore 同理：不要求用户认证，否则后台自动同步会因锁屏失败。
                .build());
        return kg.generateKey();
    }
}
