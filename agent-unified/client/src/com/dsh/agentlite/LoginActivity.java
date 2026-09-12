// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 账号登录 / 注册页。
 *
 * 流程：本地校验账号名与密钥 → 后台线程调 /account/login 或 /account/register
 *      → 保存 account_token / account_id / 账号名 → 自动调 /device/bind 绑定本机并保存 device_id。
 *
 * 约定：
 *  - 所有网络 IO 都在 AccountClient 的后台线程里跑，本类只在主线程回调中改 UI；
 *  - 失败提示全部是可读中文（409 → 账号名已被占用，401 → 账号名或账号密钥错误）；
 *  - 「稍后再说」允许跳过登录直接使用本机功能，主页只保留引导，不强制登录。
 */
public class LoginActivity extends Activity {

    private Prefs prefs;
    private EditText etName, etKey;
    private Button btnLogin, btnRegister, btnSkip;
    private TextView tvMsg, tvCurrent;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        prefs = new Prefs(this);

        // 圆形表盘：内容收进内接方形（手机保持 16dp 边距不变）
        if (UiForm.isWatch(this)) {
            int edge = UiForm.watchEdgePx(this);
            LinearLayout content = findViewById(R.id.loginContent);
            if (content != null) {
                content.setPadding(edge, content.getPaddingTop(), edge, content.getPaddingBottom());
            }
        }

        etName = findViewById(R.id.etName);
        etKey = findViewById(R.id.etKey);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        btnSkip = findViewById(R.id.btnSkip);
        tvMsg = findViewById(R.id.tvMsg);
        tvCurrent = findViewById(R.id.tvCurrent);

        // 已登录过则回填账号名，省得重新输入
        etName.setText(prefs.accountName());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> confirmRegister());
        btnSkip.setOnClickListener(v -> {
            if (prefs.loggedIn()) {
                confirmLogout();
            } else {
                finish();   // 稍后再说：不强制登录，主页仍有登录入口
            }
        });

        TextView target = findViewById(R.id.tvSyncTarget);
        if (target != null) {
            String server = SyncClient.serverBase(this);
            target.setText("同步服务器：" + (server.isEmpty() ? "未配置（请在设置页填写）" : server));
        }
        refreshCurrent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
    }

    // ---------- 状态展示 ----------

    private void refreshCurrent() {
        if (prefs.loggedIn()) {
            tvCurrent.setText("已登录：" + prefs.accountName() + "（" + prefs.accountId() + "）\n"
                    + "额度 " + prefs.accountQuota() + " units · 已用 " + prefs.accountQuotaUsed()
                    + (prefs.syncDeviceId().isEmpty() ? " · 本机尚未绑定" : " · 本机已绑定"));
            btnSkip.setText("退出登录");
        } else {
            String why = prefs.accountInvalid() ? "上次登录已失效，请重新登录" : "当前未登录";
            tvCurrent.setText(why + "\n未登录时任务历史与记忆只保存在本机，不会同步到账号。");
            btnSkip.setText("稍后再说");
        }
    }

    /** 消息区：error=true 用警示色（颜色取色板内已有的语义色）。 */
    private void msg(String text, boolean error) {
        tvMsg.setVisibility(View.VISIBLE);
        tvMsg.setText(text);
        tvMsg.setTextColor(error ? 0xFFF87171 : 0xFF34D399);
    }

    private void setBusy(boolean v) {
        busy = v;
        btnLogin.setEnabled(!v);
        btnRegister.setEnabled(!v);
        btnSkip.setEnabled(!v);
        btnLogin.setAlpha(v ? 0.6f : 1f);
        btnRegister.setAlpha(v ? 0.6f : 1f);
    }

    // ---------- 登录 ----------

    private void doLogin() {
        if (busy) return;
        String name = AccountClient.normalizeName(etName.getText().toString());
        String key = etKey.getText().toString();
        String err = AccountClient.nameError(name);
        if (err == null) err = AccountClient.keyError(key);
        if (err != null) {
            msg(err, true);
            return;
        }
        setBusy(true);
        msg("登录中…", false);
        AccountClient.login(this, name, key, new AccountClient.ApiCallback<AccountClient.Session>() {
            @Override
            public void onOk(AccountClient.Session s) {
                afterAuthOk(s, key, "登录成功，正在绑定本机设备…");
            }

            @Override
            public void onErr(String message) {
                setBusy(false);
                msg(message, true);
            }
        });
    }

    // ---------- 注册 ----------

    private void confirmRegister() {
        if (busy) return;
        // 注册前把规则与风险讲清楚（账号名不可改、密钥丢失无法找回）
        new AlertDialog.Builder(this)
                .setTitle("注册新账号")
                .setMessage("将创建新账号并获得 500 units 试用额度。\n\n"
                        + "· " + AccountClient.NAME_RULE + "\n"
                        + "· " + AccountClient.KEY_RULE + "\n\n"
                        + "账号密钥无法找回，请自行妥善保存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认注册", (d, w) -> doRegister())
                .show();
    }

    private void doRegister() {
        String name = AccountClient.normalizeName(etName.getText().toString());
        String key = etKey.getText().toString();
        String err = AccountClient.nameError(name);
        if (err == null) err = AccountClient.keyError(key);
        if (err != null) {
            msg(err, true);
            return;
        }
        setBusy(true);
        msg("注册中…", false);
        AccountClient.register(this, name, key, new AccountClient.ApiCallback<AccountClient.Session>() {
            @Override
            public void onOk(AccountClient.Session s) {
                afterAuthOk(s, key, "注册成功（赠送 " + s.quota + " units），正在绑定本机设备…");
            }

            @Override
            public void onErr(String message) {
                setBusy(false);
                msg(message, true);
            }
        });
    }

    // ---------- 鉴权成功后的统一收尾 ----------

    /**
     * 登录 / 注册成功后的统一收尾。
     *
     * 关键点：**账号密钥明文只在这一刻存在**（回调参数 plainKey），
     * 局域网对称密钥必须在这里就地派生并保存（PBKDF2-HMAC-SHA256 10 万次，服务端不经手密钥）；
     * 派生之后明文随 Activity 一起被丢弃，不再落盘。
     *
     * 随后启动局域网直连服务：LanSync.start 只绑定站点本地地址（不绑 0.0.0.0），
     * 且对「已在运行」「未派生密钥」「局域网开关关闭」都安全跳过，重复调用无副作用。
     */
    private void afterAuthOk(AccountClient.Session s, String plainKey, String okMsg) {
        AccountClient.saveSession(prefs, s);
        LanSync.deriveAndStoreKey(this, s.accountId, plainKey);
        LanSync.start(this);
        msg(okMsg, false);
        bindThisDevice();
    }

    // ---------- 设备绑定 ----------

    private void bindThisDevice() {
        AccountClient.bindDevice(this, new AccountClient.ApiCallback<String>() {
            @Override
            public void onOk(String deviceId) {
                prefs.setSyncDeviceId(deviceId);
                setBusy(false);
                toast("登录成功");
                // 登录后立刻补一次同步：把本机已有的任务历史与记忆归到新账号名下（服务端按 id 去重）
                SyncClient.syncNow(LoginActivity.this);
                refreshCurrent();
                finish();
            }

            @Override
            public void onErr(String message) {
                setBusy(false);
                // 已登录但绑定失败：不阻塞使用，同步时 SyncClient 会自动补绑
                msg("已登录，但本机设备绑定失败：" + message + "\n（同步时会自动重试绑定）", true);
                toast("已登录，设备绑定待重试");
                refreshCurrent();
            }
        });
    }

    // ---------- 退出登录 ----------

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将清除本机保存的账号令牌与设备绑定。\n"
                        + "任务历史与记忆仍保留在本机；下次登录会重新绑定本机设备。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> {
                    LanSync.stop();          // 关掉局域网监听，避免残留已失效账号的密钥继续在网内服务
                    prefs.clearAccount();    // 同时清除派生出的局域网密钥
                    etKey.setText("");
                    msg("已退出登录", false);
                    refreshCurrent();
                    toast("已退出登录");
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
