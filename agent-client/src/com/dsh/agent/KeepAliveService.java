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
 * 前台保活服务：持有一条常驻低优先级通知，防止 ColorOS 等国产 ROM
 * 把进程速冻（这是 agentd-apk 时代 8081 反复失联的根因）。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "agent_keepalive";
    private static final int ID = 1;

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Agent 服务", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Agent 运行中")
                .setContentText("无障碍感知与执行服务保持活跃")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
