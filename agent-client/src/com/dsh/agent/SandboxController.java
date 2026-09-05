package com.dsh.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 虚拟屏沙盒：root 守护进程（assets/vd_daemon.jar, app_process）建 TRUSTED+独立显示组
 * 的 VirtualDisplay，目标 App 启动进去，Agent 在后台操作，用户前台完全不受影响。
 *
 * 为什么必须 root：ColorOS（实测 ColorOS 16）会把普通/投影虚拟屏上的任务
 * "organize" 到物理屏（canHostTasks=false），只有 root 身份 + TRUSTED(1024) +
 * OWN_DISPLAY_GROUP(2048) 能建出真正隔离的虚拟屏。无 root 时沙盒直接不可用。
 *
 * 通道：
 *   建屏  —— nohup app_process .../vd_daemon.jar（守护进程随宿主 App 死亡自动退出）
 *   起 App —— root am start --display <id>
 *   截图  —— root screencap -d <SurfaceFlinger display-id>
 *   触控  —— root input -d <displayId>
 */
public final class SandboxController {
    private static volatile SandboxController s;

    private int displayId = -1;
    private String sfId = null;      // SurfaceFlinger 显示 id（screencap -d 用）
    private int width, height, dpi;
    private File frameFile;

    private SandboxController() {}

    public static SandboxController get() { return s; }
    public int displayId() { return displayId; }
    public int width() { return width; }
    public int height() { return height; }

    /** 开启沙盒：起 root 守护进程建虚拟屏。失败抛异常（调用方提示用户）。 */
    public static synchronized SandboxController create(Context ctx) throws Exception {
        stop();
        if (!RootShell.available(ctx)) throw new Exception("沙盒模式需要 Root 权限");

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        // 守护 jar：assets → app files → /data/local/tmp（root 可读）
        File local = new File(ctx.getFilesDir(), "vd_daemon.jar");
        try (InputStream in = ctx.getAssets().open("vd_daemon.jar");
             FileOutputStream out = new FileOutputStream(local)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        boolean cpOk = RootShell.exec("cp " + local.getAbsolutePath()
                + " /data/local/tmp/agent_vd.jar && chmod 644 /data/local/tmp/agent_vd.jar");
        AgentEngine.staticLog("沙盒部署 cp=" + cpOk);
        if (!cpOk) throw new Exception("守护进程部署失败(cp)");
        RootShell.exec("pkill -f \"agent_[v]d.jar\"; rm -f /data/local/tmp/agent_vd.out; true");
        AgentEngine.staticLog("沙盒部署 cleanup done");

        RootShell.exec("nohup app_process -Djava.class.path=/data/local/tmp/agent_vd.jar"
                + " /system/bin VdMain " + dm.widthPixels + " " + dm.heightPixels + " " + dm.densityDpi
                + " > /data/local/tmp/agent_vd.out 2>&1 &");
        AgentEngine.staticLog("沙盒守护已请求启动");

        // 等守护进程报 VD_ID
        int vid = -1;
        for (int i = 0; i < 12 && vid < 0; i++) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            String out = RootShell.execOutput("cat /data/local/tmp/agent_vd.out");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("VD_ID=(\\d+)").matcher(out);
            if (m.find()) vid = Integer.parseInt(m.group(1));
        }
        if (vid < 0) {
            String err = RootShell.execOutput("cat /data/local/tmp/agent_vd.out");
            throw new Exception("虚拟屏创建失败：" + (err.isEmpty() ? "守护进程无输出" : err.trim()));
        }

        // SurfaceFlinger 显示 id（截屏用）
        String sf = RootShell.execOutput("dumpsys SurfaceFlinger --display-id | grep agent_sandbox");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Display (\\d+)").matcher(sf);
        if (!m.find()) { RootShell.exec("pkill -f \"agent_[v]d.jar\""); throw new Exception("虚拟屏未注册到合成器"); }

        SandboxController c = new SandboxController();
        c.displayId = vid;
        c.sfId = m.group(1);
        c.width = dm.widthPixels;
        c.height = dm.heightPixels;
        c.dpi = dm.densityDpi;
        c.frameFile = new File(ctx.getFilesDir(), "sandbox_frame.png");
        s = c;
        return c;
    }

    public static synchronized void stop() {
        if (s != null) RootShell.exec("pkill -f \"agent_[v]d.jar\"");
        s = null;
    }

    /** 把应用启动进虚拟屏（root am --display）。 */
    public boolean launchApp(Context ctx, String pkg) {
        return RootShell.exec("am start --display " + displayId
                + " $(cmd package resolve-activity --brief " + pkg + " | tail -n1)");
    }

    /** 把设置页/系统 action 启动进虚拟屏。 */
    public boolean launchAction(String action) {
        return RootShell.exec("am start --display " + displayId + " -a " + action);
    }

    /** 把链接/scheme 启动进虚拟屏。 */
    public boolean launchUrl(String url) {
        return RootShell.exec("am start --display " + displayId
                + " -a android.intent.action.VIEW -d '" + url.replace("'", "") + "'");
    }

    /** 启动后校验：虚拟屏是否真的出现了该应用的窗口（弹回真屏则 false）。 */
    public boolean hostsPackage(Context ctx, String pkg) {
        String dump = RootShell.execOutput("dumpsys activity activities");
        if (dump.isEmpty()) return true;   // 查不了就信任（不挡任务）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "Display #" + displayId + " \\(activities from top to bottom\\):(.*?)" +
                "(?=Display #|RootTaskContainer|\\Z)", java.util.regex.Pattern.DOTALL)
                .matcher(dump);
        if (!m.find()) return false;
        return m.group(1).contains(pkg);
    }

    /** 把迁移到真屏的任务拉回虚拟屏（防御性兜底）。 */
    public boolean reclaimTask(String pkg) {
        String cmd = "T=$(dumpsys activity activities | grep -oE 'Task\\{[a-f0-9]+ #[0-9]+[^}]*A=[0-9]+:"
                + pkg + "' | grep -oE '#[0-9]+' | head -1 | tr -d '#'); "
                + "[ -n \"$T\" ] && am display move-stack $T " + displayId;
        return RootShell.exec(cmd);
    }

    /** 取虚拟屏最新一帧；失败返回 null。 */
    public Bitmap frame() {
        if (!RootShell.exec("screencap -p -d " + sfId + " " + frameFile.getAbsolutePath()))
            return null;
        return BitmapFactory.decodeFile(frameFile.getAbsolutePath());
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
    public boolean key(int keycode) {
        return RootShell.exec("input -d " + displayId + " keyevent " + keycode);
    }
}
