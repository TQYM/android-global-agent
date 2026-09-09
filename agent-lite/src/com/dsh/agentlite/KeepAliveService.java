package com.dsh.agentlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * 系统级灵动岛与前台保活服务：
 * 深度对接主流系统原生灵动胶囊协议（一加/OPPO ColorOS 流体云、荣耀 MagicOS 灵动胶囊、原生 Android 14+ 实时活动条）：
 * 1. 声明高优先级持续活动通知（Ongoing + CATEGORY_STATUS + setProgress）。
 * 2. 注入 ColorOS / MagicOS 原生灵动岛与流体云私有字段，直接唤起系统级状态栏灵动胶囊。
 * 3. 伴随任务全生命周期与每一步执行，实时向系统灵动岛推送最新动作文案。
 */
public class KeepAliveService extends Service {

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_TASK_TITLE = "task_title";
    public static final String EXTRA_STEP_TEXT = "step_text";
    public static final String EXTRA_STEP_CUR = "step_cur";
    public static final String EXTRA_STEP_MAX = "step_max";

    private static final String CHANNEL = "agent_system_island";
    private static final int NOTIF_ID = 1001;

    private static String lastTaskTitle;
    private static String lastStepText;
    private static int lastStepCur;
    private static int lastStepMax;
    private static boolean lastRunning;

    private static android.os.PowerManager.WakeLock sWakeLock;

    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
    }

    /** 获取或维持 CPU 锁屏运行锁（任务运行期常驻，任务结束后释放） */
    public static synchronized void acquireLock(Context ctx) {
        try {
            if (sWakeLock == null) {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    sWakeLock = pm.newWakeLock(
                            android.os.PowerManager.PARTIAL_WAKE_LOCK, "agentlite:lockscreen_keepalive");
                    sWakeLock.setReferenceCounted(false);
                }
            }
            if (sWakeLock != null && !sWakeLock.isHeld()) {
                sWakeLock.acquire(30 * 60 * 1000L); // 单次任务最长维持 30 分钟保护
                AgentEngine.staticLog("锁屏保活: PARTIAL_WAKE_LOCK 已生效");
            }
        } catch (Throwable t) {
            AgentEngine.staticLog("acquireLock 异常: " + t);
        }
    }

    public static synchronized void releaseLock() {
        try {
            if (sWakeLock != null && sWakeLock.isHeld()) {
                sWakeLock.release();
                AgentEngine.staticLog("锁屏保活: PARTIAL_WAKE_LOCK 已释放");
            }
        } catch (Throwable t) {
            AgentEngine.staticLog("releaseLock 异常: " + t);
        }
    }

    /** 更新系统灵动岛与前台状态 */
    public static void updateProgress(Context ctx, String taskTitle, String stepProgress, int step, int maxSteps) {
        lastTaskTitle = taskTitle;
        lastStepText = stepProgress;
        lastStepCur = step;
        lastStepMax = maxSteps;
        lastRunning = taskTitle != null && !taskTitle.isEmpty();

        if (lastRunning) {
            acquireLock(ctx);
        } else {
            releaseLock();
        }

        Intent it = new Intent(ctx, KeepAliveService.class)
                .putExtra(EXTRA_RUNNING, lastRunning)
                .putExtra(EXTRA_TASK_TITLE, taskTitle)
                .putExtra(EXTRA_STEP_TEXT, stepProgress)
                .putExtra(EXTRA_STEP_CUR, step)
                .putExtra(EXTRA_STEP_MAX, maxSteps);
        try {
            ctx.startForegroundService(it);
        } catch (Throwable t) {
            AgentEngine.staticLog("startForegroundService 失败: " + t);
        }
    }

    public static void updateState(Context ctx, boolean running) {
        if (!running) {
            updateProgress(ctx, null, null, 0, 0);
        } else {
            updateProgress(ctx, "Agent 正在执行任务", "处理中…", 1, 10);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Agent 系统灵动岛", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("在系统状态栏、流体云与灵动胶囊中展示 Agent 实时执行进展");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotif(lastRunning, lastTaskTitle, lastStepText, lastStepCur, lastStepMax));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            lastRunning = intent.getBooleanExtra(EXTRA_RUNNING, lastRunning);
            if (intent.hasExtra(EXTRA_TASK_TITLE)) lastTaskTitle = intent.getStringExtra(EXTRA_TASK_TITLE);
            if (intent.hasExtra(EXTRA_STEP_TEXT)) lastStepText = intent.getStringExtra(EXTRA_STEP_TEXT);
            lastStepCur = intent.getIntExtra(EXTRA_STEP_CUR, lastStepCur);
            lastStepMax = intent.getIntExtra(EXTRA_STEP_MAX, lastStepMax);
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotif(lastRunning, lastTaskTitle, lastStepText, lastStepCur, lastStepMax));
        }
        return START_STICKY;
    }

    private Notification buildNotif(boolean running, String task, String step, int curStep, int maxSteps) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = running ? (task != null ? task : "Agent 运行中") : "Agent 待命";
        String content = running ? (step != null ? step : "正在执行任务…") : "无障碍服务已连接，随时下达指令";

        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(running ? R.drawable.ic_dot_red : R.drawable.ic_dot_green)
                .setColor(MonetColors.primary(this))
                .setColorized(true)
                .setOngoing(running)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(pi);

        if (running) {
            if (curStep > 0) nb.setSubText("第 " + curStep + " 步");
            if (maxSteps > 0 && curStep > 0) {
                nb.setProgress(maxSteps, curStep, false);
            } else {
                nb.setProgress(0, 0, true);
            }
        }

        // 注入 ColorOS (OPPO/一加 流体云) + 荣耀 MagicOS (灵动胶囊) 原生胶囊协议扩展
        Bundle extras = new Bundle();
        if (running) {
            // ColorOS 流体云协议规范
            extras.putString("coloros_capsule_title", title);
            extras.putString("coloros_capsule_content", content);
            extras.putString("coloros_capsule_status", content);
            extras.putInt("coloros_capsule_style", 1);
            extras.putBoolean("coloros_fluid_cloud_enable", true);

            // Oplus 状态栏灵动胶囊规范
            extras.putString("oplus.notification.capsule.title", title);
            extras.putString("oplus.notification.capsule.content", content);
            extras.putInt("oplus.notification.capsule.status", 1);

            // 荣耀 MagicOS 灵动胶囊规范
            extras.putString("honor.notification.capsule.title", title);
            extras.putString("honor.notification.capsule.content", content);
        }
        nb.addExtras(extras);

        return nb.build();
    }

    @Override
    public void onDestroy() {
        releaseLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
