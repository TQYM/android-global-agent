// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设备屏幕形态统一抽象（异形屏适配 T1）——零 root。
 *
 * 采集：真实分辨率、旋转、密度、挖孔/刘海区域（DisplayCutout）、
 * 瀑布屏禁触区（WaterfallInsets）、状态栏/导航栏高度、导航模式，
 * 并据此计算「安全触控区」safeRect 与坐标 clamp。
 *
 * 用途：
 *  - AgentEngine 感知注入：把形态告诉模型，提高异形屏/分屏下坐标预估精度；
 *  - clampTouch：px/py 比例坐标落点被挖孔/曲面边缘/系统栏吞掉时向内修正；
 *  - fingerprint：检测形态变化（旋转/分屏/插拔外屏），触发节点表缓存失效。
 *
 * 设计约束：所有改动零 root、minSdk 30 直接可用；采集失败时退化为全屏矩形
 * （与旧行为完全一致，保证既有真机链路可回退）。
 */
public final class DisplaySpec {

    private static final String TAG = "DisplaySpec";

    // ---- 采集字段 ----
    public int width, height;                  // 真实物理像素（含挖孔/系统栏区）
    public int rotation;                       // Surface.ROTATION_0/90/180/270
    public int densityDpi;                     // 密度
    public float density;                      // scale（dp→px）
    public final List<Rect> cutout = new ArrayList<>();   // 挖孔/刘海边界（屏幕坐标，可多个）
    public int waterfallL, waterfallT, waterfallR, waterfallB;  // 曲面禁触区（像素）
    public int statusBarH, navBarH;            // 状态栏高 / 导航区高（0=手势条不可见）
    public boolean gestureNav;                 // true=手势导航，false=三键
    public boolean round;                      // true=圆形屏（手表）：四角物理不存在
    public final Rect safe = new Rect();       // 安全触控区 [L,T,R,B]（可有效注入手势的区域）
    public long collectedAt;                   // 采集时间戳

    /** 是否竖屏（宽度 < 高度）；横屏平板 natural 状态即宽>高。 */
    public boolean portrait() { return width < height; }

    /** 形态名：圆形（手表）/ 竖屏 / 横屏（方形表盘宽=高归横屏描述无意义，用方形）。 */
    public String shapeName() {
        if (round) return "圆形";
        if (width == height) return "方形";
        return portrait() ? "竖屏" : "横屏";
    }

    // ---- 厂商 ROM 坐标偏移修正（T3 校准：节点坐标与截图坐标系统不一致时使用） ----
    /** 全局偏移量（px）：节点/截图坐标执行前统一加上的 Δ。默认 0。 */
    public static volatile int offsetX, offsetY;

    /** 设置全局坐标偏移（诊断页校准发现偏移后由用户/日志触发）。 */
    public static void setOffset(int dx, int dy) { offsetX = dx; offsetY = dy; }

    /** 应用全局偏移：返回修正后坐标。 */
    public static int[] applyOffset(int x, int y) {
        return new int[]{ x + offsetX, y + offsetY };
    }

    // ---- 静态缓存（节流刷新） ----
    private static volatile DisplaySpec sLast;
    private static long sLastCollectMs;
    private static final long REFRESH_MS = 500;   // 节流：500ms 内不重复采集

    // ---- 瀑布禁触区回填（来自 OverlayController 全屏窗口 insets 回调） ----
    // 无窗口进程（A11y 服务）的 WindowMetrics 不暴露 waterfall side insets，
    // 覆盖层窗口（NO_LIMITS + SHORT_EDGES）的 DisplayCutout.getWaterfallInsets() 是公开可达通道。
    private static volatile int sWfL, sWfT, sWfR, sWfB;

    public static void setWaterfallFallback(int l, int t, int r, int b) {
        if (l == sWfL && t == sWfT && r == sWfR && b == sWfB) return;
        sWfL = l; sWfT = t; sWfR = r; sWfB = b;
        sLastCollectMs = 0;   // 值变化 → 强制下次重采集
    }

    public static DisplaySpec last() { return sLast; }

    /** 采集（500ms 节流）；返回最新值。 */
    public static DisplaySpec get(Context ctx) {
        long now = System.currentTimeMillis();
        if (sLast != null && now - sLastCollectMs < REFRESH_MS) return sLast;
        try {
            DisplaySpec s = collect(ctx);
            sLast = s;
            sLastCollectMs = now;
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "collect failed: " + t);
            if (sLast == null) sLast = fallback(ctx);
            sLastCollectMs = now;
            return sLast;
        }
    }

    /** 强制重采集（配置页「刷新」按钮 / 形态变更事件）。 */
    public static DisplaySpec refresh(Context ctx) {
        sLastCollectMs = 0;
        return get(ctx);
    }

    // ---- 采集实现 ----
    private static DisplaySpec collect(Context ctx) {
        DisplaySpec s = new DisplaySpec();
        android.content.res.Resources res = ctx.getResources();
        s.densityDpi = res.getDisplayMetrics().densityDpi;
        s.density = res.getDisplayMetrics().density;
        s.collectedAt = System.currentTimeMillis();

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        // 尺寸：优先 maximumWindowMetrics（无窗口进程也返回含 insets 的全屏最大窗口）
        WindowMetrics wmMetrics = null;
        try { wmMetrics = wm.getMaximumWindowMetrics(); } catch (Throwable t) { }
        if (wmMetrics == null) {
            try { wmMetrics = wm.getCurrentWindowMetrics(); } catch (Throwable t) { }
        }
        if (wmMetrics != null) {
            Rect b = wmMetrics.getBounds();
            s.width = b.width();
            s.height = b.height();
        } else {
            s.width = res.getDisplayMetrics().widthPixels;
            s.height = res.getDisplayMetrics().heightPixels;
        }

        // 旋转
        try {
            Display d = wm.getDefaultDisplay();
            if (d != null) s.rotation = d.getRotation();
        } catch (Throwable t) { }

        // insets：状态栏 / 导航栏 / 挖孔 / 瀑布禁触区
        WindowInsets ins = null;
        try { if (wmMetrics != null) ins = wmMetrics.getWindowInsets(); } catch (Throwable t) { }
        if (ins != null) {
            try {
                Insets sb = ins.getInsets(WindowInsets.Type.statusBars());
                s.statusBarH = sb.top;
            } catch (Throwable t) { }
            try {
                Insets nb = ins.getInsets(WindowInsets.Type.navigationBars());
                s.navBarH = Math.max(nb.bottom, nb.top);
            } catch (Throwable t) { }
            try {
                // 瀑布禁触区：WindowInsets.getWaterfallInsets 是 @hide，公开途径走 DisplayCutout（API 29+）
                android.view.DisplayCutout dc = ins.getDisplayCutout();
                if (dc != null) {
                    Insets wf = dc.getWaterfallInsets();
                    if (wf != null) {
                        s.waterfallL = wf.left; s.waterfallT = wf.top;
                        s.waterfallR = wf.right; s.waterfallB = wf.bottom;
                    }
                    for (Rect r : dc.getBoundingRects()) {
                        if (r != null && !r.isEmpty()) s.cutout.add(new Rect(r));
                    }
                }
                // 补充通道：无窗口进程里 getWaterfallInsets 可能返回 0，
                // 而瀑布区同时体现在 displayCutout insets 的 side（模拟器/部分 ROM 实测）
                Insets dcSide = ins.getInsets(WindowInsets.Type.displayCutout());
                if (dcSide != null) {
                    if (s.waterfallL <= 0 && dcSide.left > 0) s.waterfallL = dcSide.left;
                    if (s.waterfallR <= 0 && dcSide.right > 0) s.waterfallR = dcSide.right;
                }
            } catch (Throwable t) { }
        }

        // 瀑布回填通道（overlay 窗口 insets 回调）：本进程 WindowMetrics 拿不到时兜底
        if (s.waterfallL <= 0) s.waterfallL = sWfL;
        if (s.waterfallT <= 0) s.waterfallT = sWfT;
        if (s.waterfallR <= 0) s.waterfallR = sWfR;
        if (s.waterfallB <= 0) s.waterfallB = sWfB;

        // 导航模式：framework 资源探测（@hide config_navBarInteractionMode），失败启发式
        s.gestureNav = probeGestureNav(ctx, s);

        // 圆形屏检测（手表）：Configuration.isScreenRound() 公开 API（23+）
        try {
            s.round = ctx.getResources().getConfiguration().isScreenRound();
        } catch (Throwable t) { }

        // 安全触控区
        computeSafe(s);

        return s;
    }

    private static boolean probeGestureNav(Context ctx, DisplaySpec s) {
        try {
            int id = ctx.getResources().getIdentifier("config_navBarInteractionMode", "bool", "android");
            if (id != 0) return ctx.getResources().getBoolean(id);
        } catch (Throwable t) { }
        // 启发式：导航 insets 小于 32dp 视为手势导航（三键高约 48dp）
        return s.navBarH < (int) (32 * s.density + 0.5f);
    }

    /** 计算安全触控区：全屏 − 瀑布禁触区 − 系统栏 − 顶部挖孔下沿。
     *  圆形屏（手表）：矩形 safe 取内接正方形（保守近似），
     *  clampTouch 另做径向修正以保留圆上下窄条区域的可点性。 */
    private static void computeSafe(DisplaySpec s) {
        int l = Math.max(s.waterfallL, 0);
        int r = Math.max(s.waterfallR, 0);

        // 顶部：状态栏区不可点（会拉下通知栏），挖孔若伸出状态栏则再下移
        int top = Math.max(s.statusBarH, 0);
        for (Rect c : s.cutout) {
            if (c.top < s.height / 3 && c.bottom > top) top = c.bottom;
        }

        // 底部：三键高；手势模式保底 24dp 手势区（手势条可能报告 0）
        int bottom = Math.max(s.navBarH, s.gestureNav ? (int) (24 * s.density + 0.5f) : 0);

        if (s.round) {
            // 圆形屏：内接正方形（半径 R 的内接方形半边 = R/√2）
            int rad = Math.min(s.width, s.height) / 2;
            int half = (int) (rad / Math.sqrt(2));
            int cx0 = s.width / 2, cy0 = s.height / 2;
            s.safe.set(cx0 - half, Math.max(cy0 - half, top),
                       cx0 + half, Math.min(cy0 + half, s.height - bottom));
        } else {
            s.safe.set(l, top, s.width - r, s.height - bottom);
        }
        if (s.safe.right <= s.safe.left) s.safe.right = s.safe.left + 1;
        if (s.safe.bottom <= s.safe.top) s.safe.bottom = s.safe.top + 1;
    }

    /** 无信息兜底：全屏矩形，等价旧行为。 */
    private static DisplaySpec fallback(Context ctx) {
        DisplaySpec s = new DisplaySpec();
        s.density = ctx.getResources().getDisplayMetrics().density;
        s.densityDpi = ctx.getResources().getDisplayMetrics().densityDpi;
        s.width = ctx.getResources().getDisplayMetrics().widthPixels;
        s.height = ctx.getResources().getDisplayMetrics().heightPixels;
        s.safe.set(0, 0, s.width, s.height);
        s.collectedAt = System.currentTimeMillis();
        return s;
    }

    // ---- 落点安全（T2 接入执行路径） ----

    /**
     * 把目标坐标 clamp 进安全触控区；返回修正后的 [x,y] 与修正距离（[x,y,dist]）。
     * 挖孔区/曲面边缘/系统栏落点均被向内修正，避免手势被系统吞掉或静默无效。
     */
    public int[] clampTouch(int x, int y) {
        int cx = x, cy = y;
        if (round) {
            // 圆形屏：不用内接方形限制边界（会浪费圆上下窄条），
            // 只限制系统栏，其余交给径向修正兜底。
            int topBar = Math.max(statusBarH, 0);
            int botBar = Math.max(navBarH, gestureNav ? (int) (24 * density + 0.5f) : 0);
            cy = Math.max(topBar, Math.min(height - botBar, cy));
            // 挖孔回避（圆屏少见，保留逻辑）
            for (Rect c : cutout) {
                if (c.contains(cx, cy)) {
                    cy = c.bottom + (int) (8 * density + 0.5f);
                    if (cy > height - botBar) cy = height - botBar;
                    break;
                }
            }
            // 径向修正：超出内切圆（留 12dp 边距防误触边缘手势）→ 沿半径拉回圆内
            float ccx = width / 2f, ccy = height / 2f;
            float rr = Math.min(width, height) / 2f - 12f * density;
            double d = Math.hypot(cx - ccx, cy - ccy);
            if (d > rr && d > 1) {
                cx = (int) (ccx + (cx - ccx) * rr / d);
                cy = (int) (ccy + (cy - ccy) * rr / d);
            }
        } else {
            cx = Math.max(safe.left, Math.min(safe.right, cx));
            cy = Math.max(safe.top, Math.min(safe.bottom, cy));
            // 挖孔回避：落点在挖孔内 → 移到其正下方
            for (Rect c : cutout) {
                if (c.contains(cx, cy)) {
                    cy = c.bottom + (int) (8 * density + 0.5f);
                    if (cy > safe.bottom) cy = safe.bottom;
                    break;
                }
            }
        }
        int dist = (int) Math.sqrt((cx - x) * (cx - x) + (cy - y) * (cy - y));
        return new int[]{ cx, cy, dist };
    }

    // ---- 形态变化检测 ----

    /** 形态指纹：宽高/旋转/系统栏/挖孔/瀑布/圆形任一变化都会变。 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(width).append('x').append(height).append('@').append(rotation)
          .append("|sb").append(statusBarH).append("|nb").append(navBarH)
          .append("|wf").append(waterfallL).append(',').append(waterfallR)
          .append("|rd").append(round ? 1 : 0);
        for (Rect c : cutout) sb.append("|c").append(c.flattenToString());
        return sb.toString();
    }

    // ---- 感知注入摘要（T2 使用，≤120 字原则） ----

    public String promptFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append(shapeName()).append(' ')
          .append(width).append('×').append(height).append('@').append(densityDpi).append("dpi");
        if (round) {
            // 圆形表盘：比例坐标四角必然落空，必须显式告知 LLM
            sb.append("，圆形屏四角无显示（内切圆半径").append(Math.min(width, height) / 2)
              .append("px），px/py 比例坐标禁止落在四角与边缘，目标不在可视区时先滑动");
        }
        if (statusBarH > 0) sb.append("，状态栏高").append(statusBarH).append("px");
        if (round) {
            // 圆屏（手表）无手势条：省略手势区句式，避免内接方形差误报为手势区
        } else if (gestureNav) {
            sb.append("，手势导航，底部").append(height - safe.bottom).append("px 为手势区");
        } else if (navBarH > 0) {
            sb.append("，三键导航高").append(navBarH).append("px");
        }
        if (!cutout.isEmpty()) {
            Rect c = cutout.get(0);
            sb.append("，挖孔位于").append(horizPos(c)).append('[')
              .append(c.flattenToString()).append("]，该区域触摸无效");
            if (cutout.size() > 1) sb.append("（共").append(cutout.size()).append("处）");
        }
        if (waterfallL > 0 || waterfallR > 0) {
            sb.append("，曲面屏左右各").append(Math.max(waterfallL, waterfallR)).append("px 为禁触区");
        }
        sb.append("，安全触控区[").append(safe.flattenToString()).append(']');
        return sb.toString();
    }

    private String horizPos(Rect c) {
        int cxm = c.centerX();
        if (cxm < width / 3) return "左侧";
        if (cxm > width * 2 / 3) return "右侧";
        return "顶部居中";
    }

    // ---- 展示（配置页设备形态卡） ----

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(width).append('×').append(height).append('@').append(densityDpi)
          .append("dpi 旋转").append(rotation).append(' ')
          .append(shapeName()).append('\n');
        sb.append("状态栏 ").append(statusBarH).append("px ｜ 导航区 ").append(navBarH).append("px（")
          .append(gestureNav ? "手势" : "三键").append("）\n");
        if (round) {
            sb.append("圆形屏：四角无显示（内切圆 R=").append(Math.min(width, height) / 2).append("）\n");
        }
        if (cutout.isEmpty()) {
            sb.append("挖孔：无\n");
        } else {
            for (Rect c : cutout) sb.append("挖孔：").append(c.flattenToString()).append('\n');
        }
        if (waterfallL > 0 || waterfallR > 0) {
            sb.append("瀑布曲面：L").append(waterfallL).append(" R").append(waterfallR).append('\n');
        }
        sb.append("安全触控区：").append(safe.flattenToString());
        return sb.toString();
    }
}
