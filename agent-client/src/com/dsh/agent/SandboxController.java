package com.dsh.agent;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import java.nio.ByteBuffer;

/**
 * 虚拟屏沙盒：MediaProjection 建 VirtualDisplay，把目标 App 启动进去，
 * Agent 在后台操作，用户前台不受影响。
 *
 * - 感知：ImageReader 帧（无限频、不抢真屏）+ a11y 按 displayId 节点树
 * - 注入：root `input -d <displayId>`（零 root 无 API 可注入虚拟屏 → 沙盒需 root）
 */
public class SandboxController {
    private static SandboxController s;

    public static synchronized SandboxController get() { return s; }

    /** 由 MainActivity 在拿到投影授权后调用。 */
    public static synchronized SandboxController create(Context ctx, int resultCode, Intent data) {
        stop();   // 旧的先释放
        MediaProjectionManager mpm =
                (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mp = mpm.getMediaProjection(resultCode, data);
        SandboxController c = new SandboxController();
        c.mp = mp;
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display real = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        real.getRealMetrics(dm);
        c.width = dm.widthPixels;
        c.height = dm.heightPixels;
        c.reader = ImageReader.newInstance(c.width, c.height, PixelFormat.RGBA_8888, 2);
        // 仅 PUBLIC：OWN_CONTENT_ONLY 会禁止第三方 App 显示，绝不能加
        c.vd = mp.createVirtualDisplay("agent_sandbox", c.width, c.height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                c.reader.getSurface(), null, null);
        c.displayId = c.vd.getDisplay().getDisplayId();
        s = c;
        return c;
    }

    public static synchronized void stop() {
        if (s != null) {
            try { s.vd.release(); } catch (Exception ignored) { }
            try { s.mp.stop(); } catch (Exception ignored) { }
            try { s.reader.close(); } catch (Exception ignored) { }
            s = null;
        }
    }

    private MediaProjection mp;
    private VirtualDisplay vd;
    private ImageReader reader;
    private int width, height;

    private int displayId = -1;
    public int displayId() { return displayId; }
    public int width() { return width; }
    public int height() { return height; }

    /** 把应用启动进虚拟屏。 */
    public boolean launchApp(Context ctx, String pkg) {
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) return false;
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return launchIntent(ctx, it);
        } catch (Exception e) { return false; }
    }

    /** 把任意 Intent（设置页/链接）启动进虚拟屏。 */
    public boolean launchIntent(Context ctx, Intent it) {
        try {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(it, opts.toBundle());
            return true;
        } catch (Exception e) { return false; }
    }

    /** 最新一帧 → Bitmap；无帧返回 null。 */
    public Bitmap frame() {
        Image img;
        try { img = reader.acquireLatestImage(); }
        catch (Exception e) { return null; }
        if (img == null) return null;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            return Bitmap.createBitmap(bmp, 0, 0, width, height);
        } catch (Exception e) { return null; }
        finally { img.close(); }
    }

    /** 手势注入（root input -d 直达虚拟屏）。 */
    public boolean tap(int x, int y) {
        return RootShell.exec("input -d " + displayId + " tap " + x + " " + y);
    }
    public boolean longPress(int x, int y, int durMs) {
        return RootShell.exec("input -d " + displayId + " swipe " + x + " " + y + " " + x + " " + y + " " + durMs);
    }
    public boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        return RootShell.exec("input -d " + displayId + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durMs);
    }
    public boolean key(int code) {
        return RootShell.exec("input -d " + displayId + " keyevent " + code);
    }
}
