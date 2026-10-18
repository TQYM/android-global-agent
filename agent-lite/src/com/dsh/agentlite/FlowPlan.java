// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作记忆库的**纯逻辑核心**：把「一次成功的任务」压成可复现的步骤序列，并判断
 * 「这次的步骤还能不能照做」。
 *
 * <p>与 {@link FuzzyFlow} 同一模式：零 android、零 org.json 依赖，能脱离设备在桌面 JVM
 * 直跑回归（见 {@code tests/FlowMemoryTest.java}）。Android 侧的胶水——读 {@code JSONObject}、
 * 落盘 {@code flowmem.json}、调 {@code exec()}——全在 {@link FlowMemory} 与
 * {@code AgentEngine}，那边只做搬运，不做判断。
 *
 * <p><b>为什么要有这个类</b>：用户要的是「每一步成功执行的操作进记忆库，之后直接复现就能完成」。
 * 复现的成败几乎全部压在两个判断上——「这条记录现在还该不该信」与「这一步在当前屏幕上到底
 * 落在哪个节点」——所以这两个判断被刻意做成可单测的纯函数，而不是散在引擎的循环里靠日志猜。
 *
 * <p><b>三条硬边界</b>（都在本类里可执行地表达，而不是只写在文档里）：
 * <ol>
 *   <li><b>只记可复现动作</b>：{@link #REPLAYABLE} 白名单之外的动作（跨设备 {@code remote}、
 *       {@code list_apps} 这类信息动作、{@code ask/fail/done} 控制动作）一旦出现，
 *       整条流程**不入库**（{@link #isReplayable}）——半条流程比没有流程更危险：复现时
 *       缺步会在用户毫无察觉的情况下走错分支。</li>
 *   <li><b>凭据不入库</b>：输入内容是验证码/密码形态时整条流程不入库（{@link #hasCredential}）。
 *       记忆库是本地文件，但"本地"不等于"可以存密码"。</li>
 *   <li><b>失败即贬值</b>：{@link #shouldRetire} 判定记录连续失败到阈值就该退场，
 *       避免一条过时路径把用户反复带进同一条死路。</li>
 * </ol>
 */
public final class FlowPlan {

    /** 结构版本：字段语义变化时递增（与 {@link ActionSchema#SCHEMA_VERSION} 同思路）。 */
    public static final int VERSION = 1;

    /** 单条流程的步骤上限：超出的任务太复杂，照做的价值低于风险。 */
    public static final int MAX_STEPS = 12;

    /** 记忆库容量（设计方案 §4.4：最多 50 条，LRU 淘汰）。 */
    public static final int MAX_RECORDS = 50;

    /** 命中保鲜期：超过 24h 没成功过的记录不再直接命中（设计方案 R-4）。 */
    public static final long FRESH_MS = 24 * 60 * 60 * 1000L;

    /** 连续失败多少次后退役。 */
    public static final int MAX_FAILS = 2;

    /**
     * 可复现动作白名单。
     *
     * <p>判定标准只有一条：**这个动作"照做"在语义上永远安全且与上下文无关**。
     * 所以 {@code app}/{@code tap}/{@code text} 这类界面动作在列，而：
     * <ul>
     *   <li>{@code remote}——指向另一台设备的副作用，照做会重复下发；</li>
     *   <li>{@code open_api}——支付/短信/导航等直达通道，参数语义由模型按当次语境生成，
     *       照抄旧参数等于把上次的金额/收款人再执行一遍；</li>
     *   <li>{@code open_url}——外部 Intent/DeepLink 通道，与 {@code open_api} 同类
     *       （设计规范 §4.4 R-5）：照抄旧 URL 等于把上次的 {@code tel:}、应用 Scheme、
     *       带 token 的查询参数**不经模型确认**再打开一遍，且 URL 会明文落进本地
     *       记忆库文件（2026-10-02 审计 P1-1，已从白名单移除）；</li>
     *   <li>{@code list_apps}/{@code open_api} 这类**信息动作**——照做没有副作用但也没有意义，
     *       且它们的存在说明任务当时依赖了屏幕外的信息，流程本身不可复现；</li>
     *   <li>{@code ask}——复现时不该再问用户一遍（问题本身是模型当次生成的）。</li>
     * </ul>
     */
    public static final Set<String> REPLAYABLE = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "app", "tap", "longpress", "text", "key", "back", "home",
                    "edge_back", "swipe", "scroll", "wait")));

    private FlowPlan() { }

    // ======================= 数据结构 =======================

    /** 一步「照做」所需的全部信息：动作 + 语义锚点 + 兜底坐标。 */
    public static final class Step {
        /** 动作名（必须命中 {@link #REPLAYABLE}）。 */
        public String act = "";
        /** {@code app} 的目标包名。 */
        public String pkg = "";
        /** {@code text} 的输入内容 / {@code open_url} 的地址。 */
        public String text = "";
        /** 语义锚点：被执行节点的可见文本（归一化后比较）。 */
        public String anchor = "";
        /** 语义锚点：节点 resource-id（最稳的一档）。 */
        public String rid = "";
        /** 语义锚点：节点类名（弱锚点，仅在文本缺失时用）。 */
        public String cls = "";
        /** {@code key} 的键码（-1 = 未使用）。 */
        public int key = -1;
        /** {@code scroll} 的方向 / {@code edge_back} 的边。 */
        public String arg = "";
        /** 归一化点坐标（0~1）；-1 表示该步没有点。 */
        public double px = -1, py = -1;
        /** {@code swipe} 终点归一化坐标。 */
        public double px2 = -1, py2 = -1;
        /** {@code longpress}/{@code swipe} 的时长（ms，0 = 默认）。 */
        public int dur;
        /** 执行后前台包名（复验用；空 = 当时取不到）。 */
        public String pkgAfter = "";

        /** 这一步是否带点坐标（决定复现时能否用坐标兜底）。 */
        public boolean hasPoint() {
            return px >= 0 && py >= 0;
        }
    }

    /** 一条操作记忆：指纹 + 步骤序列 + 命中统计。 */
    public static final class Record {
        /** 主指纹：归一化任务文本精确匹配（零模型往返即可命中）。 */
        public String fp = "";
        /** 骨架指纹：转译后的 intent + 骨架（同款不同参也能命中）。 */
        public String fpSkel = "";
        /** 原始任务文本（UI 回显用）。 */
        public String task = "";
        /** 归一化任务文本（指纹来源，保留以便排障与重算）。 */
        public String normalized = "";
        public String intent = "";
        /** 主要目标包名（步骤里第一个 app 动作，UI 展示用）。 */
        public String app = "";
        public long created, lastUsed;
        public int hits, oks, fails;
        public final List<Step> steps = new ArrayList<>();

        /** 面向用户的一行摘要（记忆库页面用）。 */
        public String label() {
            StringBuilder b = new StringBuilder();
            b.append(steps.size()).append(" 步");
            if (hits > 0) b.append(" · 复用 ").append(hits).append(" 次");
            if (oks > 0) b.append(" · 成功 ").append(oks);
            if (fails > 0) b.append(" · 失败 ").append(fails);
            return b.toString();
        }
    }

    // ======================= 入库前的把关 =======================

    /**
     * 整条流程是否可入库：每一步都在白名单内、步数在 1~{@link #MAX_STEPS}、且不含凭据。
     *
     * <p>返回不可入库的**原因**（空串 = 可入库）：拒绝入库时日志要能说清为什么，
     * 否则「记忆库怎么不涨」会变成只能靠猜的排障题。
     */
    public static String rejectReason(List<Step> steps) {
        if (steps == null || steps.isEmpty()) return "没有可复现的步骤";
        if (steps.size() > MAX_STEPS) return "步骤过多（" + steps.size() + " > " + MAX_STEPS + "）";
        for (Step s : steps) {
            if (s == null || !REPLAYABLE.contains(s.act)) {
                return "含不可复现动作：" + (s == null ? "null" : s.act);
            }
            if (hasCredential(s.text)) return "输入内容疑似凭据，拒绝入库";
        }
        return "";
    }

    /** 是否可入库。 */
    public static boolean isReplayable(List<Step> steps) {
        return rejectReason(steps).isEmpty();
    }

    /**
     * 疑似凭据：验证码 / 密码 / 银行卡形态的输入内容。
     *
     * <p>刻意做成**宽进严出**的保守判定：宁可漏记一条正常输入，也不能把验证码写进本地文件
     * ——记忆库是明文 JSON，任何"以后再说"的安全债都会先变成用户的实际损失。
     */
    public static boolean hasCredential(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        if (t.contains("密码") || t.contains("验证码") || t.contains("口令")) return true;
        // 纯数字 4 位以上：验证码、支付密码、卡号尾号的高频形态
        boolean digits = true;
        int d = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') { d++; continue; }
            if (c == ' ' || c == '-') continue;      // 卡号常带分隔符
            digits = false;
            break;
        }
        return digits && d >= 4;
    }

    // ======================= 指纹 =======================

    /** 主指纹：归一化任务的精确指纹（同一句话再来一次 → 零模型往返命中）。 */
    public static String exactFp(String normalized) {
        return FuzzyFlow.fingerprint("exact", normalized == null ? "" : normalized);
    }

    /** 骨架指纹：同款不同参（「点奶茶」vs「点咖啡」）的指纹，转译后才拿得到。 */
    public static String skeletonFp(String intent, String skeleton) {
        return FuzzyFlow.fingerprint(intent == null ? "" : intent,
                skeleton == null ? "" : skeleton);
    }

    /** 任务文本归一化（转译前的本地规则，与 {@code planTask} 用的是同一条链）。 */
    public static String normalize(String task) {
        if (task == null) return "";
        return TextNormalizer.correctTypos(TextNormalizer.normalize(task), null);
    }

    // ======================= 命中与退役 =======================

    /**
     * 记录是否还「新鲜」到可以直接照做。
     *
     * <p>三种情况不再命中：从未成功过、超过保鲜期、已退役。
     * 注意判定用的是 {@code lastUsed}——失败也会刷新它，所以一条**一直失败**的记录
     * 不会因为"时间新"而被反复命中（它由 {@link #shouldRetire} 拦下）。
     */
    public static boolean isFresh(Record r, long now) {
        if (r == null || r.steps.isEmpty()) return false;
        if (shouldRetire(r)) return false;
        if (r.oks <= 0) return false;
        long base = Math.max(r.lastUsed, r.created);
        return now - base <= FRESH_MS;
    }

    /** 是否该退役：连续失败到阈值。成功一次即清零，避免"历史失败"永久污名化一条可用路径。 */
    public static boolean shouldRetire(Record r) {
        return r != null && r.fails >= MAX_FAILS;
    }

    /** 主指纹命中（同一句话）。返回记录，无命中返回 null。 */
    public static Record matchExact(List<Record> all, String normalized, long now) {
        String fp = exactFp(normalized);
        if (fp.isEmpty()) return null;
        for (Record r : all) {
            if (fp.equals(r.fp) && isFresh(r, now)) return r;
        }
        return null;
    }

    /** 骨架指纹命中（同款不同参）。 */
    public static Record matchSkeleton(List<Record> all, String intent, String skeleton, long now) {
        if (intent == null || intent.isEmpty() || skeleton == null || skeleton.isEmpty()) return null;
        String fp = skeletonFp(intent, skeleton);
        if (fp.isEmpty()) return null;
        for (Record r : all) {
            if (fp.equals(r.fpSkel) && isFresh(r, now)) return r;
        }
        return null;
    }

    /**
     * 容量控制：超出 {@link #MAX_RECORDS} 时淘汰**最该被忘掉**的记录。
     *
     * <p>淘汰顺序刻意不是纯 LRU：先扔退役的，再扔从未成功过的，最后才按最久未用淘汰。
     * 纯 LRU 会把"刚失败两次"的记录因为"刚用过"而留在库里，挤掉真正好用的老记录。
     *
     * @return 实际被淘汰的条数
     */
    public static int evict(List<Record> all) {
        int removed = 0;
        while (all.size() > MAX_RECORDS) {
            int victim = -1;
            for (int i = 0; i < all.size(); i++) {
                if (victim < 0) { victim = i; continue; }
                if (worse(all.get(i), all.get(victim))) victim = i;
            }
            if (victim < 0) break;
            all.remove(victim);
            removed++;
        }
        return removed;
    }

    private static boolean worse(Record a, Record b) {
        if (shouldRetire(a) != shouldRetire(b)) return shouldRetire(a);
        if ((a.oks <= 0) != (b.oks <= 0)) return a.oks <= 0;
        if (a.lastUsed != b.lastUsed) return a.lastUsed < b.lastUsed;
        return a.created < b.created;
    }

    // ======================= 锚点定位（复现的核心） =======================

    /**
     * 在当前屏幕节点里找到这一步该点的节点，返回其 {@code index}；找不到返回 -1。
     *
     * <p>优先级刻意是 **rid → 文本相等 → 文本包含 → 类名（唯一时）**：
     * 界面会改版，但改版最常动的是位置与措辞，最少动的是 resource-id 与语义文本。
     * 每一步都要求唯一命中——多命中时选离上次坐标最近的那个；坐标也没有（纯视觉步骤）
     * 就判定为"定位不到"，交给调用方用比例坐标兜底或放弃复现。
     *
     * <p>返回 -1 是**正常结果**而不是错误：工程上必须假设界面会变，所以调用方
     * （{@code AgentEngine.replay}）拿到 -1 后先退到比例坐标，再失败才回退模型规划。
     */
    public static int matchAnchor(Step s, List<NodeInfo> nodes, int screenW, int screenH) {
        if (s == null || nodes == null || nodes.isEmpty()) return -1;

        if (!s.rid.isEmpty()) {
            int hit = pick(nodes, s, true, false, false, screenW, screenH);
            if (hit >= 0) return hit;
        }
        if (!s.anchor.isEmpty()) {
            int hit = pick(nodes, s, false, true, false, screenW, screenH);
            if (hit >= 0) return hit;
            hit = pick(nodes, s, false, false, true, screenW, screenH);
            if (hit >= 0) return hit;
        }
        if (!s.cls.isEmpty() && s.anchor.isEmpty() && s.rid.isEmpty()) {
            // 只有类名的步骤（罕见：多数是无文本图标）——仅当屏幕上该类名**唯一**时才认，
            // 否则「点第一个 ImageView」这种猜测比不做更糟。
            int only = -1;
            for (int i = 0; i < nodes.size(); i++) {
                NodeInfo n = nodes.get(i);
                if (n != null && s.cls.equals(n.cls)) {
                    if (only >= 0) return -1;
                    only = i;
                }
            }
            return only;
        }
        return -1;
    }

    private static int pick(List<NodeInfo> nodes, Step s, boolean byRid, boolean eq,
                            boolean contains, int screenW, int screenH) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        int hits = 0;
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo n = nodes.get(i);
            if (n == null) continue;
            boolean ok;
            if (byRid) {
                ok = s.rid.equals(n.id);
                if (ok && !s.anchor.isEmpty()) {
                    // rid 相同但文本明显不同（列表项复用 id）→ 不算命中
                    ok = similar(s.anchor, normText(n.label()));
                }
            } else if (eq) {
                ok = !text(n).isEmpty() && s.anchor.equals(text(n));
            } else {
                String t = text(n);
                // 包含匹配要求**两边都**至少 2 个字符：单字符锚点（「设」「确」）能同时命中
                // 「设置」与「设计」、「确认」与「取消」——这类误配会直接点错按钮。
                ok = s.anchor.length() >= 2 && t.length() >= 2
                        && (t.contains(s.anchor) || s.anchor.contains(t));
            }
            if (!ok) continue;
            hits++;
            double d = dist(s, n, screenW, screenH);
            if (best < 0 || d < bestDist) { best = i; bestDist = d; }
        }
        if (best < 0) return -1;
        // 多命中且无法用坐标区分（记录里没有坐标，或拿不到屏幕尺寸）→ 不猜：
        // 猜错的一步会让整条复现路径走偏，而"复现不了"只是退回模型规划，代价小得多。
        if (hits > 1 && bestDist == Double.MAX_VALUE && !byRid) return -1;
        return best;
    }

    private static boolean similar(String a, String b) {
        return a.equals(b) || (!b.isEmpty() && (b.contains(a) || a.contains(b)));
    }

    private static String text(NodeInfo n) {
        return normText(n.label());
    }

    /**
     * 记录点与节点中心的距离，**在归一化坐标系里比**（0~1）。
     *
     * <p>必须归一化：记忆库是跨设备、跨分辨率的（同一账号两台手机），拿像素比距离
     * 在换设备后会系统性地偏向屏幕上半部分。拿不到尺寸或任一方没有坐标 → 无穷大，
     * 即"无法据此择优"。
     */
    private static double dist(Step s, NodeInfo n, int screenW, int screenH) {
        if (!s.hasPoint() || screenW <= 0 || screenH <= 0) return Double.MAX_VALUE;
        double nx = n.cx / (double) screenW;
        double ny = n.cy / (double) screenH;
        double dx = nx - s.px, dy = ny - s.py;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 锚点文本归一化：比的是"同一段字"，不是"同一串字节"。 */
    public static String normText(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000') continue;
            if (c == '，' || c == '。' || c == '、' || c == '：' || c == '！'
                    || c == '？' || c == '\u201c' || c == '\u201d'
                    || c == '\u2018' || c == '\u2019') continue;
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    // ======================= 面向日志与 UI 的渲染 =======================

    /** 一条流程的一行中文概要（日志与卡片用，零技术词）。 */
    public static String describe(Record r) {
        if (r == null) return "";
        StringBuilder b = new StringBuilder();
        b.append("记忆库：").append(r.steps.size()).append(" 步");
        for (Step s : r.steps) {
            b.append(" → ").append(stepLabel(s));
            if (b.length() > 160) { b.append(" → …"); break; }
        }
        return b.toString();
    }

    /** 单步的中文标签（日志用）。 */
    public static String stepLabel(Step s) {
        if (s == null) return "?";
        switch (s.act) {
            case "app": return "打开" + (s.pkg.isEmpty() ? "应用" : s.pkg);
            case "tap": return "点击" + (s.anchor.isEmpty() ? "坐标" : s.anchor);
            case "longpress": return "长按" + (s.anchor.isEmpty() ? "坐标" : s.anchor);
            case "text": return "输入文字";
            case "key": return "按键 " + s.key;
            case "back": return "返回";
            case "home": return "回桌面";
            case "edge_back": return "边缘返回";
            case "swipe": return "滑动";
            case "scroll": return "翻页";
            case "wait": return "等待";
            case "open_url": return "打开链接";
            default: return s.act;
        }
    }
}
