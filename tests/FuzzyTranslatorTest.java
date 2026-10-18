// SPDX-License-Identifier: Apache-2.0
//
// 桌面 JVM 回归：模糊语义转译的纯逻辑层（零 android / org.json 依赖，可脱离设备直跑）。
//   javac --release 11 -d "$TMP" tests/FuzzyTranslatorTest.java \
//     agent-lite/src/com/dsh/agentlite/{FuzzyFlow,TextNormalizer,SemanticTranslator}.java
//   java -cp "$TMP" FuzzyTranslatorTest
//
// 为什么值得单独测：这一层决定「引擎带着什么样的理解去操作手机」。
// 判错的代价**不对称**——
//   · 把「别打开微信」理解成「打开微信」：用户看到一条「任务已完成」，但做的是反事；
//   · 把风险任务（支付/发送）的确认点丢失：可能直接替用户花钱；
//   · 而少删一个填充词、多写一行「不确定项」，代价只是提示词长几个字。
// 所以每个模糊地带都必须偏向保守，并且用断言把这份保守钉死在代码里。

import com.dsh.agentlite.FuzzyFlow;
import com.dsh.agentlite.SemanticTranslator;
import com.dsh.agentlite.TextNormalizer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class FuzzyTranslatorTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        normalizer();
        jsonExtraction();
        parseAndValidate();
        interpretPaths();
        compileOutput();
        fingerprinting();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------- 1. 归一化层（FF-5：否定词保护） ----------

    private static void normalizer() {
        section("归一化：填充词、纠错、否定词保护");

        eq("剥句首填充词", "打开微信", TextNormalizer.normalize("帮我打开微信"));
        eq("多层填充词一次剥净", "打开微信", TextNormalizer.normalize("麻烦你帮我打开微信"));
        eq("无填充词原样返回", "打开微信", TextNormalizer.normalize("打开微信"));

        // ★ 红线：否定词一旦被删，任务会整个反过来
        eq("否定词保护：别+填充词不删", "别帮我打开微信",
                TextNormalizer.normalize("别帮我打开微信"));
        eq("否定词保护：不要开头不删", "不要麻烦你打开微信",
                TextNormalizer.normalize("不要麻烦你打开微信"));
        yes("hasNegation 命中「取消」", TextNormalizer.hasNegation("取消闹钟"));
        yes("hasNegation 不误报「打」", !TextNormalizer.hasNegation("打开微信"));

        // 全是填充词时不得清空（清空等于把任务丢了）
        eq("整句都是填充词时保留原文", "喂", TextNormalizer.normalize("喂"));
        eq("空输入", "", TextNormalizer.normalize("   "));

        eq("全角转半角", "abc123", TextNormalizer.toHalfWidth("ａｂｃ１２３"));
        eq("空白折叠", "a b", TextNormalizer.collapseWhitespace("a \n\t  b"));

        // 同音/形近纠错（ASR 噪音，F-5）
        eq("形近纠错：美团", "打开美团", TextNormalizer.correctTypos("打开美団", null));
        eq("同音纠错：奶茶", "点奶茶", TextNormalizer.correctTypos("点奶查", null));
        eq("同音纠错：蓝牙", "打开蓝牙", TextNormalizer.correctTypos("打开蓝芽", null));
        eq("正确词不动", "打开美团", TextNormalizer.correctTypos("打开美团", null));
        // 正确词已在句中时跳过替换，避免「奶茶奶查」变成「奶奶茶」
        eq("目标词已在句中则不替换", "奶茶奶查",
                TextNormalizer.correctTypos("奶茶奶查", null));
    }

    // ---------- 2. JSON 抽取（模型回复噪声） ----------

    private static void jsonExtraction() {
        section("JSON 抽取：围栏、前后废话、字符串内大括号");

        eq("裸 JSON", "{\"a\":1}", FuzzyFlow.extractJsonObject("{\"a\":1}"));
        eq("markdown 围栏", "{\"a\":1}",
                FuzzyFlow.extractJsonObject("```json\n{\"a\":1}\n```"));
        eq("前后有解释文字", "{\"a\":1}",
                FuzzyFlow.extractJsonObject("好的，这是结果：{\"a\":1} 希望有帮助"));
        // 字符串里含大括号：不能靠简单数括号
        eq("字符串内的大括号不干扰配对", "{\"t\":\"a{b}c\"}",
                FuzzyFlow.extractJsonObject("{\"t\":\"a{b}c\"}"));
        eq("转义引号不干扰", "{\"t\":\"a\\\"b\"}",
                FuzzyFlow.extractJsonObject("{\"t\":\"a\\\"b\"}"));
        eq("嵌套对象", "{\"a\":{\"b\":1}}", FuzzyFlow.extractJsonObject("{\"a\":{\"b\":1}}"));
        eq("括号不闭合（被截断）→ null", null,
                FuzzyFlow.extractJsonObject("{\"goal\":\"打开微信\""));
        eq("完全没有 JSON → null", null, FuzzyFlow.extractJsonObject("打开微信就行"));
        eq("null 输入 → null", null, FuzzyFlow.extractJsonObject(null));
    }

    // ---------- 3. 解析与校验（FF-1：风险任务必须有确认点） ----------

    private static void parseAndValidate() {
        section("解析与校验：意图归一、风险确认点");

        FuzzyFlow.Spec ok = FuzzyFlow.parse(GOOD_JSON);
        yes("合法 spec 解析成功", ok != null);
        if (ok == null) return;
        eq("意图读对", "order_food", ok.intent);
        eq("风险读对", "money", ok.risk);
        eq("槽位数", "2", String.valueOf(ok.slots.size()));
        eq("步骤数", "2", String.valueOf(ok.steps.size()));
        yes("校验通过", FuzzyFlow.isValid(ok));

        // 未知意图 → general（保证指纹稳定与统计不失真）
        FuzzyFlow.Spec weird = FuzzyFlow.parse(GOOD_JSON.replace("\"order_food\"", "\"订奶茶\""));
        yes("未知意图归一为 general", weird != null && "general".equals(weird.intent));

        // 非法风险等级 → none
        FuzzyFlow.Spec badRisk = FuzzyFlow.parse(GOOD_JSON.replace("\"money\"", "\"超危险\""));
        yes("非法 risk 归一为 none", badRisk != null && "none".equals(badRisk.risk));

        // ★ 核心硬约束：风险任务必须留下一个明示确认点
        String noGuard = GOOD_JSON
                .replace("\"guard\":\"ask\"", "\"guard\":\"none\"")
                .replace("\"must_ask_before\":[\"提交订单\"]", "\"must_ask_before\":[]");
        FuzzyFlow.Spec unsafe = FuzzyFlow.parse(noGuard);
        yes("风险任务缺确认点 → 校验失败",
                unsafe != null && !FuzzyFlow.isValid(unsafe));
        yes("失败原因指向确认点",
                unsafe != null && join(FuzzyFlow.validate(unsafe)).contains("确认点"));

        // must_ask_before 非空也算有确认点（与 SensitiveGuard 词表互补）
        String onlyList = GOOD_JSON.replace("\"guard\":\"ask\"", "\"guard\":\"none\"");
        FuzzyFlow.Spec listed = FuzzyFlow.parse(onlyList);
        yes("仅靠 must_ask_before 也可通过",
                listed != null && FuzzyFlow.isValid(listed));

        // 无风险任务不需要确认点
        FuzzyFlow.Spec safe = FuzzyFlow.parse(GOOD_JSON
                .replace("\"risk\":\"money\"", "\"risk\":\"none\"")
                .replace("\"guard\":\"ask\"", "\"guard\":\"none\"")
                .replace("\"must_ask_before\":[\"提交订单\"]", "\"must_ask_before\":[]"));
        yes("无风险任务无需确认点", safe != null && FuzzyFlow.isValid(safe));

        // 宽松槽位形态：{"品类":"奶茶"}
        FuzzyFlow.Spec loose = FuzzyFlow.parse(
                "{\"goal\":\"点奶茶\",\"slots\":{\"品类\":\"奶茶\"}}");
        yes("宽松槽位形态可解析", loose != null && loose.slots.size() == 1);
        eq("宽松槽位来源默认 utterance", "utterance",
                loose == null || loose.slots.isEmpty() ? "" : loose.slots.get(0).source);
        yes("缺省置信度保守取 0.5",
                loose != null && !loose.slots.isEmpty() && loose.slots.get(0).confidence == 0.5);

        // goal 与 steps 同时为空 = 无意义产出
        isNull("空壳 spec 被拒", FuzzyFlow.parse("{\"intent\":\"search\"}"));
        isNull("非法 JSON 被拒", FuzzyFlow.parse("{不是 json}"));
        isNull("非对象根被拒", FuzzyFlow.parse("[1,2,3]"));

        // 包名形态校验
        yes("合法包名", FuzzyFlow.looksLikePackage("com.sankuai.meituan"));
        yes("中文不是包名", !FuzzyFlow.looksLikePackage("美团"));
        yes("空不是包名", !FuzzyFlow.looksLikePackage(""));
        yes("带空格不是包名", !FuzzyFlow.looksLikePackage("com.foo bar"));
        yes("单段不是包名", !FuzzyFlow.looksLikePackage("meituan"));
    }

    // ---------- 4. 三路回退（FF-2：转译失败必须能回退） ----------

    private static void interpretPaths() {
        section("解读：结构化 / 松散 / 丢弃 三条路");

        Set<String> allowed = new HashSet<>(Arrays.asList(
                "com.sankuai.meituan", "com.tencent.mm"));

        SemanticTranslator.Result good = SemanticTranslator.interpret(GOOD_JSON, allowed);
        yes("合法 JSON → 结构化", good.structured);
        eq("意图透出", "order_food", good.intent);
        eq("风险透出", "money", good.risk);
        yes("产物含任务理解段", good.planText != null && good.planText.contains("【任务理解】"));

        // 第 1 档：没有 JSON → 当散文简报用（等于今天的行为，零损失）
        SemanticTranslator.Result prose =
                SemanticTranslator.interpret("意图：打开微信\n推荐应用：微信\n步骤：打开即可", allowed);
        yes("散文 → 非结构化但可用", !prose.structured && prose.planText != null);
        yes("散文原文保留", prose.planText.contains("推荐应用"));

        // ★ 被截断的 JSON（max_tokens 用尽）必须**丢弃**，不能当散文喂给引擎：
        //   引擎收到一串 JSON 碎片会试图去解析它、甚至照着半句话行动——比什么都不给更糟。
        SemanticTranslator.Result truncated = SemanticTranslator.interpret(
                "{\"v\":1,\"intent\":\"setting_toggle\",\"goal\":\"打开", allowed);
        eq("截断的 JSON → 丢弃（不退回散文）", null, truncated.planText);
        yes("detail 指明是被截断", truncated.detail.contains("截断"));

        // 第 2 档：有 JSON 但校验不过 → 丢弃（绝不把半成品 JSON 喂给引擎）
        String unsafeJson = GOOD_JSON
                .replace("\"guard\":\"ask\"", "\"guard\":\"none\"")
                .replace("\"must_ask_before\":[\"提交订单\"]", "\"must_ask_before\":[]");
        SemanticTranslator.Result dropped = SemanticTranslator.interpret(unsafeJson, allowed);
        eq("校验不过 → 产物为空（回退）", null, dropped.planText);
        yes("detail 说明原因", dropped.detail.contains("校验未通过"));

        // 第 3 档：空回复
        eq("空回复 → 回退", null, SemanticTranslator.interpret("", allowed).planText);
        eq("null 回复 → 回退", null, SemanticTranslator.interpret(null, allowed).planText);

        // 包名白名单：编造的包名被清除，但其余结构照用
        SemanticTranslator.Result faked = SemanticTranslator.interpret(
                GOOD_JSON.replace("com.sankuai.meituan", "com.meituan.fake"), allowed);
        yes("编造包名被清除后仍结构化", faked.structured);
        yes("产物不含被清除的包名",
                faked.planText != null && !faked.planText.contains("com.meituan.fake"));

        // 白名单为 null 表示不过滤（供没有已装列表的场景）
        SemanticTranslator.Result noFilter = SemanticTranslator.interpret(GOOD_JSON, null);
        yes("白名单为 null 时不过滤", noFilter.structured);

        // 提示词里不得出现"编包名"的自由度
        String sys = SemanticTranslator.systemPrompt();
        yes("系统指令要求只输出 JSON", sys.contains("只输出一个 JSON"));
        yes("系统指令禁止自编包名", sys.contains("绝不要自己编包名"));
        yes("系统指令含受限意图表", sys.contains("general"));
        // 归一化后与原文字面不同才附加，省 token
        String p1 = SemanticTranslator.userPrompt("帮我打开微信", "打开微信", "微信=com.tencent.mm", "", "");
        yes("归一文与原文不同时附加", p1.contains("归一化后"));
        String p2 = SemanticTranslator.userPrompt("打开微信", "打开微信", "", "", "");
        yes("相同时不附加", !p2.contains("归一化后"));
    }

    // ---------- 5. 编译产物 ----------

    private static void compileOutput() {
        section("编译：风险提示、不确定项、长度上限");

        FuzzyFlow.Spec s = FuzzyFlow.parse(GOOD_JSON);
        yes("解析成功", s != null);
        if (s == null) return;
        String text = FuzzyFlow.compile(s);
        yes("含目标", text.contains("在美团点一杯奶茶"));
        yes("含路径建议", text.contains("路径建议"));
        yes("含完成判据", text.contains("完成判据"));
        yes("风险任务提示必须 ask", text.contains("ask"));
        yes("声明路径建议不是命令", text.contains("不是命令"));
        yes("长度不超上限", text.length() <= FuzzyFlow.COMPILE_MAX_CHARS);

        // 低置信槽位进入「不确定项」，让模型知道哪一项可能不对
        yes("低置信槽位列入不确定项", text.contains("不确定项"));

        // 无风险任务不应出现风险行
        FuzzyFlow.Spec safe = FuzzyFlow.parse(GOOD_JSON.replace("\"risk\":\"money\"", "\"risk\":\"none\""));
        yes("无风险任务不写风险行", safe != null && !FuzzyFlow.compile(safe).contains("风险等级"));

        eq("null spec 编译为空", "", FuzzyFlow.compile(null));
    }

    // ---------- 6. 指纹与骨架（P2 流程库的地基） ----------

    private static void fingerprinting() {
        section("指纹与骨架：同款流程必同指纹");

        FuzzyFlow.Spec a = FuzzyFlow.parse(GOOD_JSON);
        String sk = FuzzyFlow.skeleton("点奶茶送到公司", a == null ? null : a.slots);
        eq("骨架把值换成占位符", "点<品类>送到<地址>", sk);

        // 同骨架、不同参数 → 同指纹（这才是"复用"的判据）
        String fp1 = FuzzyFlow.fingerprint("order_food", "点<品类>送到<地址>");
        String fp2 = FuzzyFlow.fingerprint("order_food", "点<品类>送到<地址>");
        eq("同意图同骨架 → 同指纹", fp1, fp2);
        yes("指纹非空", !fp1.isEmpty());

        // 不同意图 → 不同指纹（否则"搜奶茶"会命中"点奶茶"）
        yes("不同意图 → 不同指纹",
                !fp1.equals(FuzzyFlow.fingerprint("search", "点<品类>送到<地址>")));
        yes("不同骨架 → 不同指纹",
                !fp1.equals(FuzzyFlow.fingerprint("order_food", "点<品类>")));

        // 长值优先替换：先换「三分糖」再换「糖」，避免短值把长值切碎
        FuzzyFlow.Spec multi = FuzzyFlow.parse(
                "{\"goal\":\"x\",\"slots\":{\"甜度\":\"三分糖\",\"糖\":\"糖\"}}");
        String sk2 = FuzzyFlow.skeleton("要三分糖", multi == null ? null : multi.slots);
        eq("长槽位值优先替换", "要<甜度>", sk2);

        eq("空输入骨架为空", "", FuzzyFlow.skeleton(null, null));
    }

    // ---------- 断言工具 ----------

    private static final String GOOD_JSON = "{"
            + "\"v\":1,"
            + "\"intent\":\"order_food\","
            + "\"goal\":\"在美团点一杯奶茶（少冰三分糖）\","
            + "\"target_app\":{\"name\":\"美团\",\"pkg\":\"com.sankuai.meituan\"},"
            + "\"slots\":{"
            + "\"品类\":{\"value\":\"奶茶\",\"source\":\"utterance\",\"confidence\":0.95},"
            + "\"地址\":{\"value\":\"公司\",\"source\":\"ledger\",\"confidence\":0.4}},"
            + "\"steps\":["
            + "{\"n\":1,\"do\":\"启动美团\",\"channel\":\"app\",\"expect\":\"首页\",\"guard\":\"none\"},"
            + "{\"n\":2,\"do\":\"提交订单\",\"channel\":\"ui\",\"expect\":\"结算页\",\"guard\":\"ask\"}],"
            + "\"done_when\":\"结算页出现目标商品\","
            + "\"risk\":\"money\","
            + "\"must_ask_before\":[\"提交订单\"],"
            + "\"carry_over\":[\"店铺\"],"
            + "\"confidence\":0.86}";

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void eq(String label, String expect, String actual) {
        boolean ok = (expect == null) ? (actual == null) : expect.equals(actual);
        report(ok, label + "（期望=" + expect + " 实际=" + actual + "）");
    }

    private static void yes(String label, boolean cond) {
        report(cond, label);
    }

    private static void isNull(String label, Object o) {
        report(o == null, label + "（期望=null 实际=" + o + "）");
    }

    private static void report(boolean ok, String label) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + label);
        } else {
            fail++;
            System.out.println("  ✗ " + label);
        }
    }

    private static String join(java.util.List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) {
            if (b.length() > 0) b.append("；");
            b.append(x);
        }
        return b.toString();
    }

    private FuzzyTranslatorTest() { }
}
