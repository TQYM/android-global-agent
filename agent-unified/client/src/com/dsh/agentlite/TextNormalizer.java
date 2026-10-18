// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模糊输入的**归一化层**（转译管线第 ① 层，见
 * {@code docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md} §5.1）。
 *
 * <p><b>职责边界</b>：只做**确定性**变换（规则表），不做语义改写——「用户到底想干什么」
 * 是下游转译层（{@link SemanticTranslator}）的事。守住这条边界，本类就是纯函数：
 * 零 android 依赖、零 org.json 依赖，可脱离设备在桌面 JVM 直跑回归
 * （与 {@link PurchaseFlow} 同一模式，见设计方案 §7）。
 *
 * <p><b>红线：不得删除否定词。</b>「别打开微信」删掉「别」就变成「打开微信」——
 * 一个字把任务整个反过来，而用户看到的还是「任务已完成」。所以本类采取两条保守策略：
 * <ol>
 *   <li>填充词表是**白名单制**：只删明确列出的词，绝不按"看起来像废话"模糊匹配；</li>
 *   <li>原文一旦含否定词，**整条填充词规则全部放弃**——宁可少删一句废话，
 *       也不冒把否定删掉的风险。代价只是提示词里多几个字，收益是这类事故为零。</li>
 * </ol>
 *
 * <p>本类与 {@code tests/FuzzyTranslatorTest.java}（桌面 JVM）配套；门禁断言 FF-5
 * 静态检查「否定词保护」这条分支是否还在。
 */
public final class TextNormalizer {

    /**
     * 句首填充词白名单（**按长度降序**匹配，先长后短，避免「请帮我」被「请」截断）。
     *
     * <p>只收句首、且**在任何语境下都无实义**的词。刻意**不收**「给我」「要」「想」——
     * 「给我妈发个消息」里的「给我」是必要成分，删掉会把收件人抹掉。
     */
    private static final String[] PREFIX_FILLERS = {
            "麻烦你帮我", "麻烦帮我", "你能不能帮我", "可以帮我", "能不能帮我",
            "你帮我", "帮我", "帮忙", "麻烦你", "麻烦", "请你", "请",
            "那个啥", "那个", "嗯", "呃", "哎", "喂", "你好", "我想要", "我想", "我要",
    };

    /** 否定词：命中即放弃填充词删除（见类注释红线）。 */
    private static final String[] NEGATIONS = {
            "别", "不要", "不用", "甭", "勿", "禁止", "取消", "停止", "不许", "无需",
    };

    /** 内置同音/形近纠错表：ASR 与手输里最常见、且**改后无歧义**的一批。 */
    private static final Map<String, String> DEFAULT_TYPOS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 形近（手输/OCR 型）
        m.put("美団", "美团");
        m.put("京冬", "京东");
        m.put("拼兮兮", "拼多多");
        m.put("蓝芽", "蓝牙");
        m.put("社置", "设置");
        // 同音（ASR 型）
        m.put("外买", "外卖");
        m.put("导行", "导航");
        m.put("奶查", "奶茶");
        m.put("危信", "微信");
        m.put("高得地图", "高德地图");
        m.put("支付保", "支付宝");
        DEFAULT_TYPOS = Collections.unmodifiableMap(m);
    }

    private TextNormalizer() { }

    /**
     * 归一化主入口：剥句首填充词 → 全角转半角 → 折叠空白。
     *
     * <p>顺序不可颠倒：先剥填充词（此时尚未动过标点，白名单匹配最准），再规整字符。
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        s = stripPrefixFillers(s);
        s = toHalfWidth(s);
        s = collapseWhitespace(s);
        return s.trim();
    }

    /** 剥离句首填充词；含否定词时整体放弃（红线）。 */
    public static String stripPrefixFillers(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty() || hasNegation(t)) return t;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : PREFIX_FILLERS) {          // 已按长度降序，先长后短
                if (t.startsWith(f)) {
                    String rest = t.substring(f.length()).trim();
                    // 删空 = 整句都是填充词（如「喂」）→ 保留原文，宁可执行也不清空
                    if (rest.isEmpty()) return t;
                    t = rest;
                    changed = true;
                    break;
                }
            }
        }
        return t;
    }

    /** 是否含否定词（供守卫与桌面回归使用；含义为 MUST NOT 时绝不可继续删词）。 */
    public static boolean hasNegation(String s) {
        if (s == null) return false;
        for (String n : NEGATIONS) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    /**
     * 同音/形近纠错。{@code table} 为 null 时用内置表。
     *
     * <p>调用方（引擎）可传入从已装应用名派生的表——**只做替换，不做模糊匹配**：
     * 匹配不到就不动，绝不猜。
     */
    public static String correctTypos(String s, Map<String, String> table) {
        if (s == null || s.isEmpty()) return "";
        Map<String, String> t = (table == null || table.isEmpty()) ? DEFAULT_TYPOS : table;
        String out = s;
        for (Map.Entry<String, String> e : t.entrySet()) {
            String bad = e.getKey();
            if (bad == null || bad.isEmpty()) continue;
            if (!out.contains(bad)) continue;
            String good = e.getValue();
            if (good == null || good.isEmpty() || good.equals(bad)) continue;
            // 纠错后的词已经在句中出现过 → 跳过，避免「奶茶奶查」被改成「奶奶茶」这类粘连
            if (out.contains(good)) continue;
            out = out.replace(bad, good);
        }
        return out;
    }

    /** 内置纠错表（只读）。 */
    public static Map<String, String> defaultTypos() {
        return DEFAULT_TYPOS;
    }

    /** 全角字母/数字/常见标点 → 半角（不动中文全角标点，避免破坏语义边界）。 */
    public static String toHalfWidth(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E' && !isCjkPunct(c)) {
                b.append((char) (c - 0xFEE0));          // 全角 ASCII 区整体平移
            } else if (c == '\u3000') {
                b.append(' ');                          // 全角空格
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** 中文全角标点（，。？！：；、「」等）必须原样保留。 */
    private static boolean isCjkPunct(char c) {
        return c == '\uFF0C' || c == '\u3002' || c == '\uFF1F' || c == '\uFF01'
                || c == '\uFF1A' || c == '\uFF1B' || c == '\u3001' || c == '\u300C'
                || c == '\u300D' || c == '\u201C' || c == '\u201D' || c == '\uFF08'
                || c == '\uFF09' || c == '\u2014' || c == '\u2026';
    }

    /** 连续空白（含换行）折叠为单个空格。 */
    public static String collapseWhitespace(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean sp = (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000');
            if (sp) {
                if (!lastSpace && b.length() > 0) b.append(' ');
                lastSpace = true;
            } else {
                b.append(c);
                lastSpace = false;
            }
        }
        return b.toString().trim();
    }
}
