// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模糊语义转译的**纯逻辑核心**（见 {@code docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md} §4、§5.4）。
 *
 * <p>本类承载「模型回什么 → 我们信不信 → 怎么喂给引擎」这条链上**所有可出错、且值得测**
 * 的判断，并且刻意做成纯 Java：零 android 依赖、零 org.json 依赖，能脱离设备在桌面 JVM
 * 直跑回归（{@link PurchaseFlow} 是同一模式）。Android 侧的胶水（调模型、落盘、接线）
 * 全在 {@link SemanticTranslator}，那边几乎没有判断逻辑，也就不用测。
 *
 * <p><b>为什么不用 org.json</b>：{@code org.json} 只在 Android 运行时存在，桌面 JVM 没有；
 * 而「从模型回复里抽 JSON」恰恰是这套链路最常出错的一环（带 markdown 围栏、前后废话、
 * 尾随逗号）。把它隔离在一个可测的纯函数里，比在设备上靠日志猜要划算得多。
 *
 * <p><b>置信姿态</b>：解析或校验不过 → 返回 {@code null}，由调用方回退到原有路径。
 * 本类**从不**替模型"猜一个差不多的值"——猜错会让引擎带着错误的理解跑完整条流程。
 */
public final class FuzzyFlow {

    /** 结构版本：字段语义变化时递增（与 {@link ActionSchema#SCHEMA_VERSION} 同思路）。 */
    public static final int SPEC_VERSION = 1;

    /** 受限意图表：未知输入一律落 {@link #INTENT_GENERAL}，保证指纹稳定与统计不失真。 */
    public static final String INTENT_GENERAL = "general";
    public static final List<String> INTENTS = Collections.unmodifiableList(Arrays.asList(
            "open_app", "search", "navigate", "play", "dial", "sms", "setting_toggle",
            "order_food", "pay_bill", "query_info", "read_screen", INTENT_GENERAL));

    /** 风险等级：与交互守则 §7.2 红线清单一一对应。 */
    public static final String RISK_NONE = "none";
    public static final List<String> RISKS = Collections.unmodifiableList(Arrays.asList(
            "none", "personal_info", "send", "delete", "money"));

    /** 槽位来源：**必须可追溯**，这是澄清与纠错的依据。 */
    public static final String SRC_UTTERANCE = "utterance";
    public static final String SRC_MEMORY = "memory";
    public static final String SRC_LEDGER = "ledger";
    public static final String SRC_DEFAULT = "default";

    /** 步骤守卫：{@code ask} 表示该步执行前必须取得用户明示许可。 */
    public static final String GUARD_NONE = "none";
    public static final String GUARD_ASK = "ask";

    /** 编译产物长度上限（设计方案 §13 R-5：不得挤占屏幕节点预算）。 */
    public static final int COMPILE_MAX_CHARS = 300;

    /** 步骤上限（超出截断，不判失败——多出来的步骤本就该由主循环按实况决定）。 */
    public static final int MAX_STEPS = 5;

    private FuzzyFlow() { }

    // ======================= 数据结构 =======================

    /** 一个槽位：值 + 来源 + 模型自评置信度。 */
    public static final class Slot {
        public String name = "";
        public String value = "";
        public String source = SRC_UTTERANCE;
        public double confidence = 0.5;
    }

    /** 一步路径建议。注意 JSON 键是 {@code do}，Java 里 {@code do} 是关键字，故字段名为 {@code what}。 */
    public static final class Step {
        public int n;
        public String what = "";
        public String channel = "ui";
        public String expect = "";
        public String guard = GUARD_NONE;
    }

    /** 结构化流程（对应设计方案 §4.1 的 FlowSpec）。 */
    public static final class Spec {
        public int v = SPEC_VERSION;
        public String intent = INTENT_GENERAL;
        public String goal = "";
        public String appName = "";
        public String appPkg = "";
        public String doneWhen = "";
        public String risk = RISK_NONE;
        public double confidence = 0.5;
        public final List<Slot> slots = new ArrayList<>();
        public final List<Step> steps = new ArrayList<>();
        public final List<String> mustAskBefore = new ArrayList<>();
        public final List<String> carryOver = new ArrayList<>();

        /** 低置信槽位（编译时作为「不确定项」提示给模型，P0 不做交互澄清）。 */
        public List<Slot> lowConfidenceSlots(double threshold) {
            List<Slot> out = new ArrayList<>();
            for (Slot s : slots) {
                if (!s.value.isEmpty() && s.confidence < threshold) out.add(s);
            }
            return out;
        }
    }

    // ======================= JSON 抽取与解析 =======================

    /**
     * 从模型回复里抽出第一个**括号平衡**的 JSON 对象。
     *
     * <p>实际回复常见三种噪声：markdown 围栏、前后解释文字、字符串里含大括号。
     * 前者靠"找第一个 {"跳过，后者靠扫描时跟踪字符串状态与转义——不能简单数括号。
     */
    public static String extractJsonObject(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return reply.substring(start, i + 1);
            }
        }
        return null;   // 括号不闭合（回复被截断）→ 交给调用方回退
    }

    /**
     * 解析 JSON 文本为 {@link Spec}。任何结构问题 → 返回 {@code null}（**绝不**产出半成品）。
     */
    public static Spec parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        Object root;
        try {
            root = new MiniJson(json).parseDocument();
        } catch (Exception e) {
            return null;
        }
        if (!(root instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) root;

        Spec s = new Spec();
        Object v = o.get("v");
        if (v instanceof Number) s.v = ((Number) v).intValue();

        s.intent = normIntent(str(o.get("intent")));
        s.goal = str(o.get("goal"));
        s.doneWhen = str(o.get("done_when"));
        s.risk = normRisk(str(o.get("risk")));
        if (o.get("confidence") instanceof Number) {
            s.confidence = clamp01(((Number) o.get("confidence")).doubleValue());
        }

        Object app = o.get("target_app");
        if (app instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) app;
            s.appName = str(a.get("name"));
            s.appPkg = str(a.get("pkg"));
        }

        parseSlots(o.get("slots"), s);
        parseSteps(o.get("steps"), s);
        s.mustAskBefore.addAll(strList(o.get("must_ask_before")));
        s.carryOver.addAll(strList(o.get("carry_over")));

        // 目标为空即无意义：与其让引擎带着空目标跑，不如判定转译失败回退原路径
        if (s.goal.isEmpty() && s.steps.isEmpty()) return null;
        return s;
    }

    private static void parseSlots(Object raw, Spec s) {
        if (!(raw instanceof Map)) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) raw;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Slot sl = new Slot();
            sl.name = e.getKey();
            Object val = e.getValue();
            if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> vm = (Map<String, Object>) val;
                sl.value = str(vm.get("value"));
                String src = str(vm.get("source"));
                sl.source = normSource(src);
                if (vm.get("confidence") instanceof Number) {
                    sl.confidence = clamp01(((Number) vm.get("confidence")).doubleValue());
                } else {
                    sl.confidence = 0.5;   // 未给置信度 → 保守视为不确定
                }
            } else {
                sl.value = str(val);       // 宽松形态：{"品类":"奶茶"}
                sl.source = SRC_UTTERANCE;
                sl.confidence = 0.5;
            }
            if (!sl.value.isEmpty()) s.slots.add(sl);
        }
    }

    private static void parseSteps(Object raw, Spec s) {
        if (!(raw instanceof List)) return;
        List<?> arr = (List<?>) raw;
        int auto = 0;
        for (Object item : arr) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) item;
            Step st = new Step();
            st.n = (m.get("n") instanceof Number) ? ((Number) m.get("n")).intValue() : ++auto;
            st.what = str(m.get("do"));
            if (st.what.isEmpty()) st.what = str(m.get("what"));
            st.channel = str(m.get("channel"));
            if (st.channel.isEmpty()) st.channel = "ui";
            st.expect = str(m.get("expect"));
            st.guard = GUARD_ASK.equals(str(m.get("guard"))) ? GUARD_ASK : GUARD_NONE;
            if (!st.what.isEmpty()) s.steps.add(st);
        }
    }

    // ======================= 校验 =======================

    /**
     * 契约校验，返回**具体问题列表**（空 = 通过）。
     *
     * <p>返回列表而不是布尔值：门禁与日志都需要知道"到底哪条不过"，
     * 而回退决策只看是否为空。
     */
    public static List<String> validate(Spec s) {
        List<String> errs = new ArrayList<>();
        if (s == null) {
            errs.add("spec 为空");
            return errs;
        }
        if (!INTENTS.contains(s.intent)) errs.add("intent 不在受限表内: " + s.intent);
        if (!RISKS.contains(s.risk)) errs.add("risk 非法: " + s.risk);
        if (s.goal.isEmpty() && s.steps.isEmpty()) errs.add("goal 与 steps 同时为空");
        if (s.confidence < 0 || s.confidence > 1) errs.add("confidence 越界: " + s.confidence);
        for (Slot sl : s.slots) {
            if (!isValidSource(sl.source)) errs.add("槽位来源非法: " + sl.name + "=" + sl.source);
            if (sl.confidence < 0 || sl.confidence > 1) errs.add("槽位置信度越界: " + sl.name);
        }
        for (Step st : s.steps) {
            if (!GUARD_NONE.equals(st.guard) && !GUARD_ASK.equals(st.guard)) {
                errs.add("步骤 guard 非法: " + st.guard);
            }
        }
        // ★ 硬约束：有风险的任务必须留下一个明示确认点。
        // 引擎敏感守卫只覆盖 2/4 组且纯视觉模式失效（守则 §7.1/§7.5），
        // 所以这条在转译层就卡死——宁可判转译失败走回退，也不产出"无确认点的支付流程"。
        if (!RISK_NONE.equals(s.risk)) {
            boolean hasAsk = false;
            for (Step st : s.steps) {
                if (GUARD_ASK.equals(st.guard)) { hasAsk = true; break; }
            }
            if (!hasAsk && s.mustAskBefore.isEmpty()) {
                errs.add("风险任务缺少确认点: risk=" + s.risk);
            }
        }
        return errs;
    }

    /** 是否通过校验。 */
    public static boolean isValid(Spec s) {
        return validate(s).isEmpty();
    }

    /**
     * 包名**形态**校验（真正的白名单过滤在 {@link SemanticTranslator}：必须命中已装应用）。
     * 这里只挡明显不是包名的东西，避免把模型编的"美团"当包名传给引擎。
     */
    public static boolean looksLikePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.indexOf('.') <= 0 || pkg.startsWith(".") || pkg.endsWith(".")) return false;
        if (pkg.indexOf(' ') >= 0 || pkg.indexOf('/') >= 0) return false;
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    // ======================= 编译 =======================

    /**
     * 编译成注入主循环首条 user message 的文本块（替换原【规划简报】位置）。
     *
     * <p>刻意**不含动作 JSON**：路径建议是参考不是命令，最后必须由主循环按屏幕实况
     * 一步一个动作地决策（守则 §3 环境态前置判断优先于任何预设路径）。
     */
    public static String compile(Spec s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        b.append("【任务理解】\n");
        if (!s.goal.isEmpty()) b.append("目标：").append(s.goal).append('\n');
        if (!s.appName.isEmpty()) {
            b.append("目标应用：").append(s.appName);
            if (!s.appPkg.isEmpty()) b.append("（").append(s.appPkg).append("）");
            b.append('\n');
        }
        if (!s.steps.isEmpty()) {
            b.append("路径建议：");
            int n = Math.min(s.steps.size(), MAX_STEPS);
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(" → ");
                b.append(s.steps.get(i).what);
            }
            b.append('\n');
        }
        if (!s.doneWhen.isEmpty()) b.append("完成判据：").append(s.doneWhen).append('\n');
        if (!RISK_NONE.equals(s.risk)) {
            b.append("风险等级：").append(riskLabel(s.risk)).append("——");
            if (!s.mustAskBefore.isEmpty()) {
                b.append("涉及「").append(join(s.mustAskBefore, "、")).append("」时必须先 ask 取得用户明示许可");
            } else {
                b.append("相关步骤执行前必须先 ask 取得用户明示许可");
            }
            b.append('\n');
        }
        List<Slot> low = s.lowConfidenceSlots(0.6);
        if (!low.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Slot sl : low) {
                if (names.length() > 0) names.append('、');
                names.append(sl.name).append('(').append(sl.value).append(')');
            }
            b.append("不确定项：").append(names)
                    .append("——若与实际不符，按屏幕实况修正或 ask 用户确认\n");
        }
        b.append("说明：以上路径建议仅供参考，不是命令；屏幕实况与之冲突时以实况为准。");
        String out = b.toString();
        return out.length() <= COMPILE_MAX_CHARS ? out : out.substring(0, COMPILE_MAX_CHARS);
    }

    private static String riskLabel(String risk) {
        switch (risk) {
            case "money": return "资金（支付/转账/扣费）";
            case "send": return "发送（消息/邮件）";
            case "delete": return "删除数据";
            case "personal_info": return "个人信息（密码/验证码绝不代填）";
            default: return risk;
        }
    }

    // ======================= 指纹与骨架（P2 流程库用，先落地纯函数） =======================

    /**
     * 把归一化文本里的槽位值替换为占位符，得到"同款流程"的可比骨架。
     *
     * <p>例：「点奶茶送到公司」→「点&lt;品类&gt;送到&lt;地址&gt;」，
     * 于是「点咖啡送到公司」命中同一骨架，可复用上次的路径。
     */
    public static String skeleton(String normalized, List<Slot> slots) {
        if (normalized == null) return "";
        String out = normalized;
        if (slots != null) {
            // 长值优先替换：先换「三分糖」再换「糖」，避免短值把长值切碎
            List<Slot> sorted = new ArrayList<>(slots);
            sorted.sort((a, b) -> Integer.compare(
                    b.value == null ? 0 : b.value.length(),
                    a.value == null ? 0 : a.value.length()));
            for (Slot sl : sorted) {
                if (sl.value == null || sl.value.isEmpty()) continue;
                if (!out.contains(sl.value)) continue;
                out = out.replace(sl.value, "<" + sl.name + ">");
            }
        }
        return TextNormalizer.collapseWhitespace(out);
    }

    /** 流程指纹：意图 + 骨架 的 sha1（十六进制），相同流程必得相同指纹。 */
    public static String fingerprint(String intent, String skeleton) {
        String seed = (intent == null ? "" : intent) + "|" + (skeleton == null ? "" : skeleton);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(seed.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 0xF, 16))
                    .append(Character.forDigit(x & 0xF, 16));
            return b.toString();
        } catch (Exception e) {
            return "";   // 算法缺失属环境异常：返回空指纹 = 永不命中，安全侧失败
        }
    }

    // ======================= 内部工具 =======================

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof String) return ((String) o).trim();
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return "";
    }

    private static List<String> strList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object x : (List<?>) raw) {
                String v = str(x);
                if (!v.isEmpty()) out.add(v);
            }
        } else {
            String v = str(raw);       // 宽松：单个字符串也接受
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String normIntent(String s) {
        return INTENTS.contains(s) ? s : INTENT_GENERAL;
    }

    private static String normRisk(String s) {
        return RISKS.contains(s) ? s : RISK_NONE;
    }

    private static String normSource(String s) {
        if (SRC_MEMORY.equals(s) || SRC_LEDGER.equals(s) || SRC_DEFAULT.equals(s)) return s;
        return SRC_UTTERANCE;
    }

    private static boolean isValidSource(String s) {
        return SRC_UTTERANCE.equals(s) || SRC_MEMORY.equals(s)
                || SRC_LEDGER.equals(s) || SRC_DEFAULT.equals(s);
    }

    private static double clamp01(double d) {
        if (Double.isNaN(d)) return 0.5;
        if (d < 0) return 0;
        if (d > 1) return 1;
        return d;
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) {
            if (b.length() > 0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }

    /**
     * 极简 JSON 解析器：只实现本场景需要的子集（对象/数组/字符串/数字/布尔/null）。
     *
     * <p>刻意不引入依赖——见类注释「为什么不用 org.json」。
     * 解析失败抛异常，由 {@link #parse} 统一转成 {@code null}。
     */
    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) { this.s = s; }

        Object parseDocument() throws Exception {
            skipWs();
            Object v = value();
            skipWs();
            return v;   // 尾随内容忽略（模型常在 JSON 后补一句解释）
        }

        private Object value() throws Exception {
            skipWs();
            if (i >= s.length()) throw new Exception("意外结束");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() throws Exception {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                       // '{'
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != '"') throw new Exception("键必须是字符串");
                String k = string();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw new Exception("缺少冒号");
                i++;
                m.put(k, value());
                skipWs();
                if (i >= s.length()) throw new Exception("对象未闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new Exception("对象中出现意外字符: " + c);
            }
        }

        private List<Object> array() throws Exception {
            List<Object> a = new ArrayList<>();
            i++;                       // '['
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(value());
                skipWs();
                if (i >= s.length()) throw new Exception("数组未闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return a; }
                throw new Exception("数组中出现意外字符: " + c);
            }
        }

        private String string() throws Exception {
            StringBuilder b = new StringBuilder();
            i++;                       // 开引号
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new Exception("\\u 转义不完整");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new Exception("非法转义: \\" + e);
                }
            }
            throw new Exception("字符串未闭合");
        }

        private Double number() throws Exception {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') i++;
                else break;
            }
            if (start == i) throw new Exception("非法值");
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new Exception("非法数字");
            }
        }

        private void expect(String lit) throws Exception {
            if (!s.startsWith(lit, i)) throw new Exception("非法字面量");
            i += lit.length();
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }
    }
}
