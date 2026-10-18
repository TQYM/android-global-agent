# Agent Lite 成熟模块全面整合开发规划

> **目标读者**：DeepSeek4.1flash、接手项目的智能体、人类开发者
>
> **适用项目**：`agent-lite` 运行时 + `repo` 共享文档/测试 + `agent-unified` 发布同步 + 外部评测目录
>
> **规划状态**：部分基础切片已有实现；本文件的目标架构不代表所有模块已经集成。2026-10-06 的当前进度见[全景手册 §18](<../PROJECT_OVERVIEW.zh-CN.md#18-当前进度评估与未来开发入口2026-10-06>)，面向老年人与手机入门者的后续优先级、任务卡与验收见本文件 §23。
>
> **模型角色澄清（2026-10-06）**：用户指定的产品运行模型原名为 `de 4.1flash`；历史正文把 `DeepSeek4.1flash` 同时写成接手代码的实施模型，属旧写作背景，不是本轮分析者身份或产品能力证明。本轮分析者与产品运行模型分离。身份核对与条件方案以 §23.2 为准；历史标题、任务编号与决策不整体改写。
>
> **基线版本**：2026-09-20；当前 Agent Lite 为纯 Java、无 Gradle、无 AndroidX、无第三方运行时依赖、Android 11+、AccessibilityService 主链路

---

## 0. 给 DeepSeek4.1flash 的执行总规约

你是本项目的实施模型。你的任务不是把上游代码机械复制进仓库，而是把 9 类候选能力转换为与 Agent Lite 兼容的 Java 模块、资源、测试和文档。

### 0.1 必须遵守

1. **先读后改**：每次开始一个阶段前，必须读取本文件对应阶段、`agent-lite/README.md`、`docs/INTERACTION_LOGIC_SPEC.zh-CN.md`、相关源码和项目日志尾部。
2. **小步提交**：一个任务卡只完成一个可验证能力；不得在同一改动中同时重写感知、执行、模型和 UI。
3. **保持零 root 默认路径**：普通用户不安装 Shizuku、不安装额外服务、不授予 root 时，原有任务能力必须仍可运行。
4. **保持纯 Java 构建**：默认不得引入 Gradle、Kotlin、AndroidX、第三方 AAR 或新的运行时下载依赖。若某阶段必须引入依赖，先提交 ADR 和可回滚 PoC。
5. **安全优先**：任何策略变更都不能降低当前敏感接管、用户授权、纯视觉红线和停止按钮的保护级别。
6. **上游只作参考**：ClosePaw 的 Kotlin 源码、mobilerun 的 Python 源码、AndroidWorld 的 Python 环境和 Open-AutoGLM 的提示词都必须经过 Java/本项目语义适配。
7. **三处一致**：动作词表、`software-interaction-logic` Skill、`docs/INTERACTION_LOGIC_SPEC.zh-CN.md` 必须同步；动作或阈值变化必须跑对应守卫。
8. **禁止凭感觉宣布完成**：每个阶段必须有源码测试、免设备测试、真机测试或明确记录为“未执行”。
9. **禁止泄露凭据**：不把 API Key、账号令牌、TLS 私钥、真实手机号、真实验证码写入源码、日志、测试样例或轨迹。
10. **不要覆盖用户改动**：发现工作树有未提交内容时，先区分是否与当前阶段有关；无关改动不回滚。

### 0.2 每次开发回合固定流程

```text
读取日志尾部与阶段任务
  ↓
读取目标源码、测试、上游隔离副本和本文件对应章节
  ↓
写 ADR/数据契约/迁移说明（若有行为或格式变化）
  ↓
先写失败测试或守卫
  ↓
实现一个最小垂直切片
  ↓
跑免设备门禁
  ↓
跑 Android 编译
  ↓
跑真机/模拟器专项验证
  ↓
检查安全、性能、日志脱敏和兼容性
  ↓
更新文档、CHANGELOG、工作日志
  ↓
记录提交点、测试命令、已知风险和回滚方式
```

### 0.3 禁止的工作方式

- 禁止一次性重写 `AgentEngine.java` 全文件。
- 禁止把 ClosePaw `tool/` 目录原样复制到 `agent-lite/src` 后声称完成。
- 禁止把 AndroidWorld 打包进 APK。
- 禁止把 Shizuku 虚拟屏设为默认执行路径。
- 禁止把 Open-AutoGLM 的整段系统提示词无审校拼接到现有 `SCHEMA` 提示词。
- 禁止用“编译通过”代替敏感任务、节点屏蔽任务、流程偏离任务的行为验收。
- 禁止为了通过测试放宽安全规则、提高超时上限或跳过红线检查。

---

## 1. 当前基线与最终目标

### 1.1 当前基线

当前 `agent-lite` 已有以下能力，不应被新模块破坏：

- `AgentEngine`：自然语言任务、感知、单 JSON 动作、执行、等待、终态闭环；
- `AgentA11yService`：无障碍节点采集、手势、文本输入、截图；
- 纯视觉模式：连续空节点且截图有效时切换比例坐标；
- `FlowMemory` / `FlowPlan`：成功路径记忆、骨架匹配、复现；
- `SensitiveGuard`：支付、验证、协议、权限等敏感场景接管；
- `LlmClient`：OpenAI 兼容接口、取消、配额/上游错误分类、响应限制；
- `EngineLog`：异步日志、轮转和基础凭据脱敏；
- `PopupGuard`：弹窗、广告、权限、异常跳转处置；
- `KeepAliveService`：前台服务和 OEM 保活；
- `Makefile`：Go、契约、UI、JVM 等免设备门禁；
- `agent-lite/build-mac.sh`：纯 SDK 命令行构建 APK。

### 1.2 最终目标

最终形成以下闭环：

```text
用户任务
  ↓
任务预处理：应用解析、技能目录、风险分级、完成判据
  ↓
安全上下文：当前 App、目标 App、AppTier、ApprovalMode
  ↓
Perceptor 感知：多根节点、空文本补全、密码脱敏、结构化快照
  ↓
工具层：ToolRegistry → Validation → PolicyEngine → Approval → Executor
  ↓
动作执行：AndroidPlatform（默认无障碍）
  ├─ 普通模式：主显示屏 AccessibilityService
  ├─ 视觉兜底：截图 + 比例坐标
  └─ 高级模式：Shizuku VirtualDisplay（显式开启）
  ↓
状态复验：ScreenState + expectation + replay guard
  ↓
轨迹记录：trace.jsonl + meta.json + artifacts/，全程脱敏
  ↓
完成/失败/用户停止
  ↓
FlowMemory 成功路径入库 / 偏离交接 / 评测 reward
```

### 1.3 四种整合形态

| 形态 | 模块 | 是否进入 APK | 说明 |
|---|---|---:|---|
| 核心运行时 | `01` 感知、`02` 策略工具、`03` 轨迹、`05` 复现守卫 | 是 | Java 适配后默认启用，失败可降级到旧逻辑 |
| 资源能力 | `04` 技能、`09` 中文知识资产 | 是 | 作为 assets/资源表进入，必须有版本和审校状态 |
| 可选运行时 | `06` LLM 提供商、`07` 虚拟屏 | `06` 分阶段，`07` 可选 | 不得影响默认零 root 启动和执行 |
| 外部基础设施 | `08` AndroidWorld | 否 | 独立 Python 评测桥接层，不进入 APK |

---

## 2. 目标目录与代码边界

### 2.1 Agent Lite 新增 Java 包

最终建议建立以下包；阶段初期可以先放在 `com.dsh.agentlite`，每个阶段稳定后再移动到子包。

```text
agent-lite/src/com/dsh/agentlite/
├── perception/
│   ├── PerceptionElement.java
│   ├── ScreenSnapshot.java
│   ├── PerceptionConfig.java
│   ├── Perceptor.java
│   ├── PerceptorDiagnostics.java
│   └── ScreenSummary.java
├── tool/
│   ├── ToolSpec.java
│   ├── ToolRegistry.java
│   ├── ToolInvocation.java
│   ├── ToolRouter.java
│   ├── ToolCallState.java
│   ├── ValidationResult.java
│   └── ToolExecutionResult.java
├── policy/
│   ├── AppTier.java
│   ├── ApprovalMode.java
│   ├── AppClassifier.java
│   ├── PolicyDecision.java
│   ├── PolicyEngine.java
│   └── PolicyConfig.java
├── trace/
│   ├── TraceRecorder.java
│   ├── FileTraceRecorder.java
│   ├── TraceEvent.java
│   ├── TraceArtifact.java
│   ├── TraceRedactor.java
│   └── TraceRecorderFactory.java
├── replay/
│   ├── ReplaySchema.java
│   ├── ScreenState.java
│   ├── ScreenStateMatcher.java
│   ├── ReplayGuard.java
│   └── ReplayHandoff.java
├── skills/
│   ├── SkillFrontmatter.java
│   ├── SkillFrontmatterParser.java
│   ├── AppSkillCatalog.java
│   ├── AgentSkillCatalog.java
│   ├── SkillManager.java
│   └── SkillIntegrity.java
├── llm/
│   ├── ProviderSpec.java
│   ├── ProviderRegistry.java
│   ├── LlmError.java
│   ├── LlmRetryPolicy.java
│   └── SseEventParser.java
└── platform/
    ├── AndroidPlatform.java
    ├── AccessibilityPlatform.java
    ├── VirtualDisplayPlatform.java
    └── PlatformLease.java
```

### 2.2 与现有类的关系

| 新模块 | 现有接入点 | 接入原则 |
|---|---|---|
| `Perceptor` | `AgentA11yService`、`AgentEngine` | 先生成兼容 `NodeInfo`，稳定后再让模型消费 `ScreenSnapshot` |
| `PolicyEngine` | `SensitiveGuard`、`AgentEngine.exec()` | PolicyEngine 是动作级策略；SensitiveGuard 保留为内容级硬守卫，两者取更严格结果 |
| `ToolRouter` | `AgentEngine.runLoop()`、`exec()` | 先包住旧 `exec()`，不要立即删除旧分支 |
| `TraceRecorder` | `EngineLog`、`TaskHistory` | Trace 是步级审计；EngineLog 继续做诊断日志；TaskHistory 继续做用户终态历史 |
| `ReplayGuard` | `FlowMemory.replayFlow()` | 复现前检查屏幕状态；偏离时交还现有 Agent 主循环 |
| `SkillManager` | Prompt 构造、`AgentEngine` | 只追加受长度限制的技能上下文，不改变动作安全优先级 |
| `ProviderRegistry` | `LlmClient` | 先抽出错误分类和重试纯逻辑，再改变实际 HTTP 链路 |
| `VirtualDisplayPlatform` | `AndroidPlatform` 抽象 | 默认不启用，只有用户明确开启且 Shizuku 可用才进入 |
| AndroidWorld Bridge | `tests/androidworld_bridge/` | 通过 HTTP/ADB/文件协议与 APK 交互，不进入生产包 |

### 2.3 不变量

整合期间必须保持：

- 每轮模型最多输出一个动作对象；
- 节点编号每轮重新生成，不能使用过期编号；
- 动作完成不等于任务完成，必须有屏幕或业务证据；
- 终态只有 `done`、`fail`、用户停止；
- 敏感动作在任何执行平台上都必须重新检查；
- 轨迹默认脱敏，截图和节点树遵循 AppTier 掩码策略；
- 虚拟屏不能绕过用户授权和 BLOCKED 策略；
- 新格式必须可读旧的 `FlowMemory` 和旧任务历史，或提供显式迁移。

---

## 3. 数据契约与版本策略

在实现前先固定数据格式，防止各阶段各自发明字段。

### 3.1 `ScreenSnapshot` 契约

```json
{
  "schema_version": 1,
  "timestamp_ms": 0,
  "package_name": "com.example.app",
  "activity_name": "com.example.MainActivity",
  "display_id": 0,
  "screen_width": 1080,
  "screen_height": 2400,
  "keyboard_visible": false,
  "masked": false,
  "elements": [
    {
      "index": 0,
      "text": "原始文本或空串",
      "description": "内容描述",
      "hint_text": "输入提示",
      "resource_id": "id/name",
      "class_name": "android.widget.Button",
      "clickable": true,
      "enabled": true,
      "checked": false,
      "scrollable": false,
      "password": false,
      "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 80},
      "source": "text|description|hint|child_enrichment"
    }
  ]
}
```

规则：

- `text`、`description`、`hint_text` 保留原始值；比较时另行规范化；
- `password=true` 时不得保留真实文本，统一替换为 `[password]`；
- `AccessibilityNodeInfo` 不得进入快照对象；
- `schema_version` 只在不兼容变化时递增；新增可选字段不递增；
- `elements` 为空不代表任务失败，必须结合截图、包名和环境态判断。

### 3.2 `ToolInvocation` 契约

```json
{
  "call_id": "turn-0007-call-01",
  "tool_name": "tap",
  "arguments": {"index": 3},
  "package_name": "com.example.app",
  "destination_package": null,
  "created_at_ms": 0,
  "state": "VALIDATING",
  "risk": "NORMAL",
  "approval": "NOT_REQUIRED"
}
```

状态只能按以下方向变化：

```text
VALIDATING
  ├─ invalid → ERROR
  └─ valid → POLICY_CHECK
                  ├─ Deny → CANCELLED
                  ├─ Allow → EXECUTING
                  └─ AskUser → AWAITING_APPROVAL
                                  ├─ denied/timeout → CANCELLED
                                  └─ approved → EXECUTING
                                                   ├─ exception → ERROR
                                                   └─ success → SUCCESS
```

任何终态必须不可逆，并写入 Trace。

### 3.3 `ScreenState` 与复现格式

```json
{
  "macro_schema_version": "3.0",
  "package_name": "com.example.app",
  "activity_name": "com.example.MainActivity",
  "display_width": 1080,
  "display_height": 2400,
  "visible_nodes": [
    {"key":"button|提交|id/submit|100,200,500,300","role":"button","text":"提交"}
  ],
  "fingerprint": "sha256-or-local-stable-fingerprint",
  "captured_at_ms": 0
}
```

兼容规则：

- 读取旧 `FlowPlan` 时没有 `ScreenState`，视为 `LEGACY_UNGUARDED`，只允许低风险复现；
- 新录制流程必须写入 `macro_schema_version`；
- 匹配失败时不得自动降阈值重试超过一次；
- 偏离报告必须包含步骤号、期望摘要、当前摘要、相似度和处置方式。

### 3.4 Trace 格式

目录：

```text
<trace_root>/<run_id>/
├── meta.json
├── trace.jsonl
└── artifacts/
    ├── step-0001-screen.jpg
    ├── step-0001-snapshot.json
    └── step-0001-a11y.json
```

每行事件至少包含：

```json
{
  "seq": 1,
  "ts_ms": 0,
  "run_id": "uuid",
  "step": 1,
  "event": "perception|llm_request|llm_response|tool_call|approval|verification|terminal|error",
  "payload": {},
  "artifact_refs": [],
  "redaction": "applied"
}
```

禁止把完整 API Key、Bearer token、JWT、密码、验证码、剪贴板秘密、银行卡号写进 `payload` 或文件名。

### 3.5 版本与迁移

- `SCHEMA_VERSION`：动作/字段契约版本，先新增 `AgentEngine.SCHEMA_VERSION` 常量；
- `SNAPSHOT_SCHEMA_VERSION`：屏幕快照版本；
- `TRACE_SCHEMA_VERSION`：轨迹版本；
- `MACRO_SCHEMA_VERSION`：流程复现版本；
- `SKILL_CATALOG_VERSION`：技能 frontmatter/目录规则版本；
- 版本变化必须写 `CHANGELOG.md` 的 `Added / Changed / Deprecated / Removed`；
- 迁移器必须幂等（重复执行结果一致）；
- 迁移失败必须保留原文件，生成 `.migration-error` 诊断，不静默删除旧数据。

---

## 4. 总体阶段图与依赖

```text
M0 基线与契约冻结
  ↓
M1 安全策略内核 ─────────────┐
  ↓                         │
M2 感知引擎适配 ─────────────┤
  ↓                         │
M3 工具路由与执行包装 ───────┤
  ↓                         │
M4 流程复现守卫 ─────────────┤
  ↓                         │
M5 结构化轨迹与回放 ─────────┤
  ├────────→ M6 技能系统     │
  ├────────→ M7 中文知识资产  │
  └────────→ M8 Provider 层   │
                              │
M9 AndroidWorld 外部评测桥接 ─┘
  ↓
M10 Shizuku 虚拟屏高级模式
  ↓
M11 全量回归、发布、观察期、收尾
```

### 4.1 阶段优先级

| 阶段 | 名称 | 主要输出 | 风险 | 是否阻塞后续 |
|---|---|---|---|---|
| M0 | 基线与契约冻结 | 分支、ADR、指标、开关、守卫骨架 | 中 | 是 |
| M1 | 安全策略 | AppTier、PolicyEngine、审批路由 | 高 | 是 |
| M2 | 感知引擎 | Perceptor、快照、脱敏、截断 | 高 | 是 |
| M3 | 工具路由 | ToolSpec、Registry、Router、兼容旧 exec | 高 | 是 |
| M4 | 流程复现 | 状态匹配、偏离停止、Agent 交接 | 高 | 是 |
| M5 | 结构化轨迹 | TraceRecorder、redactor、查看器桥接 | 高 | 否但强烈建议 |
| M6 | 技能系统 | App Skill、Agent Skill、资产校验 | 中 | 否 |
| M7 | 知识资产 | 应用别名、中文经验、反思规则 | 中 | 否 |
| M8 | LLM 层 | Provider 注册、错误分类、流式重试 | 高 | 否 |
| M9 | AndroidWorld | 任务桥接、reward、回归报告 | 中 | 发布前必须完成最小集 |
| M10 | VirtualDisplay | Shizuku 隔离屏、租约、输入截图 | 极高 | 可选，不阻塞默认发布 |
| M11 | 发布收尾 | 全量门禁、灰度、回滚、归档 | 高 | 最终出口 |

---

## 5. M0：基线与契约冻结

### 5.1 任务目标

在写新运行时代码前，建立可比较基线，避免整合后不知道成功率、步数、授权频率和性能是否变化。

### 5.2 必做任务

1. 建立功能开关：
   - `feature_policy_engine`
   - `feature_perceptor_v2`
   - `feature_tool_router`
   - `feature_replay_guard`
   - `feature_trace_recorder`
   - `feature_skill_system`
   - `feature_provider_registry`
   - `feature_virtual_display`
2. 所有开关默认值：
   - 安全策略、轨迹：可先影子模式；
   - 感知、路由、复现：默认关闭直到兼容测试完成；
   - 虚拟屏：默认关闭；
   - 知识资产：默认只启用已审校条目。
3. 记录基线指标：
   - 任务成功率；
   - 平均/中位步骤数；
   - 平均任务时长；
   - `ask` 次数与误触率；
   - 看门狗终止率；
   - 模型调用失败率；
   - 纯视觉切换率；
   - 崩溃、ANR、内存峰值、电量消耗。
4. 建立 ADR：
   - ADR-001：保持纯 Java/零 root 默认路径；
   - ADR-002：双轨安全模型；
   - ADR-003：Trace 与 EngineLog 分工；
   - ADR-004：VirtualDisplay 只作显式高级模式；
   - ADR-005：AndroidWorld 为外部评测系统。
5. 把所有上游来源、许可证和复制边界登记到 `docs/THIRD_PARTY_NOTICES.zh-CN.md`。

### 5.3 M0 验收

```sh
make check
sh agent-lite/build-mac.sh
python3 tests/apk_readability.py agent-lite/build/agent-lite.apk
```

另外必须人工确认：

- 旧 APK 可安装启动；
- 设置页可录入运行时 API Key；
- API Key 不出现在 APK、日志和测试产物；
- 现有 FlowMemory、敏感接管、停止按钮仍可工作。

### 5.4 M0 回滚

只删除新开关、ADR 和文档，不触碰现有运行时逻辑。若 M0 期间修改了公共守卫，使用对应提交点回退，但禁止 `git reset --hard` 覆盖用户改动。

---

## 6. M1：安全策略内核

来源：`_local/mature-modules/02-tool-policy-system/`。

### 6.1 目标

将当前关键词式 `SensitiveGuard` 扩展为“动作 + 当前应用 + 目标应用 + 审批模式”的分级策略，同时保留 `SensitiveGuard` 作为内容级硬守卫。

### 6.2 实现顺序

#### M1.1 纯逻辑模型

新增：

- `AppTier`: `NORMAL / CAUTIOUS / BLOCKED`；
- `ApprovalMode`: `ALWAYS_ASK / AUTO_APPROVE / SMART`；
- `PolicyDecision`: `Allow / Deny / AskUser`；
- `PolicyInput`: 工具名、参数、当前包名、目标包名、任务来源；
- `AppClassifier`: 默认内置分级 + 用户覆盖 + 会话覆盖；
- `PolicyEngine`: 固定判定顺序。

判定顺序必须固定：

1. 非改变屏幕的内部工具直接允许；
2. 返回/主页等逃生动作允许；
3. BLOCKED 应用硬拒绝；
4. 浏览器脚本等特殊动作先于普通白名单处理；
5. 用户 allow-list；
6. 根据 ApprovalMode 判断允许或询问。

#### M1.2 与 `SensitiveGuard` 合并

策略结果取最严格值：

```text
SensitiveGuard 命中 → 必须 Ask/Takeover
PolicyEngine Deny → Deny
PolicyEngine Ask → Ask
两者均 Allow → Allow
```

禁止出现“PolicyEngine Allow 覆盖 SensitiveGuard 命中”的路径。

#### M1.3 目标应用策略

`open_app`、深链、跨应用跳转必须把目标包名作为 `destinationPackage`。审批主体优先使用目标包名，而不是此前台包名。

#### M1.4 感知掩码

BLOCKED 应用或其多窗口根存在时：

- 节点快照返回 masked；
- 截图不发送给模型；
- 轨迹只记录包名 hash、策略结果和原因；
- 用户界面可显示“受安全策略限制”，不得显示敏感内容。

### 6.3 测试清单

桌面 JVM 测试：

- 三种 AppTier × 三种 ApprovalMode；
- 当前 App 与目标 App 不同；
- BLOCKED 在 AUTO_APPROVE 下仍拒绝；
- escape action 不被 BLOCKED 锁死；
- browser special rule；
- allow-list 不绕过硬拒绝；
- SensitiveGuard 与 PolicyEngine 取更严格结果；
- 无包名时 fail closed；
- 策略配置损坏时使用安全默认值。

真机测试：

- 普通 App 点击无需多余弹窗；
- CAUTIOUS App 首次动作出现授权；
- BLOCKED App 不泄露截图和节点；
- 用户拒绝后任务可继续、停止或合理失败；
- 前台切换到目标 App 时审批文案正确。

### 6.4 M1 放行标准

- 所有安全测试全绿；
- 红线任务没有一次自动执行；
- 普通任务 `ask` 频率相对基线增加不超过 20%，超出必须解释；
- 策略命中和拒绝原因出现在 Trace，但敏感值不出现；
- 关闭 `feature_policy_engine` 可回到旧行为。

---

## 7. M2：可访问性感知引擎

来源：`_local/mature-modules/01-perception-engine/`。

### 7.1 目标

把当前无障碍遍历升级为可复用、可测试、节点无生命周期泄漏的 `Perceptor`，先兼容旧 `NodeInfo`，再逐步让模型使用结构化 `ScreenSnapshot`。

### 7.2 实现分层

#### M2.1 原始采集层

输入：一个或多个 `AccessibilityNodeInfo` 根、屏幕尺寸、displayId、键盘状态。

责任：

- 只负责遍历和复制原始字段；
- 不持有节点引用到异步线程；
- 每个节点及时 recycle（按 API 兼容方式）；
- 记录诊断计数，不把原始节点写日志。

#### M2.2 过滤层

规则：

- 屏幕外边界裁剪；
- 不可见、尺寸异常、IME/overlay 节点按配置过滤；
- 密码字段立即抹除；
- 根据 clickable、focusable、scrollable、enabled 分组；
- 去重使用稳定 key，不依赖对象 identity。

#### M2.3 文本增强层

visible text fallback：

```text
text → contentDescription → hintText → child text enrichment
```

子文本补全只给无文字且有交互意义的父容器使用，最多取配置数量，禁止把整棵子树拼进提示词。

#### M2.4 评分与截断层

候选分池：

- 交互节点优先；
- 有文本、resource-id、边界合理、当前可见者得分更高；
- 非交互节点只保留对上下文有价值的少量节点；
- 截断前过采样，截断后稳定排序；
- 节点上限与旧提示词上限兼容，默认不超过当前 25 个语义节点，视觉模式另行处理。

#### M2.5 输出层

输出：

- `ScreenSnapshot`；
- 兼容旧 `NodeInfo` 列表；
- 紧凑 summary；
- Prompt JSON；
- Trace artifact JSON。

### 7.3 与纯视觉模式的关系

- 节点正常：优先语义快照；
- 连续空节点且截图有效：进入纯视觉模式；
- BLOCKED：不是纯视觉兜底，而是直接 masked；
- 截图黑屏或失败：回到节点模式或 fail，不能把黑屏当有效视觉证据；
- 视觉模式下仍必须执行 PolicyEngine 和 SensitiveGuard。

### 7.4 测试清单

JVM/纯 Java 测试：

- 文本/描述/hint fallback；
- 子节点补全上限；
- 密码字段脱敏；
- 边界裁剪；
- 重复节点去重；
- 交互/非交互配额；
- 稳定排序与 index 重建；
- 空树、损坏 bounds、异常 className；
- snapshot 不含节点对象引用。

设备测试：

- 普通设置页；
- RecyclerView/Compose/自绘控件；
- 输入法显示时；
- 多窗口/权限弹窗；
- 微信等节点被屏蔽的纯视觉场景；
- 密码页面和 BLOCKED 场景。

### 7.5 性能预算

- 单次遍历主线程额外耗时目标 < 80ms；
- 节点快照不产生长期对象持有；
- 单次 Prompt JSON 大小不超过当前限制；
- 任务 20 步后 Java heap 增长应可回收，不出现线性增长。

### 7.6 放行与回滚

先以 shadow mode 同时生成旧节点表和新快照，只比较数量、文本召回、目标 index 是否一致；至少通过 3 个设备、5 类 App 后才让新快照进入模型。

出现点击目标召回下降、节点遍历崩溃或内存增长时，关闭 `feature_perceptor_v2`，保留 Trace 中的对比数据。

---

## 8. M3：ToolSpec、ToolRegistry 与 ToolRouter

来源：`_local/mature-modules/02-tool-policy-system/`。

### 8.1 目标

把 `AgentEngine.exec()` 中的动作分支逐步包装成声明式工具，但不一次性删除旧动作执行器。

### 8.2 工具定义

每个工具必须声明：

- name；
- 描述；
- 参数 schema；
- 是否改变屏幕；
- 是否需要目标包名；
- 风险级别；
- 是否允许在纯视觉模式；
- 执行器；
- 观察结果生成器。

首批工具映射：

```text
app / open_api / open_url / list_apps / setting
 tap / longpress / text / wait / swipe / scroll
 key / edge_back / back / home
 ask / done / fail
```

### 8.3 兼容包装策略

第一阶段：

```text
模型 JSON
  → ActionParser
  → ToolInvocation
  → ToolRouter
  → 旧 exec(action, args)
```

第二阶段：

```text
模型 JSON
  → ToolRegistry 查找
  → 参数校验
  → PolicyEngine
  → 新旧执行器适配器
  → ToolObservation
```

第三阶段才允许把高风险动作从旧 `exec()` 中移到独立 executor。

### 8.4 失败分类

所有工具结果必须分为：

- `SUCCESS`：动作执行并得到合理观察；
- `NO_EFFECT`：动作发出但屏幕没有预期变化；
- `INVALID_ARGUMENT`：模型参数错误；
- `POLICY_DENIED`：策略拒绝；
- `APPROVAL_TIMEOUT`：用户未响应；
- `CAPABILITY_UNAVAILABLE`：当前设备不支持；
- `TRANSIENT_ERROR`：可重试；
- `PERMANENT_ERROR`：应换策略或终止。

禁止把所有失败拼成一个“操作失败”。

### 8.5 测试与放行

- Registry 生成的 schema 与现有 `SCHEMA` 一致；
- 每个 `SCHEMA` 动作恰有一个路由；
- `exec()` 未注册分支 `back/home` 要么注册，要么在文档明确兼容处理；
- 无效参数不触碰设备；
- 策略拒绝不调用 executor；
- 审批超时不遗留线程或 latch；
- 用户 stop 可取消模型、审批和执行等待；
- 旧模式和新 Router 在相同固定动作集上的结果一致。

---

## 9. M4：守卫式流程复现

来源：`_local/mature-modules/05-guarded-flow-replay/`。

### 9.1 目标

增强当前 `FlowMemory`，不是替换它。成功路径仍由当前记忆库管理，新模块提供每一步执行前的屏幕状态守卫。

### 9.2 录制流程

成功任务终态时：

1. 从 Trace 提取每一步动作前的 `ScreenState`；
2. 去除动态文本、时间、金额、账号等不稳定字段；
3. 保留 package、activity、role、resource-id、可见文本摘要、bounds；
4. 对秘密输入只保留 `secret_id` 或占位符；
5. 写 `macro_schema_version=3.0`；
6. 记录成功次数、失败次数、最近验证时间和来源设备。

### 9.3 复现流程

```text
读取 FlowPlan
  ↓
检查版本与风险
  ↓
感知当前 ScreenState
  ↓
匹配：包名/Activity + 节点集合 + 空间摘要
  ↓
超过阈值 → 执行一步
低于阈值 → 等待短暂稳定
仍低于阈值 → 停止复现
  ↓
动作后复验
  ↓
进入下一步或交回 Agent
```

建议初始阈值：

- package/activity 必须匹配或有明确允许变化；
- 节点集合相似度起始 0.85；
- 空间/role 变化可给小幅容差；
- 连续一次低分后最多等待 2 秒；
- 第二次低分不得自动降低阈值。

### 9.4 偏离处置

必须支持三种结果：

- `REPLAY_OK`：继续复现；
- `REPLAY_STALE`：停止并交回 Agent，带剩余动作和偏离报告；
- `REPLAY_FAILED`：环境或动作执行错误，终止或回旧主循环。

高风险流程出现偏离时，默认不自动交回执行，先要求用户确认。

### 9.5 兼容与回滚

- 旧 FlowPlan 没有状态时，仅允许低风险动作复现；
- 复现守卫关闭时，仍保留当前旧 replay 行为；
- 任何偏离不得删除记忆，写入失败样本供后续调参；
- 不得把一次失败自动覆盖原成功路径。

---

## 10. M5：结构化 Trace 与回放

来源：`_local/mature-modules/03-structured-trace/`。

### 10.1 目标

把当前 `EngineLog` 的诊断能力升级为逐步可审计轨迹，但保留 `EngineLog` 作为低成本实时日志。

### 10.2 事件埋点位置

必须覆盖：

1. `task_started`；
2. `perception_started / perception_finished`；
3. `llm_request / llm_response / llm_error`；
4. `tool_validating / policy_decision / approval`；
5. `tool_started / tool_finished`；
6. `verification`；
7. `replay_match / replay_diverged`；
8. `popup_handled`；
9. `sensitive_takeover`；
10. `terminal`；
11. `task_stopped`；
12. `crash_recovered`。

### 10.3 写入策略

- 单写者异步队列；
- 有界队列，满时优先丢弃调试型截图，不丢终态、安全和错误事件；
- `flush()` 用于任务结束、用户导出和崩溃前；
- 文件写入失败不得阻塞 Agent 主循环；
- 每个 run 有独立目录；
- 设定总大小上限和保留天数；
- 用户可清除轨迹；
- 默认不上传云端。

### 10.4 脱敏策略

递归处理字符串、JSON 对象、数组和文件名：

- `api_key`、`token`、`authorization`、`password`、`secret`、`cookie`、`code` 等键；
- Bearer token、JWT、长随机串；
- 邮箱、手机号、身份证号、银行卡号、验证码；
- 输入动作文本；
- 剪贴板内容；
- 密码节点和 BLOCKED 应用内容。

脱敏器必须有“输入原文不落盘”的测试，而不是落盘后再替换。

### 10.5 回放查看器

先支持离线本地查看：

- 时间轴；
- 动作和结果；
- 屏幕截图；
- 节点快照；
- 策略决定；
- 失败归因；
- 复现偏离；
- 脱敏标记。

不在第一版加入远程上传、在线共享或公开链接。

---

## 11. M6：App Skill 与 Agent Skill

来源：`_local/mature-modules/04-skill-system/`。

### 11.1 两类技能

| 类型 | 目录 | 加载时机 | 用途 |
|---|---|---|---|
| App Skill | `agent-lite/assets/app_skills/<package>/SKILL.md` | 每轮按前台包 | 当前 App 的导航、控件语义和已知陷阱 |
| Agent Skill | `filesDir/skills/<name>/SKILL.md` | 显式激活 | 日历、搜索策略、领域任务等专项能力 |

### 11.2 第一阶段只实现 App Skill

原因：当前项目已有提示词和任务记忆，App Skill 的价值明确，且不需要动态安装外部文件。

每个技能必须：

- frontmatter 有 `name`、`description`、`metadata.package`；
- 目录和包名一致；
- 正文限制长度；
- 不覆盖安全、审批、动作 schema；
- 明确来源、更新时间和验证设备；
- 不能包含账号、密码、真实业务数据。

### 11.3 第二阶段 Agent Skill

实现：

- 目录扫描；
- frontmatter 解析；
- catalog 精简注入；
- 显式 `activate_skill`；
- 禁用列表；
- 并发激活幂等；
- 文件读取失败可恢复；
- 新会话刷新 catalog。

外部技能导入必须延后，先不允许任意网络下载。后续若开放，需要签名、来源白名单、大小限制和审阅状态。

### 11.4 测试

- frontmatter 缺字段、非法名称、过长描述；
- 目录与 package 不一致；
- 同名冲突；
- 禁用技能不可见；
- 仅按前台包注入对应 App Skill；
- 技能内容过长时截断；
- 技能错误不影响主循环；
- 真实资产完整性测试。

---

## 12. M7：中文操作经验与应用别名

来源：`_local/mature-modules/09-mobile-knowledge-assets/`。

### 12.1 资产分类

不得直接把上游 Python 文件当运行时依赖。应转换为项目自己的资源格式：

```text
agent-lite/assets/knowledge/
├── app_aliases.json
├── action_heuristics_zh.json
├── action_reflection_rules.json
├── source_manifest.json
└── reviewed_overrides.json
```

### 12.2 应用别名格式

```json
{
  "schema_version": 1,
  "entries": [
    {
      "canonical_name": "哔哩哔哩",
      "aliases": ["bilibili", "B站", "哔站"],
      "packages": ["tv.danmaku.bili"],
      "platform": "android",
      "source": "Open-AutoGLM",
      "review_status": "reviewed",
      "last_verified": "YYYY-MM-DD"
    }
  ]
}
```

冲突处理：

- 一个 alias 指向多个包时不得静默选择；
- “淘宝闪购”等业务词必须人工判定，不按字面直接映射；
- 解析结果带 confidence 和 source；
- 安装包扫描结果优先于静态表；
- 用户明确包名优先于别名；
- 解析失败时交给模型或用户澄清，不猜测敏感目标。

### 12.3 操作经验转规则

将 18 条经验拆为：

- `navigation_recovery`：返回、重新搜索、加载失败；
- `list_search`：分栏逐项、避免重复扫描；
- `form_selection`：购物车、筛选、日期方向；
- `post_action_verify`：点击/滑动无效时复验；
- `domain_specific`：外卖、游戏、小红书等；
- `terminal_check`：结束前检查错选、漏选、多选。

每条规则必须标记：

- 是否适用于 API 优先策略；
- 是否可能触发敏感操作；
- 是否与当前守则冲突；
- 适用 App/场景；
- 评审状态。

### 12.4 反思机制

把 MobileAgent 的“上一步是否产生预期行为”转为结构化字段：

```json
{
  "last_action_result": "success|partial|failure|unknown",
  "evidence": "screen_change|node_change|business_signal|none",
  "next_policy": "continue|retry_adjusted|backtrack|ask|fail"
}
```

模型只获得压缩后的结论，不把无界历史全部塞入上下文。

---

## 13. M8：多 Provider LLM 层

来源：`_local/mature-modules/06-llm-provider-layer/`。

### 13.1 目标

指导模型为 DeepSeek4.1flash 提供稳定的 Provider 注册、错误分类和重试行为；不先重写整个 `LlmClient`。

### 13.2 实施顺序

1. 从现有 `LlmClient` 抽出纯逻辑 `LlmErrorClassifier`；
2. 抽出 `LlmRetryPolicy`，固定“已产生输出后不得盲目重试”；
3. 抽出 `ProviderSpec` 和本地配置校验；
4. 保持现有 HTTP 实现作为 `OpenAiCompatibleProvider`；
5. 再增加 Provider 注册和模型发现；
6. 最后评估 SSE 流式，不让流式重构阻塞普通 JSON 任务执行。

### 13.3 DeepSeek4.1flash 模型约束

实施模型必须遵守：

- 不假设模型一定具备视觉能力；请求中明确标注是否有 screenshot；
- 不把“思考文本”当动作；只接受严格 JSON；
- 控制单轮上下文，优先传递当前快照、上一动作结果、完成判据和安全状态；
- 发生上下文超限时先压缩历史，不自动删除安全上下文；
- 模型输出不可信，所有参数仍由本地 schema 校验；
- 服务器名称、模型 ID、endpoint 来自运行时配置，不写死为秘密；
- 取消任务时立即取消网络读取，不能等待完整超时。

### 13.4 重试矩阵

| 错误 | 是否重试 | 条件 |
|---|---:|---|
| DNS/连接失败 | 是 | 指数退避且次数有限 |
| 429/额度不足 | 否或长退避 | 根据服务端 retry-after |
| 401/配置错误 | 否 | 直接提示配置 |
| 400 schema 错误 | 否 | 记录并修正请求 |
| 5xx | 是 | 尚未输出动作时 |
| SSE 中途断开 | 仅未产出完整动作时 | 已产出动作不得重复执行 |
| 用户停止 | 否 | 立即取消 |
| 上下文超限 | 一次压缩后重试 | 不能无限压缩 |

### 13.5 测试

- DeepSeek4.1flash 的标准响应、空响应、非 JSON 响应；
- thinking 开关与响应长度；
- 取消；
- 429/5xx/超时/上下文超限；
- 已输出动作后的断线不重复执行；
- endpoint 改写保护与配置合法性；
- API Key 不进日志和 Trace。

---

## 14. M9：AndroidWorld 外部评测桥接

来源：`_local/mature-modules/08-androidworld-evaluation/`。

### 14.1 定位

AndroidWorld 是评测设施，不是 Android 运行时模块。它必须单独放在：

```text
_local/mature-modules/08-androidworld-evaluation/
tests/androidworld_bridge/
tests/evaluation_reports/
```

不得复制到 `agent-lite/assets` 或 APK。

### 14.2 桥接目标

建立一个最小 Adapter：

```text
AndroidWorld task
  → task.json
  → Agent Lite 启动参数/本地接口
  → Agent Lite 执行
  → trace.jsonl / terminal result
  → AndroidWorld validator/reward
  → evaluation report
```

如果 AndroidWorld 强制 A11yGrpcWrapper、`emulator -grpc 8554` 或特定 APK，必须在报告中明确环境阻断，不得声称支持真机。

### 14.3 第一批评测集

先选 10–20 个覆盖以下能力的任务：

- 启动应用和导航；
- 搜索和列表选择；
- 输入和表单；
- 滚动和日期；
- 系统设置；
- 异常/权限处理；
- 终态验证。

再建立国内真机扩展集：微信、淘宝、京东、美团、支付宝、地图、设置和输入法。两套结果分开统计。

### 14.4 指标

- task success reward；
- 完成时间；
- 平均动作数；
- 错误动作数；
- `ask` 频次；
- 复现命中率；
- 假成功率；
- 敏感违规率；
- 轨迹完整率。

发布阻断：敏感违规率必须为 0；假成功率不得高于基线；成功率下降超过 5 个百分点必须归因，超过 10 个百分点自动回滚。

---

## 15. M10：Shizuku VirtualDisplay 高级模式

来源：`_local/mature-modules/07-shizuku-virtual-display/`。

### 15.1 定位

这是全部模块中风险最高、依赖最重的能力。整合目标是“纳入本项目并可选启用”，不是把它变成默认执行路径。

### 15.2 前置条件

- 用户明确开启后台虚拟屏；
- Shizuku 已安装并授权；
- 当前 ROM 和 SDK 通过兼容性检查；
- 当前任务不涉及被策略禁止的 App；
- 用户知道屏幕会在后台操作；
- 真实屏幕模式仍可随时接管和停止。

### 15.3 分阶段

1. **纯逻辑阶段**：先实现 `PlatformLease`、状态机和幂等停止测试；
2. **能力探测阶段**：只检测 Shizuku/binder/display 能力，不创建屏幕；
3. **截图阶段**：创建隔离屏并采集截图，禁止执行真实动作；
4. **节点阶段**：按 displayId 采集无障碍根，做只读对比；
5. **输入阶段**：注入点击/返回/文本，所有动作仍过 PolicyEngine；
6. **预览阶段**：提供用户可见预览和停止按钮；
7. **灰度阶段**：仅白名单设备和低风险任务；
8. **正式阶段**：仍默认关闭，设置页明确显示依赖和故障回退。

### 15.4 必须有的生命周期

```text
STOPPED → STARTING → RUNNING → DRAINING → STOPPED
                  └→ BROKEN → DRAINING/STOPPED
```

要求：

- binder death 可检测；
- start 失败回滚全部资源；
- stop 幂等；
- lease 释放后才能销毁 display；
- 任务取消立即停止输入和截图；
- 虚拟屏异常不得杀死主 App；
- 无法创建虚拟屏时回到普通模式或明确 fail，不静默假装后台执行。

### 15.5 放行标准

- 至少 2 个 Android 版本和 2 个 ROM；
- Shizuku 重启、授权撤销、binder death、旋转、锁屏、App 崩溃均有测试；
- 100 次 start/stop 无资源泄漏；
- 普通模式回归完全不受影响；
- 敏感红线与 BLOCKED 策略在虚拟屏仍全绿。

---

## 16. 跨阶段测试体系

### 16.1 静态/免设备门禁

每个阶段至少运行：

```sh
make check
```

并根据改动补充：

```sh
python3 tests/action_schema_guard.py
python3 tests/fuzzy_flow_guard.py
python3 tests/flow_memory_guard.py
python3 tests/popup_ad_guard.py
bash tests/ui_text_guard.sh
python3 tests/ui_widget_guard.py
python3 tests/spec_ux_001_guard.py
```

新增建议门禁：

```sh
python3 tests/module_contract_guard.py
python3 tests/trace_redaction_guard.py
python3 tests/skill_asset_guard.py
python3 tests/policy_order_guard.py
python3 tests/replay_schema_guard.py
```

### 16.2 编译门禁

```sh
cd agent-lite
sh build-mac.sh
```

确认：

- 没有隐式 Gradle/Kotlin/AndroidX 依赖；
- API Key 为空且不在 dex；
- 所有资源进入 APK；
- `aapt2`、`javac`、`d8`、`zipalign`、`apksigner` 全成功；
- APK 可安装、启动和覆盖安装。

### 16.3 设备矩阵

至少覆盖：

| 类别 | 设备/环境 | 必测能力 |
|---|---|---|
| 标准 Android 11/12 | 模拟器或真机 | 感知、动作、输入、设置 |
| Android 13/14 | 真机 | 权限、通知、截图 |
| Android 15/16 | OnePlus/ColorOS 等 | 节点屏蔽、截图限频、保活 |
| 国内应用 | 微信、淘宝、京东、美团、支付宝、地图 | 别名、搜索、敏感和纯视觉 |
| 输入法 | 常用第三方键盘 + Agent 键盘 | 三层输入回退 |
| Shizuku 设备 | 明确授权设备 | VirtualDisplay 高级模式 |
| 无网络 | 飞行模式/断网 | 失败分类、缓存、终态 |

### 16.4 测试分组

#### 功能集

- 打开 App；
- API/深链直达；
- 语义点击；
- 视觉点击；
- 输入/追加输入；
- 滑动/滚动/返回；
- 系统设置；
- 任务完成和失败。

#### 治理集

- 同动作重复；
- 6 步无变化；
- 最大步数；
- 页面加载等待；
- 弹窗/广告/权限；
- 复现偏离；
- Trace 写入失败；
- LLM 取消。

#### 红线集

- 支付；
- 转账；
- 发送敏感消息；
- 验证码；
- 密码；
- 权限授予；
- BLOCKED 应用；
- 纯视觉模式下敏感页面。

#### 退化集

- 关闭每一个 feature flag；
- 空节点；
- 黑屏截图；
- 无障碍断开；
- Shizuku 撤权；
- Trace 磁盘满；
- 配置损坏；
- 模型返回非法 JSON。

---

## 17. 统一提交、评审与回滚流程

### 17.1 每个阶段开始前

1. 新建阶段分支或明确提交基点；
2. 记录当前 `git status --short`；
3. 读取相关上游许可证；
4. 写或更新 ADR；
5. 明确 feature flag 默认值；
6. 建立阶段测试清单；
7. 为新增数据格式写版本和迁移规则。

### 17.2 每个任务卡完成时

必须有：

- 代码或文档产物；
- 至少一个自动化测试；
- 一条可复现命令；
- 风险和未完成项；
- 是否需要真机；
- feature flag 行为；
- 回滚方法；
- `CHANGELOG.md` 记录（行为变化时）；
- `PROJECT_OVERVIEW.zh-CN.md` 工作日志追加一行。

### 17.3 合并前评审顺序

```text
代码结构评审
  ↓
数据/版本兼容评审
  ↓
安全评审
  ↓
交互逻辑三处一致性评审
  ↓
免设备门禁
  ↓
Android 构建
  ↓
真机专项
  ↓
AndroidWorld/回归报告
  ↓
Owner 放行
```

### 17.4 回滚层级

1. **开关回滚**：关闭新模块，适用于逻辑错误和指标退化；
2. **配置回滚**：恢复策略、阈值、技能和别名资源版本；
3. **数据回滚**：保留新 Trace/Flow 数据，恢复旧读取器；
4. **代码回滚**：回到阶段稳定提交；
5. **APK 回滚**：发布旧 APK，不覆盖用户数据；
6. **服务器回滚**：仅当 Provider/同步契约变化时执行，遵守服务器手册。

禁止直接删除用户轨迹、记忆和配置来“修复”版本问题。

---

## 18. 发布流程

### 18.1 Release Candidate 前

- 所有阶段 feature flag 的目标默认值已确定；
- 旧数据迁移在副本上完成；
- APK readability 无秘密；
- 版本号和 CHANGELOG 更新；
- 第三方许可证和 NOTICE 齐全；
- AndroidWorld 最小集报告完成；
- 国内真机矩阵完成；
- 红线集全绿；
- 回滚 APK 已保留；
- 轨迹目录、清理策略和隐私说明已确认。

### 18.2 构建与签名

```sh
make check
cd agent-lite
sh build-mac.sh
```

构建后检查：

```sh
unzip -l build/agent-lite.apk
apkanalyzer manifest print build/agent-lite.apk
python3 ../tests/apk_readability.py build/agent-lite.apk
```

不得把真实 `API_KEY` 放进 `dev.local`。构建脚本已有硬阻断，必须保持。

### 18.3 灰度阶段

建议顺序：

1. 内部设备、所有新模块关闭；
2. 内部设备、只开 Trace 和策略影子模式；
3. 内部设备、开启感知和复现守卫；
4. 少量可信用户、开启 App Skill 和知识资产；
5. 少量设备、开启 Provider 新层；
6. Shizuku 设备单独灰度；
7. 指标稳定后再扩大范围。

观察至少：

- 崩溃/ANR；
- 成功率；
- 平均步数；
- `ask` 频次；
- 敏感误执行；
- 纯视觉切换；
- 轨迹落盘失败；
- 内存和电量；
- 用户主动停止率。

### 18.4 发布阻断条件

任一条件成立，不得扩大灰度：

- 红线集出现自动执行；
- BLOCKED 内容进入模型上下文或 Trace；
- API Key 出现在 APK/日志；
- 停止按钮不能在模型等待或审批等待中生效；
- 任务假成功率上升；
- 新模块导致普通模式崩溃；
- 数据迁移删除或破坏旧 FlowMemory；
- 资源写满导致主循环卡死。

---

## 19. 完整收尾流程

这是每个阶段和整个项目都必须执行的结束流程。

### 19.1 阶段收尾

1. 确认任务卡全部完成或明确标记延期；
2. 跑 `make check`；
3. 跑 `sh agent-lite/build-mac.sh`；
4. 跑本阶段 JVM/设备测试；
5. 检查 `git diff --check`；
6. 检查新增文件许可证和 SPDX；
7. 检查日志、截图、Trace 无秘密；
8. 检查 feature flag 默认值；
9. 更新 `CHANGELOG.md`；
10. 更新相关 Skill/规范/设计文档；
11. 更新 `PROJECT_OVERVIEW.zh-CN.md` 日志；
12. 写阶段结果摘要：成功指标、失败项、已知风险、回滚点；
13. 提交阶段稳定点；
14. 将下一阶段依赖写进本文档或任务清单。

### 19.2 整体项目收尾

最终必须形成以下交付包：

```text
交付包/
├── agent-lite/build/agent-lite.apk
├── release-notes.md
├── CHANGELOG.md
├── THIRD_PARTY_NOTICES.md
├── evaluation/
│   ├── baseline.json
│   ├── final.json
│   ├── androidworld-report.json
│   └── device-matrix.md
├── migration/
│   ├── flow-memory-migration.md
│   ├── trace-schema.md
│   └── rollback-procedure.md
└── security/
    ├── redaction-review.md
    ├── policy-review.md
    └── release-checklist.md
```

收尾验收问题必须逐项回答“是/否/证据路径”：

- 9 类候选模块是否全部有对应实现或外部整合物？
- 是否明确哪些进入 APK、哪些只进评测、哪些只进可选模式？
- 默认零 root 路径是否可用？
- 原有 FlowMemory 是否可读？
- 原有 SensitiveGuard 是否仍是硬守卫？
- 纯视觉模式是否仍可用？
- BLOCKED 应用是否不会泄露内容？
- 所有 ToolInvocation 是否都有终态？
- 所有任务是否有 trace 或明确失败原因？
- Trace 是否可脱敏、可清理、可回放？
- DeepSeek4.1flash 非法输出是否不会直接触碰设备？
- 模型中断是否不会重复执行上一步动作？
- Shizuku 未安装时是否不会影响普通模式？
- AndroidWorld 结果是否没有被冒充为真机结果？
- 许可证和 NOTICE 是否随发布物保留？
- 回滚 APK、配置、数据迁移方案是否已实际演练？

### 19.3 最终终态定义

只有满足以下条件，项目才可记录 `终态: done`：

1. P0/P1 阶段全部完成：安全、感知、工具、复现、轨迹、最小评测；
2. P2 阶段有明确结果：LLM 层和 VirtualDisplay 已完成或按风险评审延期，不能无记录跳过；
3. `make check` 全绿；
4. Android APK 构建、安装、启动通过；
5. 红线集全绿，敏感违规率为 0；
6. 至少一轮真机矩阵和一轮 AndroidWorld/替代评测完成；
7. 迁移、回滚、许可证、隐私文档齐全；
8. 观察期内没有触发发布阻断条件；
9. 工作日志追加完整终态行。

若仍有未解决的高风险问题，终态只能写 `blocked`，并注明具体阻断条件，不得用“基本完成”代替。

---

## 20. DeepSeek4.1flash 分阶段执行提示模板

每次只把一个阶段任务发给模型，避免上下文过大。

```text
你正在维护 Android Global Agent 的 agent-lite 子项目。
目标模型：DeepSeek4.1flash。

本次阶段：<M0-M11>
本次任务卡：<唯一任务卡编号>

必须先读取：
1. PROJECT_OVERVIEW.zh-CN.md 最后 40 行
2. docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md 的本阶段章节
3. agent-lite/README.md
4. docs/INTERACTION_LOGIC_SPEC.zh-CN.md
5. 任务卡列出的源码和测试

本次只完成：<一个最小垂直切片>
禁止：
- 大范围重写 AgentEngine.java
- 直接复制 Kotlin/Python 运行时
- 引入未经批准的 Gradle/Kotlin/第三方依赖
- 降低敏感安全、停止、授权或纯视觉保护
- 修改无关文件

执行顺序：
1. 先检查工作树并识别用户改动；
2. 读取上游隔离副本和许可证；
3. 先写失败测试/守卫；
4. 实现最小代码；
5. 跑 make check；
6. 跑 agent-lite/build-mac.sh；
7. 根据任务需要执行真机测试；
8. 更新 CHANGELOG、相关规范和工作日志；
9. 汇报变更文件、测试命令、结果、未完成项、风险和回滚方法。

成功标准：<明确的可观察断言>
失败标准：<明确的阻断条件>
回滚点：<提交或 feature flag>
```

### 20.1 模型输出格式

每个任务完成后必须按以下结构汇报：

```text
任务卡：M?-?
状态：done | blocked | partial
实现：<不超过 10 行>
文件：<路径列表>
测试：<命令> → <结果>
真机：<设备/未执行/结果>
安全：<是否检查秘密、权限、红线>
兼容：<旧数据/旧动作/旧配置结果>
风险：<剩余风险>
回滚：<开关/提交/配置>
下一步：<唯一下一任务卡>
```

禁止只说“已完成”“测试通过”，必须给证据。

---

## 21. 推荐任务卡总表

### M0

- `M0-01`：建立 feature flag 与默认配置；**已完成（2026-09-20，见 `docs/ADR-001-FEATURE-FLAGS.zh-CN.md`）**
- `M0-02`：冻结 ScreenSnapshot/Tool/Trace/Replay 版本契约；**已完成（2026-09-20，见 `docs/ADR-002-CONTRACTS.zh-CN.md`）**
- `M0-03`：建立基线任务集和指标采集；**下一张任务卡**
- `M0-04`：建立 ADR 与第三方许可证清单；
- `M0-05`：增加模块契约守卫骨架。

### M1

- `M1-01`：AppTier/ApprovalMode/PolicyDecision 纯 Java 模型；
- `M1-02`：AppClassifier 默认表和用户覆盖；
- `M1-03`：PolicyEngine 判定顺序；
- `M1-04`：与 SensitiveGuard 取更严格结果；
- `M1-05`：目标包审批主体；
- `M1-06`：BLOCKED 感知/截图掩码；
- `M1-07`：策略测试和真机红线集。

### M2

- `M2-01`：PerceptionElement/ScreenSnapshot；
- `M2-02`：多根节点遍历；
- `M2-03`：密码入口脱敏；
- `M2-04`：文本 fallback 与 child enrichment；
- `M2-05`：评分、去重、截断和排序；
- `M2-06`：旧 NodeInfo 兼容适配；
- `M2-07`：shadow 对比和设备验证。

### M3

- `M3-01`：ToolSpec/ToolRegistry；**已完成（2026-09-21，无设备纯逻辑切片，见 `docs/ADR-003-M3-TOOL-REGISTRY.zh-CN.md`）**
- `M3-02`：参数 Validation；**已完成（2026-09-21，纯 Java 声明校验与结构化错误，见 `docs/ADR-003-M3-TOOL-REGISTRY.zh-CN.md`）**
- `M3-03`：ToolInvocation/ToolCallState；**下一张任务卡**
- `M3-04`：ToolRouter 包装旧 exec；
- `M3-05`：审批与停止取消；
- `M3-06`：动作词表三处一致门禁；
- `M3-07`：逐个迁移高价值 executor。

### M4

- `M4-01`：ScreenState 归一化；
- `M4-02`：状态 key 和相似度；
- `M4-03`：FlowPlan schema 3.0；
- `M4-04`：复现前等待与匹配；
- `M4-05`：偏离停止和 Agent 交接；
- `M4-06`：秘密引用而非秘密值；
- `M4-07`：迁移测试和真实流程回归。

### M5

- `M5-01`：TraceEvent/TraceRecorder；
- `M5-02`：FileTraceRecorder 有界异步写入；
- `M5-03`：递归脱敏器；
- `M5-04`：AgentEngine/LLM/Tool/Verification 埋点；
- `M5-05`：保留策略、清理和用户删除；
- `M5-06`：离线回放查看器；
- `M5-07`：磁盘满和写入失败退化测试。

### M6

- `M6-01`：Skill frontmatter parser；
- `M6-02`：App Skill catalog；
- `M6-03`：国内 App Skill 第一批；
- `M6-04`：Agent Skill catalog；
- `M6-05`：显式激活和禁用；
- `M6-06`：资产完整性门禁。

### M7

- `M7-01`：应用别名 JSON schema；
- `M7-02`：冲突检测和安装包优先解析；
- `M7-03`：中文经验规则拆分；
- `M7-04`：操作结果反思结构化；
- `M7-05`：规则审校和来源标记；
- `M7-06`：知识资产回归集。

### M8

- `M8-01`：错误分类纯逻辑层；
- `M8-02`：重试策略纯逻辑层；
- `M8-03`：ProviderSpec/Registry；
- `M8-04`：DeepSeek4.1flash 配置验证；
- `M8-05`：SSE 解析隔离测试；
- `M8-06`：多 Provider 灰度；
- `M8-07`：上下文压缩与重复动作防护。

### M9

- `M9-01`：AndroidWorld 环境复现说明；
- `M9-02`：task/observation/action bridge；
- `M9-03`：终态和 reward 转换；
- `M9-04`：10 个任务最小集；
- `M9-05`：国内真机扩展集；
- `M9-06`：基线/最终报告自动化。

### M10

- `M10-01`：VirtualDisplay 能力探测；
- `M10-02`：生命周期和 lease 纯逻辑；
- `M10-03`：只读截图 PoC；
- `M10-04`：displayId 感知；
- `M10-05`：输入注入与策略接线；
- `M10-06`：预览/停止/异常恢复；
- `M10-07`：设备灰度和默认关闭。

### M11

- `M11-01`：全量门禁；
- `M11-02`：全设备矩阵；
- `M11-03`：AndroidWorld 最终报告；
- `M11-04`：迁移/回滚演练；
- `M11-05`：发布候选包；
- `M11-06`：灰度观察；
- `M11-07`：最终收尾和 done/blocked 判定。

---

## 22. 最终实施结论

整合顺序不能按“哪个上游代码最多”决定，而应按安全风险和依赖关系推进：

1. 先冻结契约和基线；
2. 先做安全策略，再做感知和工具路由；
3. 把流程复现从“盲目重放”升级为“有状态守卫”；
4. 用结构化 Trace 把后续调试、评测和回滚证据建立起来；
5. 再加入技能和中文知识资产，避免提示词无序膨胀；
6. 最后重构 Provider 层并做 VirtualDisplay 高级模式；
7. AndroidWorld 始终作为外部评测基座，不进 APK；
8. 每个阶段都必须有开关、测试、指标、文档和回滚点；
9. 只有红线、安全、默认兼容、迁移、发布和观察期全部通过，才允许最终收尾。

这条路线会把上游 9 类能力全部纳入本项目，但不会牺牲 Agent Lite 当前最重要的产品约束：零 root、低依赖、国内 ROM 兼容、敏感操作可控、失败可解释、用户可以随时停止。

---

## 23. 面向老年人与手机入门者的开发续篇（2026-10-06）

### 23.1 本次增量如何接续已有架构

**本章为未来实施要求，不是本轮已实现清单。** 当前实现与验证层级集中见[全景手册 §18](<../PROJECT_OVERVIEW.zh-CN.md#18-当前进度评估与未来开发入口2026-10-06>)。本轮仅编辑文档/工作日志，不修改业务代码、依赖、配置，也不执行设备部署或真实账户操作。

保留 §§0–22 的模块边界、M0–M11 编号和历史任务状态，不重写整套路线。§21 的 M0-01/02、M3-01/02 已有纯逻辑基础；2026-10-02 又将 M3 校验接入主循环，但默认关闭，且复用直接调用执行器。故 M3-04 应读作“有局部接线，完整 Router/全入口保证待做”，不是全未开始，也不是整体完成。原 §6.4 的“关掉策略回旧行为”今后仅可用于可选增强，**已纳入基线的敏感保护、停止与出网限制不得随开关一起移除**。

产品优先级从“整合更多成熟模块”收窄为：**先让普通无 root 用户能安全完成一个可证实的任务，再扩大覆盖范围。** 基础可访问性、持续停止入口与数据告知不是 P2 美化。纯 Java/框架 API/低依赖继续沿用；不引入 Gradle、AndroidX、复杂插件系统作为本轮后续改造前置。

### 23.2 `de 4.1flash` 身份、能力与接入边界

#### 身份与证据链

用户指定的产品运行模型原名保留为 **`de 4.1flash`**，不将它静默替换为其他模型，也不等同于本轮编码/分析助手。

- 代码事实：[构建脚本](<../agent-lite/build-mac.sh>)默认 `BASE_URL=https://api.deepseek.com/v1`、`MODEL=deepseek-flash`；[购买接入:53–58](<../agent-lite/src/com/dsh/agentlite/PurchaseActivity.java#L53-L58>)同样指定该端点/模型。
- 官方资料（本轮 2026-10-06 读取）：[2026-09-10 公告](<https://api-docs.deepseek.com/zh-cn/news/news260910/>)将 `deepseek-flash` 对应 **DeepSeek V4.1 Flash**；[平台服务协议](<https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html>)载明运营者为杭州深度求索人工智能基础技术研究有限公司。
- 结论：有充分代码和官方资料支持“用户原名意指 DeepSeek V4.1 Flash”这一候选映射，但用户缩写意图、设备偏好和网关实际运行模型仍需确认。[gateway:259–261](<../agentlite-server/gateway.go#L259-L261>)可重写模型名；未读取线上配置，不能用客户端默认值证明线上身份。

| 能力 | 官方核验 | 项目实际接入 | 后续要求 |
|---|---|---|---|
| 文本与多轮对话 | [Chat API](<https://api-docs.deepseek.com/api/create-chat-completion>)支持 messages | 客户端构造消息，引擎保留有限历史；中文任务有转译 | 记录模型/端点/模式版本；测短口语、否定与歧义，不以中文宣传替代任务效果 |
| 结构化输出 | 官方支持 JSON Output | [chatOnce:341–388](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L341-L388>)未传 `response_format`；依靠提示词+提取 JSON | 能力验证后选择 JSON 模式；仍须本地 schema、枚举、范围、未知字段和安全策略校验 |
| 工具调用 | 官方支持 tools/function calling | 目前是 JSON 动作词表，不是原生 tools 接线 | 不宣称已接；如后续接入仍归同一个校验/执行入口，绝不让模型直接调用设备 |
| 截图/视觉 | [视觉指南](<https://api-docs.deepseek.com/guides/vision>)核实 `image_url`，支持 JPEG/PNG/GIF/WebP | 客户端可发送 JPEG data URL；设备/网关链路未实测 | 确认同一端点、模型、模式可收图；脱敏后再发；黑屏/截屏禁止时交还，不盲点 |
| 流式输出 | Chat API 支持流式 | 网关有流式转发，Android 当前请求未开 `stream` | 可做用户进度反馈；完整解析前不可执行半截动作；取消/usage 需独立测试 |
| 上下文与输出 | [官方规格/价格](<https://api-docs.deepseek.com/quick_start/pricing/>)列 1M 上下文、最大 384K 输出 | 动作请求通常 `max_tokens=160`、响应上限 1MiB、历史裁剪 | 官方上限不是本工程配置；测长确认/长文本截断，按任务分预算 |
| 延迟、并发、限流 | [限流说明](<https://api-docs.deepseek.com/quick_start/rate_limit>)列账户并发限制 2500、429 与排队机制 | 客户端读超时 120s、最多四次尝试、429 阶梯等待 | 没有本项目时延/SLA 证据；记录首反馈、决策、总耗时和取消时延，不把 Flash 名称当速度证明 |
| 费用 | 官方按输入命中/未命中、输出及时间段计价 | 网关 units 与用户自带 Key 两条付费路由 | 官方价不是产品售价；显示付费主体、单次预算/累计用量；不得静默换付费主体 |
| ASR / TTS | 本轮核实的聊天/视觉文档不足以证明该模型可直接转写音频或合成语音 | [AsrRouter](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java>)为独立云/本地路径；本轮未找到 TTS 接线 | 标为“信息不足/未集成”，不把视觉、多模态或单模型模式等同于语音可用 |
| 隐私与数据处理 | [隐私政策](<https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html>)及平台协议要求应用方披露下游数据处理 | 节点/截图可能经网关或直达厂商，ASR 与同步又是独立链路 | API 专属留存期限、训练用途、删除/区域条款未获完整可确认依据；发布前核实，不套用消费者产品的退出训练条款 |

接入条件分支：

1. **若实际即官方 `deepseek-flash`**：保持该运行模型，先用无敏感数据 mock 合约，再经授权进行少量受控文本/截图/JSON 测试；设备端与网关都记录脱敏的 resolved model/模式，不存 Key。
2. **若为别名、中转或不同部署**：先确认厂商、正式 ID、base URL、模型映射与能力矩阵；未确认视觉时用节点文本降级，节点不可用则暂停；不自行换视觉模型或上传到新厂商。
3. **若 JSON/tools/流式在该具体通道不可用**：保留文本 JSON 协议的确定性校验与有限修复；禁止把解析失败当成默认点击、默认同意或 done。
4. **语音独立决策**：ASR 上传需单独告知，可选本地/指定云端；失败保留文字入口。TTS 是可选播报层，不读密码/验证码，不在公共环境自动朗读私密内容。

### 23.3 面向目标人群的首用与日常旅程

以下是待实施的产品要求；字号/尺寸/时延为建议验收线，不是已经达到的指标或法规标准。

#### 首次安装到第一次成功

1. **安装**：给出可信签名 APK/分发入口、版本与兼容说明；不要求电脑、开发者选项、ADB、root 或用户编辑 URL/模型 ID。商店/侧载渠道及 Android 受限设置提示按实际版本单独说明；不能用命令绕过系统知情授权。
2. **一屏解释**：“你说要做什么；我操作手机；你随时可以停。”同时说明会读取何种屏幕信息、是否发往云端、可能的费用；不要只显示长条款链接。
3. **普通账号路径优先**：登录后直接解析可用通道，不要求懂 Key。账号恢复/密钥不可找回应在注册前明示；能否提供恢复机制是产品决策，不能保证不存在的找回能力。高级自带 Key 收到折叠页。
4. **权限分步**：先无障碍（解释读取/操作范围）；语音点击时才要录音；通知被拒仍须能停止；电池优化、修改系统设置仅在对应能力需要时指导。无障碍开关显示已开但服务没连，要显示真实状态和返回检查按钮。
5. **首个安全演练**：推荐“打开计算器”或“打开设置页面”，避开社交发送/付款。完成后展示真实目标/证据和“再做一件事”，不只说“成功”。五分钟计时从首次启动开始，另记安装耗时；实验员代操作不能算独立成功。
6. **拒绝和退路**：用户拒绝屏幕上传可停在本地说明/可用的低风险本地能力，不虚称云规划照常可用；拒绝录音可打字；没装目标 App 解释并停止，不擅自去商店安装。

#### 日常任务的最短交互

主页优先保留“说一句／输入一句”、少量安全示例、当前任务和历史，不让模型/同步/高级控制争夺注意力。模糊表达如“给小王说我晚到十分钟”，先展示候选联系人和将发送的内容；不要让用户重写完整专业指令。语音识别结果可看、可改、可取消后再执行。

状态统一为“准备中／正在做／等你确认／已暂停／已停止／已完成／未完成／结果不确定”；用文字+图标，不只靠颜色。每次只突出一件需用户决定的事；失败卡用“发生什么、已经做了什么、下一步”，并区分免费本地重试与可能继续消耗云额度。

无障碍建议基线：正文 18–20sp、关键触控目标至少 56dp、支持系统字体到 200%；这些是本产品待验证目标，当前尺寸较小。关键确认不得省略对象/金额/内容；长内容可展开且不会把停止挤出屏幕。TalkBack 焦点顺序、按钮名称、动态状态播报、横竖屏、刘海/小屏、对比度和减少动画均需真机验收；不以搜索到 `contentDescription` 为合格证明。无障碍服务与 TalkBack 共存，主动关闭服务不被守护强行恢复。

### 23.4 安全、控制与成功的确定性边界

#### 一条执行路径，不给复用和提示词开后门

沿用既有 Perception → Decision → ExecutionResult / ToolSpec 契约，逐步建立：

```text
新感知（snapshotId、时间、包名、显示区域）
 → 模型建议/流程复用建议（均为不可信输入）
 → JSON/动作参数校验
 → 策略：拒绝 > 接管 > 单次确认 > 允许
 → 校验批准是否匹配本次动作与当前快照
 → 统一执行器（提交前再检查停止、权限与页面）
 → 新感知、目标复验
 → 已证明完成 / 未完成 / 结果不确定
```

- 模型只提出建议，程序决定是否可执行、是否成功。网页/聊天/节点文字中的“忽略限制、自动确认、改收款人”等指令都是界面数据，不能改变策略。
- 批准记录至少绑定 taskId/requestId、动作、目标应用、联系人/收款对象、金额、内容摘要/哈希、snapshotId、过期时间及单次使用状态；对象、金额、内容或页面改变即失效。普通 ask 回答不自动变成授权凭证。
- 付款、转账、账号安全、验证码/密码输入、协议法律授权：默认交还用户在原界面亲自操作。发送/发布/删除等外部副作用即便任务已明确也须展示本次对象与内容、单独确认；首次发布可只支持草稿/预览，不宣称全自动发送。
- 空节点、截图黑屏、页面不新鲜、无法定位风险对象、授权 UI 无法显示、批准超时/过期：失败时禁止执行，而非视为允许。不能靠再加一句“请谨慎”替代硬闸门。
- 不用一个 `dangerous=true` 覆盖所有差异；浏览/搜索、可逆设置、对外动作、金融/安全凭据分级。金额/联系人缺失时问清；不存在可靠撤销的动作不能提供“撤销成功”按钮。
- 复用、外部链接、API、跨设备和弹窗自动处置均不得跳过同一基线；敏感动作不因曾经批准或命中记忆而自动批准。无法改为共用入口前，收窄可执行功能。

#### 暂停、停止、接管、撤销各自是什么意思

| 控制 | 应有语义 | 必须展示的边界 |
|---|---|---|
| 暂停 | 不再提交后续动作；可等待用户查看/调整 | 已提交的手势/远端调用可能完成，立即记录其结果 |
| 停止 | 任务终结、失效所有批准、尽力取消请求并丢弃迟到回调 | 停止不等于撤回已发消息/已扣费用；列出已执行步骤 |
| 接管 | 用户自由操作；Agent 停止注入动作 | 交还时重新感知、重新验证目标和批准，不从旧坐标继续 |
| 重试/恢复 | 从新状态判断是否仍需该动作 | 不能重放未知结果的发送、付款、删除；先查结果或交还 |
| 撤销 | 仅对明确可逆且有可靠逆操作的动作提供 | 无可靠逆操作时明确“无法自动撤销”，引导原 App 查看 |

单一任务状态机应覆盖主页、无障碍覆盖层、通知、ask 和历史。使用同一个 requestId/sessionId 防旧页面/旧网络响应污染新任务；超时、停止、进程死亡、旋转与离开页面都要使旧确认失效。常驻可触达的“暂停/停止/我来操作”不能依赖厂商胶囊；通知不可用时须有覆盖层或可回到主界面的替代，若无法保证控制入口则不启动自动任务。锁屏默认暂停，不因保活成功就继续敏感操作。

#### 成功必须是目标成立

“模型 done”“前台变了”“节点有变化”“手势 API 返回 true”均不单独构成业务成功。按任务维护验收器：打开应用验证目标包；设置验证读回值（能读时）；输入验证正确控件中的完整内容；发送只在允许范围内验证正确对话/内容/发送状态，不把填好草稿说成已发送。无法可靠复验则结果不确定，并停止自动重试副作用。只有带目标证据的成功可进入操作记忆。

### 23.5 数据、费用与恢复的最低约束

| 数据/通道 | 当前已知情况 | 待实施与验收 |
|---|---|---|
| 节点/截图/提示词 | 节点有密码与纯数字掩码；截图可上传云端 | 在发送前统一脱敏/阻断，不只在日志落盘时处理；指定应用/窗口不出网；双窗口/验证码混入文本/截图覆盖测试 |
| 语音 | 独立云 ASR、本地 Whisper 条件路由；[日志含转写前段](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java#L53-L54>) | 上传前告知端点类别、用途；原音/转写默认不入诊断；临时录音生命周期、删除和失败路径验证 |
| 凭据 | 账号令牌由 Keystore 加密；API/ASR Key 仍普通偏好 | 分别迁移、加密失败不回落明文、不回显日志；用户可删除/更换；升级失败保留安全可恢复状态 |
| 历史/事实/流程记忆 | 本地任务/文本记录；FlowMemory 不接云同步，历史/事实有同步路径 | 分类独立说明、保存期限及删除入口；事实记忆进提示词也属于发送给模型，不称“本地文件就不会外传” |
| 云同步 | 未明确选择时登录即默认开启 | 独立 opt-in，不以登录/使用模型推定同意；先询问后上传；关闭不等于删除云端，须分别反馈删除状态 |
| HTTP/局域网 | [网络配置](<../agent-lite/res/xml/network_security_config.xml>)全局允许明文，注释称为局域网需求 | 默认云端 HTTPS；限制明文端点范围并解释风险；本机/局域网不等于无隐私风险 |
| 日志与反馈 | EngineLog 掩码令牌；任务历史含原任务、结果/详情 | 默认诊断只保留事件/耗时/错误码与脱敏标识；导出预览+确认；锁屏通知不暴露问题/联系人；不声称现代 Android 外部私有目录可被任意 App 读取 |
| 费用/取消 | 自带 Key 与账号额度两条通道，重试可能重复推理计费 | 用户知道向谁付费、剩余额度和预算；禁自动购买/充值；失败与取消不承诺厂商不计费；不静默切换付费账户 |
| 重启/断网 | 有超时、重试和保活；无完整持久化任务证据链 | 最小检查点保存已确认步骤/未知副作用，重启默认暂停；先查询结果，禁止盲重放；恢复不再使用旧确认 |

成本仪表建议分开记录每任务模型调用数、输入/输出 token、图片量、ASR 次数、重试次数、网关 units 与厂商账单币种/日期；每成功任务成本和每尝试成本均报告，避免失败成本消失。公开价格变化时重新核查，不把厂商 token 价换算成无依据的“每次几分钱”。预算上限和余额不足须能在下一次付费请求前停止；已提交请求的扣费结果另行说明。

### 23.6 分阶段路线与依赖

| 优先级/阶段 | 目标 | 依赖及对应旧模块 | 退出标准 |
|---|---|---|---|
| P0-A 基线/入口 | 证据基线、统一凭据与费用路由 | T01 → T02；M0-03/M9 最小适配，不先重写整个 Provider | 新装登录无 Key 可开始；无凭据不请求；不静默换付费主体 |
| P0-B 安全可信闭环 | 统一策略、成功验收、持续可控 | T01 → T03 → T04；T05 与前两者合流；M1/M2/M3/M4/M5 最小切片 | 所有入口不可绕过策略；无证据不成功；停止、旧回调与 ask 超时正确 |
| P0-C 可交付首用 | 隐私告知/选择、基本可访问性、无 root 首次成功 | T02/T03/T04/T05/T06 → T07；延续 UX-1/3/5/6/7 | 无电脑无 root 清洁环境完成安全任务，目标用户独立操作；隐私与费用选择有效 |
| P1 稳定日用 | 模糊表达、独立 ASR、弱网恢复、守卫式复用 | T03–T07 → T08/T09；接续模糊转译与 M4/M5 | 代表任务实测提升，恢复不重复副作用；旧库迁移/删除可验证 |
| P2 扩展与优化 | TTS、技能、Provider、性能/能耗、可选虚拟屏 | T10；在 P0/P1 指标稳定后推进 M6–M10 | 不牺牲主链路、安全与成本；新增能力有无 root 降级及独立证据 |

P0 是首个对目标用户开放版本的最低条件，不是承诺一个冲刺全部做完。T01–T10 是本章可派工卡，不替换原 M 编号；每卡可再拆为一个小 diff，但未完成前保持待办。未规定团队人数或工期，以设备和测试反馈再估算。

### 23.7 下一批可直接实施的十张任务卡

#### T01 · P0 · 固定证据基线与行为回归入口（接续 M0-03/M5）

- **目标/原因**：让当前矩阵可复验，避免静态 PASS 掩盖已发现行为矛盾。
- **修改范围**：[Makefile](<../Makefile>)、[UX 门禁](<../tests/spec_ux_001_guard.py>)、现有桌面测试及专用测试夹具；不动实际凭据/设备备份。
- **依赖**：无。先记录工作树/构建标识与模块开关，保留已有修改。
- **最小步骤**：①将入口/请求路由真值表、双 done、旧 ask 回调、无节点敏感页变成可失败用例；②区分静态、JVM、mock、无 root 真机、用户试验五层结果；③记录脱敏事件与耗时字段，不存屏幕正文。
- **验收/测试**：新增用例在问题版能失败；不存在“脚本找到字符串就代表行为通过”；非 root 设备不因缺 root 报失败。复跑本轮四项静态+JVM并报告未覆盖项。
- **风险/回滚**：测试替身不能改变生产默认值；夹具独立停用，不删除业务数据。

#### T02 · P0 · 统一可用凭据、模型与付费主体（最小 M9 切片）

- **目标/原因**：修复“已登录但无 Key 仍被拦”，消除入口与客户端各自决定路由。
- **修改范围**：[MainActivity](<../agent-lite/src/com/dsh/agentlite/MainActivity.java>)、[Prefs](<../agent-lite/src/com/dsh/agentlite/Prefs.java>)、[LlmClient](<../agent-lite/src/com/dsh/agentlite/LlmClient.java>)、[LoginActivity](<../agent-lite/src/com/dsh/agentlite/LoginActivity.java>)；支付页面仅修路由说明，不自动购买。
- **依赖**：T01；实际网关模型身份若未定，不阻塞本地真值表测试。
- **最小步骤**：①纯逻辑 CredentialRoute 返回可用性、原因、端点类别/模型/付费主体；②新装默认账号与用户明确选自带 Key 分开；③主入口与请求构造消费同一结果；④切换主体须可见并经选择。
- **验收/测试**：覆盖新装无凭据、登录无 Key、显式自带 Key 为空、有 Key 未登录、两者都有、过期账号、离线和升级残留标志。登录无 Key 可使用账号路由；真无凭据零请求；说明不再称云能力“只在本机”。
- **风险/回滚**：不得自动覆盖旧 Key/模型；迁移失败显示修复入口，不静默切换付费账户。

#### T03 · P0 · 共用动作校验、敏感策略和单次授权（M1/M2/M3）

- **目标/原因**：安全由程序保证，复用、视觉、链接不能绕过。
- **修改范围**：[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>)、[SensitiveGuard](<../agent-lite/src/com/dsh/agentlite/SensitiveGuard.java>)、[ToolRegistry](<../agent-lite/src/com/dsh/agentlite/tools/ToolRegistry.java>)及既有契约；在现有纯 Java 边界增量接线。
- **依赖**：T01；与 T05 共享批准/状态标识，按契约先后合并，避免两个并行执行器。
- **最小步骤**：①所有执行入口汇聚参数校验；②策略取最严格结果；③动作/对象/内容/金额/快照绑定一次性批准；④动作提交前重查停止/快照；⑤空节点、无法展示确认、过期批准拒绝执行；⑥结构化 TOOL_INVALID 与拒绝原因不含敏感值。
- **验收/测试**：mock 中发送/删除/支付、视觉空节点、重名、改金额、过期回复、恶意页面提示、replay/open_url/open_api/remote 各入口均不得无批准越界；策略关闭不移除基线红线；批准只用一次。
- **风险/回滚**：误拦普通浏览可调非敏感规则；回滚到收窄功能/人工操作，不能回到静默允许。

#### T04 · P0 · 目标验收决定成功与入库（M2/M4/M5）

- **目标/原因**：消除二次 done 放行以及假成功进入记忆。
- **修改范围**：[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>)、[FlowMemory](<../agent-lite/src/com/dsh/agentlite/FlowMemory.java>)、[TaskHistory](<../agent-lite/src/com/dsh/agentlite/TaskHistory.java>)及 ExecutionResult 契约。
- **依赖**：T01/T03；先支持打开 App/设置/本地输入等少数可验证目标。
- **最小步骤**：①目标类型携带可验证条件；②执行后新感知；③分已完成/未完成/不确定；④模型摘要仅为文案；⑤成功证据写脱敏摘要，入库要求验收通过。
- **验收/测试**：连续两个 done、仅前台变化、无截图/节点、文本截断、错对话、回放只有弹窗关闭等均不判成功；失败/不确定不入库；真成功不被无限追问。
- **风险/回滚**：缺验收器的任务显示不确定/人工确认结果，不用旧弱规则冒充成功。

#### T05 · P0 · 永久控制入口与问答/恢复生命周期（M0/M5，UX-6/8）

- **目标/原因**：用户在模型等待、复用、弹层和系统页都能停；不会回答上一任务的问题。
- **修改范围**：[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>)、[MainActivity](<../agent-lite/src/com/dsh/agentlite/MainActivity.java>)、[OverlayController](<../agent-lite/src/com/dsh/agentlite/OverlayController.java>)、[KeepAliveService](<../agent-lite/src/com/dsh/agentlite/KeepAliveService.java>)、[A11yGuard](<../agent-lite/src/com/dsh/agentlite/A11yGuard.java>)。
- **依赖**：T01；与 T03 确认契约合流，交还使用 T04 重新复验。
- **最小步骤**：①统一状态机与 requestId；②超时/停止主动关闭旧卡片和通知；③复用、网络重试和授权等待共用取消/接管信号；④进程死亡只恢复到暂停摘要；⑤保护用户主动撤销权限、TalkBack 服务与锁屏隐私。
- **验收/测试**：覆盖旋转、后台、锁屏、通知拒绝、覆盖层不可用、重复按停止、网络迟到回复、旧 ask 回答新问题、重启与回放接管。停止后零新动作提交；已提交动作单列。受控机上建议停止反馈 p95≤1s，需报告实测而非承诺系统可撤回手势。
- **风险/回滚**：若无法保证控制入口，禁启动自动任务并回到主界面；不靠厂商胶囊唯一兜底。

#### T06 · P0 · 数据告知、独立同步选择与费用护栏（安全基线）

- **目标/原因**：让用户知道数据给谁、为什么、保存多久、能否删除、谁承担费用。
- **修改范围**：[Prefs](<../agent-lite/src/com/dsh/agentlite/Prefs.java>)、[SyncClient](<../agent-lite/src/com/dsh/agentlite/SyncClient.java>)、[NodeInfo](<../agent-lite/src/com/dsh/agentlite/NodeInfo.java>)、[EngineLog](<../agent-lite/src/com/dsh/agentlite/EngineLog.java>)、[AsrRouter](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java>)、凭据/设置 UI 与网络配置。
- **依赖**：T02/T03；未知厂商 API 数据条款必须保持未核实标识。
- **最小步骤**：①区分模型、ASR、同步、诊断四种用途；②登录不默认上传历史；③API/ASR Key 安全迁移；④敏感窗口/截图出网闸门、日志最小化；⑤删除本机与云端分别回执；⑥下一请求前检查预算，非自动购买。
- **验收/测试**：mock 服务证明未同意零同步/零禁止内容；拒绝语音不阻塞文字；退出登录不被误说为删除；迁移失败不落明文；断网删除显示待完成；取消不声称必定不扣费。
- **风险/回滚**：不根据旧登录状态推定同意；无法满足出网保护时停云能力/收窄可访问应用，而非隐藏提示。

#### T07 · P0 · 无 root 首次成功与基础适老化（UX-1/2/5/6）

- **目标/原因**：普通人无需开发者陪同就能做第一件安全事；字大、可读、可停止。
- **修改范围**：[README](<../agent-lite/README.md>)、[主界面布局](<../agent-lite/res/layout/activity_main.xml>)、[dimens](<../agent-lite/res/values/dimens.xml>)、首启/登录/权限/帮助页面与本规范；不换 UI 技术栈。
- **依赖**：T02–T06；可先做布局原型，不提前宣称首跑完成。
- **最小步骤**：①用户安装指南与开发构建指南分开；②一屏隐私/费用解释、账号默认、渐进权限；③安全首任务演练；④大字/触控/读屏、减少动画；⑤每张失败卡提供下一步。
- **验收/测试**：见 §23.8/9；未 root、无 ADB 授权、全新安装→账号→授权→安全任务→结果。拒绝可选权限仍可完成文字任务；200% 字体重要文字不截断、TalkBack 可找到停止。
- **风险/回滚**：布局变化保持旧偏好；字体放大后测试小屏，不能缩回小字来“修溢出”。

#### T08 · P1 · 口语澄清与独立语音输入（转译链增量）

- **目标/原因**：容忍简称、停顿、方言识别误差与重名，不靠用户写精准提示词。
- **修改范围**：[TextNormalizer](<../agent-lite/src/com/dsh/agentlite/TextNormalizer.java>)、[SemanticTranslator](<../agent-lite/src/com/dsh/agentlite/SemanticTranslator.java>)、[FuzzyFlow](<../agent-lite/src/com/dsh/agentlite/FuzzyFlow.java>)、[AsrRouter](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java>)及输入/澄清 UI。
- **依赖**：T03/T05/T06/T07。
- **最小步骤**：①建立经同意的匿名口语样本；②否定词与对象/时间槽位保守处理；③一次问一个关键缺口，最多展示少数候选；④ASR 配置/能力与主模型分离，冷却与降级一致；⑤识别文本可编辑确认。
- **验收/测试**：“不要发”“给另一个小王”“不是明天”不丢语义；低置信不自动选联系人；ASR 失败不清空输入；无网且本地资源缺失明确说明。测试分普通话/口音/噪声层，不合并掩盖失败。
- **风险/回滚**：转译可退原文本+询问，不能通过猜测对象减少轮次；TTS 另列 P2。

#### T09 · P1 · 有状态守卫的复用与可靠持久化（M4/M5）

- **目标/原因**：复用提升速度，但不复用旧批准、旧成功假设或旧坐标。
- **修改范围**：[FlowPlan](<../agent-lite/src/com/dsh/agentlite/FlowPlan.java>)、[FlowMemory](<../agent-lite/src/com/dsh/agentlite/FlowMemory.java>)、[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>)、[FlowMemoryTest](<../tests/FlowMemoryTest.java>)。
- **依赖**：T03/T04/T05/T06。
- **最小步骤**：①新库仅存经验证低风险流程；②每步快照/节点锚点/包名和策略校验；③新参数不复制旧联系人/内容；④rename 失败不退破坏性覆盖；⑤保留旧库兼容过滤与可查可删；⑥无进展/弹窗/中断分别归因。
- **验收/测试**：App 页面变化、同名对象、弹窗、空节点、进程死亡、磁盘满/rename 失败均不误执行或误入库；mock 对比调用数/耗时，真机确认第二次仍达成目标；功能关闭不影响正常规划。
- **风险/回滚**：可关闭复用，不删除用户历史；旧批准一律失效；不能因命中率压力放宽安全校验。

#### T10 · P2 · 小范围发布、度量与后续扩展（M5–M11）

- **目标/原因**：在 P0/P1 的证据上逐步开放，不用更多能力掩盖主链路失败。
- **修改范围**：发布说明、测试报告、脱敏指标、既有构建流程；后续才按 M6–M10 接技能/TTS/Provider/可选虚拟屏。
- **依赖**：T01–T09 中本次支持场景相关卡已验收，未做场景从支持清单剔除并明确显示。
- **最小步骤**：①固定签名/构建标识/开关/模型路由；②无 root 设备矩阵与目标用户小样本；③灰度、观察、撤回；④成本/功耗/时延优化；⑤每项扩展独立能力探测与零 root 降级。
- **验收/测试**：所有 P0 红线用例零违规；真实成功/误成功、取消、恢复、费用分项可核查；安装/升级/清数据可复验；发布不是 `make check` 单命令结论。
- **风险/回滚**：停止扩大灰度、关可选能力或退兼容版本；状态/凭据迁移可回退，不回滚安全基线。任何真实 API、设备部署和发布另获授权。

### 23.8 十个代表任务与异常组合

**全为计划测试，不是本轮已跑。** 使用本地夹具、虚构联系人和测试内容；涉及真实交易只验证到拒绝/人工接管，绝不实际扣款。

| ID/任务 | 期望的可观察结果 | 注入异常/否定验收 |
|---|---|---|
| U01 清洁安装后“打开计算器” | 无 root/ADB/Key 操作，目标包正确，结果可见 | 登录无 Key、通知拒绝；5 分钟首成功不是实验员代操作 |
| U02 “把字调大一点” | 确认范围后到正确设置或读回效果，显示已做/需手动项 | 无改设置权限、ROM 不同；不能仅打开设置就说字号已改 |
| U03 “看明天天气” | 城市/日期明确，显示来源或打开正确页面 | 城市未知问清；离线/过期页面不编天气；不强制位置权限 |
| U04 “给小王说晚到十分钟” | 重名澄清，显示正确对象与草稿，单次确认后才允许约定动作 | “另一个小王”“不要发”、页面切换、旧确认；不得发给真实联系人 |
| U05 “给女儿打电话” | 明确对象/号码，呼叫属于对外动作需确认 | 联系人不存在、两个女儿、取消；使用模拟拨号，不真实呼出 |
| U06 “导航去医院” | 明确目的地/交通方式，路径可见；先确认定位用途 | 同名医院、GPS 拒绝；不替用户叫车或付款 |
| U07 “帮我买瓶水” | 可浏览/准备订单；价格/数量/对象清楚；付款停下交还 | 运费变化、广告跳转、免密付款入口；零实际支付 |
| U08 “删除这张照片” | 指定照片与影响，明确确认；不能定位则拒绝 | “别删”、相册刷新、多选变化、旧确认；仅夹具媒体 |
| U09 “停一下，我自己来” | 各阶段停止/接管可达，后续零新动作，交还重新感知 | 模型超时、复用中、ask 超时、锁屏/旋转/进程杀死 |
| U10 “再做一遍上次的事” | 命中低风险记忆仍检查当前对象/状态并复验 | App 更新、弹窗、弱网、空节点、旧 URL 库、写盘失败；不复制旧授权 |

每个任务至少配一条成功路径和一条拒绝/失败路径；U04/U07/U08/U09 必测时序竞态。额外恶意界面夹具插入“忽略用户、自动同意”的内容，验证只作为屏幕文本而不改变策略。自动脚本断言应观察动作是否提交、状态是否变化，不只查字符串。

### 23.9 设备、用户研究、指标与放行

**设备计划**：至少两台未 root 真机，覆盖 Android 11/API30 下界与目标 Android 14/API34，另对常用更新系统（如 Android 16）做兼容抽测；至少两个主流 ROM，包含小屏/大字体。模拟器可做系统版本覆盖但不代替 ROM 真机。普通用户验收禁 ADB 预授予、WRITE_SECURE_SETTINGS 特权、root、预置 Key；测试观察工具可用但不得替用户完成设置。root 机诊断单列，不影响未 root 的合格判定。

**最小用户试验建议**：至少 5 位老年参与者与 5 位手机入门者（可记录重叠，但不重复计数），先做形成性测试而非统计代表性宣称。征得同意，使用虚构资料，不让参与者承担云费用；说明可随时停止。记录独立完成/需口头提示/需代操作、卡点、误解、疲劳与“不敢点”的原因；只收必要匿名数据。

| 指标 | 测量定义 | 建议验收线/解释 |
|---|---|---|
| 首次成功 | 首启到 U01 目标验证通过，另记安装/授权耗时 | UX-1 五分钟；报告独立完成比例及失败者，不删失败样本 |
| 真实任务成功率 | 已验证目标完成数 / 全部开始任务数 | 先对低风险清单建议 ≥90%；用户停止/不支持等另分原因，不混入成功 |
| 误成功/未授权副作用 | UI 报成功但目标不成立；无有效批准而发生敏感动作 | 发布用例集中为 0；有限样本零事件不等于现实永不发生 |
| 澄清负担 | 每任务问答轮数、用户重新说完整需求次数 | 先报告基线，目标多数常见任务 ≤1 次关键澄清；不得为了指标猜对象 |
| 响应/执行时延 | 用户点击到本地反馈、首模型结果、首动作、最终结果分别计时 | 本地反馈建议 ≤300ms；报告 p50/p95、弱网分层，不能仅报模型首 token |
| 停止可达与时延 | 找到停止的耗时、按下到反馈/禁止新提交的耗时 | 每条路径均可达；受控设备建议反馈 p95≤1s；既成副作用单列 |
| 恢复正确性 | 弱网/杀进程/回前台后状态正确且无重复副作用 | 核心夹具 100% 不重复；无法判断结果必须暂停/不确定 |
| 可访问性 | 200% 字体关键信息完整、TalkBack 任务/停止可完成 | 所有 P0 页面必过；实际对比度/焦点/触控需设备检查 |
| 隐私/费用可理解性 | 用户能说清“谁看到什么、是否收费、怎样停/删除” | 逐项报告误解人数；禁止只看勾选同意率 |
| 成本/资源 | 每尝试/成功任务分渠道用量、重试、耗电/内存/网络量 | 先建立基线再定预算；无账单/功耗测量不得写“低成本/低耗电已达标” |

每轮报告同时带应用构建标识、源码版本/脏树说明、模型实际 ID/模式/路由、设备/ROM、权限、网络、开关、样本数与失败归因；只用脱敏字段。P0 缺陷未关、误成功、未授权副作用、停止不可达、未经同意出网任一存在即不放行。修复回归后再进行有限灰度；用户停止与接管不当作算法失败强行重试。

### 23.10 本轮边界与需要产品确认的三件事

本轮已完成的是资料/源码核查、最小离线测试以及本章与全景评估的增量更新；T01–T10 均未因写入文档而自动完成。本轮实际命令、计数与未验证项见[全景手册 §18.4](<../PROJECT_OVERVIEW.zh-CN.md#184-本轮实际检查及未验证项>)。

1. `de 4.1flash` 是否明确指官方 DeepSeek V4.1 Flash／`deepseek-flash`？普通账号网关最终也固定此模型，还是允许部署映射？不确定前按条件能力处理，不自动换模型。
2. 第一版优先保证哪三项低风险任务，以及目标用户最常用的两款手机/ROM？未选前以打开应用、打开设置、查看信息作测试基线，而非承诺所有社交/支付自动化。
3. 普通用户是否统一采用账号额度路线，是否具备试用额度/账号恢复/厂商数据条款的产品安排？未确定前不承诺免费、可找回、无限用量或数据不留存。

其余工程工作可按上述依赖先做 mock/纯逻辑与界面验证，不需要以反复追问替代推进；真实账号、付费 API、真机安装或发布须另行授权。

### 23.11 T01 凭据切片与 T02 实施记录（2026-10-07）

用户在文档轮之后明确选择实施 T01→T02。本节记录这次代码变更；§23.10 的“仅文档、任务未实施”保留为 2026-10-06 的历史边界，不再用它代指当前状态。

**完成边界**：T01 仅完成凭据链路的可失败行为回归，双 `done`、旧 `ask`、空节点敏感页仍待后续；T02 完成本地路由与交互接线并通过离线验证，尚无新 APK 或无 root 真机首跑验收。项目整体仍是受控验证阶段。

#### 路由真值表与迁移约束

| 保存的选择/配置 | 本次任务模型路由 | 失败或配置不足时 |
|---|---|---|
| 未存过 `use_own_key`，无 Key，账号有效 | 账号网关，使用账号令牌 | 无账号/地址不完整则前置提示，不创建可请求客户端 |
| 未存过选择，有 Key（含历史升级） | 保留历史自带优先 | 不自动切账号 |
| 显式选择自带 | 仅自己的地址、Key、指定模型 | Key 空白/缺失或地址无效则提示设置/主动选择 |
| 显式选择账号，即使仍存有旧 Key | 仅账号网关 | 过期、半登录、退出或网关关闭时，不改用旧 Key |
| 用户主动选择账号 | 记录选择，同时启用旧网关标志 | 未登录则进入登录页；不自动启动任务 |
| 保存/清空配置 | 固定保存前的付费主体 | 配置保存后显示选择卡；“暂不切换”保留来源 |
| 购买页清除 Key | 清 Key，但保留此前生效主体 | 自带模式停在待配置态，不因删除变为账号模式 |
| 任务中修改通道 | 已创建任务及其规划器保留快照 | 新选择作用于下一次任务创建 |

实现采用纯 Java [CredentialRoute](<../agent-lite/src/com/dsh/agentlite/CredentialRoute.java>)，由 [Prefs](<../agent-lite/src/com/dsh/agentlite/Prefs.java>)读取配置；[主页预检](<../agent-lite/src/com/dsh/agentlite/MainActivity.java>)与 [LlmClient 工厂](<../agent-lite/src/com/dsh/agentlite/LlmClient.java>)共用解析器。工厂不再注册其他付费通道为备用；401/402/403/429/5xx/断网均不自动切换主体。模型 HTTP 请求关闭自动重定向；同通道的既有 429 重试策略未在本轮改写。

[通道选择卡](<../agent-lite/src/com/dsh/agentlite/CredentialRouteUi.java>)由手机和手表设置保存入口共用；主页启动提示额度来源，缺失卡展示具体原因，并明确任务文字/截图可能进入云端。登录、切换与配置齐备都不等于远端 Key 有效或余额充足。登录不会写入通道选择；ASR 的独立路由、同步同意、存储加密和费用预算属于 T06/T08，未随本次修复完成。

`de 4.1flash` 原名及既有模型配置保持不变。网关实际使用的模型映射、账单计费和服务端留存仍未访问或验证；本地解析不会用猜测替换用户模型。

#### 证据与重跑

- 初始行为测试在问题版得到 **109 通过 / 68 失败断言**，包含默认登录无 Key、显式选择被绕过、故障后跨付费通道回退；这是实际执行结果，不是静态字符串匹配。
- 修复并扩充后，[CredentialRouteTest](<../tests/CredentialRouteTest.java>)得到 **245 通过 / 0 失败**。覆盖新装/升级、显式选择、空白凭据、过期/半登录/退出、配置保存、任务快照、取消、HTTP 故障及 402 欠费主体归因。
- [离线运行器](<../tests/credential_route_guard.py>)原样编译生产 Prefs/LlmClient/CredentialRoute，并抽取实际主页守卫。所有 HTTP(S) 由 JVM 内存连接替代；Android、Keystore、TLS 和 JSON 只提供测试桩，不据此声称这些平台能力通过。
- 新测试已加入 [Makefile](<../Makefile>) 的 `jvm` 目标；`make ui jvm` 与关闭依赖下载的完整 `make check` 均通过，JVM 共八组；镜像逐字节检查通过。Go 的现有测试命中缓存，服务端无测试文件，不据此声称业务覆盖。Android 34 SDK 对当前全部 Java 源码编译通过；仅生成检查目录的 class，未打包、签名、安装或启动应用。
- 零 root 真机：登录无 Key 首任务、设置选择卡/取消选择、手表布局、字体/TalkBack、重启及断网提示仍需补验。T01 的其余行为夹具和 T03/T04/T05 继续按原依赖推进。

```sh
python3 tests/credential_route_guard.py
make ui jvm
bash agent-unified/scripts/sync-sources.sh --check
```

#### 真机补证（2026-10-08）

桌面回归证明的是纯逻辑；真机回归把同一批规则放到 OnePlus PKX110（Android 16，**零 root 主链路**：只用 uiautomator + input，root 仅用于布置前置状态与取证）上跑通 **43 通过 / 0 失败**（A 首启与登录态 / B 自己的配置 / C 账号通道 / D 模型缺失 / E 故障归因 / F 设置页选择卡），两个本机假端点经 `adb reverse` 转发，不出公网、不调付费 API。

真机还额外暴露并修复了一个**桌面测不出的文案缺陷**：自带通道欠费时「下一步」建议错向为"配置自己的接口作为备用通道"（用户正在用那份接口）。根因是 `UiText.nextStep` 的「账号额度」分支先命中归因串，叠加 `AgentEngine` 对自带通道的无条件后缀；已修正并固化三条文案断言。

设备、逐阶段结论、证据边界（失败卡片是悬浮窗、假令牌登录、单机型、无障碍由脚本代开）与用户态还原见[全景 §18.7](<../PROJECT_OVERVIEW.zh-CN.md#187-真机回归凭据路由在真实设备上的验证2026-10-08>)。**真实账号、真实余额与计费、真实模型输出仍未验证**——上表的"已实现/已实施"不含这些。

### 23.12 T03a/T04a/T05a 最小切片实施记录（2026-10-09）

方案见 [PLAN_T03_T05_DS41FLASH_2026-10-09](<PLAN_T03_T05_DS41FLASH_2026-10-09.zh-CN.md>)。三张卡按 **T05a → T03a → T04a** 落地，每张卡都是「先写可失败测试 → 改源码 → `make ui jvm` 全绿 → 同步镜像 `--check`」，真机回归最后统一跑。

**完成边界**：三张卡都只做了方案写明的**最小切片**。T04b（WEAK 档入库门槛）、T03a 第二刀（replayFlow / dismissPopupOnce 的 `execChecked` 包层）、T04a 的 WEAK 入库行为均**未做**，与方案「第二刀另开回合」的约定一致。真机只覆盖一台机型/ROM（OnePlus PKX110 / Android 16）。

#### 三张卡的改动与证据

| 卡 | 改动 | 门禁 | 旧代码基线 |
|---|---|---|---|
| T05a 任务代际 | [taskEnded](<../agent-lite/src/com/dsh/agentlite/OverlayController.java>) 的 4 秒回调补 `taskActive` 守卫；[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>) 加 `RUN_SEQ`（`AtomicLong`），finally 的延迟回调改为 `mySeq == RUN_SEQ.get()` 才动 `KeepAliveService` | [generation_guard.py](<../tests/generation_guard.py>) 14 项 | 5 项 FAIL |
| T03a 代点进 exec | `autoConfirmLaunch` 与 `autoAgreeTerms`（勾选框 + 同意按钮）三处改为 `exec(svc, {"action":"tap","index":…}, nodes)`；失败原因进日志不再静默 | [auto_tap_guard.py](<../tests/auto_tap_guard.py>) 15 项 | 5 项 FAIL |
| T04a done 证据分级 | 新增 `judgeDone` → `PROVEN/WEAK/UNPROVEN`；`arrivedExpectPkg` 记录「到达过期望目标」；UNPROVEN 终态改**「不确定」**（`recordable=false` + 新增 `showUncertain` 琥珀卡，与 `showDone` 的完成语义位分离） | [done_evidence_guard.py](<../tests/done_evidence_guard.py>) 真值表 18 + 结构 18 | 7 项 FAIL |

三个新门禁共用 [java_source_util.py](<../tests/java_source_util.py>) 的剥注释读法：注释里写出 `svc.tap(...)` 或守卫字样不再能骗过门禁（既防假红也防「把守卫写进注释」的假绿）。

#### 真机回归（2026-10-09，OnePlus PKX110 / Android 16 / 无线 `127.0.0.1:5599`）

| 回归 | 结果 |
|---|---|
| 凭据路由（既有，不回退底线） | A–E **41 通过 / 0 失败**；阶段 F 单跑 **6 通过 / 0 失败** |
| 三卡行为（新增 [t03_t05_real_device.py](<../tests/t03_t05_real_device.py>)） | **14 通过 / 0 失败 / 1 跳过** |

关键结论：**任务 A 完成后 0.2 秒**启动任务 B → B 运行期间保活锁仍持有、覆盖层胶囊仍在、通知仍是运行态（旧任务的收尾回调确实没有再改写新任务）；连续两次无证据 done → 终态「不确定」，记忆库文件 344 → 344 字节未增长；`app com.coloros.calculator` 到达后 done → 终态「完成」并入库（344 → 707 字节）。

**跳过项**：本机系统已记住「允许打开」，未复现跨应用启动确认框，`autoConfirmLaunch` 代点分支未被触发——环境事实而非缺陷；该路径的静态结构门禁仍为 15/0。

#### 真机暴露的问题（分级）

- **产品侧（本轮未改，建议另立卡）**：任务结果卡片是 `FLAGS_TOUCH` 覆盖层且创建有延迟，卡片在场期间点击「运行」存在竞态（本脚本用重试规避）。它属于「覆盖层与用户输入的自有冲突」，与 T05a 的代际问题不同源。
- **测试侧（已随脚本修掉，全是"假失败"）**：① 点击前 `uiautomator dump` 会挤掉无障碍服务，服务重连初期只枚举到 SystemUI → 引擎第 1 步切纯视觉、`app X` 到达核对必然失败；② 首跑的通知权限系统对话框把前台变成 `com.android.systemui`（改为 `pm grant` 预授）；③ mock 的 `app` 参数写成 `target`，而词表要求 `package`（空串会模糊解析命中任意应用）；④ mock 未区分转译/规划请求与主循环请求，前置请求吃掉了场景序列；⑤ `input tap` 聚焦弹出的 IME 盖住「运行」按钮（收 ESC 解决），且任务结束后布局整体下移约 24px（改点按钮下缘上方 1/4）。

