// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import android.widget.Toast;

/**
 * 二级设置页（手机形态）。
 *
 * 原主页配置面板（etBase / etKey / etModel / etAsr / etPlanner / etMaxSteps / swVision
 * 与保存按钮）整体搬到这里，Prefs 读写逻辑与原来完全一致；
 * 另外承载账号额度、兑换码、同账号设备列表、同步状态与保活入口。
 *
 * 手表形态不经过本页：手表主页的「设置」直接进 WatchSettingsActivity（自带配置项），
 * 本页只在手机路径被打开，因此搬移不会影响手表。
 */
public class SettingsActivity extends Activity {

    private Prefs prefs;
    private EditText etBase, etKey, etModel, etAsr, etPlanner, etMaxSteps, etRedeem, etSyncServer;
    // 布局使用平台 Switch（已回归第一版 UI，不再依赖 Material 组件库）
    private Switch swVision;
    private Switch swSync;
    private TextView tvSyncHint;
    private TextView tvTokens, tvDisplay, tvKeepAlive, tvAccount, tvQuota, tvDevices, tvSyncStatus;

    /** 坐标校准结果（本页生命周期内保留，随形态卡一起展示）。 */
    private String lastCalib = "";
    /** 额度拉取中标志：防止 onResume 自动拉取与按钮点击同时发出两个请求。 */
    private boolean quotaFetching;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        if (UiForm.isWatch(this)) {
            int edge = UiForm.watchEdgePx(this);
            LinearLayout content = findViewById(R.id.settingsContent);
            if (content != null) {
                content.setPadding(edge, content.getPaddingTop(), edge, content.getPaddingBottom());
            }
        }

        bind();
        loadCfg();
        wire();
    }

    private void bind() {
        etBase = findViewById(R.id.etBase);
        etKey = findViewById(R.id.etKey);
        etModel = findViewById(R.id.etModel);
        etAsr = findViewById(R.id.etAsr);
        etPlanner = findViewById(R.id.etPlanner);
        etMaxSteps = findViewById(R.id.etMaxSteps);
        swVision = findViewById(R.id.swVision);
        swSync = findViewById(R.id.swSync);
        tvSyncHint = findViewById(R.id.tvSyncHint);
        etRedeem = findViewById(R.id.etRedeem);
        etSyncServer = findViewById(R.id.etSyncServer);
        tvTokens = findViewById(R.id.tvTokens);
        tvDisplay = findViewById(R.id.tvDisplay);
        tvKeepAlive = findViewById(R.id.tvKeepAlive);
        tvAccount = findViewById(R.id.tvAccount);
        tvQuota = findViewById(R.id.tvQuota);
        tvDevices = findViewById(R.id.tvDevices);
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTokens();
        refreshDisplay();
        refreshKeepAlive();
        refreshAccount();
        refreshSyncStatus();
        // 额度与设备列表：进页面即拉一次 /api/v1/account/me（等价于"下拉刷新"，
        // 本项目零依赖、无 androidx SwipeRefreshLayout，故用「进页自动拉取 + 刷新额度按钮」两条路径）
        refreshQuotaFromServer();
    }

    private void wire() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnSave).setOnClickListener(v -> saveCfg());

        findViewById(R.id.btnResetTokens).setOnClickListener(v -> {
            prefs.resetTokens();
            refreshTokens();
            toast("Token 消耗计数已归零");
        });

        findViewById(R.id.btnDisplayRefresh).setOnClickListener(v -> refreshDisplay());
        findViewById(R.id.btnDisplayCalib).setOnClickListener(v -> runCalibrate());
        findViewById(R.id.btnA11y).setOnClickListener(v -> A11yLauncher.open(this));

        findViewById(R.id.btnAutoStart).setOnClickListener(v -> {
            KeepAliveGuide.openAutoStartSettings(this);
            toast(KeepAliveGuide.manualSteps(this));   // 详细操作路径随跳转一并提示
        });
        findViewById(R.id.btnBatteryIgnore).setOnClickListener(v -> KeepAliveGuide.openBatterySettings(this));

        // ---- 账号与额度 ----
        findViewById(R.id.btnRefreshQuota).setOnClickListener(v -> refreshQuotaFromServer(true));
        findViewById(R.id.btnAccountAction).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LoginActivity.class)));
        findViewById(R.id.btnLogout).setOnClickListener(v -> confirmLogout());
        findViewById(R.id.btnRedeem).setOnClickListener(v -> doRedeem());
        // 收款界面：套餐 → 下单 → 兑换码支付 → 订单记录
        findViewById(R.id.btnBuy).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, PurchaseActivity.class)));

        // ---- 同步（守则 S-4：默认关闭 + 显式 opt-in；该开关此前只存在于代码里，UI 无入口）----
        swSync.setChecked(prefs.syncEnabled());
        swSync.setOnCheckedChangeListener((b, on) -> {
            prefs.setSyncEnabled(on);
            refreshSyncStatus();
            toast(on ? "已允许上传本机历史与记忆" : "已关闭上传，历史与记忆只存本机");
        });

        findViewById(R.id.btnSyncSave).setOnClickListener(v -> {
            String u = etSyncServer.getText().toString().trim();
            if (u.isEmpty()) u = DevConfig.SYNC_URL;   // 清空即回到内置默认地址，避免同步被静默关闭
            etSyncServer.setText(u);
            prefs.setSyncServer(u);
            refreshSyncStatus();
            toast("同步地址已保存");
        });
        findViewById(R.id.btnSyncNow).setOnClickListener(v -> {
            SyncClient.syncNow(this);
            toast("已触发同步（后台执行）");
        });
        findViewById(R.id.btnLanRendezvous).setOnClickListener(v -> {
            toast("正在会合…");
            SyncClient.rendezvousNow(this, text -> new AlertDialog.Builder(this)
                    .setTitle("局域网会合")
                    .setMessage(text)
                    .setPositiveButton("好", null)
                    .show());
        });
    }

    // ---------- 模型与 API 配置（逻辑与迁移前一致） ----------

    private void loadCfg() {
        etBase.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        etModel.setText(prefs.model());
        etAsr.setText(prefs.asrModel());
        etPlanner.setText(prefs.plannerModel());
        etMaxSteps.setText(String.valueOf(prefs.maxSteps()));
        swVision.setChecked(prefs.vision());
        etSyncServer.setText(prefs.syncServer());
    }

    private void saveCfg() {
        int steps = 50;
        try {
            steps = Integer.parseInt(etMaxSteps.getText().toString().trim());
        } catch (Exception ignored) { }
        prefs.save(etBase.getText().toString().trim(), etKey.getText().toString().trim(),
                etModel.getText().toString().trim(), etAsr.getText().toString().trim(),
                swVision.isChecked(), steps, prefs.systemPrompt());
        prefs.setPlannerModel(etPlanner.getText().toString().trim());
        toast("配置已保存");
    }

    private void refreshTokens() {
        long tot = prefs.totalTokens();
        tvTokens.setText("总计: " + tot + " (" + formatK(tot) + ") | 输入: "
                + prefs.promptTokens() + " | 输出: " + prefs.completionTokens());
    }

    private static String formatK(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(java.util.Locale.US, "%.1fk", n / 1000.0);
        return String.valueOf(n);
    }

    // ---------- 账号 / 额度 / 兑换 ----------

    /** 本地状态展示（离线也能看上次同步到的额度）。 */
    // syncEnabled 的三态语义见 Prefs；此处只负责把当前状态如实告诉用户。
    private void refreshAccount() {
        if (prefs.loggedIn()) {
            String tail = prefs.accountInvalid() ? "　⚠ 令牌已失效，请重新登录"
                    : (prefs.syncDeviceId().isEmpty() ? "　本机未绑定设备" : "　本机已绑定设备");
            tvAccount.setText("账号：" + prefs.accountName() + "（" + prefs.accountId() + "）" + tail);
        } else {
            tvAccount.setText("当前未登录：历史与记忆只存本机，不参与同步。");
        }
        long q = prefs.accountQuota();
        long used = prefs.accountQuotaUsed();
        long left = Math.max(0, q - used);
        tvQuota.setText("额度：" + left + " / " + q + " units（已用 " + used + "，1 unit = 1000 tokens）");
    }

    private void refreshSyncStatus() {
        boolean on = prefs.syncEnabled();
        tvSyncStatus.setText((on ? "同步已开启" : "同步已关闭（只存本机）")
                + "\n" + prefs.syncStatus() + "\n服务器：" + SyncClient.serverBase(this));
        if (tvSyncHint != null) {
            tvSyncHint.setText(on
                    ? "开启中：任务历史与显式记忆会上传到账号（同账号设备可共享）"
                    : "关闭后只存本机，不上传服务器");
        }
    }

    /**
     * 拉取 /api/v1/account/me（额度 + 本账号设备列表）。
     *
     * 说明：本项目零依赖、无 androidx SwipeRefreshLayout，做不了真正的手势下拉刷新，
     * 因此刷新路径是「进页面自动拉一次 + 刷新额度按钮」两条，效果等价。
     */
    private void refreshQuotaFromServer() {
        refreshQuotaFromServer(false);
    }

    private void refreshQuotaFromServer(boolean byUser) {
        if (!prefs.loggedIn()) {
            if (byUser) toast("尚未登录，请先登录账号");
            tvDevices.setText("登录后点击「刷新额度」查看");
            return;
        }
        if (quotaFetching) return;   // 自动拉取与按钮点击可能重叠，避免重复请求
        quotaFetching = true;
        tvDevices.setText("正在刷新…");
        AccountClient.me(this, new AccountClient.ApiCallback<AccountClient.Me>() {
            @Override
            public void onOk(AccountClient.Me m) {
                quotaFetching = false;
                long left = Math.max(0, m.quota - m.quotaUsed);
                tvQuota.setText("额度：" + left + " / " + m.quota + " units（已用 " + m.quotaUsed
                        + "，1 unit = 1000 tokens）");
                tvAccount.setText("账号：" + m.name + "（" + m.accountId + "）");
                StringBuilder sb = new StringBuilder();
                if (m.devices.isEmpty()) {
                    sb.append("账号下暂无已绑定设备");
                } else {
                    sb.append("共 ").append(m.devices.size()).append(" 台：");
                    for (AccountClient.Device d : m.devices) {
                        sb.append("\n· ").append(d.brand).append(' ').append(d.model)
                                .append("　最后活跃 ").append(d.lastSeenAt)
                                .append(d.deviceId.equals(prefs.syncDeviceId()) ? "　[本机]" : "");
                    }
                }
                tvDevices.setText(sb.toString());
                refreshAccount();
            }

            @Override
            public void onErr(String message) {
                quotaFetching = false;
                tvDevices.setText("刷新失败：" + message);
                refreshAccount();
                // 401 由 AccountClient 统一置为「登录失效」，此处只负责提示重新登录
                if (prefs.accountInvalid()) promptRelogin();
            }
        });
    }

    private void promptRelogin() {
        new AlertDialog.Builder(this)
                .setTitle("登录已失效")
                .setMessage("账号令牌已失效（可能在其他设备重新登录过）。\n请重新登录以继续同步。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重新登录", (d, w) ->
                        startActivity(new android.content.Intent(this, LoginActivity.class)))
                .show();
    }

    private void doRedeem() {
        if (!prefs.loggedIn()) {
            toast("尚未登录，请先登录账号");
            return;
        }
        String code = etRedeem.getText().toString().trim();
        if (code.isEmpty()) {
            toast("请输入兑换码");
            return;
        }
        final Button btn = findViewById(R.id.btnRedeem);
        btn.setEnabled(false);
        tvDevices.setText("兑换中…");
        AccountClient.redeem(this, code, new AccountClient.ApiCallback<AccountClient.Redeem>() {
            @Override
            public void onOk(AccountClient.Redeem r) {
                btn.setEnabled(true);
                etRedeem.setText("");
                toast("兑换成功 +" + r.unitsAdded + " units");
                tvDevices.setText("兑换成功：+" + r.unitsAdded + " units，当前额度 " + r.quota + " units");
                refreshQuotaFromServer();   // 顺手刷新额度与设备列表
            }

            @Override
            public void onErr(String message) {
                btn.setEnabled(true);
                tvDevices.setText("兑换失败：" + message);
                toast("兑换失败：" + message);
            }
        });
    }

    private void confirmLogout() {
        if (!prefs.loggedIn()) {
            toast("当前未登录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将清除本机保存的账号令牌与设备绑定，任务历史与记忆仍保留在本机。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> {
                    LanSync.stop();          // 关掉局域网监听（密钥随 clearAccount 一并清除）
                    prefs.clearAccount();
                    refreshAccount();
                    refreshSyncStatus();
                    tvDevices.setText("登录后点击「刷新额度」查看");
                    toast("已退出登录");
                })
                .show();
    }

    // ---------- 保活 / 设备形态 ----------

    private void refreshKeepAlive() {
        if (tvKeepAlive == null) return;
        tvKeepAlive.setText(KeepAliveGuide.summary(this) + "\n" + KeepAliveGuide.advice(this));
    }

    private void refreshDisplay() {
        if (tvDisplay == null) return;
        AgentA11yService.refreshSpec();
        DisplaySpec s = AgentA11yService.spec();
        if (s == null) {
            tvDisplay.setText("设备形态：无障碍服务未连接，无法采集");
            return;
        }
        String txt = "设备形态（异形屏适配）\n" + s;
        if (!lastCalib.isEmpty()) txt += "\n\n校准结果：\n" + lastCalib;
        tvDisplay.setText(txt);
    }

    /** 坐标校准（T3）：根窗口 bounds 与节点越界统计，结果展示在形态卡。 */
    private void runCalibrate() {
        AgentA11yService svc = AgentA11yService.get();
        if (svc == null) {
            toast("无障碍服务未连接，无法校准");
            return;
        }
        lastCalib = svc.calibrate();
        refreshDisplay();
        toast(lastCalib.startsWith("校准失败") || lastCalib.startsWith("校准异常")
                ? "校准发现问题，详见形态卡" : "校准完成：坐标系统一致");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
