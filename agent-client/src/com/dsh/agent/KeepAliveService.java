package com.dsh.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 前台保活服务（对抗 OEM 速冻），同时承载状态栏小圆点：
 * 绿点 = Agent 待命；红点 = 任务运行中；任务结束回到绿点。
 */
public class KeepAliveService extends Service {

    public static final String EXTRA_RUNNING = "running";
    private static final String CHANNEL = "agent_state_v2";
    private static final int NOTIF_ID = 1;

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
    }

    /** 更新状态栏圆点：true=运行中(红)，false=待命(绿)。 */
    public static void updateState(Context ctx, boolean running) {
        Intent it = new Intent(ctx, KeepAliveService.class).putExtra(EXTRA_RUNNING, running);
        ctx.startForegroundService(it);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Agent 状态", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("状态栏圆点：绿=待命，红=运行中");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, build(false),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                            | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, build(false));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean running = intent != null && intent.getBooleanExtra(EXTRA_RUNNING, false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, build(running));
        return START_STICKY;
    }

    private Notification build(boolean running) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(running ? "Agent 运行中" : "Agent 待命")
                .setSmallIcon(running ? R.drawable.ic_dot_red : R.drawable.ic_dot_green)
                .setColor(running ? 0xFFE74C3C : 0xFF2ECC71)
                .setColorized(true)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
