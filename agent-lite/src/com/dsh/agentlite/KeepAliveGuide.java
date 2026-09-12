package com.dsh.agentlite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 保活配置引导（零 root）：检测保活所需的关键系统配置是否到位，并一键跳转到对应厂商设置页。
 *
 * 为什么需要引导：国产 ROM（ColorOS/MIUI/EMUI/OriginOS…）默认会限制后台，
 * 无障碍服务被清理后不易自动恢复。用户在系统里把这几个开关打开，掉线率会大幅下降
 * （这也是李跳跳等零 root 工具的通用要求）。本类把"该开哪些开关、每个开关在哪里"做成
 * 可一键跳转的自检清单，免去用户自己翻菜单。
 *
 * 自检项：
 *   1) 无障碍服务已连接（AgentA11yService.get() != null）
 *   2) 自启动 / 后台运行白名单（厂商设置页，无法直接读取，只能引导）
 *   3) 忽略电池优化（可直接读取）
 *   4) 通知权限（保活通知与恢复提醒依赖）
 *   5) 守护进程与前台服务存活（GuardService / KeepAliveService）
 */
public final class KeepAliveGuide {
    private KeepAliveGuide() { }

    // ---- 状态检测 ----

    public static boolean a11yBound() {
        return AgentA11yService.get() != null;
    }

    public static boolean ignoringBattery(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean notificationsEnabled(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 守护进程（:guard）是否存活——通过 /proc 下的进程名粗查（零 root 可行）。 */
    public static boolean guardProcessAlive(Context ctx) {
        try {
            java.io.File proc = new java.io.File("/proc");
            String self = ctx.getPackageName() + ":guard";
            String[] pids = proc.list();
            if (pids == null) return false;
            for (String pid : pids) {
                if (!pid.matches("\\d+")) continue;
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/" + pid + "/cmdline"))) {
                    String line = r.readLine();
                    if (line != null && line.replace("\u0000", "").trim().equals(self)) return true;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 一句话状态摘要（供 UI 显示）。 */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(a11yBound() ? "无障碍✓" : "无障碍✗");
        sb.append(" ｜ ").append(ignoringBattery(ctx) ? "电池白名单✓" : "电池白名单✗");
        sb.append(" ｜ ").append(notificationsEnabled(ctx) ? "通知✓" : "通知✗");
        sb.append(" ｜ ").append(guardProcessAlive(ctx) ? "守护进程✓" : "守护进程✗");
        return sb.toString();
    }

    /** 未完成项数量与建议（供横幅/卡片提示）。 */
    public static String advice(Context ctx) {
        if (!a11yBound()) return "无障碍服务未连接 · 点「一键开启」恢复";
        java.util.List<String> todo = new java.util.ArrayList<>();
        if (!ignoringBattery(ctx)) todo.add("电池改为「无限制」");
        if (!notificationsEnabled(ctx)) todo.add("允许通知");
        if (!guardProcessAlive(ctx)) todo.add("允许后台运行/自启动");
        if (todo.isEmpty()) return "保活配置完整，掉线可自动恢复";
        return "建议开启：" + String.join("、", todo) + "（可显著降低掉线率）";
    }

    // ---- ROM 识别与手动路径指引 ----

    /** 当前 ROM 名称（用于给出对应的设置路径）。 */
    public static String romName() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")) return "ColorOS/realme UI";
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return "MIUI/HyperOS";
        if (m.contains("huawei")) return "EMUI/HarmonyOS";
        if (m.contains("honor")) return "MagicOS";
        if (m.contains("vivo") || m.contains("iqoo")) return "OriginOS";
        if (m.contains("samsung")) return "One UI";
        return "系统设置";
    }

    /**
     * 该 ROM 的手动配置路径（实测：ColorOS 16 的「自启动管理」页**不导出**，
     * 第三方应用连 adb 都无法直达，故必须给用户明确的文字路径）。
     */
    public static String manualSteps(Context ctx) {
        String r = romName();
        if (r.startsWith("ColorOS")) {
            return "本机 " + r + "：点下方按钮进入应用详情 → 「耗电管理」→ 打开「允许后台运行」"
                 + "；再进「应用管理 → 自启动」为本应用打开开关";
        }
        if (r.startsWith("MIUI")) {
            return "本机 " + r + "：安全中心 → 应用管理 → 权限 → 自启动管理，允许本应用；"
                 + "并在「省电策略」中选「无限制」";
        }
        if (r.startsWith("EMUI") || r.startsWith("MagicOS")) {
            return "本机 " + r + "：设置 → 应用 → 应用启动管理，关闭「自动管理」并勾选全部允许";
        }
        if (r.startsWith("OriginOS")) {
            return "本机 " + r + "：i管家 → 应用管理 → 权限管理 → 自启动，允许本应用";
        }
        return "请在系统设置的「应用管理 / 电池」中允许本应用自启动与后台运行";
    }

    // ---- 一键跳转 ----

    /** 忽略电池优化授权页（系统标准接口）。 */
    public static void openBatterySettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            openAppDetails(ctx);
        }
    }

    /**
     * 打开厂商的「自启动 / 后台运行」管理页。逐个尝试常见入口，全部失败则落到应用详情页
     * （用户可在其中找到"自启动/后台运行"）。
     */
    public static void openAutoStartSettings(Context ctx) {
        String[][] candidates = {
                // ColorOS 12+/16（一加 13T 实测：StartupAppListActivity 即「自启动管理」）
                {"com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"},
                {"com.oplus.battery", "com.oplus.startupapp.view.OptimizationAutoStartActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                // ColorOS / realme UI（旧版）
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.coloros.phonemanager", "com.coloros.phonemanager.startup.StartupAppListActivity"},
                // MIUI
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // EMUI / HarmonyOS（安卓兼容层）
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // vivo / OriginOS
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                // 三星
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                // 一加/OPPO 通用后台管理
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
        };
        // 厂商自定义 action（部分 ROM 只暴露 action 入口）
        String[] actions = {
                "com.oplus.battery.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        };
        for (String act : actions) {
            try {
                Intent i = new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
                    ctx.startActivity(i);
                    AgentEngine.staticLog("KeepAliveGuide: 打开自启动页(action) " + act);
                    return;
                }
            } catch (Throwable ignored) { }
        }
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
                    ctx.startActivity(i);
                    AgentEngine.staticLog("KeepAliveGuide: 打开自启动页 " + c[0]);
                    return;
                }
            } catch (Throwable ignored) { }
        }
        AgentEngine.staticLog("KeepAliveGuide: 无匹配自启动页，落到应用详情页");
        openAppDetails(ctx);
    }

    /** 应用详情页（通用兜底，含自启动/权限入口）。 */
    public static void openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) { }
        }
    }
}
