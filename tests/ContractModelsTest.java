// SPDX-License-Identifier: Apache-2.0

import com.dsh.agentlite.ContractVersions;
import com.dsh.agentlite.contracts.ScreenSnapshot;
import com.dsh.agentlite.contracts.ScreenState;
import com.dsh.agentlite.contracts.ToolInvocation;
import com.dsh.agentlite.contracts.TraceEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M0-02 四类结构化契约桌面 JVM 回归。 */
public final class ContractModelsTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        versionsAndStoragePolicy();
        snapshotMasksPasswordAndCopiesInput();
        toolStateMachineIsTerminalAndStable();
        traceEventsAreBoundedAndStable();
        legacyReplayIsConservative();
        System.out.println("M0-02 契约模型：通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static void versionsAndStoragePolicy() {
        eq("快照版本", 1, ContractVersions.SNAPSHOT_SCHEMA_VERSION);
        eq("工具版本", 1, ContractVersions.TOOL_SCHEMA_VERSION);
        eq("轨迹版本", 1, ContractVersions.TRACE_SCHEMA_VERSION);
        eq("流程版本", "3.0", ContractVersions.MACRO_SCHEMA_VERSION);
        eq("Trace 本地保存", true, ContractVersions.TRACE_LOCAL_ONLY);
        eq("Trace 默认保存截图与快照", true, ContractVersions.TRACE_SCREENSHOT_DEFAULT);
        eq("任务轮转上限", 20, ContractVersions.TRACE_MAX_RUNS);
        eq("总容量轮转上限", 64L * 1024L * 1024L, ContractVersions.TRACE_MAX_TOTAL_BYTES);
    }

    private static void snapshotMasksPasswordAndCopiesInput() {
        List<ScreenSnapshot.Element> source = new ArrayList<>();
        source.add(new ScreenSnapshot.Element(0, "secret", "密码", "输入", "id/password",
                "android.widget.EditText", true, true, false, false, true,
                new ScreenSnapshot.Bounds(0, 0, 100, 80), "text"));
        ScreenSnapshot snapshot = new ScreenSnapshot(1, 7L, "com.example.app", "Main",
                0, 1080, 2400, false, false, source);
        source.clear();
        eq("密码文本掩码", ScreenSnapshot.PASSWORD_MASK, snapshot.elements.get(0).text);
        eq("密码描述掩码", ScreenSnapshot.PASSWORD_MASK, snapshot.elements.get(0).description);
        yes("快照复制输入集合", snapshot.elements.size() == 1);
        yes("快照 JSON 含版本和掩码", snapshot.toJson().contains("\"schema_version\":1")
                && snapshot.toJson().contains("[password]"));
    }

    private static void toolStateMachineIsTerminalAndStable() {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("index", "3");
        ToolInvocation initial = new ToolInvocation("c1", "tap", args, "pkg", null,
                10L, ToolInvocation.STATE_VALIDATING, "NORMAL", "NOT_REQUIRED");
        ToolInvocation done = initial.transition(ToolInvocation.STATE_POLICY_CHECK)
                .transition(ToolInvocation.STATE_EXECUTING)
                .transition(ToolInvocation.STATE_SUCCESS);
        yes("工具终态成功", done.terminal());
        yes("参数稳定 JSON", done.toJson().contains("\"arguments\":{\"index\":\"3\"}"));
        yes("终态不可逆", !ToolInvocation.canTransition(ToolInvocation.STATE_SUCCESS,
                ToolInvocation.STATE_EXECUTING));
        throwsIllegal("非法状态迁移被拒绝", () -> done.transition(ToolInvocation.STATE_ERROR));
    }

    private static void traceEventsAreBoundedAndStable() {
        TraceEvent event = new TraceEvent(1, 1L, 2L, "run-1", 1,
                TraceEvent.TOOL_CALL, "{\"tool\":\"tap\"}",
                Arrays.asList("step-0001-snapshot.json"), "applied");
        String json = event.toJson();
        yes("Trace 事件字段稳定", json.startsWith("{\"schema_version\":1,\"seq\":1"));
        yes("Trace 产物引用存在", json.contains("step-0001-snapshot.json"));
        throwsIllegal("未知 Trace 事件被拒绝", () -> new TraceEvent(1, 1, 2, "r", 1,
                "unknown", "{}", Collections.<String>emptyList(), "applied"));
    }

    private static void legacyReplayIsConservative() {
        ScreenState legacy = ScreenState.legacy("pkg", 1L);
        yes("旧流程标记 LEGACY_UNGUARDED",
                ScreenState.LEGACY_UNGUARDED.equals(legacy.macroSchemaVersion));
        yes("旧流程允许普通风险", legacy.allowsLegacyReplay("NORMAL"));
        yes("旧流程允许低风险", legacy.allowsLegacyReplay("LOW"));
        yes("旧流程拒绝高风险", !legacy.allowsLegacyReplay("HIGH"));
        yes("旧流程拒绝未知风险", !legacy.allowsLegacyReplay(""));
        ScreenState current = new ScreenState("3.0", "pkg", "Main", 1080, 2400,
                Collections.singletonList(new ScreenState.VisibleNode("button|提交", "button", "提交")),
                "fp", 1L);
        yes("新流程允许高风险交给守卫处理", current.allowsLegacyReplay("HIGH"));
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
        catch (IllegalArgumentException | IllegalStateException expected) { pass(name); }
    }

    private static void pass(String name) { pass++; System.out.println("PASS " + name); }
    private static void fail(String name) { fail++; System.out.println("FAIL " + name); }
}
