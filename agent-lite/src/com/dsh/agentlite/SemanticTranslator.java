// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.List;
import java.util.Set;

/**
 * 语义转译层（STL）的门面（见 {@code docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md} §5.4、§7）。
 *
 * <p>本类负责三件事，且**全部是纯逻辑**（零 android、零 org.json 依赖，可桌面 JVM 直跑）：
 * <ol>
 *   <li>{@link #systemPrompt()}：给转译模型的固定指令（受限意图表 + 字段契约）；</li>
 *   <li>{@link #userPrompt}：把用户输入与上下文（已装应用、记忆、归一化结果）拼成一条消息；</li>
 *   <li>{@link #interpret}：解读模型回复 —— 抽 JSON、解析、白名单过滤包名、校验、编译。</li>
 * </ol>
 *
 * <p>真正的「发请求」留在 {@link AgentEngine#planTask}：那样断网、超时、额度不足这些
 * Android 侧的现实问题仍由既有代码统一处理，本类不碰网络也就没有不可测的分支。
 *
 * <p><b>失败姿态（三条路，越靠后越保守）</b>：
 * <ul>
 *   <li>回复里**没有 JSON** → 当作今天的「规划简报」散文照用（等于零损失）；</li>
 *   <li>回复里**有 JSON 但解析/校验不过** → {@code planText=null}，调用方回退原路径。
 *       绝不把半成品 JSON 原样喂给引擎——那只会让主循环在噪声里多做几次无效决策；</li>
 *   <li>模型无输出 → 同上。</li>
 * </ul>
 */
public final class SemanticTranslator {

    /** 转译结果。{@code planText == null} 表示"没有可用产出，请回退"。 */
    public static final class Result {
        public String planText;
        public boolean structured;
        public String intent = "";
        public String risk = "";
        /**
         * 流程骨架（槽位值换成 {@code <槽位名>} 占位符后的任务文本），供**操作记忆库**
         * 做同款不同参的命中判定（「点奶茶」与「点咖啡」共享一个骨架）。
         * 转译未产出结构化结果时为空串 = 只做精确指纹命中。
         */
        public String skeleton = "";
        /** 排障用：为什么成功/失败（只进日志，不进提示词）。 */
        public String detail = "";
    }

    private SemanticTranslator() { }

    /**
     * 转译模型的系统指令。
     *
     * <p>三条刻意的约束：
     * <ul>
     *   <li>「只输出一个 JSON 对象」——与引擎主循环的动作约定同构，降低模型心智切换成本，
     *       也让 {@link FuzzyFlow#extractJsonObject} 的抽取有稳定的目标；</li>
     *   <li>「不确定就写低置信度，不要编造」——编造的参数会让引擎带着错误理解跑完整条流程，
     *       而低置信度只会让编译产物多一行「不确定项」；</li>
     *   <li>「steps 是建议不是命令」——屏幕实况永远优先于预设路径（守则 §3）。</li>
     * </ul>
     */
    public static String systemPrompt() {
        StringBuilder b = new StringBuilder();
        b.append("你是手机自动化任务的结构化转译器：把用户的模糊口语转译成一份可执行的流程 JSON。\n\n");
        b.append("规则：\n");
        b.append("1. 只输出一个 JSON 对象，不要解释、不要 markdown 代码块。\n");
        b.append("2. 输入可能来自语音，含同音错字（如「奶查」应为「奶茶」），先在心里纠正再转译。\n");
        b.append("3. intent 只能取下列之一，拿不准用 general：\n   ");
        b.append(join(FuzzyFlow.INTENTS, " / ")).append('\n');
        b.append("4. target_app.pkg 只能从下面给出的「已安装应用匹配」里选；没有匹配就留空字符串，"
                + "**绝不要自己编包名**。\n");
        b.append("5. 缺失的参数不要编造：写进 slots 并把 confidence 给到 0.5 以下；"
                + "能从上文推断的，source 填 memory 或 ledger。\n");
        b.append("6. risk 只在**会花钱 / 会对外发送 / 会删数据 / 会读写用户隐私**时才标注：\n"
                + "   money=支付扣费转账；send=发消息或邮件；delete=删除数据；\n"
                + "   personal_info=读取或填写身份证、手机号、住址、位置等隐私。\n"
                + "   **纯查询或浏览不算风险**（查路线、搜商品、看天气都填 none）。\n"
                + "   一旦标了风险，就必须把需要用户确认的那一步 guard 标成 ask——\n"
                + "   标了风险却没有确认点的流程会被整份丢弃（真机实测：把「去公司怎么走」\n"
                + "   标成 personal_info 又不给 ask，这次转译就白做了）。\n");
        b.append("7. steps 是路径建议（1~5 步），channel 优先用直达通道"
                + "（app / open_api / setting），只有必须点界面时才用 ui。\n\n");
        b.append("字段格式：\n");
        b.append("{\"v\":1,\"intent\":\"...\",\"goal\":\"一句话目标\",\n");
        b.append(" \"target_app\":{\"name\":\"中文名\",\"pkg\":\"包名或空\"},\n");
        b.append(" \"slots\":{\"参数名\":{\"value\":\"值\",\"source\":\"utterance|memory|ledger|default\",\"confidence\":0.9}},\n");
        b.append(" \"steps\":[{\"n\":1,\"do\":\"做什么\",\"channel\":\"app\",\"expect\":\"预期页面\",\"guard\":\"none|ask\"}],\n");
        b.append(" \"done_when\":\"完成判据\",\"risk\":\"none|personal_info|send|delete|money\",\n");
        b.append(" \"must_ask_before\":[\"提交订单\"],\"carry_over\":[\"店铺\"],\"confidence\":0.8}");
        return b.toString();
    }

    /**
     * 拼用户消息：原始输入 + 归一化结果 + 上下文块。
     *
     * <p>归一化结果**只在与原文字面不同时**附加——否则每轮都多几十个 token，
     * 而绝大多数输入本来就是干净的（省的是真金白银，方案 §12）。
     */
    public static String userPrompt(String rawTask, String normalized,
                                    String installedBlock, String memoryBlock,
                                    String deviceBlock) {
        StringBuilder b = new StringBuilder();
        b.append("用户任务：").append(nz(rawTask)).append('\n');
        if (normalized != null && !normalized.isEmpty() && !normalized.equals(nz(rawTask))) {
            b.append("归一化后（已纠正常见同音错字）：").append(normalized).append('\n');
        }
        if (installedBlock != null && !installedBlock.trim().isEmpty()) {
            b.append("已安装应用匹配（pkg 只能取自这里）：\n").append(installedBlock.trim()).append('\n');
        }
        if (memoryBlock != null && !memoryBlock.trim().isEmpty()) {
            b.append(memoryBlock.trim()).append('\n');
        }
        if (deviceBlock != null && !deviceBlock.trim().isEmpty()) {
            b.append(deviceBlock.trim()).append('\n');
        }
        return b.toString();
    }

    /**
     * 解读模型回复。
     *
     * @param reply       模型原始回复
     * @param allowedPkgs 允许出现的包名白名单（已装应用 + 常用应用表）；为 null 表示不做过滤
     */
    public static Result interpret(String reply, Set<String> allowedPkgs) {
        return interpret(reply, allowedPkgs, null);
    }

    /**
     * 同上，并额外产出**流程骨架**（供操作记忆库复用同款流程）。
     *
     * @param normalized 归一化后的任务文本；为 null 时不做骨架（= 只支持精确命中）
     */
    public static Result interpret(String reply, Set<String> allowedPkgs, String normalized) {
        Result r = new Result();
        if (reply == null || reply.trim().isEmpty()) {
            r.detail = "模型无输出";
            return r;
        }
        String trimmed = reply.trim();
        String json = FuzzyFlow.extractJsonObject(trimmed);

        if (json == null) {
            // 抽出的是 null，其实有两种截然不同的情况，必须分开处理：
            //   · 回复里**根本没有 {** → 模型给的是散文，当今天的「规划简报」用（零损失）；
            //   · 回复里**有 { 但括号不闭合** → 这是**被截断的 JSON**（最常见的原因是
            //     max_tokens 用尽）。把它当散文喂给引擎，等于往上下文里塞一串 JSON 碎片，
            //     模型会试图去解析它、甚至照着半句话行动 —— 比什么都不给更糟。
            if (trimmed.indexOf('{') >= 0) {
                r.detail = "JSON 不完整（疑似被长度上限截断），已丢弃";
                return r;                     // planText 留 null → 调用方走裸任务
            }
            r.planText = trimmed;
            r.detail = "回复非 JSON，按松散简报使用";
            return r;
        }

        FuzzyFlow.Spec spec = FuzzyFlow.parse(json);
        if (spec == null) {
            r.detail = "JSON 解析失败（已丢弃，不把半成品喂给引擎）";
            return r;
        }

        // 包名白名单：模型最常见的越界就是"编一个看起来很像的包名"。
        // 不白名单过滤的后果是引擎拿着错包名启动失败，再花几步兜圈子。
        if (!spec.appPkg.isEmpty() && allowedPkgs != null && !allowedPkgs.contains(spec.appPkg)) {
            r.detail = "包名不在白名单已清除: " + spec.appPkg;
            spec.appPkg = "";
        }

        List<String> errs = FuzzyFlow.validate(spec);
        if (!errs.isEmpty()) {
            r.detail = "校验未通过(" + errs.size() + " 项): " + errs.get(0);
            return r;   // planText 留 null → 调用方回退
        }

        r.structured = true;
        r.intent = spec.intent;
        r.risk = spec.risk;
        r.planText = FuzzyFlow.compile(spec);
        // 骨架只在拿到归一化文本时可算（纯函数，不额外发请求）：它是操作记忆库
        // 「同款不同参」命中的依据——同一个意图 + 同一个骨架 = 同一条路径值得照做。
        if (normalized != null && !normalized.isEmpty()) {
            r.skeleton = FuzzyFlow.skeleton(normalized, spec.slots);
        }
        if (r.detail.isEmpty()) r.detail = "结构化转译成功";
        return r;
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) {
            if (b.length() > 0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
