# 开发手册（Development Manual）

> **所属项目**：**`agentlite-server`**（服务器侧）。本工作区还有 `agent-client` / `agent-lite` /
> `ios` / `agent-unified` / `agentd` / `harmony-agent` 等其他项目——
> **项目清单与会话标注约定见 [`PROJECTS.zh-CN.md`](PROJECTS.zh-CN.md)**；改别的项目请读它自己的手册。
>
> **面向对象**：接手本项目的**其他对话 / 智能体 / 人类开发者**。
> **目的**：在一个文件里拿到「项目专用服务器」的连接方式与线上事实，直接开始功能实现，不必考古。
>
> **事实基准**：2026-09-12 对本机与线上逐条实测（§1 的每条命令都实际执行过）。
> 连接方式与路径是**相对稳定**的；标注「编写时点」的运行时数值会变，不要当断言用。
>
> **维护约定**：任何改动线上拓扑（换 IP、换端口、换路径、换证书）的提交，
> **必须同步更新本手册 §1–§3**，否则其他对话会按过期地址干活。

---

## 0. 按需跳转

| 你要做的事 | 去哪一节 |
|---|---|
| 只想连上服务器看看 | [§1 项目专用服务器连接](#1-项目专用服务器连接速查) |
| 改服务端功能并上线 | [§4 部署](#4-部署从本地到线上) · [§5 运维](#5-运维速查) · [§7 接口清单](#7-接口清单线上权威) |
| 改客户端 | [§1.3 服务端点](#13-服务端点) + `agent-lite/README.md` |
| 排查「连不上 / 认证失败」 | [§9 已知坑与红线](#9-已知坑与红线) |
| **别把两套服务器搞混** | [§8 与 LLM 中转站的边界](#8-与-llm-中转站的边界重要) |

---

## 1. 项目专用服务器连接（速查）

### 1.1 一句话

本项目的**专用服务器** = 阿里云 ECS **`8.133.225.105`**，上面跑 AgentLite 的
**账号 / 信息同步 / 额度网关**服务（Go 单二进制 + systemd）。
本机已配好 SSH 别名：**直接 `ssh aliyun-ecs`** 即可登录，无需任何口令。

### 1.2 SSH 接入

| 项 | 值 |
|---|---|
| SSH 别名 | **`aliyun-ecs`** |
| 主机 | `8.133.225.105` |
| SSH 端口 | `22` |
| 用户 | `root` |
| 私钥 | `~/.ssh/aliyun-ecs`（已在 `~/.ssh/config` 绑定，`IdentitiesOnly yes`） |
| 主机指纹 | ED25519 `SHA256:wGPyhnNA2DrEnttVyJgNkBSp2GRKTd1N1a/jA81NRnA` |
| 主机名 | `iZuf62xqd95a4seql9u23fZ` |
| 出口 | 阿里云 ECS，公网直连（**无反向代理**，服务不信任 `X-Forwarded-For`） |

本机 `~/.ssh/config` 中已有（**新机器需照抄这段**，见 §1.6）：

```sshconfig
# Added by DSH agent — Alibaba Cloud ECS
Host aliyun-ecs
    HostName 8.133.225.105
    User root
    IdentityFile ~/.ssh/aliyun-ecs
    IdentitiesOnly yes
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

**连通性自检**（一条命令确认 SSH 与服务都在）：

```bash
ssh aliyun-ecs 'hostname; systemctl is-active agentlite-server'
# 期望输出：iZuf62xqd95a4seql9u23fZ
#           active
```

### 1.3 服务端点

| 用途 | 地址 | 鉴权 |
|---|---|---|
| **公网 HTTPS 入口** | `https://8.133.225.105:18443` | 除 `/health`、注册、登录外都要鉴权：`Authorization: Bearer <账号令牌>`（管理接口另用 `X-Admin-Token`，见 §7.1） |
| 健康检查 | `GET https://8.133.225.105:18443/health` | **免鉴权**（自检用这个） |
| 服务器本机回环 | `https://127.0.0.1:18443` | 同上（SSH 进去后用，不过公网） |
| 额度网关（OpenAI 兼容） | `https://8.133.225.105:18443/v1/chat/completions` | `api_key` 直接用**账号令牌** |
| 局域网 P2P 直连 | TCP **`18444`**（设备间，服务器不经手数据） | AES-256-GCM，密钥两端各自派生 |

**公网免凭据自检**（不需要任何令牌，最适合确认「服务器还活着」）：

```bash
curl -sk https://8.133.225.105:18443/health
# 编写时点实测输出：
# {"accounts":3,"active_7d":9,"devices":9,"ok":true,"server_time":"2026-09-12T15:09:02Z",
#  "service":"agentlite-sync","uptime_sec":638,"version":"0.1.0"}
```

> 未知路由返回 404；未带令牌访问 `/v1/models` 返回 **401**（这是**预期**行为，不是故障）。

### 1.4 TLS 证书与客户端 pin（改动会连带打 APK）

服务用**自签证书**，因此公网访问必须 `-k`（跳过校验）或显式指定证书。

| 项 | 值 |
|---|---|
| Subject | `CN = 8.133.225.105, O = AgentLite` |
| SAN | `IP:8.133.225.105`、`IP:127.0.0.1`、`DNS:localhost` |
| 有效期 | 2026-09-12 → **2036-09-09** |
| 线上路径 | `/srv/agentlite-server/tls/server.{crt,key}`（`key` 为 `0600`） |
| 仓库内副本 | `agentlite-server/tls/server.crt` |
| md5 | `9040bc70569069b8ba6a42ea2dc9e333`（**本地副本与线上逐字节一致**，已实测） |

客户端证书 pin 即取自 `agentlite-server/tls/server.crt`（构建期生成 `PinnedCa`）。

> ⚠️ **证书是信任锚**：重新签发证书**必须**同步客户端 assets 并**重新打包 APK**，
> 否则已装 App 会因为 pin 不匹配拒绝连接。SAN 里少了服务 IP 也会导致主机名校验失败。

### 1.5 凭据：都放在服务器上，**不进仓库**

| 凭据 | 线上位置 | 权限 |
|---|---|---|
| `AGENTLITE_ADMIN_TOKEN` | `/etc/agentlite-server/env` | `0600 root` |
| `AGENTLITE_UPSTREAM_KEY` | 同上（DashScope Key） | `0600 root` |

> ⚠️ **env 文件里只有上面这两个变量**（实测掩码后正好 2 行）。
> **`AGENTLITE_UPSTREAM_URL` 不在 env 文件里** —— 线上生效值来自 `main.go:59` 的**编译期内置默认**
> `https://dashscope.aliyuncs.com/compatible-mode/v1`（journal 可确认：`额度网关已启用 → https://…`）。
> 想改上游地址，就往 env 文件里**新增**该变量并重启，或用启动参数 `-upstream-url`；
> **别因为 `grep` 不到就以为没配**。

**取用方式**：在服务器上一条命令内用完，不要把值带回本地、更不要写进文件。

```bash
# 只看前 8 位确认存在（安全）
ssh aliyun-ecs 'source /etc/agentlite-server/env && echo "${AGENTLITE_ADMIN_TOKEN:0:8}…"'

# 需要完整令牌时：在同一条远端命令里消费掉
ssh aliyun-ecs 'source /etc/agentlite-server/env && \
  curl -sk https://127.0.0.1:18443/api/v1/admin/codes \
    -H "X-Admin-Token: $AGENTLITE_ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"count\":10,\"units\":5000,\"batch\":\"promo-2026-09\"}"'
```

> 🚨 **本仓库是公开仓库**（`github.com/TQYM/android-global-agent`）。
> 管理令牌、账号密钥、DashScope API Key、TLS 私钥、管理员密码
> **一律不得**写入任何被 git 跟踪的文件——**包括本手册**。
> 本手册只写「去哪里取」，永不写「值是什么」。

### 1.6 新机器 / 新对话的最小复现

私钥与 `~/.ssh/config` 都在**本机** `~/.ssh/` 下，**不在仓库里**（这是刻意的）。
若对话跑在**另一台机器**上，需要先备好这两样：

```bash
# 1) 放置私钥（从已有机器或安全渠道获取，权限必须 600）
install -m 600 /path/to/aliyun-ecs ~/.ssh/aliyun-ecs

# 2) 追加 §1.2 的 Host 段到 ~/.ssh/config

# 3) 首次连接核对指纹，必须与 §1.2 的 SHA256 一致
ssh-keygen -F 8.133.225.105 -f ~/.ssh/known_hosts || ssh aliyun-ecs true
ssh aliyun-ecs true   # 首次会提示确认主机指纹，核对后再输 yes
```

**在别的机器上拿不到私钥时，不要绕路**：改用只读的公网接口（§1.3 的 `/health`），
或让持有私钥的一方代为执行；**不要**为了绕开而开放新端口或新建凭据。

---

## 2. 服务器全貌（编写时点实测）

| 项 | 值 |
|---|---|
| 系统 | Alibaba Cloud Linux 3（OpenAnolis Edition），kernel `5.10.134-19.8.al8.x86_64` |
| 规格 | **2 vCPU / 1.87 GB 内存 / 40 GB 磁盘**（已用 9.4G，26%） |
| 常驻内存 | 服务本身约 **4–8 MB**（这就是选 Go 纯标准库单二进制的原因） |
| Go | `go1.26.5 linux/amd64`（**已装**，满足 `go.mod` 的 `go 1.24` 要求） |
| 工具 | `git`、`tar`、`curl`、**`python3`** 已装（`python3` 是**必需**的，不是可选）；**`rsync` 没有装**——这会让 `deploy-server.sh --yes` 失败，见 §4.1 |
| 安全组 | **入方向 TCP 18443 已放行**（默认只开 22）；`18444` 是局域网 P2P，不需要公网放行 |

> 内存只有 1.87 GB —— 在服务器上**不要**跑 Gradle / Android 构建，只跑 Go 编译。
> 客户端构建在本机做。

---

## 3. 线上路径表（照抄即用）

```text
服务二进制      /usr/local/bin/agentlite-server          # 7262370 字节（编写时点），root:root
源码目录        /srv/agentlite-server/src/               # 与本地 agentlite-server/ 对应
TLS 证书/私钥   /srv/agentlite-server/tls/server.{crt,key}
数据目录        /var/lib/agentlite-server/               # 0700，见 §6
敏感配置        /etc/agentlite-server/env                # 0600 root，见 §1.5
systemd 单元    /etc/systemd/system/agentlite-server.service
journal 日志    journalctl -u agentlite-server
```

启动命令行（**事实来源**，与单元文件一致）：

```text
/usr/local/bin/agentlite-server \
    -addr  0.0.0.0:18443 \
    -data  /var/lib/agentlite-server \
    -cert  /srv/agentlite-server/tls/server.crt \
    -key   /srv/agentlite-server/tls/server.key
```

可用启动参数（见 `main.go`）。注意**只有表中「环境变量」列有值的那几项**才有 `AGENTLITE_*` 等价环境变量：

| 参数 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `-addr` | `AGENTLITE_ADDR` | `0.0.0.0:18443` | 监听地址 |
| `-data` | `AGENTLITE_DATA` | `/var/lib/agentlite-server` | 数据目录 |
| `-cert` / `-key` | `AGENTLITE_CERT` / `AGENTLITE_KEY` | `/srv/agentlite-server/tls/…` | TLS |
| `-admin-token` | `AGENTLITE_ADMIN_TOKEN` | 空 | **空 = 管理接口整体关闭** |
| `-upstream-url` | `AGENTLITE_UPSTREAM_URL` | DashScope 兼容端点（**编译期内置**） | 额度网关上游 |
| `-upstream-key` | `AGENTLITE_UPSTREAM_KEY` | 空 | **空 = 额度网关整体关闭（返回 503），同步能力不受影响** |
| `-register-rate` / `-register-window` | **无** | `10` / `10m` | 注册限流——**只能用启动参数，没有环境变量** |
| `-version` | **无** | — | 打印版本后退出 |

> 上面两项标「**无**」的是 `main.go:54-55` 直接写死的默认值，**没有 `envOr(...)`**，
> 因此 `AGENTLITE_REGISTER_RATE` 之类的变量**不存在、设了也没用**。要改注册限流只能改 systemd 单元的 `ExecStart`。

### 3.1 源码与本地的一致性（编写时点实测）

线上 `/srv/agentlite-server/src/` 与本地 `agentlite-server/` **语义完全等价**，
仅 5 个文件的第 3 行 doc 注释差一个 `# ` 前缀（各 2 字节，不影响编译与行为）：

| 文件 | 差异 |
|---|---|
| `main.go`、`gateway.go`、`go.mod` | **逐字节一致** |
| `api.go`、`command.go`、`store.go`、`account.go`、`quota.go` | 仅第 3 行 `// # …`（线上）vs `// …`（本地） |

即：本地在上线后又改过这几行注释，**功能无差异**；重新部署一次即抹平。
`compare` 时不要被 md5 不一致吓到——先 diff，多数情况就是这类注释漂移。

> 注意：线上 `src/` 的文件属主是 `501:games`（打包时保留了本地 uid），
> 二进制 `/usr/local/bin/agentlite-server` 是 `root:root`。这不影响运行。

---

## 4. 部署：从本地到线上

### 4.1 整合版一键部署（`agent-unified`）

> ⚠️ **先看这条**：这条路**当前在这台服务器上跑不通** —— 脚本第 ② 步要 `rsync`，而服务器没装。
> 想直接用它，**先执行 §4.1 末的「前置」装 rsync**；不想动服务器就跳 §4.2 手工流程（已实测可用）。

`agent-unified/unified.conf` 是**地址与路径的单一真相源**，其中已登记本服务器的连接信息：

```ini
SERVER_HOST=8.133.225.105
SERVER_PORT=18443
SYNC_URL=https://8.133.225.105:18443
LAN_PORT=18444
SERVER_SSH_HOST=aliyun-ecs
SERVER_SRV_DIR=/srv/agentlite-server
SERVER_DATA_DIR=/var/lib/agentlite-server
SERVER_ENV_FILE=/etc/agentlite-server/env
```

```bash
cd agent-unified

# ① 默认只打印计划，不动线上（先看清楚要动什么）
bash deploy/deploy-server.sh

# ② 确认无误才真的部署：契约校验 → rsync 源码 → 远端现编现装 → 重启 → 冒烟
bash deploy/deploy-server.sh --yes

# ③ 只跑线上冒烟回归（不部署）
bash deploy/deploy-server.sh --yes --smoke-only
```

> `deploy-server.sh` **默认 dry-run 是刻意的**：这是一条会**重启线上服务**的破坏性操作。

#### ⚠️ 前置：服务器上**没有装 rsync**，直接 `--yes` 会在第 ② 步失败

编写时点实测：`deploy-server.sh` 第 ② 步用**本地** `rsync -az --delete` 推送源码，
而 **rsync over SSH 要求两端都装 rsync** —— 服务器上没有，于是报
`bash: rsync: command not found`，`set -e` 直接中止。

```bash
# 实测复现（只读，安全）
rsync -az --delete --dry-run -e ssh agentlite-server/ aliyun-ecs:/srv/agentlite-server/src/
# → bash: rsync: command not found
# → rsync(37440): error: unexpected end of file      （exit 127）
```

**两种解法，任选一种**：

```bash
# 解法 A（推荐）：给服务器装上 rsync，一键部署即可用
ssh aliyun-ecs 'dnf install -y rsync'

# 解法 B：不改服务器，走 §4.2 的 tar | ssh 手工流程
```

> ✅ **它是「失败安全」的**：第 ② 步在 `install.sh` / `systemctl restart` **之前**中止，
> 所以**生产服务不会被搞坏**，只是源码没送上去。看到这个报错不必慌，装上 rsync 重跑即可。
>
> 原因是脚本第 ③ 步只检查远端 `go`、证书、私钥、env 文件，**没有检查 rsync**，
> 所以它会一路走到第 ② 步才失败。

> ⚠️ **第 ⑤ 步会自动打生产环境跑冒烟**（用 `SYNC_URL` 指向真实线上服务）。
> 即 `--yes` 一旦跑通，**就会往生产数据目录写入测试账号与设备**。
> 这是脚本既有的设计（部署完立刻验证真实链路），但你必须知道它会发生；
> 想避免就先按 §9.2 用临时实例回归，再单独决定要不要打生产。

### 4.2 手工路径（等价，服务端 README §5 的流程）

```bash
cd agentlite-server

# 1) 传输源码
tar czf - . | ssh aliyun-ecs \
  'rm -rf /srv/agentlite-server/src && mkdir -p /srv/agentlite-server/src && \
   tar xzf - -C /srv/agentlite-server/src'

# 2) 远端构建 + 装 systemd + 重启（脚本内部会 curl 本机 /health）
ssh aliyun-ecs 'cd /srv/agentlite-server/src && bash deploy/install.sh'
```

`install.sh` 做四件事：`CGO_ENABLED=0 go build -trimpath -ldflags "-s -w"` →
装到 `/usr/local/bin/agentlite-server` → `install -m 644` 单元 + `systemctl restart` →
本机 `curl -sk https://127.0.0.1:18443/health`。

> **前置条件**：服务器已装 `go`（已满足）；`/srv/agentlite-server/tls/server.crt` 必须存在，
> 否则 `install.sh` 直接退出。
>
> **改动 `deploy/agentlite-server.service` 后**必须部署一次才会生效（单元是安装进去的，不是软链）。

---

## 5. 运维速查

```bash
# 状态 / 开关机
ssh aliyun-ecs 'systemctl status agentlite-server --no-pager -l'
ssh aliyun-ecs 'systemctl restart agentlite-server'

# 日志（刻意不记录令牌与请求体）
ssh aliyun-ecs 'journalctl -u agentlite-server -n 100 --no-pager'
ssh aliyun-ecs 'journalctl -u agentlite-server -f'          # 实时跟随

# 公网自检（本机执行，不需要凭据）
curl -sk https://8.133.225.105:18443/health

# 看监听端口与进程
ssh aliyun-ecs 'ss -lntp | grep -E "18443|18446"'

# 版本
ssh aliyun-ecs '/usr/local/bin/agentlite-server -version'
```

### 5.1 冒烟回归

```bash
cd agentlite-server

# 基础回归（不测管理接口）
bash deploy/smoke-test.sh https://8.133.225.105:18443 tls/server.crt

# 含兑换码管理接口用例：令牌从远端取出，只进入本次命令的环境变量，
# 不落盘、不回显（服务端 README 用的就是这个写法）
ADMIN_TOKEN="$(ssh aliyun-ecs 'source /etc/agentlite-server/env && printf %s "$AGENTLITE_ADMIN_TOKEN"')" \
  bash deploy/smoke-test.sh https://8.133.225.105:18443 tls/server.crt
```

脚本结束会打印 `通过 N 项，失败 N 项`，`FAIL>0` 时非零退出。

> 🚨 **不要随手拿生产环境跑冒烟**：脚本会**真实创建账号与设备**，写进 `/var/lib/agentlite-server/`。
> 更麻烦的是**旧记录迁移是一次性的**——一台设备**首次**绑定到哪个账号，它的历史就归到哪个账号，
> 用测试账号登录真机会把用户真实历史迁走。
> **推荐做法**：在服务器上起一个**临时实例**打到临时数据目录跑（见 §9「本机临时实例回归」）。

### 5.2 发放兑换码

```bash
ssh aliyun-ecs 'source /etc/agentlite-server/env && \
  curl -sk https://127.0.0.1:18443/api/v1/admin/codes \
    -H "X-Admin-Token: $AGENTLITE_ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"count\":10,\"units\":5000,\"batch\":\"promo-2026-09\"}"'
```

兑换码字母表刻意剔除了 `0/O/1/I/L` 等易混字符。核销与记账在**同一把锁内**完成，防止并发双花。

### 5.3 改远程下发配置（**无需重启**）

编辑 `/var/lib/agentlite-server/config.json` 并**递增 `version`**，服务按 mtime 缓存，改完即生效。
文件格式：`{"version":<整数>,"values":{...}}`。当前线上是 `{"version":89,"values":{"max_steps":50}}`。

#### ⚠️ 白名单在**客户端**，不在服务端（实测澄清）

只有这 5 个键会被客户端采纳：`max_steps` / `planner_model` / `vision` / `spec_inject` / `spec_clamp`。

但**不要以为服务端会拒绝别的键**——服务端 `store.Config()` 把 `config.json`
**原样读出来下发**，不做任何键过滤（`max_steps` 等键名在整个 Go 源码里**一次都没出现**）。
真正的白名单是客户端 `agent-lite/src/com/dsh/agentlite/SyncClient.java:68` 的
`CONFIG_WHITELIST`，注释写得很明确：「允许被服务端下发的配置键（白名单，越权键一律忽略）」。

实践含义：

- 你往 `config.json` 里写 `base_url` / `api_key`，`GET /api/v1/config` **会原样回显**它们，
  看起来"生效了"，但**客户端会忽略**，不会有任何实际效果——别靠 curl 回显来判断成败。
- 判断配置是否真的生效，要看**设备端行为**（如 `max_steps` 变化后任务步数上限是否改变），
  而不是看接口回显。
- `agentlite-server/README.md` §7 把这条写在「服务端安全设计」标题下，容易读成"服务端在拦"；
  以代码为准——**拦的是客户端**。这也是为什么「服务端失陷 → 改写客户端出站地址 → 定向窃取 Key」
  这条利用链目前只在客户端侧被切断，服务端本身没有防线。

---

## 6. 数据与备份

```text
/var/lib/agentlite-server/           # 目录 0700
├── accounts.json                   账号表（PBKDF2 派生值 + 盐，0600）
├── devices.json                    设备表（归属账号、局域网位置、状态快照）
├── records/<account_id>.jsonl      账号级记录流，append-only
├── redeem_codes.json               未核销的兑换码
├── quota_ledger.jsonl              额度流水（每次变动一条）
├── commands.jsonl                  跨设备指令审计流水（见 §7.2）
└── config.json                     下发配置（改它即生效）
```

**备份**：打包这个目录即可。**恢复**：停服务 → 覆盖 → 启服务。

```bash
ssh aliyun-ecs 'tar czf /tmp/alt-backup-$(date +%F).tgz -C /var/lib agentlite-server && ls -la /tmp/alt-backup-*.tgz'
```

> 记录与流水**不做服务端淘汰**，长期运行需要加 logrotate 式清理。
> `Pull` 每次全量扫描该账号 JSONL 做过滤（O(n)），单账号数万条内无感，再大需索引。

---

## 7. 接口清单（线上权威）

以下路由**逐条取自 `main.go` 的 `mux.HandleFunc` 注册**，与线上一致。

### 7.1 已有接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 无 | 健康检查 |
| POST | `/api/v1/account/register` | 无（按 IP 限流） | `{name,key}` → `{account_id,token,quota,lan_key_params}` |
| POST | `/api/v1/account/login` | 无（按 IP 限流） | **每次登录轮换令牌，旧令牌立即失效** |
| GET | `/api/v1/account/me` | Bearer | 账号信息、额度、名下设备 |
| POST | `/api/v1/device/bind` | **仅 Bearer**（`authMW`） | 同指纹幂等复用；**设备身份取自 body 的 `fingerprint`，不需要 `X-Device-Id` 头** |
| POST | `/api/v1/sync/push` | Bearer + `X-Device-Id` | `{tasks,memories,status}` → `{accepted,duplicates,rejected,server_seq}` |
| GET | `/api/v1/sync/pull?since=N` | Bearer + `X-Device-Id` | `{records:[…],server_seq,config}` |
| GET | `/api/v1/config` | Bearer | 单独取配置（轻量轮询） |
| POST | `/api/v1/lan/rendezvous` | Bearer + `X-Device-Id` | 局域网会合，判据是服务端观测的公网出口 IP |
| POST | `/api/v1/redeem` | Bearer | `{code}` → `{units_added,quota}` |
| POST | `/api/v1/admin/codes` | `X-Admin-Token` | 批量发兑换码 |
| GET | `/api/v1/packs` | Bearer | 套餐列表（`packs.json` 可覆盖默认） |
| POST | `/api/v1/orders` | Bearer | 创建订单（pending，15 分钟超时过期） |
| GET | `/api/v1/orders` | Bearer | 订单列表 |
| POST | `/api/v1/orders/cancel` | Bearer | 用户取消订单 |
| POST | `/api/v1/orders/pay` | Bearer | 兑换码核销支付（记账以兑换码实际 units 为准） |
| POST | `/v1/chat/completions` | Bearer | OpenAI 兼容；额度不足 `402 quota_exhausted` |
| GET | `/v1/models` | Bearer | 透传上游模型列表 |

> ⚠️ **2026-09-13 补录**：`/packs` 与 `/orders` 系列 5 条路由取自本地 `main.go:102-106`（配套新文件 `order.go`，2026-09-13 01:23 落盘）。
> **编写时点线上尚未确认部署该版本**——部署后请以线上 `/health` 版本与实测为准，并按维护约定更新本表。

账号相关约定：账号名 3–32 位小写字母/数字/`_`/`-`（服务端转小写），重名 `409 account_exists`；
账号密钥 ≥8 位；PBKDF2-HMAC-SHA256 / 210000 次 / 32 字节；新账号赠 500 units（1 unit = 1000 tokens）。

### 7.2 跨设备指令通道（**服务端 README 尚未收录**）

场景：用户在设备 1 上说「让设备 2 打开 B 站」，设备 2 自动执行。
实现在 `command.go`，**当前 `agentlite-server/README.md` 没有写这一节**——接手时注意别以为不存在。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/v1/device/command` | Bearer + `X-Device-Id` | 下发指令（下发方 = 当前 `X-Device-Id`） |
| GET | `/api/v1/device/commands` | Bearer + `X-Device-Id` | **目标设备**拉取待执行指令（顺带清理过期项） |
| POST | `/api/v1/device/command/ack` | Bearer + `X-Device-Id` | **仅目标设备**回报 `running`/`done`/`failed` |
| GET | `/api/v1/device/command` | Bearer | 下发方查询结果（同账号任意设备可查） |

设计约束（实现里是硬规则，改功能时不要拆掉）：

- **拉取式投递**，不用长连接——国产 ROM 杀后台导致长连接不可靠，拉取天然自愈；
- **指令是瞬态的**：`CommandTTL = 5 分钟`，存在内存里，**重启即丢**；落盘的只有审计流水 `commands.jsonl`；
- **只允许定向，不支持广播**——广播等于让一个失陷账号一次控制全部设备；
- 目标设备必须属于**同一账号**，且 **ack 只能由目标设备自己发出**（防冒领/防伪造结果）；
- 限流：**每账号每分钟最多 10 条**；`task` 长度 1–512；
- 自己给自己下发返回 `ErrCommandSelf`；跨账号查询返回 `ErrCommandNotFound`（不泄露存在性）。

---

## 8. 与 LLM 中转站的边界（**重要**）

本项目涉及**两套互不相干的服务器**，README 已明确警告**不要混用**：

| | 本项目专用服务器 | LLM 中转站 |
|---|---|---|
| 地址 | **`8.133.225.105`** | `23.185.168.190`（域名 `api.lvziwang.top`） |
| 角色 | AgentLite 账号 / 同步 / 额度网关 | 模型 API 中转（Docker Compose: Caddy + new-api + account-bridge） |
| SSH | 别名 `aliyun-ecs`，密钥 `~/.ssh/aliyun-ecs` | 另一套凭据（**不在本手册范围**） |
| 文档 | **本手册** | `_local/SERVER_TECHNICAL_HANDBOOK.zh-CN.md`（**gitignore，仅本机**） |

> ⚠️ **自引用风险**：当前模型本身可能经由中转站转发（DSH → new-api → account-bridge → 上游）。
> 任何会中断 `account-bridge` 的操作都可能**切断你自己的模型通道**，
> 必须遵守那份手册 §6 的维护门流程，**不得直接 `docker stop`**。

改动本服务器（`8.133.225.105`）**不会**影响模型通道；反过来，中转站故障表现为「模型调用失败」，
与本服务器的「同步失败」是两类不同症状——**先分清是哪一套再动手**。

---

## 9. 已知坑与红线

### 9.1 连接与部署

- **服务器没装 `rsync`**（实测已有 `git`/`tar`/`curl`/`python3`，**只缺 `rsync`**）。`deploy-server.sh` 第 ② 步依赖它，
  会以 `bash: rsync: command not found` 中止（**失败安全，不会弄坏生产**）。
  解法：`ssh aliyun-ecs 'dnf install -y rsync'`，或走 §4.2 的 `tar | ssh` 流程。详见 §4.1。
- **`python3` 是必需依赖，别漏装**：`deploy/smoke-test.sh` 的 `jget`/`jlen` 靠它解析 JSON，
  `agent-unified/scripts/verify-contract.py` 也是 python3 脚本（在**本机**跑）。本机与服务器都已有。
- **注释漂移会伪装成「源码不一致」**：线上与本地 md5 不同先 `diff`，多半只是 `// # ` 前缀（§3.1）。
- **改单元文件不部署就不生效**（`install.sh` 是「安装」不是「软链」）。
- **`18446` 端口上的临时实例可能是别人留下的**：编写时点线上有一个残留进程
  `/tmp/agentlite-server -addr 127.0.0.1:18446 -data /tmp/altest2`（属主 root、非 systemd 托管）。
  它**不影响**生产的 18443，但排查端口占用时会撞见。用完请清理，别让它常驻。

### 9.2 本机临时实例回归（推荐做法，避免污染生产）

想在服务器上跑冒烟又不想写进生产数据，就起一个**独立数据目录的临时实例**：

```bash
# 在服务器上（注意：这条序列刻意不打生产数据目录）
ssh aliyun-ecs 'cd /srv/agentlite-server/src && CGO_ENABLED=0 go build -o /tmp/agentlite-test . && \
  mkdir -p /tmp/altest && \
  nohup /tmp/agentlite-test -addr 127.0.0.1:18446 -data /tmp/altest \
    -cert /srv/agentlite-server/tls/server.crt -key /srv/agentlite-server/tls/server.key \
    >/tmp/altest.log 2>&1 & sleep 1; curl -sk https://127.0.0.1:18446/health'

# 回归完清理（务必执行，别留残留进程 —— 见 §9.1）
ssh aliyun-ecs 'pkill -f "agentlite-test|/tmp/agentlite-server" ; rm -rf /tmp/altest /tmp/altest2'
```

**注意**：临时实例的 `/health` 会显示 0 账号 0 设备，这是对的（它用的是自己的空数据目录）。

### 9.3 服务端行为

- **限流是内存态，重启清零**，但**三处限流的键不一样**，别一概而论：
  注册/登录按**客户端 IP**（`"reg:"+ip`、`"login:"+ip`，默认 10 次/10 分钟/IP），
  **兑换是按账号**（`"redeem:"+AccountID`，api.go:518）——所以换个 IP 并不能绕过兑换限流。
  回归被挡住时 `systemctl restart agentlite-server` 即可清空。
- **`decodeBody` 刻意不启用 `DisallowUnknownFields`**：客户端先于服务端升级时，新字段应被忽略
  而非让整批同步 400 丢数据。代价是未知字段被静默忽略——
  **测试必须断言 `accepted`/`duplicates` 计数，不能只看 HTTP 状态码**。
- **旧记录迁移是一次性的**（成功后旧文件改名 `.migrated`）：一台设备**首次**绑定到哪个账号，
  它的历史就归到哪个账号。**用测试账号登录真机会把用户真实历史迁走**，测试务必避开。
- **远程配置白名单在客户端、不在服务端**：服务端把 `config.json` 原样下发（不做键过滤），
  越权键会被**客户端** `SyncClient.CONFIG_WHITELIST` 忽略。**别靠 `GET /api/v1/config` 的回显
  判断配置是否生效**——它会原样回显 `base_url`/`api_key`，但客户端根本不用。详见 §5.3。
- **额度是「先检查后扣减」**，并发请求可能小幅超支。面向单用户级规模可接受；对外规模化需改预扣 + 结算。
- **局域网判定依赖服务端观测的公网出口 IP**：双 WAN、部分设备走流量、或一方仅 IPv6 时，
  物理同网也不会判为同内网，会退回云端同步。这是刻意的保守取舍（不采信可伪造的客户端自报）。

### 9.4 计量逻辑（两个真实 bug，接真实上游才暴露）

接上真实 DashScope 之前，网关只用 mock 上游验证过，于是这两个问题全部漏过：

- **流式用量完全漏计**：DashScope 兼容流式**默认不回传用量**（每个 chunk 的 `usage` 都是 `null`）。
  修法两处：① 转发前给流式请求注入 `stream_options.include_usage=true`；② 提取器显式跳过 `"usage":null`。
- **余额不足反而导致无限免费**：原先 `quota < units` 时**直接返回不扣款**，而 402 闸门只认 `quota <= 0`，
  于是余额停在 `1..units-1` 的账号永远扣不动。改为允许透支到 0 以下，下一次请求即被 402 挡下。

> **教训**：计量逻辑**必须拿真实上游验证**。mock 只能验证「协议形状对不对」，
> 验证不了「真实上游到底给不给用量字段」。

### 9.5 客户端侧（改同步功能时会撞上）

- **`input text` 是追加不是替换**：登录页会预填上次账号名，直接输入会拼成「旧名+新名」→ 401，
  看起来像「账号密钥错误」。必须先 `keyevent 123`(MOVE_END) + 一串 `keyevent 67`(DEL) 清空。
- **`am start` 会静默忽略**：目标 Activity 已在任务栈时 `am start -n` 输出
  `Warning: Activity not started…` 然后**前台仍是上一个页面**，表现为「启动后自己退出了」。
  验证必须加 `-S`（先 force-stop）。
- **`adb` 不在 PATH**：每次 shell 调用都是新环境，脚本要自己定位（`shutil.which` + SDK 常见路径）。
- **构建产物字节数不可信**：本项目在同字节数下产出过内容不同的 APK。
  必须 `unzip -o app.apk classes*.dex` 后 `grep -ac <新增标记> classes.dex` 确认代码真进包，
  并用 `find src res AndroidManifest.xml -newermt "@$(stat -f %m build/x.apk)"` 校验构建新鲜度。

### 9.6 红线（和其他对话协作时同样适用）

- 本仓库是**公开仓库**，**不提交**任何密钥、令牌、私钥、账号密钥、APK、设备日志；
  提交前先看 `git status`，**不要 `git add -A`**。
- 不要为「方便连接」而**新开公网端口**或**新建长期凭据**；需要访问就用 §1 的既有通道。
- 不要在 `8.133.225.105` 上动 §8 的中转站容器；不要直接 `docker stop` account-bridge。
- 部署到线上属于**破坏性操作**（会重启服务），先用 dry-run 看清计划再 `--yes`。

---

## 10. 其他对话开工模板

把下面这段贴给新对话，即可让它带着正确前提开始：

```text
项目：AgentLite（Android 全局智能体）—— 专用服务器与连接方式见
      docs/DEVELOPMENT_MANUAL.zh-CN.md

服务器：阿里云 ECS，SSH 别名 aliyun-ecs（root@8.133.225.105，私钥 ~/.ssh/aliyun-ecs，本机已配好）
服务：agentlite-server（Go 单二进制 + systemd），公网 https://8.133.225.105:18443，
      健康检查 /health 免鉴权；自签证书 SAN 含该 IP，本地副本 agentlite-server/tls/server.crt
代码：本地 agentlite-server/  ←→  线上 /srv/agentlite-server/src/
部署：cd agent-unified && bash deploy/deploy-server.sh          # 默认 dry-run
      cd agent-unified && bash deploy/deploy-server.sh --yes    # 真的部署（会重启线上服务）
      注意：服务器没装 rsync，直接 --yes 会在第 ② 步中止（失败安全）；
            先 ssh aliyun-ecs 'dnf install -y rsync'，或改用 tar | ssh 手工流程；
            该脚本第 ⑤ 步会自动打生产环境跑冒烟（会写入测试账号/设备）。
约束：公开仓库，任何令牌/密钥/私钥都不得写入被跟踪文件；
      改动线上拓扑必须同步更新该手册 §1–§3。
```

**开工前 30 秒自检**：

```bash
ssh aliyun-ecs 'hostname; systemctl is-active agentlite-server' && \
  curl -sk https://8.133.225.105:18443/health
```

两条都通，说明连接与服务都正常，可以开始实现功能。

---

## 11. 相关文档

| 文档 | 内容 |
|---|---|
| `agentlite-server/README.md` | 服务端权威设计说明（能力、接口、局域网线协议逐字节规格、部署、踩坑） |
| `agent-unified/docs/INTEGRATION.zh-CN.md` | 整合版（客户端 + 服务端）发布与配置下发 |
| `agent-unified/docs/CONTRACT.zh-CN.md` | 两端契约门禁（路由/字段/错误码/密码学参数） |
| `agent-unified/unified.conf` | **地址与路径的单一真相源** |
| `_local/SERVER_TECHNICAL_HANDBOOK.zh-CN.md` | **另一套服务器**（LLM 中转站）的手册，仅本机、不入 git |
| `SECURITY_REVIEW_2026-09-05.zh-CN.md` | 安全审查（含端口暴露面判定标准） |
| `docs/PROJECT_REVIEW_2026-09-13.zh-CN.md` | **项目全面体检报告**：构建矩阵/git 卫生/密钥审计/各组件发现与修复路线（P0–P3 逐条含 file:line），摘录见 `PROJECT_OVERVIEW.zh-CN.md` §13 |
| `docs/HANDOFF_2026-09-13.zh-CN.md` | 内购改版跨会话交接清单（卡点/决策/待办） |

> 本手册与 `agentlite-server/README.md` 的分工：**README 讲「服务是怎么设计的」，
> 本手册讲「怎么连上它、怎么把改动送上去」**。两者冲突时，以源码与线上实测为准，并修正文档。
