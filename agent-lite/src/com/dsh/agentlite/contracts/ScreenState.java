// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 流程复现前使用的轻量屏幕状态契约。 */
public final class ScreenState {
    public static final String LEGACY_UNGUARDED = "LEGACY_UNGUARDED";

    public final String macroSchemaVersion;
    public final String packageName;
    public final String activityName;
    public final int displayWidth;
    public final int displayHeight;
    public final List<VisibleNode> visibleNodes;
    public final String fingerprint;
    public final long capturedAtMs;

    public ScreenState(String macroSchemaVersion, String packageName, String activityName,
                       int displayWidth, int displayHeight, List<VisibleNode> visibleNodes,
                       String fingerprint, long capturedAtMs) {
        this.macroSchemaVersion = macroSchemaVersion;
        this.packageName = safe(packageName);
        this.activityName = safe(activityName);
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.visibleNodes = Collections.unmodifiableList(new ArrayList<>(
                visibleNodes == null ? Collections.<VisibleNode>emptyList() : visibleNodes));
        this.fingerprint = safe(fingerprint);
        this.capturedAtMs = capturedAtMs;
    }

    /** 没有 ScreenState 的旧 FlowPlan 只能进入此兼容态。 */
    public static ScreenState legacy(String packageName, long capturedAtMs) {
        return new ScreenState(LEGACY_UNGUARDED, packageName, "", 0, 0,
                Collections.<VisibleNode>emptyList(), "", capturedAtMs);
    }

    /** 旧流程只允许低风险动作直放；高风险动作必须交回模型重新决策。 */
    public boolean allowsLegacyReplay(String risk) {
        if (!LEGACY_UNGUARDED.equals(macroSchemaVersion)) return true;
        return "LOW".equalsIgnoreCase(risk) || "NORMAL".equalsIgnoreCase(risk);
    }

    public String toJson() {
        List<String> rendered = new ArrayList<>();
        for (VisibleNode node : visibleNodes) rendered.add(node.toJson());
        return ContractJson.object(
                ContractJson.stringField("macro_schema_version", macroSchemaVersion),
                ContractJson.stringField("package_name", packageName),
                ContractJson.stringField("activity_name", activityName),
                ContractJson.intField("display_width", displayWidth),
                ContractJson.intField("display_height", displayHeight),
                ContractJson.quote("visible_nodes") + ":" + ContractJson.array(rendered),
                ContractJson.stringField("fingerprint", fingerprint),
                ContractJson.longField("captured_at_ms", capturedAtMs));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public static final class VisibleNode {
        public final String key;
        public final String role;
        public final String text;

        public VisibleNode(String key, String role, String text) {
            this.key = safe(key);
            this.role = safe(role);
            this.text = safe(text);
        }

        public String toJson() {
            return ContractJson.object(
                    ContractJson.stringField("key", key),
                    ContractJson.stringField("role", role),
                    ContractJson.stringField("text", text));
        }
    }
}
