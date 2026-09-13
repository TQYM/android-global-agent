# 项目清单与分离约定（Project Registry & Separation）

> **这份文件解决一个问题**：本工作区里同时跑着**多个互不相干的子项目**，还有**多个并发会话**在同一个
> `PROJECT_OVERVIEW.zh-CN.md` 的「工作日志」里追加记录。没有统一的项目标识时，日志会混成一锅粥——
> 看不出某一行属于哪个项目，接手者也不知道该读哪份手册。
>
> **硬约定**：**每个会话在动手前必须先声明自己属于哪个项目**（见 §3），项目 ID 只能取 §1 表中的规范名。
>
> 维护：新增/拆分/改名子项目时，**必须同步更新本文件**；`docs/DEVELOPMENT_MANUAL.zh-CN.md`
> 只管服务器（`agentlite-server`），不要把它当成全局手册。

---

## 1. 项目清单（规范 ID）

**项目 ID = 目录名**（`agentd` 与 `ios` 除外，理由见表内），全小写、无空格，可直接用于 grep。

| 项目 ID | 目录 | 是什么 | 主要产物 / 包名 | **先读这份文档** |
|---|---|---|---|---|
| `agent-client` | `agent-client/` | 完整原生 Android 客户端（纯 Java、无 Gradle、零 root） | `agent-client/build/agent-client.apk`，包名 `com.dsh.agent` | `agent-client/README.md` |
| `agent-lite` | `agent-lite/` | 轻量 Android 客户端（无障碍执行 + 账号/同步/内购） | APK，包名 `com.dsh.agentlite` | `agent-lite/README.md`、`COMPATIBILITY.zh-CN.md` |
| `ios` | `ios/` | iOS 客户端（Swift Package：`AgentLiteCore` + CLI；`App/` 下另有 Xcode 工程与 SwiftUI 界面；账号 + 远程控制） | `swift run agentlite-ios`；`ios/App/AgentLiteIOS.xcodeproj` | **`docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md`**、`ios/README.md` |
| `agentlite-server` | `agentlite-server/` | Go 云端服务（账号 / 同步 / 额度网关 / 订单） | `/usr/local/bin/agentlite-server`，线上 `8.133.225.105:18443` | **`docs/DEVELOPMENT_MANUAL.zh-CN.md`**、`agentlite-server/README.md` |
| `agent-unified` | `agent-unified/` | 整合/发布层：把 client+server 合成一键构建/校验/发布/部署 | 发布 tar.gz，契约门禁 | `agent-unified/README.md`、`docs/CONTRACT.zh-CN.md` |
| `agentd` | `agentd-go/` + `agentd-apk/` | 设备端**常驻**守护（Go 单二进制 + APK 壳，内置 WebUI） | 包名 `com.dsh.agentd` | `agentd-go/README.md` |
| `harmony-agent` | `tools/harmony-agent/` | HarmonyOS NEXT 的 HDC 外挂式 Agent（原型） | — | `tools/harmony-agent/README.md` |
| `repo` | `docs/`、`tests/`、`skills/`、根文档 | **跨项目共享资产**：文档、测试、规范、Skill、根 README/CHANGELOG | — | `PROJECT_OVERVIEW.zh-CN.md` |
| `dsh-env` | **仓库外**（`~/.dsh`、本机环境） | DSH / Hindsight / 工具链与本机环境维护 | — | `~/.dsh/local-patches/` |

**为什么 `agentd` 是一个项目而不是两个**：`agentd-go/`（守护进程源码）与 `agentd-apk/`（APK 外壳）
是同一个设备端常驻程序的两半，必须同版本演进，拆开标注意义不大。

**为什么有 `repo`**：改 `docs/`、`tests/`、`skills/` 或根 README 时，你既不属于任何单一客户端，
也不属于服务端——标 `repo`，避免被误当成某个客户端会话的产物。

**为什么有 `dsh-env`**：本机工具链（DSH 插件、Hindsight、PATH 补丁等）不在本仓库里，
但历史上确实有会话在这里留过日志。给它一个 ID，才不会和仓库内项目混淆。

---

## 2. 分离规则（改哪里 = 属于哪个项目）

1. **按目录归属**：你改动的文件在哪个子项目目录下，就属于哪个项目。
2. **共享资产归 `repo`**：`docs/`、`tests/`、`skills/`、根目录的 `README*.md`、`CHANGELOG.md`、
   `PROJECT_OVERVIEW.zh-CN.md` 一律标 `repo`。
3. **一个会话默认只属于一个项目**；确实跨项目时（例如「服务端加路由 + 客户端调用」），
   在会话头写主项目，并在任务主题里点明协作方，如 `项目:agentlite-server +agent-lite`。
4. **`agent-unified` 是镜像层，不是源码真相源**：`agent-unified/client/` 与 `agent-unified/server/`
   是 `agent-lite` / `agentlite-server` 的**逐字节副本**（`scripts/sync-sources.sh --check` 构建期复验，
   见 CHANGELOG「零改动保证」）。**改源码要改原项目，再跑 `sync-sources.sh` 同步**；
   直接改镜像副本会被门禁拦下，也会在下次同步时被覆盖。
5. **手册按项目路由，别串门**：改 iOS 就读 iOS 手册，改服务端就读开发手册。
   两份手册各自描述自己那侧，**互相矛盾的表述以源码与线上实测为准**。

---

## 3. 会话标注约定（本文件的核心）

### 3.1 会话开始前必须声明项目

会话内**首次动手改文件之前**，用规范 ID 声明项目。落两处：

- **工作日志的会话头**（强制，见 §3.2）；
- **对话里第一句**（建议）：一句话说明「本项目：`<id>`」，让并发会话的人类读者一眼看清。

### 3.2 会话头格式（在 `PROJECT_OVERVIEW.zh-CN.md` 的 `## 工作日志` 节内）

在 `skills/project-work-log` 既有格式上**增加 `项目:` 字段**，其余不变：

```markdown
### 2026-09-13 12:40 +08 · 项目:ios · 会话:abc12345 · 实现 App Intents 动作集
- 12:40 首次运行检查：读工作日志尾 40 行恢复上下文（源:文件:PROJECT_OVERVIEW.zh-CN.md:565）
- 12:44 注册 App Intents 动作集骨架（源:文件:ios/Sources/AgentLiteCore/Intents.swift）
- 12:44 终态: done
```

字段顺序固定为：**日期 时间 时区 · `项目:<id>` · `会话:<前8位>` · ≤20 字任务主题**。

- `项目:` 取 §1 表内的规范 ID，**禁止自造**；跨项目写 `项目:<主> +<次>`。
- 会话标识前 8 位取不到时写 `会话:local`（与旧格式一致）。
- **2026-09-13 12:00 之前的历史会话块没有 `项目:` 字段**，属正常——不要回填、不要改写，
  按 §1 的目录归属即可推断。

### 3.3 为什么放在工作日志里

因为**工作日志是所有会话都必然读的那一段**（`project-work-log` skill 要求「动手前先读日志尾」）。
把项目标识挂在会话头上，等于让每个后来者**在恢复上下文的同时就知道每条记录属于谁**——
不需要额外的注册流程，也不需要任何人记得去查这份文件。

---

## 4. 「我该标哪个项目？」判定表

| 我要做的事 | 项目 ID |
|---|---|
| 改 Android 完整客户端的 Java/布局 | `agent-client` |
| 改轻量客户端（登录/同步/内购/无障碍执行） | `agent-lite` |
| 改 Swift / iOS 能力 | `ios` |
| 改服务端路由 / 数据 / 部署到 `8.133.225.105` | `agentlite-server` |
| 跑契约门禁 / 打包发布 / 部署脚本 | `agent-unified` |
| 改设备端常驻守护 | `agentd` |
| 改 HarmonyOS 原型 | `harmony-agent` |
| 写文档 / 加测试 / 改规范与 Skill | `repo` |
| 修 DSH、Hindsight、本机 PATH 等环境问题 | `dsh-env` |

**拿不准时**：看你要动的**第一个文件**在哪。仍然拿不准就标 `repo`，并在任务主题里写清涉及范围。

---

## 5. 与服务器手册的分工（别搞混）

| 想知道 | 看哪份 |
|---|---|
| 服务器怎么连、怎么部署、有哪些接口 | `docs/DEVELOPMENT_MANUAL.zh-CN.md`（属 `agentlite-server`） |
| iOS 端能做什么、服务端契约、已知坑 | `docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md`（属 `ios`） |
| 全局架构、技术决策、工作日志、体检报告 | `PROJECT_OVERVIEW.zh-CN.md`（属 `repo`） |
| 客户端交互纪律（感知→决策→执行→复验） | `docs/INTERACTION_LOGIC_SPEC.zh-CN.md` + `skills/software-interaction-logic/`（属 `repo`） |
| **另一套服务器**（LLM 中转站 `23.185.168.190`） | `_local/SERVER_TECHNICAL_HANDBOOK.zh-CN.md`（本机、不入 git） |

> ⚠️ 本仓库涉及**两套服务器**：项目自己的 `8.133.225.105`（`agentlite-server`）
> 与模型中转站 `23.185.168.190`（与仓库项目无关）。**先分清是哪一套再动手**，
> 详见开发手册 §8。
