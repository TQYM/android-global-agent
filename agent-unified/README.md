# agent-unified — AgentLite 客户端 + 云服务端 整合版

把仓库里两个独立演进的项目整合成**一套可以一键构建、一键校验、一键发布、一键部署的系统**：

| 半身 | 原项目 | 角色 |
|---|---|---|
| **Android 客户端** | [`agent-lite/`](../agent-lite)（`com.dsh.agentlite`） | 无障碍感知 + 模型决策 + 执行；账号登录、信息同步、局域网 P2P |
| **云服务端** | [`agentlite-server/`](../agentlite-server) | 账号 / 设备绑定 / 账号级同步 / 局域网会合 / 额度网关 + 兑换码 |

> **整合的第一原则：不改任何功能与逻辑。**
> `client/` 与 `server/` 是两个原项目的**逐字节副本**，整合层只做四件事——
> 配置下发、证书下发、契约校验、发布打包。两处原项目目录保持原样，可继续独立演进。

---

## 快速开始

```bash
cd agent-unified

python3 scripts/verify-contract.py     # ① 两端契约门禁（26 项检查，只读）
bash scripts/build-all.sh              # ② 一条命令出全部产物 → dist/
bash scripts/e2e-local.sh              # ③ 本地端到端：真起服务 + 真跑 pin（需 Go/JDK）
bash deploy/deploy-server.sh           # ④ 线上部署（默认只打印计划，加 --yes 才执行）
```

没装 Go 也能构建客户端：

```bash
bash scripts/fetch-go.sh               # 让整合层自备一份 Go（装到 ~/.agent-unified-go，不污染系统）
# 或
bash scripts/build-all.sh --skip-server
```

---

## 目录结构

```
agent-unified/
├── unified.conf                 ★ 单一真相源：地址 / 端口 / 版本映射（唯一需要维护的文件）
├── local.env.example            敏感配置模板（local.env 被 gitignore，永不入 git）
├── client/                      agent-lite 的逐字节副本（含 client/build-mac.sh，一行未改）
├── server/                      agentlite-server 的逐字节副本
├── scripts/
│   ├── sync-sources.sh          从原项目同步副本；--check 证明「零改动」
│   ├── verify-contract.py       ★ 两端契约静态校验（10 组检查）
│   ├── sync-cert.sh             证书 pin 下发：server/tls/server.crt → client/assets/*.pem
│   ├── gen-client-env.sh        配置下发：unified.conf + local.env → client/dev.local
│   ├── build-all.sh             ★ 主入口：契约门禁 → 证书 → 配置 → 两端构建 → 发布清单
│   ├── build-client.sh          构建 APK（含两项硬断言，见下）
│   ├── build-server.sh          构建服务端（静态单二进制，linux/amd64 + 本机架构）
│   ├── fetch-go.sh              自备 Go 工具链
│   ├── release.sh               打发布件（一个 tar.gz = 完整一套系统）
│   ├── e2e-local.sh             ★ 本地端到端（真进程 + 真 TLS + 真实客户端 pin 代码）
│   └── selftest-verifier.sh     变异测试：证明契约门禁真的会拦住漂移
├── deploy/
│   ├── gen-cert.sh              签发自签证书（SAN 固化，避免「curl 通但 App 不通」）
│   └── deploy-server.sh         一键远程部署（rsync → 远端现编现装 → 公网冒烟）
├── tests/pin-smoke/PinSmoke.java 桌面 JVM 上跑客户端真实 TlsPin 的三态断言
└── docs/
    ├── INTEGRATION.zh-CN.md     整合设计：边界、数据流、零改动保证、变更流程
    └── CONTRACT.zh-CN.md        两端契约表（端点/字段/密码学参数/错误码，逐条可校验）
```

---

## 整合层到底做了什么

原始状态下，这两半靠**手工约定**咬合，任何一处不一致都表现为「同步不通」这类静默故障：

| 环节 | 原先 | 现在 |
|---|---|---|
| 客户端连哪个服务端 | 改 `build-mac.sh` 里的 `SYNC_URL` 常量 | `unified.conf` 一处 → 下发到客户端原生注入点 `dev.local` |
| 证书 pin | 手工 `cp tls/server.crt ../agent-lite/assets/agentlite_sync_ca.pem`（README §5 的一行提示） | `sync-cert.sh` 单一入口 + 契约校验比对哈希，不一致直接失败 |
| 两端参数是否一致 | 靠人记住「改了要同步改另一边」 | `verify-contract.py` 从**两端源码**抽取 11 项密码学/端口参数逐项比对 |
| 客户端调的接口是否存在 | 只能装机跑一遍才知道 | 路由静态比对：客户端 10 处调用点 ↔ 服务端 mux 注册表 |
| 构建产物可信度 | APK 字节数会巧合重复 | `build-client.sh` 断言「源码不比 APK 新」+「解 dex grep 类描述符」 |
| 部署 | server README §5 的 5 步手工流程 | `deploy-server.sh`（默认只打印计划，`--yes` 才执行） |
| 版本 | 客户端 0.6.0 / 服务端 0.1.0 各自为政 | 整合版 `0.7.0` 统一发布号 + `dist/RELEASE.json` 记录映射与来源快照 |

---

## 版本映射

`unified.conf` 是唯一真相源，**不修改**任何一端的内部版本号（改了就不是逐字节副本了）：

| 层 | 版本 | 说明 |
|---|---|---|
| 整合版发布号 | `0.7.0` | `UNIFIED_VERSION`，只用于整合层的产物命名与发布清单 |
| 客户端内部版本 | `0.6.0`（versionCode 6） | 原样保留；构建后由 `build-client.sh` 断言 APK 内确实如此 |
| 服务端内部版本 | `0.1.0` | 原样保留；`agentlite-server -version` 可查 |

---

## 由校验器强制的不变量

`scripts/verify-contract.py` 的 10 组检查（当前 26 项全通过）覆盖：

1. **零改动** —— `client/`、`server/` 与原项目逐字节一致
2. **证书 pin** —— 客户端信任锚 == 服务端证书，且 SAN 覆盖 `SERVER_HOST`
3. **地址一致** —— `unified.conf` ↔ 客户端构建默认值 ↔ systemd 单元 ↔ `dev.local`
4. **路由覆盖** —— 客户端每个调用点的路径与方法，服务端都注册了；已下线接口不得再被调用
5. **网关边界** —— 网关可达的模型路径必须被服务端实现；ASR 必须直连（按调用点判定，不按类型猜）
6. **局域网线协议** —— KDF（算法/迭代/长度/盐前缀）、AEAD（AAD/nonce/tag）、抗重放窗口、端口
7. **字段契约** —— 客户端发送/读取的字段服务端都认识（`data`/`status` 是不透明透传，单独排除）
8. **错误码** —— 客户端写了中文提示的错误码服务端确实会返回；401/403/409/413/429 语义对齐
9. **配置白名单** —— 远程配置只能是 5 个行为开关；`base_url` / `api_key` 永不下发（安全红线）
10. **记账单位** —— 1 unit = 1000 tokens 两端一致；上游失败不计费

自证不是橡皮图章：

```bash
bash scripts/selftest-verifier.sh      # 注入 6 类真实漂移，逐一确认校验器会 FAIL，再复原
```

---

## 常用命令

| 目的 | 命令 |
|---|---|
| 上游项目改了，同步进来 | `bash scripts/sync-sources.sh` |
| 确认整合层没改写任何一端 | `bash scripts/sync-sources.sh --check` |
| 只查契约 | `python3 scripts/verify-contract.py [--json\|--quiet]` |
| 出 APK + 服务端二进制 | `bash scripts/build-all.sh` |
| 只出 APK（本机没 Go 时） | `bash scripts/build-all.sh --skip-server` |
| 打发布件 | `bash scripts/release.sh` |
| 本地端到端 | `bash scripts/e2e-local.sh` |
| 部署到线上 | `bash deploy/deploy-server.sh --yes` |
| 只跑线上冒烟 | `bash deploy/deploy-server.sh --yes --smoke-only` |
| 换证书（生产信任锚） | `bash deploy/gen-cert.sh <IP> --remote` → 拉回 `server/tls/server.crt` → `bash scripts/build-all.sh` |

---

## 排障

| 症状 | 先看这里 |
|---|---|
| App 登录/同步不通，`curl -k` 却正常 | 证书 pin：`python3 scripts/verify-contract.py` 的「证书 pin」组；换过证书必须**重新打包并重装 APK** |
| 局域网两台设备永远不同步 | 「局域网线协议」组：两端 KDF/AEAD 参数漂移的表现就是静默的认证失败 |
| 契约校验说副本被改了 | 有人直接改了 `client/` 或 `server/` 里的文件；`bash scripts/sync-sources.sh` 会从原项目复原 |
| `build-server.sh` 退出码 3 | 本机没有 Go，跑 `bash scripts/fetch-go.sh` 或用 `--skip-server` |
| 构建出来的 APK 行为不对 | `build-client.sh` 的新鲜度断言会拦「改了源码但没进包」；字节数不可信，脚本已自动 grep dex |
| 线上部署误操作 | `deploy-server.sh` 默认只打印计划，不加 `--yes` 不动线上 |

---

## 边界（刻意不做的事）

- **不合并两个项目目录**：不做代码级重构、不改包名、不抽公共模块——那会破坏「零改动」。
- **不引入构建系统**：不引入 Gradle / Go module 之外的依赖管理，延续两个原项目「零第三方依赖、纯命令行」的风格。
- **不动线协议**：协议参数一律以两端现有实现为准；整合层只校验，不定义新协议。
- **不碰真机**：本地端到端只覆盖「服务端 + 客户端网络/证书代码」；真机安装、无障碍授权、手表/鸿蒙形态验证沿用 `tests/` 下既有流程。
