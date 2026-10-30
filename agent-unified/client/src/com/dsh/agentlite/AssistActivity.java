// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 系统级长按召唤助手界面（透明悬浮底抽屉）。
 *
 * 接入途径：
 * 1. 系统默认助手 / 长按电源键 / 长按导航条：android.intent.action.ASSIST
 * 2. 语音长按交互：android.intent.action.VOICE_COMMAND
 * 3. 任何外部或后台快速召唤
 */
public class AssistActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private VoiceRecorder recorder;

    private TextView tvHint;
    private EditText etInput;
    private Button btnMic, btnRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ElderUi.applyTranslucentTheme(this);   // 手机：浅色大字的透明抽屉主题
        setContentView(R.layout.activity_assist);

        prefs = new Prefs(this);
        tvHint = findViewById(R.id.tvAssistHint);
        etInput = findViewById(R.id.etAssistInput);
        btnMic = findViewById(R.id.btnAssistMic);
        btnRun = findViewById(R.id.btnAssistRun);

        findViewById(R.id.btnAssistClose).setOnClickListener(v -> finish());
        findViewById(R.id.assistRoot).setOnClickListener(v -> finish());
        findViewById(R.id.assistDialog).setOnClickListener(v -> { /* 阻止冒泡关闭 */ });

        btnRun.setOnClickListener(v -> {
            String task = etInput.getText().toString().trim();
            if (task.isEmpty()) {
                tvHint.setText("请先点「我要说话」说一句，或者打字告诉我要做什么");
                return;
            }
            startTask(task);
        });

        btnMic.setOnClickListener(v -> toggleMic());

        // 召唤时自动启动一次语音录入，给用户极简即说即走体验
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ui.postDelayed(this::startRecording, 200);
        }
    }

    private void toggleMic() {
        if (recorder != null && recorder.isRecording()) {
            stopAndTranscribe();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101);
            return;
        }
        if (recorder == null) {
            recorder = new VoiceRecorder();
            // 30 秒上限到点自动收尾：走与用户点「停止」相同的转写路径
            recorder.setOnAutoStop(() -> runOnUiThread(() -> {
                tvHint.setText("已达 30 秒上限，自动结束录音");
                stopAndTranscribe();
            }));
        }
        if (recorder.start()) {
            tvHint.setText("我在听，请说…说完点下面红色的「说完了」");
            btnMic.setText("说完了");
            btnMic.setBackgroundResource(R.drawable.btn_elder_danger);
            btnMic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_elder_stop, 0, 0, 0);
        } else {
            tvHint.setText("麦克风打不开，请检查是否允许了麦克风权限，也可以直接打字");
        }
    }

    private void stopAndTranscribe() {
        if (recorder == null || !recorder.isRecording()) return;
        byte[] wav = recorder.stopToWav();
        tvHint.setText("正在听懂您说的话，请稍等…");
        btnMic.setText("正在识别…");
        btnMic.setEnabled(false);
        btnMic.setBackgroundResource(R.drawable.btn_elder_primary);
        new Thread(() -> {
            try {
                // 转写走统一入口：本地离线 whisper 优先，其次端点，最后主模型音频
                String text = AsrRouter.transcribe(this, prefs, wav);
                ui.post(() -> {
                    resetMic();
                    if (text == null || text.trim().isEmpty()) {
                        tvHint.setText("没听清楚，请靠近手机再说一次，或者直接打字");
                    } else {
                        tvHint.setText("我听到的是：「" + text + "」");
                        etInput.setText(text);
                        // 识别成功后立即自动执行
                        startTask(text);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    resetMic();
                    // 技术原因只进日志，界面上给能照着做的一句话（守则 T-4）
                    android.util.Log.w("AgentAssist", "识别失败: " + e.getMessage());
                    tvHint.setText("这次没听出来，请再点「我要说话」试一次，或者直接打字");
                });
            }
        }, "assist-asr").start();
    }

    /** 「我要说话」按钮回到待命样子（蓝色、可点）。 */
    private void resetMic() {
        btnMic.setEnabled(true);
        btnMic.setText("我要说话");
        btnMic.setBackgroundResource(R.drawable.btn_elder_primary);
        btnMic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_elder_mic, 0, 0, 0);
    }

    private void startTask(String task) {
        if (recorder != null && recorder.isRecording()) {
            recorder.stopToWav();
        }
        // 走**进程内**通道传任务：Intent extra 会暴露给任意应用（安全审查 P0-1）
        MainActivity.setPendingTask(task);
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (recorder != null && recorder.isRecording()) {
            recorder.stopToWav();
        }
        super.onDestroy();
    }
}
