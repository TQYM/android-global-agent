# iOS 端功能实现手册（AgentLite for iOS）

> **文档定位**：这是一份**可以照着写代码**的实现手册，不是可行性 PPT。
> 每一个「服务端事实」都在 2026-09-13 用一次真实运行核对过；对不上的一律标注为待核实。
>
> **结论先行**（一句话）：
> **iOS 端可以做「账号 + 信息同步 + 远程控制」；不能做「设备端自动操作别人 App」。**
> 因此本项目按用户授权预案落档为**裁剪版**：保留账号与远程控制，自动化执行能力冻结（见 §3）。
>
> 配套实现：`ios/`（42 项测试全绿，含 10 项真连服务端的端到端用例）。
>
> 最后更新：2026-09-13 ｜ 事实基准：本地服务端 `agentlite-sync 0.1.0` 实跑 + 源码逐行核对

---

## 0. 按需跳转

| 你要做的事 | 去哪一节 |
|---|---|
| 先搞清楚「iOS 到底能不能自动点别人 App」 | [§1 能力边界](#1-能力边界先回答最重要的问题) |
| 决定做什么、不做什么 | [§3 落档决策：保留什么、裁剪什么](#3-落档决策保留什么裁剪什么) |
| 开始写代码（接口怎么调） | [§4 服务端契约（实测）](#4-服务端契约实测) |
| 局域网 P2P 怎么对接 Android | [§5 局域网 P2P 与 iOS 的特有约束](#5-局域网-p2p-与-ios-的特有约束) |
| 后台/被系统杀的问题 | [§6 后台执行模型](#6-后台执行模型ios-端最大的工程约束) |
| 安全红线 | [§7 安全纪律](#7-安全纪律四条红线) |
| 怎么跑起来、怎么验证 | [§8 构建与验证](#8-构建与验证) |
| 接着往下做 | [§9 未完成项与下一步](#9-未完成项与下一步) |
| 踩过的坑 | [§10 已知坑](#10-已知坑照抄避免重踩) |

---

## 1. 能力边界：先回答最重要的问题

### 1.1 结论

| 问题 | 答案 |
|---|---|
| iOS 上能否实现 Android 那样的「读屏 + 自动点击任意 App」？ | **不能**，非越狱、非企业签名、无 Mac 常驻的前提下没有通道 |
| 那 iOS 端还有价值吗？ | **有**，而且价值明确：账号体系、跨设备同步、**远程下发/接收指令**、局域网加密互通 |
| iPhone 能当被控端吗？ | **能，但只能执行「用户事先授权的本地动作」**（App Intents/快捷指令），不能任意操作 |
| iPhone 能当控制端吗？ | **能，且这是它的强项** —— 下发指令、查看结果、管理多设备 |

### 1.2 为什么不能（逐条说清，避免后来者重复调研）

| # | 事实 | 对本项目的影响 |
|---|---|---|
| B1 | iOS **不向第三方 App 开放系统级无障碍/节点树读取**。Android 的 `AccessibilityService.getWindowsOnAllDisplays()` 在 iOS 上没有对应物 | 感知层（读屏）整块无对应实现 |
| B2 | iOS **没有可跨 App 注入手势的公开 API**。Android 的 `dispatchGesture` 无对应物 | 执行层（点按/滑动）整块无对应实现 |
| B3 | **XCTest / WebDriverAgent** 能自动化 iOS，但要求：Mac 常驻 + Xcode 构建 + 开发者签名 + 设备开开发者模式 + 定期重签 | 属于**研发工具链**，不是可交付给用户的产品形态 |
| B4 | **快捷指令/App Intents** 能让 App 执行**自己声明的**动作，但动作集合是**预设且受限**的，不能"任意操作别家 App" | 可以支撑「有限被控」，不能支撑「通用 Agent」 |
| B5 | App 后台**不能常驻**：没有 Android 那种前台服务；后台刷新由系统调度，时机不可控 | 轮询式指令投递需重设计（见 §6） |
| B6 | 自签 HTTPS + ATS：iOS 默认拒绝自签证书（实测 `NSURLErrorServerCertificateUntrusted`，-1202） | 必须做证书 pin（见 §7.1），不能靠「信任所有证书」绕过 |

> **本项目与鸿蒙的关系**：`docs/HARMONY_NEXT_EVAL.zh-CN.md` 的结论是「NEXT 不给三方无障碍 → 设备端 App 路线不可行」。iOS 的结论**成因不同但结果相同**：iOS 有完备的无障碍框架，只是**不开放给第三方 App 去操作别的 App**。两者都不构成本项目 iOS 端「账号+远程控制」的障碍。

---

## 2. 三条候选路线对比（评估留痕）

| 路线 | 能力 | 用户门槛 | 可持续性 | 结论 |
|---|---|---|---|---|
| **① 原生 App（账号 + 远程控制 + 本地受限动作）** | 账号/同步/指令/局域网；本地动作需用户预先授权 | 装 App 即用 + 一次免费 Apple ID 签名（7 天）或开发者账号（1 年） | 高：不依赖 Mac、不依赖越狱 | ✅ **采纳（本手册主线）** |
| **② WDA/XCUITest 外挂式** | 完整自动化（读屏+点击），与 Android Agent 对等 | 需 Mac 常驻 + 开发者模式 + 定期重签 | 低：只适合研发/测试场景 | ⏸ **冻结**，文档保留（§3.3） |
| **③ 越狱/私有 API** | 接近 Android 的能力 | 极高（设备需特定系统版本 + 越狱） | 差：系统更新即失效，且安全风险不可控 | ❌ **不做**，与项目安全红线冲突 |

**为什么 ① 是「能做的那一半」而不是「退而求其次」**：账号、同步、远程控制这三件事**本来就不依赖设备端自动化能力**——它们是服务端 + 协议层的事。iOS 端把它们做实，就和 Android 端形成了**同一账号下的异构设备网**：iPhone 当控制端/接收端，Android 当执行端。这是**功能**，不是残次品。

---

## 3. 落档决策：保留什么、裁剪什么

### 3.1 保留（本次已实现，有测试）

| 模块 | Swift 文件 | 状态 |
|---|---|---|
| 密码学原语（PBKDF2 / AES-256-GCM 信封） | `Crypto.swift` + `CAgentLiteShim` | ✅ 与 Java 逐字节一致（交叉向量测试） |
| HTTPS 客户端 + **证书 pin** + 服务端错误信封映射 | `HTTPClient.swift` | ✅ 含 pin 拒绝路径 |
| 账号：注册 / 登录 / 恢复登录态 / 退出 | `AccountService.swift` | ✅ 10 项 e2e |
| 设备绑定（指纹稳定、不产生幽灵设备） | `DeviceIdentity.swift` | ✅ e2e 断言「同机重登设备数仍为 1」 |
| 凭据存储（Keychain / 文件后端） | `KeychainStore.swift`, `FileCredentialStore.swift` | ✅ |
| 信息同步（push/pull + 游标） | `SyncService.swift` | ✅ e2e 含幂等性（duplicates）断言 |
| **远程控制：下发 / 领取 / 回报 / 跟踪** | `CommandService.swift`, `CommandInbox.swift` | ✅ e2e 全链路 |
| 敏感操作红线（跨设备指令不继承授权） | `CommandExecutor.swift` | ✅ e2e 断言以 failed+理由回报 |
| 局域网 P2P（与 Android 逐字节互通） | `LANPeer.swift` | ✅ 跨端互解测试 |
| CLI（真机以外验证协议） | `Sources/AgentLiteCLI` | ✅ |

### 3.2 裁剪（本次**不做**，且**不假装能做**）

| 被裁项 | 原因 | 用户可见的后果 |
|---|---|---|
| 视觉感知（截屏理解） | B1：拿不到系统级截屏与其它 App 的界面 | iPhone 不能"看屏幕" |
| 手势注入（点按/滑动） | B2 | iPhone 不能自动操作别的 App |
| 键盘/文本注入到其它 App | 同 B2 | 同上 |
| Android 式常驻后台轮询 | B5 | 指令领取延迟不可控（见 §6） |
| root/沙盒 | iOS 无对应生态 | 不适用 |

★ **最重要的一条产品纪律**：能力之外的任务，iOS 端**必须回报 `failed` 并说明原因**，绝不回报 `done`。
理由：跨设备指令的信任基础是「结果不撒谎」。一次假成功会让用户以为设备能做，从而把关键任务交给它。
（该纪律已固化为测试 `testUnsupportedTaskReportsFailed`。）

### 3.3 路线 ② 的触发条件（什么时候重新打开）

满足**全部**三条时才值得投入 WDA 路线：
1. 使用场景是**研发/测试**（有 Mac、能接受开发者模式常开），而非面向普通用户；
2. 需要的是**回归测试/批量验证**，而不是「用户日常让手机自己干活」；
3. 接受「证书 7 天/1 年一续」的运维成本。

---

## 4. 服务端契约（实测）

> ★ **纪律：以服务端源码为唯一权威，文档措辞不作依据。**
> 本节的字段是在实跑中逐字核对过的；`docs/` 或 README 的措辞与之不符时，**以本节的实测值为准**。
> 权威源文件：`agentlite-server/{main,api,store,account,command}.go`。

### 4.1 端点清单（`agentlite-server/main.go:88-123`）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 免 | 存活检查 |
| POST | `/api/v1/account/register` | 免 | 注册（限流） |
| POST | `/api/v1/account/login` | 免 | 登录（限流） |
| GET | `/api/v1/account/me` | Bearer | 账号档案 + **设备列表** + `lan_key_params` |
| POST | `/api/v1/device/bind` | Bearer | 绑定设备（按 fingerprint 去重） |
| POST | `/api/v1/sync/push` | Bearer + `X-Device-Id` | 上行同步 |
| GET | `/api/v1/sync/pull?since=&limit=` | Bearer + `X-Device-Id` | 下行同步 |
| GET | `/api/v1/config` | Bearer | 服务端配置 |
| POST | `/api/v1/device/command` | Bearer + `X-Device-Id` | **下发**跨设备指令 |
| GET | `/api/v1/device/commands` | Bearer + `X-Device-Id` | **领取**本机待办指令 |
| POST | `/api/v1/device/command/ack` | Bearer + `X-Device-Id` | **回报**指令状态 |
| GET | `/api/v1/device/command?id=` | Bearer | 下发方查结果 |
| POST | `/api/v1/lan/rendezvous` | Bearer + `X-Device-Id` | 局域网会合（取得同账号对端内网地址） |
| POST | `/v1/chat/completions` | Bearer | 额度网关（OpenAI 兼容） |

### 4.2 ⚠️ 三处「文档与实现不一致」——写代码前必看

这三条都是本次**跑端到端测试时才暴露**的真实偏差。iOS 端若照文档写，会直接崩在第一次请求上。

| # | 文档/直觉写法 | **实际字段（权威）** | 后果 |
|---|---|---|---|
| C1 | `lan_key_params.key_length` | **`key_len`**（+ 还有 `algorithm` 字段） | 解码抛 `keyNotFound`，注册/登录**第一跳就失败** |
| C2 | push 响应 `next_seq` | **`server_seq`**，且响应含 **`duplicates`** | 同上，同步不可用 |
| C3 | 把 pull 的 `server_seq` 当已消费游标 | **`server_seq` 为高水位；`next_since` 为本页已交付游标** | 跳过未返回页；消费成功后才提交 records 最后 seq（2026-10-11 已修） |

权威定义：`lan_key_params` 见 `account.go:72-77`；push 响应见 `api.go:349-355`；pull 响应见 `api.go:370-376`。

### 4.3 关键请求/响应形状（照抄可用）

**注册** `POST /api/v1/account/register`
```json
// 请求
{"name": "myaccount", "key": "至少8位"}
// 200 响应（accountPayload, api.go:207）
{
  "account_id": "a_…", "name": "myaccount", "token": "…",   // token 仅此一次返回明文
  "quota": 500, "quota_used": 0,
  "created_at": "2026-09-13T04:03:21Z",
  "lan_key_params": {"algorithm":"PBKDF2WithHmacSHA256","iterations":100000,"key_len":32,"salt_prefix":"agentlite-lan|"},
  "server_time": "2026-09-13T04:03:21Z"
}
```
校验规则（`account.go:92`）：账号名 **3–32 位**，仅小写字母/数字/`_`/`-`（服务端会先 `ToLower`）；账号密钥 **≥8 位**。
错误码：`account_exists`(409)、`bad_input`(400)、`bad_credentials`(401, 注册/登录失败统一文案，**不区分账号不存在与密钥错**——防账号枚举)、`rate_limited`(429)。

**绑定设备** `POST /api/v1/device/bind`
```json
{"fingerprint":"ios-<32hex>","name":"iPhone-1a2b","model":"iPhone","brand":"Apple","sdk":26,"app_version":"0.1.0"}
→ {"device_id":"d_…","account_id":"a_…","created":true,"server_time":"…"}
```
- `fingerprint` 必填、**≤256 字符**，只在同一账号域内幂等复用；另一已归属账号的同指纹不接管、不继承状态（[BindDevice](<../agentlite-server/store.go#L232>)）。
- `device_id` 由服务端签发；**后续所有设备级接口都必须带 `X-Device-Id`**，否则 400 `missing_device`（`api.go:146`）。

**下发指令** `POST /api/v1/device/command`
```json
{"to_device":"d_目标设备","task":"[跨设备指令] 打开设置"}   // task ≤512 字符
→ {"command":{"command_id":"c_…","account_id":"…","from_device":"d_我","to_device":"d_对方",
              "task":"…","status":"pending","created_at":"…","expires_at":"…"}}
```
错误码：`command_self`(400, 不能给自己下)、`bad_target`(403, 目标不属于本账号)、`bad_input`(400, 含**限流**：每账号每分钟最多 10 条，`command.go:116`)。

**领取指令** `GET /api/v1/device/commands` → `{"commands":[…],"count":N}`
- ★ **只返回 `status == "pending"` 的指令**（`command.go:149`）。
- 超出领取 TTL（**5 分钟**，`CommandTTL`）的 pending 指令不再投递，领取扫描将其标记 `expired`；终态保留 **24 小时**供查询。

**回报** `POST /api/v1/device/command/ack`
```json
{"command_id":"c_…","status":"done","detail":"执行结果摘要（服务端按256上限截断）"}
```
- **`status` 只接受 `running` / `done` / `failed`**；没有 `cancelled`，错误/取消理由放入 `failed.detail`。
- 只有**目标设备本人**可 ack：非目标设备 → 403 `not_target`；不存在/领取超期 → 404。
- **同目标设备、同终态、同截断后的 detail 重复 ACK 幂等**；冲突终态 → 409 `already_acked`。重复 ACK 不修改 `updated_at`，不延长结果保留期。
- **终态仍由原 command 对象承载**：`GET /api/v1/device/command?id=...` 在 `CommandResultTTL = 24 小时`内返回 status/detail。下发方直接查询该对象，不另经 `/sync/push` 建一套回执通道。
- 当前指令仍存于服务进程内，审计流水不在启动时重放；**重启或保留期结束后可能 404**。结果本地保存与服务端持久队列是不同保证。
- 此契约已由[服务端接口回归](<../agentlite-server/interface_correctness_test.go>)与[iOS 真实 TLS E2E](<../ios/Tests/AgentLiteCoreTests/EndToEndTests.swift>)验证；本次未部署线上，详见[实施报告](<INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md#33-指令一个对象不另造-sync-回执旁路>)。

**指令状态机（服务端全集）**：`pending → running → done|failed`，另有服务端自标的 `expired`。

### 4.4 同步语义

- `seq` 由服务端**按账号**单调分配，客户端按 records 最后 seq 推进；不用本地时间或 `server_seq` 高水位替代已消费事实。
- `next_since` 若存在须与本页最后 seq 一致；旧服务端缺该字段时仍从 records 计算。`consume` 成功、未取消且认证代次不变后才存游标；无 `consume` 为纯获取，不持久化游标。
- 游标按 `sync.cursor.v2.<accountID>` 隔离，旧无账号归属 v1 从 0 幂等重放；中途消费或存盘失败保留原游标。
- `data` 是**不透明 JSON**：服务端不解释（`api.go:310`），客户端也不能丢字段 → Swift 侧用 `JSONValue` 原样承载。
- 上行必须带 `origin_device_id`，否则服务端记成「本机产生」——数据不丢但**溯源错**（`SyncClient.java:451` 记录过该坑）。
- 幂等：按记录 `id` 去重，重复推送计入 `duplicates` 而非重复落库。

---

## 5. 局域网 P2P 与 iOS 的特有约束

### 5.1 线协议（与 Android 逐字节互通，`agentlite-server/README.md §4`）

```
key = PBKDF2-HMAC-SHA256(password = 账号密钥, salt = "agentlite-lan|" + account_id,
                         iter = 100_000, len = 32)
POST http://<peer>:18444/lan/v1/sync
body = base64( nonce(12B) || AES-256-GCM(plaintext, key, nonce, aad) )   // tag 128 位
aad  = "agentlite-lan-v1|" + account_id
```
抗重放**两条都要**：`|now - ts| ≤ 120s`，且 `(device_id, nonce)` 未见过（滚动窗口 5 分钟）。
只做时间窗是不够的——重放旧报文时密文本身完全合法。

### 5.2 ★ AAD 里为什么没有 device_id（务必照抄）

解密前拿不到发送方 `device_id`（它在密文里），若 AAD 绑它就成了鸡生蛋。
折中：**AAD 只绑 `account_id`，`device_id` 在明文内校验**。明文受 GCM 保护，所以来源绑定强度没有降低，只是校验点从 AAD 移到了明文。
Android 原始记录在 `agent-lite/src/com/dsh/agentlite/LanSync.java:305-308`。**iOS 侧不照做就会与 Android 互解失败。**

### 5.3 ★ iOS 只能是 P2P 的**发起方**

Android 端在局域网接口**常驻 `ServerSocket` 监听**（`LanSync.java:220`）；iOS **做不到**：App 进后台后监听 socket 会被挂起/回收，没有「一直监听」这回事。

⇒ **由 Android 侧承担监听方，iOS 侧主动连接。** 这不是妥协实现，而是平台事实。
后果：iPhone→Android 的局域网同步可用；Android→iPhone 的**入站**连接在 iOS 前台时勉强可用、后台必然不可用。
**需要"别人主动连 iPhone"的场景，一律改走服务端指令队列（§4）**，不要试图打局域网。

### 5.4 已实现且已验证的部分

`LANPeer` 实现了 `seal/open/acceptInbound/respondInbound` + 抗重放缓存，测试覆盖：
- 与 Java 生成的密文**逐字节互解**（`CryptoTests.testOpenJavaEnvelope`）
- 重放被拒、超时窗被拒、**跨账号密文被拒**
这些是「iOS 能参与局域网」的充分证明（在 Mac 上完成验证，真机只是换运行环境）。

---

## 6. 后台执行模型（iOS 端最大的工程约束）

### 6.1 约束

| 平台 | 机制 | 指令领取延迟 |
|---|---|---|
| Android | 常驻前后台线程轮询（`RemoteCommand.POLL_INTERVAL_MS = 20_000`） | ~20 秒 |
| iOS | **无**常驻后台；`BGAppRefreshTask` 由系统调度 | **不可控**（可能几分钟到几十分钟） |

★ 而指令 **TTL 只有 5 分钟**。因此：
> **iOS 作为"被控端"的可靠性本质低于 Android。** 这一点必须写进 UI 预期，不能靠工程手段消除。

### 6.2 采取的方案（按优先级）

1. **前台实时**（可靠）：App 在前台时轮询（间隔建议 5–10 秒），这是主路径。
2. **BGAppRefresh / BGProcessingTask**：注册后台刷新，被唤醒时执行一次完整收取。
3. **静默推送（APNs `content-available:1`）**：现状服务端**没有** APNs 通道，属于未完成项（§9）。
4. **UI 明示**：在"被控端"界面标注「iPhone 可能延迟响应；需要即时执行请用 Android 设备」。

### 6.3 ★ 单次收取的顺序纪律（踩过坑，必须照抄）

```
拉取 pending → 逐条【执行】→ 逐条【回报终态】
禁止：先 ack running 再执行
```

理由（读源码得出）：`PendingFor` 只返回 `status == pending` 的指令（`command.go:149`）。
一旦先 ack 成 `running`，这条指令就**不再出现在待办列表**里；此时若执行途中断电/被系统杀进程，
它**永远不会被再次领取**，下发方只能一直看到 `running` —— **一个不可恢复的中间态**。
先执行再 ack 则相反：中途失败顶多「这次没回报」，下次唤醒仍能领到并重做。
代价是任务可能被执行两次，所以**本地动作应写成幂等的**；跨设备指令以「最终一定有结果」优先。

### 6.4 预算控制

单次收取设**时间预算**（默认 25 秒，`CommandInbox.defaultBudget`）：iOS 后台窗口有限，
被系统强杀会导致 ack 丢失、下发方以为没生效而重复下发。**宁可少执行一条**，也不要被强杀。

---

## 7. 安全纪律（四条红线）

### 7.1 证书 pin（不可关闭）

服务端用**自签证书**（`agentlite-server/README.md §5`），系统信任链验不过。
实测：不加 pin 时 URLSession 直接返回 `-1202 NSURLErrorServerCertificateUntrusted`。

两条路只能选一条：
- ❌ 放开 ATS / 信任任意证书 → 等于把中间人攻击面彻底打开；
- ✅ **把服务端证书 SPKI SHA-256 随 App 下发，逐次比对**（本端采用）。

- 用 **SPKI** 而不是整证书哈希：续签同一把密钥时整证书哈希会变而公钥不变，减少"换证书就要发版"的次数，且不降低安全性。
- **空 pin 集合 = 拒绝一切连接**（安全默认，不是"不校验"）。
- 取指纹：`swift run agentlite-ios pin --cert agentlite-server/tls/server.crt`。
- 仅**环回地址**允许免 pin（本地联调），且是编译期 `precondition` 强制的 —— 防止这条捷径被带到线上。

### 7.2 凭据存储

| 项 | 做法 |
|---|---|
| 账号令牌 / 账号密钥 / 局域网密钥 | **Keychain**，`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`（不随 iCloud 备份迁移） |
| 设备指纹 | Keychain（重装 App 不产生幽灵设备） |
| CLI/测试 | 文件后端，**权限 0600**，且与 Keychain 后端**类型上分开**（不可互换） |

★ 为什么账号密钥必须留在设备上：局域网对称密钥由它 PBKDF2 派生，**服务端不经手**。
若要每次同步都让用户重输账号密钥，就等于没有「局域网优先」这个特性。

### 7.3 敏感操作不继承授权（与 Android 同纪律）

跨设备指令**不继承下发方的任何授权**。命中支付/转账/验证码/删除/发送等关键词时：
**执行前**直接拒绝（`SensitiveGuard`），并以 `failed` + 理由回报。
e2e 已断言「拒绝发生在到达 provider 之前」（调用次数为 0）。

### 7.4 隐私：不采集硬件标识

设备指纹是**自己生成的随机 ID**，不是 UDID/IDFV/序列号；上报的 `model` 只到 `iPhone`/`iPad` 粒度。
理由：硬件标识属于不可重置的追踪标识，上报它既过不了审核也没必要。

---

## 8. 构建与验证

### 8.1 跑测试（真机以外即可验证协议）

```bash
cd ios

# ① 离线单测（密码学交叉向量 + 线协议 + 安全红线）——无需网络
swift test --filter "CryptoTests|ProtocolTests"
#    期望：32 项全绿

# ② 端到端（真连服务端，需先起一个本地服务端，见下）
AGENTLITE_E2E_BASE="https://127.0.0.1:18443" \
AGENTLITE_E2E_CERT="../_local/ios-e2e/tls/server.crt" \
  swift test --filter EndToEndTests
#    期望：10 项全绿
```

**起本地服务端**（不碰线上，数据落在 `_local/`）：
```bash
mkdir -p _local/ios-e2e/tls && cd _local/ios-e2e/tls
openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes \
  -keyout server.key -out server.crt -subj "/CN=127.0.0.1/O=AgentLite" \
  -addext "subjectAltName=IP:127.0.0.1,DNS:localhost"
cd ../../../agentlite-server
./agentlite-server -addr 127.0.0.1:18443 \
  -data ../_local/ios-e2e/data \
  -cert ../_local/ios-e2e/tls/server.crt -key ../_local/ios-e2e/tls/server.key \
  -register-rate 5000 -register-window 1m
#   ↑ 端到端用例会多次注册账号，务必放开注册限流（默认每 IP 10 次/10 分钟）
```

### 8.2 CLI（手工联调 / 真机以外验证）

```bash
cd ios && swift build
export AGENTLITE_BASE="https://8.133.225.105:18443"
export AGENTLITE_PIN="$(swift run -q agentlite-ios pin --cert ../agentlite-server/tls/server.crt)"

swift run agentlite-ios register --name myacct --key 至少八位密钥
swift run agentlite-ios whoami            # 账号 / 额度 / 设备列表
swift run agentlite-ios devices           # 同账号的其它设备
swift run agentlite-ios send --to d_xxx --task "打开设置"
swift run agentlite-ios watch --id c_xxx  # 轮询到终态，读取原 command 的结果
swift run agentlite-ios collect           # 本机作为目标设备收件

# ★ 当前源码保留终态24小时；服务重启或保留期结束后可能404。
#   本次未部署，联调实例需先确认已具有该契约（见§4.3）。
```

### 8.3 iOS App（Xcode 工程 + 模拟器 + 真机）

App 外壳在 **`ios/App/`**，已生成完整 Xcode 工程（App target + 单元测试 target，
并把 `ios/` 这个 Swift Package 作为**本地包依赖**接入）。

```bash
# 1) 生成联调证书资源（证书是构建输入：证书 pin 的前提是"证书在包里"）
bash ios/App/scripts/prepare-resources.sh
#    也可用现成证书：--from agentlite-server/tls/server.crt

# 2) 工程文件是**脚本生成**的，不要手改
python3 ios/App/generate-project.py           # 重新生成
python3 ios/App/generate-project.py --check   # 校验是否漂移（门禁用）

# 3) 构建 + 在模拟器上跑测试
bash ios/build-mac.sh --simulator
```

**工程文件为什么用脚本生成**：`.pbxproj` 是 Xcode 私有格式，全靠 24 位 UUID 互相引用，
手写极易产生"少一位"这类肉眼不可见、只在 Xcode 打开时炸的缺陷。用
`ios/App/generate-project.py` 生成，引用关系由代码保证自洽，改一个设置不必在千行文件里搜；
`--check` 进构建脚本，防止"Xcode 里手改过、脚本重建后行为不同"的漂移。

**已实现**：SwiftUI 四屏（账号 / 设备 / 远程 / 空状态）、后台刷新（`BGAppRefreshTask`）、
本地动作提供方（URL Scheme + 快捷指令，能力边界写入 UI）、Keychain 凭据、证书 pin。

#### 模拟器运行的前置条件（★ 本机实测踩到）

> **Xcode 的 SDK 版本必须与已安装的模拟器运行时版本匹配**，否则
> `xcodebuild` 的 destination 不可用，报错形如：
> `Ineligible destinations ... error: iOS 26.5 is not installed`，
> 同时 `xcodebuild` 日志里会出现 `Unable to discover any Simulator runtimes`。
>
> 注意 `xcrun simctl list runtimes` **显示正常、设备也能 boot**，但 `xcodebuild` 依然用不了 ——
> 这两个工具对"可用运行时"的判定口径不同，别被 simctl 的正常输出误导。

排查与修复：
```bash
xcodebuild -showsdks | grep -i simulator    # 看 SDK 版本（如 26.5）
xcrun simctl list runtimes                  # 看运行时版本（如 26.4）—— 两者需匹配
xcodebuild -downloadPlatform iOS            # 补齐匹配的运行时（约数 GB）
# 或：Xcode → Settings → Components 里下载
```
**本机（2026-09-13）实况 —— 当前就卡在这里**：

| 项 | 实测值 |
|---|---|
| Xcode | 26.6（`DTXcode 2660`） |
| 随附 SDK | iOS **26.5**（`xcodebuild -showsdks`） |
| 已装模拟器运行时 | iOS **26.4**（`23E254a`），**低于 SDK** |
| `xcrun simctl list devices booted` | ✅ iPhone 17 Pro 能 boot |
| `xcodebuild -showdestinations` | ❌ 只有 generic device，报 `iOS 26.5 is not installed` |
| CoreSimulator 日志 | `Unable to discover any Simulator runtimes` |

即 **运行时版本 < SDK 版本 → xcodebuild 判定设备不合格**，而 simctl 仍能 boot ——
两个工具口径不同，别被 simctl 的正常输出误导。

**修复动作（需要在 Xcode GUI 里做，命令行下载实测卡死）**：
`Xcode → Settings → Components` 下载 **iOS 26.5** 模拟器运行时；
装好后 `bash ios/build-mac.sh --simulator` 即可跑通。
（`xcodebuild -downloadPlatform iOS` 在本机跑到 5 分钟时仍是 0% CPU、无网络连接、
缓存目录无增长 —— 已中止：它卡在等待/超时，不是真的在下载。）

#### 真机运行

| 项 | 现状 |
|---|---|
| 签名身份 | **0 个**（`security find-identity -v -p codesigning` 为空） |
| 真机 | 未连接（`xcrun devicectl list devices` 无设备） |

真机部署需要：登录 Apple ID（免费账号 7 天有效，重签即可续）或开发者账号（1 年），
在 Xcode 里给 `AgentLiteIOS` target 指定 Team 后即可安装。免费账号的限制是
**无法使用需要 entitlement 的能力**（本项目当前不需要，APNs 推送才需要）。
另外真机联调要用局域网地址（`prepare-resources.sh` 已把宿主局域网 IP 写进证书 SAN）。

### 8.4 交叉验证用到的 Java 向量生成器

`_local/ios-e2e/java/EmitVectors.java`：用与 `LanSync.java` **逐行相同**的参数生成密钥与密文，
让 Swift 侧的期望值不是「自己算自己」，而是**由 Java 侧真实产出**。
（这是本项目一贯做法：`tests/verify_lan_p2p.sh` 用桌面 JVM 做对端模拟，同一思路。）

---

## 9. 未完成项与下一步

### 9.1 验收与历史跟进清单

> 2026-10-11 本轮更新 U3/U3b；其他项为原手册历史记录，本次未探测线上、安装设备或引入 APNs。本地源码及构建验证详见[实施报告](<INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md#6-验证结果与证明边界>)。

| # | 项 | 状态 | 阻塞原因 |
|---|---|---|---|
| U1 | iPhone 真机端到端 | ❌ 未做 | 无签名的 Apple ID、无真机连接（`devicectl` 无设备） |
| U1b | 模拟器端到端 | ⏸ 待运行时 | App 与测试已就绪；Xcode SDK 26.5 与已装运行时 26.4 不匹配，`xcodebuild` destination 不可用（§8.3） |
| U2 | 线上服务端连通性 | ⚠️ 未验证 | 编写时 `8.133.225.105:18443` **连接被拒**（`curl: (7) Couldn't connect`）。需确认服务是否在跑、安全组是否放行 |
| U3 | **指令终态查询与 ACK 幂等** | ✅ 2026-10-11 已完成本地实现/Go回归/Swift TLS E2E，未部署 | 原 command 对象保留终态24小时，无另建 sync 回执；服务重启仍丢队列（§4.3） |
| U3b | 字段契约与分页措辞 | ✅ 当前分页/归属/回执说明已同步 | `server_seq` 高水位与 `next_since` 已交付游标分离；字段名以 Go 源码和跨端契约门禁为准，见§3.2/§4.4 |
| U4 | APNs 静默推送通道 | ❌ 未做 | 服务端无 APNs 集成；这是 iOS 被控端可靠性的关键缺口 |
| U5 | SwiftUI 界面 | ✅ 已做 | `ios/App/`（账号 / 设备 / 远程 + 空状态），已对模拟器 SDK 通过类型检查 |
| U6 | App Intents / 快捷指令动作集 | ❌ 未做 | 需要真机确认动作授权流程（`LocalActionProvider` 协议已就位） |
| U7 | `lan/rendezvous` 调用封装 | ❌ 未做 | 服务端接口已就绪（`api.go:115`），iOS 侧尚未接（`LANPeer` 已能直连，缺"自动发现对端"） |

### 9.2 建议的下一步顺序

1. **先修 U2**：确认线上服务可用性（这决定所有真机联调能否进行）。
2. **验收已实现的 U3 契约**：目标设备回报原 command，下发方直接查询 status/detail；无另建同步回执。先在数据副本验证迁移，再明确部署；线上终态24小时可见、幂等 ACK 与服务重启边界需实测。（数据副本上的迁移验证本机已完成：`make drill`，48 项断言全过；零 root 真机行为验收也已在真机跑通：`tests/credential_real_device.py` 44 项、`tests/t03_t05_real_device.py` 15 项断言全过。）
3. **补齐模拟器运行时**（§8.3）：`xcodebuild -downloadPlatform iOS`，之后
   `bash ios/build-mac.sh --simulator` 即可在 iOS 运行时里跑 Keychain / ATS / 端到端测试。
4. **U6 动作集**：先落 3–5 个高频本地动作（打开 App、记笔记、查日程），验证 App Intents 授权流程。
5. **U4 APNs**：服务端加推送通道 + iOS 注册 device token。这是让 iOS 被控端**真正可用**的关键。
6. **U1 真机验证**：以上完成后一次性验证。

---

## 10. 已知坑（照抄，避免重踩；K1–K13 核心库 / K14–K18 iOS App）

| # | 坑 | 现象 | 规避 |
|---|---|---|---|
| K1 | `Never` 当 `Encodable` 占位 | macOS 14 以下编译告警，Swift 6 模式升级为**错误** | 用 `EmptyBody` 空结构体 |
| K2 | SPM 的 C 目标公共头不能 `#include <CommonCrypto/...>` | 头文件解析失败，`CCKeyDerivationPBKDF` 找不到 | 公共头只留 `int` + 基本类型签名，CommonCrypto 只在 `.c` 里 include；另需单独 include `CommonCryptoError.h`（`kCCParamError` 在该头） |
| K3 | `import CommonCrypto` 在 Swift 6 工具链下不导出 PBKDF2 | `cannot find 'CCKeyDerivationPBKDF' in scope` | 同上，走自建 C shim |
| K4 | pinner 挂**空 pin 集合** | 每个请求都失败，错误被误报成"证书不匹配（可能是中间人）" | 空 pin = 拒绝一切；环回联调**不挂 pinner**，而不是挂空 pinner |
| K5 | 测试里用 `URLSession.shared` 扮演对端 | 绕过 pin，测试失去对生产信任链的覆盖 | 测试也走同一个被 pin 的 session |
| K6 | 嵌套在 actor 内的类型写 `Codable` | 推断牵连，报"does not conform to Decodable" | 会跨模块/跨类复用的线格式类型提到顶层（如 `SyncOutgoingItem`） |
| K7 | 服务端注册限流 | 端到端用例跑到一半 429 `rate_limited` | 本地服务端加 `-register-rate 5000` |
| K8 | 自签证书 + ATS | `-1202 NSURLErrorServerCertificateUntrusted` | 必须 pin（§7.1） |
| K9 | 文档字段名与实现不符（C1–C3） | 第一跳请求就解码失败 | **以服务端 Go 源码为准**，落盘前用一次实跑核对 |
| K10 | 先 ack `running` 再执行 | 指令永久停在 running，不可恢复 | 先执行、后 ack 终态（§6.3） |
| K11 | 把当前终态查询误判为必然404 | 下发方丢失已执行结果 | 当前原 command 保留终态24小时；重启/过期才可能404，结果及时本地保存 |
| K12 | 用 `cancelled` 作为 ack 状态 | 400，指令状态不变 | 服务端只有 `running/done/failed`；取消/执行失败使用 `failed.detail` |
| K13 | 为结果另建 sync 回执 | 同结果出现两份事实源和多余请求 | 直接查询原 command；同终态 ACK 幂等，不延长24小时保留期 |
| K14 | 用 `ContentUnavailableView` | 报错：仅 iOS 17+ 可用（部署目标是 16） | 用 `EmptyStateView`（`ios/App/AgentLiteIOS/EmptyStateView.swift`）；不要为了少写 30 行把最低版本抬到 17 |
| K15 | `BGTaskScheduler.register(forTaskID:using:)` | 编译报「extra argument 'forTaskID'」 | 正确签名是 `register(forTaskWithIdentifier:using:launchHandler:)` |
| K16 | 核心库内部工具函数不给宿主用 | 宿主 App 报「inaccessible due to internal protection level」 | 只应有一份判断的函数（如 `HTTPClient.isLoopback`）要 `public` |
| K17 | 用裸 `swiftc -typecheck` 验 XCTest 测试文件 | 报 `cannot find 'XCTAssertEqual' in scope`（XCTest 断言是宏） | 测试文件只能由 `xcodebuild test` 编译；裸 swiftc 可验 App 源码，验不了测试 |
| K18 | 全项目 `swift test` 通过就以为 iOS 也 OK | macOS 上过 ≠ iOS 上过（Keychain 属性、ATS、沙盒路径都不同） | `ios/App/AgentLiteIOSTests/SimulatorTests.swift` 专测这些差异 |

---

## 附：文件清单

```
ios/
├── Package.swift                          # iOS 16+ / macOS 13+，核心库不依赖 UIKit
├── Sources/
│   ├── CAgentLiteShim/                    # C shim：把 CommonCrypto PBKDF2 暴露给 Swift
│   ├── AgentLiteCore/
│   │   ├── Crypto.swift                   # PBKDF2 / AES-GCM 信封 / 常量（跨端硬约束）
│   │   ├── HTTPClient.swift               # HTTPS + 证书 pin + 错误信封映射
│   │   ├── Models.swift                   # 服务端契约的 Swift 映射（字段逐字对应）
│   │   ├── AccountService.swift           # 注册/登录/恢复/绑定/设备列表
│   │   ├── SyncService.swift              # push/pull + 游标
│   │   ├── CommandService.swift           # 下发/领取/回报/跟踪
│   │   ├── CommandInbox.swift             # 拉取→执行→回报 的可重入收取
│   │   ├── CommandExecutor.swift          # 执行器 + 能力边界 + 敏感红线
│   │   ├── LANPeer.swift                  # 局域网 P2P（与 Android 互通）
│   │   ├── KeychainStore.swift            # Keychain / 内存后端 + 凭据仓库
│   │   ├── FileCredentialStore.swift      # CLI/测试用文件后端（0600）
│   │   ├── DeviceIdentity.swift           # 设备指纹（稳定、非硬件标识）
│   │   ├── PlatformInfo.swift             # 平台探测（如实上报，不伪装 Android）
│   │   └── AgentLiteClient.swift          # 顶层门面
│   └── AgentLiteCLI/main.swift            # 命令行外壳（真机以外验证协议）
├── Tests/AgentLiteCoreTests/
│   ├── CryptoTests.swift                  # 13 项：与 Java 逐字节交叉验证
│   ├── ProtocolTests.swift                # 19 项：线格式 / 红线 / 抗重放
│   └── EndToEndTests.swift                # 10 项：真连服务端全链路
└── App/                                   # iOS App（Xcode 工程，见 §8.3）
    ├── generate-project.py                # 生成 .pbxproj + scheme（--check 供门禁）
    ├── scripts/prepare-resources.sh       # 生成/导入联调证书 + 打印 SPKI 指纹
    ├── AgentLiteIOS.xcodeproj/            # 生成物
    ├── AgentLiteIOS/
    │   ├── AgentLiteIOSApp.swift          # 入口（注册后台任务）
    │   ├── AppModel.swift                 # SwiftUI ↔ 核心库的桥（@MainActor）
    │   ├── RootView.swift / AccountView.swift / DevicesView.swift / RemoteView.swift
    │   ├── EmptyStateView.swift           # iOS 16 兼容空状态（系统 API 是 17+）
    │   ├── LocalActions.swift             # 本地动作：URL Scheme + 快捷指令（能力边界唯一实现处）
    │   └── BackgroundRefresh.swift        # BGAppRefreshTask
    ├── AgentLiteIOSTests/SimulatorTests.swift   # 模拟器专属：Keychain / ATS / 沙盒 / 端到端
    └── Resources/                         # server.crt（脚本生成，已 gitignore）
```

**测试基线（2026-09-13 实测）**：`swift test` → **42 项全绿**（含 10 项端到端）。
