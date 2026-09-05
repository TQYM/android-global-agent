package dev.patrickgold.florisboard.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import java.io.File;
import java.io.FileWriter;
import android.view.inputmethod.InputConnection;
import dev.patrickgold.florisboard.FlorisImeService;

/**
 * 缝合桥：FlorisBoard 的 IME 服务 + Agent 注入通道。
 * 进程内广播 com.dsh.agent.IME_COMMIT（extras: text, replace）→ commitText。
 * 同时暴露静态 commit() 供同设备其他 App 直接用类调用（此路径主要走广播）。
 */
public class AgentImeBridge extends FlorisImeService {
    public static final String ACTION_COMMIT = "com.dsh.agent.IME_COMMIT";
    private static AgentImeBridge sInstance;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String text = i.getStringExtra("text");
            if (text == null) return;
            boolean replace = i.getBooleanExtra("replace", false);
            boolean ok = commitText(text, replace);
            if (sInstance != null) sInstance.logf("rx [" + text + "] ok=" + ok);
        }
    };

    private void logf(String m) {
        try {
            File f = new File(getExternalFilesDir(null), "bridge.log");
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + m + "\n");
            w.close();
        } catch (Exception e) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        logf("onCreate " + getClass().getName());
        IntentFilter f = new IntentFilter(ACTION_COMMIT);
        // 跨进程可达但需签名外权限 dev.patrickgold.florisboard.agent.INJECT（normal 级）
        registerReceiver(rx, f, "dev.patrickgold.florisboard.agent.INJECT", null,
                Context.RECEIVER_EXPORTED);
    }

    @Override public void onDestroy() {
        sInstance = null;
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    public static boolean isActive() { return sInstance != null; }

    public static boolean commitText(String text, boolean replace) {
        AgentImeBridge s = sInstance;
        if (s == null) return false;
        InputConnection ic = s.getCurrentInputConnection();
        if (ic == null) return false;
        if (replace) ic.performContextMenuAction(16908319); // selectAll
        return ic.commitText(text, 1);
    }
}
