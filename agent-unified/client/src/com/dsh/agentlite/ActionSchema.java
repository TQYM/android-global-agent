// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 动作词表的**单一事实源**（手册 §16.4 UX-10、§16.5 第 2 步）。
 *
 * <p>此前词表分散在三处：给模型看的提示词文本、{@code exec()} 的 switch 分支、
 * 以及引擎循环里的终态判定。三者一旦不同步，就出现「隐藏动作」——已经在
 * {@code exec()} 里实现、却从没告诉模型（{@code back}/{@code home} 就是如此），
 * 或者提示词里承诺了、实际执行会抛 {@code unknown action} 的动作。
 *
 * <p>本类把「有哪些动作、词表是哪个版本」收敛到一处，并提供：
 *
 * <ul>
 *   <li>{@link #SCHEMA_VERSION}：词表版本，动作集合或字段语义变化时递增——
 *       排障时对得上「模型看到的词表」和「设备跑的代码」；</li>
 *   <li>{@link #isRegistered}：执行前校验，未知动作只回灌一次短错误；</li>
 *   <li>门禁依据：{@code tests/spec_ux_001_guard.py} 与契约检查脚本用它比对两端词表。</li>
 * </ul>
 *
 * <p><b>加动作的正确顺序</b>：先在本类登记 → 再实现 {@code exec()} 分支 →
 * 再补提示词文本 → 递增 {@link #SCHEMA_VERSION}。
 */
public final class ActionSchema {

    /** 词表版本。改动作集合/字段语义必须递增（两端与排障靠它对版本）。 */
    public static final String SCHEMA_VERSION = "2026-10-10.1";

    /** 执行型动作：由 {@code AgentEngine.exec()} 实现，必须有对应的 case 分支。 */
    public static final Set<String> EXEC_ACTIONS = unmodifiable(
            // 直达层：API / DeepLink / 设置 / 应用
            "open_api", "open_url", "setting", "app", "list_apps", "remote",
            "wifi", "bluetooth", "brightness", "volume", "statusbar", "wake",
            // 界面层：节点优先，坐标兜底
            "tap", "longpress", "swipe", "scroll", "key", "edge_back", "back", "home",
            "text", "wait");

    /** 终态/交互型动作：由任务循环处理（不进 exec）。 */
    public static final Set<String> CONTROL_ACTIONS = unmodifiable("ask", "fail", "done");

    /** 动作是否已登记（执行前校验用；未登记的动作不得执行）。 */
    public static boolean isRegistered(String action) {
        if (action == null || action.isEmpty()) return false;
        return EXEC_ACTIONS.contains(action) || CONTROL_ACTIONS.contains(action);
    }

    /** 词表总条目数（门禁与诊断用）。 */
    public static int size() {
        return EXEC_ACTIONS.size() + CONTROL_ACTIONS.size();
    }

    private static Set<String> unmodifiable(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
    }

    private ActionSchema() { }
}
