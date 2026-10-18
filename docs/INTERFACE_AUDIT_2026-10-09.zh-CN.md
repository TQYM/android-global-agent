# 功能接口审计与最小架构分析（2026-10-09）

> **历史版本提示（2026-10-10）**：本报告部分路由计数、iOS 命令接线、开关状态、编解码次数及“死代码”删除结论已被源码复核纠正。请以[实际功能接口与最小架构复核](<INTERFACE_AUDIT_2026-10-10.zh-CN.md>)为当前依据；下文保留作审计历史，不直接据此删代码。

> 审计原则（用户设定）：一个接口对应一个实现；一个功能不做多种实现样式；
> 第一性原理，不冗余、不兜底；能 A/B 解决不拆 ABCD（拒绝臭抽象）；
> 检查架构摆放是否合理、数据是否在层次间反复传输导致冗余接口与性能低下。
>
> 方法：全库只读审计。服务端 8 文件逐行审读；客户端以调用面 grep 验证零引用；
> 所有"死代码"结论均经全仓 grep 验证，非猜测。

## 1. 全局拓扑：谁在跟谁说话

```
                    ┌────────────────────────────────────────┐
                    │  agentlite-server (Go, 8.133.225.105:18443)
                    │  23 条路由：账号/设备/同步/配置/订单/指令/LAN会合/兑换/LLM网关 │
                    └──────┬──────────────────┬──────────────┘
                           │ HTTPS+证书pin     │ HTTPS+证书pin
              ┌────────────┴───┐          ┌───┴────────────┐
              │ agent-lite     │◄─LAN P2P─►│ ios AgentLite  │
              │ (Android 主力) │  :18444   │ (Core+CLI+App) │
              └────────────────┘  GCM信封  └────────────────┘

  独立子系统（不连 agentlite-server，无 /api/v1 调用）：
    agent-client   —— 另一 Android 客户端（SandboxController + vd-daemon + floris-merge）
    agentd-go      —— 设备端常驻守护（Go，自带 WebUI + LLM 语义栈）
    tools/harmony-agent —— HarmonyOS 原型（Python，hdc 通道）
    agent-unified  —— 镜像层（client/ server/ 逐字节副本，sync-sources.sh --check 门禁，非真相源）
```

三个事实直接决定审计结论的量级：

1. **真正共用契约的只有两个端**（agent-lite、ios）对一个服务器。其余子项目是完全独立的执行栈。
2. **iOS 生产 App 只接线了指令链路**（CommandInbox）。同步链路（SyncService.push/drain）与 LAN 链路（LANPeer）在 iOS 生产代码零调用——意味着"LAN 优先同步"这个双端特性实际只有 Android 单端在跑。
3. **agent-unified 是镜像不是抽象**：它不减少任何接口，只是复制接口并用脚本门禁保证一致。它解决的是"多仓漂移"问题，代价是每次改动双写。

## 2. 服务端接口全表（agentlite-server，23 条）

| # | Method + Path | Handler | 调用链（一层） | 语义 |
|---|---|---|---|---|
| 1 | GET /health | api.go:180 | Store.Devices + Store.Accounts | 健康检查与计数 |
| 2 | POST /api/v1/account/register | api.go:220 | rl.allow → RegisterAccount | 注册+签发令牌（IP 限流） |
| 3 | POST /api/v1/account/login | api.go:246 | rl.allow → LoginAccount | PBKDF2 校验，多令牌 |
| 4 | GET /api/v1/account/me | api.go:265 | DevicesOfAccount | 账号信息+设备列表 |
| 5 | POST /api/v1/device/bind | api.go:281 | BindDevice + migrateLegacyRecords | fingerprint 幂等绑定 |
| 6 | POST /api/v1/sync/push | api.go:317 | ValidOrigin×N + Append + Touch | 上行同步，kind:id 幂等 |
| 7 | GET /api/v1/sync/pull | api.go:358 | Store.Pull + Store.Config | 增量拉取，附配置 |
| 8 | GET /api/v1/config | api.go:379 | Store.Config | 远程配置下发 |
| 9 | GET /api/v1/packs | api.go:389 | Store.Packs | 套餐列表 |
| 10 | POST /api/v1/orders | api.go:402 | CreateOrder | 15min TTL 订单 |
| 11 | GET /api/v1/orders | api.go:421 | ListOrders | 列表+惰性过期 |
| 12 | POST /api/v1/orders/cancel | api.go:432 | CancelOrder | 取消 pending 订单 |
| 13 | POST /api/v1/orders/pay | api.go:454 | PayOrderWithCode → Redeem | 兑换码支付订单 |
| 14 | POST /api/v1/device/command | api.go:495 | CreateCommand + auditCommand | 定向下发（10 条/分/账号） |
| 15 | GET /api/v1/device/commands | api.go:524 | PendingFor | 目标设备拉取+清过期 |
| 16 | POST /api/v1/device/command/ack | api.go:540 | AckCommand | 仅目标设备回报 |
| 17 | GET /api/v1/device/command | api.go:564 | GetCommand | 下发方查询结果 |
| 18 | POST /api/v1/lan/rendezvous | api.go:585 | RecordLANPresence + LANPeers | 上报 LAN 位置换邻居 |
| 19 | POST /api/v1/redeem | api.go:615 | Store.Redeem | 直接兑换充值 |
| 20 | POST /api/v1/admin/codes | api.go:650 | CreateCodes | 批量生成兑换码 |
| 21 | POST /v1/chat/completions | gateway.go:96 | acquireSlot → adapt → chargeQuota | LLM 网关转发+计费 |
| 22 | GET /v1/models | gateway.go:216 | 透传 | 模型列表 |
| 23 | / 其余 | main.go:132 | writeErr 404 | 未知接口兜底 |

中间件：wrap = logMW(recoverMW)；authMW / authDeviceMW / adminMW。

客户端消费面对照：
- **agent-lite 调 19 条**（health 除外：account×4、bind、sync×2、config、packs、orders×4、command×4、rendezvous、redeem；admin/codes 是管理端）。
- **ios 调 11 条**（account×4、bind、sync×2、config、command×4；packs/orders/redeem/rendezvous/admin 未接）。
- agent-client / agentd-go / harmony-agent：0 条（独立子系统）。

## 3. iOS 接口清单（AgentLiteCore）

结构：`AgentLiteClient（门面 actor）→ {AccountService, SyncService, CommandService, CommandInbox} → 共享 HTTPClient`，标准两层，无三层转发。LANPeer 为独立 P2P 通道。

全部 11 条 URL 契约与消费状态：

| 接口 | 位置 | 生产消费 |
|---|---|---|
| register / login / me / bind | AccountService.swift:35/47/142/112 | ✅ App+CLI |
| sync/push、sync/pull | SyncService.swift:55/76 | ❌ push 仅测试；pull 经 drainOnce 间接（见下） |
| sync/pull（命令通道） | CommandService.swift:62 + CommandInbox.swift:65 | ✅ 唯一生产同步路径 |
| config | SyncService.swift:100 | ❌ 零调用（连测试都没有） |
| command / commands / ack / command?id | CommandService.swift:39/62/76/99 | ✅ |
| P2P POST :18444/lan/v1/sync | LANPeer.swift:103 | ❌ 仅 CLI 入口，App 未接线 |

关键问题（详见 §7 iOS 小节）：11 项零引用死代码；PEM 解析/凭据解码/终态判断各有多份实现；`lanKeyParams` 服务端下发但客户端解码即丢弃（硬编码常量派生）——契约漂移风险。

## 4. agent-lite 接口清单（Android 主力客户端）

### 4.1 引擎动作词表（词表单一事实源 = ActionSchema.java）

词表链路：`ActionSchema.java`（SCHEMA_VERSION="2026-09-14.1" L33；EXEC_ACTIONS 23 个 L36-42 + CONTROL_ACTIONS 3 个 L45）→ 提示词文本 AgentEngine `SCHEMA` 常量 L349-427 → 执行前四重校验：ActionParser.parse L860 → isRegistered L869（未注册回灌×3）→ TOOL_REGISTRY.validate L1001（死开关门控）→ exec() L2108。

**终态/交互型（runLoop 内处理，不进 exec）**

| 动作 | 语义 | 入口 |
|---|---|---|
| ask | 暂停问用户（askLatch 5min 四态；TIMEOUT≥2 → failTask） | L883-934 → askUser L2491 |
| done | 终态成功（judgeDone 三档证据 PROVEN/WEAK/UNPROVEN；有效才 FlowMemory.commit） | L935-990 → judgeDone L1653 |
| fail | 终态失败（UiText.uiReason 三处共用） | L991-995 → failTask L1688 |

**直达层（exec 内）**

| 动作 | 语义 | 入口 |
|---|---|---|
| open_api | target×intent 硬编码 deep link（高德/百度/B站/小红书/微博/淘宝/京东/闲鱼/拼多多/美团/网易云/QQ音乐/dial/sms/browse），失败降级 startApp | L2309-2391 |
| open_url | ACTION_VIEW 开链接 | L2304-2308 |
| setting | SETTINGS_PAGES 22 项直达设置子页 | L2291-2303 |
| app | 中文名→APP_NAMES→installedApps 模糊匹配；登记 expectPkg | L2258-2290 |
| list_apps | 已装应用列表注入 pendingNote | L2179-2187 |
| remote | 跨设备转发（resolveDevice 指代解析+防回环） | L2129-2171 |
| wifi/bluetooth/brightness/volume/statusbar/wake | 快捷面板/开关直达 | L2392-2436 |

**界面层**

| 动作 | 语义 | 入口 |
|---|---|---|
| tap | index 节点→px,py 比例→x,y 像素三级回退 + RootShell 兜底 | L2111-2128 → resolvePoint L2448 |
| longpress / swipe / scroll | 手势（scroll 四向，down=手指上滑） | L2172-2217 |
| key | code 4=返回 3=主页 187=最近 | L2218-2226 |
| edge_back | 边缘手势返回（y=0.45h 横滑） | L2227-2238 |
| back | 无障碍返回（提示词自述「比 key 4 可靠，优先用」） | L2239-2240 |
| home | 回桌面 | L2241-2242 |
| text | 三级降级：svc.setText→florisboard IME 广播→剪贴板+粘贴引导 | L2243-2257 |
| wait | 200-8000ms 钳位 | L2437-2441 |

注：back/home 已在 ActionSchema 注册（L41，SCHEMA L378-379）——旧文档称「未注册」已过时。

### 4.2 网络客户端接口清单

| 客户端 | 面向 | 关键接口 | HTTP 底座 |
|---|---|---|---|
| LlmClient（594 行，实例类） | 推理 | forPrefs L194 / chat L265（429 退避×3、含图自动切视觉模型）/ transcribe L376（ASR 分流）/ post L458（402 也回退的网关矩阵） | 自有（TlsPin+禁重定向） |
| AccountClient（455 行，全 static） | 账号 | register/login/me/bindDevice/redeem/shop/createOrder/orders/payOrder/cancelOrder | **复用 SyncClient.request** |
| SyncClient（843 行，全 static） | 同步编排 | syncOnce L195（tryLanFirst→push×4→pullConfig）/ rendezvousNow / request L693 | 自有 pinnedFactory |
| LanSync（647 行，全 static） | P2P 数据面 | deriveAndStoreKey / syncWithPeer / pendingPeerRecords / 手写最小 HTTP L617-640 | 自有（明文 HTTP+GCM 信封） |
| RemoteCommand（356 行，全 static） | 跨设备指令 | refreshDevices L79 / send L171 / pollOnce L228 / ack L299 | **自有 request L317** |
| BailianClient（410 行，全 static） | 百炼 Key 控制面 | list L106 / create L189 | 自有 |

**服务端 URL 消费面（agent-lite 全量）**：account/register·login·me；device/bind；sync/push·pull；config（pull 白名单 5 键）；packs；orders×4；device/command×4；lan/rendezvous；redeem。

## 5. 独立子系统接口清单

### 5.1 agent-client（旧代 Android 客户端）

**关键背景**：agent-client/README.md:1 与 agent-lite/README.md:1 逐字相同（都叫 "agent-client —— 零 root 原生 Agent 客户端"）。agent-lite 是 agent-client 的演进继任（动作集严格超集 19→23 case），**旧目录整体是代际遗留，与 agent-lite 大面积同源复制**：collectNodes/截图注释逐字同文/RootShell.java:85-89 行号相同逐行同款/NodeInfo.toPrompt 同构。

| 组件 | 位置 | 接口/职责 |
|---|---|---|
| MainActivity | MainActivity.java:30（494 行） | 唯一 Activity，UI + 回调宿主 |
| AgentA11yService | AgentA11yService.java:32（333 行） | 节点爬取/手势/截图/setText |
| AgentEngine | AgentEngine.java（995 行） | exec 分发 19 case：tap longpress swipe scroll key edge_back back home text app setting open_url wifi bluetooth brightness volume statusbar wake wait（L534-753） |
| SandboxController | SandboxController.java:36（259 行） | root 虚拟屏沙盒（单例）：nohup app_process vd_daemon.jar 建屏 / am start --display / screencap -d / dumpsys SurfaceFlinger --display-id / dumpsys activity 迁移 Task / pgrep 探活 |
| vd-daemon | VdMain.java（90 行） | app_process root 守护：反射 DisplayManagerGlobal 建 TRUSTED+OWN_DISPLAY_GROUP 虚拟屏（绕 ColorOS canHostTasks=false）；接口=stdout 状态行写 /data/local/tmp/agent_vd.out |
| floris-merge | AgentImeBridge.java:34 | 把文字注入缝进 FlorisBoard APK：广播 com.dsh.agent.IME_COMMIT（extras text/replace）+内置拼音引擎；merge_floris.py 二进制手术；FlorisImeService.java 7 行编译垫片（有意，不进 dex） |
| 其余 | LlmClient/RootShell/NodeInfo/ActionParser/ActionValidator/Prefs/VoiceRecorder | 薄工具层（与 agent-lite 同源） |

### 5.2 agentd-go（设备端常驻守护）

**HTTP 路由**（main.go:390-406，全部经 guard.protect 中间件）：

| 路由 | Handler | 语义 |
|---|---|---|
| GET / | handleIndex | WebUI |
| GET/POST /api/config | handleGetConfig/handleSetConfig | 配置读写 |
| POST /api/task | handleTask | 下发任务 |
| POST /api/stop | handleStop | 停止 |
| GET /api/status | handleStatus | 状态 |
| GET /api/screen | handleScreen | 截屏 |
| POST /api/test | handleTest | 自检 |
| POST /api/asr | handleAsr | 语音识别 |

**鉴权**：首次启动生成随机 hex token 落盘 api.token（0600，或 env AGENTD_TOKEN），每请求校验 X-Agentd-Token 头或 Authorization: Bearer（auth.go:36-75）+ 方法白名单 + POST Content-Type 必须 JSON。

**internal 公开接口**：llm 包 TextMessage/VisionMessage/Chat/ChatCtx；device 包 28 函数（DumpUI/Screenshot/Tap/Swipe/Key/Text/StartApp…）；semantics 包 Parse/ToPrompt；vision 包 DataURL；config 包 Default/Load/ValidatePort/ValidateBaseURL；agent 包全私有（Run L366 / exec L154 / sense L240 / senseViaService L325）。

### 5.3 三端能力重叠总表

| 能力 | agent-client（旧代） | agent-lite（主力） | agentd-go | 计数 |
|---|---|---|---|---|
| OpenAI 兼容 LLM 客户端 | LlmClient.java:49 | LlmClient.java:265 | llm.go:174 | **×3**（BailianClient 是 Key 管理非推理，不计） |
| 节点提取→prompt | NodeInfo.toPrompt:22 | NodeInfo.toPrompt:31（+SemanticTranslator+ScreenState 第二层） | semantics.Parse/ToPrompt | ×3 |
| 截屏 | RootShell:85-89 + takeScreenshot:276 | RootShell:85-89 + takeScreenshot:538 | device.go:48 | ×3 通道 ×3 端 |
| 设备操作命令 | RootShell input/am | RootShell 同款 | device.go 同款字符串 | ×3 |
| 动作集 | 19 case | 23 case（超集，同名同语义） | 私有 exec | ×2(+1) |
| 输入注入 | floris 广播 + SET_TEXT:89 | SET_TEXT ×2（:297/:502）+ IME 缝合 + 剪贴板 | Text/TypeText/SetTextViaA11y | ×N |

## 6. 审计发现总表

| 子项目 | 同功能多实现 | 层间反复传输 | 死代码/死开关 | 薄层/臭抽象 | 契约漂移 |
|---|---|---|---|---|---|
| agentlite-server（§7.1） | 6（S1-S6） | 5（S7-S11） | 5（S12-S16） | 1（S17） | — |
| ios（§7.2） | 5（I12-I16） | 4（I18-I21） | 11（I1-I11） | 3（D 组） | 1（I17） |
| agent-lite（§7.3） | 8（A1-A8） | 4（A9-A12） | 10（A13-A22，≈1700 行 ≈9.3%） | 3（A23-A25） | — |
| agent-client/agentd（§7.4） | 6（X1-X6） | 3（X11-X13） | 遗留 1 目录（X14-X16） | 4（X7-X10） | — |

**横向四条主线**（详见 §8）：
1. 同一基础设施多份实现且质量漂移（HTTP 栈×4 / LLM 客户端×3 / readAll×3 / 限流器×2 / PEM 解析×2 / 凭据解码×3）——差的那份往往跑在关键链路（RemoteCommand.request）。
2. 服务端"单条变更→整表序列化→全文件重写"的持久化模式 + 客户端串行 6 往返同步 + 网关 4 次编解码——层次间数据传输税集中在持久化与同步路径。
3. 预留能力从未接线：死代码 agent-lite ≈1700 行（9.3%）+ iOS 11 项 + 服务端 5 函数 + contracts/ 整包。
4. 代际遗留：agent-client/ 整目录是 agent-lite 旧代同源复制，四项核心能力两边各一份。

## 7. 分项审计发现

### 7.1 服务端（agentlite-server）

**同一功能多种实现**

| # | 问题 | 位置 | 第一性裁决 |
|---|---|---|---|
| S1 | 兑换码双入口：/api/v1/redeem 与 /api/v1/orders/pay 同调 Store.Redeem，前者绕过订单生命周期 | api.go:615 / api.go:454→order.go:273 | 保留 orders/pay（带订单对账），redeem 是多余入口或需改为内部复用同一审计路径 |
| S2 | 配置双出口：/api/v1/config 独立路由 + sync/pull 内嵌 config | api.go:379 / api.go:374 | 一个数据一个出口；iOS config 零调用佐证独立路由无消费 |
| S3 | mtime 缓存双胞胎：Store.Config 与 Store.Packs 同一逻辑两份 | store.go:460 / order.go:101 | 抽一个 generic helper 或合并配置与套餐为同一资源 |
| S4 | JSONL 流水双胞胎：appendLedgerLocked（带 Sync）与 auditCommand（不 Sync） | quota.go:246 / command.go:214 | 同为追加式审计流水，行为不一致是隐患 |
| S5 | 滑动窗口限流双胞胎：rateLimiter 与 commandStore.recent | main.go:195 / command.go:110 | 同一算法两份实现 |
| S6 | logInfo/logError 单行包装，gateway.go:404 仍直接 log.Printf 绕开 | main.go:188 | 要么统一要么删除 |

**数据反复传输/序列化放大（性能）**

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| S7 | /health 全量防御拷贝只为 len()：Devices() 拷全部 Device（含 LastStatus 大快照）、Accounts() 同理 | api.go:192-193 ← store.go:306/account.go:469 | 每次健康检查 O(全表) 拷贝 |
| S8 | 整表 MarshalIndent+原子重写 per 单条变更：save{Accounts,Devices,Orders,Codes}Locked；handlePush 每次必经 Touch→saveDevicesLocked | account.go:201/store.go:539/order.go:162/quota.go:80；api.go:346→store.go:302 | push 高频路径上每条记录触发全表序列化+全文件重写 |
| S9 | 网关同一 body 最多 4 次 unmarshal + 2 次 marshal（probe→ensureIncludeUsage→adaptRequest） | gateway.go:131/289/310/255/273 | 每次推理请求重复编解码 |
| S10 | Store.Pull 无索引全文件逐行 unmarshal 凑 limit | store.go:413-453 | 账号流增长线性变慢 |
| S11 | handlePush 内 ValidOrigin 每条记录独立加读锁 | api.go:326/332 | 批内 N 次锁往返 |

**死代码/无效防御（grep 零引用验证）**

| # | 问题 | 位置 |
|---|---|---|
| S12 | 五个零引用函数：newToken、drainAndClose、CodeStats、SetDisabled、AddQuota | store.go:187/api.go:689/quota.go:263/store.go:318/account.go:349 |
| S13 | ErrDisabled 状态不可达：SetDisabled 无调用、BindDevice 恒置 false；authDeviceMW 又把它与 NotFound 合并同 403 | store.go:40/282/253；api.go:152-155 |
| S14 | kindStatus 常量零引用 | store.go:32 |
| S15 | PayOrderWithCode 自赋值防御无效：重取的 o 是同一指针，并发下只能改 Status | order.go:269→280→290 |
| S16 | CodeStats 的 used 恒 0（核销即 delete） | quota.go:263-270/178 |

**薄层抽象**

| # | 问题 | 位置 |
|---|---|---|
| S17 | orderStore 独立类型零方法纯裸 map 容器，方法全挂 *Store | order.go:80-85 |

### 7.2 iOS（AgentLiteCore + CLI）

**死代码（全仓含测试零引用）**

| # | 问题 | 位置 |
|---|---|---|
| I1 | SyncedCommandStatus 枚举（status 是 String 从未用） | Models.swift:218 |
| I2 | CommandStatus.isTerminal（实际用字符串数组/`== .done`） | Models.swift:236 |
| I3 | AgentLiteClient.commandResult 纯转发零调用 | AgentLiteClient.swift:124 |
| I4 | SyncService.serverConfig（/api/v1/config 无消费） | SyncService.swift:100 |
| I5 | SyncService.resetCursor | SyncService.swift:42 |
| I6 | sha256Hex / constantTimeEquals | Crypto.swift:146/158 |
| I7 | PlatformInfo.osVersionString | PlatformInfo.swift:63 |
| I8 | HTTPClient.send 无返回值重载（9 处调用全带 as:） | HTTPClient.swift:230-237 |
| I9 | EmptyBody 从未实例化 | Models.swift:272 |
| I10 | SyncItem 被 SyncRecord/SyncOutgoingItem 取代零引用 | Models.swift:119 |
| I11 | LANPeerFactory 写而不读（current 零读出；shared 是 Void 属性纯误导） | AgentLiteClient.swift:94/99/164/157 |

**同一功能多种实现**

| # | 问题 | 位置 |
|---|---|---|
| I12 | PEM→DER 解析两份：CertificatePinnerSupport vs CLI 手写 | HTTPClient.swift:117-130 / main.swift:130-139 |
| I13 | 凭据解码三份：CredentialStore.load vs CLI lan-accept/lan-respond 手解 | KeychainStore.swift:145 / main.swift:245-246/261-262 |
| I14 | 指令终态判断三种表达：字符串数组 / == .done / 无人用的 isTerminal | CommandService.swift:132 / CommandInbox.swift:89 / Models.swift:236 |
| I15 | 状态枚举两套：SyncedCommandStatus（5 值未用）vs CommandStatus（3 值） | Models.swift:218/228 |
| I16 | ExecutionOutcome.rejected ≡ .failed 完全同义 | CommandExecutor.swift:35/38 |

**契约漂移风险（解码即丢弃）**

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| I17 | lanKeyParams 服务端下发 PBKDF2 参数，deriveLANKey 用硬编码常量 | Models.swift:41/93；Crypto.swift:22-24 | 服务端调参后 iOS P2P 静默永远解不开 |

**数据反复转格式**

| # | 问题 | 位置 |
|---|---|---|
| I18 | 游标 Int64→String→Data 存、三段反转读（迁就 SecureStore Data 接口） | SyncService.swift:32-39 |
| I19 | FileCredentialStore 每次 set/get/remove 全量 load+persist | FileCredentialStore.swift:20-34 |
| I20 | LANPeer seal/open 以 String 为接口多两次 UTF-8 往返 | LANPeer.swift:100-109/135 |
| I21 | Credentials 在 adopt 内两次完整 save+编码 | AccountService.swift:83/88 |

**行为观察**

| # | 问题 | 位置 |
|---|---|---|
| I22 | LANPeer 抗重放缓存 seen 随实例丢弃，实际无效 | AgentLiteClient.swift:143 |
| I23 | CLI usage 缺 lan-respond 子命令 | main.swift:91-111 |
| I24 | SensitiveGuard 词表含「发送/send」高频动词，跨设备任务大面积误拒 | CommandExecutor.swift:111-112 |

**按宿主分流（非问题，记录裁决）**：KeychainStore（App）/ FileCredentialStore（CLI 无 Keychain entitlement）/ InMemoryStore（仅测试）——刻意分流成立，代价是三实现。

### 7.3 agent-lite（Android 主力）

**组件通信（背景，无冗余问题）**：三件套——进程内静态单例直调（AgentEngine.get()/AgentA11yService.get()）、回调接口（AgentEngine.Listener 等）、显式 Intent（类构造，应用内零自定义 action）；唯一跨应用广播 com.dsh.agent.IME_COMMIT（text 注入第三链，配自定义权限）。AssistActivity→MainActivity 走 sPendingTask 通道（P0-1 confused deputy 修复，外部 extra 刻意忽略）。

**同一功能多种实现**

| # | 问题 | 位置 | 第一性裁决 |
|---|---|---|---|
| A1 | HTTP 栈四套：LlmClient.post（推理）、SyncClient.request（AccountClient 复用）、RemoteCommand.request L317、BailianClient 自带签名栈 | 四处 | 同一服务器域三种栈；RemoteCommand 版质量最差却在指令链路（见 §8.1） |
| A2 | readAll 三份拷贝 | SyncClient L769 / BailianClient L306 / RemoteCommand L347 | 纯复制 |
| A3 | sha256Hex 两份逐字相同 + 内网 IP 枚举两份 | SyncClient L805/LanSync L560；SyncClient.lanIPv4 L660/LanSync.lanIPs L136 | 纯复制 |
| A4 | /api/v1/account/me 双客户端各写解析 | AccountClient.me L215 / RemoteCommand.refreshDevices L79 | 同一端点两份解析代码 |
| A5 | key/back/home 三词同参数空间（back≡key{code:4}，home≡key{code:3}） | AgentEngine L2218-2242 | 提示词自述 back 优先于 key 4——词表应收敛为单表达 |
| A6 | tap 五层回退：index→px,py→x,y→svc.tap→dispatchGesture→RootShell | AgentEngine L2111-2128 + AgentA11yService L404-458 | 层间真实失败场景递进，但 tap/longPress/swipe 每个内部再分 root/无障碍两支——回退矩阵嵌套回退，两层各自兜底 |
| A7 | swSingleModel 同开关两处 UI | PurchaseActivity L131-135 / SettingsActivity L63 | 状态双入口易漂移 |
| A8 | open_api 与 open_url 在 expectEffect 判定合并 | AgentEngine L2304-2309 | 判定合并但实现分立，样式堆积候选（deep link 表是真实数据非抽象，裁决：保留数据、并入 open_url 语义） |

**层间反复拷贝/转格式（性能）**

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| A9 | 模型回复四重转换：文本→ActionParser 正则→JSONObject→actionToArgs Map→validate 再遍历→回灌再拼 JSON | AgentEngine L860/L1441-1461 | 每动作四次表示变化 |
| A10 | 节点三态往返：AccessibilityNodeInfo→NodeInfo→提示词文本→模型回 index→nodeRefs 反查 | AgentA11yService L112/L333 | 感知层固有成本，index 复用设计合理；记录不整改 |
| A11 | 任务历史双读：SyncClient.buildTaskBatch 与 LanSync.collectRecordsForPeer 各自读同一 JSONL | SyncClient / LanSync | 同文件每轮双读 |
| A12 | syncOnce 串行 6 往返（4 种 push 载荷分 4 请求） | SyncClient L195-262 | 见 §8.2 |

**死代码/死链路（全部 grep 零引用验证，合计 ≈1700 行 / 18242 ≈ 9.3%）**

| # | 项 | 位置 | 规模 |
|---|---|---|---|
| A13 | **BailianClient 整类死链路**（DeepSeek 充值切换后零调用，连带 AkStore、AliyunV3Signer 仅被它引用） | BailianClient 410 + AkStore 212 + AliyunV3Signer 311 | ≈933 行 |
| A14 | **contracts/ 整包 + ContractVersions 死**（五类互引，包外零使用者——ADR-002 声明的契约层运行时从未接线） | contracts/ 463 + ContractVersions 20 | ≈483 行 |
| A15 | PurchaseFlow 大半死（KeyInfo 被死的 BailianClient 引用；DeepSeek 路径下 checkAfterPurchase 快照永假） | PurchaseFlow L84/L147-149 | 大半 |
| A16 | requestAuth(String) 旧三选项授权（被 submitAuth 取代） | AgentEngine L1739-1759 | 21 行 |
| A17 | requestIgnoreBatteryOptimizations（权限已声明） | MainActivity L980-997 | 18 行 |
| A18 | validateThenCommit 与内联重复 | PurchaseActivity L348 | ~30 行 |
| A19 | setTextOnDisplay/collectNodesOnDisplay（root 虚拟屏残留） | AgentA11yService L276/L316 | ~80 行 |
| A20 | lockScreen/powerDialog（performGlobalAction 七连中两支） | AgentA11yService L681-682 | 2 行 |
| A21 | isPolling() / onFallback() / payerLabel() 零调用 | RemoteCommand L226 / LlmClient L212/L224 | 3 方法 |
| A22 | feature_tool_router 死开关（TOOL_REGISTRY.validate 是 ToolRegistry 唯一消费） | FeatureFlags L19 / AgentEngine L1002 | 声明层未转正 |

**薄层（可接受/待裁决）**

| # | 问题 | 裁决 |
|---|---|---|
| A23 | 保活三层：KeepAliveService 同时编排 GuardService（双进程互绑）+A11yJobService（JobScheduler）+KeepAliveGuide | 多层各治一种厂商杀法，有真实场景；但四类仅一个编排方，可合并为配置表 |
| A24 | SemanticTranslator 单调用方（AgentEngine.planTask L565） | 有纯逻辑可测性理由，可接受 |
| A25 | bmpToDataUrl public static 仅内部用 | 缩权限，一行 |

### 7.4 agent-client / agentd-go（独立子系统）

**同一功能多种实现（跨项目级，量级最大）**

| # | 问题 | 位置 | 第一性裁决 |
|---|---|---|---|
| X1 | OpenAI 兼容 LLM 客户端 ×3：agent-client LlmClient.java:49 / agent-lite LlmClient.java:265 / agentd-go llm.go:174，vision 切换逻辑各自重写 | 三端 | Go/Java 跨语言无法直接共享，但两份 Java（新旧代）是纯复制；协议形状（messages/image_url/dataURL）可抽契约测试对齐，而非三份各自漂移 |
| X2 | 节点→prompt 渲染 ×3 + agent-lite 内部第二层（SemanticTranslator+ScreenState） | NodeInfo.java:22/31、semantics.ToPrompt | agent-client 注释自认"移植自 agentd-go"——三代同源；lite 内 contracts 层与 NodeInfo.toPrompt 是双层语义链 |
| X3 | 截屏 ×3 通道 ×3 端：screencap（两份 RootShell 同款+device.go:48）、takeScreenshot（两份 AgentA11yService）、沙盒 screencap -d | 三端 | 通道差异（root/a11y/虚拟屏）是真实场景；同通道跨端复制是冗余 |
| X4 | 设备操作命令字符串 ×3 端同款 | RootShell.java:89/98/102/106 ×2 + device.go | input/am 命令序列三处硬编码，改一处忘两处 |
| X5 | 动作词表 ×2（client 19 case ⊂ lite 23 case，同名同语义） | AgentEngine.java:534-753 / :2108+ | 旧代目录整体归档后自动消解 |
| X6 | agentd-go 两条感知链并存：sense() XML-dump 与 senseViaService() 服务直读，各自整树序列化 | agent.go:240/:325 | 双路径无开关切换则是一条死路；需确认接线 |

**agent-client 单调用方薄层（臭抽象）**

| # | 问题 | 位置 |
|---|---|---|
| X7 | ActionParser.java 37 行仅 1 处引用 | ActionParser.java |
| X8 | VoiceRecorder.java 仅 1 文件引用 | VoiceRecorder.java |
| X9 | ActionValidator.java 仅 2 处引用 | ActionValidator.java |
| X10 | device.go Back()/Home()/Wake() 一行转发 Key() | device.go:213-215 |

（注：X7-X9 属旧代目录，随 X5 归档一并处理，不单独整改。）

**数据反复转格式**

| # | 问题 | 位置 | 影响 |
|---|---|---|---|
| X11 | 截图链同进程两次编解码：Bitmap→PNG 落盘→DataURL 重读再 base64→JSON | vision.go:22 + llm.go:55 | 每帧视觉请求多一轮 PNG 编解码+磁盘往返 |
| X12 | 节点链三层两跳：AccessibilityNodeInfo→NodeInfo→ScreenState.VisibleNode→JSON | agent-lite 感知层 | 每轮感知两跳转换 |
| X13 | go 侧两条感知路径各自整树序列化 | agent.go:240/:325 | 与 X6 同源 |

**代际遗留（架构摆放）**

| # | 问题 | 裁决 |
|---|---|---|
| X14 | agent-client/ 整目录是 agent-lite 旧代（README 同文、动作集子集、四能力同源复制） | **若无沙盒(root 虚拟屏)/floris-merge 两项独立价值，整体归档**。二者若仍需，抽为独立组件目录而非保留整个旧客户端 |
| X15 | agent-lite/README.md:1 标题仍是 "agent-client" | 改名遗留，一行修复 |
| X16 | vd-daemon root 通道 | 符合工作区「零 root 基线 + root 可选增强」，有降级路径（非沙盒走真屏），无违规，记录裁决 |

## 8. 架构摆放与层次间数据流（第一性分析）

### 8.1 接口语义重复的三种形态（全库横向归纳）

把所有发现按第一性归类，"一个功能多种实现样式"在本库有三种形态：

1. **同一资源多个入口**（服务端）：兑换码有 /api/v1/redeem 和 /api/v1/orders/pay 两个入口同调 `Store.Redeem`，前者绕过订单生命周期，对账流可跳过；配置有 /api/v1/config 独立路由和 sync/pull 内嵌双出口。
2. **同一操作多个客户端词**（agent-lite / iOS）：`key{code}` / `back` / `home` 三词覆盖同一参数空间（back≡key{code:4}，home≡key{code:3}）；iOS 指令终态判断有字符串数组、`== .done`、无人用的 `isTerminal` 三种表达；状态枚举两套（5 值的 SyncedCommandStatus 全未用 vs 3 值的 CommandStatus 在用）。
3. **同一基础设施多份实现**：HTTP 底座 ×2（SyncClient.request vs RemoteCommand.request）、TLS pin ×2（TlsPin.apply vs SyncClient.pinnedFactory）、服务端限流器 ×2、mtime 缓存 ×2、JSONL 流水 ×2、iOS PEM 解析 ×2、凭据解码 ×3。

跨项目维度再放大一层：OpenAI 兼容 LLM 客户端 ×3 端、节点→prompt 渲染 ×3 端、截屏 ×3 通道 ×3 端、input/am 命令字符串 ×3 端同款——其中 agent-client 一份是旧代整体复制（X14），消解它可一次砍掉一半的跨端重复。

形态 3 危害最大：两份实现必然演化出质量差——已证实 RemoteCommand.request（指令链路在用）无 streaming mode、错误处理原始，而 SyncClient.request（账号/同步在用）有 pinnedFactory+parseError+finally disconnect。**差的那套恰好跑在关键链路上。**

### 8.2 层次间数据反复传输（性能税）

**服务端持久化模式是最大的一笔**：四个 store（accounts/devices/orders/codes）都是"单条变更 → 整表 MarshalIndent → 全文件原子重写"。push 高频路径上每条记录必经 Touch→saveDevicesLocked（api.go:346→store.go:302）——设备数增长后，同步吞吐为全表序列化付钱。/health 更典型：只要 len() 却做了全表防御拷贝（含 LastStatus 大快照）。网关对同一 body 最多 4 次 unmarshal+2 次 marshal。Pull 无索引逐行扫描凑 limit。

**agent-lite syncOnce 的串行往返**：一轮同步最多 6 次串行 HTTP（rendezvous→LAN 尝试→push 任务史/同伴/记忆/状态→pull 配置），4 种 push 载荷打同一路由、服务端按 kind 分流。路由复用本身是"一个接口一个实现"的正例，但 4 次独立请求是传输未合并：同账号同轮的 4 种载荷语义上可一批发（服务端 handlePush 本来就逐条处理）。

**iOS 的迁就式转换**：游标 Int64→String→Data 三段存取迁就 SecureStore 的 Data 接口——存储接口形状渗出到调用方，调用方付转换税。

### 8.3 死代码与死开关的共性：预拆的 CDE

feature_tool_router 死开关、ToolRegistry 声明层（唯一用途是一次 validate）、contracts/ 整包（ADR-002 声明、运行时零接线，≈483 行）、SyncedCommandStatus 全量枚举、ErrDisabled 不可达状态机、orderStore 裸容器、BailianClient 死链路三件套（≈933 行）——它们的共性是**为"将来"预留的能力从未接线**。这正是用户说的"能 A/B 解决不要拆 ABCD"：A（exec switch / 字符串判断 / *Store 直挂 / PurchaseActivity 直连）已在干活，预留的 B′ 从未转正，却要人脑维护两份语义映射。agent-lite 一个子项目死代码 ≈1700 行 ≈ 9.3%。

### 8.4 架构摆放问题

1. **iOS 半死链路最差**：Sync/LAN 同步代码全量维护、测试覆盖，但生产 App 零接线——实际只跑 CommandInbox。要么接线要么删；半死状态下每个协议常量改动（如 lanKeyParams）都在给无人使用的路径付对齐成本，且已产生 I17 契约漂移。
2. **agent-unified 镜像层**：构建期逐字节复制+rsync 门禁，不构成运行时数据流冗余；代价是每个改动双写、门禁 `--check` 本身是"副本决策"的持续税。若发布流水线直接引用真相源路径构建，副本层可整体消失。属可简化项而非臭抽象（注释与排除项设计详尽，动机成立）。
3. **客户端-服务器消费面不对称**：服务端 22 条业务路由，iOS 只消费 11 条，agent-lite 消费 19 条。packs/orders/redeem/rendezvous/admin 五组路由是 Android 单端特性——单端特性放在共享服务器上无妨，但 config 路由连唯一该消费它的 iOS 都零调用（它从 sync/pull 拿配置），独立路由存在的必要性弱。

### 8.5 有据设计（非臭抽象，记录裁决）

审计同时确认以下"看似多套实现"实际每套都有真实场景，不应砍：

| 设计 | 位置 | 依据 |
|---|---|---|
| text 三级降级 setText→commitViaMergedIme→injectViaClipboard | AgentEngine.java:2243-2257 | 每级对应真实失败场景（无焦点→IME 不可用→兜底剪贴板），级间有日志 |
| LanSync 独立 P2P 加密通道 | LanSync.java | 省 ECS 出网流量+降延迟；GCM 信封与 TLS 不同威胁模型（明文 HTTP 上的 P2P） |
| FuzzyFlow/FlowMemory 记忆复现 | FuzzyFlow.java / FlowMemory | 命中操作记忆免 LLM 推理，成本动机明确；纯 Java 可桌面 JVM 回归 |
| iOS Keychain/File 凭据双实现 | KeychainStore.swift / FileCredentialStore.swift | CLI 无 Keychain entitlement，宿主分流 |
| 保活多层级联（GuardService 双进程互绑 + JobScheduler + 引导页） | KeepAliveService L136-138 | 各层针对不同厂商杀法，有真实场景；仅编排方可合并为配置表（A23） |

## 9. 重构建议（按收益排序）

> 原则：每条建议都是删一个东西或合并两份实现，不是新增一层。

**R1（最高收益·服务端性能）持久化改增量**
整表 MarshalIndent+重写改为：JSONL 追加式存储（accounts 除外，保留整表）或按表分文件。push 路径的 Touch 去掉全表重写（LastStatus 快照单独存放）。/health 改计数器。预期：push 吞吐从 O(全表) 降 O(1)。

**R2（接口收敛）兑换码单入口**
/api/v1/redeem 删除或改为内部复用订单审计路径；配置单出口（保 sync/pull 内嵌，删独立 /api/v1/config——iOS 零消费已验证安全）。

**R3（客户端底座合一）**
RemoteCommand.request 废弃，指令链路改走 SyncClient.request（补 device 头）；TlsPin.apply 与 pinnedFactory 二选一。质量高的那套成为唯一实现。

**R4（词表收敛）**
ActionSchema 删 `back`/`home`（≡key{code:4}/{code:3}），引擎 exec switch 同步删两个 case；或反向：删 `key` 保留语义化词。二选一，不三词并存。open_api 若 expectEffect 判定与 open_url 合并处无差异，并入 open_url。

**R5（死代码清除，一次砍 ≈2600+ 行）**
agent-lite：BailianClient+AkStore+AliyunV3Signer 死链路（≈933 行）、contracts/ 整包+ContractVersions（≈483 行，ADR-002 从未接线——若契约层仍要做，先接线再保留）、PurchaseFlow 死支（DeepSeek 切换残留）、requestAuth/requestIgnoreBatteryOptimizations/validateThenCommit/setTextOnDisplay/collectNodesOnDisplay/lockScreen/powerDialog/isPolling/onFallback/payerLabel、feature_tool_router 死开关（ToolRegistry 转正为执行真相源或整体删除）。服务端：newToken/drainAndClose/CodeStats/SetDisabled/AddQuota/ErrDisabled/kindStatus/orderStore 裸类型。iOS：§7.2 A 组 11 项。

**R6（iOS 链路决断）**
Sync/LAN 生产接线或删除。若保留代码不接线，至少删 lanKeyParams 解码（I17）或改为真正读取下发参数——解码即丢弃是定时炸弹。

**R7（传输合并）**
syncOnce 的 4 种 push 载荷合并为单请求批量 kind 数组（服务端 handlePush 逐条处理逻辑不变）；网关 body 解析次数 4→1（unmarshal 一次后传递结构体）；工具函数拷贝收敛（readAll/sha256Hex/lanIP 各留一份）。

**R8（跨端重复治理）**
agent-client/ 旧代目录归档（保沙盒/floris-merge 两件独立价值则抽为独立组件目录）；agent-lite/README.md:1 标题改名遗留修复；LLM 客户端三端以契约测试（同协议 fixtures）对齐而非各自漂移；input/am 命令字符串三端同款——至少在注释里互相索引。

**R9（杂项）**
PayOrderWithCode 删无效自赋值；auditCommand 与 appendLedgerLocked 统一为一个带 Sync 的流水函数；限流器/mt ime 缓存/PEM 解析/凭据解码各收敛为一份；CLI usage 补 lan-respond；SensitiveGuard 词表复核「发送/send」；swSingleModel 开关收敛单入口；bmpToDataUrl 缩权限。
