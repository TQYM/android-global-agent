// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作记忆库（流程记忆）的落盘门面。
 *
 * <p>与 {@link MemoryStore} 的分工，是本类最需要说清的一件事：
 * <ul>
 *   <li>{@code MemoryStore} 存的是**用户显式说出口的事实**（「记一下我家地址是…」），
 *       它注入系统提示词、由模型在需要时自己取用；</li>
 *   <li>{@code FlowMemory} 存的是**引擎自己走通过的操作序列**（先打开微信 → 点搜索 → 输入…），
 *       它不进提示词，而是在下一次同款任务时被**直接照做**——这正是「把每一步成功执行的
 *       操作放进记忆库，之后直接复现就能完成操作」的落地。</li>
 * </ul>
 *
 * <p><b>判断全在别处</b>：能不能入库、还新不新鲜、这一步该落在哪个节点，全部由纯逻辑类
 * {@link FlowPlan} 决定（那边可桌面单测）。本类只做三件搬运：读写 {@code flowmem.json}、
 * 更新命中统计、给 UI 列出条目。
 *
 * <p><b>不上云</b>：刻意**不接** {@link SyncClient}（与设计方案 §4.3 的实体账本同一姿态）。
 * {@code MemoryStore} 上云是因为那是用户明示要求记住的内容；操作记忆含高频操作轨迹，
 * 隐私代价高于同步收益。用户若要换机，宁可重新走一遍。
 */
public final class FlowMemory {

    private FlowMemory() { }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "flowmem.json");
    }

    // ======================= 读 =======================

    /** 全部记录（按写入顺序；越靠后越新）。 */
    public static synchronized List<FlowPlan.Record> list(Context ctx) {
        List<FlowPlan.Record> out = new ArrayList<>();
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
                FlowPlan.Record r = parse(arr.getJSONObject(i));
                // open_url 已移出可复现白名单（R-5，2026-10-02 审计 P1-1）：
                // 旧库里遗留的含链接路径不再加载，防止经旧记录绕过白名单回放外部 Intent。
                // 不加载即不会被命中；下次任意 save 时自然从文件中清除。
                if (hasOpenUrl(r)) {
                    android.util.Log.w("FlowMemory", "跳过含 open_url 步骤的旧记录（R-5 禁止回放外部链接）");
                    continue;
                }
                out.add(r);
            }
        } catch (Exception e) {
            // 文件损坏 → 备份现场后当作空库：记忆库是加速手段，读不出来就不加速，
            // 绝不能让一条坏记录把整个任务拦死在启动阶段。备份文件让用户与排障
            // 能区分「没有记录」与「记录损坏」（此前两者不可区分，2026-10-02 审计 P1-B）。
            try {
                File f = file(ctx);
                if (f.exists()) {
                    File bak = new File(f.getParentFile(), "flowmem.json.corrupt");
                    if (!bak.exists() && f.renameTo(bak)) {
                        android.util.Log.w("FlowMemory",
                                "记忆库文件损坏，已备份为 flowmem.json.corrupt，按空库继续: " + e);
                    }
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** 条数（UI 展示用）。 */
    public static synchronized int size(Context ctx) {
        return list(ctx).size();
    }

    /**
     * 按任务文本找一条可直接照做的记录；找不到返回 {@code null}。
     *
     * <p>用**归一化后的任务文本**做主指纹：同一句话再说一次（含 ASR 同音字差异）
     * 就能零模型往返命中——这是"直接复现"最快的路径。
     */
    public static synchronized FlowPlan.Record match(Context ctx, String task, long now) {
        return FlowPlan.matchExact(list(ctx), FlowPlan.normalize(task), now);
    }

    /** 按转译骨架找同款流程（同款不同参：「点奶茶」→「点咖啡」）。 */
    public static synchronized FlowPlan.Record matchSkeleton(Context ctx, String intent,
                                                            String skeleton, long now) {
        return FlowPlan.matchSkeleton(list(ctx), intent, skeleton, now);
    }

    // ======================= 写 =======================

    /**
     * 任务以 {@code done} 收尾时入库。
     *
     * @return 入库/更新成功的记录；被拒时返回 {@code null}（拒绝原因见 {@link #rejectReason}）
     */
    public static synchronized FlowPlan.Record commit(Context ctx, String task, String normalized,
                                                      String intent, String fpSkel,
                                                      List<FlowPlan.Step> steps) {
        String why = FlowPlan.rejectReason(steps);
        if (why != null && !why.isEmpty()) return null;
        if (normalized == null || normalized.isEmpty()) return null;

        List<FlowPlan.Record> all = list(ctx);
        String fp = FlowPlan.exactFp(normalized);
        if (fp.isEmpty()) return null;

        FlowPlan.Record hit = null;
        for (FlowPlan.Record r : all) {
            if (fp.equals(r.fp)) { hit = r; break; }
        }
        long now = System.currentTimeMillis();
        if (hit == null) {
            hit = new FlowPlan.Record();
            hit.fp = fp;
            hit.created = now;
            all.add(hit);
        }
        hit.task = task == null ? "" : task;
        hit.normalized = normalized;
        hit.intent = intent == null ? "" : intent;
        // 骨架指纹只在转译成功时才有；拿不到就保留旧值（旧值仍可能命中同款任务）
        if (fpSkel != null && !fpSkel.isEmpty()) hit.fpSkel = fpSkel;
        hit.steps.clear();
        hit.steps.addAll(steps);
        hit.app = mainApp(steps);
        hit.lastUsed = now;
        hit.oks++;
        hit.fails = 0;              // 成功一次即清零：路径本身被证明是对的
        FlowPlan.evict(all);
        save(ctx, all);
        return hit;
    }

    /** 记录一次命中（复现即将开始）。 */
    public static synchronized void markHit(Context ctx, String fp) {
        update(ctx, fp, r -> r.hits++);
    }

    /** 记录一次复现成功。 */
    public static synchronized void markOk(Context ctx, String fp) {
        update(ctx, fp, r -> { r.oks++; r.fails = 0; r.lastUsed = System.currentTimeMillis(); });
    }

    /**
     * 记录一次复现失败。
     *
     * <p>失败只贬值不删除：同一条路径可能是"这次界面恰好变了"，所以给它
     * {@link FlowPlan#MAX_FAILS} 次机会；到了阈值由 {@link FlowPlan#shouldRetire}
     * 让它自然退场（仍然留在库里可查可删，只是不再被命中）。
     */
    public static synchronized void markFail(Context ctx, String fp) {
        update(ctx, fp, r -> { r.fails++; r.lastUsed = System.currentTimeMillis(); });
    }

    /** 删除单条（UI 点按删除）。 */
    public static synchronized void delete(Context ctx, int index) {
        List<FlowPlan.Record> all = list(ctx);
        if (index >= 0 && index < all.size()) {
            all.remove(index);
            save(ctx, all);
        }
    }

    /** 清空整个操作记忆库。 */
    public static synchronized void clear(Context ctx) {
        save(ctx, new ArrayList<>());
    }

    private interface Edit { void apply(FlowPlan.Record r); }

    private static void update(Context ctx, String fp, Edit e) {
        if (fp == null || fp.isEmpty()) return;
        List<FlowPlan.Record> all = list(ctx);
        boolean changed = false;
        for (FlowPlan.Record r : all) {
            if (fp.equals(r.fp)) { e.apply(r); changed = true; break; }
        }
        if (changed) save(ctx, all);
    }

    // ======================= 注入与展示 =======================

    /**
     * 命中骨架时给主循环的一行提示（设计方案 §5.5 的【上次同款】）。
     *
     * <p>刻意**只作参考**：复现走的是另一条通道（{@link AgentEngine} 直接照做）；
     * 这里注入的是给模型的"上次这条路走通过"，让它在回退到规划后少绕弯。
     * 明确写上"以屏幕实况为准"，与守则 §3 一致。
     */
    public static String hintBlock(FlowPlan.Record r) {
        if (r == null || r.steps.isEmpty()) return "";
        String when = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.US).format(new java.util.Date(r.lastUsed));
        StringBuilder b = new StringBuilder("\n\n【上次同款】");
        b.append(when).append("成功过一次，路径（仅供参考）：");
        int n = Math.min(r.steps.size(), FlowPlan.MAX_STEPS);
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(" → ");
            b.append(FlowPlan.stepLabel(r.steps.get(i)));
        }
        b.append("。屏幕实况与之不符时以实况为准。");
        String out = b.toString();
        return out.length() <= 240 ? out : out.substring(0, 240);
    }

    /** 面向记忆库页面的一行摘要。 */
    public static String label(FlowPlan.Record r) {
        StringBuilder b = new StringBuilder();
        if (!r.app.isEmpty()) b.append(appName(r.app)).append(" · ");
        b.append(r.steps.size()).append(" 步");
        if (r.hits > 0) b.append(" · 复用 ").append(r.hits).append(" 次");
        if (r.fails > 0) b.append(" · 最近失败 ").append(r.fails);
        if (FlowPlan.shouldRetire(r)) b.append("（已失效）");
        return b.toString();
    }

    private static String appName(String pkg) {
        for (java.util.Map.Entry<String, String> e : AgentEngine.APP_NAMES.entrySet()) {
            if (e.getValue().equals(pkg)) return e.getKey();
        }
        int dot = pkg.lastIndexOf('.');
        return dot > 0 && dot < pkg.length() - 1 ? pkg.substring(dot + 1) : pkg;
    }

    private static String mainApp(List<FlowPlan.Step> steps) {
        for (FlowPlan.Step s : steps) {
            if ("app".equals(s.act) && s.pkg != null && !s.pkg.isEmpty()) return s.pkg;
        }
        return "";
    }

    /** 旧库迁移防线：记录里是否含 {@code open_url} 步骤（R-5 移出白名单后不再加载）。 */
    private static boolean hasOpenUrl(FlowPlan.Record r) {
        for (FlowPlan.Step s : r.steps) {
            if ("open_url".equals(s.act)) return true;
        }
        return false;
    }

    // ======================= 落盘 =======================

    private static void save(Context ctx, List<FlowPlan.Record> all) {
        File target = file(ctx);
        File tmp = new File(target.getParentFile(), "flowmem.json.tmp");
        try {
            JSONArray arr = new JSONArray();
            for (FlowPlan.Record r : all) arr.put(toJson(r));
            // 原子提交（2026-10-02 审计 P1-B）：临时文件 + fsync + rename——
            // 进程死亡/磁盘异常时最坏丢**一次增量**，不会把整个库截断成半个 JSON
            // （此前 FileWriter 直接覆盖，异常即整库蒸发，且失败完全不可见）。
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            out.write(arr.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(target)) {
                // 极端文件系统（部分厂商 FUSE 层）rename 覆盖失败：退回直写，仍保底可用。
                java.io.FileWriter w = new java.io.FileWriter(target, false);
                w.write(arr.toString());
                w.close();
            }
        } catch (Exception e) {
            // 写失败不抛：记忆库是加速手段，写不进去只意味着下次还要走模型，任务本身不受影响。
            // 但「不拦任务」不等于「不可见」——诊断日志必须留痕（此前是静默空操作）。
            android.util.Log.w("FlowMemory", "记忆库写入失败（本次增量丢失，任务不受影响）: " + e);
        }
    }

    private static JSONObject toJson(FlowPlan.Record r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", FlowPlan.VERSION);
        o.put("fp", r.fp);
        o.put("fp_skel", r.fpSkel);
        o.put("task", r.task);
        o.put("normalized", r.normalized);
        o.put("intent", r.intent);
        o.put("app", r.app);
        o.put("created", r.created);
        o.put("last_used", r.lastUsed);
        o.put("hits", r.hits);
        o.put("oks", r.oks);
        o.put("fails", r.fails);
        JSONArray ss = new JSONArray();
        for (FlowPlan.Step s : r.steps) {
            JSONObject so = new JSONObject();
            so.put("act", s.act);
            if (!s.pkg.isEmpty()) so.put("pkg", s.pkg);
            if (!s.text.isEmpty()) so.put("text", s.text);
            if (!s.anchor.isEmpty()) so.put("anchor", s.anchor);
            if (!s.rid.isEmpty()) so.put("rid", s.rid);
            if (!s.cls.isEmpty()) so.put("cls", s.cls);
            if (s.key >= 0) so.put("key", s.key);
            if (!s.arg.isEmpty()) so.put("arg", s.arg);
            if (s.hasPoint()) { so.put("px", s.px); so.put("py", s.py); }
            if (s.px2 >= 0 && s.py2 >= 0) { so.put("px2", s.px2); so.put("py2", s.py2); }
            if (s.dur > 0) so.put("dur", s.dur);
            if (!s.pkgAfter.isEmpty()) so.put("pkg_after", s.pkgAfter);
            ss.put(so);
        }
        o.put("steps", ss);
        return o;
    }

    private static FlowPlan.Record parse(JSONObject o) {
        FlowPlan.Record r = new FlowPlan.Record();
        r.fp = o.optString("fp");
        r.fpSkel = o.optString("fp_skel");
        r.task = o.optString("task");
        r.normalized = o.optString("normalized");
        r.intent = o.optString("intent");
        r.app = o.optString("app");
        r.created = o.optLong("created");
        r.lastUsed = o.optLong("last_used");
        r.hits = o.optInt("hits");
        r.oks = o.optInt("oks");
        r.fails = o.optInt("fails");
        JSONArray ss = o.optJSONArray("steps");
        if (ss != null) {
            for (int i = 0; i < ss.length(); i++) {
                JSONObject so = ss.optJSONObject(i);
                if (so == null) continue;
                FlowPlan.Step s = new FlowPlan.Step();
                s.act = so.optString("act");
                s.pkg = so.optString("pkg");
                s.text = so.optString("text");
                s.anchor = so.optString("anchor");
                s.rid = so.optString("rid");
                s.cls = so.optString("cls");
                s.key = so.has("key") ? so.optInt("key") : -1;
                s.arg = so.optString("arg");
                s.px = so.has("px") ? so.optDouble("px") : -1;
                s.py = so.has("py") ? so.optDouble("py") : -1;
                s.px2 = so.has("px2") ? so.optDouble("px2") : -1;
                s.py2 = so.has("py2") ? so.optDouble("py2") : -1;
                s.dur = so.optInt("dur");
                s.pkgAfter = so.optString("pkg_after");
                r.steps.add(s);
            }
        }
        return r;
    }
}
