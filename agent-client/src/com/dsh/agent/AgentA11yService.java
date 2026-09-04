package com.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 感知 + 执行核心。所有能力零 root：
 *  - 感知：getRootInActiveWindow 遍历语义节点
 *  - 点击/滑动/长按：dispatchGesture
 *  - 中文输入：ACTION_SET_TEXT
 *  - 截图：takeScreenshot（API 30+）
 *  - 全局按键：performGlobalAction
 */
public class AgentA11yService extends AccessibilityService {

    private static final String TAG = "AgentA11y";
    private static volatile AgentA11yService sInstance;
    private static long sLastShotMs;

    public static AgentA11yService get() { return sInstance; }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不需要事件流 */ }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    // ---- 感知 ----

    /** 收集当前窗口的语义节点（可点/可滚/有文本），返回扁平列表。 */
    public List<NodeInfo> collectNodes() {
        List<NodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        walk(root, out, 0);
        root.recycle();
        return out;
    }

    private int walk(AccessibilityNodeInfo n, List<NodeInfo> out, int depth) {
        if (n == null || depth > 60) return 0;
        int count = 0;
        try {
            CharSequence text = n.getText();
            CharSequence desc = n.getContentDescription();
            boolean interesting = n.isClickable() || n.isScrollable()
                    || (text != null && text.toString().trim().length() > 0)
                    || (desc != null && desc.toString().trim().length() > 0);
            if (interesting && out.size() < 400) {
                NodeInfo ni = new NodeInfo();
                ni.index = out.size();
                ni.text = text == null ? "" : text.toString().trim();
                ni.desc = desc == null ? "" : desc.toString().trim();
                ni.id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
                ni.cls = n.getClassName() == null ? "" : shortName(n.getClassName().toString());
                ni.clickable = n.isClickable();
                ni.scrollable = n.isScrollable();
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                ni.cx = b.centerX();
                ni.cy = b.centerY();
                out.add(ni);
                count++;
            }
            int kids = n.getChildCount();
            for (int i = 0; i < kids && out.size() < 400; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                count += walk(c, out, depth + 1);
                c.recycle();
            }
        } catch (Exception e) {
            Log.w(TAG, "walk: " + e);
        }
        return count;
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    // ---- 手势（dispatchGesture，同步等待结果） ----

    public boolean tap(int x, int y) {
        int[] c = clampXY(x, y);
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean longPress(int x, int y, int durMs) {
        if (durMs <= 0) durMs = 900;
        int[] c = clampXY(x, y);
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        if (durMs <= 0) durMs = 400;
        int[] a = clampXY(x1, y1), b = clampXY(x2, y2);
        Path p = new Path();
        p.moveTo(a[0], a[1]);
        p.lineTo(b[0], b[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    private int[] clampXY(int x, int y) {
        android.graphics.Rect b = getSystemService(android.view.WindowManager.class)
                .getCurrentWindowMetrics().getBounds();
        return new int[]{ Math.max(0, Math.min(b.width() - 1, x)),
                          Math.max(0, Math.min(b.height() - 1, y)) };
    }

    private boolean dispatch(GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> ok = new AtomicReference<>(false);
        boolean accepted = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                ok.set(true); latch.countDown();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                ok.set(false); latch.countDown();
            }
        }, null);
        if (!accepted) return false;
        try {
            latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    // ---- 中文输入（ACTION_SET_TEXT） ----

    /**
     * 向聚焦的可编辑节点写入文本（支持中文/Unicode）。默认替换内容。
     * 目标：优先 input-focus 的可编辑节点，否则活动窗口里第一个可编辑节点。
     */
    public String setText(String text, boolean append) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "无法获取当前窗口";
        AccessibilityNodeInfo target = null;
        try {
            AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) {
                target = focus;
            }
            if (target == null) target = findEditable(root);
            if (target == null) return "当前页面没有可输入的编辑框（先 tap 聚焦输入框）";

            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            CharSequence cur = target.getText();
            String value = (append && cur != null) ? cur.toString() + text : text;
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            return ok ? null : "ACTION_SET_TEXT 被目标应用拒绝";
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) {
            AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(n);
            return copy;
        }
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo hit = findEditable(c);
            c.recycle();
            if (hit != null) return hit;
        }
        return null;
    }

    // ---- 截图（API 30+ takeScreenshot） ----

    /** 同步截图；失败返回 null。ColorOS 对 takeScreenshot 有最小间隔限制，自动间隔+重试。 */
    public Bitmap screenshot() {
        for (int attempt = 0; attempt < 2; attempt++) {
            long dt = System.currentTimeMillis() - sLastShotMs;
            if (dt < 1100) {
                try { Thread.sleep(1100 - dt); } catch (InterruptedException e) { return null; }
            }
            sLastShotMs = System.currentTimeMillis();
            Bitmap bmp = screenshotOnce();
            if (bmp != null) return bmp;
        }
        return null;
    }

    private Bitmap screenshotOnce() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> out = new AtomicReference<>();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    try {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                result.getHardwareBuffer(), result.getColorSpace());
                        result.getHardwareBuffer().close();
                        if (bmp != null) out.set(bmp.copy(Bitmap.Config.ARGB_8888, false));
                    } finally {
                        latch.countDown();
                    }
                }
                @Override public void onFailure(int errorCode) {
                    Log.w(TAG, "screenshot failed: " + errorCode);
                    latch.countDown();
                }
            });
            latch.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "screenshot: " + e);
        }
        return out.get();
    }

    // ---- 全局动作 ----

    public boolean goBack()        { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean goHome()        { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean goRecents()     { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean notifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    public boolean quickSettings() { return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }
    public boolean lockScreen()    { return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
    public boolean powerDialog()   { return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); }
}
