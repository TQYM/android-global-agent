# 更新日志（Changelog）

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased]

### Changed（模型通道切换，2026-09-15）

- **额度网关上游切换到 DeepSeek**（dashscope 两条通道上游均欠费导致产品不可用）：服务端新增
  `-upstream-model` / `AGENTLITE_UPSTREAM_MODEL`，并在转发前用 `gatewayConfig.adaptRequest()` 做两件事——
  **模型名映射**（客户端仍填 `qwen3.5-omni-plus`，服务端改写为 `deepseek-flash`，用户端零改动）与
  **关闭思考链**（上游为 DeepSeek 时注入 `{"thinking":{"type":"disabled"}}`）。
  实测：`thinking.type=disabled` 与 `reasoning_effort:"none"` 有效，`enable_thinking:false` /
  `chat_template_kwargs` 无效；不关闭时思考会吃满 `max_tokens` 导致 `content` 为空串。
  客户端 `LlmClient` 在 `base_url` 含 `deepseek` 时同样关闭思考（直连场景）。

### Security

- **引擎不再替用户同意服务条款/隐私政策（2026-09-15，手册 §16.2 P0-2）**：删除 `autoAgreeTerms()` 的代点逻辑
  （识别到条款弹窗后替用户点「同意并继续」、代勾「已阅读并同意」＝替用户作出不可逆授权），改为只检测并把控制权
  暂停交还用户，由用户自行确认；SPEC-UX-001 门禁新增「条款不得代点」断言。
- **首屏不再索取权限（SPEC-UX-001 §5）**：启动期不再一次性申请录音 + 通知权限；录音权限延迟到第一次点「语音」、
  通知权限延迟到第一次真正跑任务，且各只问一次。

### Added（续）

- **SPEC-UX-001 零门槛守则 + 门禁**：`docs/SPEC_UX_001.zh-CN.md`（10 条 + 验收方式 + 三条凭据路径），
  `tests/spec_ux_001_guard.py` 并入 `make check`（当前 15 通过 / 0 失败 / 1 需真机）。
- **动作词表单一事实源**：`ActionSchema.java`（`SCHEMA_VERSION = 2026-09-14.1`、22 执行动作 + 3 终态动作），
  把此前「exec 已实现但词表没写」的 `back`/`home` 注册进词表；未注册动作只回灌一次、连续 3 次即终态。
- **失败可恢复**：`UiText.nextStep()` + 失败卡片三段式（人话归因 → 下一步 → 重试 / 为什么 / 关闭）。
- **无凭据前置拦截**：无可用凭据时**不发模型请求**，弹出解决卡（登录 / 打开设置 / 稍后）。
- **done 最小复验**：核对 `app`/`setting` 是否真的把前台换成了目标应用；缺证据时追问一次；
  `app`/`setting` 未生效即回灌要求换策略。
- **日志异步化**：`EngineLog.java`（非阻塞入队、丢弃计数、1 MB 轮转、凭据脱敏、多写入者共享）。
- **资源硬上限**：对话历史每步统一裁剪（原先多条 `continue` 分支会绕过）、任务 20 分钟上限、录音 30 秒上限。

### Fixed（续）

- **上游错误归因分通道**：欠费/密钥失效/无权限/模型不存在分别归因，并区分「账号额度网关」与「自备配置」；
  两条通道都失败时合并说明（真机实测输出「两条模型通道都不可用：…」），技术原文只进日志。
- **自备 Key 失效自动改用账号额度**：自备配置被上游拒绝且换通道有意义时自动切换（402 额度耗尽仍不回退）。

- **agentd 本机 API 与 8081 a11y 桥全面收敛（2026-09-14，手册 §15.1 P0-1）**：agentd 改为**只绑 `127.0.0.1`**
  （原 `":"+port` 等价于 `0.0.0.0`——同一局域网内任何设备都能读配置里的 API Key、读屏、提交任务），`/api/*` 统一要求
  随机令牌（`0600` 落盘、常量时间比较、令牌为空时 fail-closed），并校验 `Host` 必须指向回环（挡 DNS 重绑定）、
  `Origin` 必须同源（挡浏览器 CSRF）、方法白名单（`/api/test`、`/api/asr` 等有副作用的只收 POST）、POST 强制
  `application/json`；WebUI 增加「访问令牌」输入（只存 localStorage）并统一封装请求，截图改 `fetch`+blob（`<img src>`
  无法携带请求头）。`agentd-apk` 的 AccessibilityService 同样从 `0.0.0.0` 收到回环，并新增 `X-Agentd-Bridge` 令牌
  （令牌写在 APK 私有目录，同机其他 App 读不到），agentd 以 root 读取携带、读不到时降级为仅回环（不破坏旧 APK）。
- **agent-lite 账号令牌改为 Keystore 加密存储（手册 §15.1 P1-2）**：新增 `TokenStore`（AES-256-GCM，Keystore 别名与
  AK 分离，进程内缓存），`Prefs.accountToken()` 首次读取执行「加密落盘 → 删除明文」一次性迁移（**只有加密成功才删**，
  失败则保留旧值下次重试）；`saveAccount` 改返回成败，`LoginActivity` 在安全存储不可用时明确报错（绝不明文降级）；
  `AndroidManifest` 关闭备份导出（`allowBackup=false` + `fullBackupContent=false`）。

### Fixed

- **agentd 任务并发与配置保存（手册 §15.2-1/4）**：运行位改用 `CompareAndSwap(false, true)` 一步占位（并发请求不再
  同时启动 Runner 争用输入/截图/日志），释放统一交给 `defer`；未配置 API Key 时 fail-fast；配置保存改为**锁内**生成
  不可变快照、锁外临时文件 `fsync`+`rename` 原子落盘，落盘失败回滚内存；「保持原 Key」判据从
  `strings.Contains(key, "...")` 换成显式哨兵 `__AGENTD_KEEP__`，且真实 Key 不再回流浏览器。
- **停止任务即时生效（手册 §15.2-2）**：agent-lite `LlmClient` 新增 `cancel()`——断开进行中的连接并置取消标志，
  取消不再触发网关回退、不再被误报成「模型调用失败」（新增 `CancelledException`），`AgentEngine.stop()` 随即断开；
  agentd `Runner` 接入任务级 context，`/api/stop` 取消后进行中的模型请求立刻断开，不必再等最长 180 秒读超时。
- **HTTP 资源释放与响应上限（手册 §15.2-3）**：`LlmClient.postOnce` 的所有路径（含 402、JSON 解析失败、取消）都走
  `finally` 断开连接，输出流显式关闭；响应体设 1MB 上限，避免异常上游耗尽内存与 fd。
- **配置与输入校验（手册 §15.2-5）**：`base_url` 强制 HTTPS（仅 `127.0.0.1`/`localhost` 允许明文）、拒绝内联凭证与
  越界端口；`port` 限 1..65535（空值不再拼成 `":"`）；`/api/asr` 的 `format` 改白名单（wav/mp3/m4a/webm/ogg）。

### Fixed（真机验证发现，2026-09-14）

- **Keystore 加密在真机上必然失败**：Android Keystore 生成的密钥默认 `randomizedEncryptionRequired=true`，**拒绝调用方自带的 IV**，原实现手工造 IV 传入 `Cipher.init` 会抛 `InvalidAlgorithmParameterException`（令牌迁移因此在真机首次失败）。修复：加密不传 IV，改用 `cipher.getIV()` 取 Keystore 生成的随机 IV；`AkStore`（内购 AK 存储）是同源代码，一并修掉。
- **停止任务仍要等满 connect 超时**：`HttpURLConnection.disconnect()` 只能中断**已建立连接**的请求，连接阶段（地址不可达/DNS 慢）不响应——黑洞地址实测点停止后仍等 13 秒才结束。修复：模型请求交给网络线程执行，主循环每 200ms 轮询结果并检查取消标志，规划请求同样登记可取消；同一场景复测 13 秒 → 约 1 秒。

### Added

- **`make check` 免设备聚合门禁（2026-09-14，手册 §15.3 P2-1）**：根 `Makefile` 一条命令跑 Go 单测（agentd +
  agentlite-server）、两端契约门禁、UI 文案/控件守卫与桌面 JVM 回归（AliyunV3Signer / PurchaseFlow），实跑全绿；
  agentd 首次有单测（鉴权矩阵、Host/Origin/方法/Content-Type、令牌文件权限与复用、配置合并与原子落盘、停止取消、
  `ChatCtx` 取消即时性）。
- **项目全面体检（2026-09-13）**：`docs/PROJECT_REVIEW_2026-09-13.zh-CN.md` ——构建矩阵（三 APK + 两 Go 服务实测）、
  git 工程卫生、密钥审计（源码扫描 + APK 复扫）、各组件 P0–P3 发现（含 file:line 与最小修复）；改进路线摘录入
  `PROJECT_OVERVIEW.zh-CN.md` §13；开发手册 §7 补录套餐/订单 5 条路由（本地代码为准，标注待线上确认）、§11 收录体检报告。
- **agentlite-server 首次入库**：账号（PBKDF2）/信息同步/额度网关（OpenAI 兼容）/兑换码/跨设备指令/套餐订单
  （`order.go`，2026-09-13 01:23 落盘）——此前生产服务源码仅存本机与线上 `/srv/`，磁盘故障即丢失。
- **内购三件套与配套测试**：`AkStore`（Keystore AES-256-GCM）/`BailianClient`/`PurchaseFlow` +
  `tests/AliyunV3SignerTest.java`（47/47）/`tests/PurchaseFlowTest.java`（20/20）/`tests/verify_create_apikey.sh`
  （AK 仅走环境变量）；`agent-lite/build-mac.sh` 改 fail-closed——dev.local 含非空 API_KEY 即中止构建
  （修复明文密钥入 APK 的发布面泄露，历史产物已删净并复扫 0 命中；**该 Key 仍需轮换**）。
- **工程规范文件入库**：`.editorconfig`、`.gitattributes`、`CONTRIBUTING.md`、`SECURITY.md`、`skills/project-work-log/`、
  交接与设计文档（`docs/HANDOFF_2026-09-13.zh-CN.md`、`docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md`）、测试工具
  （`tests/android_ui.py`、`tests/lan_peer_sim/`、`tests/verify_lan_p2p.sh`）。
- 开发手册 `docs/DEVELOPMENT_MANUAL.zh-CN.md`：把**项目专用服务器**（阿里云 ECS `8.133.225.105`）的连接方式与线上事实
  收进一处，供其他对话直接接手功能实现——SSH 别名 `aliyun-ecs` 与主机指纹、服务端点（`https://8.133.225.105:18443`、
  `/health` 免鉴权、局域网 `18444`）、TLS 自签证书与客户端 pin 的联动关系、线上路径表与启动参数、两条部署流程与运维速查、
  **逐条取自 `main.go` 的接口清单**（含服务端 README 尚未收录的跨设备指令通道 `command.go`），以及连接/部署/计量/客户端各类已踩坑与红线。
  凭据只写「去哪里取」不写值（本仓库为公开仓库），并明确划分与 LLM 中转站（`23.185.168.190`）的边界。
  编写时点实测发现并已记录：**服务器未装 `rsync`，`deploy-server.sh --yes` 会在第 ② 步失败**（失败安全，不弄坏生产，
  装 `rsync` 或走 `tar | ssh` 即可），且该脚本第 ⑤ 步会**自动打生产环境跑冒烟**；线上与本地源码仅差 5 个文件第 3 行的
  `// # ` 注释前缀（各 2 字节，语义等价）。
- **文档勘误（远程配置白名单的落点）**：`agentlite-server/README.md` §7 把「远程配置白名单」写在**服务端安全设计**标题下，
  容易被读成服务端在拒绝越权键；核对代码后确认——服务端 `store.Config()` **原样下发** `config.json`、不做键过滤
  （`max_steps` 等键名在整个 Go 源码中零出现），真正的白名单是客户端 `SyncClient.CONFIG_WHITELIST`
  （`agent-lite/src/com/dsh/agentlite/SyncClient.java:68`）。已在开发手册 §5.3 / §9.3 写明，
  并点出「`GET /api/v1/config` 会原样回显 `base_url`/`api_key`，不能拿回显判断生效」这一实践陷阱。
  README 的表述本身未改动（属既有文档口径问题，留待后续统一）。
- **开发手册经独立复核并勘误**：由独立子代理对线上与本地逐条只读复核（60+ 项断言，未重启服务、未部署、未打印任何凭据值），
  确认约 95% 的事实精确吻合（含 §3.1 源码漂移「三文件逐字节相同 + 五文件仅第 3 行不同」、
  `-register-rate`/`-register-window` 默认值、§7 全部 17 条路由的路径/方法/中间件、证书 md5 与 SAN/有效期）。
  据此修正 6 处实质错误 + 2 处措辞：**`POST /api/v1/device/bind` 只走 `authMW`**（`X-Device-Id` 仅在 `authDeviceMW` 内读取，
  设备身份取自 body 的 `fingerprint`）；**`AGENTLITE_UPSTREAM_URL` 并不在 env 文件里**（生效值来自 `main.go:59` 编译期内置默认，
  env 文件实测仅两个变量）；**启动参数表以「环境变量」列有值为准**（`-register-rate`/`-register-window`/`-version`
  没有 `envOr(...)`，不存在对应环境变量）；**`python3` 是必需依赖**（`smoke-test.sh` 的 JSON 解析与 `verify-contract.py` 都要它）；
  **三处限流键不同**（注册/登录按 IP，兑换按账号）；§4.1 标题不再称「推荐路径」并在开头直陈 rsync 阻塞。
- 整合版工程 `agent-unified/`（v0.7.0）：把 `agent-lite`（Android 客户端）与 `agentlite-server`（Go 云端服务）整合为一套系统——
  - **零改动保证**：两端为原项目逐字节副本（`sync-sources.sh --check` 每次构建复验），整合层只做配置下发、证书下发、契约校验与发布打包；
  - **单一真相源** `unified.conf`：地址/端口/版本映射一处维护，经两端**原生注入点**生效（客户端 `dev.local`、服务端启动参数），不改任何代码；
  - **契约门禁** `verify-contract.py`（10 组 26 项）：路由覆盖、网关边界、局域网密码学参数、字段、错误码、远程配置白名单红线、记账单位，全部从两端源码抽取比对；
  - **门禁自证** `selftest-verifier.sh`：注入 6 类真实漂移（KDF 漂移、忘同步证书 pin、删路由、接口拼错、白名单放宽、错误码改名），逐一确认会被拦住并定位到正确分组；
  - **本地端到端** `e2e-local.sh`：现编服务端起真实进程跑通全部冒烟断言，并用**客户端真实的 `TlsPin.java`** 验证证书 pin，含两个负例（换证书必须失败、不用 pin 必须失败）；
  - **构建硬断言**：交付新鲜度（源码不得比 APK 新）+ 解 dex grep 类描述符（APK 字节数不可信）+ `aapt2` 版本映射核对；
  - **一键部署** `deploy-server.sh`（默认只打印计划，`--yes` 才动线上）、`gen-cert.sh`（SAN 固化）、`release.sh`（一个 tar.gz = 完整一套系统）。
- 软件交互逻辑 Skill（`skills/software-interaction-logic/SKILL.md` v1.0.0）：AgentLite 引擎的「感知 → 决策 → 执行 → 复验」运行时决策纪律，含动作四级分层、降级链、循环治理、敏感红线（模型侧主防线 + 引擎守卫现状披露）、终态语义、能力边界与反模式清单，逐条锚定 `AgentEngine.SCHEMA` 与主循环实现。
- 软件交互逻辑规范守则（`docs/INTERACTION_LOGIC_SPEC.zh-CN.md` SPEC-INTERACT-001 v1.0.0）：工程侧上位规范——交互不变量（INV-1~7）、动作契约元规则（M-1~9）、决策优先级（D-1~7）、降级容错、看门狗与守卫失效窗口披露、七类终态归因、双防线安全模型、可观测性、量化质量门禁（含回滚判据与机器可执行一致性核对 §12.4）、变更分级与 Waiver 流程、偏差登记表（附录 D，DEV-1~9）。
- 上述两文档经 2026-09-12 独立评审（NEEDS WORK：4 Blocker/14 Major/16 Minor）后全面修订：事实陈述按 as-built 校正（敏感守卫仅接线 2/4 组、生态三档未生效、事前授权未接线、自适应等待实为前置 60ms+80ms 轮询+600ms 上限、看门狗空节点屏豁免、`back`/`home` 未注册词表等），规范要求与实现差距改由附录 D 偏差登记追踪。

- **远程账号与信息同步服务**（`agentlite-server/`）：Go 纯标准库零依赖单二进制，含
  本地账号体系（账号名 + 账号密钥，PBKDF2-HMAC-SHA256 210000 次，不涉及手机号/短信）、
  **账号级记录流**（记录按账号而非按设备归档，每条带来源 `device_id`，转发场景可用
  `origin_device_id` 声明真实来源且仅接受同账号设备）、局域网会合、额度账本与一次性兑换码、
  以及 OpenAI 兼容**额度网关**（转发 DashScope 并按响应 `usage` 精确扣减账号额度，
  额度耗尽返回 402）。详见 `agentlite-server/README.md`（含局域网线协议逐字节规格）。
- **客户端账号接入**（`agent-lite/`）：新增 `AccountClient` / `LoginActivity` / `SettingsActivity` /
  `SyncClient` / `LanSync` / `TlsPin` / `ApiException` / `UiForm`。登录后同账号多设备共享
  历史与记忆；同内网时自动 P2P 直连（对称密钥两端各自派生，AES-256-GCM 封装，
  服务器不经手密钥）；自签服务端证书以构建期生成的 `PinnedCa` 做证书 pin（无需 Context）。
- **面向用户文案合规门禁** `tests/ui_text_guard.sh`（守则 §12.4 第 5 项）——两层断言：
  - **静态**：终态文案不含异常原文拼装、`failTask` 走唯一加工点、映射表覆盖 `exec()` 全部 21 类标签与
    `SCHEMA` 全部 22 个动作名且兜底不回原文（枚举式——新增标签忘补文案会直接报出缺哪个）；
  - **行为**：`tests/ui_text_test/UiTextTest.java` 在桌面 JVM 上编译运行 `UiText`（该类刻意不依赖
    `android.*`，与 `TlsPin` 同款取舍），用真实异常原文与全部内部标签断言真实输出。
    这层测试抓到了静态检查漏掉的**三处真实缺陷**：JVM 英文残留（`Attempt to invoke … on a null
    object reference`）会当成归因展示、技术串判定看错阶段导致 `java.lang.IllegalStateException: …`
    被原样透出、裸动作名 `app`（`label = kind` 兜底路径）没有文案。
- **同步 opt-in 控件（守则 S-4：同步必须默认关闭且显式 opt-in）**：此前 `Prefs.syncEnabled()` 默认 `true`
  却**没有任何 UI 入口**（`setSyncEnabled()` 无调用点），隐私开关只存在于代码里。现于设置页「信息同步」
  卡片加入 `swSync` 开关（M3 MaterialSwitch）+ 状态与说明行，并写明三态默认：用户显式拨过以开关为准；
  从未拨过且未登录 → 关；从未拨过但已登录 → 视为登录时已授权（避免升级后静默中断既有用户的同步）。
  由于 `SyncClient` 本就要求已登录才上行，第二/三种情形相对旧默认**行为完全一致**。
- **控件类型一致性门禁** `tests/ui_widget_guard.py`（守则 §12.4 第 6 项）：对布局中写全限定类的控件，
  用 javap 从真实 classpath 走继承链，确认 Java 侧转型类型是同类或祖先。它守护「布局换成 Material 控件、
  Java 转型没跟上」这类只在真机点开对应页面才暴露的崩溃，并已用两处变异（全限定与简单名错误转型）
  验证过确实会拦住。
- **面向用户文案抽取为纯类** `agent-lite/src/com/dsh/agentlite/UiText.java`（T-4 的 `uiReason()` 与
  O-3 的 `friendlyLabel()`）：使「只在真机屏幕上才看得见」的规则变成可自动验证的纯函数；
  `AgentEngine` 只保留走 android 侧的两件事——应用显示名解析与「未映射标签记 logcat」。
- **字体可读性检测工具**：`tests/font_scale_sweep.sh`（在 6 个系统字体档位下重走各页面并取样）+
  `tests/font_scale_readability.py`（按「声明字号 × 缩放 × 密度 × 行高系数」对比实测控件框高，判定裁字/临界）；
  `tests/apk_readability.py` 增补「字号单位是否用 sp」「固定高度容器」「字重与行距」「提示色对比度」等字体专项检查。
  开发中修正过 5 处误报（墨迹测量被填充区/圆角混色污染、同名 id 在手机与手表布局声明不同被覆盖、
  被 ScrollView 裁掉的观测被误判为裁字等），逐条记在工具注释里。
- **验证工具**：`tests/android_ui.py`（uiautomator 驱动的真机 UI 自动化）、
  `tests/verify_lan_p2p.sh` + `tests/lan_peer_sim/`（局域网 P2P 的跨实现验证对端）。

### Fixed
- **P0-1 root 命令注入（agent-client，2026-09-13 体检）**：模型返回的 package/url/action 原样拼进 `su -c`
  （`launchApp` 的 `$(cmd package resolve-activity ...)` 未加引号、`findTaskId`/`reclaimTask` 的 grep 模式可被
  单引号截断）——新增 `ActionValidator` 白名单网关，`AgentEngine` 动作入口与 `SandboxController` root 边界
  双重强制，非法值直接抛错回灌模型。dex 已验证进包。
- **P0-2 无障碍列表整体覆盖（agent-client）**：`RootShell.ensureA11y()` 由 `settings put` 覆盖式改为先读后追加，
  用户已有的 TalkBack/银行类等无障碍服务不再被静默清掉。
- **P0-4 agentd-go 本机不可构建**：`go.mod` 无谓声明 `go 1.27`（未用任何 1.25+ API），与 `fetch-go.sh` 自备
  工具链 1.24.6 对齐后编译 + vet 通过。
> 说明：本轮「UI 回归第一版并删除 M3」后，下列 M3 迁移遗留问题已随库一并消失（平台 `Switch` 已回归），
> 条目保留作为历史记录。
- **设置页崩溃（M3 迁移遗留）**：布局已改用 `com.google.android.material.materialswitch.MaterialSwitch`，
  但 Java 侧仍是 `private Switch swVision;` / `(Switch) findViewById(...)`——`MaterialSwitch` 的实际父类是
  `SwitchCompat`（→ `CompoundButton`），**不是** `android.widget.Switch`，于是进入手机设置页或手表设置页
  必 `ClassCastException`。已改为按真实类型声明/转型（`SettingsActivity`、`WatchSettingsActivity`）。
  （`MaterialButton` 一类不受影响：`AppCompatButton extends android.widget.Button`。）
- **面向用户文案合规（守则 T-4 / O-3）——两处实机可见的违规**：
  - **T-4 归因泄露**：失败卡片、灵动胶囊与保活通知此前会把异常原文直接摆给用户
    （`内部异常：<t.getMessage()>`、`模型调用失败：<e.getMessage()>`）。现以 `failTask()` 为唯一出口，
    经 `uiReason()` 统一加工：去堆栈行、去全限定类名与异常类名、压空白、截断为 120 字；
    清洗后若已无中文且含技术串特征则落中性结论。异常细节只进 logcat（符合 O-2 留痕）。
    引擎自产归因改用守则附录 B 分类文案：「引擎内部异常，任务已停止（可重试）」
    「模型调用失败——请检查网络与 API 配置后重试」。
  - **O-3 内部标签外泄**：`friendlyLabel()` 此前只覆盖 11 种形态且**兜底 `return raw`**，
    胶囊/通知会显示 `edge_back left`、`setting wifi`、`brightness 128`、`text(缝合键盘)`、
    `tap#12(直点)` 这类内部标签，包名也直接显示为「打开应用 com.tencent.mm」。
    现覆盖 `exec()` 全部 21 类标签与 `SCHEMA` 全部 22 个动作名，**兜底不再回原文**
    （未映射只记 logcat，用户看到通用文案），`app <包名>` 经 `PackageManager` 换为应用显示名。
- **规范守则同步至 1.0.1**（§0 元数据 + §12.4 第 5 项 + 附录 C 代码锚点 + Skill §9 溯源表），
  条款本身未变——本次是让实现回到条款要求（§13.3 三处一致）。

- **额度网关的客户端回退与额度提示**：`LlmClient` 在网关不可用（超时/连接失败/5xx/上游鉴权失败）
  时自动回退到用户自填的直连配置，且**回退后不再重复尝试已知不通的网关**；
  网关返回 **402 时绝不回退**（否则额度形同虚设），并抛出 `QuotaExhaustedException`，
  由 `AgentEngine` 给出「额度不足——请在设置→账号与额度用兑换码充值」的差异化指引，
  而不是笼统的「请检查网络与 API 配置」。402 也不再走 429 那套 20/40/60 秒退避重试。

- **跨设备下命令**：在设备 1 上说「让设备 2 打开 B 站」，设备 2 自动执行并在共享任务历史中可见结果。
  新增 `remote` 动作（`AgentEngine.SCHEMA` + `exec()` 分支 + `UiText` 文案 + 同账号设备列表注入提示词），
  客户端新增 `RemoteCommand`（下发 / 20s 轮询 / 执行 / 回报）；服务端新增定向指令队列
  `agentlite-server/command.go` 与四个接口。安全边界：**只支持同账号定向**（不支持群发）、
  每账号每分钟最多 10 条、**5 分钟未执行即作废**、**只有目标设备本人可回报结果**、
  跨设备指令**不继承下发方授权**（目标设备用户对敏感操作仍需自行确认）。
  同步更新 `skills/software-interaction-logic/SKILL.md`（§4/§7.3/§9）与 `docs/INTERACTION_LOGIC_SPEC.zh-CN.md`（§4 L1 词表）。

### Changed
- **主界面精简**：删除「设置 · 账号 / 模型 / 同步」入口按钮（设置页仍可从页头「设置」与账号状态行进入）
  与整个调试台（`btnDebug` 折叠开关 + `dbgScroll` 面板 + 12 个动作直测按钮）；同步清理
  `MainActivity` 中对应的绑定与 `DbgAction`/`dbg()` 助手（共 76 行）。
  APK 核验：上述 6 个 id 已从资源表彻底消失；真机启动 568ms、无 FATAL。
- **主界面布局微调（按调试需求）**：任务下达区由「输入框一行 + 语音/运行一行」合并为**同一行**
  （输入框 `0dp + weight=1`，语音 56dp、运行 84dp）；「历史记录 / 授权管理」「省电策略 / 记忆库」
  两组磁贴之间补 12dp 间距（此前两个 `weight=1` 按钮直接顶在一起）。真机实测：
  三者垂直中心差 0.0dp、磁贴间距 12.0dp。
- **UI 回归第一版（M3 之前的平台控件版本）并**删除 Material 3 依赖与支持**：
  - 资源：恢复第一版的 9 个 drawable（`btn_primary` 22dp / `btn_ghost` 20dp / `btn_chip` 14dp 圆角）、
    平台主题 `Theme.Material.NoActionBar`（沉浸式）、27 色深色色板与 v31 动态取色映射；
  - 组件：**64 个 `MaterialButton` → 平台 `Button`**（按原样式语义映射到第一版 drawable）、
    **3 个 `MaterialSwitch` → 平台 `Switch`**，清除全部 `app:` 命名空间属性；
  - Java：`SettingsActivity` / `WatchSettingsActivity` 的 `MaterialSwitch` 回归 `android.widget.Switch`；
  - 构建：`build-mac.sh` 移除 AAR 展开 / 库资源编译 / `--extra-packages` / 库 classpath；
    删除 `scripts/fetch-m3-deps.py` 与 `build-with-m3.sh`，整合层 `build-client.sh` 同步去掉依赖准备步骤；
  - **效果**：APK **5900KB → 183KB（约 1/31）**，dex **6 → 1**，恢复「无 Gradle、无 AAR、无第三方依赖」的零依赖构建；
    实测确认包内不再含 `com/google/android/material` 类，主题 parent 为平台 `Theme.Material.NoActionBar`。
  - 保留：手表与手机的按钮均沿用第一版的圆角 drawable（手表药丸元素保留）；此前修好的可读性
    （字阶、溢出防护、提示文字对比度）与缺陷修复（震荡看门狗、无障碍、同步开关、充值页）一并保留。
- **`setting home` 死循环（实机日志定位）**：`Settings.ACTION_HOME_SETTINGS` 在 ColorOS 16 上进入「默认桌面选择」
  流程 → 屏幕转黑、无障碍节点归零；模型在「打开设置」类任务里选中它后与 `key 4` 形成
  `setting home → 空屏 → key 4 → 回桌面` 的 A↔B 来回震荡，把 50 步步数全部耗光。
  两处修复：① 移除 `home` 映射并从 `SCHEMA` 页面枚举删除（需要设置首页用 `setting settings`）；
  ② 新增**震荡看门狗**——最近 6 步内屏幕状态 ≤2 种且动作 ≤2 种即判为无进展并终态。
  该缺口此前既躲过重复检测（只数「连续相同」动作，A↔B 每步归零），也躲过指纹看门狗
  （空节点屏用交替哨兵，`allSame` 永不成立，即守则 DEV-5）。Skill §4/§5 与守则 §8 已同步（守则升 1.0.3）。
- **语音面板按钮溢出**：等分 135dp 的按钮在系统字体 1.15× 下装不下 7 字标签「按住/点按说话」
  → 缩短为「按住说话」+ 单行省略 + 内边距；主界面「语音/运行」由固定 52/80dp 改为
  `wrap_content + minWidth`，任何字体缩放下都不会把字挤出按钮。
- **按钮文字通用防护**：63 个按钮统一补 `maxLines=1` + `ellipsize=end`（溢出退化为省略号，不再出框）。

- **界面视觉一致性（设计令牌化）**：布局此前内联硬写字号/高度/间距，全 App 出现 **9 种字号、12 种控件高度、
  9 种间距取值**（含 2/6/10/14dp 这类脱离网格的值），同一角色的控件在不同页面尺寸不一——这是「像随手拼的」
  的主要来源。现统一到 `res/values/dimens.xml` 的设计令牌：
  - 字阶 5 档：标题 22 / 小节 16 / 正文 14 / 标签 13 / 说明 12sp（手表单独一档 13/12sp，不低于 12sp）；
  - 触控目标 3 档：紧凑 40dp / 常规 44dp / 主操作 48dp（此前最小 28dp）；
  - 间距：4/8/12/16 网格 + 页面留白 16dp（M3 标准）+ 卡片间距 12dp（此前碎片化）。
  共改 432 处属性（脚本 `tools/normalize_layout_tokens.py`，只改值不动结构）。
  **实测效果**：字号偏小 37 处 → **0**；触控目标不足 44dp 50 处 → **0**；
  字体放大 ×2.0 时裁字 3 处 → **0**（余量由 103% 提升到 117%）；对比度仍全部达标。
- **去掉「药丸」按钮风格**：M3 默认按钮是全圆角（stadium），新增
  `Widget.AgentLite.Button{,.Tonal,.Outlined}` 三个样式把 `cornerRadius` 统一为 **8dp**，
  51 个按钮全部切换（更接近常见 App 观感）。
- **字阶再提一档**：标题 24 / 小节 17 / 正文 15 / 标签 14 / 说明 13sp；
  **提示文字色对齐 `on_surface_variant`**（原 `#8E8A94` 在输入框底色上仅 3.63:1，
  实测动态取色下低至 2.03:1；改后约 7:1）。
- **API 配置移入二级菜单**：`base_url` / `api_key` / 主模型 / 语音模型 / 规划模型 /
  步数上限 / 纯视觉开关从主页移入 `SettingsActivity`，主页只保留任务输入与各管理页入口；
  手表形态继续走 `WatchSettingsActivity`。
- `LlmClient` 新增 `forPrefs`：已登录且未关闭网关时经额度网关转发（`api_key` 用账号令牌），
  未登录时仍直连用户自填的 DashScope 配置，功能不退化。
- 仓库标准化：统一构建脚本权限与 shebang、补充 SPDX 许可证头、新增治理文件（CONTRIBUTING / SECURITY / .editorconfig / .gitattributes / CI）、中文文档统一 `.zh-CN` 后缀、`agentd-apk` minSdk 对齐为 30（Android 11+）。

## [0.6.0] - 2026-09（首个正式版 Release）

### Added
- `agent-lite` 零 Root 原生客户端成为主推形态：无障碍节点感知 + `takeScreenshot()` 视觉兜底；
- 点击、长按、滑动、边缘返回、中文文本输入及全局系统动作；
- OpenAI 兼容多模态模型接口与原生录音语音输入；
- 任务规划、已安装应用匹配、纯视觉模式；
- 代理状态可见（胶囊、边缘光晕、授权/接管/终态卡片）、事前授权、事中停止、事后历史追溯；
- 敏感场景（支付、验证码、协议确认、系统权限）自动暂停并交由用户接管。

详情见 `_local/release-notes-v0.6.0.md`（本地留存，不对外发布）。

## [0.5.0-beta.1] - 2026（首个对外预发布）

- 首个可侧载安装的 zero-Root 手机助手 beta 版。

[Unreleased]: https://github.com/TQYM/android-global-agent/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/TQYM/android-global-agent/releases/tag/v0.6.0
[0.5.0-beta.1]: https://github.com/TQYM/android-global-agent/releases
