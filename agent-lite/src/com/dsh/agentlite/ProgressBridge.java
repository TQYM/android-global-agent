// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 项目进度桥：把 {@code getExternalFilesDir()/project_progress.txt} 的一行文本显示到**灵动岛胶囊**上。
 *
 * <p><b>为什么用文件而不是广播/Intent</b>：P0-A1 之后，外部 Intent（含 {@code task}/{@code auto_run}
 * extra）一律被忽略——就是为了防止任意 App 一条 intent 驱动无障碍代理。文件是"进程外**只写数据、
 * 不触发行为**"的最小通道：App 自己轮询、只读一行文本、只改一处显示。删掉文件即回到常态。
 *
 * <p><b>格式</b>：单行文本，建议 ≤18 字（胶囊很窄，超出会被截断）。文件不存在或内容为空 → 收起提示。
 */
public final class ProgressBridge {

    private static final String FILE_NAME = "project_progress.txt";
    private static final int INTERVAL_SEC = 3;      // 3 秒轮询：够及时，几乎不耗电

    private static ScheduledExecutorService exec;
    private static volatile String lastText = null;

    private ProgressBridge() { }

    public static synchronized void start(final Context ctx) {
        if (exec != null) return;
        final Context app = ctx.getApplicationContext();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress-bridge");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> poll(app), 2, INTERVAL_SEC, TimeUnit.SECONDS);
        AgentEngine.staticLog("进度桥已启动（每 " + INTERVAL_SEC + "s 检查 " + FILE_NAME + "）");
    }

    public static synchronized void stop() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
        lastText = null;
    }

    private static void poll(Context ctx) {
        try {
            File f = new File(ctx.getExternalFilesDir(null), FILE_NAME);
            String text = "";
            if (f.isFile()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                    String line = r.readLine();
                    text = line == null ? "" : line.trim();
                }
            }
            if (text.equals(lastText)) return;              // 内容没变就不折腾 UI
            lastText = text;

            AgentA11yService svc = AgentA11yService.get();
            if (svc == null) return;                        // 服务没连上（无障碍关闭）时静默跳过
            OverlayController ov = svc.overlay();
            if (ov == null) return;

            if (text.isEmpty()) {
                ov.hideProgressNote();
            } else {
                ov.showProgressNote(text);
                AgentEngine.staticLog("进度已推送到灵动岛：" + text);
            }
        } catch (Throwable ignored) {
            // 桥只是锦上添花，任何异常都不该影响主流程
        }
    }
}
