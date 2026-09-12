# 两端契约表（client ↔ server）

> 本文档描述 AgentLite Android 客户端（`com.dsh.agentlite`，v0.6.0）与 agentlite-server（v0.1.0）
> 之间的**咬合面**。表中每一项都可由 `scripts/verify-contract.py` 从两端源码复核——
> 文档与实现不一致时，以校验器的输出为准（脚本失败会阻塞构建）。
>
> 最后核对：整合版 0.7.0（26 项检查全通过）

---

## 1. HTTP 接口

### 1.1 客户端实际调用的端点（11 处调用点，全部命中服务端路由）

| 方法 | 路径 | 鉴权 | 客户端调用点 |
|---|---|---|---|
| POST | `/api/v1/account/register` | 无（按 IP 限流） | `AccountClient.java:93` |
| POST | `/api/v1/account/login` | 无（按 IP 限流） | `AccountClient.java:98` |
| POST | `/api/v1/device/bind` | Bearer + `X-Device-Id` | `AccountClient.java:164` |
| GET | `/api/v1/account/me` | Bearer | `AccountClient.java:178` |
| POST | `/api/v1/redeem` | Bearer | `AccountClient.java:225` |
| POST | `/api/v1/sync/push` | Bearer + `X-Device-Id` | `SyncClient.java:399 / 434 / 462 / 480`（任务 / 同伴转发 / 记忆 / 状态） |
| GET | `/api/v1/sync/pull?since=N` | Bearer + `X-Device-Id` | `SyncClient.java:488` |
| POST | `/api/v1/lan/rendezvous` | Bearer + `X-Device-Id` | `SyncClient.java:565` |
| POST | `/v1/chat/completions` | Bearer（= 账号令牌） | `LlmClient.java`（经 `chat()`；网关模式可达） |

### 1.2 服务端已实现、本客户端未调用（加性余量，不是遗漏）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 无需鉴权，供 systemd / 负载与运维探活 |
| GET | `/api/v1/config` | 单独取远程配置（本客户端从 `pull` 响应里取 `config`，故未调用） |
| GET | `/v1/models` | OpenAI 兼容面透传上游模型列表 |
| POST | `/api/v1/admin/codes` | 用 `X-Admin-Token` 批量发兑换码（仅运维） |

### 1.3 不经服务端的端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/lan/v1/sync` | **设备 ↔ 设备** P2P 监听端点（`LanSync.java:41`）。服务端只做会合介绍，数据不过服务器 |

### 1.4 已下线（双方都不得再使用）

| 路径 | 状态 |
|---|---|
| `/api/v1/device/register` | 设备令牌时代接口，客户端与服务端均已移除；客户端源码中仅存注释说明 |

---

## 2. 局域网 P2P 线协议（v1）

两端必须**逐字节一致**的常量（错一个字节 = 永远连不通，且症状是静默的认证失败）：

| 项 | 客户端取值 | 服务端取值 | 客户端位置 |
|---|---|---|---|
| 监听端口 | `18444` | `18444`（`unified.conf` 的 `LAN_PORT`） | `LanSync.DEFAULT_PORT` / `SyncClient.LAN_PORT` |
| KDF 算法 | `PBKDF2WithHmacSHA256` | `Algorithm: "PBKDF2WithHmacSHA256"` | `LanSync.deriveKey()` |
| KDF 迭代数 | `100_000` | `LANKeyIterations = 100_000` | `LanSync.KDF_ITERATIONS` |
| KDF 密钥长度 | `256` bit | `LANKeyLen = 32` 字节 | `LanSync.KDF_KEY_BITS` |
| KDF 盐前缀 | `agentlite-lan\|` | `LANKeySaltPrefix = "agentlite-lan\|"` | `LanSync.KDF_SALT_PREFIX` |
| AAD 前缀 | `agentlite-lan-v1\|` | 线协议文档 §4.2 | `LanSync.AAD_PREFIX` |
| nonce 长度 | `12` 字节 | 文档 §4.2 | `LanSync.NONCE_LEN` |
| GCM tag | `128` bit | 文档 §4.2 | `LanSync.GCM_TAG_BITS` |
| 抗重放时间窗 | `±120` 秒 | 文档 §4.3 | `LanSync.TS_WINDOW_SEC` |
| 报文路径 | `/lan/v1/sync` | 文档 §4.2 | `LanSync.PATH` |
| 密钥盐来源 | `salt = 前缀 + account_id` | `lan_key_params` 由 `/api/v1/account/*` 下发 | `LanSync.deriveKey()` |

**信任模型**：服务端只公布派生参数，**不经手局域网对称密钥**——服务器失陷也无法解密 P2P 流量。
按服务端选型说明，原需求写的是 HKDF，实现改用 PBKDF2（Java 标准库原生支持、且对低熵口令有拉伸），
两端参数以服务端 `lan_key_params` 为准以避免漂移。

---

## 3. 错误码

### 3.1 客户端有中文提示的错误码（`ApiException.readable()` 的 case 表）

| 错误码 | 服务端 HTTP | 客户端文案 |
|---|---|---|
| `account_exists` | 409 | 账号名已被占用 |
| `bad_credentials` | 401 | 账号名或账号密钥错误 |
| `bad_input` | 400 | 请求参数不合法 |
| `bad_payload` | 400 | 设备信息不合法 |
| `missing_token` | 401 | 尚未登录，请先登录账号 |
| `invalid_token` | 401 | 登录状态已失效，请重新登录 |
| `device_not_bound` | 403 | 本机未绑定到当前账号，正在重新绑定 |
| `code_invalid` | 404 | 兑换码无效或已被使用 |
| `rate_limited` | 429 | 操作过于频繁，请稍后再试 |
| `body_too_large` | 413 | 上行数据过大，已跳过本次同步 |

### 3.2 客户端按状态码做行为分支的语义（必须与服务端一致）

| 客户端判定 | 依赖 | 服务端 |
|---|---|---|
| `isAuthFailure()` → 标记登录失效、引导重新登录 | `httpCode == 401` | `invalid_token` / `missing_token` / `bad_credentials` 均 401 |
| `isDeviceUnbound()` → 自动重新绑定设备 | `403` 或 `kind == "device_not_bound"` | `device_not_bound` → 403 |
| 限流提示 | `429` | `rate_limited` → 429 |

### 3.3 服务端会返回但客户端无专门文案（走兜底分支）

`bad_json`、`bad_port`、`missing_device`、`not_found`、`internal`、`upstream_error`、
`upstream_read`、`gateway_disabled`、`admin_disabled`、`bad_admin_token`、`quota_exhausted`（402）。

> `quota_exhausted`（402）客户端未单独出文案，但额度展示会随之下降——属**已知的体验缺口**，
> 不是契约错误。若要在客户端补文案，属于加性改动（客户端可在服务端不改的情况下先上线）。

---

## 4. 字段契约

| 方向 | 字段（校验器实抽） |
|---|---|
| 客户端**发送**（16） | `app_version` `brand` `code` `data` `fingerprint` `id` `key` `lan_ips` `memories` `name` `origin_device_id` `port` `sdk` `status` `tasks` `ts` |
| 客户端**读取**（27） | `accepted` `app_version` `brand` `config` `data` `device_id` `devices` `duplicates` `error` `id` `lan_ips` `lan_port` `last_seen_at` `message` `name` `origin_device_id` `peers` `quota` `quota_used` `sdk` `seen_sec` `server_seq` `token` `ts` `units_added` `values` `version` |

**不透明透传（不算契约字段）**：服务端把 `status`（`Device.LastStatus`）与 `data`
（`Incoming.Data`）都声明为 `json.RawMessage` —— 原样存取、从不解析。因此嵌在其中的
客户端自有键（`a11y` `app` `battery` `charging` `model` `root_mode` `text` `time` `tokens_total`）
不参与契约校验，服务端也不该解释它们。

**设计取舍（服务端 README §8）**：`decodeBody` 刻意不启用 `DisallowUnknownFields`，
这样客户端先于服务端升级时，新字段被忽略而不是让整批同步 400 失败。代价是拼写错误会被
静默忽略 —— 因此校验器专门做了「客户端发送字段是否被服务端认识」这一项，且冒烟测试必须
断言 `accepted` 计数而不能只看 HTTP 状态码。

---

## 5. 远程配置与安全红线

服务端从 `/var/lib/agentlite-server/config.json` 下发 `{version, values}`；**白名单在客户端执行**：

| 允许下发的键 | 效果 |
|---|---|
| `max_steps` | 单任务最大步数 |
| `planner_model` | 规划模型 |
| `vision` | 视觉感知开关 |
| `spec_inject` | 屏幕形态注入开关 |
| `spec_clamp` | 触摸坐标钳制开关 |

**红线**：`base_url` 与 `api_key` 既不在白名单、也不在 `applyConfigKey` 的落地分支里。
否则服务端一旦失陷，就等于拿到一条「改写客户端出站地址 → 定向窃取 API Key」的通道。
白名单外的键一律走 `default:` 忽略。

> 信任边界在**客户端**：服务端只转发 `config.json` 的内容，是客户端拒绝越权键。
> 因此校验器检查的是客户端的白名单与落地分支（这也是唯一能保证红线的地方）。

---

## 6. 计量

| 项 | 值 |
|---|---|
| 记账单位 | 1 unit = 1000 tokens |
| 扣费公式 | `units = ceil(tokens / 1000)`，下限 1 unit（`gateway.go`） |
| 用量来源 | 上游响应的 `usage.total_tokens`；流式取带 `usage` 的最后一个 chunk |
| 上游报错 | 原样透传且**不计费**（`upstream_error` / `upstream_read` 在计费之前返回） |
| 额度不足 | 402 `quota_exhausted` |
| 已知边界 | 「先检查后扣减」，并发下可能小幅超支（服务端 README §3.4 已声明） |

---

## 7. 契约变更规则

本系统**没有 API 版本化**：客户端与服务端按整合版一起发布（`dist/RELEASE.json` 记录配对关系）。
因此变更按下列规则处理：

**加性（安全，可单独上线）**
- 新增端点、新增可选字段、新增错误码（客户端对未知 `error` 走兜底文案）
- 服务端先支持新字段 → 客户端再开始发送（服务端已刻意忽略未知字段，反向也安全）

**破坏性（禁止单边上线，必须两端同发布）**
- 删改字段语义/类型、收紧校验、改错误码名、改 URL 结构
- 改 LAN KDF / AEAD 参数 —— 这是最危险的一类：**老客户端的 P2P 会静默失效**，必须两端同发布

**改完必须跑**（顺序即依赖）：

```bash
bash scripts/sync-sources.sh      # 上游改动同步进整合层
python3 scripts/verify-contract.py  # 契约是否仍然对齐（11 项路由 + 11 项密码学参数 + 字段 + 错误码）
bash scripts/e2e-local.sh         # 动态验证：真起服务端 + 真跑客户端 pin
bash scripts/selftest-verifier.sh # 确认门禁本身没被改瞎（变异测试）
```
