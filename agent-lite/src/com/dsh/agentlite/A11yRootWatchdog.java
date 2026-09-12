package com.dsh.agentlite;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;

/**
 * root 深度保活看门狗（开机自启，独立于 App 进程）。
 *
 * 为什么需要它（一加 13T / ColorOS 16 实测）：
 *   进程被信号杀死后，ColorOS ①不重启 START_STICKY 前台服务；②连 JobScheduler 到点拉起也被压制
 *   （注册成功但 60s+ 不见拉起）。App 侧的一切自愈都以"进程活着"为前提，故必须有进程外兜底。
 *
 * 方案：往 KernelSU / Magisk 的开机脚本目录写一个 shell 守护（service.d），由 root 在开机时启动。
 *   root shell 循环不受 ROM 后台策略限制：每 60s 检查无障碍绑定，掉线即回写设置并拉起 App。
 *
 * 适用：已 root 的设备（KernelSU/Magisk）。未 root 设备走 App 内守护 + 系统自启动白名单引导。
 */
public final class A11yRootWatchdog {
    private static final String DIR = "/data/adb/service.d";
    private static final String SCRIPT = DIR + "/agentlite_a11y_guard.sh";

    private A11yRootWatchdog() { }

    public static boolean rootAvailable(Context ctx) {
        return RootShell.available(ctx);
    }

    public static boolean isInstalled(Context ctx) {
        if (!rootAvailable(ctx)) return false;
        String out = RootShell.execOutput("[ -f " + SCRIPT + " ] && echo yes || echo no");
        return out != null && out.contains("yes");
    }

    /** 部署看门狗脚本（开机自启 + 立即启动一次）。 */
    public static boolean install(Context ctx) {
        if (!rootAvailable(ctx)) return false;
        String comp = ctx.getPackageName() + "/" + AgentA11yService.class.getName();
        String pkg = ctx.getPackageName();
        String script =
            "#!/system/bin/sh\n" +
            "# agent-lite 无障碍守护（A11yRootWatchdog 部署）：每 60s 检查绑定，掉线即恢复\n" +
            "COMP=\"" + comp + "\"\n" +
            "PKG=\"" + pkg + "\"\n" +
            "LOG=/data/local/tmp/agentlite_guard.log\n" +
            "log() { echo \"$(date '+%m-%d %H:%M:%S') $*\" >> $LOG; }\n" +
            "log \"guard started\"\n" +
            "while true; do\n" +
            "  sleep 60\n" +
            "  BOUND=$(dumpsys accessibility 2>/dev/null | grep -c 'Agent Lite')\n" +
            "  if [ \"$BOUND\" -eq 0 ]; then\n" +
            "    log \"a11y lost -> repairing\"\n" +
            "    settings delete secure enabled_accessibility_services >/dev/null 2>&1\n" +
            "    settings put secure enabled_accessibility_services \"$COMP\" >/dev/null 2>&1\n" +
            "    settings put secure accessibility_enabled 1 >/dev/null 2>&1\n" +
            "    monkey -p \"$PKG\" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1\n" +
            "    sleep 5\n" +
            "    B2=$(dumpsys accessibility 2>/dev/null | grep -c 'Agent Lite')\n" +
            "    log \"repair result: bound=$B2\"\n" +
            "  fi\n" +
            "done\n";
        // 先落到应用私有目录，再 root 复制（避免 root 通道直接写多行文本的转义问题）
        try {
            File tmp = new File(ctx.getCacheDir(), "a11y_guard.sh");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(script.getBytes("UTF-8"));
            }
            String tmpPath = tmp.getAbsolutePath();
            boolean ok = RootShell.exec("mkdir -p " + DIR)
                    && RootShell.exec("cp " + tmpPath + " " + SCRIPT)
                    && RootShell.exec("chmod 755 " + SCRIPT)
                    && RootShell.exec("chown 0:0 " + SCRIPT)
                    && RootShell.exec("sh " + SCRIPT + " &");
            AgentEngine.staticLog("A11yRootWatchdog: 部署" + (ok ? "成功 ✓ " : "失败 ✗ ") + SCRIPT);
            return ok;
        } catch (Throwable t) {
            AgentEngine.staticLog("A11yRootWatchdog: 部署异常 " + t);
            return false;
        }
    }

    public static boolean uninstall(Context ctx) {
        if (!rootAvailable(ctx)) return false;
        boolean ok = RootShell.exec("pkill -f agentlite_a11y_guard.sh")
                && RootShell.exec("rm -f " + SCRIPT);
        AgentEngine.staticLog("A11yRootWatchdog: 已卸载" + (ok ? " ✓" : " ✗"));
        return ok;
    }

    /** 看门狗日志（诊断用）。 */
    public static String log(Context ctx) {
        String out = RootShell.execOutput("tail -8 /data/local/tmp/agentlite_guard.log 2>/dev/null");
        return out == null ? "" : out.trim();
    }
}
