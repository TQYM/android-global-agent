package com.dsh.agentlite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务记录归档（报告 §4.3.3 / §5.1.3 事后层）。
 * 完成/失败/终止三类终态统一落入 JSONL 档案，支撑事后回查与问责。
 */
public final class TaskHistory {

    public static final class Record {
        public String time, task, result, detail;
        public int steps;
        public String line() {
            return time + "  [" + result + "]  " + task
                    + "\n    " + detail + "（" + steps + " 步）";
        }
    }

    private TaskHistory() { }

    private static File file(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "task_history.jsonl");
    }

    public static synchronized void append(Context ctx, String task,
                                           String result, String detail, int steps) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date()));
            o.put("task", task);
            o.put("result", result);
            o.put("detail", detail);
            o.put("steps", steps);
            FileWriter w = new FileWriter(file(ctx), true);
            w.write(o.toString() + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    /** 全量记录，最新在前。 */
    public static synchronized List<Record> list(Context ctx) {
        List<Record> out = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            String[] lines = new String(buf, 0, off, "UTF-8").split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String ln = lines[i].trim();
                if (ln.isEmpty()) continue;
                JSONObject o = new JSONObject(ln);
                Record r = new Record();
                r.time = o.optString("time");
                r.task = o.optString("task");
                r.result = o.optString("result");
                r.detail = o.optString("detail");
                r.steps = o.optInt("steps");
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void clear(Context ctx) {
        try { file(ctx).delete(); } catch (Exception ignored) { }
    }
}
