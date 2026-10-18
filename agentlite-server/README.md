# agentlite-server — 账号 / 同步 / 额度网关

面向 AgentLite Android 客户端（`agent-lite`，包名 `com.dsh.agentlite`）的云端服务。
独立部署在阿里云 ECS `8.133.225.105`，与 `_local/SERVER_TECHNICAL_HANDBOOK` 描述的
LLM 中继站（`23.185.168.190`）**是两套互不相干的系统**，不要混用。

> **2026-10-11 本地源码更新**：[接口收敛实施报告](<../docs/INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md>)记录分页、指令结果、账务事务和存储索引的新契约及验证。本轮未部署；线上实例版本另行实测确认。

## 1. 能力范围

| 能力 | 说明 |
|---|---|
| 账号体系 | 账号名 + 账号密钥（本地账号，不涉及手机号/短信，无备案与短信资质依赖） |
| 账号级共享 | 记录流按**账号**归档，同账号所有设备共享历史/记忆/配置；每条带来源 `device_id` |
| 局域网优先 | 服务端只做会合，同账号同出口的设备走 P2P 直连，数据不过服务器 |
| 额度网关 | OpenAI 兼容转发到 DashScope，按权威 `usage` 扣减账号额度；额度不足返回 402 |
| 兑换码 | 应用内兑换码充值额度；管理接口批量发码 |

## 2. 技术选型与理由

- **Go + 纯标准库，零第三方依赖**。项目其他组件（`agentd-go`、无 Gradle 构建）也是零依赖风格；
  同时规避国内 `GOPROXY` 拉包与 cgo 编译 SQLite 两类部署风险。
- **不用 SQLite**：数据量级（单机数个账号、数万条记录）用 append-only JSONL 足够。
- 单静态二进制约 7MB，**常驻内存约 4–8MB**（ECS 仅 2C/1.8G，这点很重要）。
- 账号密钥派生用标准库 `crypto/pbkdf2`（Go 1.24+），**因此 `go.mod` 要求 `go 1.24`**。

## 3. 接口

### 3.1 账号

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/account/register` | 无（按 IP 限流） | `{name,key}` → `{account_id,token,quota,lan_key_params}` |
| POST | `/api/v1/account/login` | 无（按 IP 限流） | 同上；最多保留10个并行 token，超额删除最旧项 |
| GET | `/api/v1/account/me` | Bearer | 账号信息、额度、名下设备列表 |

- 账号名：3–32 位，仅小写字母/数字/`_`/`-`（服务端会转小写）。重名 → `409 account_exists`。
- 账号密钥：≥8 位。派生参数 PBKDF2-HMAC-SHA256 / 210000 次 / 32 字节，只存派生值 + 16 字节随机盐。
- 新账号赠送 500 units（1 unit = 1000 tokens）。
- 登录失败与账号不存在**返回同一文案**，且账号不存在时也做一次等价开销的派生——抵御账号枚举与时序侧信道。

### 3.2 设备与同步

绑定只需 `Authorization: Bearer <account_token>`；同步、会合及设备指令接口还需 `X-Device-Id: <device_id>`。配置与单条指令查询仅需 Bearer。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/device/bind` | `{fingerprint,...}` → `{device_id,created}`；只在同账号域幂等复用，不接管另一已归属账号的设备 |
| POST | `/api/v1/sync/push` | `{tasks,memories,status}` → `{accepted,duplicates,rejected,server_seq}`；可合并多来源一次上传 |
| GET | `/api/v1/sync/pull?since=N&limit=200` | `{records:[{seq,device_id,kind,id,ts,data}],server_seq,next_since,has_more,config}` |
| GET | `/api/v1/config` | `{config,server_time}`；只取配置的客户端不下载 records |

`server_seq` 是账号流高水位，`next_since` 是本页最后已交付 seq（空页保留请求 since）；客户端处理成功后才提交 records 最后 seq，避免跳过中间页。

- 服务端**校验 `device_id` 确实属于该账号**，否则 `403 device_not_bound`。跨账号返回同一错误，避免探测设备 ID。
- 记录以客户端提供的 `id` 去重，**重推安全**（幂等）。
- `status` 是**快照不是日志**：只写设备档案（最新值覆盖），不进 append-only 记录流。
  早期版本按秒级时间戳当 id 写进记录流，导致同秒重推翻倍、跨秒又变新记录。
- 账号体系上线前的按设备归档记录，会在**首次绑定账号时自动迁移**进账号流（旧文件改名 `.migrated`）。

### 3.3 局域网会合

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/lan/rendezvous` | `{lan_ips:["192.168.1.5"],port:18444}` → `{same_lan,peers:[...],lan_key_params}` |

「同一内网」判定依据是**服务端观测到的公网出口 IP**（同 NAT 后必然一致），
不采信客户端自报——自报可伪造。邻居需在 3 分钟内上报过才算新鲜。

### 3.4 额度与兑换码

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/redeem` | Bearer | `{code}` → `{units_added,quota}`；无效码统一 `404 code_invalid` |
| POST | `/api/v1/admin/codes` | `X-Admin-Token` | `{count,units,batch}` → `{codes:[...]}` |
| POST | `/v1/chat/completions` | Bearer | OpenAI 兼容；**api_key 直接用账号令牌** |
| GET | `/v1/models` | Bearer | 透传上游模型列表 |

- 兑换码字母表刻意剔除 `0/O/1/I/L` 等易混字符。核销与记账在**同一把锁内**完成——否则并发提交同一张码可能双花。
- 上游报错**原样透传且不计费**：用户不该为失败的请求付费。
- 计量取自上游响应的 `usage.total_tokens`；流式则取带 `usage` 的最后一个 chunk。
- 额度不足返回 `402 quota_exhausted`。

> 已知边界：额度是「先检查后扣减」，**并发请求可能小幅超支**。本服务面向单用户级规模，
> 可接受；若将来对外规模化，需改成预扣 + 结算。

### 3.5 跨设备指令

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/device/command` | Bearer + `X-Device-Id` | `{to_device,task}` 下发定向指令 |
| GET | `/api/v1/device/commands` | Bearer + `X-Device-Id` | 目标设备领取 pending 指令 |
| POST | `/api/v1/device/command/ack` | Bearer + `X-Device-Id` | 目标设备回报 `running` / `done` / `failed` |
| GET | `/api/v1/device/command?id=...` | Bearer | 同账号查询原 command 的 status/detail |

领取 TTL 为5分钟，终态保留24小时。相同目标设备、终态和截断 detail 的重复 ACK 幂等，不延长保留期；冲突终态返回409。指令仍为进程内队列，审计不在启动时重放，重启或保留期结束后可能404。结果直接查询原 command，不另建 `/sync/push` 回执旁路。

## 4. 局域网 P2P 线协议（v1）

服务端**不经手局域网对称密钥**，只公布派生参数。两端各自从账号密钥派生同一把密钥，
因此即使服务器失陷也无法解密局域网内的同步流量。

### 4.1 密钥派生（两端本地计算）

```
key = PBKDF2WithHmacSHA256(
          password = 账号密钥原文,
          salt     = "agentlite-lan|" + account_id,
          iterations = 100000,
          keyLength  = 32 字节)
```

Java 侧用 `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` + `PBEKeySpec` 复现。
参数以服务端 `/api/v1/lan/rendezvous` 返回的 `lan_key_params` 为准，避免两端漂移。

> **与原始需求的一处偏离（PKDF 选择）**：需求原文写的是「HKDF 派生」，实现用的是
> **PBKDF2-HMAC-SHA256**。理由有二：
> 1. **Java 标准库没有 HKDF**，手写 HMAC-Extract-Expand 会引入自研密码学代码；
>    而 `PBKDF2WithHmacSHA256` 从 Android API 26 起由 `SecretKeyFactory` 原生支持，
>    minSdk 30 完全够用。少写自研密码学 = 少出错面。
> 2. HKDF 是为**高熵输入**设计的密钥扩展函数，**不做抗暴力破解的拉伸**。
>    这里的输入是用户自选的账号密钥（可能很弱），PBKDF2 的迭代拉伸在语义上更合适。
>
> 已用桌面 JVM 与 Python 双向复算验证两端密钥逐字节一致（见 §8）。

### 4.2 报文封装

- 监听：设备在局域网接口上开 HTTP 服务（默认端口 `18444`，可配置）。
- 端点：`POST /lan/v1/sync`
- 请求体 / 响应体一律为 **base64( nonce(12B) || ciphertext )**，
  `ciphertext = AES-256-GCM(plaintext, key, nonce, aad)`，GCM tag 128 位。
- 当前 v1 `aad = "agentlite-lan-v1|" + account_id`；请求/响应相同。device_id 位于 GCM 保护的正文，解密后校验；不按原手册的 device_id 后缀另造不兼容 AAD。
- `plaintext`（请求；游标作为不透明对象原样回传）：
  ```json
  {"ts": 1757690000, "device_id": "d_xxx", "since": 12,
   "lan_cursor": {"local": 0, "peer": 0},
   "push": {"tasks": [{"id": "...", "ts": "...", "data": {}}], "memories": []}}
  ```
- `plaintext`（当前 Android 响应）：
  ```json
  {"ts": 1757690001, "device_id": "d_yyy", "server_seq": 12, "merged": 0,
   "records": [{"origin_device_id": "d_yyy", "id": "...", "ts": "...", "data": {}}],
   "lan_cursor": {"local": 1, "peer": 0, "local_id": "...", "peer_id": "", "next_peer": true},
   "lan_has_more": true}
  ```
  `server_seq` 仅沿用兼容字段，不代表 LAN 页的进度；本机/转发两文件由 `lan_cursor` 各自跟踪，成功合入 records 后才保存它。旧对端不带新游标时，Android 服务端轮转有界页。

### 4.3 抗重放

接收方必须校验：

1. `|now - ts| <= 120` 秒；
2. `(device_id, nonce)` 未在缓存中见过（建议保留 5 分钟滚动窗口）。
   仅靠随机 nonce 不够——重放旧报文时密文本身仍然合法。

### 4.4 为什么不用明文 HTTP + HMAC

同内网设备互相同步必然要有一方监听端口并被另一方信任——这正是
`SECURITY_REVIEW` 里 `agentd-apk` 8081 端口「绑定 0.0.0.0 + 无鉴权」那条的同类暴露面。
用 AEAD（AES-GCM）一次性覆盖**机密性 + 完整性 + 来源认证**，比另加 HMAC 更少出错面。

## 5. 部署

```bash
# 1) 传输源码
tar czf - agentlite-server | ssh aliyun-ecs \
  'rm -rf /srv/agentlite-server/src && mkdir -p /srv/agentlite-server/src && \
   tar xzf - -C /srv/agentlite-server/src --strip-components=1'

# 2) 首次：生成自签证书（SAN 必须含服务 IP，否则 App 端主机名校验失败）
ssh aliyun-ecs 'mkdir -p /srv/agentlite-server/tls && cd /srv/agentlite-server/tls && \
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout server.key -out server.crt -subj "/CN=8.133.225.105/O=AgentLite" \
    -addext "subjectAltName=IP:8.133.225.105,IP:127.0.0.1,DNS:localhost" && \
  chmod 600 server.key && chmod 644 server.crt'

# 3) 敏感配置（0600，不写进 systemd 单元——单元默认 0644 全局可读）
ssh aliyun-ecs 'mkdir -p /etc/agentlite-server && cat > /etc/agentlite-server/env <<EOF
AGENTLITE_ADMIN_TOKEN=<openssl rand -hex 20>
AGENTLITE_UPSTREAM_KEY=<你的 DashScope Key>
AGENTLITE_UPSTREAM_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
EOF
chmod 600 /etc/agentlite-server/env'

# 4) 构建 + 装 systemd
ssh aliyun-ecs 'cd /srv/agentlite-server/src && bash deploy/install.sh'

# 5) 回归（公网真实链路）
ADMIN_TOKEN=$(ssh aliyun-ecs 'sed "s/.*=//" /etc/agentlite-server/env' | head -1) \
  bash deploy/smoke-test.sh https://8.133.225.105:18443 tls/server.crt
```

前置条件：

- 服务器需 `dnf install -y golang git`（Alibaba Cloud Linux 3 提供 go1.26.5，满足 `go 1.24` 要求）。
- **阿里云安全组必须放行入方向 TCP 18443**（默认只开 22）。
- 证书公钥需复制到客户端：`cp tls/server.crt ../agent-lite/assets/agentlite_sync_ca.pem`。
- 未配 `AGENTLITE_UPSTREAM_KEY` 时网关自动关闭（返回 503），同步能力不受影响。

## 6. 运维

```bash
systemctl status agentlite-server
journalctl -u agentlite-server -f      # 不记录令牌与请求体
```

**发放兑换码**：

```bash
source /etc/agentlite-server/env
curl -sk https://127.0.0.1:18443/api/v1/admin/codes \
  -H "X-Admin-Token: $AGENTLITE_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"count":10,"units":5000,"batch":"promo-2026-09"}'
```

**改远程配置**：编辑 `/var/lib/agentlite-server/config.json` 并递增 `version`。
服务按 mtime 缓存，**改完即生效，无需重启**。

数据目录 `/var/lib/agentlite-server/`（0700）：

```
billing.jsonl             账号/令牌/余额、兑换码、订单、流水的唯一事务日志
devices.jsonl             设备归属、局域网位置、状态快照的变更日志
records/<account_id>.jsonl 账号记录事实源；启动重建内存页索引
commands.jsonl            指令审计；启动不恢复指令队列
config.json               下发配置（改它即生效）
packs.json                可选套餐配置
```

备份就是打包完整目录；恢复时停服务、覆盖、启服务。首次升级从旧账号/兑换码/订单与设备 JSON 一次导入，旧文件保留归档但不再更新；旧额度流水不重复入账。新日志已有写入后，回滚需配套数据备份，单换旧二进制会读到过期表。活跃日志不直接外部截断，压实/淘汰尚未实现。

## 7. 安全设计

按项目 `SECURITY_REVIEW_2026-09-05.zh-CN.md` 的标准，刻意避开 `agentd-apk` 8081 端口
「绑定 0.0.0.0 + 无鉴权 → 局域网任意主机可窥屏/注入」那类问题：

- 除 `/health`、注册、登录外**全部接口强制 Bearer 鉴权**；账号令牌只存 sha256。
- 设备接口额外校验 `device_id` 归属账号（跨账号 → 403）。
- 注册/登录/兑换按 IP 或账号限流；管理接口用独立的 `X-Admin-Token`（常量时间比较）。
- 请求体上限 8MB、TLS ≥1.2、**不信任 `X-Forwarded-For`**（服务直连公网无反向代理，
  否则限流与局域网判定都可被伪造头绕过）。
- systemd 加固：`ProtectSystem=strict` / `NoNewPrivileges` / `MemoryDenyWriteExecute` /
  `SystemCallFilter=@system-service` / `ReadWritePaths` 仅数据目录。
- **局域网密钥不经过服务器**（见 §4），服务器失陷也无法解密 P2P 流量。
- **远程配置白名单**：只接受 `max_steps` / `planner_model` / `vision` / `spec_inject` / `spec_clamp`，
  **显式拒绝 `base_url` 与 `api_key`**——否则服务端一旦失陷，就等于拿到一条
  「改写客户端出站地址 → 定向窃取 Key」的通道（即 P1-1 的利用链）。

### 已知边界

- 账号令牌存于 App 的 SharedPreferences（应用私有目录，未加密）。设备已 root 时可被读取。
- 无 TLS 客户端证书；服务端无法密码学地确认设备身份，仅靠令牌。
- 额度「先检查后扣减」，并发下可能小幅超支（见 §3.4）。
- 当前 `Pull` 按启动重建的 seq→字节区间索引只读本页，不再每请求扫描整流；索引/ID 集合随数据增长，启动重建 O(n)。
- 日志压实与自动淘汰尚未实现，fsync 仍在单 Store 锁内串行；活跃 append-only 日志不直接外部 logrotate/截断。
- **局域网判定依赖公网出口 IP**：同账号设备若走不同出口（双 WAN、部分设备走流量、
  或一方仅 IPv6），即使物理同网也不会被判为同内网，会退回云端同步。这是刻意的
  保守取舍——宁可少走 P2P，也不用可伪造的客户端自报地址。
- 自签证书 10 年有效期，到期需重新签发并**同步更新客户端 assets 并重新打包 APK**。

## 8.1 验证与运维踩坑（都实际踩过）

这些不是理论风险，是本次开发中被真实浪费过时间的坑，写下来避免重复踩。

**构建产物的可信度**

- **APK 字节数会巧合重复，完全不可信**：本项目在同字节数下产出了内容不同的包。
  必须 `unzip -o app.apk classes*.dex` 后 `grep -ac <新增标记> classes.dex` 确认代码真的进包。
- 还要校验**构建新鲜度**：`find src res AndroidManifest.xml -newermt "@$(stat -f %m build/x.apk)"`
  应无输出，否则说明有改动没进这次构建。
- `go vet` / `go build` 的退出码会被管道吃掉：`go vet ./... | head` 的退出码是 `head` 的。
  脚本里必须 `set -o pipefail` 或显式判空，否则会看到假的 `BUILD_OK`。

**真机/模拟器验证**

- **`am start` 会静默忽略**：当目标 Activity 已在任务栈中时，`am start -n` 输出
  `Warning: Activity not started, intent has been delivered to currently running top-most instance.`
  然后**前台仍是上一个页面**。表现为"Activity 启动后自己退出了"，实则是根本没启动。
  验证时必须加 `-S`（先 force-stop 目标 App）。
- **`adb` 不在 PATH**：每次 shell 调用都是新环境，之前 `export` 的 PATH 不保留。
  自动化脚本要自己定位（`shutil.which` + SDK 常见路径），否则会得到 `FileNotFoundError`。
- **`input text` 是追加不是替换**：登录页会用 `etName.setText(prefs.accountName())` 预填上次账号名，
  直接输入会拼成「旧名+新名」→ 服务端返回 401，看起来像"账号密钥错误"。
  必须先 `keyevent 123`(MOVE_END) + 一串 `keyevent 67`(DEL) 清空。
- **构建期会 `rm -rf build/`**：构建进行中 APK 不存在，此时任何对它的检查都会得到
  全 0 的假结果。核查前先等产物稳定（连续几次探测大小不变）。

**服务端行为**

- 注册/登录/兑换限流是**内存态，重启清零**。回归测试连续建号会被自己挡住
  （默认 10 次/10 分钟/IP）；`systemctl restart` 即可清空。
- `decodeBody` 刻意**不启用** `DisallowUnknownFields`（客户端先于服务端升级时，
  新字段应被忽略而非让整批同步 400 丢数据）。代价是未知字段被静默忽略，
  所以**测试必须断言 `accepted`/`duplicates` 计数，不能只看 HTTP 状态码**。
- 旧记录迁移是**一次性**的（成功后旧文件改名 `.migrated`）。这意味着
  一台设备**首次**绑定到哪个账号，它的历史就归到哪个账号——用测试账号登录真机会
  把用户真实历史迁走，测试时务必避开。

**额度网关计量（两个真 bug，都是接上真实上游才暴露的）**

接上真实 DashScope 之前，网关只用 mock 上游验证过，于是这两个问题全部漏过：

- **流式用量完全漏计**：DashScope 的 OpenAI 兼容流式**默认不回传用量** —— 每个 chunk 的
  `usage` 都是 `null`。早期提取器看到 `"usage"` 后就找第一个 `{`，抓到的是后面
  `"choices":[{...}]` 那个对象，解出来全是 0，于是回落到"最小计费 1 unit"兜底。
  实测 3600 tokens 的流式请求只扣 1 unit，**等于流式调用近乎免费**。
  修法有两处：① 转发前给流式请求注入 `stream_options.include_usage=true`
  （实测这样上游才会在最后一个 chunk 给出真实 usage）；② 提取器显式跳过 `"usage":null`。
- **余额不足反而导致无限免费**：`ConsumeQuota` 原先在 `quota < units` 时**直接返回、不扣款**，
  而网关的 402 闸门只认 `quota <= 0`。后果是余额为 `1..units-1` 的账号永远扣不动、
  余额永远停在 >0 —— 实测余额 2、单次成本 4，**连发 3 次全部 HTTP 200 且余额纹丝不动**。
  改为允许透支到 0 以下（透支上限就是单次请求成本，因为扣费发生在响应之后），
  下一次请求即被 402 挡下。

> 教训：**计量逻辑必须拿真实上游验证**。mock 上游只能验证"协议形状对不对"，
> 验证不了"真实上游到底给不给用量字段"。

**局域网**

- 局域网判定的 `same_lan` 依据是**服务端观测到的公网出口 IP**，因此
  双 WAN、部分设备走移动数据、或一方仅 IPv6 时，物理同网也不会被判为同内网。
  这是刻意的保守取舍——不采信可伪造的客户端自报。
- 云端 `since` 不作对端本地记录下标。当前使用独立 `lan_cursor` 为本机/转发两个来源分页；总回包最多500条、加密信封最多4MiB，ID集合一批读取、追加一次 writer。按实际交付页推进，不再无限追加全部转发记录。
- 内容哈希（`sha256Hex(原始行).substring(0,32)`）在 `SyncClient` 与 `LanSync` 里
  **必须完全一致**，否则同一条记录会被当成两条，局域网拉取与云端幂等同时失效。

## 8. 测试

`deploy/smoke-test.sh` 覆盖 29 项断言，全部走真实 TLS 链路：

```bash
ADMIN_TOKEN=xxx bash deploy/smoke-test.sh https://8.133.225.105:18443 tls/server.crt
```

覆盖：健康检查、账号注册/重名/错误密钥/令牌轮换、设备绑定幂等、
**账号级双向共享与 device_id 溯源**、**跨账号越权 403**、**局域网会合**、
额度与**兑换码防双花**、管理接口鉴权。

> 注意：`decodeBody` 刻意**不启用** `DisallowUnknownFields`——客户端先于服务端升级时，
> 新字段应被忽略而非让整批同步 400 失败。代价是拼写错误会被静默忽略，
> **因此测试必须断言 `accepted` 计数，不能只看 HTTP 状态码**。

额度网关的计量另用 mock 上游做端到端验证（不需要真实 DashScope Key）：
1500 tokens → 扣 2 units；流式同样取到 usage；精确用尽后再次请求返回 402。
**这个测试抓到过一个真实缺陷**：早期 `extractUsageTokens` 在定位到 `"usage"` 后的对象时
又多套了一层包装字段，永远解出 0，导致每笔只按最小单位计费——静默少扣费。
