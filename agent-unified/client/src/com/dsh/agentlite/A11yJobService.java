package com.dsh.agentlite;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 无障碍保活的系统级兜底（JobScheduler）。
 *
 * 背景（一加 13T / ColorOS 16 实测）：进程被信号杀死后（ApplicationExitInfo reason=2 SIGNALED），
 * ColorOS **不会**按 AOSP 语义重启 START_STICKY 的前台服务，导致无障碍掉线且无人自愈。
 * JobScheduler 由系统进程托管：即使 App 进程已被杀，系统到点仍会拉起 App 执行本 Job，
 * 从而恢复 KeepAliveService（前台服务）与 A11yGuard（守护）。
 *
 * 周期取 15 分钟（JobScheduler 的系统下限）。配合：
 *   - 前台服务：App 存活期间即时守护；
 *   - A11yGuard 5s 轮询：存活期间秒级修复；
 *   - 本 Job：进程被杀后的最长 15 分钟兜底拉起。
 * 需要用户在系统里允许自启动/后台运行才能达到最佳效果（ColorOS 等国产 ROM）。
 */
public class A11yJobService extends JobService {
    private static final int JOB_ID = 8801;
    private static final long PERIOD_MS = 15 * 60 * 1000L;   // JobScheduler 最小周期

    /** 注册周期任务（幂等）。在 KeepAliveService.onCreate 调用。 */
    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return;   // 已注册
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, A11yJobService.class))
                    .setPersisted(true)                     // 重启后仍有效（需 RECEIVE_BOOT_COMPLETED）
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            int r = js.schedule(job);
            AgentEngine.staticLog("A11yJob: 周期任务注册 " + (r == JobScheduler.RESULT_SUCCESS ? "成功 ✓" : "失败 ✗"));
        } catch (Throwable t) {
            android.util.Log.w("A11yJob", "schedule: " + t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            AgentEngine.staticLog("A11yJob: 系统拉起，执行守护自检");
            KeepAliveService.start(this);              // 恢复前台服务（含 A11yGuard.install）
            A11yGuard.check(this, "JobScheduler");     // 立即自检并在需要时回写设置
        } catch (Throwable t) {
            android.util.Log.w("A11yJob", "onStartJob: " + t);
        }
        return false;   // 同步完成，无需 reschedule
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;    // 被中断则稍后重试
    }
}
