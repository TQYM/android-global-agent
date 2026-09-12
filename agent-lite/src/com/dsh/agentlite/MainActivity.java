// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 原生主界面：任务输入 / 语音 / 日志 / 屏幕回显 / 动作调试台。 */
public class MainActivity extends Activity implements AgentEngine.Listener {
    private static MainActivity sInstance;
    static android.app.Application appStatic() { return sInstance.getApplication(); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private AgentEngine engine;
    private boolean watchUi;   // 手表形态（圆形表盘 / 方形小屏）→ activity_main_watch 布局

    /**
     * 手表形态判定（决定用哪套布局）：
     *  1) UI_MODE_TYPE_WATCH（标准 Wear 声明）；
     *  2) isScreenRound()（圆形表盘，手机无此形态）；
     *  3) 屏幕最小边 < 320dp 兜底（OWW261 466px@320dpi = 233dp；手机最小边 ≥ 320dp）。
     * 任一命中按手表 UI 渲染；判定异常回退手机布局（不崩）。
     */
    private boolean isWatchUi() {
        try {
            android.content.res.Configuration c = getResources().getConfiguration();
            if ((c.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                    == android.content.res.Configuration.UI_MODE_TYPE_WATCH) return true;
            if (c.isScreenRound()) return true;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (dm.density > 0) {
                int minDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
                return minDp > 0 && minDp < 320;
            }
        } catch (Throwable t) { }
        return false;
    }

    private VoiceRecorder recorder;
    private Bitmap lastBmp;

    private TextView tvStatus, tvA11y, tvLog, tvTokens;
    private EditText etTask, etBase, etKey, etModel, etAsr, etMaxSteps, etPlanner;
    private Switch swVision;
    private Button btnRun, btnMic, btnResetTokens;
    private TextView tvDisplay;
    private Button btnDisplayRefresh, btnDisplayCalib;
    private View llConfig, vTap;
    private ImageView ivScreen;
    private ScrollView svLog;
    private FrameLayout flScreen;

    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;

        // 全局沉浸式 Edge-to-Edge：彻底消除状态栏与底部手势导航的黑块闪烁
        android.view.Window window = getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        watchUi = isWatchUi();
        setContentView(watchUi ? R.layout.activity_main_watch : R.layout.activity_main);

        // 启动即与同步服务对账一次：首次自动注册，之后增量上行 + 配置下发。
        // 内部走单线程 executor，不阻塞首帧；服务器不可达时静默失败、下次启动重试。
        SyncClient.syncNow(this);
        View root = find(R.id.rootLayout);
        if (root != null) {
            final boolean isWatch = watchUi;
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (isWatch) {
                    // 手表：水平安全边距由布局（@dimen/watch_edge，圆形/方形分档）控制，
                    // 此处只补系统栏，避免覆盖圆形屏的内接方形边距
                    v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
                } else {
                    v.setPadding(dp(16), top + dp(4), dp(16), bottom + dp(8));
                }
                return insets.consumeSystemWindowInsets();
            });
        }

        prefs = new Prefs(this);
        engine = AgentEngine.get(this);
        engine.setListener(this);

        bind();
        loadCfg();
        wire();
        handleIntent(getIntent());
        KeepAliveService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
        requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}, 1);

        // 功能启用级弹窗（报告 §5.1.1：首次打开时告知操作与权限使用场景）
        if (!prefs.sp.getBoolean("intro_shown", false)) {
            prefs.sp.edit().putBoolean("intro_shown", true).apply();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Agent 操作手机说明")
                    .setMessage("本助手通过无障碍服务读取屏幕并模拟点击来替你操作手机：\n"
                            + "· 任务执行中屏幕边缘会出现粉色呼吸光晕，顶部胶囊显示进展\n"
                            + "· 支付、协议、验证码等敏感环节会强制暂停，由你手动完成\n"
                            + "· 点顶部胶囊可随时接管、补充指令或停止\n"
                            + "· 任务结束后可在「历史 / 授权 / 生态 / 记忆」中回查与撤回")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private void bind() {
        // 手机/手表共有的核心控件：用 find（缺控件时占位不崩）
        tvStatus = find(R.id.tvStatus);
        tvA11y = find(R.id.tvA11y);
        etTask = find(R.id.etTask);
        btnRun = find(R.id.btnRun);
        btnMic = find(R.id.btnMic);
        // 仅手机布局存在的控件：直接 findViewById（手表极简主页无这些控件，保持 null 并由使用点判空）
        tvLog = findViewById(R.id.tvLog);
        etBase = findViewById(R.id.etBase);
        etKey = findViewById(R.id.etKey);
        etModel = findViewById(R.id.etModel);
        etAsr = findViewById(R.id.etAsr);
        etPlanner = findViewById(R.id.etPlanner);
        etMaxSteps = findViewById(R.id.etMaxSteps);
        swVision = findViewById(R.id.swVision);
        tvTokens = findViewById(R.id.tvTokens);
        btnResetTokens = findViewById(R.id.btnResetTokens);
        tvDisplay = findViewById(R.id.tvDisplay);
        btnDisplayRefresh = findViewById(R.id.btnDisplayRefresh);
        btnDisplayCalib = findViewById(R.id.btnDisplayCalib);
        llConfig = findViewById(R.id.llConfig);
        vTap = findViewById(R.id.vTap);
        ivScreen = findViewById(R.id.ivScreen);
        svLog = findViewById(R.id.svLog);
        flScreen = findViewById(R.id.flScreen);
    }

    /** 空安全的 findViewById：布局形态差异（手机/手表/精简布局）缺控件时返回不可见占位，绝不 NPE。 */
    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        View v = findViewById(id);
        if (v == null) v = new View(this);
        return (T) v;
    }

    private void loadCfg() {
        if (watchUi) return;   // 手表：模型配置在 WatchSettingsActivity，主页无配置控件
        etBase.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        etModel.setText(prefs.model());
        etAsr.setText(prefs.asrModel());
        etPlanner.setText(prefs.plannerModel());
        etMaxSteps.setText(String.valueOf(prefs.maxSteps()));
        swVision.setChecked(prefs.vision());
        refreshTokens();
    }

    /** 保活自检卡：显示无障碍/电池/通知/守护进程状态与建议。 */
    private void refreshKeepAlive() {
        TextView tv = find(R.id.tvKeepAlive);
        if (tv == null) return;
        tv.setText("保活自检：" + KeepAliveGuide.summary(this) + "\n" + KeepAliveGuide.advice(this)
                + "\n" + KeepAliveGuide.manualSteps(this));
    }

    private void refreshTokens() {
        if (tvTokens == null) return;
        long tot = prefs.totalTokens();
        long p = prefs.promptTokens();
        long c = prefs.completionTokens();
        tvTokens.setText("总计: " + tot + " (" + formatK(tot) + ") | 输入: " + p + " | 输出: " + c);
    }

    private static String formatK(long n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1000.0);
        return String.valueOf(n);
    }

    private void wire() {
        // ---- 手表极简主页：只连 设置入口 / 任务输入 / 语音 / 运行 / 无障碍引导 ----
        // 历史、记忆、省电、授权、模型配置、设备形态均在 WatchSettingsActivity；
        // 日志与截图手表端不提供。样式沿用原设计语言（顶栏/卡片/按钮不变）。
        if (watchUi) {
            find(R.id.btnConfig).setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, WatchSettingsActivity.class)));
            btnRun.setOnClickListener(v -> onRunClicked());
            btnMic.setOnClickListener(v -> toggleMic());
            find(R.id.btnA11yBanner).setOnClickListener(v -> openA11ySettingsDirectly());
            find(R.id.btnA11y).setOnClickListener(v -> openA11ySettingsDirectly());
            tvA11y.setOnClickListener(v -> openA11ySettingsDirectly());
            return;
        }

        find(R.id.btnConfig).setOnClickListener(v -> {
            boolean show = llConfig.getVisibility() == View.GONE;
            llConfig.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) refreshTokens();
        });

        if (btnResetTokens != null) {
            btnResetTokens.setOnClickListener(v -> {
                prefs.resetTokens();
                refreshTokens();
                toast("Token 消耗计数已归零");
            });
        }

        if (btnDisplayRefresh != null) {
            btnDisplayRefresh.setOnClickListener(v -> refreshDisplay());
        }
        if (btnDisplayCalib != null) {
            btnDisplayCalib.setOnClickListener(v -> runCalibrate());
        }

        find(R.id.btnSave).setOnClickListener(v -> {
            int steps = 50;
            try { steps = Integer.parseInt(etMaxSteps.getText().toString().trim()); } catch (Exception ignored) { }
            prefs.save(etBase.getText().toString().trim(), etKey.getText().toString().trim(),
                    etModel.getText().toString().trim(), etAsr.getText().toString().trim(),
                    swVision.isChecked(), steps, prefs.systemPrompt());
            prefs.setPlannerModel(etPlanner.getText().toString().trim());
            onLog("配置已保存");
        });

        find(R.id.btnA11y).setOnClickListener(v -> openA11ySettingsDirectly());
        find(R.id.btnA11yBanner).setOnClickListener(v -> openA11ySettingsDirectly());

        // 锁屏保活与电池白名单配置指引
        View btnBatteryIgnore = find(R.id.btnBatteryIgnore);
        if (btnBatteryIgnore != null) {
            btnBatteryIgnore.setOnClickListener(v -> KeepAliveGuide.openBatterySettings(this));
        }
        // 保活引导：一键跳厂商「自启动/后台运行」管理页（零 root 保活的关键系统配置）
        find(R.id.btnAutoStart).setOnClickListener(v -> KeepAliveGuide.openAutoStartSettings(this));

        // 管理入口（豆包规格事后层：可回查、可撤回、可清除）
        find(R.id.btnHistory).setOnClickListener(v -> showHistory());
        find(R.id.btnAuth).setOnClickListener(v -> showAuthMgr());
        find(R.id.btnEco).setOnClickListener(v -> showEco());
        find(R.id.btnMemory).setOnClickListener(v -> showMemory());

        // 调试台折叠开关（默认收起，保持主界面干净）
        final View dbgScroll = find(R.id.dbgScroll);
        find(R.id.btnDebug).setOnClickListener(v -> {
            boolean show = dbgScroll.getVisibility() != View.VISIBLE;
            dbgScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            ((android.widget.Button) v).setText(show ? "收起调试" : "调试台");
        });

        btnRun.setOnClickListener(v -> onRunClicked());

        btnMic.setOnClickListener(v -> toggleMic());

        // ---- 调试台 ----
        dbg(R.id.btnDbgNodes, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            List<NodeInfo> nodes = s.collectNodes();
            onLog("感知：" + nodes.size() + " 个节点");
            for (int i = 0; i < Math.min(nodes.size(), 12); i++) {
                NodeInfo n = nodes.get(i);
                onLog("  [" + n.index + "] " + n.label() + " @" + n.cx + "," + n.cy);
            }
        });
        dbg(R.id.btnDbgShot, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            Bitmap bmp = s.screenshot();
            onLog(bmp != null ? "截图成功 " + bmp.getWidth() + "x" + bmp.getHeight() : "截图失败");
            if (bmp != null) ui.post(() -> { lastBmp = bmp; ivScreen.setImageBitmap(bmp); });
        });
        dbg(R.id.btnDbgTap, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            onLog("点中心 " + (s.tap(w / 2, h / 2) ? "[成功]" : "[失败]"));
        });
        dbg(R.id.btnDbgBack, () -> { AgentA11yService s = svc(); if (s != null) onLog("返回 " + (s.goBack() ? "[成功]" : "[失败]")); });
        dbg(R.id.btnDbgEdge, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            android.graphics.Rect wb = getSystemService(android.view.WindowManager.class)
                    .getCurrentWindowMetrics().getBounds();
            int y = (int) (wb.height() * 0.45);
            onLog("手势返回(左缘内滑) " + (s.swipe(2, y, (int) (wb.width() * 0.35), y, 300) ? "[成功]" : "[失败]"));
        });
        dbg(R.id.btnDbgHome, () -> { AgentA11yService s = svc(); if (s != null) onLog("主页 " + (s.goHome() ? "[成功]" : "[失败]")); });
        dbg(R.id.btnDbgRecents, () -> { AgentA11yService s = svc(); if (s != null) onLog("最近任务 " + (s.goRecents() ? "[成功]" : "[失败]")); });
        dbg(R.id.btnDbgNotif, () -> { AgentA11yService s = svc(); if (s != null) onLog("通知栏 " + (s.notifications() ? "[成功]" : "[失败]")); });
        dbg(R.id.btnDbgWifi, () -> {
            startActivity(new Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            onLog("WiFi 面板已开启");
        });
        dbg(R.id.btnDbgIme, () -> {
            String def = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            boolean merged = def != null && def.startsWith("dev.patrickgold.florisboard/");
            onLog(merged ? "缝合键盘(FlorisBoard+注入桥)已是默认" : "默认输入法: " + def + "，弹出切换器…");
            if (!merged) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                ui.post(imm::showInputMethodPicker);
            }
        });
        dbg(R.id.btnDbgText, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            String err = s.setText("测试中文输入", false);
            onLog(err == null ? "输中文成功（焦点编辑框已写入「测试中文输入」）" : "输中文失败：" + err);
        });
        dbg(R.id.btnDbgVol, () -> {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
            onLog("音量调整已触发");
        });
    }

    private AgentA11yService svc() {
        AgentA11yService s = AgentA11yService.get();
        if (s == null) onLog("✗ 无障碍服务未开启");
        return s;
    }

    private interface DbgAction { void run(); }
    private void dbg(int btnId, DbgAction action) {
        findViewById(btnId).setOnClickListener(v ->
                new Thread(() -> { try { action.run(); } catch (Throwable t) { onLog("✗ " + t); } }, "dbg").start());
    }

    // ---- 语音 ----

    /** 运行/停止（手机与手表极简主页共用）。 */
    private void onRunClicked() {
        if (engine.isRunning()) {
            engine.stop();
            return;
        }
        String task = etTask.getText().toString().trim();
        if (task.isEmpty()) { toast("请输入任务"); return; }
        // 记忆写入显式通道（报告 §7.1）：「记一下…」直接写入全局记忆，不进入执行循环
        if (task.startsWith("记一下")) {
            String mem = task.substring(3).trim();
            if (mem.isEmpty()) { toast("「记一下」后面跟上要记住的内容"); return; }
            MemoryStore.add(this, mem);
            onLog("已写入记忆：" + mem + "（之后任务会静默使用）");
            etTask.setText("");
            return;
        }
        if (AgentA11yService.get() == null) {
            // 服务可能刚被系统重绑（例：刚从无障碍设置页返回）——短暂等待重试，
            // 避免带着空感知启动任务（10 轮检查实测：会让模型看到「未开启」提示而误判失败）
            toast("无障碍服务正在连接…");
            waitA11yThenRun(task, 0);
            return;
        }
        startTask(task);
    }

    /**
     * 统一任务启动出口。
     * 1) 刷新无障碍状态（清掉可能残留的「未开启」横幅——否则横幅进入引擎首帧截图会误导模型）；
     * 2) 网络预检 + WiFi 高性能锁（手表长时间运行会被省电策略断网，实测出现
     *    「Unable to resolve host dashscope.aliyuncs.com」导致任务失败）；
     * 3) 启动引擎；手表上随即把本界面退到后台，避免自身 UI 遮挡首帧。
     */
    private void startTask(String task) {
        refreshA11y();
        if (!networkReady()) {
            toast("网络不可用：请检查手表 WiFi（省电模式下会被关闭）");
            return;
        }
        acquireWifiLock();
        engine.start(task);
        toast("任务已启动，可上滑划出，灵动岛将实时显示进度");
        if (watchUi) {
            ui.postDelayed(() -> moveTaskToBack(true), 800);
        }
    }

    /** 是否有可用网络（INTERNET 能力）。 */
    private boolean networkReady() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            return cap != null && cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;   // 检测异常时不阻塞任务
        }
    }

    // ---- 任务期间 WiFi 高性能锁（防省电策略断网） ----
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    private void acquireWifiLock() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "agentlite:task");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) { }
    }

    /**
     * 等待无障碍服务就绪后再启动任务（最多 6 秒）。
     * 系统在无障碍设置页往返 / 进程回收后会短暂解绑再重绑服务，立即启动会拿到空感知。
     */
    private void waitA11yThenRun(String task, int tries) {
        if (AgentA11yService.get() != null) {
            startTask(task);
            return;
        }
        if (tries >= 6) {
            toast("无障碍服务未连接：请在设置中重新开启");
            refreshA11y();
            return;
        }
        ui.postDelayed(() -> waitA11yThenRun(task, tries + 1), 1000);
    }

    private void toggleMic() {
        if (recorder != null && recorder.isRecording()) {
            byte[] wav = recorder.stopToWav();
            btnMic.setText("语音");
            onLog("识别中…");
            new Thread(() -> {
                try {
                    LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
                    String text = c.transcribe(wav, prefs.asrModel());
                    if (text.isEmpty()) { onLog("识别结果为空"); return; }
                    onLog("[语音输入] " + text);
                    ui.post(() -> {
                        etTask.setText(text);
                        btnRun.performClick();
                    });
                } catch (Exception e) {
                    onLog("语音识别失败：" + e.getMessage());
                }
            }, "asr").start();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        recorder = new VoiceRecorder();
        if (recorder.start()) {
            btnMic.setText("■");
            onLog("录音中…再点一次结束");
        } else {
            onLog("✗ 无法启动录音");
        }
    }

    // ---- AgentEngine.Listener（引擎线程回调 → UI 线程） ----

    @Override
    public void onLog(String line) {
        android.util.Log.i("AgentUI", line);
        if (watchUi) return;   // 手表端不显示日志区（adb logcat / engine.log 可查）
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(() -> {
            logBuf.append('[').append(ts).append("] ").append(line).append('\n');
            if (logBuf.length() > 12000) logBuf.delete(0, logBuf.length() - 9000);
            tvLog.setText(logBuf);
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onStatus(boolean running, int step) {
        if (!running) releaseWifiLock();   // 任务结束：释放 WiFi 高性能锁
        ui.post(() -> {
            tvStatus.setText(running ? "运行中 · 第 " + step + " 步" : "就绪");
            if (watchUi) return;   // 手表极简主页：只更新状态文字
            tvStatus.setTextColor(running ? 0xFF34D399 : 0xFF94A3B8);
            tvStatus.setBackgroundResource(running ? R.drawable.pill_green : R.drawable.pill_gray);
            btnRun.setText(running ? "停止" : "运行");
        });
    }

    @Override
    public void onScreen(Bitmap bmp) {
        if (watchUi) return;   // 手表端不显示屏幕预览（省内存/流量）
        ui.post(() -> { lastBmp = bmp; ivScreen.setImageBitmap(bmp); });
    }

    @Override
    public void onTap(int x, int y) {
        if (watchUi) return;   // 手表端无预览画布，跳过点击标记
        ui.post(() -> {
            if (lastBmp == null) return;
            flScreen.post(() -> {
                int vw = flScreen.getWidth(), vh = flScreen.getHeight();
                float scale = Math.min((float) vw / lastBmp.getWidth(), (float) vh / lastBmp.getHeight());
                float dw = lastBmp.getWidth() * scale, dh = lastBmp.getHeight() * scale;
                float offX = (vw - dw) / 2f, offY = (vh - dh) / 2f;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) vTap.getLayoutParams();
                lp.leftMargin = Math.round(offX + x * scale - lp.width / 2f);
                lp.topMargin = Math.round(offY + y * scale - lp.height / 2f);
                vTap.setLayoutParams(lp);
                vTap.setVisibility(View.VISIBLE);
                vTap.postDelayed(() -> vTap.setVisibility(View.GONE), 2500);
            });
        });
    }

    @Override
    public void onAsk(String question) {
        pendingAsk = question;
        fireAskNotification(question);   // 全屏通知：其他 App 界面也会弹
        ui.post(this::showAskDialogIfNeeded);
    }

    /** 模型提问的待回答状态。 */
    private String pendingAsk;
    private boolean askDialogShowing;

    private void showAskDialogIfNeeded() {
        if (pendingAsk == null || askDialogShowing) return;
        askDialogShowing = true;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入回答…");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Agent 提问")
                .setMessage(pendingAsk)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("回答", (d, w) -> {
                    engine.answerAsk(input.getText().toString().trim());
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                })
                .setNegativeButton("跳过", (d, w) -> {
                    engine.answerAsk("");
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                })
                .show();
    }

    private static final int ASK_NOTIF_ID = 9021;

    private void fireAskNotification(String question) {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm == null) return;
        String ch = "agent_ask";
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel(ch, "Agent 提问",
                    android.app.NotificationManager.IMPORTANCE_HIGH));
        }
        android.content.Intent it = new android.content.Intent(this, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new android.app.Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Agent 需要你的回答")
                .setContentText(question)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)   // 来电级弹出（零 root）
                .setAutoCancel(true)
                .build();
        nm.notify(ASK_NOTIF_ID, n);
    }

    private void cancelAskNotification() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(ASK_NOTIF_ID);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前台自检：回前台时纠正"设置里已开启但服务未连接"的静默掉线（李跳跳式保活的一环）
        A11yGuard.check(this, "前台自检");
        refreshKeepAlive();
        refreshA11y();
        refreshTokens();
        refreshDisplay();
    }

    /** 设备形态卡：读取 DisplaySpec（含挖孔/曲面/系统栏/安全触控区），供真机核对。 */
    private String lastCalib = "";
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

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        ui.post(this::showAskDialogIfNeeded);
    }

    private void handleIntent(android.content.Intent it) {
        if (it == null) return;
        // 复用已在前台的实例（singleTask）时不会走 onResume：这里补一次状态刷新，
        // 否则从系统无障碍页返回 / adb 唤起的场景会显示过期的服务状态（横幅滞留）。
        refreshA11y();
        if (it.getBooleanExtra("a11y_repair", false)) {
            // 来自掉线通知：先尝试自动修复；仍不健康则直达无障碍设置页由用户一键恢复
            if (!A11yGuard.check(this, "通知修复")) {
                ui.postDelayed(() -> A11yLauncher.open(this), 600);
            }
        }
        String task = it.getStringExtra("task");
        if (task != null && !task.trim().isEmpty()) {
            etTask.setText(task.trim());
            if (it.getBooleanExtra("auto_run", false)) {
                ui.postDelayed(() -> {
                    if (btnRun != null && !engine.isRunning()) btnRun.performClick();
                }, 300);
            }
        }
    }

    private void refreshA11y() {
        boolean a11yOn = AgentA11yService.get() != null;
        // root 若可用则静默加速 + 自动补开无障碍（Lite 无 root 模式 UI，默认 auto）
        boolean root = RootShell.available(this);
        if (root && !a11yOn) {
            RootShell.ensureA11y(this);
            a11yOn = AgentA11yService.get() != null;
        }
        // 状态文案统一由 A11yLauncher 判定：区分「未开启」与「已启用但未连接（被强杀/回收）」
        String hint = a11yOn ? null : A11yLauncher.statusHint(this);
        if (watchUi) {
            // 手表极简主页：tvA11y 是提示行（点击直达系统设置），正常时隐藏
            tvA11y.setText(hint == null ? "" : hint);
            tvA11y.setVisibility(hint == null ? View.GONE : View.VISIBLE);
            return;
        }
        tvA11y.setText(a11yOn
                ? (root ? "无障碍已开启 (Root)" : "无障碍已开启")
                : hint);
        tvA11y.setTextColor(a11yOn ? 0xFF3FB950 : 0xFFF85149);
        tvA11y.setBackgroundResource(a11yOn ? R.drawable.pill_green : R.drawable.pill_red);
        // 引导横幅：无障碍未开时置顶显示一键开启
        View banner = find(R.id.a11yBanner);
        if (banner != null) banner.setVisibility(a11yOn ? View.GONE : View.VISIBLE);
    }

    // ---- 事后管理（报告 §5.1.3：可回查 / 可撤回 / 可清除） ----

    /** 任务记录归档：完成/失败/终止三类终态统一可回查。 */
    private void showHistory() {
        List<TaskHistory.Record> rs = TaskHistory.list(this);
        StringBuilder b = new StringBuilder();
        if (rs.isEmpty()) b.append("暂无任务记录");
        for (TaskHistory.Record r : rs) b.append(r.line()).append("\n\n");
        new android.app.AlertDialog.Builder(this)
                .setTitle("任务记录")
                .setMessage(b.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> {
                    TaskHistory.clear(this);
                    onLog("任务记录已清空");
                })
                .show();
    }

    /** 预授权管理：点按逐条撤回，可全部重置；撤回后再次发起会重走授权弹窗。 */
    private void showAuthMgr() {
        AuthStore store = new AuthStore(this);
        List<String> scopes = store.list();
        if (scopes.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("预授权管理")
                .setMessage("当前所有应用均已开放直接打开与操作。\n不再弹出授权阻断框。")
                .setPositiveButton("关闭", null)
                .show();
            return;
        }
        String[] items = new String[scopes.size()];
        for (int i = 0; i < scopes.size(); i++) {
            items[i] = scopes.get(i).replace("app|", "操作应用：") + "　[始终允许]";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("预授权管理（点按撤回）")
                .setItems(items, (d, which) -> {
                    store.revoke(scopes.get(which));
                    onLog("已撤回授权：" + scopes.get(which));
                })
                .setNeutralButton("全部重置", (d, w) -> {
                    store.clearAll();
                    onLog("预授权已全部重置");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 生态清单：三档梯度（可全自动/可登录不可AI操作/受限·纯视觉尝试），点按循环切换。 */
    private void showEco() {
        EcoPolicy eco = new EcoPolicy(this);
        List<EcoPolicy.Entry> es = eco.entries();
        String[] items = new String[es.size()];
        for (int i = 0; i < es.size(); i++) {
            EcoPolicy.Entry e = es.get(i);
            items[i] = e.name + "　[" + EcoPolicy.tierLabel(e.tier) + "]"
                    + (e.altName != null ? "　→ 替代：" + e.altName : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("生态清单（点按切换档位）")
                .setItems(items, (d, which) -> {
                    EcoPolicy.Entry e = es.get(which);
                    e.tier = (e.tier + 1) % 3;
                    eco.save(es);
                    onLog("「" + e.name + "」已变更为 " + EcoPolicy.tierLabel(e.tier));
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    eco.reset();
                    onLog("生态清单已恢复默认");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 记忆管理：点按删除单条，可清空；写入用「记一下…」，调用静默无披露。 */
    private void showMemory() {
        List<MemoryStore.Item> ms = MemoryStore.list(this);
        if (ms.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("全局记忆")
                    .setMessage("暂无记忆。\n在任务框输入「记一下 …」即可写入；记忆会在后续任务中被静默使用。")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        String[] items = new String[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            items[i] = ms.get(i).time + "  " + ms.get(i).text;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("全局记忆（点按删除）")
                .setItems(items, (d, which) -> {
                    MemoryStore.delete(this, which);
                    onLog("已删除记忆：" + ms.get(which).text);
                })
                .setNeutralButton("清空", (d, w) -> {
                    MemoryStore.clear(this);
                    onLog("记忆已清空");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 智能自动定位无障碍详情页（各 ROM 入口差异 → 统一走 A11yLauncher 的可靠降级链）。
     */
    private void openA11ySettingsDirectly() {
        A11yLauncher.open(this);
    }

    /** 申请忽略电池优化白名单（锁屏保活核心） */
    private void requestIgnoreBatteryOptimizations() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单中，锁屏保活已激活");
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ex) {
                toast("请在系统设置 -> 电池优化中，将 Agent Lite 设为「不优化/允许后台活动」");
            }
        }
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
