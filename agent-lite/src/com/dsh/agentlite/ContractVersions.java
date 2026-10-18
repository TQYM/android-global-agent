// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

/** M0-02 四类结构化契约的版本与落盘策略常量。 */
public final class ContractVersions {
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
    public static final int TOOL_SCHEMA_VERSION = 1;
    public static final int TRACE_SCHEMA_VERSION = 1;
    public static final String MACRO_SCHEMA_VERSION = "3.0";

    /** Trace 未来落盘实现的保守轮转上限；本卡只冻结策略，不执行 IO。 */
    public static final int TRACE_MAX_RUNS = 20;
    public static final long TRACE_MAX_RUN_BYTES = 8L * 1024L * 1024L;
    public static final long TRACE_MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    public static final boolean TRACE_SCREENSHOT_DEFAULT = true;
    public static final boolean TRACE_LOCAL_ONLY = true;

    private ContractVersions() { }
}
