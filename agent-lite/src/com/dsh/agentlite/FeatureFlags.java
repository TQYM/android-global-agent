// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.SharedPreferences;

/**
 * M0-01 成熟模块功能开关的唯一读取入口。
 *
 * <p>本类只保存配置，不接入任何运行时行为。所有新模块默认关闭；策略与轨迹
 * 允许先以影子模式采集诊断信息，但影子模式本身也不改变旧执行路径。
 */
public final class FeatureFlags {
    /** 功能开关键名与默认值契约版本。新增可选键不升版本；改默认语义必须升版本。 */
    public static final int SCHEMA_VERSION = 1;

    public static final String POLICY_ENGINE = "feature_policy_engine";
    public static final String PERCEPTOR_V2 = "feature_perceptor_v2";
    public static final String TOOL_ROUTER = "feature_tool_router";
    public static final String REPLAY_GUARD = "feature_replay_guard";
    public static final String TRACE_RECORDER = "feature_trace_recorder";
    public static final String SKILL_SYSTEM = "feature_skill_system";
    public static final String PROVIDER_REGISTRY = "feature_provider_registry";
    public static final String VIRTUAL_DISPLAY = "feature_virtual_display";

    /** 仅用于未来安全策略灰度，不等同于启用 PolicyEngine。 */
    public static final String POLICY_SHADOW = "feature_policy_shadow";
    /** 仅用于未来轨迹灰度，不等同于启用 TraceRecorder。 */
    public static final String TRACE_SHADOW = "feature_trace_shadow";

    private final SharedPreferences sp;

    public FeatureFlags(SharedPreferences sp) {
        if (sp == null) throw new IllegalArgumentException("SharedPreferences 不能为空");
        this.sp = sp;
    }

    public boolean policyEngine() { return enabled(POLICY_ENGINE, false); }
    public boolean perceptorV2() { return enabled(PERCEPTOR_V2, false); }
    public boolean toolRouter() { return enabled(TOOL_ROUTER, false); }
    public boolean replayGuard() { return enabled(REPLAY_GUARD, false); }
    public boolean traceRecorder() { return enabled(TRACE_RECORDER, false); }
    public boolean skillSystem() { return enabled(SKILL_SYSTEM, false); }
    public boolean providerRegistry() { return enabled(PROVIDER_REGISTRY, false); }
    public boolean virtualDisplay() { return enabled(VIRTUAL_DISPLAY, false); }

    /** 返回策略影子模式状态；默认开启但不改变执行结果。 */
    public boolean policyShadow() { return enabled(POLICY_SHADOW, true); }

    /** 返回轨迹影子模式状态；默认开启但不改变执行结果。 */
    public boolean traceShadow() { return enabled(TRACE_SHADOW, true); }

    /** 供后续灰度接入使用；未知键拒绝写入，避免把任意配置混入开关命名空间。 */
    public void set(String key, boolean value) {
        if (!isKnown(key)) throw new IllegalArgumentException("未知功能开关: " + key);
        sp.edit().putBoolean(key, value).apply();
    }

    private boolean enabled(String key, boolean defaultValue) {
        return sp.getBoolean(key, defaultValue);
    }

    private static boolean isKnown(String key) {
        return POLICY_ENGINE.equals(key)
                || PERCEPTOR_V2.equals(key)
                || TOOL_ROUTER.equals(key)
                || REPLAY_GUARD.equals(key)
                || TRACE_RECORDER.equals(key)
                || SKILL_SYSTEM.equals(key)
                || PROVIDER_REGISTRY.equals(key)
                || VIRTUAL_DISPLAY.equals(key)
                || POLICY_SHADOW.equals(key)
                || TRACE_SHADOW.equals(key);
    }
}
