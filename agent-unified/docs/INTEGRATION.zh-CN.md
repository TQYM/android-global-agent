# 整合设计与边界（agent-unified）

> 面向后续接手的人（或 AI）：为什么这样整合、边界在哪、什么被强制保证、什么**刻意不做**。

---

## 1. 被整合的两个半身

```
┌─────────────────────────── Android 手机 / 手表（agent-lite） ───────────────────────────┐
│ MainActivity / WatchSettingsActivity / SettingsActivity / LoginActivity                 │
│        │                                                                                │
│        ├── AgentEngine ── LlmClient ──┬─ 直连：用户自填 Base URL + API Key（未登录）      │
│        │                              └─ 网关：serverBase + "/v1"（登录后，受账号额度约束）│
│        │                                                                                │
│        ├── AgentA11yService（无障碍：节点/手势/截图/文本注入）                            │
│        ├── AccountClient ─┐                                                             │
│        ├── SyncClient ────┼─ HTTPS + 证书 pin（TlsPin + 构建期 PinnedCa）                 │
│        ├── LanSync ───────┘                                                             │
│        └── OverlayController / AuthStore / TaskHistory / SensitiveGuard / EcoPolicy      │
└───────────────┬──────────────────────────────────────────────┬──────────────────────────┘
                │ 云端同步（账号级）                             │ 局域网 P2P（数据不过服务器）
                ▼                                              ▼
┌────────────────────── agentlite-server（Go，零第三方依赖） ──────┐   ┌───────────────────┐
│ account.go  账号（PBKDF2 210k，只存派生值+盐；登录轮换令牌）      │   │ 另一台同账号设备    │
│ api.go      设备绑定 / 同步 push·pull / 局域网会合 / 账号视图     │◀──│ AES-256-GCM 直连   │
│ quota.go    额度与兑换码（防双花，同锁内核销+记账）               │   │ 会合只交换地址与参数 │
│ gateway.go  OpenAI 兼容转发 → DashScope，按 usage 扣额度         │   └───────────────────┘
│ store.go    append-only JSONL + 设备档案 + 远程配置（mtime 缓存） │
└──────────────────────────────────────────────────────────────────┘
```

**为什么必须整合**：这两半单独都不成立——客户端登录、跨设备历史/记忆、额度约束、局域网加速
全部依赖服务端；而服务端的每个接口都只服务于这一个客户端。它们是一个产品的两个进程。

---

## 2. 整合层的四条设计原则

### 原则一：零改动（最重要）

`client/` 与 `server/` 是原项目的**逐字节副本**，由 `scripts/sync-sources.sh` 从
`../agent-lite/`、`../agentlite-server/` 复制而来。整合层从不编辑这两个目录里的文件。

保证方式（可验证，不是口头承诺）：

```bash
bash scripts/sync-sources.sh --check     # rsync itemize 干跑比对，任何差异即非零退出
```

它同时是 `verify-contract.py` 的第①组检查，所以**每次构建都会重新证明一遍**。

> 两处刻意排除：
> - `client/dev.local` —— 构建期注入的密钥文件（gitignore），由 `gen-client-env.sh` 生成；
> - `client/assets/agentlite_sync_ca.pem` —— 证书 pin，真相源是**服务端证书**，
>   由 `sync-cert.sh` 下发。排除它才能让「证书轮换」只有一个入口。

### 原则二：单一真相源

`unified.conf` 是唯一需要维护的文件（地址、端口、版本映射、部署路径）。
它通过各端**原生已有的注入点**生效，而不是靠改代码：

| 值 | 下发到哪里 | 用的什么注入点 |
|---|---|---|
| `SYNC_URL` / `API_KEY` / `BASE_URL` / `MODEL` | `client/dev.local` | 客户端 `build-mac.sh` 本来就会 `. ./dev.local`（原项目自带能力） |
| 证书 | `client/assets/agentlite_sync_ca.pem` | 客户端构建期把它编成 `PinnedCa` 常量 |
| 端口 / 目录 | 服务端 `-addr/-data/-cert/-key` 参数 | systemd 单元与 `install.sh` 既有约定 |

### 原则三：契约由机器守，不靠人记

两端靠线协议咬合，而**这类故障全是静默的**：404 被 catch、字段拼错被忽略、
证书换了只是「同步不通」、KDF 参数漂移只是「认证失败」。所以契约校验必须自动化，
且结论必须来自源码事实（详见 `docs/CONTRACT.zh-CN.md`）。

`scripts/verify-contract.py`：10 组、26 项检查，只读，退出码可当 CI 门禁。

`scripts/selftest-verifier.sh`：往副本注入 6 类真实漂移，逐一确认门禁会 FAIL 且
定位到正确分组，再从原项目复原。**一个永远报绿的校验器比没有校验器更危险**，
所以这一步是整合层的一部分。

### 原则四：静态校验必须配动态验证

静态比对证明「代码看起来一致」，但证明不了「跑起来真的通」。
`scripts/e2e-local.sh` 在 127.0.0.1 上补上另一半：

1. 用副本源码现编服务端 → 起真实进程 → 跑服务端自带冒烟脚本（账号/设备/同步/会合/额度/兑换/鉴权）
2. 用**客户端真实的 `TlsPin.java`**（原样编译）跑证书 pin 握手 → 必须 200
3. 服务端换另一张证书 → 必须握手失败（证明 pin 不是摆设）
4. 不应用 pin（走系统 CA）连自签服务 → 必须失败（证明成功来自 pin，而非默认宽松）

第 3、4 条是**负例断言**：没有它们，第 2 条的绿可能是假的。

---

## 3. 证书信任锚的单向流

整条链路只有一个真相源，方向不可逆：

```
deploy/gen-cert.sh <IP> --remote        （在 ECS 上签发，SAN 固化包含服务 IP）
        │
        ├─→ 服务器 $SERVER_SRV_DIR/tls/server.crt + server.key（600）
        │
        └─→ scp 拉回 server/tls/server.crt
                    │
                    └─→ scripts/sync-cert.sh → client/assets/agentlite_sync_ca.pem
                                    │
                                    └─→ client/build-mac.sh 编成 PinnedCa → APK
```

**换证书 = 换信任锚**，必须重新打包并重装 APK，否则已装的 App 会连不上
（这是设计使然：`TlsPin` 只信任内置那一张，且**不放松主机名校验**）。

---

## 4. 目录映射与发布

| 整合层 | 来源 | 关系 |
|---|---|---|
| `agent-unified/client/` | `agent-lite/` | 逐字节副本（排除构建产物、`dev.local`、证书 pin） |
| `agent-unified/server/` | `agentlite-server/` | 逐字节副本（排除编译产物） |
| `dist/agentlite-unified-<ver>-android.apk` | 由 `client/build-mac.sh` 产出 | 内含 `DevConfig`（地址）+ `PinnedCa`（证书） |
| `dist/agentlite-unified-<ver>-server-linux-amd64` | `go build`（CGO_ENABLED=0 + `-trimpath -s -w`） | 与服务器上现编等价 |
| `dist/RELEASE.json` | 构建时生成 | 版本映射 + 上游 git 快照 + 产物校验和 |
| `sources.lock.json` | `sync-sources.sh` 生成 | 本次同步对应的上游 HEAD 与未提交条目数 |

版本策略：整合版发布号（`UNIFIED_VERSION`）独立，**不改**两端内部版本号
（客户端 `0.6.0/6`、服务端 `0.1.0`）——改了就不再是逐字节副本。
`build-client.sh` 会用 `aapt2 dump badging` 断言 APK 内的版本确实等于 `unified.conf` 的映射。

---

## 5. 构建期的三条硬断言（来自上游的实盘教训）

服务端 README §8.1 记下了两条真实踩过的坑，整合层把它们固化成构建断言：

| 断言 | 为什么 |
|---|---|
| **交付新鲜度**：`src/res/AndroidManifest/assets` 不得比 APK 新 | 出现过「改了源码但没进这次构建」，而 APK 看着是新的 |
| **内容真在包里**：解 dex 后 `grep -c` 类描述符 | **APK 字节数会巧合重复，完全不可信**（同字节数下产出过不同内容） |
| **版本映射一致**：`aapt2 dump badging` 的包名/versionName/versionCode == `unified.conf` | 防止发布清单与真包不一致 |
| **配置真的进包**：内嵌同步地址的主机名必须出现在 dex 里 | 配置注入没生效的话，症状（连错服务端 → 同步不通）要到真机上才暴露 |

另外，`build-server.sh` 里 `go vet` 显式开启 `pipefail` —— 因为
`go vet ./... | head` 的退出码是 `head` 的，会看到假的 `BUILD_OK`。

---

## 6. 已知边界（不掩盖）

| 边界 | 影响 | 缓解 |
|---|---|---|
| 本机可能没有 Go | 无法本地构建/验证服务端 | `scripts/fetch-go.sh` 自备；`.e2e` 与 `dist` 会自动发现它；`--skip-server` 也能只出 APK |
| 本地 e2e 不覆盖真机 | 无障碍授权、厂商 ROM 保活、手表/鸿蒙形态、真机安装均未覆盖 | 沿用仓库 `tests/` 下既有真机流程（`plan-*.sh`、`watch_task.py`、`harmony_*.sh`） |
| 额度网关未端到端验证上游计费 | e2e 里 `-upstream-key` 为空（网关关闭，返回 503 属预期） | 服务端 README §8 描述了用 mock 上游做计量验证的方法；接真实 DashScope Key 才计费 |
| 服务端数据无淘汰 | 长期运行需人工清理 JSONL 与流水 | 服务端 README §7 已列为已知边界 |
| 无 API 版本化 | 两端必须同发布；破坏性变更不能单边上线 | `docs/CONTRACT.zh-CN.md` §7 给出加性/破坏性判定规则 |
| 客户端 `quota_exhausted`(402) 无专门文案 | 用户看到的是额度数字下降而非明确提示 | 属客户端体验缺口，服务端不改即可单独补（加性改动） |

---

## 7. 上游改了怎么办（标准流程）

```bash
cd agent-unified

bash scripts/sync-sources.sh          # ① 把两个原项目的最新状态同步进来（覆盖副本）
python3 scripts/verify-contract.py    # ② 契约是否仍然咬合？不一致会指出具体哪一组
bash scripts/build-all.sh             # ③ 门禁通过后出全部产物
bash scripts/e2e-local.sh             # ④ 真的跑一遍（服务端 + 客户端 pin）
bash scripts/selftest-verifier.sh     # ⑤ 顺手确认门禁本身还灵

# 需要上线时
bash deploy/deploy-server.sh          # 默认只打印计划
bash deploy/deploy-server.sh --yes    # 确认后执行
```

若上游改完了 `agent-lite/` 或 `agentlite-server/` 里的文件，整合层的副本会被覆盖——
这正是想要的：**整合层不持有任何一端的私有改动**，所有改动都留在原项目里。
