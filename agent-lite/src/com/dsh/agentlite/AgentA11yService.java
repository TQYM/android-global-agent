package com.dsh.agentlite;

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

    /** 代理可见性覆盖层（灵动岛胶囊/边缘光晕/接管卡片），随服务生命周期存活。 */
    private OverlayController overlay;

    public static AgentA11yService get() { return sInstance; }

    public OverlayController overlay() { return overlay; }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        overlay = new OverlayController(this);
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不需要事件流 */ }

    @Override
    public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        return super.onKeyEvent(event);
    }

    @Override
    public void onDestroy() {
        if (overlay != null) { overlay.hideAll(); overlay = null; }
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (overlay != null) { overlay.hideAll(); overlay = null; }
        sInstance = null;
        return super.onUnbind(intent);
    }

    // ---- 感知 ----

    /** 收集当前窗口的语义节点（可点/可滚/有文本），返回扁平列表。 */
    /** 上一次 collectNodes 的节点引用（供 tapNode 直点用），下次收集时回收。 */
    private final java.util.Map<Integer, AccessibilityNodeInfo> nodeRefs = new java.util.HashMap<>();

    /**
     * 感知当前屏幕全部应用窗口（合并多窗口：主窗口 + 弹窗/权限对话框），
     * 并排除本助手自己的界面/覆盖层——防止模型看到并误触自家胶囊/面板/卡片。
     */
    public List<NodeInfo> collectNodes() {
        List<NodeInfo> out = new ArrayList<>();
        releaseRefs();
        String self = getPackageName();
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null && !wins.isEmpty()) {
                // 层级高的（弹窗/对话框）排前面，其节点优先生效
                wins.sort((a, b) -> b.getLayer() - a.getLayer());
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    if (w == null || w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
                        continue;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && self.contentEquals(pkg)) { root.recycle(); continue; }
                    walk(root, out, 0);
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectNodes windows: " + e);
        }
        if (out.isEmpty()) {   // 兜底：活跃窗口
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !self.contentEquals(pkg)) walk(root, out, 0);
                root.recycle();
            }
        }
        return out;
    }

    private void releaseRefs() {
        for (AccessibilityNodeInfo n : nodeRefs.values()) {
            try { n.recycle(); } catch (Exception ignored) { }
        }
        nodeRefs.clear();
    }

    /** 直点节点：爬到最近的可点击祖先执行 ACTION_CLICK；失败返回 false（调用方手势兜底）。 */
    public boolean tapNode(int index) {
        AccessibilityNodeInfo n = nodeRefs.get(index);
        if (n == null) return false;
        try {
            AccessibilityNodeInfo cur = n;
            for (int up = 0; up < 8 && cur != null; up++) {
                if (cur.isClickable()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo p = cur.getParent();
                if (cur != n) cur.recycle();
                cur = p;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在指定屏幕（虚拟屏）的输入框写入文字；成功返回 null。 */
    public String setTextOnDisplay(int displayId, String text, boolean append) {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins =
                    getWindowsOnAllDisplays().get(displayId);
            if (wins == null) return "虚拟屏无窗口";
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                AccessibilityNodeInfo target = (focus != null && focus.isEditable()) ? focus : findEditable(root);
                if (target == null) { root.recycle(); continue; }
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle args = new Bundle();
                CharSequence cur = target.getText();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        (append && cur != null) ? cur.toString() + text : text);
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                root.recycle();
                return ok ? null : "ACTION_SET_TEXT 被目标应用拒绝";
            }
            return "虚拟屏页面没有可输入的编辑框";
        } catch (Exception e) { return e.getMessage(); }
    }

    /** 收集指定屏幕（虚拟屏）上的节点树；该屏无窗口返回空表。 */
    public List<NodeInfo> collectNodesOnDisplay(int displayId) {
        List<NodeInfo> out = new ArrayList<>();
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins =
                    getWindowsOnAllDisplays().get(displayId);
            if (wins == null) return out;
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                walk(root, out, 0);
                root.recycle();
            }
        } catch (Exception ignored) { }
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
                AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(n);
                if (copy != null) nodeRefs.put(ni.index, copy);
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
        if (RootShell.available(this) && RootShell.tap(x, y)) return true;
        int[] c = clampXY(x, y);
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean longPress(int x, int y, int durMs) {
        if (durMs <= 0) durMs = 900;
        if (RootShell.available(this) && RootShell.longPress(x, y, durMs)) return true;
        int[] c = clampXY(x, y);
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        if (durMs <= 0) durMs = 400;
        if (RootShell.available(this) && RootShell.swipe(x1, y1, x2, y2, durMs)) return true;
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
            latch.await(3, TimeUnit.SECONDS);   // 手势结果等待 4s→3s：超时即失败换策略，提速
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

    /** 同步截图；失败返回 null。root 时走 screencap 无限频；否则 takeScreenshot（ColorOS 限频，自动间隔+重试）。 */
    private boolean shotLogged;
    public Bitmap screenshot() {
        if (RootShell.available(this)) {
            Bitmap fast = RootShell.screenshot(this);
            if (fast != null) {
                if (!shotLogged) { shotLogged = true; android.util.Log.i("AgentA11y", "截图通道: root screencap（无限频）"); }
                sLastShotMs = System.currentTimeMillis();
                return fast;
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            long dt = System.currentTimeMillis() - sLastShotMs;
            if (dt < 900) {   // 截图限频容忍 1.1s→0.9s（荣耀/原生没有 ColorOS 那么激进）
                try { Thread.sleep(900 - dt); } catch (InterruptedException e) { return null; }
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
