// SPDX-License-Identifier: Apache-2.0

import com.dsh.agentlite.ActionSchema;
import com.dsh.agentlite.tools.ToolParameter;
import com.dsh.agentlite.tools.ToolRegistry;
import com.dsh.agentlite.tools.ToolSpec;
import com.dsh.agentlite.tools.ValidationResult;

import java.util.HashMap;
import java.util.Map;

/** M3-01/M3-02 ToolSpec/ToolRegistry 桌面 JVM 回归。 */
public final class ToolRegistryTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        completeActionCoverage();
        controlToolsAreDeclaredOnly();
        schemaConstraintsAreStable();
        duplicateRegistrationIsRejected();
        declarationsAreIndependentFromAndroid();
        parameterValidationRejectsInvalidCalls();
        System.out.println("M3-01/M3-02 工具注册表与参数校验：通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static void completeActionCoverage() {
        ToolRegistry registry = ToolRegistry.builtIn();
        eq("Registry 覆盖动作总数", ActionSchema.size(), registry.size());
        for (String name : ActionSchema.EXEC_ACTIONS) yes("执行动作已登记 " + name, registry.contains(name));
        for (String name : ActionSchema.CONTROL_ACTIONS) yes("控制动作已登记 " + name, registry.contains(name));
        eq("动作顺序与 ActionSchema 一致", ActionSchema.EXEC_ACTIONS.iterator().next(),
                registry.all().get(0).name);
    }

    private static void controlToolsAreDeclaredOnly() {
        ToolRegistry registry = ToolRegistry.builtIn();
        for (String name : ActionSchema.CONTROL_ACTIONS) {
            ToolSpec spec = registry.get(name);
            yes(name + " 标记控制动作", spec.controlAction);
            yes(name + " 不改变屏幕", !spec.changesScreen);
        }
        yes("tap 不是控制动作", !registry.get("tap").controlAction);
        yes("tap 允许纯视觉模式", registry.get("tap").allowedInVisionMode);
        yes("longpress 支持坐标兜底", registry.get("longpress").parameterSchemaJson().contains("\"px\""));
    }

    private static void schemaConstraintsAreStable() {
        ToolRegistry registry = ToolRegistry.builtIn();
        String brightness = registry.get("brightness").parameterSchemaJson();
        yes("brightness 范围 0..255", brightness.contains("\"minimum\":0")
                && brightness.contains("\"maximum\":255"));
        String wait = registry.get("wait").parameterSchemaJson();
        yes("wait 范围 200..8000", wait.contains("\"minimum\":200")
                && wait.contains("\"maximum\":8000"));
        String scroll = registry.get("scroll").parameterSchemaJson();
        yes("scroll 方向枚举", scroll.contains("\"up\"") && scroll.contains("\"down\""));
        String remote = registry.get("remote").parameterSchemaJson();
        yes("remote 要求 device/task", remote.contains("\"required\":[\"device\",\"task\"]"));
        yes("未知参数默认拒绝", remote.contains("\"additionalProperties\":false"));
        yes("工具描述不为空", registry.get("tap").description.length() > 0);
    }

    private static void duplicateRegistrationIsRejected() {
        ToolRegistry registry = new ToolRegistry();
        ToolSpec spec = new ToolSpec("demo", "测试工具", java.util.Collections.<ToolParameter>emptyList(),
                false, false, ToolSpec.RISK_NORMAL, false, false);
        registry.register(spec);
        throwsIllegal("重复注册被拒绝", () -> registry.register(spec));
        yes("注册表仍保留首个声明", registry.size() == 1 && registry.get("demo") == spec);
        throwsIllegal("空工具被拒绝", () -> registry.register(null));
    }

    private static void declarationsAreIndependentFromAndroid() {
        yes("工具注册表不持有执行器", ToolRegistry.class.getDeclaredFields().length == 1);
        yes("参数类型可序列化", ToolParameter.enumOf("side", true, "left", "right")
                .toJson().contains("\"enum\""));
    }

    private static void parameterValidationRejectsInvalidCalls() {
        ToolRegistry registry = ToolRegistry.builtIn();

        Map<String, String> openApi = map("target", "地图", "param", "上海");
        yes("合法调用通过且可选 intent 可省略", registry.validate("open_api", openApi).valid);
        yes("未知工具被拒绝", hasCode(registry.validate("missing", null),
                ValidationResult.CODE_UNKNOWN_TOOL));
        yes("缺少必填参数被拒绝", hasCode(registry.validate("remote", map("device", "2")),
                ValidationResult.CODE_MISSING));
        yes("未知参数被拒绝", hasCode(registry.validate("wait", map("ms", "500", "extra", "x")),
                ValidationResult.CODE_UNKNOWN_PARAMETER));
        yes("整数类型拒绝小数", hasCode(registry.validate("wait", map("ms", "500.0")),
                ValidationResult.CODE_TYPE));
        yes("范围下界被拒绝", hasCode(registry.validate("wait", map("ms", "199")),
                ValidationResult.CODE_RANGE));
        yes("范围上界被拒绝", hasCode(registry.validate("brightness", map("level", "256")),
                ValidationResult.CODE_RANGE));
        for (String direction : new String[]{"up", "down", "left", "right"})
            yes("四向翻页合法 " + direction, registry.validate("scroll", map("direction", direction)).valid);
        yes("枚举值被拒绝", hasCode(registry.validate("scroll", map("direction", "diagonal")),
                ValidationResult.CODE_ENUM));
        yes("布尔值严格校验", hasCode(registry.validate("text", map("text", "x", "append", "yes")),
                ValidationResult.CODE_TYPE));
        yes("null 值被拒绝", hasCode(registry.validate("text", map("text", null)),
                ValidationResult.CODE_NULL));
        ValidationResult secret = registry.validate("text", map("text", "密码123456", "append", "yes"));
        yes("错误不回显参数值", !secret.errors.isEmpty()
                && !secret.errors.get(0).toString().contains("密码123456"));
    }

    private static Map<String, String> map(String... values) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) out.put(values[i], values[i + 1]);
        return out;
    }

    private static boolean hasCode(ValidationResult result, String code) {
        for (ValidationResult.Error error : result.errors) if (code.equals(error.code)) return true;
        return false;
    }

    private static void eq(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) pass(name);
        else fail(name + "：期望=" + expected + " 实际=" + actual);
    }

    private static void yes(String name, boolean condition) {
        if (condition) pass(name); else fail(name);
    }

    private static void throwsIllegal(String name, Runnable action) {
        try { action.run(); fail(name); }
        catch (IllegalArgumentException expected) { pass(name); }
    }

    private static void pass(String name) { pass++; System.out.println("PASS " + name); }
    private static void fail(String name) { fail++; System.out.println("FAIL " + name); }
}
