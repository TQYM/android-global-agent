// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.contracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 单次工具调用及其不可逆状态迁移。 */
public final class ToolInvocation {
    public static final String STATE_VALIDATING = "VALIDATING";
    public static final String STATE_POLICY_CHECK = "POLICY_CHECK";
    public static final String STATE_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String STATE_EXECUTING = "EXECUTING";
    public static final String STATE_SUCCESS = "SUCCESS";
    public static final String STATE_ERROR = "ERROR";
    public static final String STATE_CANCELLED = "CANCELLED";

    public final String callId;
    public final String toolName;
    public final Map<String, String> arguments;
    public final String packageName;
    public final String destinationPackage;
    public final long createdAtMs;
    public final String state;
    public final String risk;
    public final String approval;

    public ToolInvocation(String callId, String toolName, Map<String, String> arguments,
                          String packageName, String destinationPackage, long createdAtMs,
                          String state, String risk, String approval) {
        this.callId = safe(callId);
        this.toolName = safe(toolName);
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(
                arguments == null ? Collections.<String, String>emptyMap() : arguments));
        this.packageName = safe(packageName);
        this.destinationPackage = destinationPackage;
        this.createdAtMs = createdAtMs;
        this.state = requireState(state);
        this.risk = safe(risk);
        this.approval = safe(approval);
    }

    public ToolInvocation transition(String nextState) {
        if (!canTransition(state, nextState)) {
            throw new IllegalStateException("非法工具状态迁移: " + state + " -> " + nextState);
        }
        return new ToolInvocation(callId, toolName, arguments, packageName,
                destinationPackage, createdAtMs, nextState, risk, approval);
    }

    public boolean terminal() {
        return STATE_SUCCESS.equals(state) || STATE_ERROR.equals(state)
                || STATE_CANCELLED.equals(state);
    }

    public String toJson() {
        return ContractJson.object(
                ContractJson.stringField("call_id", callId),
                ContractJson.stringField("tool_name", toolName),
                ContractJson.quote("arguments") + ":" + ContractJson.stringMap(arguments),
                ContractJson.stringField("package_name", packageName),
                ContractJson.stringField("destination_package", destinationPackage),
                ContractJson.longField("created_at_ms", createdAtMs),
                ContractJson.stringField("state", state),
                ContractJson.stringField("risk", risk),
                ContractJson.stringField("approval", approval));
    }

    public static boolean canTransition(String from, String to) {
        if (from == null || to == null || isTerminal(from)) return false;
        if (STATE_VALIDATING.equals(from)) {
            return STATE_ERROR.equals(to) || STATE_POLICY_CHECK.equals(to);
        }
        if (STATE_POLICY_CHECK.equals(from)) {
            return STATE_CANCELLED.equals(to) || STATE_EXECUTING.equals(to)
                    || STATE_AWAITING_APPROVAL.equals(to);
        }
        if (STATE_AWAITING_APPROVAL.equals(from)) {
            return STATE_CANCELLED.equals(to) || STATE_EXECUTING.equals(to);
        }
        if (STATE_EXECUTING.equals(from)) {
            return STATE_ERROR.equals(to) || STATE_SUCCESS.equals(to);
        }
        return false;
    }

    private static boolean isTerminal(String value) {
        return STATE_SUCCESS.equals(value) || STATE_ERROR.equals(value)
                || STATE_CANCELLED.equals(value);
    }

    private static String requireState(String value) {
        if (!STATE_VALIDATING.equals(value) && !STATE_POLICY_CHECK.equals(value)
                && !STATE_AWAITING_APPROVAL.equals(value) && !STATE_EXECUTING.equals(value)
                && !STATE_SUCCESS.equals(value) && !STATE_ERROR.equals(value)
                && !STATE_CANCELLED.equals(value)) {
            throw new IllegalArgumentException("未知工具状态: " + value);
        }
        return value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
