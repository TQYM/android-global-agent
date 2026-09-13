package com.dsh.agentlite;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 无障碍授权守护（李跳跳式保活）。
 *
 * 问题：无障碍服务经常"掉授权"——系统设置里仍显示已开启，但服务实际未连接（进程被回收/强杀后
 * 残留 crashed 标记），或开关被系统/用户/省电策略改回。此时 App 全部能力失效。
 *
 * 三条触发路径（覆盖掉线的主要成因）：
 *   1) ContentObserver 监听 ACCESSIBILITY_ENABLED / ENABLED_ACCESSIBILITY_SERVICES 被改写 → 立即自检并回写；
 *   2) AgentA11yService.onUnbind/onDestroy 掉线 → 延迟自检（系统重绑需要时间，避免误判后误改设置）；
 *   3) 周期自检（KeepAliveService 内每 30s）→ 覆盖"无人通知的静默掉线"；前台服务被杀后由
 *      START_STICKY 重启，onCreate 再次 install 即恢复守护。
 *
 * 分级恢复（**全程零 root**，能力递减，实测校准）：
 *   L1 直接写 Settings.Secure（需 WRITE_SECURE_SETTINGS；agent-lite 已声明，删除后重写以触发变化），
 *      写入后读回校验；
 *   L2 拉起主界面：实测证实——只要 enabled_accessibility_services 里仍有本服务，**App 进程重新
 *      启动后系统即重新绑定**（一加 13T force-stop 场景实测：am start 后绑定恢复 1），
 *      故"拉起界面"本身就是有效的零 root 恢复手段；
 *   L3 通知兜底：高优先级通知，点击直达无障碍设置页，由用户一键恢复。
 *
 * 节流：修复后 8s 内不再重复修复（避免与系统写设置形成回环）；自检 2s 内合并。
 */
public final class A11yGuard {
    private static final String TAG = "A11yGuard";
    private static final long SELF_CHECK_PERIOD_MS = 5_000;    // 周期自检间隔（observer 在部分 ROM 不可靠，轮询是兜底主力）
    private static final long REPAIR_THROTTLE_MS = 8_000;      // 修复节流
    private static final long CHECK_DEBOUNCE_MS = 2_000;       // 自检防抖
    private static final long LOST_GRACE_MS = 3_000;           // 掉线后等待系统自行重绑的宽限期

    private static volatile boolean sInstalled;
    private static ContentObserver sObserver;
    private static Handler sHandler;
    private static Runnable sPeriodic;
    private static long sLastCheckMs, sLastRepairMs;
    private static int sLostCount, sRepairOkCount, sRepairFailCount;
    private static String sLastEvent = "";

    /**
     * 无障碍连接状态变化回调（主线程）。前台界面订阅它，好在服务**异步重绑完成**时重画状态。
     *
     * <p>存在的理由：`onResume` 里判定时服务可能尚未绑定，而重绑完成时界面早已画完——
     * 界面会一直挂着「无障碍未开启」，而这一帧截图会被引擎当作首帧感知输入、
     * 误导模型以为它看不见屏幕。靠猜延迟不可靠，改为订阅真实恢复事件。
     */
    public interface Observer {
        void onA11yState(boolean connected);
    }

    private static volatile Observer sObserverRef;

    /** 注册/注销观察者。**必须在 Activity onStop 注销**，否则静态引用会泄漏 Activity。 */
    public static void setObserver(Observer obs) { sObserverRef = obs; }

    private static void notifyObserver(boolean connected) {
        final Observer obs = sObserverRef;
        if (obs == null) return;
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        sHandler.post(() -> {
            try {
                obs.onA11yState(connected);
            } catch (Throwable t) {
                // 观察者自身出错不得影响守护逻辑
                log("观察者回调异常：" + t.getClass().getSimpleName());
            }
        });
    }

    /** 供 {@link AgentA11yService#onServiceConnected()} 广播"已连接"。 */
    public static void notifyConnected() { notifyObserver(true); }

    private A11yGuard() { }

    /** 安装守护（幂等）：注册设置监听 + 启动周期自检。在 KeepAliveService.onCreate 调用。 */
    public static synchronized void install(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        if (!sInstalled) {
            sInstalled = true;
            try {
                ContentResolver cr = app.getContentResolver();
                sObserver = new ContentObserver(sHandler) {
                    @Override public void onChange(boolean self) { check(app, "设置变更"); }
                    @Override public void onChange(boolean self, android.net.Uri uri) { check(app, "设置变更"); }
                };
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, sObserver);
                cr.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, sObserver);
                log("守护已安装：监听无障碍设置变更 + 每 " + (SELF_CHECK_PERIOD_MS / 1000) + "s 自检");
            } catch (Throwable t) {
                log("注册设置监听失败: " + t);
            }
        }
        startPeriodic(app);
        // 安装即自检一次（覆盖"上次掉线后进程被杀，如今重启"的场景）
        check(app, "启动自检");
    }

    /** 服务掉线回调（onUnbind/onDestroy 调用）：宽限后再查，避免系统正在重绑时误判。 */
    public static void onServiceLost(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        sLostCount++;
        log("检测到服务掉线（累计 " + sLostCount + " 次），" + (LOST_GRACE_MS / 1000) + "s 后复查");
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        sHandler.postDelayed(() -> check(app, "掉线复查"), LOST_GRACE_MS);
    }

    /**
     * 自检 + 必要时修复。返回 true 表示当前健康（服务已连接）。
     * @param reason 触发原因（写入日志，便于回溯掉线规律）
     */
    public static boolean check(Context ctx, String reason) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - sLastCheckMs < CHECK_DEBOUNCE_MS) return AgentA11yService.get() != null;
        sLastCheckMs = now;

        boolean bound = AgentA11yService.get() != null;
        boolean enabled = isEnabledFlag(app);
        boolean listed = A11yLauncher.isEnabledInSettings(app);
        // 完全健康 = 已连接 + 总开关开 + 列表含本服务
        // 注意：总开关被关时服务可能**仍短暂绑定**（系统异步解绑），故不能只看 bound
        if (bound && enabled && listed) return true;

        sLastEvent = reason + "|" + (bound ? "已连接" : "未连接")
                + (enabled ? "/开关开" : "/开关关") + (listed ? "/列表有" : "/列表无");
        if (bound) {
            log("自检修正[" + reason + "]: " + sLastEvent + "（服务仍连接，回写设置项）");
        } else {
            log("自检异常[" + reason + "]: " + sLastEvent);
        }

        if (now - sLastRepairMs < REPAIR_THROTTLE_MS) return false;   // 节流
        sLastRepairMs = now;
        boolean ok = repair(app, listed);
        if (ok) sRepairOkCount++; else sRepairFailCount++;
        // 修复后延迟复查（系统重绑异步完成）
        if (sHandler != null) sHandler.postDelayed(() -> {
            boolean b = AgentA11yService.get() != null;
            log("修复后复查: " + (b ? "已恢复连接 ✓" : "仍未连接 ✗"));
            if (b) notifyObserver(true);
        }, 2500);
        return false;
    }

    /** 无障碍总开关是否开启（Settings.Secure.ACCESSIBILITY_ENABLED）。供状态上报复用。 */
    public static boolean isEnabledFlag(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 分级修复。listed=true 表示设置列表里仍有本服务（多为 crashed/回收），false 表示被关闭。 */
    private static boolean repair(Context app, boolean listed) {
        String comp = app.getPackageName() + "/" + AgentA11yService.class.getName();
        // L1: 直接写 Settings.Secure（需 WRITE_SECURE_SETTINGS，非 root 手段；
        //     agent-lite 已声明该权限，adb 授权/系统应用场景可用，普通安装会抛 SecurityException 走后续降级）
        try {
            // **抖动写入**（实测关键）：ColorOS 上"写入相同值"或"仅置开关"都**不会**触发系统重绑
            // （一加 13T 实测：settings put accessibility_enabled 1 后绑定仍为 0；
            //   而 delete+put 后系统立即重新绑定）。故先清空、短暂等待、再写回。
            ContentResolver cr = app.getContentResolver();
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            log("L1 抖动阶段1：已清空设置（等待系统感知）");
            if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
            sHandler.postDelayed(() -> {
                try {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, comp);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    boolean okList = A11yLauncher.isEnabledInSettings(app);
                    boolean okFlag = isEnabledFlag(app);
                    log("L1 抖动阶段2：列表=" + okList + " 开关=" + okFlag
                            + (okList && okFlag ? " ✓" : " ✗（无写权限）"));
                } catch (Throwable t) {
                    log("L1 阶段2 失败: " + t.getClass().getSimpleName());
                }
            }, 500);
            return true;
        } catch (Throwable t) {
            log("L1 写设置不可用（普通安装的正常现象，无需 root）: " + t.getClass().getSimpleName());
        }
        // L2: 拉起主界面——"列表中但未连接"时，App 进程重新启动常能触发系统重走绑定路径
        if (listed) {
            try {
                android.content.Intent i = new android.content.Intent(app, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(i);
                log("L2 已拉起主界面，等待系统重绑");
                return true;
            } catch (Throwable t) {
                log("L2 拉起主界面失败: " + t);
            }
        }
        // L3: 通知兜底（零 root 的最终手段）（点击直达无障碍设置页）
        try {
            KeepAliveService.notifyA11yLost(app);
            log("L3 已发恢复提醒通知");
        } catch (Throwable t) {
            log("L3 通知失败: " + t);
        }
        return false;
    }

    private static void startPeriodic(final Context app) {
        if (sHandler == null) sHandler = new Handler(Looper.getMainLooper());
        if (sPeriodic != null) return;
        sPeriodic = new Runnable() {
            @Override public void run() {
                check(app, "周期自检");
                sHandler.postDelayed(this, SELF_CHECK_PERIOD_MS);
            }
        };
        sHandler.postDelayed(sPeriodic, SELF_CHECK_PERIOD_MS);
    }

    /** 诊断摘要（配置页/日志用）。 */
    public static String stats() {
        return "掉线 " + sLostCount + " 次 ｜ 修复成功 " + sRepairOkCount
                + " ｜ 失败 " + sRepairFailCount
                + (sLastEvent.isEmpty() ? "" : " ｜ 最近: " + sLastEvent);
    }

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
        try { AgentEngine.staticLog("A11yGuard: " + msg); } catch (Throwable ignored) { }
    }
}
