# ADR-003：M3-01 声明式工具注册表

- 状态：已接受
- 日期：2026-09-21
- 任务卡：`M3-01`
- 决策人：用户确认

## 决策摘要

建立纯 Java `ToolSpec` / `ToolParameter` / `ToolRegistry` 声明层，把当前 `ActionSchema` 的 25 个动作转换为可查询的工具能力描述。首版只描述工具，不持有 Android 执行器、不执行策略判断、不接入 `AgentEngine.exec()`；后续由 M3-02 至 M3-07 分步接入。

## 用户确认的边界

1. **动作集合**：Registry 严格覆盖当前 `ActionSchema` 全部 25 个动作，包括 `back`/`home` 兼容动作和 `ask`/`done`/`fail` 控制动作；本卡不新增动作。
2. **参数 schema**：声明关键参数的必填性、类型、整数/小数范围和枚举值；首版不实现完整 JSON Schema 解析器。
3. **重复注册**：同名工具重复注册直接抛出明确错误；禁止静默覆盖安全属性、风险级别或参数约束。
4. **控制动作**：`ask`、`done`、`fail` 登记为 `controlAction=true`、`changesScreen=false` 的声明型工具；不得由本卡的 Registry 直接执行。

## 首版工具元数据

每个工具声明：

- `name`、`description`；
- 参数列表及 `required`、`type`、`minimum`、`maximum`、`enum`；
- `changesScreen`；
- `requiresTargetPackage`；
- `risk`：`NORMAL`、`SENSITIVE`、`HIGH`；
- `allowedInVisionMode`；
- `controlAction`。

生成的参数 schema 默认 `additionalProperties=false`，避免模型静默携带未声明字段。`ToolRegistry.builtIn()` 从 `ActionSchema.EXEC_ACTIONS` 与 `CONTROL_ACTIONS` 生成，动作集合变更会自然触发门禁。

## 安全和兼容约束

- `feature_tool_router` 继续默认关闭。
- `AgentEngine.java` 不得引用 `ToolRegistry`，旧 `exec()` 路径保持唯一实际执行路径。
- `ToolSpec` 不携带 executor、线程、Context、AccessibilityNodeInfo 或 Android 类型。
- `ask`、`done`、`fail` 只是完整动作词表声明，终态处理仍由任务循环负责。
- 当前 schema 是声明层，不等于 M3-02 已完成参数校验；无效参数不得在本卡声称已阻止触碰设备。

## 验收

```sh
python3 tests/m3_tool_registry_guard.py
mkdir -p .check-tmp/tools
javac --release 11 -encoding UTF-8 -d .check-tmp/tools \
  tests/ToolRegistryTest.java \
  agent-lite/src/com/dsh/agentlite/ActionSchema.java \
  agent-lite/src/com/dsh/agentlite/tools/*.java
java -cp .check-tmp/tools ToolRegistryTest
```

静态门禁覆盖 22 项，JVM 行为测试覆盖 Registry 完整覆盖、控制动作标记、schema 范围、重复注册和零 Android 依赖。

## 设备缺口

本卡无实体机也可验收，因为没有接入 Android 执行器。以下内容留给后续卡和设备验证：实际 `ToolRouter` 调用、无效参数不触碰设备、审批等待取消、stop 取消执行、旧 `exec()` 与新 Router 的真机结果一致。

## M3-02 参数校验增量

在原声明层上新增纯 Java `ValidationResult`，`ToolSpec.validate(Map<String, String>)` 按同一份参数声明检查：必填参数、未知参数、null、string/integer/number/boolean 类型、数值范围和枚举值。`ToolRegistry.validate(name, arguments)` 统一处理未知工具，并返回结构化错误码；错误说明不回显输入值。

校验层仍不接入 `AgentEngine` 或设备执行。M3-03/M3-04 完成 `ToolInvocation` 与 Router 接线前，现有旧 `exec()` 路径不变；因此“无效参数不触碰设备”的路由级验收留给后续接线卡。

验收增量：

```sh
python3 tests/m3_tool_validation_guard.py
# ToolRegistryTest 覆盖 M3-01/M3-02，共 58 项 JVM 断言
```

## 回滚

不启用 `feature_tool_router`，旧 AgentEngine 行为不变。代码回滚只删除 `tools/`、`tests/ToolRegistryTest.java`、`tests/m3_tool_registry_guard.py`、`tests/m3_tool_validation_guard.py`、Makefile M3 条目和本 ADR；禁止删除 `ActionSchema`、旧 `exec()` 或用户数据。
