# 接口收敛与最小架构实施报告

> 日期：2026-10-11；项目：repo +agentlite-server +agent-lite +ios +agentd。
> 范围：落实[接口与架构审计 F01–F14](<INTERFACE_AUDIT_2026-10-10.zh-CN.md#7-高证据问题与最短修复>)，基于 HEAD `2c73a9f` 和原有未提交工作树实施。保留既有改动；没有自动提交、线上部署或设备安装。
> 状态：十四项对应的源码改动与本地回归已完成。Android APK 和 iOS App 真实工具链构建通过；设备运行、线上升级和物理断电测试另行验收。

## 1. 实施原则与范围

一个状态只有一个事实源；同一批数据一次提交；同义动作共用执行分支。优先解决数据正确性，再减少请求、扫描与序列化，而不是按类数量机械删除。

- 没有新增业务 HTTP 路由，没有新增通用 Repository、网络适配框架或多层兜底。
- 服务端继续使用 Go 标准库；为现有 JSONL 存储增加明确事务提交和内存页索引，没有引入数据库驱动或 cgo。
- 账号 JSON HTTP 共用一个连接实现；LLM、ASR 和 LAN 保留各自真实的流式、音频与加密协议边界。
- 零 root 仍为必需基线；本次没有新增 root 前置条件或 root 专属能力。
- [发布镜像同步脚本](<../agent-unified/scripts/sync-sources.sh>)保留：它解决构建输入一致性，不是请求链中的一层业务实现。
- 原有未接运行主链的 contracts、默认关闭但真实接线的 ToolRegistry，以及旧订单等公开能力，没有按“单消费者/类较多”批量删除。

## 2. F01–F14 落地清单：入口 → 唯一实现

| 项 | 实际改动 | 唯一实现 / 证据 | 回归要点 |
|---|---|---|---|
| F01 分页与消费 | 保留 `server_seq` 高水位，增加 `next_since`；iOS 消费成功后才存本页最后 seq；Android 只需配置时直接取 `/config` | [handlePull](<../agentlite-server/api.go#L372-L395>)、[SyncService](<../ios/Sources/AgentLiteCore/SyncService.swift#L110-L152>)、[SyncClient](<../agent-lite/src/com/dsh/agentlite/SyncClient.java>) | 1001 条分六页；消费/存游标失败重放；账号切换隔离；配置不下载 records |
| F02 上传批次 | 本机任务、转发任务、记忆、状态合进一个现有 `/sync/push`；完整回执且身份不变才提交三游标 | [pushBatch](<../agent-lite/src/com/dsh/agentlite/SyncClient.java#L393>) | 小记录最多 150 条/POST；拒收/坏计数/失败不前移；LAN 成功只跳本机任务云上传 |
| F03 指令结果 | 引擎直接回调带 `runId/commandId` 的结果；Android 持久化待 ACK 状态；服务端原 command 对象保留终态 | [AgentEngine](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java#L247>)、[RemoteCommand](<../agent-lite/src/com/dsh/agentlite/RemoteCommand.java>)、[AckCommand](<../agentlite-server/command.go#L168-L228>) | 不读最新历史猜结果；ACK 失败重试但不重执行；同终态 ACK 幂等；查询保留 24 小时 |
| F04 档案刷新 | iOS 一次 profile 同时填额度和同账号设备列表 | [AppModel](<../ios/App/AgentLiteIOS/AppModel.swift#L93>)、[AccountProfile](<../ios/Sources/AgentLiteCore/Models.swift>) | 一次刷新 `/account/me` 从 2 GET 降为 1；同份快照筛掉本机，不引入缓存框架 |
| F05 ASR | 端点为空只用本地；指定端点只调该远端一次，使用独立 ASR 凭据；删除冷却后再次尝试/自动换来源 | [AsrRouter](<../agent-lite/src/com/dsh/agentlite/AsrRouter.java>)、[Prefs](<../agent-lite/src/com/dsh/agentlite/Prefs.java>) | 本地模式零网络；远端失败不借主模型 Key、不切模式；设置文案同步 |
| F06 SSE 透传 | 日志 writer 提供 `Unwrap`；网关统一用 `ResponseController` 设置期限与 Flush | [loggingWriter](<../agentlite-server/api.go#L85-L111>)、[gateway](<../agentlite-server/gateway.go>) | 真实 HTTP 客户端在 handler 结束前收到首段；包装后期限与流式计量仍有效 |
| F07 账务事务 | 直接兑换和订单支付共用核销内核；账号/余额、码、订单、流水同一 JSONL 提交，fsync 成功才发布内存 | [billingTxn / commitBillingLocked](<../agentlite-server/billing.go#L14-L23>)、[提交实现](<../agentlite-server/billing.go#L116-L155>)、[quota](<../agentlite-server/quota.go>)、[order](<../agentlite-server/order.go>) | 支付/取消一个胜者；同订单同码重试不重复入账；写失败余额/码/订单不先改变 |
| F08 存储成本 | 设备整表改为单行日志；账号记录建 seq→字节区间索引；Pull 只读页；登录派生移出全局锁 | [commitDeviceLocked](<../agentlite-server/store.go#L627-L636>)、[Pull](<../agentlite-server/store.go#L434>)、[LoginAccount](<../agentlite-server/account.go#L246-L305>) | 写入失败不发布索引/状态；稀疏序号、重启重建、并发 Append/Pull；锁外派生不覆盖最新额度/token |
| F09 JSON 搬运 | 网关一次顶层 RawMessage 解码，局部读取必要字段，最多一次重编码；Android 按 content.type 检查图片 | [prepareChatRequest](<../agentlite-server/gateway.go#L229>)、[LlmClient](<../agent-lite/src/com/dsh/agentlite/LlmClient.java#L326>) | 保留未知字段、大整数、stream_options 扩展；未改请求原字节返回；图片识别不整包 toString |
| F10 账号 HTTP | 普通账号、同步、指令 JSON 请求共用 AccountHttp；TLS 使用缓存的 pin 工厂，流与连接统一关闭 | [AccountHttp](<../agent-lite/src/com/dsh/agentlite/AccountHttp.java>)、[TlsPin](<../agent-lite/src/com/dsh/agentlite/TlsPin.java>) | Authorization / X-Device-Id / UTF-8 固定长度一致；异常也关闭流、disconnect；保留主机名校验 |
| F11 LAN 批处理 | 一批一次读 ID 集合、一个 writer 追加；本机/转发两个来源公平分页，总包条数与字节数共同限额 | [LanSync](<../agent-lite/src/com/dsh/agentlite/LanSync.java>) | 保留稳定 ID 与 origin；整体最多 500 条、加密信封最多 4 MiB；独立 lan_cursor 不冒用云游标 |
| F12 动作语义 | key/back/home 共用分支与成功检查；scroll 四向一致；未知 open_api/意图直接报错，启动失败不伪装开 App 成功 | [AgentEngine.exec](<../agent-lite/src/com/dsh/agentlite/AgentEngine.java>)、[ActionSchema](<../agent-lite/src/com/dsh/agentlite/ActionSchema.java>)、[ToolRegistry](<../agent-lite/src/com/dsh/agentlite/tools/ToolRegistry.java>) | 老 back/home 名称保留，避免历史流程断裂；HOME 失败不清状态；四向手指与内容方向相反；JD 参数用 JSON 编码 |
| F13 新鲜感知帧 | agentd 视觉模式每次感知只截一次，模型与 Web 预览消费同帧；删除视觉模式动作后第二采样点 | [agent.go](<../agentd-go/internal/agent/agent.go>)、[snapshot_test](<../agentd-go/internal/agent/snapshot_test.go>) | 新决策取新帧，不跨动作复用旧帧；视觉截屏/编码失败不发模型请求；文本模式保留预览 |
| F14 账号域设备 | 绑定索引使用 `accountID + "\x00" + fingerprint`；另一已归属账号的设备不被接管 | [BindDevice](<../agentlite-server/store.go#L232>)、[接口正确性测试](<../agentlite-server/interface_correctness_test.go>) | 相同指纹跨账号不继承状态/LAN；重启保持隔离；无归属历史设备可首次认领 |

## 3. 当前契约与数据路径

### 3.1 分页：已消费事实与服务端高水位分离

`GET /api/v1/sync/pull?since=N&limit=200` 返回：

```json
{
  "records": [{"seq": 1, "device_id": "d_fixture", "kind": "task", "id": "r_fixture", "ts": "...", "data": {}}],
  "server_seq": 1001,
  "next_since": 1,
  "has_more": true,
  "config": {}
}
```

- `server_seq` 是该次快照的流高水位，不是本页已消费游标。
- `next_since` 是本页最后已交付 seq；空页保留请求 `since`。`has_more = next_since < server_seq`。
- iOS 实际按返回 records 的最后 seq 推进；若服务端给 `next_since`，先核对一致性。旧服务端缺该字段时仍按 records 计算，不另开降级执行链。
- iOS `pull` / `drain` 的 `consume` 回调成功、未取消且账号/认证代次不变后才提交。无 `consume` 是纯获取，不持久化游标；消费侧应按 ID 幂等，失败可重放。
- 游标存储按 `sync.cursor.v2.<accountID>` 隔离；旧无账号归属 v1 不继承，从 0 重放，避免沿用错误水位。
- Android 当前配置路径不承担跨端 records 入库，明确使用现有 `GET /api/v1/config` 的 `config` 信封；没有声称实现新的自动跨端记录库。

### 3.2 上传：一次批次，一次完整回执提交

```text
本机任务 + 同伴转发任务 + 显式记忆 + 状态
→ 一个既有 /api/v1/sync/push
→ 核对 accepted / duplicates / rejected 与批次总数
→ 核对发起时 account / token / device 仍一致
→ 一个 SharedPreferences Editor.commit 提交三个源游标
```

- 每个来源仍最多 50 条；普通小记录最多 150 条，未把条数上限简单缩小。
- 每来源保守预算 2 MiB，留出状态/信封余量；整体 HTTP 请求硬上限 8 MiB。任务按 UTF-8 原行估算，记忆估算覆盖控制字符 JSON 转义。
- 放不下的记录留下一页；单条超来源预算明确报错，不推进该批游标，避免静默丢弃。
- 任一计数缺失、负数、拒收或总数不符，整个客户端批次不确认；已成功记录重试由服务端稳定 ID 去重。
- 游标 commit 失败时还原 Android 已改变的内存值并报错，不把“内存已变但持久化失败”视为成功。
- 服务端每个 push 仍一次记录提交与一次设备 Touch；记录与状态快照不是跨文件原子事务。Touch 失败会记录错误；状态可在下一轮重新上报，记录游标确认只代表 records 已收妥。

### 3.3 指令：一个对象，不另造 sync 回执旁路

```text
领取 command → running ACK → 待执行状态持久化 → Engine.start(task, commandId, callback)
→ 本次 runId / commandId 结果 → 保存待 ACK 终态 → 终态 ACK 成功后移除待报状态
```

- Android 待报 key 按账号和设备隔离，不把 token 写入待报记录。旧账号迟到回调只落旧归属。
- ACK 失败保留结果并重试；进程恢复发现未完成 running 时明确记失败，不盲目重放系统动作。它不是 exactly-once 的设备执行保证。
- 服务端未领取窗口 `CommandTTL = 5 分钟`；done/failed/expired 从终态更新时起保留 `CommandResultTTL = 24 小时`。
- 同目标设备、同终态和同截断后 detail 的重复 ACK 幂等，不延长保留期；冲突终态返回 409 `already_acked`。
- 原 `GET /api/v1/device/command?id=...` 在保留期内可取结果。没有新增结果路由，也没有把同一个 command 再包装成 sync 记录。
- 服务端 command 仍是进程内状态，审计日志 best-effort、启动不重放；服务重启或保留期到期后查询可为 404。24 小时保留不等于跨重启持久化保证。

### 3.4 LAN 与感知帧

- LAN 保持现有 AES-GCM、稳定 ID 和 origin 形状；新增独立 `lan_cursor` 的 local/peer 位置及锚点，交替选源，整体最多 500 条和 4 MiB 加密信封。云 `since/server_seq` 不充当 LAN 本地序列。
- 无 LAN 游标的旧客户端使用兼容轮转；这属于同一分页实现的输入兼容，不保留另一套全量扫描/无限回包实现。
- agentd 视觉模式的新感知帧同时更新 Web 预览与模型输入；截屏→缩放→JPEG→Base64 保留，因为压缩与协议转换有真实用途。文本模式仍保留动作后预览。
- 本次未统一 iOS/Android 历史记录的全部字段差异，也未给 iOS App 增加新的自动 LAN 发现/后台接收能力。

## 4. 唯一存储事实源与升级边界

当前源码的数据目录：

```text
data/
├── billing.jsonl             账号/令牌/余额、兑换码、订单、额度流水的事务日志
├── devices.jsonl             设备档案、账号归属、LAN与状态快照的变更日志
├── records/<account_id>.jsonl 账号记录事实源；启动重建 ID 去重与 seq→字节区间索引
├── commands.jsonl            指令审计；不是跨重启的指令队列
├── config.json               现有配置
└── packs.json                可选套餐配置
```

- [账务加载](<../agentlite-server/billing.go#L50-L114>)：首次缺少账务日志时从旧 accounts/redeem_codes/orders JSON 一次导入，原子创建初始提交。旧文件保留历史归档，随后不双写。旧额度流水也作为历史归档保留，不据此重复入账。
- [设备与记录加载](<../agentlite-server/store.go>)：旧设备 JSON 首次导入单日志；记录 JSONL 始终为事实源，索引不另落盘。启动一次重建，Pull 二分定位后只读本页区间。
- 提交顺序是 write → fsync → 发布内存；失败截回原尾部并同步，若回滚也失败就停止后续对应日志写入并返回错误。
- 末尾缺换行的未完成行按未提交尾部恢复；完整坏 JSON、未知账务版本、非法/非递增记录 seq 作为启动错误处理，不悄悄跳过。
- 仍使用单 Store 写锁保护提交。减少了全表序列化和全流扫描，没有为吞吐再造第二套并发状态管理。

**上线前的最小操作顺序**：

1. 停止旧服务写入，备份完整数据目录与旧二进制；在副本上启动新版本验证首次迁移和重开。
   **本机已完成**：`make drill`（[migration_drill.py](<../tests/migration_drill.py>)）在临时数据副本上跑真实二进制，覆盖首次迁移、停机核对、重开幂等、孤儿设备认领与迁移后写入，48 项断言全过。
2. 核对账号数量/余额、有效 token、设备归属、兑换码状态、订单状态，以及记录去重/高水位；做一次支付竞争与分页 smoke。
   **本机已完成**：同一演练的 C/D 阶段逐项核对上述状态并覆盖分页收敛（消费游标不跳页）；阶段 J 用 `threading.Barrier(2)` 打真并发，分别验证「同一订单并发 pay/cancel」与「同一订单两个不同兑换码抢付」，断言恰好一个赢家，并在服务重开后核对额度增量**恰等于赢家那张码的份额**——多记即双重支付，少记即丢账，两头都堵。
   **核对方式**：`billing.jsonl` 首次导入的账号/兑换码/订单数组顺序由服务端 map 迭代决定（[store.go:141](<../agentlite-server/store.go#L141>) 的 `accounts map[string]*Account` 经 [billing.go:60-L65](<../agentlite-server/billing.go#L60-L65>) 的 `range` 导出），Go 不保证该顺序——本机实测 40 轮里有 15% 的轮次首元素不是先写入的那个账号（同一进程反复 `range` 一个 2 元素 map，实测首键分布为 87.5% / 12.5%）。逐字节 diff 会抖动，请按 id 比对而不是按位置。
3. 再切换生产二进制，并验证绑定、配置、指令回执和 SSE。此次本地实现没有执行这个切换。
4. 新版日志已经接收新写入后，旧 JSON 不再是当前状态；直接换回旧二进制会读取过期表。回滚须连同升级前备份恢复，并另行处理切换后的新增数据，不是只回退可执行文件。
5. 活跃的 append-only 文件不直接外部 logrotate/截断；本次尚未实现日志压实和保留淘汰，应先明确停写、备份、重建索引与一致性要求。

## 5. 请求与成本变化

| 事件 | 审计基线 | 当前实现 |
|---|---|---|
| 三类记录 + 状态均有数据的同步轮 | 最多 4 个 push + 拉 records/config | 1 个 push + 轻量 config；LAN 会合/连接另算 |
| iOS 刷新额度与设备 | 2 个相同 profile GET | 1 个 GET，同份快照分发 |
| 普通账号 JSON 连接 | 同步/指令两套实现；每请求重建 CA 工厂 | 一份 AccountHttp；TLS pin 工厂缓存 |
| 远程指令结果 | 回调结果落历史，再全读取“最新条” | 直接带 ID 回调；历史只是独立持久视图 |
| 设备更新 | 序列化并写全设备表 | 单设备变更行 |
| Pull 末页 | 每次扫描整个账号文件 | 启动一次建索引；每次定位并读取本页 |
| 网关 request | 非流式 2 次、流式 3 次完整 decode | 一次顶层 RawMessage decode；最多一次编码 |
| Android 图片判定 | messages.toString 后再 body.toString | content.type 检查 + 唯一发送序列化 |
| LAN 批次去重 | 每条重扫既有文件，约 O(MN+M²) | 一次构建 ID 集合 + 批内插入，约 O(N+M) |
| agentd 连续视觉步骤 | 动作后与下一感知各截一次 | 每次新感知一帧，同时服务两个消费者 |

### 5.1 本机合成基准

环境：Apple M4，Go benchmark，300 ms/用例；数据规模在表内。实现与对照代码只存在于[存储基准](<../agentlite-server/storage_benchmark_test.go>)和[网关基准](<../agentlite-server/gateway_request_benchmark_test.go>)，没有把旧生产实现留作运行备用。

| 用例 | 整表/扫描/重复解析对照 | 新实现 | 同时观察 |
|---|---:|---:|---|
| 1 万设备 Touch | 17.906 ms/op | 3.819 ms/op | 写入约 3,807,782 B → 298 B；分配 24,351,296 → 1,395 B/op |
| 10 万条记录取末 200 条 | 43.498 ms/op | 0.11654 ms/op | 分配 11,843,763 → 109,980 B/op；300,713 → 608 alloc/op |
| 1000 条记录取末页 | 0.477 ms/op | 0.100 ms/op | 小规模也减少扫描 |
| 128 KiB 非流式网关请求 | 0.316 ms/op | 0.166 ms/op | 110 → 27 alloc/op |
| 128 KiB 流式网关请求 | 0.432 ms/op | 0.187 ms/op | 145 → 41 alloc/op |
| 1 MiB 非流式网关请求 | 2.198 ms/op | 1.611 ms/op | 124 → 35 alloc/op；分配字节 2,539,375 → 2,624,683 略升 |
| 1 MiB 流式网关请求 | 4.294 ms/op | 1.431 ms/op | 171 → 52 alloc/op |

解释与限制：

- Touch 对照用当前具备文件/父目录同步的 atomicWrite 模拟旧整表路径，比较的是**相同耐久性条件下的写入结构**；旧部署的 WriteFile+Rename 没有这些 fsync，所以这不是旧线上版本原样延迟对比。
- 是本机文件系统、热启动后的合成测试，不是手机实测、线上并发吞吐或断电耐久性承诺。日志 fsync 仍在 Store 写锁内。
- 内存索引每条至少三个 int64，约 24 B 加 slice 容量；启动重建 O(N)，另有 ID 去重集合。降低请求扫描，以明确的常驻内存换取，不宣称“零成本”。
- 网关分配次数下降，不代表所有尺寸分配字节均下降；1 MiB 非流式样本的字节数反而略升，已保留在表中。

## 6. 验证结果与证明边界

| 验证 | 结果 | 能证明什么 / 边界 |
|---|---|---|
| 根 `make check` | 通过 | Go、契约、UI 守卫及 JVM 全门禁；UI 中既有能力差异 WARN/真机 MANUAL 不视为设备验收 |
| 发布镜像 `sync-sources.sh --check` | 逐字节一致 | 主源与 client/server 构建输入一致，源锁已更新；不是部署成功证明 |
| 服务端 `go test -race ./... -count=1` + `go vet ./...` | 通过；32 具名动态测试 | 4 接口正确性、5 账务、3 流式、14 存储、6 网关；包括故障与真实 HTTP/SSE |
| agentd 同样 race/vet | 通过 | 含 6 单帧感知测试；真实 Run/HTTP/PNG-JPEG 编码，截图和设备桥为桩 |
| [账号同步回归](<../tests/account_sync_regression.py>) | 66 场景、1125 断言、0 失败 | 编译 9 个真实业务类；内存 HTTP、Android/TLS/Engine/LAN 平台桩，历史/记忆用真实临时文件 |
| [ASR/LAN 回归](<../tests/asr_lan_regression.py>) | 10 组、11279 断言、0 失败 | LAN 使用真实环回 TCP、AES-GCM 和文件；ASR/Chat HTTP、JNI/Android 平台为桩 |
| [动作分支回归](<../tests/action_execution_regression.py>) | 51 断言、0 失败 | 从 exec 提取原生产分支执行，Intent/手势平台桩；没有复制第二套业务实现 |
| 完整 Swift 包测试 | 57 项、0 失败、0 跳过 | 含 15 新同步/账号测试和 10 个真实本地 TLS 服务端 E2E；生产 pinner，临时服务已回收 |
| Android 真实 SDK 构建 | 通过 | aapt2 / javac release 11 / d8 / zipalign / apksigner；SDK android-36 / build-tools 36.0.0，6 个离线 native 库 |
| iOS 真正 App 构建 | 通过 | SwiftUI + Core，generic iOS device、签名关闭；不是仅 swift test/typecheck，未运行模拟器或安装真机 |
| `git diff --check` | 通过 | 无空白/补丁格式错误，不代替行为测试 |

JVM 回归编译目标 Java 11、执行环境 JDK 21.0.2，不把目标字节码版本写成“JDK 11 真运行”。iOS 构建保留原有全方向 warning，未为了消除 warning 改产品方向策略。

Android 产物：[agent-lite.apk](<../agent-lite/build/agent-lite.apk>)，19,590,513 字节；本次 F01–F14 构建 SHA-256：

```text
ceeffafee3a4b5311d09da35198aaab7052694ec55488dc9a6fef28698bab90e
```

该路径下的文件此后又因授权存储损坏自愈（第 7 节第 8 条）重建过一次，当前 SHA-256 为
`41b24198757ff77d79ad4e99480ff55423d285de1c68da682de400f22e25fc30`。字节数两次相同是巧合——
6 个离线 native 库以 `-0` 不压缩，所以判据只有哈希。第 2 条的真机行为验收（44 + 15 项）跑在**当前
产物**上，设备 `pm path com.dsh.agentlite` 取出的 `base.apk` 与本地文件逐字节一致。
（两轮的中间产物另存 `/tmp/agent-lite-prev.apk` = 上表哈希、`/tmp/agent-lite-pre-logfix.apk` =
`7b28aa8e9bfeb1664e144b24bedfeb5b25e5f6a84f4b2c677e9cf6883697d200`，后者是自愈首次构建、日志文案
修正前的版本。）

关键复跑入口（在仓库根目录，除单模块命令外）：

```bash
make check
make drill      # 只跑数据副本迁移演练（§4.1 迁移与重开 / §4.2 支付竞争 smoke）
bash agent-unified/scripts/sync-sources.sh --check
(cd agentlite-server && go test -race ./... -count=1 && go vet ./...)
(cd agentd-go && go test -race ./... -count=1 && go vet ./...)
python3 tests/account_sync_regression.py
python3 tests/asr_lan_regression.py
python3 tests/action_execution_regression.py
(cd agentlite-server && go test -run '^$' -bench 'Benchmark(Storage|Gateway)' -benchmem -benchtime=300ms)
# Swift 的 10 个 E2E 需隔离 TLS 实例和证书；缺环境变量会跳过这 10 项
(cd ios && AGENTLITE_E2E_BASE="$LOCAL_TLS_BASE" AGENTLITE_E2E_CERT="$LOCAL_TLS_CERT" swift test)
# iOS App：不签名、不安装
(cd ios && xcodebuild -project App/AgentLiteIOS.xcodeproj -scheme AgentLiteIOS \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath .build/interface-audit-app \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -quiet build)
```

## 7. 仍保留的真实边界

1. 线上与设备验收尚未执行：后台杀进程、真实 TLS pin/CA 更新、LAN 断连、系统动作权限与 iOS Keychain/ATS 需在对应平台验证。
2. 零 root 真机验收**本机已完成**。零 root 需要**代码层与设备层两条独立证据同时成立**（本条原先只写了前者，2026-10-11 复核发现它在脚本运行期间并不成立，已改正并补上后者与缺口说明）。代码层：`root_mode=off` 会让 `RootShell.available(ctx)` 在探测前直接返回 `false`（`agent-lite/src/com/dsh/agentlite/RootShell.java:36-40`），于是三处动作调用点（`AgentA11yService.java:406/417/429`、`AgentEngine.java:2143/2225/2559-2561`）与截图分支（`AgentA11yService.java:540-547`）的 `if (RootShell.available(...) && RootShell.xxx(...))` 短路全部不成立，一律落到无障碍与 `takeScreenshot` 主路径上。真机另测到主进程与 guard 进程 uid 均为应用 uid（`u0_a577`）、无障碍服务已绑定（`capabilities=169`）；这两项只作旁证——root 加速走的是 fork 出去的 `su` 子进程，app 进程 uid 本来就是应用 uid，单看 uid 排除不了加速，所以它不能当判据。设备层：这台设备的 KernelSU 允许列表（`/data/adb/ksu/.allowlist`）里是 `bin.mt.plus`、`com.astraflow.tool`、`com.byyoung.setting`、`com.github.capntrips.kernelflasher`、`com.omarea.vtools`、`com.luckyzyx.luckytool`、`com.android.shell` 等，**不含 `com.dsh.agentlite`**；`/data/adb/ksu/log/sulog-*.log` 里逐条核对下来，`su` 请求全部是 `uid=0 euid=0 comm="sh"`（adb shell 发起），**没有一条来自应用 uid**（精确 grep `uid=10577` 的唯一命中，是我自己那条 grep 命令的 argv 文本本身）。应用既未被授权、也从未成功提权，于是即使 `root_mode` 落回 `auto`，`RootShell.available()` 探测用的 `exec("true")` 也必然失败——这比「代码不去试」更硬，但它绑定在这台设备上，换机需另行取证。**原先的缺口（已修）**：本条最初只写了代码层判据，而两个脚本的 `write_prefs` 是**整体覆盖**（`cat > PREF`）且 entries 里没有 `root_mode`，运行期这一项就被抹掉，`Prefs.rootMode()`（`Prefs.java:132` `getString("root_mode","auto")`）落回默认 `"auto"` —— 也就是说代码层判据在脚本运行期间并不成立，当时的结论其实是靠设备层那条撑住的。本轮已在两个脚本的 prefs 写入里显式加上 `root_mode=off`，并删掉陈旧快照让脚本重新采集真实用户态，使运行期条件与文档一致，也不再依赖「某台设备恰好没授权」。两条脚本另各加了一项运行期自检（`credential_real_device.py` 的 `P0`、`t03_t05_real_device.py` 的 `P-ROOT`），在推进阶段前读回 prefs 并断言 `root_mode=off`；自检首次运行就抓出 `read_prefs()` 的正则只认 `value="…"` 属性形式、读不到元素文本形式的字符串（`root_mode`/`base_url`/`model` 一律读回空串），已改为两种形式都认并做了往返一致验证。行为级链路已在真机跑通，两条脚本都用本机假端点（经 `adb reverse` 暴露，不出网）：
   - `python3 tests/credential_real_device.py` → **44 通过 / 0 失败**（含运行期零 root 自检 `P0`），覆盖 A 首启与登录态、B 自己的配置、C 账号通道、D 模型缺失、E 故障归因与不跨通道、F 设置页保存后的显式选择卡。断言含「自带凭据只发给自己的端点」「显式账号未登录时不得用旧 Key 兜底启动」「终态文案不泄漏测试凭据」「保存 ≠ 同意换通道」，并留 3 张终态截图（`.check-tmp/device/E1-自带402.png` 等）。
   - `python3 tests/t03_t05_real_device.py` → **15 通过 / 0 失败 / 1 跳过**（含运行期零 root 自检 `P-ROOT`），覆盖 T05a 任务代际（旧任务的收尾回调不得撤掉新任务：保活锁、覆盖层胶囊、通知三处都在）、T04a 证据分级（无证据 done 落「不确定」且不入库，隔离基线 0 → 0 字节；有证据路径仍走完成并入库）、T03a 代点（本机未弹「想要打开 XX」确认框，代点分支未触发故跳过，非缺陷）。
   两脚本主链路全程用户态，`su` 只用于布置前置与取证，结束均还原设备用户态（备份见 `/tmp/credential_device_prefs.bak`、`/tmp/t03_t05_device_prefs.bak`）。
   过程记录：首轮尝试被 ColorOS 锁屏挡住——锁屏对 `input` 合成事件免疫（滑动手势含从底部边缘起手、`keyevent 82/4/3`、`wm dismiss-keyguard`、`cmd window dismiss-keyguard`、显式 `input touchscreen`、root 下重试均无效，`locksettings set-disabled true` 报成功但不持久化），且锁定期间 `/data/data`（CE 存储）以密文目录项呈现、前置与取证都做不了；触摸注入被 keyguard 拒绝是 Android 的安全设计，不是脚本问题。手工解锁一次后两条脚本即全绿。`credential_real_device.py` 本轮补了解锁前置检查：锁屏下退环境码 2 并打印判据，不再让 `uiautomator dump` 超时打断脚本、也不再拿落空的前置状态凑出「通过」——本机锁屏实测输出「环境不可用：锁屏未解（mShowingDream=false mDreamingLockscreen=true）」、退出码 2。
3. 账务和设备日志没有压实/自动淘汰；账号记录索引与 ID 集合随数据规模增长，启动需要重放。
4. 指令结果仍依赖服务进程存活；Android 待 ACK 持久化不构成服务端持久队列，也不消除领取与执行之间的崩溃窗口。
5. 网关继续“检查后扣费”的既有计费语义，并发请求可能透支；单日志事务修复的是账务一致性，不是预扣额度或跨请求预算锁定。
6. 本次未接入真实付费模型上游；本地 HTTP/流式计量测试不证明所有供应商都会发送同样 usage 字段。
7. 事前授权层只有管理入口、没有动作级拦截（`docs/INTERACTION_LOGIC_SPEC.zh-CN.md` §10.2 的 DEV-4 已登记，此处补上代码级核对结果）：`AuthStore.grantAlways()` / `isAlways()` 与 `AgentEngine.requestAuth()` 在 `agent-lite` 与 `agent-unified/client` 两个模块里都**只有定义、没有任何调用者**，因此 `OverlayController.showAuth()` 从不执行，卡片上「单次允许/始终允许/拒绝」三个按钮也永远点不到；已接线的只有设置页的授权管理入口（`list()` / `revoke()`），而白名单没有写入路径、始终为空。按 S-3，该层防护缺位期间**必须不**对外宣传「事前授权拦截已生效」；`README.zh-CN.md` 与 `docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md` 中按已生效叙述的两处已改为「已实现但未接线」并指向 §10.2 的 DEV-4 登记。
8. 授权存储的损坏自愈**已补上并真机验证**。原先 `SharedPreferences` 解析失败会静默返回空 map，`AuthStore.list()` 无任何提示；设备实测 `shared_prefs/agent_auth.xml` 出现过半截覆盖损坏（XML 声明之后 `<map>` 起始段整段丢失、`</map>` 重复 10 次、末尾截断在半个键名，且缺少 `AtomicFile` 正常路径本应留下的 `.bak`）。该损坏与上一条的未接线状态无关——当前代码没有写入路径，残留应是早期版本所写。修复落在构造函数上：`AuthStore(Context)` 先调 `recoverIfCorrupt(ctx)` 再取 `SharedPreferences`（`agent-lite/src/com/dsh/agentlite/AuthStore.java`，`agent-unified/client` 同构逐字节一致）；判据 `parses(File)` 用 `android.util.Xml.newPullParser()` 走到 `END_DOCUMENT` 并要求首个 START_TAG 名为 `map`，任何 `Throwable` 一律判为不可解析；判定损坏后按 `agent_auth.xml.corrupt.<millis>` 改名保留现场，改名失败则 `delete()`，并写一条 `W AuthStore` 日志。之所以只能「加载前预检」而不能像 `FlowMemory.java:68-82` 那样 catch 异常：`SharedPreferences` 的解析发生在 Android 内部、异常不外抛，catch 不到。真机验证（设备装 `73703ef912bf619018edf73d7b4640b8`，即含该修复的构建）从设置页点「预授权管理」即触发，三条证据同时命中：`shared_prefs/` 出现 `agent_auth.xml.corrupt.<millis>` 且 md5 `f4e19c04c6c69de81d7e242ed8d8cd22` 与损坏原件**逐字节一致**、logcat 留下 `W AuthStore: 授权白名单文件损坏，已备份为 agent_auth.xml.corrupt.1791701504652，白名单按空表继续：/data/user/0/com.dsh.agentlite/shared_prefs/agent_auth.xml`、对话框正确显示「当前无预授权条目」。为确认可重复，把备份复制回原名再触发一次，产出第二份独立备份（`…504652`，md5 同样 `f4e19c04…`）——这同时证明时间戳命名在重复损坏下不会覆盖前一次现场（`FlowMemory` 用的固定名会）。两点说明：修好后 `agent_auth.xml` 会在下次写入前一直不存在，属正常（`SharedPreferences` 惰性落盘，不预先建空文件）；本次未验证写入路径，因该路径本就无调用者（见上一条）。
9. 必要的平台适配、模型/系统协议转换、图像压缩和发布复制保留；不把有真实职责的边界当作臭抽象。

本次完成的是有回归证据的源码收敛与本地交付。第 4 节的副本迁移与支付竞争验证已落进 `make drill` 并纳入 `make check`，零 root 真机行为验收也已在真机跑通（44 + 15 项断言全过）；本轮另补上授权存储的损坏自愈（第 8 条，真机验证）并按 S-3 把 `README.zh-CN.md` 与 `docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md` 中「事前授权已生效」的口径改为「已实现但未接线」（第 7 条）。后续首要动作是按第 4 节第 3 步切换生产二进制，再补齐第 1 条列出的后台杀进程、真实 TLS pin/CA 更新、LAN 断连与 iOS Keychain/ATS 等平台项；不是继续叠抽象或兜底层。
