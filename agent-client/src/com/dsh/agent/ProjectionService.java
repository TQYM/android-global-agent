package com.dsh.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 投影前台服务：仅在用户完成 MediaProjection 授权后才启动。
 * 清单类型 mediaProjection 的 FGS 不能在授权前 startForeground（API 34 强校验），
 * 因此与常驻 KeepAliveService 分离。
 */
public class ProjectionService extends Service {
    private static final String CHANNEL = "agent_projection";
    private static final int NOTIF_ID = 9022;

    public static void start(android.content.Context ctx) {
        ctx.startForegroundService(new Intent(ctx, ProjectionService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "沙盒投影", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("沙盒虚拟屏运行中")
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
