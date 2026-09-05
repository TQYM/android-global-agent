package com.dsh.agent;

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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private AgentEngine engine;
    private VoiceRecorder recorder;
    private Bitmap lastBmp;

    private TextView tvStatus, tvA11y, tvLog;
    private EditText etTask, etBase, etKey, etModel, etAsr, etMaxSteps;
    private Switch swVision;
    private Button btnRun, btnMic;
    private View llConfig, vTap;
    private ImageView ivScreen;
    private ScrollView svLog;
    private FrameLayout flScreen;

    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        engine = AgentEngine.get(this);
        engine.setListener(this);

        bind();
        loadCfg();
        wire();
        wireRootMode();
        KeepAliveService.start(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
        requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    private void bind() {
        tvStatus = findViewById(R.id.tvStatus);
        tvA11y = findViewById(R.id.tvA11y);
        tvLog = findViewById(R.id.tvLog);
        etTask = findViewById(R.id.etTask);
        etBase = findViewById(R.id.etBase);
        etKey = findViewById(R.id.etKey);
        etModel = findViewById(R.id.etModel);
        etAsr = findViewById(R.id.etAsr);
        etMaxSteps = findViewById(R.id.etMaxSteps);
        swVision = findViewById(R.id.swVision);
        btnRun = findViewById(R.id.btnRun);
        btnMic = findViewById(R.id.btnMic);
        llConfig = findViewById(R.id.llConfig);
        vTap = findViewById(R.id.vTap);
        ivScreen = findViewById(R.id.ivScreen);
        svLog = findViewById(R.id.svLog);
        flScreen = findViewById(R.id.flScreen);
    }

    private void loadCfg() {
        etBase.setText(prefs.baseUrl());
        etKey.setText(prefs.apiKey());
        etModel.setText(prefs.model());
        etAsr.setText(prefs.asrModel());
        etMaxSteps.setText(String.valueOf(prefs.maxSteps()));
        swVision.setChecked(prefs.vision());
    }

    /** Root 模式选择：自动/开/关，切换后立即重探测。 */
    private void wireRootMode() {
        View.OnClickListener l = v -> {
            String m = v.getId() == R.id.btnRootOn ? "on"
                    : v.getId() == R.id.btnRootOff ? "off" : "auto";
            prefs.setRootMode(m);
            RootShell.reset();
            paintRootMode();
            onLog("Root 模式 → " + ("on".equals(m) ? "强制启用" : "off".equals(m) ? "关闭（纯零root）" : "自动"));
        };
        findViewById(R.id.btnRootAuto).setOnClickListener(l);
        findViewById(R.id.btnRootOn).setOnClickListener(l);
        findViewById(R.id.btnRootOff).setOnClickListener(l);
        paintRootMode();
    }

    private void paintRootMode() {
        String m = prefs.rootMode();
        int on = 0xFF58A6FF, off = 0xFF30363D;
        findViewById(R.id.btnRootAuto).getBackground().setTint("auto".equals(m) ? on : off);
        findViewById(R.id.btnRootOn).getBackground().setTint("on".equals(m) ? on : off);
        findViewById(R.id.btnRootOff).getBackground().setTint("off".equals(m) ? on : off);
        TextView tv = findViewById(R.id.tvRootState);
        if ("off".equals(m)) {
            tv.setText("已停用");
            tv.setTextColor(0xFF8B93A3);
        } else {
            boolean ok = RootShell.available(this);
            tv.setText(ok ? "su 可用 ✓" : "未检测到 su");
            tv.setTextColor(ok ? 0xFF3FB950 : 0xFFF85149);
        }
    }

    private void wire() {
        findViewById(R.id.btnConfig).setOnClickListener(v -> {
            llConfig.setVisibility(llConfig.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            int steps = 20;
            try { steps = Integer.parseInt(etMaxSteps.getText().toString().trim()); } catch (Exception ignored) { }
            prefs.save(etBase.getText().toString().trim(), etKey.getText().toString().trim(),
                    etModel.getText().toString().trim(), etAsr.getText().toString().trim(),
                    swVision.isChecked(), steps, prefs.systemPrompt());
            onLog("✓ 配置已保存");
        });

        findViewById(R.id.btnA11y).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnRun.setOnClickListener(v -> {
            if (engine.isRunning()) {
                engine.stop();
                return;
            }
            String task = etTask.getText().toString().trim();
            if (task.isEmpty()) { toast("请输入任务"); return; }
            if (AgentA11yService.get() == null) {
                toast("请先开启无障碍服务");
                llConfig.setVisibility(View.VISIBLE);
                return;
            }
            engine.start(task);
        });

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
            onLog(bmp != null ? "截图 ✓ " + bmp.getWidth() + "x" + bmp.getHeight() : "截图 ✗");
            if (bmp != null) ui.post(() -> { lastBmp = bmp; ivScreen.setImageBitmap(bmp); });
        });
        dbg(R.id.btnDbgTap, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            onLog("点中心 " + (s.tap(w / 2, h / 2) ? "✓" : "✗"));
        });
        dbg(R.id.btnDbgBack, () -> { AgentA11yService s = svc(); if (s != null) onLog("返回 " + (s.goBack() ? "✓" : "✗")); });
        dbg(R.id.btnDbgEdge, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            android.graphics.Rect wb = getSystemService(android.view.WindowManager.class)
                    .getCurrentWindowMetrics().getBounds();
            int y = (int) (wb.height() * 0.45);
            onLog("手势返回(左缘内滑) " + (s.swipe(2, y, (int) (wb.width() * 0.35), y, 300) ? "✓" : "✗"));
        });
        dbg(R.id.btnDbgHome, () -> { AgentA11yService s = svc(); if (s != null) onLog("主页 " + (s.goHome() ? "✓" : "✗")); });
        dbg(R.id.btnDbgRecents, () -> { AgentA11yService s = svc(); if (s != null) onLog("最近任务 " + (s.goRecents() ? "✓" : "✗")); });
        dbg(R.id.btnDbgNotif, () -> { AgentA11yService s = svc(); if (s != null) onLog("通知栏 " + (s.notifications() ? "✓" : "✗")); });
        dbg(R.id.btnDbgWifi, () -> {
            startActivity(new Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            onLog("WiFi 面板 ✓");
        });
        dbg(R.id.btnDbgIme, () -> {
            String def = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            boolean merged = def != null && def.startsWith("dev.patrickgold.florisboard/");
            onLog(merged ? "缝合键盘(FlorisBoard+注入桥)已是默认 ✓" : "默认输入法: " + def + "，弹出切换器…");
            if (!merged) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                ui.post(imm::showInputMethodPicker);
            }
        });
        dbg(R.id.btnDbgText, () -> {
            AgentA11yService s = svc();
            if (s == null) return;
            String err = s.setText("测试中文输入✓", false);
            onLog(err == null ? "输中文 ✓（焦点编辑框已写入「测试中文输入✓」）" : "输中文 ✗：" + err);
        });
        dbg(R.id.btnDbgVol, () -> {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
            onLog("音量+ ✓");
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

    private void toggleMic() {
        if (recorder != null && recorder.isRecording()) {
            byte[] wav = recorder.stopToWav();
            btnMic.setText("🎤");
            onLog("识别中…");
            new Thread(() -> {
                try {
                    LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
                    String text = c.transcribe(wav, prefs.asrModel());
                    if (text.isEmpty()) { onLog("识别结果为空"); return; }
                    onLog("🎤 " + text);
                    ui.post(() -> {
                        etTask.setText(text);
                        btnRun.performClick();
                    });
                } catch (Exception e) {
                    onLog("✗ 语音识别失败：" + e.getMessage());
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
        ui.post(() -> {
            tvStatus.setText(running ? "运行中 · 第 " + step + " 步" : "空闲");
            tvStatus.setTextColor(running ? 0xFF3FB950 : 0xFF8B93A3);
            btnRun.setText(running ? "停止" : "运行");
        });
    }

    @Override
    public void onScreen(Bitmap bmp) {
        ui.post(() -> { lastBmp = bmp; ivScreen.setImageBitmap(bmp); });
    }

    @Override
    public void onTap(int x, int y) {
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
    protected void onResume() {
        super.onResume();
        refreshA11y();
    }

    private void refreshA11y() {
        boolean on = AgentA11yService.get() != null;
        tvA11y.setText(on ? "无障碍 ✓" : "无障碍 ✗（点「设置」开启）");
        tvA11y.setTextColor(on ? 0xFF3FB950 : 0xFFF85149);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
