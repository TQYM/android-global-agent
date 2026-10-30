# 更新日志（Changelog）

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased]

### Changed（Agent Lite 手机端适老化前端改版，2026-10-30）

- **面向老年人与不便使用手机的人重做手机端 5 个页面**（主页 / 设置 / 登录 / 充值 / 语音召唤）：
  浅色高对比主题（正文 ≥7:1）、正文 20sp / 按钮 24sp / 标题 30sp、按钮 ≥64dp、控件只写 `minHeight`
  不写死高度；主页一条路「状态 → 我要说话 → 打字或点例句 → 开始 / 停止 → 大字提示」。
  全部控件 id 与业务逻辑不变，手表形态界面零改动。设计说明见 `docs/ELDER_UI_REDESIGN.zh-CN.md`。
- **设置页分层**：账号与「让小帮手正常工作」（无障碍 / 自启动 / 后台）在上，模型接口、Token、
  屏幕适配、同步收进默认收起的「高级设置」；尚无可用凭据时自动展开。
- **反馈看得见**：主页新增大字提示框，原先只写进隐藏日志的反馈（已记住、正在听、已收回授权……）
  改为直接显示；录音中「我要说话」变红色「说完了」，识别中置灰。
- **防误触**：清空记录 / 收回全部授权 / 清空操作记忆 / 恢复默认档位 / Token 归零新增二次确认；
  系统对话框统一经 `ElderUi` 放大，危险按钮红字；列表弹窗每项 20sp、≥64dp、长文案换行。
- **悬浮卡片放大**（手机）：接管 / 授权 / 提问 / 完成 / 失败卡片与控制面板字号约 ×1.4、可点控件 ≥60dp；
  窗口类型与触摸标志不变。
- **登录页**：输入框上方常驻标签，新增「显示密钥，方便核对」；手表沿用原布局 `activity_login_watch.xml`。

### Added

- `tests/elder_ui_guard.py`（接入 `make check` 的 ui 目标）：不丢 id、字号 ≥18sp、触控 ≥60dp、
  不写死高度、16 组对比度、手表不受影响、弹窗放大与危险操作确认，共 22 项。

## [0.7.0] - 2026-10-18

### Added（契约 / 凭据路由 / 流程记忆模块化，2026-10-18）

- **Android 客户端拆出可测模块**：`AccountHttp`（账号 HTTP 收敛）、`ContractVersions`
  （契约版本常量）、`CredentialRoute` + `CredentialRouteUi`（凭据来源路由，与 UI 解耦）、
  `FeatureFlags`（功能开关）、`FlowMemory` / `FlowPlan` / `FuzzyFlow`（流程记忆与模糊匹配）、
  `SemanticTranslator` / `TextNormalizer`（语义与文本归一）、`PopupGuard`（弹窗守卫），
  并新增 `contracts/`、`tools/` 两个子包。密码节点不采文本，只留坐标，内容不进记忆库。
- **服务端计费模块**：新增 `agentlite-server/billing.go`，配套 `storage` / `streaming` /
  `gateway` / `interface_correctness` 回归测试与 benchmark。
- **门禁与回归**：新增 33 个 Python 检查脚本（account_sync、action_execution、asr_lan、
  auto_tap_guard、credential_route、done_evidence、flow_memory、fuzzy_*、m0/m3、
  migration_drill、popup_* 等）与 8 个 Java 单测，`make check` 保持 `go contract drill ui jvm`。
- **文档**：新增 ADR-001（功能开关）、ADR-002（契约）、ADR-003（M3 工具注册表）、
  `FUZZY_SEMANTIC_TO_FLOW_DESIGN`、`MATURE_MODULE_INTEGRATION_PLAN`，归档
  `INTERFACE_AUDIT_2026-10-09/10` 与 `INTERFACE_IMPLEMENTATION_2026-10-11`。
- **iOS**：`AccountService` / `HTTPClient` / `SyncService` / `Models` 与同步测试随契约同步。

### Fixed（真机验收脚本的零 root 前提与记忆库隔离，2026-10-11）

- **零 root 前提在 run 期间被脚本自己抹掉**：`tests/credential_real_device.py` 与
  `tests/t03_t05_real_device.py` 的 `write_prefs` 是**整体覆盖**（`cat > PREF`），而 entries 里
  没有 `root_mode` —— 运行期 `agent_prefs.xml` 里这一项就被抹掉，`Prefs.rootMode()`
  （`Prefs.java:132` `getString("root_mode","auto")`）落回默认 `"auto"`。也就是说「零 root 主链路」
  这个自称在脚本运行期间并不成立；先前那份结论实际是靠设备侧证据撑住的（KernelSU 允许列表不含
  `com.dsh.agentlite`、sulog 里没有一条 `su` 请求来自应用 uid），不是靠文档里写的代码层判据。
  现在两个脚本的 prefs 写入都显式带上 `root_mode=off`，运行期条件与文档一致，也不再依赖
  「某台设备恰好没授权」。
- **T04a 记忆库隔离**：`t03_t05_real_device.py` 的「有证据路径仍入库」会假失败——上一轮验收已把
  「打开计算器」写进记忆库，同场景任务被直接复现完成（logcat：「命中操作记忆库」→
  「记忆库复现 1/1」→「已按记忆库直接完成（1 步，未重新规划）」），根本走不到
  `AgentEngine.java:992`（`if (recordable && !recordedSteps.isEmpty())`）的入库分支，于是
  「已写入操作记忆库」与「操作记忆未入库」两条日志都不出现，报「既无入库也无未入库日志」。
  现在 T04a 开始前把 `flowmem.json` 移到 `flowmem.json.t04a.bak`、结束（含 `restore()` 兜底）归位；
  相应地 T4-5 的表述改为「无证据终态不新建也不增长记忆库」，在隔离基线下与「有证据会新建」形成对照。
- **还原链路**：删掉陈旧快照（原先脚本只在快照不存在时采集，导致上一轮的 prefs 被反复写回），
  让脚本重新采集真实用户态（118 字节、无 `root_mode` 键）。
- **新增运行期自检，一加上就抓出第二个缺陷**：两条脚本在推进阶段前会读回一次 prefs 并断言
  `root_mode=off`（`credential_real_device.py` 的 `P0`、`t03_t05_real_device.py` 的 `P-ROOT`），
  使「零 root」不再只是文档里的说法。自检首次运行即报 `root_mode=''`——`read_prefs()` 的正则只认
  `value="..."` 属性形式，而 `SharedPreferences` 把字符串写成元素文本
  （`_xml()` 产出 `<string name="k">v</string>`），于是 `base_url`/`model`/`root_mode` 一律读回空串。
  此前没有断言依赖字符串读回（已接线的判断都读 boolean，如 `use_own_key`、`auto_accept_terms`），
  所以一直没暴露；而「按读回值判断前置条件」这类断言会永远假失败。现在两种形式都认，并用 `_xml()`
  的输出做了往返一致验证。
- **`adb reverse` 隧道泄漏**：`credential_real_device.py` 建了两条隧道
  （`tcp:19101`/`tcp:19102`）但 `restore()` 只还原 prefs 与令牌，不撤隧道，收工后设备上一直挂着
  两条指向宿主的转发（下次运行还会静默复用陈旧隧道）。已在 `restore()` 里按端口逐条
  `reverse --remove`。`t03_t05_real_device.py` 本就撤了自己的 `tcp:19111`，无需改。

### Added（数据副本迁移演练与支付竞争 smoke，2026-10-11）

- **`tests/migration_drill.py`**：在临时数据副本上用真实 `agentlite-server` 二进制 + 现场自签 TLS
  走完首次迁移、停机核对、重开幂等、孤儿设备认领、迁移后写入、回滚与损坏边界，并新增**阶段 J
  支付竞争 smoke**——`threading.Barrier(2)` 并发打「同一订单 pay vs cancel」与「同一订单两个不同
  兑换码抢付」，断言恰好一个赢家，服务重开后核对额度增量恰等于赢家那张码的份额（多记即双重支付，
  少记即丢账）。48 项断言全过。
- **门禁**：`make check` 依赖改为 `go contract drill ui jvm`，新增 `make drill` 目标；演练只绑
  `127.0.0.1` 随机端口、使用现场自签证书，不碰外部网络。
- **报告同步**：`docs/INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md` §4 上线前第 1、2 步标注
  「本机已完成」，§6 复跑入口补 `make drill`，§7 重写设备验收边界并新增「Android 授权白名单
  无损坏自愈」一条（该条已在同日 Fix 段补上自愈并真机验证）。
- **真机脚本加固**：`tests/credential_real_device.py` 补解锁前置检查 `screen_ready()`。锁屏设备上
  `uiautomator dump` 会超时打断脚本，而 `/data/data`（CE 存储）在锁定期间不挂载会让前置状态写入
  静默落空——「未登录」本身又是被测状态，可能凑出一份看着合理的「通过」。现在直接退环境码 2 并
  打印判据；锁屏设备实测输出 `环境不可用：锁屏未解（mShowingDream=false mDreamingLockscreen=true）`、
  退出码 2。
- **零 root 真机验收已在真机跑通**：零 root 由两条独立证据共同成立——代码层 `root_mode=off` 让
  `RootShell.available()` 在探测前直接返回 false，设备层这台机器的 KernelSU 允许列表不含
  `com.dsh.agentlite` 且 sulog 里没有任何一条 `su` 请求来自应用 uid（逐条核对都是 `uid=0 euid=0
  comm="sh"`，即 adb shell 发起）；旁证为主进程与 guard 进程 uid 均为应用 uid（`u0_a577`）、
  无障碍服务已绑定（`capabilities=169`）。两条脚本都用本机假端点
  （经 `adb reverse` 暴露，不出网）。`tests/credential_real_device.py` → **44 通过 / 0 失败**
  （含运行期零 root 自检 `P0`）：
  首启与登录态、自己的配置、账号通道、模型缺失、故障归因与不跨通道、设置页保存后的显式选择卡，
  含 3 张终态截图。`tests/t03_t05_real_device.py` → **15 通过 / 0 失败 / 1 跳过**
  （含运行期零 root 自检 `P-ROOT`）：任务代际
  （旧任务收尾回调不得撤掉新任务）、证据分级（无证据 done 落「不确定」且不入库，隔离基线
  0 → 0 字节）、代点分支（本机未弹确认框故跳过，非缺陷）。两脚本主链路全程用户态，结束
  均还原设备用户态。
  首轮尝试被 ColorOS 锁屏挡住：`input` 合成事件被 keyguard 拒绝（滑动手势含底部边缘起手、
  `keyevent 82/4/3`、`wm dismiss-keyguard`、`cmd window dismiss-keyguard`、`locksettings
  set-disabled true` 报成功但不持久化、`USER_PRESENT` 广播、重启 SystemUI 全无效），锁定期间
  `/data/data`（CE 存储）以密文目录项呈现、前置与取证都做不了；这是 Android 的安全设计，手工
  解锁一次后即全绿。

### Fixed（授权白名单损坏自愈与事前授权口径修正，2026-10-11）

- **`AuthStore` 补上损坏自愈**：`SharedPreferences` 解析失败原本静默返回空 map，`AuthStore.list()`
  因此无任何提示——一旦事前授权接线，损坏就表现为「白名单整体消失」且无从察觉。构造函数现在先
  `recoverIfCorrupt(ctx)` 再取实例：用 `android.util.Xml.newPullParser()` 走到 `END_DOCUMENT` 并
  要求首个 START_TAG 名为 `map`，任何 `Throwable` 判为不可解析；判定损坏则按
  `agent_auth.xml.corrupt.<millis>` 改名保留现场（改名失败则 `delete()`），并写一条
  `W AuthStore` 日志。只能「加载前预检」而不能像 `FlowMemory.java:68-82` 那样 catch 异常，是因为
  `SharedPreferences` 的解析发生在 Android 内部、异常不外抛。`agent-lite` 与
  `agent-unified/client` 两模块逐字节一致。
  真机验证：设备装含该修复的构建（`73703ef912bf619018edf73d7b4640b8`）后从设置页点
  「预授权管理」即触发，三条证据同时命中——`shared_prefs/` 出现
  `agent_auth.xml.corrupt.<millis>`（md5 `f4e19c04c6c69de81d7e242ed8d8cd22`，与损坏原件
  **逐字节一致**）、logcat 留下「授权白名单文件损坏，已备份为 …，白名单按空表继续」、对话框
  正确显示「当前无预授权条目」。把备份复制回原名再触发一次可复现，产出第二份独立备份、md5 相同，
  顺带证明时间戳命名在重复损坏下不覆盖前一次现场（`FlowMemory` 的固定备份名会）。
- **事前授权未接线的事实写进文档**：`AuthStore.grantAlways()` / `isAlways()` 与
  `AgentEngine.requestAuth()` 在 `agent-lite` 与 `agent-unified/client` 里都**只有定义、没有调用者**，
  `OverlayController.showAuth()` 从不执行，卡片上「单次允许/始终允许/拒绝」三个按钮永远点不到；
  已接线的只有设置页的 `list()` / `revoke()`，白名单没有写入路径。按
  `docs/INTERACTION_LOGIC_SPEC.zh-CN.md` S-3「接线前**必须不**对外宣传事前授权拦截已生效」，
  把 `README.zh-CN.md` 与 `docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md` 中按已生效叙述的两处改为
  「已实现但未接线」并指向 §10.2 的 DEV-4 登记；报告 §7 相应拆成两条（未接线 / 损坏自愈）。

### Fixed（门禁稳定性：演练偶发假失败，2026-10-11）

- **`tests/migration_drill.py` 的「旧配额流水未作为入账依据重放」会偶发假失败**——`make check`
  实测 4 次里红了 1 次，紧接着单跑又是 48/0（48 项断言全过）。这种「随机变红」比稳定失败更难查。
  根因是断言写 `first_tx["accounts"][0]` 并假定第 0 个是 alice，而 `billing.jsonl` 首次导入的数组
  顺序来自 `agentlite-server/store.go:141` 的 `accounts map[string]*Account` 经 `billing.go:63`
  `for _, a := range s.accounts` 导出——**Go 不保证 map 迭代顺序**。
  实测（go1.27.1）：同一进程对 2 元素 map 反复 `range` 200 万次，首键 `a_legacy1` 占 87.5%、
  `a_legacy2` 占 12.5%；换成 3 元素则是 75% / 12.5% / 12.5%——正是 Swiss table 单 group 8 个 slot、
  迭代起点在 8 个位置上等概率的几何（7/8、6/8、1/8）。跨进程探针 40 轮复现同一比例：
  `accounts[0]` 落在 bob（quota=300）上 **6 次（15%）**，这 6 次里按位置取到的值与按 id 取到的值
  不一致，旧断言必然假失败。旧 detail 又写死「alice 额度仍为 9000」，把真实值一起藏住，故障现场
  因此看不出是取错了对象。改为按 `account_id` 取账号、detail 打印实际额度——同一探针按 id 取值
  40 轮全对，`make drill` 连跑 5 次均 48/0。服务端导出顺序不影响语义（导入按 id 匹配），未改动。
- 运维提示同步进报告 §4.2：副本核对不要逐字节 diff `billing.jsonl`，按 id 比对。

### Fixed（任务代际防护 T05a / 动作入口收敛 T03a / done 证据分级 T04a，2026-10-09）

- **T05a 任务代际**：`OverlayController.taskEnded(keepCard=true)` 的 4 秒收尾回调补上 `taskActive`
  守卫（此前无条件撤胶囊，而 15 秒分支有守卫）；`AgentEngine` 引入 `RUN_SEQ` 代际号，旧任务的延迟
  回调只在「仍是最新一代」时才改 `KeepAliveService` 状态。真机实测：任务 A 完成后 0.2 秒启动任务 B，
  B 的保活锁、覆盖层胶囊与运行态通知均未被旧回调破坏。
- **T03a 代点进 exec**：启动确认代点与无限制模式协议代点（勾选框 + 同意按钮）三处由裸
  `svc.tap`/`svc.tapNode` 改为经 `exec()`，统一节点直点、坐标修正与执行日志，失败原因进日志不再
  静默落空。`injectViaClipboard` 的 root 聚焦点击登记为受约束例外（`DisplaySpec.applyOffset` 非幂等）。
- **T04a done 证据分级**：新增纯静态 `judgeDone(sawFgChange, expectPkg, activePkg)` →
  `PROVEN / WEAK / UNPROVEN`；`arrivedExpectPkg` 记住「确实到达过被期望的目标」，使 `PROVEN` 在
  真机可达（`expectPkg` 是一次性核对项，done 时通常已被清空）。**二次无证据 done 不再放行**：
  终态降级为「不确定」，`recordable=false` 不入记忆库，新增琥珀色 `showUncertain` 卡片与绿色
  `showDone` 的完成语义位分离（终态文案不含「完成」字样）。
- 新增门禁并纳入 `make ui` / `make jvm`：`tests/generation_guard.py`(14)、`tests/auto_tap_guard.py`(15)、
  `tests/done_evidence_guard.py`（真值表 18 + 结构守卫 18）、共用剥注释读法 `tests/java_source_util.py`；
  三者在 HEAD 旧代码上分别 FAIL 5 / 6 / 8 项。**注意**：旧代码断言基线取自 HEAD；工作树上的
  「修复前」实测为 5 / 5 / 7 项。
- 真机回归（OnePlus PKX110 / Android 16 / 无线 adb）：凭据路由 A–E 41/0 + 阶段 F 6/0 无回退；
  三卡行为 `tests/t03_t05_real_device.py` **14 通过 / 0 失败 / 1 跳过**（跳过：本机系统已记住
  「允许打开」，启动确认框未复现）。详见 `PROJECT_OVERVIEW.zh-CN.md` §18.8 与
  `docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md` §23.12。
- **未做（方案明确留待后续）**：T04b（WEAK 档入库门槛）、T03a 第二刀（`execChecked` 包层）、
  T04a 的 WEAK 入库行为。**真机暴露但本轮未改**：结果卡片（`FLAGS_TOUCH` 覆盖层）在场期间
  点「运行」存在竞态，建议另立卡。

### Fixed（悬浮胶囊吃掉应用底部导航点击，2026-09-21）

- 修复任务运行期间底部状态胶囊（窗口 frame `[264,2437]-[952,2542]`）压在应用 Tab 栏上、
  以 `FLAGS_TOUCH` 吞掉点击的问题：胶囊改为 `FLAG_NOT_TOUCHABLE` 纯展示层并移除点击监听。
- 实测对照：同一坐标 `tap(766,2442)` 在无悬浮窗时画面变化 36.6%，胶囊存在时为 0.0%——
  这是「微信朋友圈点赞做不到」的直接原因（Agent 点不动「发现」Tab）。
- 修复后真机端到端复验通过：`Like the first Moment in WeChat Moments` 19 步完成，
  朋友圈首条动态出现点赞条目「报道北方」，与修复前基线（无任何点赞）对比确认点赞真实发生。
- 详见 `PROJECT_OVERVIEW.zh-CN.md` §17.9。

### Fixed（纯视觉感知与看门狗，2026-09-21）

- 修复 ColorOS/微信类应用只返回窄幅 `SystemUI` 状态栏节点时仍被当作应用页面的问题；检测到该形态后强制进入截图+比例坐标纯视觉路径。
- 纯视觉/状态栏污染场景的无进展看门狗改用动作后新截图视觉摘要；截图失败时不再依据恒定状态栏节点或旧截图误杀任务。
- 新增 `NodeInfo` 纯 Java 感知判定与视觉摘要回归，`FlowMemoryTest` 共 54 项通过；APK 独立构建成功。
- 更新 `docs/INTERACTION_LOGIC_SPEC.zh-CN.md` DEV-5，保留生态三档、截图敏感守卫等未完成项。

### Added（M3-02 参数 Validation，2026-09-21）

- 新增纯 Java `ValidationResult`，`ToolSpec.validate` 与 `ToolRegistry.validate` 在路由接线前冻结参数校验边界：必填、未知字段、null、类型、范围和枚举。
- 整数仅接受十进制整数；拒绝小数、科学计数法、NaN/Infinity；错误结果不回显输入值。
- 新增 `tests/m3_tool_validation_guard.py`；扩展 `ToolRegistryTest`，M3-01/M3-02 JVM 断言共 58 项。`feature_tool_router` 继续默认关闭，旧 `AgentEngine.exec()` 不变。

### Added（M3-01 声明式工具注册表，2026-09-21）

- 新增纯 Java `ToolParameter`、`ToolSpec`、`ToolRegistry`，严格从 `ActionSchema` 登记 25 个动作。
- 冻结参数最小 schema：必填性、类型、范围、枚举和 `additionalProperties=false`；重复注册明确拒绝。
- `ask`/`done`/`fail` 作为控制工具登记，但不携带执行器、不接入 `AgentEngine`；`feature_tool_router` 继续默认关闭。
- 新增 `tests/m3_tool_registry_guard.py`（22 项）和 `tests/ToolRegistryTest.java`（46 项），详见 `docs/ADR-003-M3-TOOL-REGISTRY.zh-CN.md`。

### Added（M0-02 结构化契约冻结，2026-09-20）

- 新增纯 Java `ScreenSnapshot`、`ToolInvocation`、`TraceEvent`、`ScreenState` 契约及稳定字段渲染器，不依赖 Android、`org.json` 或第三方运行时库。
- 冻结快照、工具、轨迹、流程复现四类版本；工具终态不可逆，Trace 事件限定为 8 类。
- 按用户确认冻结隐私与存储边界：密码节点输出 `[password]`；Trace 未来默认本地保存脱敏截图/快照，最多 20 个任务、单任务 8 MiB、总量 64 MiB。
- 旧 `FlowPlan` 无 `ScreenState` 时标记 `LEGACY_UNGUARDED`，仅明确 `LOW`/`NORMAL` 风险允许直放；空值、未知值和高风险交回模型与现有守卫。
- 新增 `tests/m0_contract_guard.py`（29 项）与 `tests/ContractModelsTest.java`（23 项），并入 `make ui`/`make jvm`。
- 详见 `docs/ADR-002-CONTRACTS.zh-CN.md`。

### Added（M0-01 成熟模块功能开关基线，2026-09-20）

- 新增 `FeatureFlags` 与 `Prefs.featureFlags()`，集中冻结 8 个成熟模块开关和 2 个影子模式开关。
- 感知、工具路由、复现、技能、Provider、虚拟屏及策略/轨迹正式模块默认关闭；策略与轨迹影子模式默认开启但不改变旧执行路径。
- 新增 `tests/m0_feature_flags_guard.py`，29 项静态契约断言并入 `make check` 的 `ui` 目标。
- 新增 `docs/ADR-001-FEATURE-FLAGS.zh-CN.md`，记录默认矩阵、版本、兼容边界和回滚方式。

### Added（三功能协同整合：模糊语义 + 操作记忆库 + 弹窗治理，2026-09-20）

把此前各自独立的三块能力在**同一条任务路径**上打通：转译产出结构化流程 → 主模型按流程执行 →
执行中把「一次性突发界面」当环境噪音剔除 → 业务步骤写入操作记忆库 → 第二次同任务**零模型往返**复现。
来源：用户要求「把最新的功能进行整合（模糊语义，记忆库，新的 skill）」。

- **回放三态终局**（`REPLAY_OK=1` / `REPLAY_STALE=0` / `REPLAY_INTERRUPTED=-1`）：复现不再只有
  「成/败」二元。**环境打断**（弹窗两轮关不掉、敏感场景接管、a11y 断开）**不记失败**——此前无差别
  `markFail` 会让两次运营弹窗误退役一条好记录；只有**路径失效**（界面改版、目标消失、连续无变化）
  才 `markFail`。
- **采集侧一次性突发界面过滤**（`PopupGuard.isDismissAnchor`）：带强关闭锚点（跳过/关闭/×）的步骤
  **跳过入库**并留痕「操作记忆跳过一次性突发界面步骤」。判定受**任务语义约束**——任务本身是
  「取消订单」时 `取消` 不得被当噪音跳过。修复的是「把广告关闭步记进路径，下次复现去点一个
  已经不存在的按钮」这一类必然失效。
- **回放侧弹窗处置**（`PopupGuard.findDismissable` + `MAX_REPLAY_DISMISS=2`）：回放遇弹窗先处置
  再续跑，两轮无效即判环境打断并回退模型，不无限重试。
- **`TYPE_SYSTEM` 窗口纳入感知**（`AgentA11yService.collectNodes`）：系统权限窗/系统对话框的节点
  此前被整窗丢弃，复现路径只能看见被遮挡的底层旧节点。
- **一体化端到端**：新增 `tests/integrated_features_e2e.py`（真机 16 项：夹具弹窗在场时提交 →
  转译成功 → 弹窗步被识别且不入库 → 业务步入库且带骨架指纹 → 第二次命中记忆库并直接完成 →
  零模型往返 → 弹窗噪音不给记录增加失败计数）。假模型 `tests/mock_llm_server.py` 新增
  `integrated` 场景（多模态 content 解析，此前 vision 请求会让 mock 500 → 引擎静默切网关）。
- **规范与 Skill 同步**：SPEC 升 **v1.0.6**（新增 §4.4 操作记忆库与复现路径 R-1~R-6、附录 C 对照行）；
  Skill §9 溯源表新增三条（记忆库、采集侧过滤、回放侧处置），§7.5 补充「引擎可能先于模型复现历史
  路径」的正常机制说明。动作集合与字段契约**未变**，`SCHEMA_VERSION` 不递增。
- **测试环境治理**（`tests/integrated_features_e2e.py`）：真机已登录时 `LlmClient.forPrefs` 会给
  自备 Key 客户端挂**同步服务网关 fallback**，mock 一旦失败真实模型就接管、测试结果不可复现——
  测试期清空 `sync_token`/`account_id` 掐断该通道；备份改为**主机侧且不覆盖首次**
  （设备端备份曾被下一轮 run 覆盖，导致还原后把 mock 配置写回用户设备）；恢复链逐条容错
  （一次 USB 掉线曾中断整个还原）。

### Added（软件引导跟随：新手引导/分步指引，2026-09-19）

- **`SCHEMA` 守则 4 新增 g) 新手引导/分步指引**（来源：用户要求「增加软件引导跟随，让 agent
  可以跟随软件指引进行操作」）。判定特征：高亮遮罩+镂空聚焦、气泡「点击这里/试试看」、
  「下一步/知道了/开始使用/跳过引导」按钮、分步圆点。双策略按任务相关性分流：
  - **跟随模式**（任务本身要求「跟着引导设置/开通」，如首次配置、开通某功能）：按引导指示
    点击被高亮的元素或「下一步/继续/知道了」逐步行进，**每步复验界面变化后**再走下一步；
  - **跳过模式**（引导只是首次教学、与任务无关）：优先点「跳过/跳过引导/×/不需要」；
    没有跳过入口就连续点「下一步/知道了/开始使用/继续」走完（一般 3~6 步）；
  - **红线不豁免**：引导中要求授权、登录、开启通知、付费、同意协议仍按守则 4 a)/d)
    与守则 5（敏感红线）处理——引导 UI 不构成绕过敏感确认的理由；
  - **循环防护**：超过 6 步仍走不完或同一提示循环重复 → 找跳过入口或 `fail`「引导无法完成」。
- **门禁扩至 30 项**：`tests/popup_ad_guard.py` 新增 PU-11 八项断言（判定特征/跟随/跳过/
  红线不豁免/6 步上限），守则 4 标题同步列入「新手引导」类别；Skill §3 表新增引导行、
  SPEC 升 v1.0.5（§4.2 环境态序列补「新手引导/分步指引」）。动作集合与字段契约未变，
  `SCHEMA_VERSION` 不递增。

### Added（突发界面处置守则：弹窗/广告/权限/异常跳转，2026-09-19）

- **`SCHEMA` 新增守则 4「突发界面优先处置」**（原守则 4~9 顺延为 5~10；动作集合与字段契约未变，
  `SCHEMA_VERSION` 不递增）：来源：用户反馈「当前 skill 依旧不全面，无法应对弹窗、广告等突发情况」。
  六类突发界面各有明确处置边界——
  - **系统权限弹窗**：最小必要授权，「仅本次允许/使用时允许」优先；任务没用到的敏感权限
    （定位/通讯录/短信/电话/相机/麦克风）点拒绝且不再强求；拿不准 `ask`；
  - **开屏/插屏广告**：只点「跳过/跳过广告/×/关闭」或 `key 4`，**严禁**点广告主体/领取福利/
    下载/抽奖；倒计时先 `wait` 1000~2000ms；两轮关不掉 → `fail`「广告无法关闭」；
  - **运营弹窗**（领券/红包/评分/更新/会员）：无关任务点「关闭/×/暂不/取消/稍后再说」，
    「立即更新/立即开通/立即领取」等改变状态的一律不点；
  - **登录/注册墙**：先试「跳过/暂不登录/游客模式」；绕不过 → `fail`「需要登录」，
    严禁自行输入账号、密码、验证码；
  - **系统对话框**（USB 调试/位置服务/剪贴板）：非任务必需一律「拒绝/取消/仅本次」；
  - **异常跳转**（浏览器/应用商店/下载页）：`key 4` 返回并复验 → 重新 `app` 直达 → `fail`；
    严禁进应用商店搜索/安装补救（与守则 7 呼应）。
  通则：每步先于原任务检查顶层窗口；被遮挡的底层页面旧编号禁止引用；处置后以
  「弹窗消失 + 底层目标可见」复验，未复验不得恢复原任务；同一弹窗两轮无效必须换策略。
- **防漂移门禁 `tests/popup_ad_guard.py`**（22 项断言，并入 `make check` 的 `ui` 目标）：
  守则 4 每类突发界面一条不漏地变成断言（PU-1~PU-8），PU-9 核对 `Prefs` 转译层简短守则
  不弱化，PU-10 核对守则编号 1~10 连续有序（防插入错位）。
- **三处一致性同步**：Skill（`skills/software-interaction-logic/SKILL.md` §3 环境态表扩充
  五类新环境态 + §8 检查清单 + §9 溯源表）与守则（`docs/INTERACTION_LOGIC_SPEC.zh-CN.md`
  v1.0.4 §4.2 环境态序列 + 版本变更表）按「代码 / Skill / 守则」纪律同步。

### Added（操作记忆库：记一次，之后直接复现，2026-09-19）

- **记忆库默认开启**（`Prefs#flowMemory`，可关）：每次任务把**每一步成功执行的操作**记进操作记忆库
  （`filesDir/flowmem.json`），同一句话下次再来时**直接照做**——跳过转译与主循环的逐步推理，
  **零模型往返**完成操作。来源：用户要求「记忆库改成默认的，将每一步成功执行的操作放入记忆库，
  之后直接复现记忆库就可以完成操作」。
- **判断与搬运分层**：`FlowPlan`（纯逻辑，零 android / org.json，桌面可直跑回归）
  + `FlowMemory`（落盘 / 命中统计 / UI 列表）+ `AgentEngine`（采集与复现接线）。
  复现全程复用既有 `exec()`，手势降级、root 兜底、输入法借用、敏感场景守卫与模型驱动时**完全一致**
  ——记忆库既不是第二条执行路径，也不是免检通道。
- **三道闸门**：① **可复现动作白名单**（`app`/`tap`/`longpress`/`text`/`key`/`back`/`home`/`edge_back`/
  `swipe`/`scroll`/`wait`/`open_url`），出现 `remote`/`open_api`/`list_apps`/`setting` 类动作即
  **整条流程作废**（不写半条流程——缺步的路径比没有路径更危险）；② 输入内容疑似凭据
  （4 位以上纯数字、含「密码/验证码/口令」）→ 整条不入库，密码框连文本锚点都不采集；
  ③ **不上云**（不接 `SyncClient`），本地可见 / 可删 / 可清空 / 可关。
- **定位优先语义**：`resource-id → 文本相等 → 文本包含（两边都 ≥2 字）→ 类名唯一`，
  都定位不到才退到记录的比例坐标（跨分辨率、跨设备可用）；仍拿不到就**放弃复现**而不是猜。
- **失败即贬值**：连续 2 次复现失败即退役（任何一次成功立即清零），失败后**回退模型规划**——
  记忆库永远只是加速器，不会变成卡住任务的新故障点。
- **门禁**：`tests/FlowMemoryTest.java`（桌面 JVM，48 断言）+ `tests/flow_memory_guard.py`
  （免设备，31 项）+ `tests/flow_memory_e2e_mock.py`（虚拟机端到端：记一次 → 直接复现且零模型往返，
  配套 `mock_llm_server.py` 新增 `flow` 场景）。

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

[Unreleased]: https://github.com/TQYM/android-global-agent/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/TQYM/android-global-agent/releases/tag/v0.7.0
[0.6.0]: https://github.com/TQYM/android-global-agent/releases/tag/v0.6.0
[0.5.0-beta.1]: https://github.com/TQYM/android-global-agent/releases
