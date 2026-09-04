package com.dsh.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/**
 * Agent 键盘：无实体键盘的输入法服务，接收引擎广播直接 commitText。
 * 专治「应用屏蔽无障碍节点树导致 ACTION_SET_TEXT 不可用」的场景（微信等），
 * 支持任意 Unicode/中文。用户在系统设置启用并切换一次即可。
 */
public class AgentImeService extends InputMethodService {

    public static final String ACTION_COMMIT = "com.dsh.agent.IME_COMMIT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_REPLACE = "replace";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_COMMIT.equals(intent.getAction())) return;
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text == null) text = "";
            if (intent.getBooleanExtra(EXTRA_REPLACE, true)) {
                ic.performContextMenuAction(android.R.id.selectAll);
            }
            ic.commitText(text, 1);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(ACTION_COMMIT);
        registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /** 极简输入视图：一条状态提示，不占屏幕。 */
    @Override
    public View onCreateInputView() {
        TextView tv = new TextView(this);
        tv.setText("Agent 键盘 · 由 Agent 客户端驱动");
        tv.setTextSize(11f);
        tv.setPadding(24, 12, 24, 12);
        tv.setTextColor(0xFF8B93A3);
        tv.setBackgroundColor(0xFF171A21);
        return tv;
    }

    /** 当前是否已被设为默认输入法。 */
    public static boolean isActive(Context ctx) {
        String def = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(),
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return def != null && def.startsWith("com.dsh.agent/");
    }

    /** 发送文字到当前焦点输入框。返回是否送达（IME 存活且活跃）。 */
    public static boolean commit(Context ctx, String text, boolean replace) {
        if (!isActive(ctx)) return false;
        Intent it = new Intent(ACTION_COMMIT)
                .setPackage(ctx.getPackageName())
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_REPLACE, replace);
        ctx.sendBroadcast(it);
        return true;
    }
}
