// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单行 trace.jsonl 事件契约；事件载荷由上层传入已脱敏 JSON。 */
public final class TraceEvent {
    public static final String PERCEPTION = "perception";
    public static final String LLM_REQUEST = "llm_request";
    public static final String LLM_RESPONSE = "llm_response";
    public static final String TOOL_CALL = "tool_call";
    public static final String APPROVAL = "approval";
    public static final String VERIFICATION = "verification";
    public static final String TERMINAL = "terminal";
    public static final String ERROR = "error";

    public final int schemaVersion;
    public final long seq;
    public final long timestampMs;
    public final String runId;
    public final int step;
    public final String event;
    public final String payloadJson;
    public final List<String> artifactRefs;
    public final String redaction;

    public TraceEvent(int schemaVersion, long seq, long timestampMs, String runId,
                      int step, String event, String payloadJson,
                      List<String> artifactRefs, String redaction) {
        if (!isKnownEvent(event)) throw new IllegalArgumentException("未知 Trace 事件: " + event);
        this.schemaVersion = schemaVersion;
        this.seq = seq;
        this.timestampMs = timestampMs;
        this.runId = safe(runId);
        this.step = step;
        this.event = event;
        this.payloadJson = payloadJson == null ? "{}" : payloadJson;
        this.artifactRefs = Collections.unmodifiableList(new ArrayList<>(
                artifactRefs == null ? Collections.<String>emptyList() : artifactRefs));
        this.redaction = safe(redaction);
    }

    public String toJson() {
        return ContractJson.object(
                ContractJson.intField("schema_version", schemaVersion),
                ContractJson.longField("seq", seq),
                ContractJson.longField("ts_ms", timestampMs),
                ContractJson.stringField("run_id", runId),
                ContractJson.intField("step", step),
                ContractJson.stringField("event", event),
                ContractJson.quote("payload") + ":" + payloadJson,
                ContractJson.quote("artifact_refs") + ":" + ContractJson.stringArray(artifactRefs),
                ContractJson.stringField("redaction", redaction));
    }

    public static boolean isKnownEvent(String event) {
        return PERCEPTION.equals(event) || LLM_REQUEST.equals(event)
                || LLM_RESPONSE.equals(event) || TOOL_CALL.equals(event)
                || APPROVAL.equals(event) || VERIFICATION.equals(event)
                || TERMINAL.equals(event) || ERROR.equals(event);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
