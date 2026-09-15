# agentlite-server 安全审查报告

审查范围：`agentlite-server/`（8 个 Go 文件，3197 行）+ `agent-unified/server/`（**逐字节相同**，`diff -rq` 仅 agentlite-server 多一个未跟踪的编译产物 `agentlite-server`，故全部结论对两者同样成立）。另核对 `deploy/`、`tls/`、`go.mod`、`README.md`、`docs/`，以及客户端 `agent-lite/src/com/dsh/agentlite/TlsPin.java` 的证书 pin 逻辑（用于判定 TLS 影响面）。

未修改任何源码文件。环境限制：本机无 Go 工具链（`go: command not found`），故**未执行** `go vet` / `-race` / 实际构建；并发类结论均由代码静态路径推导。git 因 Xcode 许可默认不可用，已用 `DEVELOPER_DIR=/Library/Developer/CommandLineTools` 绕过并完成跟踪状态核验。

---

## P0 — 发布阻塞

### P0-1 计费可被客户端显式关闭，上游成本近乎无限

**位置**：`agentlite-server/gateway.go:239-263`（`ensureIncludeUsage`），触发点 `gateway.go:89-91`

**证据**：

```go
244	if raw, ok := m["stream_options"]; ok {
245		var opts map[string]json.RawMessage
246		if err := json.Unmarshal(raw, &opts); err == nil {
247			if _, has := opts["include_usage"]; has {
248				return body            // ← 客户端显式给 false，网关原样放行
```

**影响**：网关的唯一计费依据是上游响应里的 `usage.total_tokens`。注释（`gateway.go:234-237`）自己写明「不注入的话网关拿不到用量，只能按最小计费单位兜底，实测 3600 tokens 的流式请求只扣了 1 unit，等于流式调用近乎免费」。但这里为了「尊重调用方选择」，只要客户端在请求体里带上 `"stream_options":{"include_usage":false}` 就完全跳过注入。结果：客户端自控 `max_tokens`（服务端**不覆盖**该字段），单次请求可消耗上游数万 token，而 `chargeQuota` 兜底只扣 **1 unit（=1000 tokens 记账）**。500 units 的新账号赠送额度因此可支撑约 500 次满额生成，放大倍数 ≈ `max_tokens/1000`（8192 时约 8 倍，更大值更高）。

**最小修复**：`include_usage` 由服务端强制，不接受客户端值 —— 把 `gateway.go:247-249` 的「已存在就返回」改为「无条件覆写为 `true`」；同时给 `max_tokens` 设服务端硬上限（例如按套餐分档），不要透传客户端任意值。

### P0-2 注册零验证 + 每个新账号白送 50 万 tokens → 上游 Key 被规模化盗刷

**位置**：`agentlite-server/quota.go:23`、`main.go:93`、`api.go:220-224`

**证据**：

```go
// quota.go:23
const newAccountGrant = 500 // = 50 万 tokens
// api.go:221
if !s.rl.allow("reg:" + clientIP(r)) {   // 唯一的门槛：按 IP 计数
```

**影响**：`POST /api/v1/account/register` 无需邮箱、短信、邀请码、验证码或任何前置凭证，唯一防线是「每 IP 每 10 分钟 10 次」（`main.go:54-55`）。而 `clientIP` 取 `RemoteAddr`（正确的抗伪造做法，但没有反代也就没有 IP 池收敛）：攻击者用 IPv6 时单个 `/64` 即含 2^64 个独立地址，IPv4 用代理池/僵尸网络同样可绕。每个新账号立即获得 500 units 免费额度，并可立即用 `POST /v1/chat/completions` 消耗**服务端付费的** `AGENTLITE_UPSTREAM_KEY`（`main.go:63`，网关路由 `main.go:124`）。这是**直接的资金损失**，且服务一暴露在 `0.0.0.0:18443` 就会被扫描到。与 P0-1 组合后成本进一步放大，与 P0-3 组合后单账号即可一次爆发式刷穿。

**最小修复**（任一即可显著降险，建议叠加）：① 新账号赠送额度改为**需兑换码/邀请码激活**才生效；② 注册强制携带并校验设备指纹、每指纹限一次；③ 赠送额度直接降到个位数 units；④ 对 `/v1/*` 增加按账号的**并发上限 + 每分钟请求上限**，而不只是余额检查。

### P0-3 额度「先检查后扣减」的竞态超支无上限

**位置**：`agentlite-server/gateway.go:64`（检查）、`gateway.go:133`/`164`（扣减）、`quota.go:191`

**证据**：

```go
// gateway.go:64 —— 用的是 AccountByToken 返回的快照副本
if a.Quota <= 0 { writeErr(w, http.StatusPaymentRequired, ...); return }
// gateway.go:133 —— 上游调用完成之后才扣
s.chargeQuota(a.AccountID, extractUsageTokens(data), probe.Model)
```

```go
// quota.go:191
func (s *Store) ConsumeQuota(accountID string, units int64, reason string) (int64, error) {
	s.mu.Lock()   // 扣减本身是原子的，但检查不在锁内、且在上游调用之前
```

**影响**：README §3.4 把这写成「并发请求可能**小幅**超支，可接受」，这个描述**低估了实际风险**。闸门只认 `Quota <= 0`，而检查发生在转发之前、扣减发生在响应之后，中间隔着整段上游耗时（流式可达数分钟）。同时并发 N 个请求，全部读到同一个正值快照即全部放行，超支额度 = N × 单次真实成本，**N 无任何上限**（服务端没有并发闸门）。攻击者无需等待余额被正确扣减：注册新账号 → 一次性并发数千请求 → 丢弃账号 → 重复。这是把 P0-2 的「每账号 50 万 tokens」变成「每账号无上限」。

**最小修复**：改为**预扣 + 结算** —— 请求进入时在 `s.mu` 内按保守估算（如 `max_tokens` 折算）预扣并落盘，响应结束后按真实 usage 多退少补；同时加每账号在途请求计数上限。

---

## P1 — 启用前必须修

### P1-1 `POST /v1/chat/completions` 可被认证方（=任何人）单行触发 panic

**位置**：`agentlite-server/gateway.go:244-250`

**证据**：

```go
244	if raw, ok := m["stream_options"]; ok {
245		var opts map[string]json.RawMessage          // nil map
246		if err := json.Unmarshal(raw, &opts); err == nil {   // "null" → 无错，opts 仍为 nil
247			if _, has := opts["include_usage"]; has {        // nil map 读安全，has=false
250			opts["include_usage"] = json.RawMessage("true")  // ← assignment to entry in nil map
```

**影响**：`json.Unmarshal([]byte("null"), &map)` 是合法且无错误的操作（JSON null 对 map 是 no-op），opts 保持 nil，随后对 nil map 赋值 panic。触发 payload 极简：`{"stream":true,"stream_options":null}`（`stream_options` 为数字/数组等同理走失败分支）。`recoverMW`（`api.go:73-83`）能兜住并返回 500，所以**当前不会崩进程**，但这是一条「客户端可控输入 → 服务端未定义行为」的路径：认证即可反复触发、每次产生一条 ERROR 日志；而 `wrap()` 一旦被重构或 `recoverMW` 被移除就立刻升级为远程 DoS。注意触发门槛极低——注册免费（P0-2），所以等价于未认证可达。

**最小修复**：`var opts = map[string]json.RawMessage{}`（非 nil 初始化），并在 `json.Unmarshal` 失败或结果类型不符时走「改为整体覆写 `stream_options`」分支，而不是跳过注入。

### P1-2 登录在**全局写锁**内跑 210,000 次 PBKDF2 → 一条请求即可压制整个服务

**位置**：`agentlite-server/account.go:240-259`（锁），`account.go:36`（迭代数）

**证据**：

```go
240	s.mu.Lock()
241	defer s.mu.Unlock()
...
259	got, err := hashAccountKey(key, salt, iter)   // 210_000 次 PBKDF2，持写锁执行
```

**影响**：`RegisterAccount` 把派生放在 `s.mu.Lock()` **之前**（`account.go:192` vs `205`，写法正确），但 `LoginAccount` 把 PBKDF2 放在写锁**内部**。单次派生在本级 ECS（2C）约几十至上百毫秒量级。`s.mu` 是 `Store` 的全局锁，`AccountByToken`（`account.go:297`）、`DeviceOfAccount`、`Append`、`Touch`、`Devices()`、`ConsumeQuota` **全部**依赖它；Go 的 `RWMutex` 在写者等待时还会阻塞新读者，因此一次登录会冻结 `push/pull/bind/health/chat` 全部路径。`/login` 无需 token，限流仅按 IP（`api.go:247`），配合 IPv6/代理池可持续压制。账号不存在时（`account.go:246-248`）**也**走一次等价派生，所以无需知道任何有效账号名即可施压。

**最小修复**：把派生移到锁外 —— 持 `RLock` 取出 `hash`/`salt`/`iter` 后立即解锁，算出派生值再重新加锁比对；删掉 `account.go:240` 的 `defer s.mu.Unlock()` 并显式管理。

### P1-3 `loggingWriter` 丢失 `Flush` 与 `Unwrap`，流式网关的写入期限豁免与实时推送**双双失效**

**位置**：`agentlite-server/api.go:96-111`（包装器）、`gateway.go:71-72`、`gateway.go:143`

**证据**：

```go
// api.go:96-105 —— 只覆盖 Write/WriteHeader，无 Unwrap()，无 Flush()
type loggingWriter struct { http.ResponseWriter; code int; n int }
// gateway.go:71-72
rc := http.NewResponseController(w)
_ = rc.SetWriteDeadline(time.Now().Add(15 * time.Minute))   // 错误被丢弃
// gateway.go:143
flusher, _ := w.(http.Flusher)                              // 断言失败，flusher == nil
```

**影响**：`http.NewResponseController` 通过 `Unwrap() http.ResponseWriter` 链向下查找 `SetWriteDeadline`；`loggingWriter` 没有 `Unwrap()`，于是返回 `ErrNotSupported`，而返回值被 `_` 丢弃。结果：注释声称「长连接流式响应需要放宽写期限，否则会被 `http.Server` 的 `WriteTimeout` 掐断」的豁免**根本没生效**，`main.go:136` 的 `WriteTimeout: 60s` 照常生效——超过 60 秒的流式生成会被服务端切断，而 `chargeQuota` 仍会执行（`gateway.go:154` 的 `w.Write` 失败只触发 `break`）。同理 `w.(http.Flusher)` 失败使 `flusher == nil`，`gateway.go:160-162` 的 `Flush` 永不调用，SSE 退化为按底层 bufio 缓冲批量下发，「打字机」效果失效。二者都是被中间件悄悄抹掉的安全/功能控制。

**最小修复**：给 `loggingWriter` 补 `Unwrap() http.ResponseWriter { return l.ResponseWriter }` 与 `Flush()`（有 `http.Flusher` 就转发）。补上后 `SetWriteDeadline` 与 SSE flush 立即恢复。

### P1-4 账号令牌永不过期、无吊销、无登出

**位置**：`agentlite-server/account.go:55-69`（`Account` 结构无任何过期字段）、`main.go:89-129`（无 logout 路由）

**证据**：

```go
type Account struct {
	AccountID   string    `json:"account_id"`
	...
	TokenHash   string    `json:"token_hash"`
	CreatedAt   time.Time `json:"created_at"`
	Disabled    bool      `json:"disabled"`     // 无 TokenExpiresAt；Disabled 也无任何接口可设置
```

**影响**：令牌一旦签发即**永久有效**，唯一失效途径是同一账号再次成功登录（`account.go:278-283` 轮换 `TokenHash`，旧令牌当场作废）。没有登出接口，没有会话列表，`Disabled` 字段存在但无任何管理路径可写（`SetDisabled` 定义在 `store.go:318` 属死代码）。后果：设备丢失/令牌从 APK 包或抓包中泄露后，攻击者持有的是**长期有效**的账号令牌，可读全部同步记录、全部记忆、下发跨设备指令、消耗余额（因为令牌同时就是 `/v1/*` 的 api_key）；用户唯一的补救是「再登录一次」，而这会把**自己当前设备也踢下线**，多设备场景下等于没有可用的补救手段。附带可用性缺陷：多设备共享同一账号令牌，任一台重新登录即让其余设备全部 401。

**最小修复**：令牌加过期（如 30 天）与滑动续期；新增 `POST /api/v1/account/logout`（轮换 `TokenHash`）与「吊销全部会话」；把 `Disabled` 接到一个受 `X-Admin-Token` 保护的管理端点上。

### P1-5 设备 `status` 无大小校验 × `devices.json` 全量重写 = 磁盘写放大数十倍

**位置**：`agentlite-server/store.go:289-303`（`Touch`）、`store.go:61`、`store.go:539-549`（全量重写）、`api.go:314`/`346`

**证据**：

```go
// store.go:298-302 —— 只要 status 非空就存，无上限（maxPayloadB 只作用于 tasks/memories）
if len(status) > 0 { dev.LastStatus = status; dev.LastStatusAt = now }
// store.go:540-548 —— 每次 Touch 都序列化**全部设备**并原子重写整个文件
list := make([]Device, 0, len(s.devices))
for _, d := range s.devices { list = append(list, *d) }
raw, err := json.MarshalIndent(list, "", "  ")
```

**影响**：单条 `status` 只受 8MB 请求体上限约束（`api.go:22`、`api.go:314` 是裸 `json.RawMessage`），而 `maxPayloadB = 256KB` 只检查 `tasks`/`memories`。`POST /api/v1/device/bind` **无限流**（`api.go:281`，`main.go:98` 只用 `authMW`），`BindDevice` 也不设设备数量上限（`store.go:221-271` 仅校验 fingerprint 非空且 ≤256）。因此：注册一个账号 → 绑定 N 台设备 → 每台上传 8MB status → `devices.json` 达 N×8MB → 此后**任意一台设备的任意一次 push** 都会把整个 N×8MB 文件重写两遍（`.tmp` + rename，`store.go:646-652`）。N=100 时一次 8MB 请求触发约 1.6GB 写入，在 2C/1.8G 的 ECS 上足以打满磁盘。同一份 `LastStatus` 还会被 `GET /api/v1/account/me` 原样序列化下发（`api.go:273` → `account.go:420-432` 返回完整 `Device`），响应体同样膨胀。

**最小修复**：`Touch` 内对 status 施加硬上限（例如 64KB）并超限拒绝/截断；`BindDevice` 加每账号设备数上限；`devices.json` 拆成按设备分文件，避免任意写触发全表重写。

### P1-6 订单无数量上限 + 每次下单全量重写 `orders.json` → 二次方级写入 DoS

**位置**：`agentlite-server/order.go:174-201`、`api.go:402-419`、`order.go:204-227`

**证据**：

```go
// api.go:402 —— handleCreateOrder 全文没有 s.rl.allow(...)，是唯一无限流的写接口
func (s *Store) CreateOrder(w http.ResponseWriter, r *http.Request, a *Account) {
// order.go:196 —— 每建一单序列化全部订单
if err := s.saveOrdersLocked(); err != nil { return nil, err }
```

**影响**：`POST /api/v1/orders` 是唯一**完全没有限流**的状态变更接口（对比 redeem/pay 都有 `s.rl.allow`）。`orders.json` 只增不减（过期只改 `Status`，见 `order.go:214-216`，从不删除），每次创建都 `MarshalIndent` 全量 N 条并写盘 → 第 N 次下单成本 O(N)，累计 O(N²)。一个免费账号循环调用即可把磁盘和 CPU 吃满。雪上加霜：`ListOrders`（读接口）在**持有 `s.orders.mu` 写锁**的情况下对每个过期订单调用一次 `saveOrdersLocked()`（`order.go:214-216`），并发 GET 会被串行化并放大写盘。

**最小修复**：`handleCreateOrder` 加入 `s.rl.allow("order:"+a.AccountID)`；限制每账号未支付订单数（如 ≤5）并拒绝超限；`ListOrders` 的惰性过期改为只标记内存、由低频后台任务统一落盘。

---

## P2 — 加固

### P2-1 解锁后继续读共享对象 —— 两处真实数据竞争

**位置**：`agentlite-server/account.go:297-311`、`account.go:409-416`

**证据**：

```go
// account.go:297-303
s.mu.RLock()
id, ok := s.acctByToken[h]
var a *Account
if ok { a = s.accounts[id] }
s.mu.RUnlock()                       // ← 解锁
if !ok || a == nil { ... }
if a.Disabled { ... }                // ← 解锁后读共享指针
cp := *a                             // ← 解锁后整体浅拷贝（含 string 字段）
```

```go
// account.go:409-412：ValidOrigin 同样的模式
s.mu.RLock(); d, ok := s.devices[origin]; s.mu.RUnlock()
if !ok || d.AccountID != accountID || d.Disabled {   // ← 解锁后读
```

**影响**：`Account`/`Device` 结构的写入由 `s.mu.Lock()` 保护（`LoginAccount:281` 改 `TokenHash`/`LastLoginAt`、`ConsumeQuota:212` 改 `Quota`、`BindDevice:246` 改 `AccountID`），而这两处读发生在锁外。Go 的 string 是 `{ptr,len}` 两字，撕裂读可能拿到无效指针 → 段错误而非仅脏读。`ValidOrigin` 尤其容易命中：`POST /sync/push` 的每个 item 都调用一次，与并发的 `BindDevice`（`main.go:98`）天然重叠。

**最小修复**：把拷贝与判定移进临界区 —— `RLock` 内完成 `cp := *a` 与 `Disabled` 判断后再解锁（`DeviceOfAccount:274-286` 已是正确写法，照它改）。

### P2-2 自签证书 `CA:TRUE` + 10 年有效期 + 无轮换路径

**位置**：`agentlite-server/tls/server.crt`（git 跟踪）、`README.md:152-156`（生成命令）、`agent-lite/src/com/dsh/agentlite/TlsPin.java:60-79`

**证据**：

```
subject=CN=8.133.225.105, O=AgentLite
notAfter=Sep  9 12:57:53 2036 GMT
X509v3 Basic Constraints: critical
    CA:TRUE
```

```java
// TlsPin.java:70-75 —— 该证书被当作唯一信任锚
ks.setCertificateEntry("agentlite-sync", ca);
tmf.init(ks); ctx.init(null, tmf.getTrustManagers(), null);
```

**影响**：客户端把服务端**叶证书本身**当 CA 装进 `KeyStore` 作为唯一 TrustManager，而这张证书带 `CA:TRUE` 且有效期 10 年。私钥一旦泄露，攻击者可**为任意主机名签发**证书；而客户端 pin 是编译期常量（`PinnedCa.PEM` 来自 `assets/agentlite_sync_ca.pem`，与 `tls/server.crt` **逐字节相同**），**换证书必须发新版 APK**，10 年内无吊销手段（自签也无 CRL/OCSP）。同时 `install.sh:26-29` 只检查 `server.crt` 存在，**不检查也不强制 `server.key` 的 0600 权限**（README 手工步骤里有 `chmod 600`，但脚本不校验，重装/迁移容易漏）。

**最小修复**：服务端证书改为「短效叶证书 + 独立 CA」，把 CA 公钥（非叶证书）作为 pin 锚点并去掉叶证书的 `CA:TRUE`；客户端 pin 改为 SPKI 指纹并预留双 pin 过渡期（新旧指纹同时接受）以便轮换；`install.sh` 增加 `server.key` 存在性与权限（≤0600）的硬校验。

### P2-3 systemd 单元未降权，服务以 root 运行

**位置**：`agentlite-server/deploy/agentlite-server.service:7-15`

**证据**：

```
[Service]
Type=simple
EnvironmentFile=-/etc/agentlite-server/env
ExecStart=/usr/local/bin/agentlite-server \
    -addr 0.0.0.0:18443 \
```

（`[Service]` 段内无 `User=` / `Group=` / `DynamicUser=`）

**影响**：`ProtectSystem=strict`、`NoNewPrivileges`、`SystemCallFilter=@system-service`、`ReadWritePaths` 等加固做得相当扎实（值得肯定），但服务仍以 **root** 身份运行。对处理公网不可信输入（且存在 P0-1/P1-1 这类解析缺陷）的 daemon 而言，任何 RCE 都直接落在 root 上；沙箱能限制横向移动，却不能限制对 `/var/lib/agentlite-server` 全部数据与 `/etc/agentlite-server/env`（含上游 API Key 与管理令牌）的读取。

**最小修复**：新增专用低权用户（`User=agentlite` / `Group=agentlite`），`chown` 数据目录，`/etc/agentlite-server/env` 设为 `0640 root:agentlite`，`ExecStart` 前置 `+` 仅在确需绑特权端口时使用（18443 > 1024，无需特权）。

### P2-4 管理接口无限流

**位置**：`agentlite-server/api.go:162-176`（`adminMW`）、`main.go:121`

**证据**：

```go
169	got := r.Header.Get("X-Admin-Token")
170	if !subtleCompare(got, s.adminToken) {
171		writeErr(w, http.StatusUnauthorized, "bad_admin_token", "管理令牌无效")
```

**影响**：常量时间比较正确（对比长度提前返回仅泄露长度，无实际价值），但 `POST /api/v1/admin/codes` 完全**没有速率限制与失败计数**，可无限次尝试 `X-Admin-Token`；一旦运维用了弱令牌（README 建议 `openssl rand -hex 20`，但没有强制校验），即可离线爆破。同接口成功时单次最多签发 1000 张码、每张上限 10,000,000 units（`quota.go:107-112`），令牌失陷等于无限造额度，且无审计告警。

**最小修复**：对 `adminMW` 增加按 IP 的失败限流（如 5 次/分钟）与失败告警日志；启动时校验 `adminToken` 长度 ≥32 且非默认值，否则拒绝启动。

### P2-5 `lan_ips` 未做地址合法性校验，服务端成为客户端 SSRF 载荷分发器

**位置**：`agentlite-server/account.go:340-362`（记录）、`api.go:585-607`（回传）、`account.go:368-399`（`LANPeers`）

**证据**：

```go
// account.go:348-352 —— 只校验非空与长度
ip = strings.TrimSpace(ip)
if ip != "" && len(ip) <= 45 { clean = append(clean, ip) }
```

```go
// account.go:383-384 —— 同账号 + 同公网出口即回传
if d.PublicIP != publicIP || len(d.LANIPs) == 0 || d.LANPort == 0 { continue }
```

**影响**：服务端本身不发起请求（**网关上游 URL 来自启动参数，非用户可控 —— 服务端侧不存在 SSRF**），但它会原样接受并**分发**任意 ≤45 字节字符串作为 `lan_ips`，接收端（同账号、同 NAT 出口的另一台设备）会据此主动发起局域网连接。上传 `127.0.0.1`、`169.254.169.254`、`0.0.0.0`、公网地址等均不被拒绝。当前利用前提是「同一账号」（`LANPeers` 要求 `d.AccountID == accountID`），所以单账号多设备场景下等于自己打自己；但该信任链把「服务端校验」这一环完全让给了客户端，一旦账号共享或设备被 P2-6 的指纹劫持污染，就会跨信任边界。

**最小修复**：`RecordLANPresence` 用 `netip.ParseAddr` 校验并**只接受私有/链路本地单播地址**（RFC1918、RFC4193、IPv6 ULA），显式拒绝 loopback、link-local、multicast、unspecified、公网地址；同时把 `Port` 限制到非特权范围（当前允许 1-65535，`api.go:590`）。

### P2-6 设备指纹被无条件采信为身份主键，可跨账号劫持设备

**位置**：`agentlite-server/store.go:221-246`

**证据**：

```go
233	if id, ok := s.byFinger[fp]; ok {
234		dev = s.devices[id]           // 命中已有设备
...
245	// 换账号登录时把设备改挂到新账号（同一台设备不应同时属于两个账号）
246	dev.AccountID = accountID         // ← 仅凭客户端自报的 fingerprint 就改写归属
```

**影响**：`fingerprint` 完全由客户端提供（`RegisterRequest`，仅校验非空与 ≤256），服务端无任何验证即把它当作设备身份主键。任何知道受害人设备指纹的攻击者（指纹通常源自 Android ID / Build 字段等可预测或可泄露的值），只要在自己的账号下调用 `POST /api/v1/device/bind` 并带上该指纹，就会把**受害人的设备记录改挂到自己账号**：受害人设备随即收到 `403 device_not_bound` 而失去同步能力（DoS），攻击者则取得该 `device_id`、继承其 `LANIPs`/`PublicIP`/`LastStatus`。更值得警惕的是紧接其后的 `migrateLegacyRecordsLocked(dev.DeviceID, accountID)`（`store.go:264`）——它会把该 `device_id` 遗留的记录文件并入**攻击者的**账号流；虽然该文件在首次绑定后已改名 `.migrated`（`store.go:640`），常规路径下为空，但这是「劫持即可读他人历史」的现成通道。

**最小修复**：`BindDevice` 对已存在指纹的设备**要求当前账号与其 `AccountID` 一致**，不一致时返回冲突而不是改挂（换账号应走显式的「解绑」流程）；理想做法是绑定后用服务端签发的设备密钥（HMAC/签名）替代自报指纹，并把 `migrateLegacyRecordsLocked` 限定为「设备首次入账」时才执行。

### P2-7 `/health` 未认证且泄露运营数据

**位置**：`agentlite-server/api.go:180-198`、`main.go:90`

**证据**：

```go
writeJSON(w, http.StatusOK, map[string]any{
	"ok": true, "service": "agentlite-sync", "version": version,
	"accounts": len(s.store.Accounts()), "devices": len(devices),
	"active_7d": active, "uptime_sec": int64(time.Since(s.started).Seconds()),
```

**影响**：完全未认证、无限流，向公网暴露服务版本（便于精确匹配已知漏洞）、账号总数、设备总数、7 日活跃数、运行时长与服务器时间。单条信息价值有限，但这是攻击者测绘阶段最省力的一手数据，也让「服务刚上线、还没真实用户」这一状态可被外部直接观测到。

**最小修复**：把统计字段拆到需要 `X-Admin-Token` 的 `/admin/stats`；`/health` 只保留 `{"ok":true}` 与 `server_time`；若必须保留探活，加按 IP 限流。

### P2-8 服务端对下发配置不做键过滤，白名单仅存在于客户端

**位置**：`agentlite-server/store.go:460-496`（`Config()` 原样读出）、`api.go:374`/`381`，说明见 `docs/DEVELOPMENT_MANUAL.zh-CN.md:390-397`、`:551`

**影响**：`config.json` 的内容**不经任何键过滤**就通过 `GET /api/v1/config` 和 `GET /api/v1/sync/pull` 下发给所有持令牌的客户端。仓库文档已明确承认「服务端把 config.json 原样下发（不做键过滤）」，并把安全期望全部押在客户端白名单（`max_steps`/`planner_model`/`vision`/`spec_inject`/`spec_clamp`，显式拒绝 `base_url`/`api_key`）上。这是**单点防御**：服务端一旦被改（或运维误把密钥写进 config.json），`api_key`/`base_url` 会立刻直达全部客户端，构成「改写客户端出站地址 → 定向窃取 Key」的通道。此外 `config.json` 是运维手改的裸文件，无 schema 校验、无签名，客户端也无从判断其来源真实性。

**最小修复**：把白名单**复制到服务端**（`Config()` 读取后按键过滤，非白名单键直接丢弃并记日志），并对下发内容签名（服务端私钥签、客户端验），使服务端失陷也不能伪造配置。

### P2-9 「服务器失陷也无法解密局域网 P2P 流量」的安全声明不成立

**位置**：`agentlite-server/account.go:14-16`（设计声明）、`README.md` §4/§7、`account.go:36-44`/`116-118`，对照 `api.go:202-204`/`account.go:259`

**证据**：

```go
// account.go:14-16（声明）
//   **服务器不经手局域网对称密钥**：两端各自用账号密钥做 PBKDF2 派生
//   因此即使服务器失陷，也无法解密局域网内的同步流量。
// api.go:204 + account.go:259（实际：服务端明文收到账号密钥）
type credentials struct { Name string `json:"name"`; Key string `json:"key"` }
got, err := hashAccountKey(key, salt, iter)     // key 即账号密钥原文
```

```
LAN 密钥 = PBKDF2WithHmacSHA256(password = 账号密钥原文,
                                 salt = "agentlite-lan|" + account_id,
                                 iterations = 100000, keyLength = 32)
```

**影响**：LAN 对称密钥的 PBKDF2 输入是**账号密钥原文**，而账号密钥原文在每次注册/登录时都会明文到达服务端（虽然不落盘，仅用于一次派生）。因此持有服务进程内存、或能改服务端代码的一方可以立即派生出 LAN 密钥并解密 P2P 流量——「服务器失陷也无法解密」是**过于绝对的表述**。严格说防的只是「静态磁盘失陷」，防不了进程内窥探与代码篡改。另外该 salt 中的 `account_id` 是公开值（`/me`、`/rendezvous` 均返回），100,000 次 PBKDF2 对用户自选的低强度密钥（下限仅 8 位，`account.go:106-114`）离线爆破成本可接受，同网段嗅探到密文后即可爆破。

**最小修复**：修正文档与代码注释，把声明限定为「服务端不持久化账号密钥，静态磁盘失陷无法解密 P2P 流量」；更强的做法是让 LAN 密钥改由两端协商的独立密钥（或客户端本地随机、经公钥加密交换）派生，彻底与服务端可见的凭据解耦。

---

## P3 — 卫生

### P3-1 兜底 404 处理器绕过 `wrap()`，探测行为不留痕

**位置**：`agentlite-server/main.go:127-129` vs `main.go:177-179`

```go
127	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
128		writeErr(w, http.StatusNotFound, "not_found", "未知接口: "+r.URL.Path)
```

所有已注册路由都经 `s.wrap`（= `logMW(recoverMW(h))`），唯独这条没有：**未注册路径的访问既不入访问日志、也没有 panic 恢复**。攻击者的路径扫描/端点枚举因此对 `journalctl` 完全不可见——这是安全监控盲点。修复：`mux.HandleFunc("/", s.wrap(func(...){...}))`。

### P3-2 访问日志缺客户端 IP 与 requestId

**位置**：`agentlite-server/api.go:85-93`

```go
logInfo("%s %s %d %dB %s", r.Method, r.URL.Path, lw.code, lw.n, ...)
```

令牌与请求体确实未入日志（做法正确，`api.go:90` 的注释也说明了），但也没有记录来源 IP、`X-Request-Id`、账号 ID，导致事后无法把滥用/攻击关联到具体来源，用户报障也无从定位。修复：日志加 `clientIP(r)`（该值已可信）与贯穿的 requestId。

### P3-3 `adaptRequest` 经 `map[string]interface{}` 往返导致数值精度与类型有损

**位置**：`agentlite-server/gateway.go:205-230`（尤其 `206`、`225-229`）

```go
206	var m map[string]interface{}
...
225	out, err := json.Marshal(m)   // 所有数字被解码为 float64 再重新编码
```

重新编码会把整数变为 float64 语义：超大整数（如 `seed`）丢精度、`1.0` 可能写成 `1`、`json.Number` 未被启用。仅在启用模型映射或上游 URL 含 `deepseek`（`gateway.go:216`）时触发。注释说「绝不因为适配把请求弄坏」，实际并非绝对成立。修复：改用 `map[string]json.RawMessage`（`ensureIncludeUsage` 已经是这种写法）或用 `Decoder.UseNumber()`。

### P3-4 `sanitizeID` 白名单过滤后可返回空串

**位置**：`agentlite-server/store.go:504-514`、调用点 `store.go:501`/`605`

全字符都被过滤时返回 `""`，路径退化为 `records/.jsonl` 并可能与其它空 ID 碰撞。当前 `accountID`/`deviceID` 均为服务端随机生成（`account.go:128-134`、`store.go:200-206`），**用户不可控，实际不可利用**，但这是防御代码的完整性缺口。修复：过滤后为空则返回固定占位（如 `"_"`）或直接报错。

### P3-5 兑换码写入流水与订单的明文留存

**位置**：`agentlite-server/quota.go:181-184`、`order.go:287`

```go
// quota.go:183
Reason: "redeem:" + code,
// order.go:287
o.RedeemCode = strings.ToUpper(strings.TrimSpace(code))
```

已核销的码（`quota.go:173` 已从 `s.codes` 删除）会以明文留在 `quota_ledger.jsonl` 与 `orders.json`。因码已失效，重放无收益，风险限于对账文件外泄。修复：流水只记码的哈希前缀。

### P3-6 `generateCode` 取模偏差

**位置**：`agentlite-server/quota.go:88-103`

```go
sb.WriteByte(codeAlphabet[int(b)%len(codeAlphabet)])
```

字母表长度 31，`256 % 31 = 8`，前 8 个字符的取值概率为 9/256 而非 8/256。熵损失约 0.02 bit/字符，对 31^12 ≈ 7.9×10^17 的空间无实际影响。修复：改用拒绝采样或 `crypto/rand.Int`。

### P3-7 `atomicWrite` 使用固定 `.tmp` 名、不正确设置既有文件权限

**位置**：`agentlite-server/store.go:646-652`

正文 `path + ".tmp"` 是确定性的，且 `os.WriteFile` 仅在**创建**时应用 `perm`（`0600`），若 `.tmp` 因上次崩溃残留则沿用旧权限。当前所有写路径都在各自的锁内（`s.mu` 或 `s.orders.mu`），**未发现并发写同一文件的情形**，数据目录又是 0700，故为卫生问题。修复：临时名加随机后缀；写前显式 `os.Chmod(tmp, perm)`。

### P3-8 限流器状态与 IP 基数

**位置**：`agentlite-server/main.go:190-244`

`hits map[string][]time.Time` 以客户端 IP 为键，惰性 GC 最长每分钟才清一次（`main.go:215`），且 `allow` 在拒绝时仍写回 `kept`（`main.go:239`）。真实 RemoteAddr 不可伪造，故内存增长受攻击者可用 IP 池约束，**未发现可无界增长的路径**，仅作提示：可加硬上限（LRU 淘汰）。

### P3-9 死代码与未接线能力

**位置**：`api.go:689`（`drainAndClose` 定义后从未调用）、`store.go:318`（`SetDisabled` 无调用者）、`quota.go:258`（`CodeStats` 无调用者）、`store.go:33`（`kindStatus` 定义后从未使用）、`account.go:64`（`Disabled` 字段无任何写入路径）。功能缺口或冗余，建议删除或接线。

### P3-10 脚本与构建卫生

- `deploy/install.sh:49` 用 `curl -sk`（跳过证书校验）做本机健康检查——本机回环场景可接受，建议改用 `--cacert` 以保持习惯一致。
- `deploy/smoke-test.sh:11-18` 默认目标为**真实生产地址** `https://8.133.225.105:18443`，且未传 CACERT 时默认退回 `-k` 跳过校验；在真实链路上运行会把测试账号名/密钥与流量暴露给中间人（测试凭据非敏感，故为 P3）。
- 工作区存在未跟踪的 7MB 编译产物 `agentlite-server/agentlite-server`（已被 `.gitignore:47` 覆盖，**未入库**），源码目录内散落二进制属卫生问题。
- `go.mod` 未固定工具链（无 `toolchain` 指令），`install.sh:18` 直接用系统 `go build`，README 依赖镜像自带 `go1.26.5`；不可复现构建，且标准库 CVE 修复完全取决于镜像 Go 版本。

---

## 已检查、无发现的维度

1. **SQL / 命令 / Shell 注入**：已检查，无发现。全仓无 `database/sql`、无 ORM，数据层为 JSONL + JSON 文件；`grep os/exec|exec.Command|syscall.Exec` 零命中；无任何字符串拼接进 shell 的代码。
2. **路径穿越**：已检查，无发现。唯一的用户相关路径拼接 `store.go:500-514` 用 `sanitizeID` 做白名单过滤（仅保留 `[A-Za-z0-9_-]`），`.` 与 `/` 均被剔除；`accountID`/`deviceID` 又都由服务端 `crypto/rand` 生成，用户不可控（边界见 P3-4）。
3. **服务端 SSRF**：已检查，无发现。网关上游 URL 仅来自 `-upstream-url`/`AGENTLITE_UPSTREAM_URL`（`main.go:58-60`），非用户输入；请求体中的 `model`/`max_tokens` 等无法改变目标主机（`gateway.go:96-97` 用固定 `baseURL` 构造请求）。客户端可控 URL 的服务端请求**不存在**（相关信任链问题另见 P2-5）。
4. **第三方依赖 CVE**：已检查，无发现。`go.mod` 仅 3 行（`module` + `go 1.24`），**无 `require` 块、无 `go.sum`、零第三方依赖**，故无第三方 CVE 面。（标准库与工具链版本风险见 P3-10。）
5. **CSRF**：已检查，无发现。服务不使用 Cookie，认证一律走 `Authorization: Bearer`（`api.go:62-69`），浏览器不会自动附加该头；不存在可被跨站利用的凭据载体。**注意：这是当前唯一挡住 CSRF 的机制，若将来引入 Cookie 会话必须同时补 CSRF token。**
6. **CORS**：已检查，无发现。全代码零 `Access-Control-*` 头 —— 浏览器默认同源策略下跨域读取被拒，且无 `Allow-Credentials` 误配。属安全的默认姿态（建议显式声明策略并留注释，避免后人误加 `*`）。
7. **Host 头注入 / 开放重定向**：已检查，无发现。无任何代码读取 `r.Host` 或据此拼接 URL/跳转；日志与错误信息只使用 `r.URL.Path`。
8. **令牌传输与日志泄露**：已检查，无发现。令牌仅经 HTTPS 传输，仅从 `Authorization` 头读取（**未**支持 query 参数传递，`api.go:62-69`），`logMW` 不记录该头与请求体（`api.go:90`），`/health` 等响应体亦不含任何令牌或哈希；`Accounts()`（`account.go:435-447`）在对外返回前显式清空 `KeyHash`/`KeySalt`/`TokenHash`，`DevicesOfAccount` 清空 `LegacyTokenHash`（`account.go:427`）。
9. **私钥 / 口令入库**：已检查，无发现。`git ls-files` 核验：`agentlite-server/` 被跟踪的 15 个文件中**不含任何私钥**；`tls/server.crt` 是公钥（客户端 pin 锚点，公开属设计使然），`grep -c PRIVATE` 结果为 0；`git log --all --diff-filter=A -- "*.key"` 零命中（历史中从未提交过 `.key`）。工作区存在的 `ios/App/Resources/server.key`、`agent-unified/.e2e/tls*/server.key`、`_local/ios-e2e/tls/server.key` 经 `git ls-files --error-unmatch` 逐一确认**均未被跟踪**，且分别被 `.gitignore` 的 `ios/App/Resources/`、`.e2e/`、`/_local/` 规则覆盖。全仓 `grep "sk-[A-Za-z0-9]{20,}"` 零命中，未发现明文上游 Key。`deploy/` 下三个脚本亦无任何硬编码密钥或令牌（管理令牌一律经 `ADMIN_TOKEN` 环境变量注入）。
10. **传输中凭据的随机性**：已检查，无发现。账号令牌 `crypto/rand` 32 字节 → base64url（`store.go:187-193`）；服务端只存 `sha256(token)`（`store.go:195-198`），对 256-bit 高熵令牌无需加盐慢哈希，做法正确；账号密钥用 PBKDF2-HMAC-SHA256 / 210,000 次 / 16 字节随机盐（`account.go:36-38`、`188-195`），比对用 `subtle.ConstantTimeCompare`（`account.go:267`），账号不存在时仍执行等价派生抵御时序侧信道（`account.go:246-248`），账号枚举也被统一文案挡住（`api.go:256-259`）。
11. **死锁**：已检查，无发现。逐条核对锁序：`PayOrderWithCode` 取 `orders.mu` → **先解锁**再调 `s.Redeem`（取 `s.mu`），无嵌套；`CreateOrder` 路径 `cfgMu`（`Packs`）与 `orders.mu` 不重叠；`CreateCommand` 的 `s.mu.RLock`（`DeviceOfAccount`）与 `cmds.mu` 不重叠；`saveXxxLocked` 系列均在对应锁内调用，符合命名约定。未发现环状等待。
12. **额度双花**：已检查，无发现。`Redeem` 的「校验 `UsedBy` → 置位 → 加额度 → 删码 → 落盘」全部在同一把 `s.mu` 写锁内完成（`quota.go:153-188`），并发提交同一张码不会双花；`PayOrderWithCode` 即便并发也只会有一次核销成功。

---

## 端点 × 认证状态总表

来源：`main.go:89-129` 的路由注册表（`wrap` = 日志 + panic 恢复中间件）。共 22 条注册路由 + 1 条兜底。

| # | 方法 | 路径 | 认证要求 | 限流 | 备注 |
|---|---|---|---|---|---|
| 1 | GET | `/health` | **无** | **无** | 泄露账号/设备数量、版本、uptime（P2-7） |
| 2 | POST | `/api/v1/account/register` | **无** | 10 次/10 分钟 **每 IP** | 零验证；每账号白送 500 units（P0-2） |
| 3 | POST | `/api/v1/account/login` | **无** | 10 次/10 分钟 每 IP | 在全局写锁内跑 PBKDF2（P1-2）；成功即轮换令牌 |
| 4 | GET | `/api/v1/account/me` | Bearer | 无 | 响应含全部设备及其 `last_status`（P1-5） |
| 5 | POST | `/api/v1/device/bind` | Bearer | **无** | 无设备数上限；指纹劫持（P2-6）、写放大（P1-5） |
| 6 | POST | `/api/v1/sync/push` | Bearer + `X-Device-Id` | 无 | `status` 无大小校验（P1-5）；`ValidOrigin` 竞态（P2-1） |
| 7 | GET | `/api/v1/sync/pull` | Bearer + `X-Device-Id` | 无 | `limit` 已在 store 侧夹到 ≤1000 |
| 8 | GET | `/api/v1/config` | Bearer | 无 | 不需设备头；配置原样下发（P2-8） |
| 9 | GET | `/api/v1/packs` | Bearer | 无 | 套餐展示 |
| 10 | POST | `/api/v1/orders` | Bearer | **无** | **唯一无限流写接口**；二次方写入（P1-6） |
| 11 | GET | `/api/v1/orders` | Bearer | 无 | 读接口内写盘（P1-6） |
| 12 | POST | `/api/v1/orders/cancel` | Bearer | 无 | 归属校验正确 |
| 13 | POST | `/api/v1/orders/pay` | Bearer | 10 次/10 分钟 **每账号** | 码核销原子；订单状态可能不同步（见文末备注） |
| 14 | POST | `/api/v1/device/command` | Bearer + `X-Device-Id` | 10 条/分钟 每账号（store 层） | 要求目标设备属同账号，禁自发 |
| 15 | GET | `/api/v1/device/commands` | Bearer + `X-Device-Id` | 无 | 仅返回发给本设备的 pending |
| 16 | POST | `/api/v1/device/command/ack` | Bearer + `X-Device-Id` | 无 | 仅目标设备可 ack（防伪造结果） |
| 17 | GET | `/api/v1/device/command` | Bearer | 无 | 同账号任意设备可查（按 `id` 查询） |
| 18 | POST | `/api/v1/lan/rendezvous` | Bearer + `X-Device-Id` | 无 | `lan_ips` 未校验（P2-5）；出口 IP 服务端观测，不可伪造 |
| 19 | POST | `/api/v1/redeem` | Bearer | 10 次/10 分钟 每账号 | 错误文案已合并，防枚举 |
| 20 | POST | `/api/v1/admin/codes` | `X-Admin-Token`（常量时间比较） | **无** | 无限流可爆破（P2-4）；未配置令牌时返回 503 |
| 21 | POST | `/v1/chat/completions` | Bearer（令牌即 api_key） | **无** | 计费可绕过（P0-1）、并发超支（P0-3）、nil map panic（P1-1）、流式 flush/期限失效（P1-3） |
| 22 | GET | `/v1/models` | Bearer | 无 | 透传上游模型列表；不校验额度 |
| 23 | * | `/`（兜底） | **无** | **无** | 404 + 回显 `r.URL.Path`；**不经 `wrap`**，无日志无 recover（P3-1） |

**认证覆盖小结**：22 条业务路由中 **3 条完全无认证**（`/health`、`register`、`login`）+ 1 条兜底 404；1 条走独立 `X-Admin-Token`；其余 18 条走 Bearer 账号令牌，其中 7 条额外要求 `X-Device-Id` 并校验设备归属账号（`api.go:133-159`，做法正确）。跨账号探测已被统一错误文案抑制（`api.go:152-155`、`api.go:180-182`）。

**另注（P3 级）**：`PayOrderWithCode`（`order.go:252-297`）在「校验订单 pending → 解锁 → 核销兑换码 → 重新加锁」之间存在窗口，若订单在此期间被取消或过期，兑换码已核销、额度已到账，却返回 `order_closed`（用户额度无损，仅订单状态与账目不一致）。修复：把订单终态检查与核销的结果回写放在一次临界区内，或核销失败时补偿回滚。

---

## 优先修复建议（最小改动路径）

1. **发布前必做**：P0-1（强制 `include_usage` + `max_tokens` 上限）、P0-2（新账号额度需激活 / 每指纹限一次 / 降赠送额 + `/v1/*` 并发与频次闸门）、P0-3（预扣 + 结算）——这三条共同决定服务是否会立刻产生不可控的上游费用。
2. **紧随其后**：P1-1（`map` 非 nil 初始化，一行修复）、P1-2（PBKDF2 移出锁）、P1-3（给 `loggingWriter` 补 `Unwrap()`/`Flush()`）、P1-4（令牌过期 + logout）、P1-5/P1-6（`status` 上限、设备数上限、下单限流）。
3. **加固批次**：P2-1（把拷贝移进临界区）、P2-3（降权运行）、P2-4（admin 限流 + 令牌强度校验）、P2-2（短效叶证书 + SPKI pin + 轮换预案）、P2-6（指纹冲突拒绝改挂）。
4. **卫生批次**：P3-1/P3-2（兜底路由走 `wrap`，日志补 IP/requestId）成本极低、对安全运营收益直接，建议一并做掉。

**整体评价**：该项目在若干易错点上做得高于平均水平——零第三方依赖、令牌只存 sha256、账号密钥 PBKDF2 + 常量时间比较 + 抗枚举时序、`X-Forwarded-For` 一律不信任、路径穿越白名单过滤、请求体上限、TLS ≥1.2 且服务端不设 `InsecureSkipVerify`、systemd 沙箱相当完整、私钥未入库。真正的问题集中在**业务信任边界**与**并发/资源治理**两条线上：计费依赖客户端可关闭的信号（P0-1）、免费额度缺少发放门槛（P0-2）、余额检查与扣减非原子（P0-3），以及全库唯一的 `s.mu` 被放在慢操作内（P1-2）与若干无上限的增长点（P1-5/P1-6）。这些都不是「写法不讲究」，而是**上线即会被利用**的结构性缺陷，故列为发布阻塞。
