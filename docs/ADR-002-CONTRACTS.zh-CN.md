# ADR-002：M0-02 结构化契约与隐私边界

- 状态：已接受
- 日期：2026-09-20
- 任务卡：`M0-02`
- 决策人：用户确认

## 决策摘要

冻结 `ScreenSnapshot`、`ToolInvocation`、`TraceEvent`、`ScreenState` 四类纯 Java 契约。契约模型不依赖 Android、`org.json` 或第三方 JSON 库；本卡只定义数据模型、稳定字段渲染和兼容规则，不接入实际采集、执行或文件 IO。

## 用户确认的边界

1. **快照隐私**：仅对 `password=true` 节点脱敏，文本、描述和 hint 保留原始值；密码字段统一输出 `[password]`。其它敏感内容由后续出网/Trace 采集层另行评审，不在本卡擅自扩大或缩小范围。
2. **Trace 产物**：默认允许保存脱敏截图与快照；Trace 只在本地按任务目录保存，不上传云端。
3. **Trace 轮转**：后续落盘实现固定容量轮转，默认最多 20 个任务目录、单任务 8 MiB、总量 64 MiB；达到上限删除最旧任务，具体删除实现属于 M5。
4. **旧流程复现**：没有 `ScreenState` 的旧 `FlowPlan` 标记为 `LEGACY_UNGUARDED`；仅明确标记为 `LOW` 或 `NORMAL` 的风险允许直接复现；空值、未知值和高风险必须交回模型和现有守卫重新决策。
5. **序列化**：不可变 Java 对象 + 手写稳定字段渲染器，保持纯 Java、零第三方运行时依赖和桌面 JVM 可测性。

## 契约版本

- `SNAPSHOT_SCHEMA_VERSION = 1`
- `TOOL_SCHEMA_VERSION = 1`
- `TRACE_SCHEMA_VERSION = 1`
- `MACRO_SCHEMA_VERSION = "3.0"`

新增可选字段不递增版本；改变字段语义、删除字段或改变默认隐私策略必须递增对应版本并提供迁移规则。Trace 事件只允许 `perception`、`llm_request`、`llm_response`、`tool_call`、`approval`、`verification`、`terminal`、`error` 八类。

## 安全和兼容约束

- `AccessibilityNodeInfo` 不得进入任何契约对象。
- 工具调用状态终态为 `SUCCESS`、`ERROR`、`CANCELLED`，终态不可逆。
- Trace `payload` 必须由上层先完成脱敏，契约层不接受完整凭据进入文件名或事件引用。
- `ScreenState` 的新流程必须带 `macro_schema_version`、指纹和可见节点摘要。
- 本卡不改变现有 `FlowMemory`、`SensitiveGuard`、`AgentEngine.exec()` 或默认功能开关行为。

## 验收

```sh
python3 tests/m0_contract_guard.py
mkdir -p .check-tmp/contracts
javac --release 11 -encoding UTF-8 -d .check-tmp/contracts \
  tests/ContractModelsTest.java \
  agent-lite/src/com/dsh/agentlite/ContractVersions.java \
  agent-lite/src/com/dsh/agentlite/contracts/*.java
java -cp .check-tmp/contracts ContractModelsTest
```

本卡通过标准：静态契约门禁和 JVM 测试均无失败，Android APK 可构建；真机不需要执行，因为模型尚未接入运行时。

## 回滚

关闭所有 M0 新功能开关即可保持旧运行路径。本卡若需代码回滚，只删除 `ContractVersions.java`、`contracts/`、对应测试/门禁和 Makefile 条目；禁止删除已有 FlowMemory、任务历史或用户配置。
