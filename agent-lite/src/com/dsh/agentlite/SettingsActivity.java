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
    private EditText etAsrBase, etAsrKey;   // 云端语音（智谱 GLM 等）
    // 布局使用平台 Switch（已回归第一版 UI，不再依赖 Material 组件库）
    private Switch swVision;
    private Switch swSync;
    private Switch swSingleModel;
    private TextView tvSingleModelHint;
    private TextView tvSyncHint;
    private TextView tvTokens, tvDisplay, tvKeepAlive, tvAccount, tvQuota, tvDevices, tvSyncStatus;

    /** 坐标校准结果（本页生命周期内保留，随形态卡一起展示）。 */
    private String lastCalib = "";
    /** 额度拉取中标志：防止 onResume 自动拉取与按钮点击同时发出两个请求。 */
    private boolean quotaFetching;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ElderUi.applyTheme(this);   // 手机：浅色大字主题（须在 setContentView 之前）
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
        // 还没有可用的使用方式（未登录也没填自己的配置）时，直接展开高级设置——
        // 从主页「打开设置」过来的人正是要填这些，别让他再找一次开关
        setAdvancedVisible(!prefs.credentialRoute(prefs.model()).isUsable());
    }

    /** 高级设置（模型 / 用量 / 屏幕适配 / 同步）收起与展开。 */
    private void setAdvancedVisible(boolean show) {
        android.view.View group = findViewById(R.id.advancedGroup);
        Button toggle = findViewById(R.id.btnAdvanced);
        if (group == null || toggle == null) return;
        group.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        toggle.setText(show ? "高级设置（点这里收起）\n一般不用改，可以请家人帮忙"
                : "高级设置（点开）\n一般不用改，可以请家人帮忙");
    }

    private void bind() {
        etBase = findViewById(R.id.etBase);
        etKey = findViewById(R.id.etKey);
        etModel = findViewById(R.id.etModel);
        etAsr = findViewById(R.id.etAsr);
        etAsrBase = findViewById(R.id.etAsrBase);
        etAsrKey = findViewById(R.id.etAsrKey);
        findViewById(R.id.btnAsrTest).setOnClickListener(v -> showAsrStatus());
        etPlanner = findViewById(R.id.etPlanner);
        etMaxSteps = findViewById(R.id.etMaxSteps);
        swVision = findViewById(R.id.swVision);
        swSync = findViewById(R.id.swSync);
        swSingleModel = findViewById(R.id.swSingleModel);
        tvSingleModelHint = findViewById(R.id.tvSingleModelHint);
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
        findViewById(R.id.btnAdvanced).setOnClickListener(v ->
                setAdvancedVisible(findViewById(R.id.advancedGroup).getVisibility() != android.view.View.VISIBLE));

        findViewById(R.id.btnSave).setOnClickListener(v -> saveCfg());

        // 单模型模式即时生效（不必等"保存"）：勾上/取消立刻反映到输入框可用状态，
        // 并立刻落盘——否则用户以为已生效、返回主界面仍走多模型，属于静默不一致。
        swSingleModel.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setSingleModelMode(checked);
            applySingleModelUi();
            toast(checked ? "已开启单模型模式" : "已关闭单模型模式");
        });

        findViewById(R.id.btnResetTokens).setOnClickListener(v -> ElderUi.show(new AlertDialog.Builder(this)
                .setTitle("把用量统计归零？")
                .setMessage("只清零本机的计数，不影响账号额度。")
                .setNegativeButton("不了", null)
                .setPositiveButton("重置", (d, w) -> {
                    prefs.resetTokens();
                    refreshTokens();
                    toast("Token 消耗计数已归零");
                })));

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
        // 充值改写为「跳官方购买 + 自动创建 API Key」——用户在此配置 AccessKey、
        // 打开百炼官方页买 token 资源包，返回后自动创建并启用 DashScope Key。
        // （兑换码/下单/订单那套 UI 已按决策从该页面移除；服务端代码保留，另一条模式仍可用。）
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
            SyncClient.rendezvousNow(this, text -> ElderUi.show(new AlertDialog.Builder(this)
                    .setTitle("局域网会合")
                    .setMessage(text)
                    .setPositiveButton("好", null)));
        });
    }

    // ---------- 模型与 API 配置（逻辑与迁移前一致） ----------

    private void loadCfg() {
        etBase.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        etModel.setText(prefs.model());
        // ⚠️ 必须用 raw 值：单模型模式下 asrModel()/plannerModel() 返回的是主模型名，
        // 直接回填会让用户一保存就把主模型名写进这两个字段，永久覆盖原配置。
        etAsr.setText(prefs.asrModelRaw());
        etAsrBase.setText(prefs.asrBaseUrl());
        etAsrKey.setText(prefs.asrApiKey());
        etPlanner.setText(prefs.plannerModelRaw());
        etMaxSteps.setText(String.valueOf(prefs.maxSteps()));
        swVision.setChecked(prefs.vision());
        swSingleModel.setChecked(prefs.singleModelMode());
        etSyncServer.setText(prefs.syncServer());
        applySingleModelUi();
    }

    /**
     * 单模型模式下把"额外模型"输入框置灰并说明原因。
     *
     * <p>置灰而非隐藏：用户需要看到「这些值还在、只是没生效」，否则会以为配置丢了。
     */
    private void applySingleModelUi() {
        boolean on = swSingleModel.isChecked();
        for (EditText et : new EditText[]{etAsr, etPlanner}) {
            et.setEnabled(!on);
            et.setAlpha(on ? 0.5f : 1.0f);
        }
        if (on) {
            String main = etModel.getText().toString().trim();
            tvSingleModelHint.setText("已开启：语音与规划都走主模型「" + main
                    + "」。百炼资源包按模型名严格匹配，买一个包只能抵扣一个模型，"
                    + "多模型时其余调用会走按量付费。要用别的模型请关闭本开关。");
        } else {
            tvSingleModelHint.setText("已关闭：允许语音/规划使用不同模型。"
                    + "注意资源包只抵扣与之同名的模型，其余按量付费。");
        }
    }

    /** 「测试语音转写链路」：把当前生效的通道、本地模型状态、云端配置一次说清楚。 */
    private void showAsrStatus() {
        StringBuilder b = new StringBuilder();
        String base = etAsrBase.getText().toString().trim();
        String model = etAsr.getText().toString().trim();
        b.append("当前优先级：云端 → 本地离线 → 主模型音频\n\n");
        if (base.isEmpty()) {
            b.append("· 云端：未配置（不启用）\n");
        } else {
            b.append("· 云端：").append(base).append("\n")
             .append("  模型：").append(model.isEmpty() ? "（留空，需填 glm-asr-2512 之类）" : model).append("\n")
             .append("  密钥：").append(etAsrKey.getText().toString().trim().isEmpty()
                     ? "用主密钥" : "已单独填写").append("\n");
        }
        boolean libOk = WhisperAsr.available();
        boolean modelOk = WhisperAsr.modelReady(this);
        b.append("· 本地离线：").append(!libOk ? "库不可用" : modelOk ? "就绪（whisper.cpp，完全离线）" : "已装库，模型未下载")
         .append("\n");
        if (libOk && modelOk) {
            b.append("  模型文件：").append(WhisperAsr.modelFile(this).length() / 1048576).append("MB\n");
        }
        b.append("\n提示：填了云端端点就用云端；留空则用本地离线，飞行模式下也能识别。");
        ElderUi.show(new android.app.AlertDialog.Builder(this).setTitle("语音转写链路").setMessage(b.toString())
                .setPositiveButton("知道了", null));
    }

    private void saveCfg() {
        int steps = 50;
        try {
            steps = Integer.parseInt(etMaxSteps.getText().toString().trim());
        } catch (Exception ignored) { }
        prefs.setAsrCloud(etAsrBase.getText().toString(), etAsrKey.getText().toString());
        prefs.save(etBase.getText().toString().trim(), etKey.getText().toString().trim(),
                etModel.getText().toString().trim(), etAsr.getText().toString().trim(),
                swVision.isChecked(), steps, prefs.systemPrompt());
        prefs.setPlannerModel(etPlanner.getText().toString().trim());
        toast("配置已保存");
        CredentialRouteUi.showChooser(this, prefs, this::refreshAccount);
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
            tvAccount.setText("还没登录：做过的事和记住的事只存在这台手机上。");
        }
        long q = Math.max(0, prefs.accountQuota());
        long used = Math.max(0, prefs.accountQuotaUsed());
        tvQuota.setText("剩余额度：" + q + "（已用 " + used + "；1 额度约等于 1000 个字的用量）");
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
            tvDevices.setText("登录后点「刷新额度」查看");
            return;
        }
        if (quotaFetching) return;   // 自动拉取与按钮点击可能重叠，避免重复请求
        quotaFetching = true;
        tvDevices.setText("正在刷新…");
        AccountClient.me(this, new AccountClient.ApiCallback<AccountClient.Me>() {
            @Override
            public void onOk(AccountClient.Me m) {
                quotaFetching = false;
                long quota = Math.max(0, m.quota);
                long quotaUsed = Math.max(0, m.quotaUsed);
                tvQuota.setText("剩余额度：" + quota + "（已用 " + quotaUsed
                        + "；1 额度约等于 1000 个字的用量）");
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
        ElderUi.show(new AlertDialog.Builder(this)
                .setTitle("登录已失效")
                .setMessage("账号令牌已失效（可能在其他设备重新登录过）。\n请重新登录以继续同步。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重新登录", (d, w) ->
                        startActivity(new android.content.Intent(this, LoginActivity.class))));
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
        ElderUi.show(new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("将清除本机保存的账号令牌与设备绑定，任务历史与记忆仍保留在本机。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> {
                    LanSync.stop();          // 关掉局域网监听（密钥随 clearAccount 一并清除）
                    prefs.clearAccount();
                    refreshAccount();
                    refreshSyncStatus();
                    tvDevices.setText("登录后点「刷新额度」查看");
                    toast("已退出登录");
                }));
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
        // 手机适老化：显示时间加长（老人读得慢）
        Toast.makeText(this, s, ElderUi.enabled(this) ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }
}
