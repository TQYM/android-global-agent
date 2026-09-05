package com.dsh.agent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Root 加速层（可选）：检测到 su 可用时，截图/手势/粘贴走 shell 直达，
 * 绕开 ColorOS 截图限频与 dispatchGesture 的异步取消问题。
 * 无 root 设备全部返回不可用，上层自动回落无障碍路径。
 */
public class RootShell {
    private static volatile Boolean sAvailable;

    /** 一次性探测 su 可用性（KernelSU 首次会弹授权框）。 */
    public static boolean available() {
        if (sAvailable == null) {
            synchronized (RootShell.class) {
                if (sAvailable == null) sAvailable = exec("true");
            }
        }
        return sAvailable;
    }

    /** 执行一条 root shell 命令，返回是否成功（exit 0）。 */
    public static boolean exec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.getOutputStream().close();
            drain(p.getInputStream());
            drain(p.getErrorStream());
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;   // 授权弹窗无人点等场景，不阻塞引擎
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void drain(InputStream in) {
        try {
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) { }
        } catch (Exception ignored) { }
    }

    /** root 截图：screencap 直出到应用私有目录，无限频。失败返回 null。 */
    public static Bitmap screenshot(android.content.Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "root_shot.png");
        f.delete();
        if (!exec("screencap -p " + f.getAbsolutePath())) return null;
        if (!f.exists() || f.length() < 1024) return null;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        f.delete();
        return bmp;
    }

    /** root 手势：同步、不会被系统取消。 */
    public static boolean tap(int x, int y) {
        return exec("input tap " + x + " " + y);
    }

    public static boolean longPress(int x, int y, int durMs) {
        return exec("input swipe " + x + " " + y + " " + x + " " + y + " " + durMs);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        return exec("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durMs);
    }

    /** root 按键：279 = KEYCODE_PASTE（剪贴板内容直接粘贴到光标处，无需菜单）。 */
    public static boolean paste() {
        return exec("input keyevent 279");
    }
}
