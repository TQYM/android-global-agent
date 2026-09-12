package com.dsh.agentlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

/**
 * 双进程守护（**零 root**）：运行在独立进程 {@code :guard}，与主进程的 KeepAliveService 互绑互拉。
 *
 * 原理（李跳跳等零 root 工具的通用手段）：
 *   1) 本服务与主进程服务各自处于不同进程，ROM 清理后台时通常不会同时杀掉两个；
 *   2) 通过 {@link #bindService} 跨进程绑定对端，借助 {@link ServiceConnection#onServiceDisconnected}
 *      与 {@link ServiceConnection#onBindingDied}（API 26+）获得**进程死亡通知**；
 *   3) 收到死亡通知即拉起对端：{@code startForegroundService} + 拉起界面（界面拉起会让系统
 *      重新走无障碍绑定路径——一加 13T 实测：进程重启后绑定自动恢复）。
 *
 * 为什么需要它（一加 13T / ColorOS 16 实测）：
 *   进程被信号杀死后，ColorOS 不重启 START_STICKY 前台服务，JobScheduler 到点也不拉起；
 *   唯一不依赖 root 的活路是"另一个进程还活着，由它把主进程拉回来"。
 *
 * 节流：8s 内不重复拉起（避免两端互拉形成风暴）。
 */
public class GuardService extends Service {
    private static final String CHANNEL = "agent_guard";
    private static final int NOTIF_ID = 2001;
    private static final long RELAUNCH_THROTTLE_MS = 8_000;

    private long lastRelaunchMs;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = true;
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            relaunch("主进程服务断开");
        }

        @Override public void onBindingDied(ComponentName name) {
            bound = false;
            relaunch("主进程绑定死亡");
        }

        @Override public void onNullBinding(ComponentName name) {
            bound = false;
        }
    };

    public static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, GuardService.class));
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "start: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "Agent 守护", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("保持无障碍服务在线的辅助进程");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            startForeground(NOTIF_ID, buildNotif());
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "foreground: " + t);
        }
        bindMain();
    }

    /** 绑定主进程服务：拿到它的死亡通知。BIND_AUTO_CREATE 会在主进程被杀时顺带把它拉起来。 */
    private void bindMain() {
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            bound = bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "bindMain: " + t);
        }
    }

    /** 拉起主进程：前台服务 + 界面（界面启动会让系统重新绑定无障碍服务，实测有效）。 */
    private void relaunch(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastRelaunchMs < RELAUNCH_THROTTLE_MS) return;
        lastRelaunchMs = now;
        try {
            AgentEngine.staticLog("GuardService: " + reason + " → 拉起主进程（零 root 互拉）");
        } catch (Throwable ignored) { }
        try {
            startForegroundService(new Intent(this, KeepAliveService.class));
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "relaunch service: " + t);
        }
        try {
            Intent ui = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("from_guard", true);
            // 界面拉起是无障碍重新绑定的关键路径（实测：进程起来后系统即重绑）
            startActivity(ui);
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "relaunch ui: " + t);
        }
        // Android 10+ 限制后台启动 Activity → 用**全屏意图通知**这是被系统允许的合法途径
        // （USE_FULL_SCREEN_INTENT 已声明且实测 granted）。界面一旦显示，系统即重走无障碍绑定。
        showFullScreenRecover();
        // 重新绑定，等待对端再次就绪
        postDelayedBind();
    }

    private static final String CHANNEL_FS = "agent_guard_recover";
    private static final int NOTIF_FS_ID = 2002;

    /** 全屏意图拉起界面（后台启动 Activity 的合法通道）。 */
    private void showFullScreenRecover() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_FS, "Agent 恢复", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Intent ui = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("from_guard", true);
            PendingIntent pi = PendingIntent.getActivity(this, 2, ui,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_FS)
                    .setContentTitle("正在恢复无障碍服务")
                    .setContentText("Agent 需要重新连接系统无障碍能力")
                    .setSmallIcon(R.drawable.ic_dot_red)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFullScreenIntent(pi, true)   // 关键：允许后台直接显示界面
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTIF_FS_ID, n);
        } catch (Throwable t) {
            android.util.Log.w("GuardService", "fullScreen: " + t);
        }
    }

    private void postDelayedBind() {
        new android.os.Handler(getMainLooper()).postDelayed(this::bindMain, 3000);
    }

    private Notification buildNotif() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Agent 守护运行中")
                .setContentText("保障无障碍服务不掉线")
                .setSmallIcon(R.drawable.ic_dot_green)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!bound) bindMain();
        return START_STICKY;   // 守护进程自身被重启后继续履职
    }

    @Override
    public void onDestroy() {
        try { if (bound) unbindService(conn); } catch (Throwable ignored) { }
        // 自身被杀也尝试重启（部分 ROM 会放行守护进程）
        try {
            Intent i = new Intent(this, GuardService.class);
            PendingIntent pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, pi);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
