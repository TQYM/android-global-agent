package com.dsh.agentlite;

import android.content.Context;
import android.os.Build;

/**
 * Android 12+ (API 31+) Material You 莫奈色彩引擎提取与自适应回退：
 * 从系统全局壁纸中实时提取动态色彩种子，实现全局视觉的一体化与原生质感。
 */
public final class MonetColors {

    private MonetColors() {}

    public static int primary(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_accent1_200);
            } catch (Throwable ignored) {}
        }
        return 0xFF3B82F6; // 回退原生蓝
    }

    public static int primaryContainer(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_accent1_700);
            } catch (Throwable ignored) {}
        }
        return 0xFF1D4ED8;
    }

    public static int accent(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_accent3_200);
            } catch (Throwable ignored) {}
        }
        return 0xFFF472B6; // 莫奈次强调色（用于光晕、呼吸灯）
    }

    public static int surface(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_neutral1_900);
            } catch (Throwable ignored) {}
        }
        return 0xFF0F131A;
    }

    public static int surfaceContainer(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_neutral1_800);
            } catch (Throwable ignored) {}
        }
        return 0xFF191E28;
    }

    public static int onSurface(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_neutral1_100);
            } catch (Throwable ignored) {}
        }
        return 0xFFF1F5F9;
    }

    public static int onSurfaceVariant(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return ctx.getColor(android.R.color.system_neutral2_300);
            } catch (Throwable ignored) {}
        }
        return 0xFF94A3B8;
    }
}
