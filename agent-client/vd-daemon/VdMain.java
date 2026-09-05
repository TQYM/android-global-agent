import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import java.lang.reflect.Method;

/**
 * 沙盒虚拟屏 root 守护进程（app_process 入口，被打包进 APK assets/vd_daemon.jar）。
 * 为什么需要它：ColorOS 会把普通虚拟屏的任务"organize"到物理屏（canHostTasks=false），
 * 只有 root 身份 + TRUSTED + OWN_DISPLAY_GROUP 才能建出真正隔离的虚拟屏。
 */
public class VdMain {
    public static void main(String[] args) {
        try { run(args); } catch (Throwable t) {
            java.io.PrintWriter pw = new java.io.PrintWriter(System.out, true);
            t.printStackTrace(pw); pw.flush();
        }
    }

    static void run(String[] args) throws Exception {
        int w = Integer.parseInt(args[0]), h = Integer.parseInt(args[1]), dpi = Integer.parseInt(args[2]);
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);

        Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = dmg.getMethod("getInstance").invoke(null);
        Class<?> bld = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Object builder = bld.getDeclaredConstructor(String.class, int.class, int.class, int.class)
                .newInstance("agent_sandbox", w, h, dpi);
        // PUBLIC(1) | TRUSTED(1024) | OWN_DISPLAY_GROUP(2048) | STEAL_TOP_FOCUS_DISABLED(65536)
        // 不带 SYSTEM_DECORATIONS，且必须禁抢焦点——否则 VD 建好后真屏前台 App 会被切到后台
        builder = bld.getMethod("setFlags", int.class).invoke(builder, 1 | 1024 | 2048 | 65536);
        builder = bld.getMethod("setSurface", android.view.Surface.class).invoke(builder, reader.getSurface());
        Object cfg = bld.getMethod("build").invoke(builder);

        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thr = at.getMethod("systemMain").invoke(null);
        android.content.Context ctx = (android.content.Context) at.getMethod("getSystemContext").invoke(thr);

        Method create = null;
        for (Method m : dmg.getMethods())
            if (m.getName().equals("createVirtualDisplay") && m.getParameterCount() == 5) create = m;
        VirtualDisplay vd = (VirtualDisplay) create.invoke(global, ctx, null, cfg, null, null);
        System.out.println("VD_ID=" + vd.getDisplay().getDisplayId());
        System.out.flush();

        // 宿主 App 死则自杀，防止虚拟屏泄漏；虚拟屏被系统销毁则退出，让宿主看门狗重建
        int misses = 0;
        for (;;) {
            try {
                Thread.sleep(10000);
                boolean alive = hostAlive();
                misses = alive ? 0 : misses + 1;
                boolean vdOk = vd.getDisplay().isValid();
                System.out.println("HB alive=" + alive + " vd=" + vdOk);
                System.out.flush();
                if (!vdOk) { System.out.println("VD_LOST exit"); System.out.flush(); System.exit(2); }
                if (misses >= 2) { System.out.println("HOST_GONE exit"); System.out.flush(); System.exit(0); }   // 连续 20s 不在才退
            } catch (Throwable t) { /* 保持存活 */ }
        }
    }

    /** 宿主（com.dsh.agent）是否存活：pgrep 优先，失败时扫 /proc 兜底（查不了视为存活，防误杀）。 */
    private static boolean hostAlive() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", "com.dsh.agent"});
            boolean alive = p.getInputStream().read() != -1;
            p.waitFor();
            return alive;
        } catch (Throwable t) { /* fall through to /proc scan */ }
        try {
            String[] pids = new java.io.File("/proc").list();
            if (pids != null) {
                for (String pid : pids) {
                    if (!pid.matches("\\d+")) continue;
                    try {
                        byte[] b = new byte[4096];
                        java.io.FileInputStream in = new java.io.FileInputStream("/proc/" + pid + "/cmdline");
                        int n = in.read(b);
                        in.close();
                        if (n > 0 && new String(b, 0, n).contains("com.dsh.agent")) return true;
                    } catch (Throwable ignored) { }
                }
                return false;   // /proc 可读但没找到 → 宿主确实死了
            }
        } catch (Throwable ignored) { }
        return true;   // 两种手段都不可用：宁可泄漏（宿主下次开沙盒时 pkill 兜底）
    }
}
