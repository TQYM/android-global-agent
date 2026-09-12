// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 手表设置页（WatchSettingsActivity）：
 * 主页（极简：语音+打字）只留核心交互，历史/记忆/省电/授权/模型配置/设备形态全部收进本页。
 * 日志与截图按需求不在手表端提供（adb logcat 可查）。
 */
public class WatchSettingsActivity extends Activity {

    private Prefs prefs;
    private TextView tvDisplay, tvTokens;
    private Button btnA11y;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_watch_settings);
        prefs = new Prefs(this);

        tvDisplay = findViewById(R.id.tvDisplay);
        tvTokens = findViewById(R.id.tvTokens);
        // 保活引导：状态自检 + 一键跳厂商自启动/后台运行设置（零 root 保活的关键配置）
        TextView tvKeep = findViewById(R.id.tvKeepAlive);
        if (tvKeep != null) {
            tvKeep.setText(KeepAliveGuide.summary(this) + "\n" + KeepAliveGuide.advice(this));
        }
        android.view.View btnAuto = findViewById(R.id.btnAutoStart);
        if (btnAuto != null) {
            btnAuto.setOnClickListener(v -> {
                KeepAliveGuide.openAutoStartSettings(this);
                toast(KeepAliveGuide.manualSteps(this));
            });
        }
        android.view.View btnBat = findViewById(R.id.btnBatteryIgnore);
        if (btnBat != null) {
            btnBat.setOnClickListener(v -> KeepAliveGuide.openBatterySettings(this));
        }

        loadCfg();
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(v -> finish());
        ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> saveCfg());
        ((Button) findViewById(R.id.btnResetTokens)).setOnClickListener(v -> {
            prefs.resetTokens();
            refreshTokens();
            toast("Token 计数已清零");
        });

        ((Button) findViewById(R.id.btnHistory)).setOnClickListener(v -> showHistory());
        ((Button) findViewById(R.id.btnMemory)).setOnClickListener(v -> showMemory());
        ((Button) findViewById(R.id.btnEco)).setOnClickListener(v -> showEco());
        ((Button) findViewById(R.id.btnAuth)).setOnClickListener(v -> showAuthMgr());

        btnA11y = findViewById(R.id.btnA11y);
        btnA11y.setOnClickListener(v -> openA11ySettings());
        ((Button) findViewById(R.id.btnBatteryIgnore)).setOnClickListener(v -> requestBatteryWhitelist());

        ((Button) findViewById(R.id.btnDisplayRefresh)).setOnClickListener(v -> refreshDisplay());
        ((Button) findViewById(R.id.btnDisplayCalib)).setOnClickListener(v -> runCalibrate());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDisplay();
        refreshTokens();
        refreshA11yStatus();
    }

    /** 无障碍按钮上直接显示服务状态（区分未开启 / 已启用但未连接）。 */
    private void refreshA11yStatus() {
        if (btnA11y == null) return;
        if (AgentA11yService.get() != null) {
            btnA11y.setText("无障碍服务 · 已开启");
        } else if (A11yLauncher.isEnabledInSettings(this)) {
            btnA11y.setText("无障碍服务 · 未连接（点此重开）");
        } else {
            btnA11y.setText("无障碍服务 · 未开启");
        }
    }

    // ---- 配置 ----

    private void loadCfg() {
        ((EditText) findViewById(R.id.etBase)).setText(prefs.baseUrl());
        ((EditText) findViewById(R.id.etKey)).setText(prefs.apiKey());
        ((EditText) findViewById(R.id.etModel)).setText(prefs.model());
        ((EditText) findViewById(R.id.etPlanner)).setText(prefs.plannerModel());
        ((EditText) findViewById(R.id.etAsr)).setText(prefs.asrModel());
        ((EditText) findViewById(R.id.etMaxSteps)).setText(String.valueOf(prefs.maxSteps()));
        ((Switch) findViewById(R.id.swVision)).setChecked(prefs.vision());
    }

    private void saveCfg() {
        int steps = 50;
        try {
            steps = Integer.parseInt(((EditText) findViewById(R.id.etMaxSteps)).getText().toString().trim());
        } catch (Exception ignored) { }
        prefs.save(((EditText) findViewById(R.id.etBase)).getText().toString().trim(),
                ((EditText) findViewById(R.id.etKey)).getText().toString().trim(),
                ((EditText) findViewById(R.id.etModel)).getText().toString().trim(),
                ((EditText) findViewById(R.id.etAsr)).getText().toString().trim(),
                ((Switch) findViewById(R.id.swVision)).isChecked(), steps, prefs.systemPrompt());
        prefs.setPlannerModel(((EditText) findViewById(R.id.etPlanner)).getText().toString().trim());
        toast("配置已保存");
    }

    private void refreshTokens() {
        if (tvTokens == null) return;
        long tot = prefs.totalTokens();
        tvTokens.setText("Token 总计: " + tot + "（输入 " + prefs.promptTokens()
                + " / 输出 " + prefs.completionTokens() + "）");
    }

    // ---- 历史 / 记忆 / 省电 / 预授权（与手机端同源 Store） ----

    private void showHistory() {
        List<TaskHistory.Record> rs = TaskHistory.list(this);
        StringBuilder sb = new StringBuilder();
        if (rs.isEmpty()) sb.append("暂无任务记录");
        for (TaskHistory.Record r : rs) sb.append(r.line()).append("\n\n");
        new AlertDialog.Builder(this)
                .setTitle("任务记录")
                .setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> TaskHistory.clear(this))
                .show();
    }

    private void showMemory() {
        List<MemoryStore.Item> ms = MemoryStore.list(this);
        if (ms.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("全局记忆")
                    .setMessage("暂无记忆。\n在主页输入「记一下 …」即可写入，之后任务会静默使用。")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        String[] items = new String[ms.size()];
        for (int i = 0; i < ms.size(); i++) items[i] = ms.get(i).text;
        new AlertDialog.Builder(this)
                .setTitle("全局记忆（点按删除）")
                .setItems(items, (d, which) -> {
                    MemoryStore.delete(this, which);
                    toast("已删除");
                })
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> MemoryStore.clear(this))
                .show();
    }

    private void showEco() {
        EcoPolicy eco = new EcoPolicy(this);
        List<EcoPolicy.Entry> es = eco.entries();
        String[] items = new String[es.size()];
        for (int i = 0; i < es.size(); i++) {
            items[i] = es.get(i).name + "　[" + EcoPolicy.tierLabel(es.get(i).tier) + "]";
        }
        new AlertDialog.Builder(this)
                .setTitle("生态清单（点按切换档位）")
                .setItems(items, (d, which) -> {
                    EcoPolicy.Entry e = es.get(which);
                    e.tier = (e.tier + 1) % 3;
                    eco.save(es);
                    toast("「" + e.name + "」→ " + EcoPolicy.tierLabel(e.tier));
                })
                .setPositiveButton("关闭", null)
                .setNeutralButton("恢复默认", (d, w) -> eco.reset())
                .show();
    }

    private void showAuthMgr() {
        AuthStore store = new AuthStore(this);
        List<String> scopes = store.list();
        if (scopes.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("预授权管理")
                    .setMessage("当前无预授权条目。\n所有应用发起操作时会走授权确认。")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        String[] items = new String[scopes.size()];
        for (int i = 0; i < scopes.size(); i++) {
            items[i] = scopes.get(i).replace("app|", "操作应用：") + "　[始终允许]";
        }
        new AlertDialog.Builder(this)
                .setTitle("预授权（点按撤回）")
                .setItems(items, (d, which) -> store.revoke(scopes.get(which)))
                .setPositiveButton("关闭", null)
                .setNeutralButton("全部重置", (d, w) -> store.clearAll())
                .show();
    }

    // ---- 权限与授权 ----

    private void openA11ySettings() {
        A11yLauncher.open(this);
    }

    private void requestBatteryWhitelist() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单中");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("无法打开电池设置");
            }
        }
    }

    // ---- 设备形态 ----

    private void refreshDisplay() {
        if (tvDisplay == null) return;
        try {
            DisplaySpec s = DisplaySpec.refresh(this);
            tvDisplay.setText(s == null ? "采集失败" : s.toString());
        } catch (Throwable t) {
            tvDisplay.setText("采集失败：" + t.getMessage());
        }
    }

    private void runCalibrate() {
        AgentA11yService svc = AgentA11yService.get();
        if (svc == null) {
            toast("请先开启无障碍服务");
            return;
        }
        String r = svc.calibrate();
        new AlertDialog.Builder(this)
                .setTitle("坐标校准")
                .setMessage(r)
                .setPositiveButton("好", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
