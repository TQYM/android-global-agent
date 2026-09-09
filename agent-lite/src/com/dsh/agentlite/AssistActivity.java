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
import android.widget.Toast;

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
                Toast.makeText(this, "请输入或说出指令", Toast.LENGTH_SHORT).show();
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
        if (recorder == null) recorder = new VoiceRecorder();
        if (recorder.start()) {
            tvHint.setText("正在倾听中…说完请点停止或执行");
            btnMic.setText("停止倾听");
        } else {
            tvHint.setText("录音启动失败，请检查麦克风权限");
        }
    }

    private void stopAndTranscribe() {
        if (recorder == null || !recorder.isRecording()) return;
        byte[] wav = recorder.stopToWav();
        tvHint.setText("正在识别语音内容…");
        btnMic.setText("识别中…");
        new Thread(() -> {
            try {
                LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
                String text = c.transcribe(wav, prefs.asrModel());
                ui.post(() -> {
                    btnMic.setText("按住/点按说话");
                    if (text == null || text.trim().isEmpty()) {
                        tvHint.setText("未识别到清晰语音，请重新录制或打字输入");
                    } else {
                        tvHint.setText("已识别：" + text);
                        etInput.setText(text);
                        // 识别成功后立即自动执行
                        startTask(text);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    btnMic.setText("按住/点按说话");
                    tvHint.setText("识别失败: " + e.getMessage());
                });
            }
        }, "assist-asr").start();
    }

    private void startTask(String task) {
        if (recorder != null && recorder.isRecording()) {
            recorder.stopToWav();
        }
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra("task", task);
        it.putExtra("auto_run", true);
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