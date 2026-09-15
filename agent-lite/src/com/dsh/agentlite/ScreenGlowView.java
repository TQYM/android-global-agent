// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.TypedValue;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

/**
 * 原生全屏曲面贴合呼吸灯（拒绝生硬的预设直角长方形）：
 * 1. 在 API 31+ 自动通过 WindowInsets 读取物理屏幕四角的真实圆角曲率（RoundedCorner.getRadius()），
 *    在一加 13T、小米、荣耀等各类曲面/直屏设备上像素级贴合实际边框。
 * 2. 采用连续封闭的圆角轮廓（drawRoundRect）多层辉光渲染：
 *    - 外层扩散辉光（12dp 柔和环境光，融合莫奈强调色）
 *    - 内层高精轮廓（3.5dp 聚焦光环）
 * 3. 动态响应莫奈色彩体系与呼吸动画，接管状态无缝切换为素色轮廓。
 */
public class ScreenGlowView extends View {

    private final Paint paintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBorder = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rectOuter = new RectF();
    private final RectF rectInner = new RectF();

    private float cornerRadius = -1f;
    private float breathFactor = 1.0f;
    private boolean pausedMode = false;

    private int glowColor;

    public ScreenGlowView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        glowColor = MonetColors.accent(getContext());

        paintOuter.setStyle(Paint.Style.STROKE);
        paintOuter.setStrokeCap(Paint.Cap.ROUND);
        paintOuter.setStrokeJoin(Paint.Join.ROUND);

        paintInner.setStyle(Paint.Style.STROKE);
        paintInner.setStrokeCap(Paint.Cap.ROUND);
        paintInner.setStrokeJoin(Paint.Join.ROUND);

        paintBorder.setStyle(Paint.Style.STROKE);
        paintBorder.setStrokeCap(Paint.Cap.ROUND);
        paintBorder.setStrokeJoin(Paint.Join.ROUND);
        paintBorder.setColor(0x77FFFFFF);
        paintBorder.setStrokeWidth(dp(2f));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resolveCornerRadius(w, h);
    }

    /**
     * 解析屏幕物理圆角半径。
     *
     * <p><b>为什么不能只靠 WindowInsets</b>：Overlay 窗口（TYPE_ACCESSIBILITY_OVERLAY）拿到的
     * insets 里 {@code getRoundedCorner()} 常常是 null——真机实测（OnePlus 13T / ColorOS 16）
     * 光晕圆角只有 ~83px，而系统实际圆角是 **154px**，四角因此明显不贴合屏幕轮廓。
     *
     * <p>取值顺序：① 系统资源 {@code android:dimen/rounded_corner_radius}（AOSP 标准名，
     * 多数 ROM 保留）→ ② 按屏幕短边比例估算（实测本机 154/1216 ≈ 12.7%，取 12% 作通用值）。
     */
    private void resolveCornerRadius(int w, int h) {
        if (cornerRadius > 0 || w <= 0 || h <= 0) return;
        try {
            android.content.res.Resources sys = android.content.res.Resources.getSystem();
            int id = sys.getIdentifier("rounded_corner_radius", "dimen", "android");
            if (id > 0) {
                int px = sys.getDimensionPixelSize(id);
                if (px > 0) {
                    cornerRadius = px;
                    return;
                }
            }
        } catch (Throwable ignored) { }
        cornerRadius = Math.min(w, h) * 0.12f;   // 兜底：短边 12%（本机实测 12.7%）
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && insets != null) {
            try {
                // 四角都要看：只读左上角时，若该角半径与其它角不同（或系统只上报部分角），
                // 光晕圆角就会与屏幕轮廓错位。取最大值与屏幕外轮廓对齐最稳。
                int[] positions = {
                        RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                        RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
                };
                int best = 0;
                for (int pos : positions) {
                    RoundedCorner c = insets.getRoundedCorner(pos);
                    if (c != null && c.getRadius() > best) best = c.getRadius();
                }
                if (best > 0) cornerRadius = best;
            } catch (Throwable ignored) {}
        }
        return super.onApplyWindowInsets(insets);
    }

    public void setBreathFactor(float factor) {
        this.breathFactor = factor;
        invalidate();
    }

    public void setPausedMode(boolean paused) {
        this.pausedMode = paused;
        invalidate();
    }

    public void updateGlowColor(int color) {
        this.glowColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        resolveCornerRadius(w, h);
        float radius = cornerRadius > 0 ? cornerRadius : Math.min(w, h) * 0.12f;

        if (pausedMode) {
            // 接管/暂停态：仅保留精致全屏素色贴边轮廓，提示任务未终止
            float halfBorder = dp(1.5f);
            rectInner.set(halfBorder, halfBorder, w - halfBorder, h - halfBorder);
            float rPaused = Math.max(0f, radius - halfBorder);
            canvas.drawRoundRect(rectInner, rPaused, rPaused, paintBorder);
            return;
        }

        // 正常工作态：莫奈色彩全屏自适应贴合呼吸灯
        float outerStroke = dp(12f);
        float innerStroke = dp(3.5f);

        // 外层柔和环境光
        int alphaOuter = Math.min(255, Math.max(0, (int) (80 * breathFactor)));
        paintOuter.setColor((glowColor & 0x00FFFFFF) | (alphaOuter << 24));
        paintOuter.setStrokeWidth(outerStroke);
        rectOuter.set(outerStroke / 2f, outerStroke / 2f, w - outerStroke / 2f, h - outerStroke / 2f);
        // 同心几何：矩形四边各内缩 stroke/2，圆角半径也必须同比减 stroke/2，
        // 才能与屏幕物理圆角保持平行。原先减 stroke/4，圆角处与屏幕轮廓错位约 3dp
        // （560dpi ≈ 10px）——真机上就是"特效四角不贴合"（2026-09-15 用户反馈）。
        float radiusOuter = Math.max(0f, radius - outerStroke / 2f);
        canvas.drawRoundRect(rectOuter, radiusOuter, radiusOuter, paintOuter);

        // 内层聚焦光环
        int alphaInner = Math.min(255, Math.max(0, (int) (200 * breathFactor)));
        paintInner.setColor((glowColor & 0x00FFFFFF) | (alphaInner << 24));
        paintInner.setStrokeWidth(innerStroke);
        rectInner.set(innerStroke / 2f, innerStroke / 2f, w - innerStroke / 2f, h - innerStroke / 2f);
        float radiusInner = Math.max(0f, radius - innerStroke / 2f);
        canvas.drawRoundRect(rectInner, radiusInner, radiusInner, paintInner);
    }
}
