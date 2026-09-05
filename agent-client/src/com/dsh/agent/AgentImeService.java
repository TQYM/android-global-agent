package com.dsh.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Agent 键盘：完整 QWERTY + 数字符号页的正常键盘，同时保留广播通道
 * 供 Agent 引擎在「应用屏蔽无障碍节点」时直接 commitText（支持任意中文）。
 */
public class AgentImeService extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    public static final String ACTION_COMMIT = "com.dsh.agent.IME_COMMIT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_REPLACE = "replace";

    private static final int KEY_SHIFT = -1;
    private static final int KEY_SYMBOLS = -2;
    private static final int KEY_DELETE = -5;
    private static final int KEY_PICKER = -10;
    private static final int KEY_ABC = -11;
    private static final int KEY_MORE_SYMBOLS = -12;

    private KeyboardView keyboardView;
    private Keyboard qwerty;
    private Keyboard symbols;
    private boolean shifted;

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
        registerReceiver(receiver, new IntentFilter(ACTION_COMMIT), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        qwerty = new Keyboard(this, R.xml.ime_qwerty);
        symbols = new Keyboard(this, R.xml.ime_symbols);
        keyboardView = new KeyboardView(this, null, 0, R.style.AgentKeyboardView);
        keyboardView.setKeyboard(qwerty);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);

        // Agent 提示条：标识身份 + 一键切换/收起
        android.widget.LinearLayout strip = new android.widget.LinearLayout(this);
        strip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        strip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        strip.setPadding(24, 6, 24, 6);
        strip.setBackgroundColor(0xFF14171E);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("🤖 Agent 键盘");
        label.setTextSize(11f);
        label.setTextColor(0xFF8B93A3);
        android.widget.LinearLayout.LayoutParams labelLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        strip.addView(label, labelLp);

        android.widget.TextView btnSwitch = new android.widget.TextView(this);
        btnSwitch.setText("切换输入法");
        btnSwitch.setTextSize(11f);
        btnSwitch.setTextColor(0xFF58A6FF);
        btnSwitch.setPadding(16, 8, 16, 8);
        btnSwitch.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });
        strip.addView(btnSwitch);

        android.widget.TextView btnHide = new android.widget.TextView(this);
        btnHide.setText("收起 ▾");
        btnHide.setTextSize(11f);
        btnHide.setTextColor(0xFF8B93A3);
        btnHide.setPadding(16, 8, 0, 8);
        btnHide.setOnClickListener(v -> requestHideSelf(0));
        strip.addView(btnHide);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(strip);
        root.addView(keyboardView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    // ---------- 键盘事件 ----------

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        switch (primaryCode) {
            case KEY_SHIFT:
                shifted = !shifted;
                if (qwerty != null) qwerty.setShifted(shifted);
                if (keyboardView != null) keyboardView.invalidateAllKeys();
                break;
            case KEY_SYMBOLS:
                if (keyboardView != null) keyboardView.setKeyboard(symbols);
                break;
            case KEY_ABC:
            case KEY_MORE_SYMBOLS:
                if (keyboardView != null) keyboardView.setKeyboard(qwerty);
                break;
            case KEY_DELETE:
                if (ic != null) ic.deleteSurroundingText(1, 0);
                break;
            case KEY_PICKER:
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
                break;
            case 10: {  // 回车：优先执行编辑器动作（发送/搜索/下一步），否则换行
                if (ic == null) break;
                EditorInfo ei = getCurrentInputEditorInfo();
                int action = ei == null ? EditorInfo.IME_ACTION_NONE
                        : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action);
                } else {
                    ic.commitText("\n", 1);
                }
                break;
            }
            default:
                if (ic == null) break;
                char c = (char) primaryCode;
                if (shifted && Character.isLetter(c)) {
                    c = Character.toUpperCase(c);
                }
                ic.commitText(String.valueOf(c), 1);
        }
    }

    @Override public void onPress(int primaryCode) { }
    @Override public void onRelease(int primaryCode) { }
    @Override public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }
    @Override public void swipeLeft() { }
    @Override public void swipeRight() { }
    @Override public void swipeDown() { }
    @Override public void swipeUp() { }

    // ---------- 引擎通道 ----------

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
