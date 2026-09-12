// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 无障碍设置页跳转（手机 / 手表共用）。
 *
 * 各 ROM 入口差异大，按可靠性排序逐级尝试，且**只走可导出的组件**：
 *  - 部分 ROM 的 com.android.settings/.SubSettings 未导出，直连会抛
 *    SecurityException（日志出现 Permission Denial）并延迟跳转 → 这里先查 exported。
 *  - OWW261（OPPO Watch / ColorOS Watch，Android 11）实测：带 data 的
 *    ACCESSIBILITY_DETAILS_SETTINGS 无法解析，而 ACTION_ACCESSIBILITY_SETTINGS 可用。
 *  - 一加 13T（ColorOS / Android 14）实测：细节直达页可解析，优先使用。
 *
 * 服务侧「无法运行」提示：无障碍总开关被关（accessibility_enabled=0）或服务进程被系统
 * 强杀后留下 crashed 标记时，系统页会显示「无法运行」。App 内无 WRITE_SECURE_SETTINGS
 * 权限无法自动改写，因此 {@link #statusHint(Context)} 给出可操作提示，{@link #open(Context)}
 * 负责把用户送到正确的页面。
 */
public final class A11yLauncher {

    private A11yLauncher() { }

    /** 打开无障碍设置页（当前进程内可用的最合适入口）。 */
    public static void open(Context ctx) {
        // 1) Android 14+ 官方细节页（带 package data；手表 ROM 不解此类 intent，会跳过）
        try {
            Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
            i.setData(Uri.fromParts("package", ctx.getPackageName(), null));
            i.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(ctx, AgentA11yService.class).flattenToString());
            if (i.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(i);
                return;
            }
        } catch (Throwable ignored) { }

        // 2) 系统无障碍设置主列表（最通用；OWW261 手表实测可用）
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        } catch (Throwable ignored) { }

        // 3) ColorOS/Oplus「已下载的应用」细节页（仅当组件可导出时才尝试，否则必抛权限拒绝）
        try {
            ComponentName cn = new ComponentName("com.android.settings",
                    "com.android.settings.SubSettings");
            Intent i = new Intent().setComponent(cn)
                    .putExtra(":settings:show_fragment",
                            "com.android.settings.accessibility.AccessibilityDownloadedSettings");
            if (canStart(ctx, cn)) {
                ctx.startActivity(i);
                return;
            }
        } catch (Throwable ignored) { }

        // 4) 兜底：系统设置主页，用户可自行进入「无障碍」
        try {
            ctx.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法打开系统设置：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 组件存在且 exported=true 才可被第三方 App 启动。 */
    private static boolean canStart(Context ctx, ComponentName cn) {
        try {
            ActivityInfo ai = ctx.getPackageManager().getActivityInfo(cn, 0);
            return ai != null && ai.exported;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 服务状态提示（主页/设置页共用的状态文案）。
     * @return null 表示服务正常；否则返回需要向用户展示的提示。
     */
    public static String statusHint(Context ctx) {
        if (AgentA11yService.get() != null) return null;
        boolean listed = isEnabledInSettings(ctx);
        if (listed) {
            // 已启用但未绑定：多为进程被系统回收/强杀后留下的 crashed 标记，重开即恢复
            return "无障碍服务异常（未连接）· 点此重新开启";
        }
        return "无障碍未开启 · 点此开启";
    }

    /** 只读检查：系统设置的无障碍启用列表里是否包含本服务（无需权限）。 */
    public static boolean isEnabledInSettings(Context ctx) {
        try {
            String svc = new ComponentName(ctx, AgentA11yService.class).flattenToString();
            String cur = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur != null && cur.contains(svc)) return true;
            // 兼容短名（pkg/.Service）写法
            String shortName = ctx.getPackageName() + "/." + AgentA11yService.class.getSimpleName();
            return cur != null && cur.contains(shortName);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开系统设置中本应用的详情页（用于清理/重置场景）。 */
    public static void openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            ctx.startActivity(i);
        } catch (Throwable t) {
            open(ctx);
        }
    }
}
