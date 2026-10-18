# ADR-001：成熟模块功能开关与默认配置

- 状态：已接受
- 日期：2026-09-20
- 任务卡：`M0-01`
- 适用范围：`agent-lite` 运行时配置与共享门禁

## 背景

成熟模块需要分阶段整合。直接让新感知、工具路由、复现、技能、Provider 或虚拟屏进入默认路径，会使成功率、步数、授权频率和回滚边界无法比较。项目当前保持纯 Java、Android 11+、无 Gradle/AndroidX/第三方运行时依赖，并要求普通用户的零 root 路径持续可用。

## 决策

新增 `FeatureFlags` 作为功能开关的唯一读取入口，并由 `Prefs.featureFlags()` 暴露给后续模块。M0-01 只建立配置契约，不接入 `AgentEngine` 或任何执行路径。

| 开关 | 默认值 | 本卡语义 |
|---|---:|---|
| `feature_policy_engine` | `false` | 后续接入动作级策略内核 |
| `feature_perceptor_v2` | `false` | 后续接入结构化感知快照 |
| `feature_tool_router` | `false` | 后续包住旧 `exec()` |
| `feature_replay_guard` | `false` | 后续接入流程复现前页面守卫 |
| `feature_trace_recorder` | `false` | 后续接入结构化轨迹 |
| `feature_skill_system` | `false` | 后续接入技能目录 |
| `feature_provider_registry` | `false` | 后续接入 Provider 分层 |
| `feature_virtual_display` | `false` | 后续显式高级模式，不影响普通模式 |
| `feature_policy_shadow` | `true` | 预留策略影子诊断，不改变执行结果 |
| `feature_trace_shadow` | `true` | 预留轨迹影子诊断，不改变执行结果 |

契约版本为 `FeatureFlags.SCHEMA_VERSION = 1`。新增可选开关不升版本；改变既有键的默认语义或删除键必须升版本并提供迁移说明。

## 约束

- 未知键禁止通过 `FeatureFlags.set()` 写入。
- 开关值仅存于现有 `agent_prefs`，不新增文件格式和迁移路径。
- 本卡不改变 `SensitiveGuard`、停止按钮、纯视觉降级、FlowMemory 或旧 `exec()` 行为。
- 开启任何新模块前，必须先完成对应阶段的契约、门禁和专项验证。

## 验收与回滚

可复现验证：

```sh
python3 tests/m0_feature_flags_guard.py
```

回滚方式：关闭所有新模块开关；本卡尚未接入默认运行路径，因此删除 `FeatureFlags.java`、`Prefs.featureFlags()` 和对应门禁即可恢复到 M0-01 前代码。不得删除用户的 `agent_prefs`、FlowMemory 或任务历史。

已知未完成项：指标基线、ScreenSnapshot/Tool/Trace/Replay 数据契约冻结、ADR-002 至 ADR-005、上游许可证总清单和真机专项验证属于后续 M0 卡片。
