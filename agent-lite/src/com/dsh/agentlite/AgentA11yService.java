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

    /** 屏幕形态（异形屏适配 T1）：服务连接时采集，事件流 2s 节流刷新。 */
    private static volatile DisplaySpec sSpec;
    private static long sLastSpecMs;

    public static DisplaySpec spec() { return sSpec; }

    /** 强制重采集（配置页设备形态卡「刷新」按钮）。 */
    public static void refreshSpec() {
        if (sInstance != null) sSpec = DisplaySpec.refresh(sInstance);
    }

    /** 代理可见性覆盖层（灵动岛胶囊/边缘光晕/接管卡片），随服务生命周期存活。 */
    private OverlayController overlay;

    public static AgentA11yService get() { return sInstance; }

    public OverlayController overlay() { return overlay; }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        overlay = new OverlayController(this);
        sSpec = DisplaySpec.get(this);
        Log.i(TAG, "service connected; spec=" + sSpec);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 节流刷新屏幕形态：窗口切换/旋转/分屏后 2s 内感知一次变化
        long now = System.currentTimeMillis();
        if (now - sLastSpecMs < 2000) return;
        sLastSpecMs = now;
        DisplaySpec old = sSpec;
        sSpec = DisplaySpec.get(this);
        if (old != null && sSpec != null && !old.fingerprint().equals(sSpec.fingerprint())) {
            Log.i(TAG, "屏幕形态变化: " + old.fingerprint() + " → " + sSpec.fingerprint());
        }
    }

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

    /** 最近一次感知时「最上层应用窗口」的屏幕 bounds（分屏/小窗检测用；全屏时≈全屏）。 */
    private static volatile android.graphics.Rect sActiveWinBounds;

    public static android.graphics.Rect activeWinBounds() { return sActiveWinBounds; }

    /**
     * 感知当前屏幕全部应用窗口（合并多窗口：主窗口 + 弹窗/权限对话框），
     * 并排除本助手自己的界面/覆盖层——防止模型看到并误触自家胶囊/面板/卡片。
     */
    public List<NodeInfo> collectNodes() {
        List<NodeInfo> out = new ArrayList<>();
        releaseRefs();
        String self = getPackageName();
        android.graphics.Rect topBounds = null;
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
                    if (topBounds == null) {   // 最上层非自身应用窗口 = 焦点操作窗口
                        android.graphics.Rect wb = new android.graphics.Rect();
                        w.getBoundsInScreen(wb);
                        topBounds = wb;
                    }
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
                if (pkg == null || !self.contentEquals(pkg)) {
                    if (topBounds == null) {
                        android.graphics.Rect wb = new android.graphics.Rect();
                        root.getBoundsInScreen(wb);
                        topBounds = wb;
                    }
                    walk(root, out, 0);
                }
                root.recycle();
            }
        }
        sActiveWinBounds = topBounds;
        return out;
    }

    /** 是否检测到多个显示上有窗口（外接屏/折叠外屏）；仅统计，不操作其他屏。 */
    public static boolean multiDisplay() {
        try {
            if (sInstance == null) return false;
            android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>> all =
                    sInstance.getWindowsOnAllDisplays();
            if (all == null) return false;
            int displaysWithWindows = 0;
            for (int i = 0; i < all.size(); i++) {
                List<android.view.accessibility.AccessibilityWindowInfo> ws = all.valueAt(i);
                if (ws != null && !ws.isEmpty()) displaysWithWindows++;
            }
            return displaysWithWindows > 1;
        } catch (Exception e) {
            return false;
        }
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
    // 异形屏适配 T2：所有落点先 clamp 进安全触控区（含 root 路径），
    // 挖孔/曲面边缘/系统栏落点向内修正，避免手势被系统吞掉或静默无效。

    /** 最近一次 clamp 修正距离（px），供引擎在执行后告知模型调整策略。 */
    private static volatile int sLastClampDist;

    /** 取出并清零最近一次 clamp 修正距离。 */
    public static int consumeClampDist() {
        int d = sLastClampDist;
        sLastClampDist = 0;
        return d;
    }

    public boolean tap(int x, int y) {
        int[] c = clampPoint(x, y);
        if (RootShell.available(this) && RootShell.tap(c[0], c[1])) return true;
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean longPress(int x, int y, int durMs) {
        if (durMs <= 0) durMs = 900;
        int[] c = clampPoint(x, y);
        if (RootShell.available(this) && RootShell.longPress(c[0], c[1], durMs)) return true;
        Path p = new Path();
        p.moveTo(c[0], c[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        if (durMs <= 0) durMs = 400;
        // 起点只做屏幕边界 clamp（保留 edge_back 等边缘手势语义），终点做安全区 clamp
        int[] a = clampBounds(x1, y1), b = clampPoint(x2, y2);
        if (RootShell.available(this) && RootShell.swipe(a[0], a[1], b[0], b[1], durMs)) return true;
        Path p = new Path();
        p.moveTo(a[0], a[1]);
        p.lineTo(b[0], b[1]);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
        return dispatch(new GestureDescription.Builder().addStroke(stroke).build());
    }

    /** 安全区 clamp（挖孔/曲面/系统栏回避）；开关关闭或 spec 未就绪时退化为屏幕边界 clamp。 */
    private int[] clampPoint(int x, int y) {
        DisplaySpec s = sSpec;
        if (s != null && new Prefs(this).specClamp()) {
            int[] r = s.clampTouch(x, y);
            if (r[2] > 40) {
                sLastClampDist = r[2];
                Log.i(TAG, "clampTouch 修正 " + r[2] + "px: (" + x + "," + y + ") → (" + r[0] + "," + r[1] + ")");
            }
            return new int[]{ r[0], r[1] };
        }
        return clampBounds(x, y);
    }

    /** 仅屏幕边界 clamp（旧行为）。 */
    private int[] clampBounds(int x, int y) {
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
        Bitmap out;
        if (RootShell.available(this)) {
            Bitmap fast = RootShell.screenshot(this);
            if (fast != null) {
                if (!shotLogged) { shotLogged = true; android.util.Log.i("AgentA11y", "截图通道: root screencap（无限频）"); }
                sLastShotMs = System.currentTimeMillis();
                out = fast;
                checkShotSize(out);
                return out;
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            long dt = System.currentTimeMillis() - sLastShotMs;
            if (dt < 900) {   // 截图限频容忍 1.1s→0.9s（荣耀/原生没有 ColorOS 那么激进）
                try { Thread.sleep(900 - dt); } catch (InterruptedException e) { return null; }
            }
            sLastShotMs = System.currentTimeMillis();
            Bitmap bmp = screenshotOnce();
            if (bmp != null) {
                checkShotSize(bmp);
                return bmp;
            }
        }
        return null;
    }

    /**
     * 坐标一致性自检（T3-1 尺寸校验）：截图尺寸与 DisplaySpec 不一致 →
     * 告警并强制重采集（旋转/多屏切换瞬间属正常，重采集即可跟上）。
     */
    private void checkShotSize(Bitmap bmp) {
        DisplaySpec s = sSpec;
        if (s == null || bmp == null) return;
        if (bmp.getWidth() != s.width || bmp.getHeight() != s.height) {
            Log.w(TAG, "截图尺寸与 DisplaySpec 不一致: shot=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " spec=" + s.width + "x" + s.height + "（旋转/多屏切换？强制重采集）");
            sSpec = DisplaySpec.refresh(this);
        }
    }

    /**
     * 坐标一致性自检（T3-2 锚点校准，配置页手动触发）：
     * 校验活动窗口 root bounds 与屏幕尺寸、统计节点中心越界数，
     * 返回人类可读诊断串（同时写日志）。
     */
    public String calibrate() {
        DisplaySpec s = sSpec;
        if (s == null) return "校准失败：屏幕形态未采集（无障碍服务未连接？）";
        StringBuilder sb = new StringBuilder();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "校准失败：当前无活动窗口";
            Rect rb = new Rect();
            root.getBoundsInScreen(rb);
            boolean rootOk = rb.left >= 0 && rb.top >= 0
                    && rb.right <= s.width && rb.bottom <= s.height
                    && rb.width() > 0 && rb.height() > 0;
            sb.append("根窗口 bounds=").append(rb.flattenToString())
              .append("（屏幕 ").append(s.width).append('x').append(s.height).append("）")
              .append(rootOk ? " ✓" : " ✗ 越界！ROM 坐标系统与截图不一致").append('\n');

            // 节点中心越界统计
            int[] stat = countOutOfBounds(root, s);
            sb.append("节点总数=").append(stat[0]).append("，中心越界=").append(stat[1]);
            if (stat[1] > 0) {
                sb.append("（max 偏差 ").append(stat[2]).append("px）✗ 需设置坐标偏移修正");
                Log.w(TAG, "calibrate: " + stat[1] + " 个节点中心越界，max 偏差 " + stat[2] + "px");
            } else {
                sb.append(" ✓");
            }
            root.recycle();
        } catch (Exception e) {
            return "校准异常：" + e.getMessage();
        }
        Log.i(TAG, "calibrate: " + sb.toString().replace('\n', ' '));
        return sb.toString();
    }

    /** 统计节点数与中心越界节点数；返回 [总数, 越界数, 最大越界像素]。 */
    private int[] countOutOfBounds(AccessibilityNodeInfo n, DisplaySpec s) {
        int[] r = new int[3];
        if (n == null) return r;
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            r[0]++;
            int cx = b.centerX(), cy = b.centerY();
            if (cx < 0 || cy < 0 || cx >= s.width || cy >= s.height) {
                r[1]++;
                int over = Math.max(Math.max(-cx, cx - s.width + 1), Math.max(-cy, cy - s.height + 1));
                r[2] = Math.max(r[2], Math.max(0, over));
            }
            int kids = n.getChildCount();
            for (int i = 0; i < kids; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                int[] sub = countOutOfBounds(c, s);
                r[0] += sub[0]; r[1] += sub[1]; r[2] = Math.max(r[2], sub[2]);
                c.recycle();
            }
        } catch (Exception ignored) { }
        return r;
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
