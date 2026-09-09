package com.dsh.agentlite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局记忆（报告 §7 的无障碍化简版）。
 * 写入显式：任务框以「记一下」开头即写入，不进入执行循环；
 * 调用静默：记忆块注入系统提示词，模型直接用、不披露（报告 §7.2.2 的不对称设计）；
 * 管理可见：列表可查看/删除/清空（事后透明性兜底）。
 */
public final class MemoryStore {

    public static final class Item {
        public String time, text;
    }

    private MemoryStore() { }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "memories.json");
    }

    public static synchronized void add(Context ctx, String text) {
        List<Item> all = list(ctx);
        Item it = new Item();
        it.time = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.US).format(new java.util.Date());
        it.text = text;
        all.add(it);
        save(ctx, all);
    }

    public static synchronized List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            JSONArray arr = new JSONArray(new String(buf, 0, off, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.time = o.optString("time");
                it.text = o.optString("text");
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void delete(Context ctx, int index) {
        List<Item> all = list(ctx);
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            save(ctx, all);
        }
    }

    public static synchronized void clear(Context ctx) {
        save(ctx, new ArrayList<>());
    }

    private static void save(Context ctx, List<Item> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : all) {
                JSONObject o = new JSONObject();
                o.put("time", it.time);
                o.put("text", it.text);
                arr.put(o);
            }
            FileWriter w = new FileWriter(file(ctx), false);
            w.write(arr.toString());
            w.close();
        } catch (Exception ignored) { }
    }

    /**
     * 注入系统提示词的记忆块（静默调用：明示模型不要披露使用了记忆）。
     * 最新 15 条、总量封顶 1000 字，空记忆返回空串。
     */
    public static String promptBlock(Context ctx) {
        List<Item> all = list(ctx);
        if (all.isEmpty()) return "";
        StringBuilder b = new StringBuilder(
                "\n\n【用户记忆】以下是用户此前明确要求你记住的事实与偏好。"
                        + "执行任务时静默使用（如自动带出口味、地址等），不要向用户提及你使用了记忆：\n");
        int count = 0;
        for (int i = all.size() - 1; i >= 0 && count < 15; i--, count++) {
            String t = all.get(i).text;
            if (b.length() + t.length() > 1000) break;
            b.append("- ").append(t).append('\n');
        }
        return b.toString();
    }
}
