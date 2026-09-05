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
        // PUBLIC(1) | TRUSTED(1024) | OWN_DISPLAY_GROUP(2048) | SHOULD_SHOW_SYSTEM_DECORATIONS(512)
        builder = bld.getMethod("setFlags", int.class).invoke(builder, 1 | 1024 | 2048 | 512);
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

        // 宿主 App 死则自杀，防止虚拟屏泄漏（任何异常都不退出，宁可泄漏）
        int misses = 0;
        for (;;) {
            try {
                Thread.sleep(10000);
                Process p = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", "com.dsh.agent"});
                boolean alive = p.getInputStream().read() != -1;
                p.waitFor();
                misses = alive ? 0 : misses + 1;
                System.out.println("HB alive=" + alive);
                System.out.flush();
                if (misses >= 2) System.exit(0);   // 连续 20s 不在才退
            } catch (Throwable t) { /* 保持存活 */ }
        }
    }
}
