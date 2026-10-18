# 实际功能接口、实现归属与最小架构复核

> 日期：2026-10-10；项目：repo；基线：HEAD `2c73a9f` 加本地未提交工作树。
> 本轮交付为代码审计文档，未修改业务源码、未部署、未做真机或性能压测。下文“风险/成本”为调用链和复杂度静态推导，不是延迟或吞吐实测。
> 本文纠正[上一版审计](<INTERFACE_AUDIT_2026-10-09.zh-CN.md>)中的计数、接线与删除判断；发生冲突时以本次引用的源码为依据。
> **后续实施已完成**：[2026-10-11 接口收敛实施报告](<INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md>)记录 F01–F14 的源码落地、当前契约、回归与构建结果。本文保留改造前审计快照，旧行号及问题描述不代表改造后的状态。

## 1. 结论先行

**主要问题不是类数量，而是同一状态有多个管理者、一个请求被拆成多次搬运、同一功能保留多套行为。**

优先处理：
1. **同步契约失配**：服务端分页返回流末尾 `server_seq`，iOS 把它直接作为下次游标，会跳过未返回页；Android 拉回记录后只消费配置。
2. **同一同步批次拆成多次请求**：本机任务、转发任务、记忆、状态各自 push，服务端每个 push 都刷新并重写设备表。
3. **远程任务没有唯一结果归属**：Android 用“最新历史记录”推断指令结果；服务端终态 ack 后删除指令，查询接口随即失去结果。
4. **无价值的重复网络调用**：iOS 一次刷新连续请求两次相同账号档案；ASR 的冷却分支最终仍可调用同一个失败端点。
5. **抽象破坏行为**：服务端日志 ResponseWriter 包装遗漏流式能力，导致 Flush 和写期限扩展失效。
6. **存储边界不完整**：兑换码、余额、流水、订单分别落盘，互斥锁并不等于跨文件事务。

最小方向：**明确一个状态所有者，合并同一目的的请求，保留必要的系统/网络边界，删除未使用的备用执行语义。** 不新增通用 Router→Manager→Service→Repository 四层套壳。

### 1.1 “一个接口对应一个实现”的准确口径

- 本文按**可调用功能边界**列接口：HTTP 方法/路径、模型动作、App/CLI 功能入口、平台桥接、存储操作。私有工具函数、控件回调、编译产物不逐个算业务接口。
- 一个业务能力指定一个实现所有者；UI、CLI、后台触发可以共用它，不各写一份规则。
- 别名应在输入边界归一化，别在执行器里保留带不同成功/失败语义的平行分支。
- Android 无障碍、iOS URL 打开、Harmony hdc 是不同平台机制；统一契约和验收，不强迫共用跨语言运行时。
- “未在 App 接线”“仅 CLI/测试消费”“仓内零调用”“可删除”是四种不同结论。
- 本仓现有规则要求零 root 为主基线，root 仅为可选能力，见[项目规则](<../AGENTS.md#L6-L25>)。精简时先删重复策略，不把可选 root 提升成主路径。

## 2. 项目边界与事实来源

| 项目 | 实际职责/边界 | 主要入口与唯一归属 | 裁决 |
|---|---|---|---|
| agent-lite | Android 主 App；感知、决策、执行、账号、同步、远程指令 | [Manifest](<../agent-lite/AndroidManifest.xml>)；[AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>) | 主执行链，优先收敛 |
| agentlite-server | 账号、设备、记录同步、配置、额度、订单、指令、LLM 代理 | [路由注册](<../agentlite-server/main.go#L94-L134>) | 服务端业务规则唯一所有者 |
| ios | SwiftUI App + Core + CLI；跨设备控制、有限本地动作 | [AgentLiteClient](<../ios/Sources/AgentLiteCore/AgentLiteClient.swift>)；[AppModel](<../ios/App/AgentLiteIOS/AppModel.swift>) | 不是 Android 全屏自动化的同能力复制 |
| agent-client | 另一套 Android 执行 App，含虚拟屏能力 | [AgentEngine](<../agent-client/src/com/dsh/agent/AgentEngine.java>)；[SandboxController](<../agent-client/src/com/dsh/agent/SandboxController.java>) | 同源重复候选；归档前先明确仍需保留的产品能力 |
| agentd | Go 本机 Web/API/执行循环 + APK 无障碍桥 | [Go 路由](<../agentd-go/main.go#L387-L406>)；[APK 桥](<../agentd-apk/src/com/dsh/agentd/AgentA11yService.java#L182-L184>) | 独立执行栈，不是云端账号服务的中间层 |
| harmony-agent | 主机 Python 经 hdc 驱动 Harmony 设备 | [run/main](<../tools/harmony-agent/harmony_agent.py>)；[hdc 适配](<../tools/harmony-agent/hdc.py>) | 原型/工具入口，不计作 Android 的第四层 |
| agent-unified | 从主源码复制出的构建/发布目录 | [sync-sources](<../agent-unified/scripts/sync-sources.sh#L4-L27>) | 不是运行时转发层；只在上游改源码，不双写业务 |

```text
Android UI/语音/远程任务 → AgentEngine → AgentA11yService → Android
                           └→ LlmClient → 已选推理通道
Android 账号/同步/指令 ─────────────────────────→ agentlite-server
SwiftUI/CLI → AgentLiteCore ───────────────────→ agentlite-server
                        └→ IOSLocalActionProvider → URL/Shortcuts
Android LanSync ←──────── 加密 LAN 信封 ────────→ iOS LANPeer（CLI入口）
agentd Web/API → Runner → device/APK桥 → Android    （独立）
Harmony CLI → run → hdc → Harmony                  （独立）
agent-unified：以上部分源码的发布镜像，不参与请求链
```

## 3. 服务端 HTTP 接口完整清单

计数来自[实际注册](<../agentlite-server/main.go#L95-L132>)：**22 个显式 method/path 操作、20 个不同业务路径，加 1 个 `/` 未匹配请求处理项**。23 个注册项不等于 23 个业务接口；GET 的协议级 HEAD 行为不另算功能。

鉴权缩写：`—` 无账号令牌；`A` Bearer 账号；`D` A + 同账号 `X-Device-Id`；`M` 管理令牌。详细实现见[中间件](<../agentlite-server/api.go#L113-L175>)。

| # | 方法与路径 | 权限 | 唯一 handler → 业务实现 | 输入 → 输出/职责 |
|---|---|---|---|---|
| S01 | GET `/health` | — | [handleHealth](<../agentlite-server/api.go#L180>) → Devices/Accounts | 服务状态、账号/设备/活跃数 |
| S02 | POST `/api/v1/account/register` | —，限流 | [handleAccountRegister](<../agentlite-server/api.go#L220>) → RegisterAccount | name/key → 账号、token、额度 |
| S03 | POST `/api/v1/account/login` | —，限流 | [handleAccountLogin](<../agentlite-server/api.go#L246>) → LoginAccount | name/key → 登录态 |
| S04 | GET `/api/v1/account/me` | A | [handleAccountMe](<../agentlite-server/api.go#L265>) → DevicesOfAccount | 档案、额度、设备、LAN参数 |
| S05 | POST `/api/v1/device/bind` | A | [handleDeviceBind](<../agentlite-server/api.go#L281>) → BindDevice | fingerprint/设备信息 → device_id/created |
| S06 | POST `/api/v1/sync/push` | D | [handlePush](<../agentlite-server/api.go#L317>) → ValidOrigin/Append/Touch | tasks/memories/status → accepted/duplicates/rejected/server_seq |
| S07 | GET `/api/v1/sync/pull` | D | [handlePull](<../agentlite-server/api.go#L358>) → Pull/Config | since/limit → records/server_seq/has_more/config |
| S08 | GET `/api/v1/config` | A | [handleConfig](<../agentlite-server/api.go#L379>) → Config | 仅配置快照 |
| S09 | GET `/api/v1/packs` | A | [handlePacks](<../agentlite-server/api.go#L389>) → Packs | 套餐/pay_methods；online_pay=false |
| S10 | POST `/api/v1/orders` | A | [handleCreateOrder](<../agentlite-server/api.go#L402>) → CreateOrder | pack_id → order |
| S11 | GET `/api/v1/orders` | A | [handleListOrders](<../agentlite-server/api.go#L421>) → ListOrders | orders/quota |
| S12 | POST `/api/v1/orders/cancel` | A | [handleCancelOrder](<../agentlite-server/api.go#L432>) → CancelOrder | order_id → order |
| S13 | POST `/api/v1/orders/pay` | A，限流 | [handlePayOrder](<../agentlite-server/api.go#L454>) → PayOrderWithCode → Redeem | order_id/code → order/quota |
| S14 | POST `/api/v1/device/command` | D | [handleCreateCommand](<../agentlite-server/api.go#L495>) → CreateCommand | to_device/task → command；定向下发 |
| S15 | GET `/api/v1/device/commands` | D | [handleListCommands](<../agentlite-server/api.go#L524>) → PendingFor | 本机待执行指令 |
| S16 | POST `/api/v1/device/command/ack` | D，目标设备 | [handleAckCommand](<../agentlite-server/api.go#L540>) → AckCommand | command_id/status/detail → command |
| S17 | GET `/api/v1/device/command` | A | [handleGetCommand](<../agentlite-server/api.go#L564>) → GetCommand | id → command；终态保留存在缺陷 |
| S18 | POST `/api/v1/lan/rendezvous` | D | [handleRendezvous](<../agentlite-server/api.go#L585>) → RecordLANPresence/LANPeers | lan_ips/port → 邻居；不搬运记录 |
| S19 | POST `/api/v1/redeem` | A | [handleRedeem](<../agentlite-server/api.go#L615>) → Redeem | code → 入账与余额 |
| S20 | POST `/api/v1/admin/codes` | M | [handleAdminCreateCodes](<../agentlite-server/api.go#L650>) → CreateCodes | 发码参数 → 兑换码 |
| S21 | POST `/v1/chat/completions` | A | [handleChatCompletions](<../agentlite-server/gateway.go#L96>) → 上游请求/chargeQuota | OpenAI兼容体 → JSON/SSE、用量计费 |
| S22 | GET `/v1/models` | A | [handleModels](<../agentlite-server/gateway.go#L216>) | 上游模型列表 |

存储所有权：账号和认证归[account](<../agentlite-server/account.go>)；设备/账号记录流/配置归[Store](<../agentlite-server/store.go>)；额度和兑换归[quota](<../agentlite-server/quota.go>)；订单状态归[order](<../agentlite-server/order.go>)；指令状态归[command](<../agentlite-server/command.go>)。这些是同一进程内职责分区，不是五跳 RPC。

**保留还是合并**：S07 和 S08 分别服务“记录+配置”“只取配置”，已有合理消费差异；S13 和 S19 共享 Redeem，但前者还有订单归属、过期和支付审计，不能仅凭都加额度认定等价。最小化应合并共同实现，而非丢弃业务语义。

## 4. Android 主 App 功能 → 唯一实现

### 4.1 功能入口与依赖方向

| 功能/入口 | 当前实现所有者 | 状态与收敛意见 |
|---|---|---|
| 文字建任务 | [MainActivity](<../agent-lite/src/com/dsh/agentlite/MainActivity.java#L438>) → AgentEngine.start | 已接；多个UI入口共用引擎 |
| 开始/停止/接管/恢复/补充/回答 | [AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L242-L337>) 的 start/stop/requestTakeover/requestResume/submitSupplement，answerAsk | 执行状态归引擎；UI只发意图 |
| 语音输入 | [VoiceRecorder](<../agent-lite/src/com/dsh/agentlite/VoiceRecorder.java>) → [AsrRouter.transcribe](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java#L42>) | 已接；ASR重复降级见 F05 |
| 本地ASR/模型准备 | [WhisperAsr](<../agent-lite/src/com/dsh/agentlite/WhisperAsr.java>)，AsrRouter.warmUp/localReady | 平台计算适配；不与远端请求混为同一实现 |
| 任务模型与付费来源选择 | [CredentialRoute.resolve](<../agent-lite/src/com/dsh/agentlite/CredentialRoute.java#L33-L55>) → [LlmClient.forPrefs](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L194-L200>) | 已统一主任务预检/请求选择；明确选择后不另换付费来源 |
| 推理/取消/用量 | [LlmClient.chat/cancel](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L162-L325>) | 已接；取消和长超时属LLM职责，不塞进普通账号请求 |
| 可选规划 | [planTask](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2037-L2082>) → TextNormalizer/SemanticTranslator → planner.chat | 额外一次模型请求，模型配置为空则跳过；需以减少执行步数衡量价值 |
| 屏幕感知/节点/截图/手势/文字 | [AgentA11yService](<../agent-lite/src/com/dsh/agentlite/AgentA11yService.java>)；[NodeInfo](<../agent-lite/src/com/dsh/agentlite/NodeInfo.java>) | 真实系统边界；RootShell是可选增强 |
| 动作解析/登记/可选参数校验 | [ActionParser](<../agent-lite/src/com/dsh/agentlite/ActionParser.java>) → [ActionSchema](<../agent-lite/src/com/dsh/agentlite/ActionSchema.java>) → [ToolRegistry](<../agent-lite/src/com/dsh/agentlite/tools/ToolRegistry.java>) | Registry已接但默认开关关闭；不是未实现路由器 |
| 弹窗/终态判定 | [AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L935-L995>) + PopupGuard/SensitiveGuard | 规则与执行仍由引擎汇合；不新增第二套动作执行器 |
| 结果/进度展示 | [OverlayController](<../agent-lite/src/com/dsh/agentlite/OverlayController.java>)、[UiText](<../agent-lite/src/com/dsh/agentlite/UiText.java>)、通知 | 展示适配不等于重复执行业务 |
| 成功流程复用 | [FlowMemory](<../agent-lite/src/com/dsh/agentlite/FlowMemory.java#L97-L196>) + [FlowPlan](<../agent-lite/src/com/dsh/agentlite/FlowPlan.java>) → replayFlow → exec | 规则与磁盘分离有可测试价值；只存本机流程，不上云 |
| 显式事实记忆 | [MemoryStore.add/list/delete/clear/promptBlock](<../agent-lite/src/com/dsh/agentlite/MemoryStore.java#L33-L109>) | 与流程记忆的数据语义不同，保留区分 |
| 任务历史 | [TaskHistory.append/list/rawLines/clear](<../agent-lite/src/com/dsh/agentlite/TaskHistory.java#L36-L103>) | JSONL归档；不应被当作远程指令结果总线 |
| 注册/登录/绑定/档案/兑换/订单 | [AccountClient](<../agent-lite/src/com/dsh/agentlite/AccountClient.java#L126-L376>) | register/login/bindDevice/me/redeem/shop/createOrder/orders/payOrder/cancelOrder；请求复用SyncClient.request |
| 增量上行与配置 | [SyncClient](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L433-L563>) | onTaskFinished/onMemoryAdded/syncNow合流；当前下行只应用配置 |
| LAN密钥、监听、交换、转发 | [LanSync](<../agent-lite/src/com/dsh/agentlite/LanSync.java>) + [tryLanFirst](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L282-L354>) | POST `/lan/v1/sync`；默认18444；数据直连、邻居发现经云 |
| 远程设备列表/下发/轮询/回报 | [RemoteCommand](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java>) | 独立HTTP实现应收敛；结果须绑定run/command ID |
| 登录态/偏好/Token/设备形态 | [Prefs](<../agent-lite/src/com/dsh/agentlite/Prefs.java>)、[TokenStore](<../agent-lite/src/com/dsh/agentlite/TokenStore.java>)、[DisplaySpec](<../agent-lite/src/com/dsh/agentlite/DisplaySpec.java>) | 持久化与平台信息各有不同责任 |
| 手机/手表设置、账号页、购买页 | [Manifest注册](<../agent-lite/AndroidManifest.xml#L46-L83>)；[PurchaseActivity](<../agent-lite/src/com/dsh/agentlite/PurchaseActivity.java#L121-L170>) | 手机/手表可保留布局差异，规则共用；购买页现为官方充值/粘贴Key，旧订单区隐藏 |
| 保活/服务连接/辅助语音入口 | [Manifest](<../agent-lite/AndroidManifest.xml#L72-L119>) 的 AssistActivity/AgentA11yService/GuardService/A11yJobService/KeepAliveService | Android组件生命周期边界，不按组件数直接合并 |

### 4.2 全部模型动作：22 执行 + 3 控制，共25个

词表唯一清单见[ActionSchema:36–45](<../agent-lite/src/com/dsh/agentlite/ActionSchema.java#L36-L45>)。下表分组仅压缩展示，组内每个动作都列出实现。

| 动作与核心输入 | 当前唯一执行分支 | 最小化裁决 |
|---|---|---|
| `tap` index 或 px/py 或 x/y | [exec:2111](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2111>) → tapNode/resolvePoint/tap | 一个点击入口；节点定位与坐标定位是输入形式，不另建点击服务 |
| `longpress` index/坐标/dur | [exec:2172](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2172>) → longPress | 保留 |
| `swipe` 起终点/dur | [exec:2188](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2188>) → swipe | 保留 |
| `scroll` direction | [exec:2208](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2208>) → swipe | 仅up/down分支；down的坐标方向与注释相反，需以契约测试裁定 |
| `key` code | [exec:2218](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2218>) → goHome/goRecents/goBack | 当前未知code均返回；应只允许明确枚举 |
| `edge_back` side | [exec:2227](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2227>) → 边缘swipe | 是物理手势，不与系统返回当作完全等价 |
| `back` / `home` | [exec:2239–2242](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2239-L2242>) | 与key语义重叠，但错误检查/lastApp处理不同；归一化后只留一个执行实现 |
| `text` text/append | [exec:2243](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2243>) → setText/IME/剪贴板 | 当前多级通道；选定明确能力路径，剪贴板待粘贴别算已输入 |
| `app` package | [exec:2258](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2258>) → startApp | 名称解析集中此处 |
| `setting` page | [exec:2291](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2291>) → Intent | 设置页与设置值变更是不同业务 |
| `open_url` url | [exec:2304](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2304>) → ACTION_VIEW | URL打开统一入口 |
| `open_api` target/intent/param | [exec:2309](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2309-L2390>) → DeepLink/Intent | 应只做目标到Intent映射；别把搜索失败降成打开App仍称成功 |
| `list_apps` | [exec:2179](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2179>) → installedApps/pendingNote | 查询功能，不等于启动 |
| `remote` device/task | [exec:2129](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2129>) → RemoteCommand.send | “下发成功”与“执行完成”分开 |
| `wifi` | [exec:2392](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2392>) → Wi-Fi面板 | 当前是打开面板，不是直接开关 |
| `bluetooth` | [exec:2396](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2396>) → 蓝牙设置 | 当前是设置页 |
| `brightness` level | [exec:2400](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2400>) → Settings.System | 值变更 |
| `volume` dir | [exec:2414](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2414>) → AudioManager | 值变更 |
| `statusbar` mode | [exec:2423](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2423>) → notifications/quickSettings | 面板 |
| `wake` | [exec:2429](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2429>) → WakeLock | 点亮屏幕 |
| `wait` ms | [exec:2437](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2437>) | 200–8000ms |
| `ask` question | [runLoop:883](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L883>) → askUser | 控制动作，不进入exec |
| `done` summary | [runLoop:935](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L935>) → judgeDone/history | 证据不足两次后记“不确定”；已不是一律成功 |
| `fail` reason | [runLoop:991](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L991>) → failTask | 统一失败出口 |

### 4.3 真实接线，避免误删

- [ToolRegistry校验](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L1001-L1019>)已接入执行前；[FeatureFlags](<../agent-lite/src/com/dsh/agentlite/FeatureFlags.java#L38-L51>)默认关闭工具路由，不等于没有调用。
- [contracts模型](<../agent-lite/src/com/dsh/agentlite/contracts/ScreenSnapshot.java>)等属于预备数据模型，当前引擎未通过它们搬运屏幕。可讨论移出主构建或停止演进，**不计入当前热路径转码次数**。
- [LlmClient.fallback](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L202-L209>)及[post备用分支](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L458-L504>)仍在源码，但主任务工厂不登记备用凭据。可删除闲置备用配置及分支；当前主任务没有自动切换付费来源的接线证据。
- [BailianClient](<../agent-lite/src/com/dsh/agentlite/BailianClient.java>)是Key管理面，不是另一份LLM推理器。购买页仍有AkStore字段初始化；清理时按真实调用和产品入口逐项删，不沿用旧“整包约933行死代码”的推断。

## 5. iOS：Core、App、CLI 分开统计

### 5.1 云端接口映射

| 功能 | 入口 → 唯一实现 | HTTP操作/状态 |
|---|---|---|
| 注册/登录 | AgentLiteClient.register/login → [AccountService](<../ios/Sources/AgentLiteCore/AccountService.swift>) | S02/S03；App+CLI |
| 绑定设备 | AccountService登录后绑定 | S05；App+CLI |
| 恢复/退出 | [bootstrap/logout](<../ios/Sources/AgentLiteCore/AgentLiteClient.swift#L73-L100>) → CredentialStore/HTTP状态 | 本地操作，没有logout服务端路由 |
| 档案/邻居 | [profile/peerDevices](<../ios/Sources/AgentLiteCore/AccountService.swift#L141-L149>) | S04；App+CLI；两方法当前各发请求 |
| 下发任务 | [sendRemoteTask](<../ios/Sources/AgentLiteCore/AgentLiteClient.swift#L105>) → CommandService.send | S14；App+CLI |
| 领取 | [CommandService.pending](<../ios/Sources/AgentLiteCore/CommandService.swift#L59-L67>) | S15；CommandInbox消费；不是sync/pull |
| 回报 | [CommandService.ack](<../ios/Sources/AgentLiteCore/CommandService.swift#L73-L81>) | S16；CommandInbox消费 |
| 单次/等待结果 | [result/waitForResult](<../ios/Sources/AgentLiteCore/CommandService.swift#L96-L146>) | S17；CLI watch使用；终态删除导致404 |
| 记录上行 | [SyncService.push](<../ios/Sources/AgentLiteCore/SyncService.swift#L48-L60>) | S06；公开Core接口，App常规链未接 |
| 记录下行/排空 | [SyncService.pull/drain](<../ios/Sources/AgentLiteCore/SyncService.swift#L72-L96>) | S07；公开Core接口，App常规链未接；分页问题见F01 |
| 只读配置 | [serverConfig](<../ios/Sources/AgentLiteCore/SyncService.swift#L100-L102>) | S08；公开Core接口，App常规链未接 |

合计 **11 个Core HTTP操作**（注册、登录、档案、绑定、push、pull、config、下发、领取、ack、结果）。不宣称App使用了全部11个。

### 5.2 本地与工具入口

| 功能 | 实现/证据 | 边界 |
|---|---|---|
| 前台/后台收取任务 | [CommandInbox](<../ios/Sources/AgentLiteCore/CommandInbox.swift>) → CommandExecutor → LocalActionProvider；[BackgroundRefresh](<../ios/App/AgentLiteIOS/BackgroundRefresh.swift>) | iOS系统调度，不承诺Android式持续20秒轮询 |
| 设置/固定App/快捷指令 | [IOSLocalActionProvider.perform](<../ios/App/AgentLiteIOS/LocalActions.swift#L27-L52>) | URL打开结果，非跨App点击引擎；调起快捷指令不等于其内容已完成 |
| HTTP/TLS | [HTTPClient](<../ios/Sources/AgentLiteCore/HTTPClient.swift>) | Core共用；不是每个Service一套HTTP |
| 凭据 | [KeychainStore](<../ios/Sources/AgentLiteCore/KeychainStore.swift>) / [FileCredentialStore](<../ios/Sources/AgentLiteCore/FileCredentialStore.swift>) | iOS Keychain与CLI文件分别适配平台；不为少一个类强行合并 |
| LAN发起 | AgentLiteClient.syncWithLANPeer → [LANPeer](<../ios/Sources/AgentLiteCore/LANPeer.swift>) | CLI `lan-sync`已接，App未接 |
| LAN验包/回包 | [CLI lan-accept/lan-respond](<../ios/Sources/AgentLiteCLI/main.swift#L242-L269>) → acceptInbound/respondInbound | 有真实工具入口；不是常驻iOS LAN服务 |
| CLI其它命令 | [main switch](<../ios/Sources/AgentLiteCLI/main.swift>) | pin/register/login/whoami/devices/send/watch/collect/help；每项调用Core能力，不另复制规则 |

[AgentLiteClient](<../ios/Sources/AgentLiteCore/AgentLiteClient.swift#L52-L69>)主要组装依赖；[AppModel](<../ios/App/AgentLiteIOS/AppModel.swift>)负责主线程可观察状态。这里不是“层越少越好”的机械删除对象，真正该删的是同一事件重复发请求与无消费者的凭据副本，而非actor/MainActor平台边界。

## 6. 独立执行栈清单

### 6.1 agent-client

[AgentEngine](<../agent-client/src/com/dsh/agent/AgentEngine.java#L534-L753>)含19个执行动作：`tap`、`longpress`、`swipe`、`scroll`、`key`、`edge_back`、`back`、`home`、`text`、`app`、`setting`、`open_url`、`wifi`、`bluetooth`、`brightness`、`volume`、`statusbar`、`wake`、`wait`。由一个exec分发；循环中的终态不计作这19个执行case。

| 功能 | 唯一实现 | 与主App关系 |
|---|---|---|
| UI任务、语音、进度 | [MainActivity](<../agent-client/src/com/dsh/agent/MainActivity.java>) → AgentEngine；[VoiceRecorder](<../agent-client/src/com/dsh/agent/VoiceRecorder.java>) | 相似UI/执行能力的另一份产品实现 |
| 感知与执行 | [AgentA11yService](<../agent-client/src/com/dsh/agent/AgentA11yService.java>) + NodeInfo | 与agent-lite同语言同平台重叠，较有收敛价值 |
| 模型与解析 | [LlmClient](<../agent-client/src/com/dsh/agent/LlmClient.java>)、[ActionParser](<../agent-client/src/com/dsh/agent/ActionParser.java>)、[ActionValidator](<../agent-client/src/com/dsh/agent/ActionValidator.java>) | 独立策略，不能靠复制补丁长期保持一致 |
| 可选虚拟屏 | [SandboxController](<../agent-client/src/com/dsh/agent/SandboxController.java#L52-L256>) | create/stop/daemonAlive；launchApp/launchAction/launchUrl；hostsPackage/reclaimTask；frame/tap/longPress/swipe/key |

最小建议：主App承接仍需保留的唯一能力后冻结旧执行栈；先核对发布/构建消费者和虚拟屏需求，勿直接删目录或额外造一套通用跨端框架。

### 6.2 agentd：Go API 与 APK桥分开

[Go注册](<../agentd-go/main.go#L390-L406>)：7个`/api/*`路径，8个方法操作；加根页面共8个注册项。根页面不经过API guard；API经过令牌/方法/Host/Origin/Content-Type检查。

| HTTP操作 | handler/唯一实现 | 输入输出 |
|---|---|---|
| `/` 页面 | [handleIndex](<../agentd-go/main.go>) | WebUI；非账号服务 |
| GET `/api/config` | handleGetConfig | 当前配置 |
| POST `/api/config` | handleSetConfig → config | 更新配置 |
| POST `/api/task` | handleTask → Runner.Run | 任务 → 异步运行 |
| POST `/api/stop` | handleStop | 停止当前任务 |
| GET `/api/status` | handleStatus | 运行状态 |
| GET `/api/screen` | handleScreen | 屏幕图像 |
| POST `/api/test` | handleTest → device | 实际感知/截图自检 |
| POST `/api/asr` | handleAsr | 音频 → 文本 |
| APK GET `/nodes` | [AgentA11yService.nodesJson](<../agentd-apk/src/com/dsh/agentd/AgentA11yService.java#L182>) | 无障碍节点JSON |
| APK POST `/settext` | [AgentA11yService请求分发](<../agentd-apk/src/com/dsh/agentd/AgentA11yService.java#L184>) | 文本注入 |

执行映射见[Runner.exec](<../agentd-go/internal/agent/agent.go#L154-L234>)：tap→tapAt；longpress→device.LongPress；swipe→device.Swipe；scroll→r.scroll；key→device.Key；text→device.TypeText；app→device.StartApp；setting→device.OpenSettingsPage；open_url→device.OpenURL；statusbar/volume/brightness/wifi/bluetooth/wake各自调用同名设备能力；wait本地等待；back/home→device.Back/Home。done在Run循环截获，exec中同名分支不构成第二业务执行路径。

实际感知优先[APK服务](<../agentd-go/internal/agent/agent.go#L386-L410>)，失败才走uiautomator XML。Go输入动作没有经过APK tap/swipe接口，因为APK没有这两个接口。截图流为[PNG读取解码→缩图→JPEG→Base64](<../agentd-go/internal/vision/vision.go#L22-L36>)，不是“同进程两次PNG编码”。

### 6.3 Harmony CLI

[主循环](<../tools/harmony-agent/harmony_agent.py#L113>)统一调用[hdc适配](<../tools/harmony-agent/hdc.py>)，模型动作如下：

| 动作 | 唯一实现 |
|---|---|
| tap(index或比例坐标) | [run:166](<../tools/harmony-agent/harmony_agent.py#L166>) → hdc.click |
| swipe | [run:184](<../tools/harmony-agent/harmony_agent.py#L184>) → hdc.swipe |
| text | [run:189](<../tools/harmony-agent/harmony_agent.py#L189>) → hdc.input_text |
| back / home | [run:192–197](<../tools/harmony-agent/harmony_agent.py#L192-L197>) → hdc.back/home |
| app(bundle, ability) | [run:198](<../tools/harmony-agent/harmony_agent.py#L198>) → hdc.start_app |
| wait | [run:201](<../tools/harmony-agent/harmony_agent.py#L201>) → sleep |
| done / fail | [run:204–209](<../tools/harmony-agent/harmony_agent.py#L204-L209>) → 返回终态 |

辅助能力：collect_nodes/dump_layout/walk_nodes、screen_size、screenshot、list_targets归hdc；[probe](<../tools/harmony-agent/probe.py>)为探测入口；[fake_hdc](<../tools/harmony-agent/fake_hdc.py>)为测试替身，不是另一个生产驱动。

## 7. 高证据问题与最短修复

优先级：P0=先处理数据/状态正确性；P1=影响核心交互或明显重复成本；P2=维护性清理。优先级是本轮建议，不代表线上事故已发生。

### F01 · P0 · 分页游标越过未消费记录

- [Store.Pull](<../agentlite-server/store.go#L413-L453>)返回最多limit条，但第二返回值是账号流最高序号last；[handlePull](<../agentlite-server/api.go#L370-L375>)将其放进server_seq，同时提供has_more。
- [iOS pull](<../ios/Sources/AgentLiteCore/SyncService.swift#L72-L82>)立即saveCursor(res.serverSeq)。例：流中1…1000，limit=200；第一次收1…200后游标变1000，下一次取不到201…1000。drain循环并不能补救。
- Android [pullConfig](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L536-L563>)同样提前推进server_seq，只消费config，records/has_more被丢弃。
- **最短修复**：分页的“已交付游标”与“服务端高水位”分开；消费方处理成功后提交最后已处理记录seq。若Android当前只要配置，直接调用现有S08，别下载记录也别改记录游标。若产品要跨端记录，则实现真实入库后再推进，不两种路径同时保留。
- 验收：1001条、limit=200，多页无遗漏/重复提交；中途消费失败游标不前移；切账号不沿用另一账号游标。

### F02 · P1 · 一批同步拆4个push，放大网络与全表写

- [pushHistory/pushPeerRecords/pushMemories/pushStatus](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L433-L531>)分别调用同一S06。满批且均有数据时为4次push，再1次pull；空批次跳过，可选rendezvous及多IP P2P尝试另算，**不是固定6次**。
- 服务端[handlePush](<../agentlite-server/api.go#L340-L348>)每个成功Append请求调用一次Touch，不是每条记录Touch。[Touch](<../agentlite-server/store.go#L289-L302>)重写全设备表。
- **最短修复**：一次组装tasks（本机+转发）、memories、status；保持origin_device_id；一份响应提交各批次游标。满批场景4次push降到1次，Touch也从4次降到1次。服务端已有组合载荷，无须新建batch接口。
- 注意当前按批次末尾推进游标，应对rejected制定明确报错/隔离规则，别把部分失败也记为全部送达。
- 验收：同一批响应失败时游标不提交，重传幂等；LAN成功只跳本机任务云push，不丢记忆/状态。

### F03 · P0 · 远程指令结果经过“历史文件中转”且回执不可查询

- [RemoteCommand](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java#L235-L242>)发现引擎空闲便读取最新历史；[lastTaskResult](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java#L287-L296>)没有command_id/run_id匹配。中间插入本机任务或落档失败时可错配。
- [ack](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java#L299-L308>)吞网络异常，调用方仍清掉指令ID。服务端[终态ack](<../agentlite-server/command.go#L189-L207>)立即删除条目，S17此后404。
- **最短修复**：引擎直接产出带runId/commandId的结果，历史只是结果的持久化视图；ack成功再完成交付状态。服务端同一command对象保留可查询终态并按明确TTL清理，重复同结果ack幂等。
- 不另加“把回执再推到sync/pull”的旁路：那会把本可直接查询的一个对象拆成两个协议、两个状态所有者。
- 验收：两条任务相邻完成结果不串；ack失败保留待交付状态但不重执行任务；终态结果可按原ID读取。

### F04 · P1 · iOS 一次刷新拉同一个档案两遍

- [AppModel.refreshProfile](<../ios/App/AgentLiteIOS/AppModel.swift#L93-L99>)先profile再peerDevices；后者[内部又profile](<../ios/Sources/AgentLiteCore/AccountService.swift#L147-L149>)。
- **最短修复**：一次profile拿额度和devices，用同份结果过滤本机ID。2次S04→1次，去掉一次网络往返并保证显示来自同一个快照；不加缓存框架。

### F05 · P1 · ASR 冷却未阻止重复访问同一端点

- [AsrRouter](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java#L42-L87>)实际顺序是配置云端优先→本地→最终transcribe(asrBaseUrl)。云失败后本地也未产出文本，最后又传原cloudBase；冷却期亦可能走到该分支。
- “5分钟冷却”和类头“离线优先”均与实际流不一致。同次语音可多交一次相同请求、多等一轮超时。
- **最短修复**：显式ASR模式（本地或指定远端）只执行一次选定实现，失败直接返回该模式错误；按本次精简原则删除自动多级降级，而非再加熔断层。产品若将来要切模式，由用户主动选。
- 验收：一次转写最多调用一次已选服务；本地模式0网络；失败/空识别不自动换来源。

### F06 · P1 · 日志包装截断SSE能力

- [loggingWriter](<../agentlite-server/api.go#L85-L111>)只实现Write/WriteHeader，嵌入的接口不包含Flush/Unwrap。
- [网关](<../agentlite-server/gateway.go#L118-L120>)忽略SetWriteDeadline错误；[Flusher断言](<../agentlite-server/gateway.go#L191-L209>)在这层包装上失败。因此逐行flush分支不执行，15分钟写期限未兑现，仍有[服务器60秒WriteTimeout](<../agentlite-server/main.go#L136-L142>)。
- **最短修复**：包装透传Unwrap，并让网关统一用ResponseController处理Flush/期限及错误；或同时正确透传Flush。只加Unwrap但保留直接Flusher断言还不够。
- 验收：通过真实wrap后的httptest.Server分段输出，客户端在上游结束前收到首段；验证期限设置成功。尚未执行此动态测试。

### F07 · P0 · 兑换/订单分散事务导致状态可分裂

- [Redeem](<../agentlite-server/quota.go#L173-L191>)先改内存，再依次落codes/accounts/ledger。中途失败已有部分写入，锁只保证进程内互斥。
- [PayOrderWithCode](<../agentlite-server/order.go#L252-L292>)检查pending后解订单锁→Redeem→再加锁；期间取消/另一支付可使“已核销入账但订单closed”。
- **最短方向**：一个账务事务内完成码核销、余额、流水与可选订单终态。建议用现有Store内部一份本地事务存储承接，不新增通用存储适配框架。实施前先做并发/故障测试和数据迁移方案。
- 保留直接兑换与订单支付两种业务入口时，二者调用同一个事务内核；若产品正式取消订单，则连客户端/服务端/历史读取一起退役，不仅删某个handler。

### F08 · P1 · 全表写、全流扫描放在公共锁和高频路径

- [Append](<../agentlite-server/store.go#L340-L409>)在全Store锁内写记录并fsync；[Touch](<../agentlite-server/store.go#L289-L302>)每次重写设备表；[ConsumeQuota](<../agentlite-server/quota.go#L195-L241>)重写账号表；[Pull](<../agentlite-server/store.go#L430-L448>)从文件头逐行解码直到目标页。
- D设备/A账号/N记录时：一次Touch约O(D)，一次扣费约O(A)，高游标Pull仍扫描前缀O(N)。不同账号可被同一写锁串行化。这是静态成本，不是已量化的性能瓶颈排名。
- **最短顺序**：先F02减请求、F01别取无用记录；再让事务存储支持单行更新和(account_id,seq)索引。避免“异步重写+补偿队列+第二缓存”把一致性再拆复杂。
- [health](<../agentlite-server/api.go#L180-L194>)只需要计数却取全部快照，改成Store中一次计数即可，无需独立统计服务。

### F09 · P2 · JSON反复编码存在，但不是固定“4→1”

- [网关](<../agentlite-server/gateway.go#L127-L142>)先probe全body，再根据stream执行ensureIncludeUsage，随后adaptRequest再解析。非流式正常路径两次完整Unmarshal；流式三次，stream_options还可额外局部解析。并非四次都解析完整请求。
- **最短修复**：入口解析一次根对象，原地修改stream_options/model/thinking，结束序列化一次；未知字段用RawMessage保留。用同一组含图/流式/非流式测试保证透明转发。
- Android [chatOnce](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L325-L347>)为检测image_url先messages.toString，随后body.toString又包含完整messages。含Base64图像时多一次大字符串分配；直接遍历消息字段判断有图即可。

### F10 · P1 · 账号网络底座放在同步模块里，指令又复制一份

- [AccountClient](<../agent-lite/src/com/dsh/agentlite/AccountClient.java>)依赖SyncClient.request；[RemoteCommand.request](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java#L317-L344>)另写连接/鉴权/读流，异常路径未统一finally断开。
- [SyncClient.request](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L693-L766>)每次HTTPS请求重建证书工厂；主模型另有TlsPin策略。
- **最短修复**：把已有普通JSON请求函数移到唯一的账号服务HTTP底座，Account/Sync/Command直接用；认证、pin、错误体、连接释放同源。不要再套Transport→Adapter→Client→Service。
- LLM保留长请求取消/流式/用量处理，LAN保留信封协议，ASR保留音频协议；只复用真正相同的连接基础，不统一成万能网络接口。

### F11 · P2 · LAN每条入库扫全文件，返回上限只约束一部分

- [appendPeerRecord](<../agent-lite/src/com/dsh/agentlite/LanSync.java#L463-L494>)每条记录重新读peer文件去重；M条批次、N条已存时，最坏约O(MN+M²)行比较。
- [collectRecordsForPeer](<../agent-lite/src/com/dsh/agentlite/LanSync.java#L511-L534>)截取本机历史到limit，但将全部peer记录追加，故不是最终总量500条上限。
- **最短修复**：一批一次读取ID集合并合并写入；总回包在合并后按协议分页/限量，不给每条记录再加Repository；进一步与本地记录的稳定ID/游标统一。

### F12 · P2 · 相同动作的别名却有不同失败语义

- [key](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2218-L2225>)检查系统动作返回值；[back/home](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2239-L2242>)忽略返回值，home还额外清lastApp。
- **最短修复**：解析边界把同义输入变成规范动作，执行仅一份，统一错误检查与状态更新；迁移已有流程记忆中的动作名并测试，不直接删词表导致旧记录失效。
- [open_api](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L2370-L2390>)的目标语义降级同理：搜索/导航失败应明确失败，不以“打开应用”冒充目标动作成功。

### F13 · P2 · agentd两处截图采样可能做重复工作

- [动作后](<../agentd-go/internal/agent/agent.go#L472>)截一次屏；[下一轮视觉感知](<../agentd-go/internal/agent/agent.go#L509-L515>)又截屏并转图。视觉开启、连续成功步骤时有两个采样点。
- **最短修复**：先明确Web预览和模型的帧消费时机；一次新鲜感知帧服务两者，或删除无人消费的动作后截图。不要直接复用过期帧，屏幕在两时点可能变化。
- 不是删除PNG→JPEG压缩：那次格式转换用于减少远端图像体积，有明确价值。

### F14 · P0 · 设备所有权不是可复用的全局指纹别名

- [BindDevice](<../agentlite-server/store.go#L233-L253>)按全局fingerprint取旧Device后直接覆盖AccountID；没有旧归属证明，旧LastStatus/LAN字段也未在此清理。
- **最短修复**：普通bind限账号域幂等；跨账号迁移作为明确有验证的操作，并清理账号相关状态。否则身份对象混入“查找+接管+数据继承”三种语义。
- 验收：账号B提交账号A设备的相同指纹，不获得旧设备对象和历史状态；正常重装/换账号行为有明确契约。

## 8. 数据搬运账本：哪里值得删

| 当前链路 | 发生的重复/失配 | 目标最短链路 |
|---|---|---|
| task_history全读→JSON数组→4类各push→各Touch | 同批多次HTTP/持久化；本机任务在LAN失败后还可能重建 | 已组装批次→一次push→一次游标提交 |
| sync/pull→records+config→Android只保留config | 服务端扫描、网络、客户端解析全浪费 | 仅配置调用现有config；记录同步另按真实需求消费 |
| profile→me；peerDevices→profile→me | 同一刷新2次GET、不同快照 | 一次me→额度+过滤设备 |
| 引擎终态→历史文件→读最新记录→猜command结果 | 数据本已在内存却经无关联文件取回 | 带ID结果→记录历史，同时ack |
| request bytes→probe→stream改写→adapt改写→bytes | 重复解析和序列化 | 一个边界解析与一次输出编码 |
| NodeInfo→提示词→LLM动作JSON→解析→系统动作 | 跨模型/系统边界的必要表示变化 | 保留；不再叠未接线DTO作为必经层 |
| PNG→缩图JPEG→Base64→模型 | 网络协议与载荷压缩，非单纯包装 | 保留一次转换；同帧勿多次截取/编码 |
| 主源码→unified副本→构建 | 发布时复制，不是运行时开销 | 保留单一源与同步校验；勿双写业务 |

## 9. 建议目标结构与实施顺序

```text
Android：UI/后台入口 → AgentEngine（任务唯一状态） → 平台操作
                        ├→ LlmClient（已选模型通道）
                        └→ 带ID任务结果（历史/远程回报分别消费）
账号业务：Account / Sync / Command → 一份普通HTTP底座 → server
服务端：HTTP handler（鉴权、解码） → 既有Store业务操作（事务、索引）
iOS：App主线程状态 → Core能力 → HTTP或系统动作
```

不需要为上图每个名词都新增一个类。**先移动/删除已有实现，再决定是否需要命名边界。**

| 批次 | 只做的事 | 验收，不以“少了多少类”验收 |
|---|---|---|
| 1 正确性 | F01分页、F03结果归属/查询、F07事务、F14归属 | 多页完整；任务结果按ID；并发核销一次；跨账号不继承设备状态 |
| 2 请求减法 | F02合批、F04档案单请求、F05 ASR单模式、F06 SSE透传 | 满批push4→1；刷新GET2→1；ASR一次；首段及时可见 |
| 3 存储/表示 | F08索引单行写、F09一次解析、F10普通HTTP归位、F11批量去重 | 锁占用/分配/扫描/请求数有基准；协议不丢字段 |
| 4 代码减法 | F12别名归一、F13截图消费合一、旧备用/预备模块/旧客户端裁剪 | 构建消费者清点；动作和历史兼容测试；无平行备用实现 |

性能测量只围绕这些指标：每次用户事件的请求数/字节数；每同步批次写次数；Pull读取行数；互斥锁持有时长；JSON分配字节；单步截图次数；模型请求数与token。不要先承诺百分比提速。

## 10. 对旧审计的明确修正

| 旧表述 | 本次裁定 |
|---|---|
| 服务端23业务接口 | 22显式方法操作 + 1未知路径处理项，20业务路径 |
| iOS CommandInbox走sync/pull | 走device/commands与command/ack |
| iOS LAN没有生产调用所以可删 | CLI三种LAN入口存在；App未接不等于接口无消费者 |
| Registry死开关/未接 | 已有受默认关闭开关控制的真实校验接线 |
| contracts造成当前节点热路径多次转码 | 未在当前引擎接线，故不产生这笔运行成本 |
| 同步固定6请求，每条记录Touch | 请求数随批次和LAN变化；每个成功Append请求一次Touch |
| 网关固定4次完整解码→1 | 非流式2次完整、流式3次完整，可另有stream_options局部处理 |
| orders/pay与redeem纯重复 | 核销核心已共享，订单还有生命周期；需要修事务而非机械删入口 |
| back/home未注册、scroll四向 | back/home已注册；scroll仅up/down两分支且需核验方向 |
| agentd节点感知同时两套转换/其中一套死代码 | 服务优先、XML失败后使用；均接线 |
| agentd两次PNG编解码 | PNG读解码→缩放→JPEG编码；重复采样另行讨论 |
| 单调用者就是臭抽象 | 看是否承担平台、事务、状态所有权或可测试纯逻辑职责 |
| 约2600+行可安全删除 | 未做构建/发布/外部消费者证明，本轮撤回该删除规模结论 |

## 11. 交付与验证边界

- 已按7个子项目完成对外功能边界清单，并区分生产App、CLI、库公开接口、预备模型和构建镜像。
- 已逐项核读本文关键调用链；两个委派审计只返回阶段性消息后失败，本报告不把其未交付部分算作独立复核通过。
- 业务源码保持原状；旧工作树修改不归本轮。本文建议均为待实施，不宣称缺陷已修复。
- 机械校验通过：191个引用的路径与行号有效；22个服务端显式HTTP操作和25个Android动作在本文均有覆盖。该检查验证覆盖与引用位置，不替代运行行为测试；未运行全项目make check、真机回归、Go race或性能测试。
- 所有性能收益数字仅采用确定的调用次数变化或复杂度；线程竞态、网络时间与线上影响需在后续实施时以针对性测试补证。

**最终原则：同一功能一个状态所有者、一份业务实现、一个明确失败结果。删的是重复规则和无效搬运，不是把必要边界也一并抹平。**
