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
 * 虚拟屏沙盒（root 限定）：root 守护进程（assets/vd_daemon.jar, app_process）建
 * TRUSTED + 独立显示组的 VirtualDisplay，目标 App 启动进去，Agent 在后台操作，
 * 用户前台完全不受影响。
 *
 * 为什么必须 root（三重实测证据，2026-09-06）：
 *  1. Android 10+ 起，App 创建公有虚拟屏被拒（需 ADD_MIRROR_DISPLAY/CAPTURE_VIDEO_OUTPUT
 *     或 MediaProjection token），OWN_CONTENT_ONLY 屏又只能显示自己内容；
 *  2. ColorOS（ColorOS 16 实测）会把普通/投影虚拟屏上的任务 organize 到物理屏
 *     （canHostTasks=false）；
 *  3. 荣耀 MagicOS（YLP-W00, Android 17 实测）更严：连 adb shell 对虚拟屏
 *     `am start --display` 都被 SafeActivityOptions.checkPermissions 拒绝。
 *  结论：只有 root 身份 + TRUSTED(1024) + OWN_DISPLAY_GROUP(2048) 能建出真正隔离的虚拟屏。
 *
 * 通道：
 *   建屏  —— nohup app_process .../vd_daemon.jar（守护进程：宿主死亡自动退出；虚拟屏失效主动退出）
 *   起 App —— root am start --display <id>
 *   截图  —— root screencap -d <SurfaceFlinger display-id>
 *   触控  —— root input -d <displayId>
 *   看门狗 —— 引擎每步 daemonAlive() 探测，死亡则限频 10s 自动重建（期间不碰真屏）
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
        if (!RootShell.available(ctx))
            throw new Exception("沙盒需要 root：Android 10+ 禁止应用虚拟屏承载第三方任务（无 root 设备请用全真屏模式）");

        // App 进程重启但守护还活着 → 重连既有虚拟屏（不打扰沙盒内正在运行的任务；
        // 重建会把 VD 里的任务甩到真屏，能避免就避免）
        SandboxController re = tryReattach(ctx);
        if (re != null) return re;

        stop();
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
        sAliveCache = true; sAliveCacheMs = System.currentTimeMillis();
        return c;
    }

    public static synchronized void stop() {
        if (s != null) RootShell.exec("pkill -f \"agent_[v]d.jar\"");
        s = null;
        sAliveCache = false; sAliveCacheMs = 0;
    }

    /**
     * 重连既有虚拟屏（App 进程重启但守护还活着的场景）：从守护输出读回 VD_ID、
     * 从 SurfaceFlinger 读回显示 id，只重建控制面——沙盒内任务原样保留。
     * 守护不在 / 心跳异常 / 虚拟屏已失效 → 返回 null 走全新创建。
     */
    private static SandboxController tryReattach(Context ctx) {
        try {
            if (RootShell.execOutput("pgrep -f \"agent_[v]d.jar\"").trim().isEmpty()) return null;
            String out = RootShell.execOutput("cat /data/local/tmp/agent_vd.out");
            if (out.isEmpty()) return null;
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("VD_ID=(\\d+)").matcher(out);
            int vid = -1;
            while (vm.find()) vid = Integer.parseInt(vm.group(1));   // 取最后一次
            if (vid < 0) return null;
            // 最近一次心跳必须健康（旧版守护无 vd= 字段 → 视为不可信，走重建）
            java.util.regex.Matcher hb = java.util.regex.Pattern.compile("HB alive=(\\w+) vd=(\\w+)").matcher(out);
            String lastAlive = null, lastVd = null;
            while (hb.find()) { lastAlive = hb.group(1); lastVd = hb.group(2); }
            if (!"true".equals(lastAlive) || !"true".equals(lastVd)) return null;
            String sf = RootShell.execOutput("dumpsys SurfaceFlinger --display-id | grep agent_sandbox");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Display (\\d+)").matcher(sf);
            if (!m.find()) return null;

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            SandboxController c = new SandboxController();
            c.displayId = vid;
            c.sfId = m.group(1);
            c.width = dm.widthPixels;
            c.height = dm.heightPixels;
            c.dpi = dm.densityDpi;
            c.frameFile = new File(ctx.getFilesDir(), "sandbox_frame.png");
            s = c;
            sAliveCache = true; sAliveCacheMs = System.currentTimeMillis();
            AgentEngine.staticLog("沙盒重连既有虚拟屏 displayId=" + vid + "（未重建，任务保留）");
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 守护进程存活探测（沙盒看门狗用）。结果缓存 2s，避免每步多次 su 开销。
     * 守护进程死亡原因：系统回收 root 后台进程 / 虚拟屏被系统销毁后守护主动退出（VD_LOST）等。
     */
    private static volatile long sAliveCacheMs;
    private static volatile boolean sAliveCache;
    public static synchronized boolean daemonAlive() {
        if (s == null) return false;
        long now = System.currentTimeMillis();
        if (now - sAliveCacheMs < 2000) return sAliveCache;
        String out = RootShell.execOutput("pgrep -f \"agent_[v]d.jar\"");
        sAliveCache = !out.trim().isEmpty();
        sAliveCacheMs = now;
        return sAliveCache;
    }

    /**
     * 把应用启动进虚拟屏。
     * 关键修复（跳回真屏问题）：若该应用已有任务在跑（尤其真屏上），am start --display
     * 会命中单实例限制、把已有任务顶到真屏前台——用户眼中的「跳回真屏」。
     * 改为先整体迁移（am display move-stack，实测 ColorOS 16 上微信任务迁入后稳定留在
     * 虚拟屏），只有完全没在跑的应用才冷启进 VD。
     */
    public boolean launchApp(Context ctx, String pkg) {
        String tid = findTaskId(pkg);
        if (tid != null) {
            AgentEngine.staticLog("沙盒接管：迁移已有任务 #" + tid + "（" + pkg + "）进虚拟屏");
            return RootShell.exec("am display move-stack " + tid + " " + displayId);
        }
        return RootShell.exec("am start --display " + displayId
                + " $(cmd package resolve-activity --brief " + pkg + " | tail -n1)");
    }

    /** 查应用现有任务号（任意屏）；没有返回 null。包名后跟空格/} 防前缀误匹配。 */
    private String findTaskId(String pkg) {
        String out = RootShell.execOutput("dumpsys activity activities | grep -oE 'Task\\{[a-f0-9]+ #[0-9]+[^}]*A=[0-9]+:"
                + pkg + "[ }]' | grep -oE '#[0-9]+' | head -1 | tr -d '#'");
        return out.trim().isEmpty() ? null : out.trim();
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
                + pkg + "[ }]' | grep -oE '#[0-9]+' | head -1 | tr -d '#'); "
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
