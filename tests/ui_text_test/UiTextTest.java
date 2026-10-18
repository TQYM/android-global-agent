// SPDX-License-Identifier: Apache-2.0

import com.dsh.agentlite.UiText;

import java.util.ArrayList;
import java.util.List;

/**
 * UiTextTest —— 在**桌面 JVM** 上直接验证面向用户文案的两条规则（守则 T-4 / O-3）。
 *
 * 为什么不写成 grep 门禁：T-4/O-3 是**行为**要求（「不得出现堆栈」「不得出现内部标签」），
 * 不是「某行代码存在」这种结构要求。UiText 刻意不依赖 android API，于是可以像 TlsPin 那样
 * 原样编译运行，用真实输入断言真实输出。
 *
 * 用法： java -cp &lt;classes&gt; UiTextTest
 */
public final class UiTextTest {

    private static int pass = 0, fail = 0;

    private static void ok(String what) {
        System.out.println("  PASS " + what);
        pass++;
    }

    private static void bad(String what, String detail) {
        System.out.println("  FAIL " + what + "  →  " + detail);
        fail++;
    }

    /** T-4：输出不得含堆栈特征与内部类名。 */
    private static void assertNoInternals(String label, String out, String... forbidden) {
        List<String> hit = new ArrayList<>();
        for (String f : forbidden) if (out.contains(f)) hit.add(f);
        if (out.contains("at ") && out.matches(".*\\bat\\s+[\\w.$]+\\(.*")) hit.add("堆栈行");
        if (!hit.isEmpty()) bad(label, "输出仍含内部信息 " + hit + "：「" + out + "」");
        else ok(label + " → 「" + out + "」");
    }

    public static void main(String[] args) {
        System.out.println("== T-4 终态归因不得暴露堆栈 / 内部字段名 ==");

        // 1. 真实异常原文（含全限定类名 + 平台类名 + 方法签名）
        assertNoInternals("异常原文清洗",
                UiText.uiReason("内部异常：java.lang.NullPointerException: Attempt to invoke virtual method "
                        + "'int android.view.View.getWidth()' on a null object reference"),
                "java.lang", "NullPointerException", "android.view", "null object reference");

        // 2. 多行堆栈：只保留首行
        String multi = UiText.uiReason("任务失败\n\tat com.dsh.agentlite.AgentEngine.exec(AgentEngine.java:954)\n"
                + "\tat java.base/java.lang.Thread.run(Thread.java:840)");
        assertNoInternals("多行堆栈只留首行", multi, "AgentEngine", "Thread.run", ".java:");

        // 3. 纯技术串（无中文）：落中性结论，且不留异常类名
        assertNoInternals("纯技术串落中性结论",
                UiText.uiReason("java.lang.IllegalStateException: cannot read property"),
                "IllegalStateException", "java.lang", "cannot read");

        // 4. 模型给的正常中文归因：原样保留（规范要求归因由模型给出）
        String keep = UiText.uiReason("搜索结果为 0 条，目标应用可能未安装");
        if (keep.equals("搜索结果为 0 条，目标应用可能未安装")) ok("模型中文归因不被改写 → 「" + keep + "」");
        else bad("模型中文归因不被改写", "被改成了「" + keep + "」");

        // 5. 空输入必须有兜底
        if (!UiText.uiReason(null).isEmpty() && !UiText.uiReason("   ").isEmpty()) ok("空归因有兜底文案");
        else bad("空归因有兜底文案", "返回空串");

        // 6. 超长输入必须截断（避免卡片被撑爆）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("很长的中文归因");
        String longOut = UiText.uiReason(sb.toString());
        if (longOut.length() <= 123) ok("超长归因被截断 → " + longOut.length() + " 字");
        else bad("超长归因被截断", "长度 " + longOut.length());

        System.out.println();
        System.out.println("== O-3 内部标签不得直接暴露给用户 ==");

        // 7. exec() 会产出的全部标签形态 → 输出不得含其内部词
        String[][] labels = {
                {"tap#12(直点)", "tap"}, {"tap", "tap"}, {"longpress#3", "longpress"},
                {"text", "text"}, {"text(缝合键盘)", "缝合键盘"}, {"text(剪贴板待粘贴)", "剪贴板"},
                {"swipe(root)", "swipe"}, {"swipe", "swipe"}, {"scroll down", "scroll"},
                {"key 4", "key"}, {"edge_back left", "edge_back"}, {"back", "back"}, {"home", "home"},
                {"list_apps(80)", "list_apps"}, {"app com.tencent.mm", "com.tencent.mm"},
                {"setting wifi", "setting"}, {"open_url", "open_url"},
                {"open_api: 高德地图 navigate (深圳北站)", "open_api"},
                {"open_api 降级 app: 高德地图", "降级"}, {"wifi 面板", "wifi"},
                {"bluetooth 设置", "bluetooth"}, {"brightness 128", "brightness"},
                {"volume up", "volume"}, {"statusbar expand", "statusbar"}, {"wake", "wake"},
        };
        int leaked = 0;
        for (String[] pair : labels) {
            String out = UiText.friendlyLabel(pair[0], "微信");
            if (out.contains(pair[1])) {
                bad("标签不外泄：" + pair[0], "输出含内部词「" + pair[1] + "」：「" + out + "」");
                leaked++;
            }
            if (out.isEmpty() || !out.matches(".*[\\u4e00-\\u9fa5].*")) {
                bad("标签有友好中文文案：" + pair[0], "输出「" + out + "」");
                leaked++;
            }
        }
        if (leaked == 0) ok(labels.length + " 类内部标签全部映射为友好中文文案，且内部词不外泄");

        // 8. 包名换成应用显示名
        String named = UiText.friendlyLabel("app com.tencent.mm", "微信");
        if (named.equals("打开应用「微信」")) ok("app 包名换显示名 → 「" + named + "」");
        else bad("app 包名换显示名", "得到「" + named + "」");

        // 9. 未映射标签兜底不回原文（本期新加的硬要求）
        String unknown = UiText.friendlyLabel("frobnicate 9", null);
        if (!unknown.contains("frobnicate")) ok("未映射标签兜底不回原文 → 「" + unknown + "」");
        else bad("未映射标签兜底不回原文", "得到「" + unknown + "」");

        // 10. SCHEMA 全部动作名都必须被映射（label = kind 的兜底路径）
        String[] schema = {"open_api", "open_url", "app", "list_apps", "setting", "wifi", "bluetooth",
                "brightness", "volume", "statusbar", "wake", "tap", "longpress", "text", "wait",
                "swipe", "scroll", "key", "edge_back", "ask", "done", "fail"};
        int unmapped = 0;
        for (String a : schema) if (!UiText.isMapped(a)) { bad("SCHEMA 动作名有文案：" + a, "未映射"); unmapped++; }
        if (unmapped == 0) ok(schema.length + " 个 SCHEMA 动作名全部有友好文案");

        System.out.println();
        System.out.println("== 失败卡片「下一步」必须指向真的能走的出口（SPEC-UX-001 §4）==");

        // 11. 自带通道欠费：不能建议「配置自己的接口作为备用通道」——他正在用自己那份接口。
        //     2026-10-08 真机回归实测踩到：该归因串本身含「账号额度」，会先命中通用分支，
        //     卡片于是把用户引向「改配置」，而他真正该做的是给自己的服务账号充值。
        String ownQuota = UiText.nextStep(
                "自己的模型服务额度不足，请查看对应服务账号余额，或在设置中主动选择账号额度");
        if (ownQuota.contains("充值") && !ownQuota.contains("备用通道")) {
            ok("自带欠费指向充值自己的服务账号 → 「" + ownQuota + "」");
        } else {
            bad("自带欠费指向充值自己的服务账号", "得到「" + ownQuota + "」");
        }

        // 12. 账号欠费不得被上面的特例改坏：出口仍是「换一份可用的接口配置」。
        String acctQuota = UiText.nextStep("账号额度不足，请在应用内使用兑换码充值");
        if (!acctQuota.isEmpty() && !acctQuota.contains("自己的服务账号充值")) {
            ok("账号欠费出口未被特例污染 → 「" + acctQuota + "」");
        } else {
            bad("账号欠费出口未被特例污染", "得到「" + acctQuota + "」");
        }

        // 13. 每类高频失败都必须给出非空、可执行的下一步（空建议 = 用户卡死）
        String[] reasons = {"网络不可用", "两条模型通道都不可用", "账号额度暂时不可用（服务端上游问题）",
                "密钥无效或没有权限", "模型配置不完整", "无障碍服务未开启", "登录已失效，请重新登录",
                "任务太久没有进展"};
        int empty = 0;
        for (String r : reasons) if (UiText.nextStep(r).trim().isEmpty()) { bad("下一步建议：" + r, "为空"); empty++; }
        if (empty == 0) ok(reasons.length + " 类高频失败都有非空「下一步」建议");

        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.printf("通过 %d 项，失败 %d 项%n", pass, fail);
        if (fail > 0) {
            System.out.println("面向用户文案行为测试未通过 ❌");
            System.exit(1);
        }
        System.out.println("面向用户文案行为测试通过 ✅（T-4 / O-3）");
    }

    private UiTextTest() { }
}
