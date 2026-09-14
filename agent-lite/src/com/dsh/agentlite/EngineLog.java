// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 引擎日志的异步落盘通道（手册 §15.2-9）。
 *
 * <p><b>为什么需要</b>：此前每条日志都「打开 → 写 → 关闭」一次文件，且异常被静默吞掉。
 * 长任务里日志密集（每步数条），同步 IO 会把引擎线程拖慢；写失败时也无从得知，
 * 排障时只能面对一个「看起来没问题」的空文件。
 *
 * <p><b>做法</b>：入队即返回（非阻塞），后台守护线程批量落盘并 flush。
 *
 * <ul>
 *   <li><b>丢弃计数</b>：队列满时丢日志而不是阻塞任务，并把丢弃条数记进文件——
 *       宁可丢日志，也不能让日志拖死任务；但「丢了多少」必须留痕。</li>
 *   <li><b>轮转</b>：单文件超过 1 MB 轮转为 {@code engine.log.1}（只留一份），
 *       避免长期运行把存储写满。</li>
 *   <li><b>脱敏</b>：落盘前过滤 API Key / 令牌形态的字符串（{@code sk-…}、{@code LTAI…}、
 *       {@code Bearer …}、{@code api_key=…}）。日志文件在外部存储，
 *       同机应用与用户文件管理器都读得到，凭据不能进去。</li>
 *   <li><b>多写入者共享</b>：引擎、无障碍守护、Activity 都往这一条队列写，
 *       避免多个 FileWriter 并发追加同一文件互相覆盖。</li>
 * </ul>
 */
final class EngineLog {

    private static final String TAG = "EngineLog";
    private static final String FILE_NAME = "engine.log";
    private static final String ROTATED_NAME = "engine.log.1";

    /** 队列容量：512 条足够吸收突发；满了就丢弃并计数。 */
    private static final int QUEUE_CAPACITY = 512;
    /** 单文件上限，超过就轮转。 */
    private static final long MAX_BYTES = 1L << 20;
    /** 单行上限：异常信息可能带超长请求体，截断避免一条日志撑爆文件。 */
    private static final int MAX_LINE_CHARS = 2000;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static final Pattern P_SK = Pattern.compile("(?i)sk-[A-Za-z0-9_\\-]{6,}");
    private static final Pattern P_AK = Pattern.compile("(?i)\\bLTAI[A-Za-z0-9]{6,}");
    private static final Pattern P_BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}");
    private static final Pattern P_KV = Pattern.compile(
            "(?i)((?:api_?key|access_?key_?secret|secret|token|password)\"?\\s*[:=]\\s*\"?)([A-Za-z0-9._\\-/]{8,})");

    private static volatile EngineLog instance;

    /** 进程内单例（同一进程共用一个文件句柄与一条队列）。 */
    static EngineLog get(android.content.Context ctx) {
        EngineLog local = instance;
        if (local == null) {
            synchronized (EngineLog.class) {
                local = instance;
                if (local == null) {
                    android.content.Context appCtx = ctx.getApplicationContext();
                    File dir = appCtx.getExternalFilesDir(null);
                    if (dir == null) dir = appCtx.getFilesDir();
                    local = new EngineLog(new File(dir, FILE_NAME));
                    instance = local;
                }
            }
        }
        return local;
    }

    private final File file;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writer;

    private EngineLog(File file) {
        this.file = file;
        writer = new Thread(this::loop, "engine-log");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * 入队一条日志（非阻塞，可在任意线程调用）。
     *
     * @return true = 已入队；false = 队列满被丢弃（丢弃计数会写进文件）
     */
    boolean append(String line) {
        if (!running.get()) return false;
        if (line == null) return false;
        if (queue.offer(prepare(line))) return true;
        dropped.incrementAndGet();
        return false;
    }

    /** 落盘线程收尾（进程退出/测试用；正常路径不需要调用）。 */
    void shutdown() {
        running.set(false);
        writer.interrupt();
    }

    /** 队列里当前积压条数（诊断用）。 */
    int backlog() {
        return queue.size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void loop() {
        BufferedWriter w = null;
        while (running.get() || !queue.isEmpty()) {
            String line;
            try {
                line = queue.poll(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                if (w == null) w = new BufferedWriter(new FileWriter(file, true));
                long lost = dropped.getAndSet(0);
                if (lost > 0) {
                    write(w, "[日志] 写入队列已满，丢弃 " + lost + " 条（任务不受影响）");
                }
                if (line != null) {
                    write(w, line);
                    w.flush();
                }
                if (file.length() > MAX_BYTES) {
                    closeQuietly(w);
                    w = null;
                    rotate();
                }
            } catch (Exception e) {
                // 不再静默：落盘失败的**类型**要留在 logcat（不含任何用户数据）
                android.util.Log.w(TAG, "日志落盘失败，1 秒后重试："
                        + e.getClass().getSimpleName());
                closeQuietly(w);
                w = null;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        closeQuietly(w);
    }

    private void write(BufferedWriter w, String line) throws Exception {
        w.write(stamp());
        w.write(' ');
        w.write(line);
        w.write('\n');
    }

    private void rotate() {
        try {
            File rotated = new File(file.getParentFile(), ROTATED_NAME);
            if (rotated.exists() && !rotated.delete()) {
                android.util.Log.w(TAG, "旧轮转文件删除失败，跳过本次轮转");
                return;
            }
            if (!file.renameTo(rotated)) {
                android.util.Log.w(TAG, "日志轮转失败（继续追加原文件）");
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "日志轮转异常：" + e.getClass().getSimpleName());
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w == null) return;
        try {
            w.flush();
            w.close();
        } catch (Exception ignored) {
        }
    }

    private static String stamp() {
        synchronized (TS) {
            return TS.format(new Date());
        }
    }

    /** 截断 + 脱敏 + 去掉换行（多行日志会破坏「一行一条」的可读性）。 */
    static String prepare(String line) {
        String s = line.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > MAX_LINE_CHARS) {
            s = s.substring(0, MAX_LINE_CHARS) + "…（已截断）";
        }
        return redact(s);
    }

    /** 抹掉日志里可能出现的凭据形态。 */
    static String redact(String s) {
        String out = P_SK.matcher(s).replaceAll("***");
        out = P_AK.matcher(out).replaceAll("***");
        out = P_BEARER.matcher(out).replaceAll("$1***");
        out = P_KV.matcher(out).replaceAll("$1***");
        return out;
    }
}
