# 项目全面体检报告（2026-09-13）

> ⚠️ **§2 P0 章节已过期（2026-09-13 11:30 复核）**：四项中有三项已被并行会话修掉但本文未更新——
> P0-1 root 注入已由 `ActionValidator` 白名单修复、P0-2 `ensureA11y` 已改追加式、agentd-go `go.mod` 已回 `go 1.24`。
> P0-3 的剪贴板残留与 P0-4 的 Activity 泄漏由 debug 会话修复。
> **请先读 `docs/DEBUG_REPORT_2026-09-13.zh-CN.md` §4 的逐项对照表，再动手。**
> 其余章节（§1 工程卫生、§3–§6 各组件审查）仍有效。

> **性质**：全仓静态审查 + 本机可执行验证（构建/编译/扫描/门禁实测）。未连接任何线上服务器，未改动任何源码。
> **审查窗口**：2026-09-13 00:42 → 11:16（+08:00）。**期间有并行会话持续开发**（内购系统、M3 回退、发布面泄露修复），本文所有「实测」均标注时点；与 02:00 后并行工作重叠的结论已按最终状态复核。
> **方法**：agent-client 全量深读（独立审查通道，P0 由主审逐条对照源码复核）；其余组件由主审定向审查（安全基本面逐项验证 + 全量编译/构建/门禁实跑）；工程卫生项（构建矩阵、git 状态、密钥泄漏、工具链、契约门禁）全部实测。
> **结论去向**：改进路线已摘录入 `PROJECT_OVERVIEW.zh-CN.md` §13；本文件是完整证据存档。

---

## 1. 构建与工程卫生实测（全部本机复现）

### 1.1 构建矩阵

| 组件 | 命令 | 结果（时点） |
|---|---|---|
| agent-client | `sh build-mac.sh` | ✅ 成功，66458 字节（00:52 实测，build-tools 36.0.0 / android-36） |
| agent-lite | `sh build-mac.sh` | ✅ 成功。00:52 实测 5921682 字节（当时含 M3）；并行会话 01:45 回退 M3 后 **183KB（31× 缩减）**，11:16 时点为 199306 字节（含内购三件套），密钥扫描 0 命中 |
| agentd-apk | `sh build-mac.sh` | ✅ 成功，16857 字节（01:0x 实测） |
| agentlite-server | `go vet ./...` + `go build`（自备 go1.24.6：`~/.agent-unified-go`） | ✅ 两轮通过：00:5x 一轮、01:23 并行改动（新增订单系统）后复测一轮，vet 均零告警 |
| agent-unified/server | 同上 | ✅ vet 零告警，编译通过 |
| agentd-go | `go build` | ❌ **本机无法构建**：`go.mod` 声明 `go 1.27`，本机工具链 1.24.6，`proxy.golang.org` 不可达（i/o timeout），工具链自动下载失败 |

**agentd-go 降级实测**：复制到 /tmp 后仅把 `go.mod` 的 `go 1.27` 改为 `go 1.24`，编译 + `go vet` 全部通过——代码**未使用任何 1.25+ 新 API**，版本抬升是无谓的。**修复**：把 `agentd-go/go.mod` 改回 `go 1.24`，与 `fetch-go.sh` 自备工具链（1.24.6）对齐，构建即可恢复。

### 1.2 git 工程卫生（P0 级风险）

实测演变：00:42 时 **56 M + 35 ??**（最后提交 9c79222）→ 并行会话 02:28 提交 `bc1fb13`（界面回归+删 M3）→ 11:16 时 **53 M + 21 ??**。脏区在收敛但**核心问题仍在**：

| 仍未跟踪内容（11:16 时点） | 影响 |
|---|---|
| **整个 `agentlite-server/`**（生产服务源码，含当晚新增订单系统） | 公开仓库里**没有**服务端；唯一异地副本在线上 `/srv/agentlite-server/src/`。本机磁盘故障 = 服务端源码永久丢失 |
| **整个 `agent-unified/`** | 整合发布工程不在版本控制内（`.e2e/`、`dist/` 已被其 .gitignore 排除，可直接入库） |
| **整个 `skills/`**（交互纪律 skill） | `software-interaction-logic` 只存在于本机 |
| agent-lite 的同步/账号类文件（`AccountClient/SyncClient/LanSync/TlsPin/…` 及当晚新增 `AkStore/AliyunV3Signer/BailianClient/PurchaseFlow/PurchaseActivity`） | **公开仓库上的 agent-lite 是账号同步之前的旧版**——克隆仓库得到的客户端连不上服务器 |
| `docs/DEVELOPMENT_MANUAL.zh-CN.md`（手册）、`docs/INTERACTION_LOGIC_SPEC.zh-CN.md`、`docs/HANDOFF_2026-09-13.zh-CN.md`、`docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md`、本报告 | 手册与规范不在仓库，新对话克隆后没有上下文 |
| `CHANGELOG.md`、`CONTRIBUTING.md`、`SECURITY.md`、`.editorconfig`、`.gitattributes`、`apply.sh`、`rollback.sh` | 工程规范文件缺席 |
| tests/ 约一半（`apk_readability.py`、`ui_text_guard.sh`、`lan_peer_sim/`、新增 `AliyunV3SignerTest.java`、`PurchaseFlowTest.java` 等） | 测试体系大半不在仓库 |

**修复方向**：
1. 立即分批提交（并行会话在 `docs/HANDOFF` §5 也提出了同样的「先决定：提交还是继续裸奔」）。源码/文档均无密钥（§1.3 审计 + APK 扫描结论），可安全入库；`竞赛材料/` 含义待用户确认后再决定。
2. 53 个未提交修改按主题拆分（保活 / 显示适配 / 手表 / 服务器订单 / 内购客户端）。
3. 建立提交纪律：每完成一个主题即提交。**本报告与手册更新也应入库**。

### 1.3 密钥与敏感文件审计

**源码层（00:5x 实测，通过）**：
- 模式扫描（`sk-`、`API_KEY=`、`Bearer`、`PRIVATE KEY`、`admin_token` 等）覆盖 go/java/py/sh/json/md/xml/conf，全仓 **0 命中**。
- `agent-lite/dev.local`（含 `API_KEY`）、`agent-unified/client/dev.local`：各自 .gitignore 忽略 ✅。
- `agent-unified/.e2e/tls*/server.key`：`.e2e/` 忽略 ✅。三个 `debug.keystore`：`*.keystore` 忽略 ✅。`_local/`（含 keystore）：`/_local/` 忽略 ✅。git 已跟踪文件中无任何密钥/keystore ✅。
- `tls/server.crt` 是自签证书公钥（客户端 pin 锚点），公开是设计使然 ✅。

**构建产物层（并行会话 02:0x 发现并修复，主审 11:16 复核确认）**：
- ★ 历史泄露：`build-mac.sh` 曾把 `dev.local` 的真实 DashScope Key 构建期注入 `DevConfig` → **明文 Key 进 APK**，波及 `agent-unified/dist/*.apk`（发布件）与 `agent-lite/build-m3/`（191M 历史产物）共 12 处。已全部删除，`build-mac.sh` 改 **fail-closed**（dev.local 含非空 API_KEY 即中止构建，实测退出码 1；`agent-unified/client` 同源副本同步修复）。主审 11:16 对现存全部 build/dist APK 复扫：**0 命中** ✅。
- ⚠️ **遗留动作（用户）**：该 Key 已随多个 APK 存在过且曾进入对话记录，**必须轮换**（见 `docs/HANDOFF_2026-09-13.zh-CN.md` §2.2）——清文件≠吊销。

**杂项**：`apply.sh`/`rollback.sh` 是 DSH 宿主插件补丁脚本（操作 `~/.dsh/`，快照+幂等，设计规范），与本项目无关——建议移到 `_local/` 或入库时在 README 说明，避免误解。

### 1.4 测试与工具脚本

- `tests/*.py`（9 个）+ `tools/normalize_layout_tokens.py` 全部 `py_compile` 通过（01:0x 实测）。
- 并行会话新增桌面 JVM 测试（`AliyunV3SignerTest` 47/47、`PurchaseFlowTest` 20/20，见工作日志）——免设备回归的正确方向。

---

## 2. agent-client（原版客户端）审查

> 独立通道全量深读；P0-1/P0-2 由主审对照源码逐条复核确认。

### P0（4 项）

| # | 现象 | 根因（证据） | 最小修复 |
|---|---|---|---|
| P0-1 | **模型输出经 `su -c` 拼接 → root 命令注入**。提示注入下 `{"action":"app","package":"x; rm -rf /data/..."}` 会以 root 执行；`launchUrl` 只删单引号，`$()`/反引号/分号全部存活 | `SandboxController.java:193/212/199-201/229-231` 把模型返回的 `pkg`/`url` 原样拼进 root shell 串；数据源 `AgentEngine.java:597/643`；执行面 `RootShell.java:44/62` | `AgentEngine.exec()` 的 `app`/`open_url` 入口加白名单：包名 `^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$`；URL 只放行 `https?/alipay/weixin/tel/mailto` scheme 并 base64 传递，命令串不含模型文本 |
| P0-2 | **`ensureA11y()` 整体覆盖系统无障碍服务列表**——用户已开的 TalkBack/银行/自动化类无障碍服务被静默关闭且无恢复路径 | `RootShell.java:117` 用 `settings put`（非追加）；调用点 `MainActivity.java:170/473` | 先 `settings get` 判重再追加 `cur+":"+comp`；旧值存 Prefs 并提供「恢复原无障碍配置」 |
| P0-3 | **`injectViaClipboard` 空指针 + 剪贴板残留**（密码/验证码任务后留在系统剪贴板，任何应用可读） | `AgentEngine.java:790` 在 `:795` null 判断**之前**调用 `aom.unsafeCheckOpNoThrow`；`:801` 写入后无清理 | null 检查前移；`runLoop` 的 `finally`（`:430`）加 `cm.clearPrimaryClip()` |
| P0-4 | **`MainActivity.sInstance` 静态持有且无 `onDestroy` 清理** → Activity 泄漏；更严重：`AgentEngine.staticLog()` 依赖 `sInstance`，后台/开机自启路径下日志**静默全丢** | `MainActivity.java:31/54`，全文件无 `onDestroy`；`AgentEngine.java:68` 吞异常调用 | 加 `onDestroy(){ sInstance=null; engine.setListener(null); }`；落盘能力搬进 `AgentEngine`（`getExternalFilesDir`），不经过 Activity |

### P1（8 项，摘要）

1. **停止按钮无法中断**：`stop()` 只置标志（`AgentEngine.java:119-122`），HTTP 挂起时最长 120s 无响应（`LlmClient.java:126`），无取消通道；`askUser` 等待期也不检查。→ `LlmClient` 持有 `HttpURLConnection` 提供 `cancel()`；readTimeout 降 30s。
2. **ask 弹窗期间手势回调死锁链**：对话框 `setCancelable(false)` 吃掉主线程点击，`dispatch()` 的 `latch.await(4s)`（`AgentA11yService.java:218`）误判「手势被取消」回灌模型。→ 弹窗改非模态或通知栏应答。
3. **ASR 线程直接 `performClick()`**（`MainActivity.java:324`）→ UI 竞态。→ 移进 `ui.post`。
4. **日志同步 IO 无上限无脱敏**（`AgentEngine.java:82-87/69-73`）→ 单例 BufferedWriter + 1MB 轮转 + 正则脱敏。
5. **每步 3–4 次全量节点树遍历且全在主线程 IPC**（`AgentA11yService.java:120-156`、`AgentEngine.java:399/446-456/711-713`）→ `waitForChange` 返回节点表复用；指纹改用 prompt hashCode；`collectNodes` 加 300ms 缓存。
6. **dispatchGesture 误报取消**：主线程繁忙时 4s 超时返回 false（`AgentA11yService.java:208-222`）→ 超时时返回 true + warn；超时降 1.5s。
7. **`KeepAliveService.updateState()` 是死代码**（`KeepAliveService.java:30-33` 全仓无调用）→ 状态栏红点永不出现；接上 start/finally。
8. **`RootShell.available()` 主线程阻塞 + 15s TTL 过短**（`RootShell.java:20/66`；`MainActivity.java:118/121/192/472` 调用）→ TTL 60–120s + 后台线程探测 + 结果缓存 Prefs。

### P2（11 项，摘要）

`LlmClient` 无重试/连接不保证关闭（`:122-140` disconnect 不在 finally）；畸形/空 content 直接终止任务（`:79` getString → `AgentEngine.java:337-340` return，应 optString+重试）；`ActionParser` 嵌套对象选错动作（`ActionParser.java:13/28-33`）；借用输入法进程死亡永不归还（`AgentEngine.java:830-834`，需 App.onCreate 补偿归还）；`VoiceRecorder` 并发写与 `getMinBufferSize` 负值（`:45/22-25`）；`sandboxActive()` NPE 判定（`AgentEngine.java:749-752`）；**沙盒会把真屏正用的应用整体迁走**（`SandboxController.java:187-202` findTaskId 不分屏 → 只迁移 VD 内 task）；API Key 明文 + base_url 无 scheme 校验（`Prefs.java:21/51`、`LlmClient.java:128` → 强制 https + Keystore 加密 + `usesCleartextTraffic=false`）；构建产物无自证（dex sha256 对比缺失）；构建脚本 d8 路径含空格被拆断（`build-mac.sh:30-32/61-62`）；`setTextOnDisplay` 节点回收顺序错误（`AgentA11yService.java:86-94/236`）。

### P3（5 项，摘要）

跨步状态散落不重置（`clipPending/rootPasteDone/borrowedIme` 等 → 收敛 Session 对象）；解析失败信息不回灌模型；**与 agent-lite 的必然重复**（SCHEMA/ActionParser/NodeInfo/看门狗/APP_NAMES 应抽 `protocol/` 纯 Java 共享包，纯 javac 构建零负担）；`KeepAliveService.specialUse` 无真实用途；主线程纪律不一致（dbg 后台线程 vs refreshA11y 主线程 su）。

### 结构性改进方向（agent-client）

1. 状态与视图解耦：Session 对象 + WeakReference + 生命周期钩子（P0-4/P3-1 共同根因）。
2. 外部输入进 shell 前过「能力网关」`ActionValidator`（白名单校验集中化，让 P0-1 在架构上不可表达）。
3. 「执行通道」抽象（Sandbox/Root/A11y 三 Executor），替代 `exec()` 里三份分支扩散。
4. 可观测性优先：统一 `Diagnostics` 单例（不依赖组件生命周期），每步记录感知耗时/遍历次数/LLM 耗时/执行通道。
5. 感知快照复用（一次动作周期一次 Perception）。
6. 构建产物自证（dex/jar sha256 + `verify-apk.sh`）。

---

## 3. agent-lite（轻量客户端）定向审查

> 覆盖度：安全关键文件逐行 + 抽样。该目录在审查窗口内被并行会话高频修改（内购三件套、M3 回退、发布面泄露修复），以下为 11:16 时点状态。

### 实测结论（质量整体良好，且当晚在持续变好）

| 项 | 结论 | 证据 |
|---|---|---|
| TLS pin | ✅ 实现正确：双重检查锁、仅覆盖同步主机、主机名校验保持开启；pin 应用失败时自签证书反正过不了系统 CA 校验（事实 fail-closed） | `TlsPin.java:30-94` |
| 局域网 P2P 加密 | ✅ 每报文 `SecureRandom` 新 nonce、AES-256-GCM 128 位 tag、时间戳 ±120s + (device_id, nonce) 滚动窗口抗重放、PBKDF2 与服务端参数对齐 | `LanSync.java:60-197` |
| 敏感守卫 | ✅ 四类关键词分组（支付>验证>协议>权限），命中即暂停接管；已知局限在 `docs/INTERACTION_LOGIC_SPEC` 附录 D 自我披露 | `SensitiveGuard.java:18-56`、`AgentEngine.java:426-431` |
| 内购凭据存储（当晚新增） | ✅ 教科书式：Keystore AES-256-GCM、随机 IV、char[]+wipe（签名器密钥全程不经 String）、无明文 getter、KeyPermanentlyInvalidated 自愈 | `AkStore.java`、`AliyunV3Signer.java` |
| 构建防泄露（当晚新增） | ✅ fail-closed：dev.local 含 API_KEY 即中止构建；并注释点明 BASE_URL 改写=密钥外送的暗洞 | `build-mac.sh:46-99` |
| 震荡看门狗（当晚新增） | ✅ 6 步内屏幕状态≤2 且动作≤2 → fail，堵「设置页来回跳」死循环 | `AgentEngine.java:309`（工作日志 01:22） |

### 待改进项

- [P2] 账号令牌仍明文存 SharedPreferences（`Prefs.java`）——建议复用 `AkStore` 模式封装 `TokenStore`，代码已在仓内，迁移成本低。
- [P3] 与 agent-client 协议层重复（SCHEMA/ActionParser/NodeInfo/看门狗/APP_NAMES 两份维护）——抽 `protocol/` 共享包。
- [P3] `build-mac.sh` 硬编码 `SYNC_URL`（另有 `tests/verify_lan_p2p.sh:22`），与 `unified.conf`「单一真相源」声明冲突——地址变更需改 3 处，建议从 unified.conf 生成。
- [待确认→P1] 内购改版的设计缺口 4 处（返回检测已定 ListApiKeys 方案、回滚语义、AK 录入路径、use_gateway 口径）与「单模型收敛 vs 资源包按模型名匹配」的架构冲突，见 `docs/HANDOFF_2026-09-13.zh-CN.md` §1.1/§4——这些是并行工作流的在途事项，不重复展开。

---

## 4. Go 服务端（agentlite-server / agent-unified/server）定向审查

> 覆盖度：安全基本面逐项验证 + 路由清单 + 两轮编译实测。01:23 并行改动（订单系统）后复测通过。

### 实测结论（基本面扎实）

| 项 | 结论 | 证据 |
|---|---|---|
| HTTP 超时 | ✅ 四项全设：ReadHeader 10s / Read 30s / Write 60s / Idle 120s | `main.go:132-135` |
| 请求体上限 | ✅ `http.MaxBytesReader` + 超限错误处理 + drainAndClose 保连接复用 | `api.go:45-51/688-691` |
| 恒时比较 | ✅ 账号密钥哈希 `subtle.ConstantTimeCompare`；管理令牌走 `subtleCompare` | `account.go:267`、`api.go:162-170` |
| PBKDF2 | ✅ 210000 轮 + 随机盐 + crypto/rand 全程 | `account.go:20-58` |
| 落盘原子性 | ✅ tmp+rename | `store.go:647-651` |
| 订单记账口径 | ✅ 以兑换码实际 units 为准，小码付大套餐不凭空产生额度（并行会话 8 项负向用例验证） | `order.go` 头注释 + 工作日志 01:33 |

### 发现

- [P1] **手册 §7 接口清单落后代码 5 条路由**：`GET /api/v1/packs`、`POST /api/v1/orders`、`GET /api/v1/orders`、`POST /api/v1/orders/cancel`、`POST /api/v1/orders/pay`（`main.go:102-106`，配套 `order.go`）。部署该版本后须同步手册（本次已先行补录并标注「待线上确认」）。
- [P2] 额度「先检查后扣减」并发可小幅超支（手册 §9.3 已知悉；规模化前改预扣+结算）。
- [P2] `config.json` 服务端原样下发不滤键（防线在客户端 `CONFIG_WHITELIST`；手册 §5.3 已披露）。
- [P2] Go 侧**零单测**：额度账本/订单状态机/PBKDF2/限流键全是最值得表驱动单测的纯逻辑——手册 §9.4 两个计量 bug 恰是「mock 验协议形状」漏掉的。
- [P3] 记录流 O(n) 全量扫描 + 无淘汰（手册 §6 已知悉）。

---

## 5. agentd-apk / agentd-go / agent-unified 定向审查

### agentd-go

- [P1-安全] **WebUI 与 a11y 桥完全无鉴权，而进程以 root 运行**：WebUI 路由 `/api/config`（读写，含 LLM Key）、`/api/task`、`/api/stop`、`/api/status`、`/api/screen`（屏幕内容）、`/api/test`、`/api/asr`（`main.go:322-335`）全部无 token；a11y 桥 `http://127.0.0.1:8081/nodes`（`internal/agent/agent.go:313`、`internal/device/device.go:80`）同样裸奔。**Android 上 loopback 不是安全边界**——设备上任意 App 都能连 localhost：偷 `/api/config` 里的 Key、经 `/api/screen` 窃屏、经 `/api/task` 以 root 指挥手机。
  **修复**：启动时 `crypto/rand` 生成 token 存 0600 文件，所有 `/api/*` 与 8081 桥校验；更彻底改 Unix domain socket。
- [P0-工程] `go.mod` 声明 `go 1.27` 但未用任何 1.25+ API → 本机不可构建。改回 `go 1.24`（实测通过，见 §1.1）。

### agentd-apk

- ✅ 构建通过；a11y 服务 `exported=false` + `BIND_ACCESSIBILITY_SERVICE` 权限保护正确（`AndroidManifest.xml:27-39`）。
- [P3] 存在 `exported=true` 组件（`AndroidManifest.xml:19`，launcher Activity 属正常，新增组件需守住显式声明纪律）。

### agent-unified

- [P1-流程] **契约门禁当前为红**：`verify-contract.py` 25 通过 / 1 失败（01:0x 实测）——失败项「零改动」：agent-lite 原项目（当晚内购文件）尚未同步进 `agent-unified/client/` 副本。属在途开发正常状态，但**下次构建前必须先 `sync-sources.sh` 再过门禁**，否则要么构建旧代码、要么跳过门禁。
- ✅ 门禁自证脚本、e2e-local、发布脚本齐备；`deploy-server.sh` 默认 dry-run 正确（rsync 前置缺失手册 §4.1 已披露）。
- [P2] `agent-unified/client` 与 `agent-lite` 平行副本归属未定（`docs/HANDOFF` §4.5 同样提出）——内购改造只动了 `agent-lite`，**开工前先定谁是真身**，否则会改一半。

---

## 6. 测试体系与文档一致性定向审查

### 测试体系

- ✅ 全部 py 语法通过；`ui_text_guard.sh` 静态+行为双层断言设计良好；新增 JVM 测试（47/47、20/20）是免设备回归的正确方向；`apk_readability.py` 门禁已转绿（对比度 23→0，并行会话修复且顺手修了门禁自身的陈旧目标路径隐患）。
- [P2] 设备序列号硬编码（`tests/agent_test.py:13-15`：oneplus/平板/模拟器）——换机即断；建议抽 `tests/devices.json`（gitignore）+ example 模板。
- [P2] 无 `make check` 类聚合入口——建议把「Go 单测（待补）+ verify-contract + ui_text_guard + ui_widget_guard + 两个 JVM 测试」聚合为一个免设备检查命令，后续可挂 CI。
- [P3] `results-*.jsonl` 归档无消费方。

### 文档一致性

- [P1] **PROJECT_OVERVIEW.zh-CN.md 主体滞后**：正文标「最后更新 2026-09-06」，未收录服务端/整合工程/agentd/账号同步/内购/保活/手表等 09-06 后演进（工作日志节除外，那是 09-13 新增的）。本次新增 §13 指针，正文刷新列入路线图。
- [P1] 手册 §7 落后 5 条路由（见 §4）。
- [P2] README（中英）+ PROJECT_OVERVIEW + 手册 + 各子 README 多轨维护，缺「谁负责什么」的文档地图。
- [P2] 手册与 `agentlite-server/README.md` 对「远程配置白名单」落点口径不一（CHANGELOG 已登记，留待统一）。
- ✅ `docs/HANDOFF_2026-09-13.zh-CN.md` 交接质量很高（卡点、决策、诚实记录），建议固化为跨会话交接的标准做法。

---

## 7. 汇总问题清单与修改路线图

### 7.1 P0（立即处理，按顺序）

> ✅ **2026-09-13 11:29 复检：四项全部完成**——`69ddf0b`（#2+#3，ActionValidator 白名单网关双重强制 +
> ensureA11y 先读后追加，dex 验证进包）、`cf04f6c`（#4，编译+vet 通过）、`bd6dbb0`/`0cc7094`/`90c2ffb`（#1，
> 服务端/内购/文档规范分批入库）；另 `2bc7683` 顺手消红 P1-4 契约门禁（sync-sources 后 26/26）。
> #3 的「恢复按钮」降级为可选（追加式修复后不再有可破坏面）。#1 中 `竞赛材料/` 仍留盘不入库（待确认）。

| # | 问题 | 位置 | 动作 |
|---|---|---|---|
| 1 | **核心工程未入版本控制**：服务端/整合版/skills/手册/测试大半未跟踪，53 文件未提交——单机磁盘故障 = 服务端源码仅存线上副本 | git 工作区 | 分批提交（§1.3 已审计无密钥）；`竞赛材料/` 待用户确认 |
| 2 | **root 命令注入**：模型输出直接拼 `su -c` | `agent-client SandboxController.java:193/212` | 入口白名单（包名正则 + scheme 白名单 + base64 传递） |
| 3 | **无障碍服务列表整体覆盖**：用户已有服务被静默关闭 | `agent-client RootShell.java:117` | 先读后追加 + 恢复按钮 |
| 4 | agentd-go 本机不可构建（`go 1.27` 无谓抬升） | `agentd-go/go.mod:3` | 改回 `go 1.24`（实测通过） |

### 7.2 P1（本迭代内）

1. **轮换 DashScope Key**（用户动作）：曾进 APK 发布件与对话记录，清文件≠吊销（`docs/HANDOFF` §2.2）。
2. agent-client：停止按钮无法中断（cancel 通道 + readTimeout 30s）；`sInstance` 泄漏与日志静默丢失；ask 弹窗手势死锁链；`updateState()` 死代码接线；`available()` 主线程阻塞。
3. agentd-go：WebUI/a11y 桥加鉴权（root 进程裸奔 localhost）。
4. agent-unified：构建前先 `sync-sources.sh` 消红门禁；**定夺 `agent-unified/client` 与 `agent-lite` 谁是真身**。
5. 服务端部署后补手册 §7（5 条订单路由）。
6. PROJECT_OVERVIEW 正文全面刷新（09-06 → 09-13 现状）。
7. agent-lite token 明文 → Keystore（复用 AkStore 模式）。

### 7.3 中期（1–2 个迭代）

1. agent-client P2 批量修复（LlmClient 重试/finally、ActionParser 括号配平、IME 借还补偿、VoiceRecorder 竞态、沙盒迁移分屏判定、base_url 强制 https、构建产物 sha256 自证、d8 空格路径）。
2. 抽 `protocol/` 共享包统一两客户端协议层。
3. Go 服务端表驱动单测（额度账本/订单状态机/PBKDF2/限流）+ `make check` 聚合免设备检查。
4. agentd 定位决策：与 agent-lite RemoteCommand/SyncClient 功能重叠，明确「整合进主线」还是「独立 root 侧线」。
5. 测试设备序列号抽离；SYNC_URL 三处硬编码收敛到 unified.conf 生成。

### 7.4 长期（方向性）

1. **执行通道抽象**（Sandbox/Root/A11y 三 Executor）+ **能力网关 ActionValidator**——让注入类问题在架构上不可表达。
2. **可观测性**：统一 Diagnostics + 每步指标（感知耗时/遍历次数/LLM 耗时/通道），先可测再优化。
3. 服务端记录流索引化 + 淘汰策略。
4. CI 化：`make check` 进 GitHub Actions（免设备部分），真机测试维持本机 + results 消费脚本。

### 7.5 审查盲区声明

- agent-lite / Go 服务端 / agentd / 测试文档四通道原计划全量深读，因子代理送达异常改为主审定向审查 + 全量编译/构建/门禁实测；agent-client 为全量深读（28 条发现，P0 逐条复核）。
- 并行开发整夜进行：01:23 后的新文件（`order.go`、内购三件套等）仅做安全导向抽查；02:00 后并行会话的修复（发布面泄露、震荡看门狗、可读性门禁）已按其工作日志采信并抽验关键项（APK 扫描、fail-closed、门禁状态）。
- 未覆盖：真机行为回归、线上服务实测（刻意不连生产）、FlorisBoard 合并 dex 运行期表现、`竞赛材料/` 与 `_local/` 内容。
