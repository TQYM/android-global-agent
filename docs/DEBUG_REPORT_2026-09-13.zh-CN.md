# 项目 Debug 报告（2026-09-13 · 会话 11:27 起）

> **范围**：全仓门禁/构建实测 + `agent-client` 定向 debug 与修复 + 真机核验。
> **方法**：不采信文档结论，全部本机复跑；代码改动以「dex 符号核验 + 设备端 sha256」自证，不以「编译通过」下结论。
> **与体检报告的关系**：`docs/PROJECT_REVIEW_2026-09-13.zh-CN.md` 是 11:16 的静态快照，**其中 P0 已有 3 项被并行会话修掉，该报告在 P0 章节已过期**（详见 §4）。

---

## 1. 门禁与构建矩阵（实测）

| 项 | 起手状态 | 处理后 |
|---|---|---|
| `agent-unified/scripts/verify-contract.py` | ❌ **25 通过 / 1 失败** | ✅ **26 / 0 / 0** |
| agent-client APK | ✅ 66458 B | ✅ 66458 B（改动后重建，符号已核验） |
| agent-lite APK | ⚠️ 构建被环境拦下 | ✅ 199306 B |
| agentd-apk APK | ⚠️ 构建被环境拦下 | ✅ 16857 B |
| agentlite-server `go vet` + `go build` | ✅ | ✅ 零告警 |
| agentd-go `go vet` + `go build` | ✅（已回 `go 1.24`） | ✅ 零告警 |
| agent-unified/server `go vet` | ✅ | ✅ 零告警 |
| `ui_text_guard.sh` | ✅ 7 + 10 全过 | ✅ 未回归 |
| `ui_widget_guard.py` | ✅ 0 失败 | ✅ 未回归 |
| `apk_readability.py` | ✅ | ✅「无硬违规」，对比度不达标 0，dex 无明文密钥 |
| `AliyunV3SignerTest` | 47/47 | ✅ 47/47 |
| `PurchaseFlowTest` | 20/20 | ✅ 20/20 |

### 1.1 唯一的门禁红点及其修复

**现象**：`verify-contract.py` 报「【零改动】FAIL — 副本与原项目存在差异」。
**根因**：`agent-unified/client/` 是 `agent-lite/` 的逐字节副本，而上游已前进 17 个文件未同步（内购四件套 `AkStore`/`AliyunV3Signer`/`BailianClient`/`PurchaseFlow` + `AkStore` 调用面 + M3 回退的 UI 资源）。
**修复**：`bash agent-unified/scripts/sync-sources.sh`（已通读脚本确认是**纯 rsync 复制，无改写/无补丁**）。
**验证**：门禁复核 26/26 全绿；`sources.lock.json` 已记录来源 HEAD 与上游未提交条目数。

---

## 2. 已修复缺陷（agent-client）

### 2.1 P0-4 · 应用上下文依赖 Activity → 日志静默丢失 + Activity 泄漏

- **位置**：`AgentEngine.java:66`（staticLog）、`MainActivity.java:31/32/54`。
- **根因**：`staticLog()` 走 `MainActivity.appStatic()` = `sInstance.getApplication()`。`sInstance` 是**静态持有 Activity**，且全文件无 `onDestroy` → 视图树随进程常驻泄漏；而 `sInstance` 为 null 时 `getApplication()` 直接 NPE，被 `catch (Exception ignored) { }` 吞掉 → **日志丢了却查不出为什么**。
- **修复**：
  - 删除 `sInstance` / `appStatic()`；
  - `AgentEngine` 自持 `sAppCtx`（**只存 Application，绝不存 Activity**），新增幂等 `cacheAppContext()`，由 `get(Context)` 注入；
  - `staticLog()` 无 ctx 时 `Log.w` 留痕，不再静默；
  - `MainActivity` 首次获得 `onDestroy()`：`engine.setListener(null)`（引擎是进程级单例，原先一直持有 Activity）+ 录音中则停录。
- **为什么不丢日志**：落盘能力已搬到引擎的 `sAppCtx`，与界面生命周期完全解耦。

### 2.2 P0-3（后半）· 任务结束后剪贴板残留

- **位置**：`AgentEngine.injectViaClipboard()`（原 `setPrimaryClip` 后无任何清理，全仓 `clearPrimaryClip` 0 命中）。
- **风险**：注入路径会把**密码、验证码**写进系统剪贴板，任务结束后任何应用可读。
- **修复**：新增 `clearClipboard()`，挂到 `runLoop` 的 `finally`（与 `restoreIme()` 同处）。
- **★ 实现时自查推翻的第一版**：初版无条件 `clearPrimaryClip()` 会**误删用户自己复制的内容**——那是数据损失。已加 `clipboardDirty` 标志，**只有本引擎写过**才清；且用 `clearPrimaryClip()` 而非写空串（后者会在剪贴板历史留一条空记录）。

### 2.3 P1-7 · `KeepAliveService.updateState()` 是死代码

- **根因**：全仓仅 1 处定义、**0 处调用** → 状态栏圆点永远停在绿色「待命」，与"任务运行中"不符（该功能的注释却明写红点=运行中）。
- **修复**：`AgentEngine.start()` 推 `updateState(app, true)`，线程 `finally` 推 `updateState(app, false)`。

### 2.4 P3-1 · 跨任务状态残留污染下一个任务

- **根因**：`clipPending` / `rootPasteDone` 是跨步状态，原先只在 `runLoop` 内部按需消费，**不在任务开始时重置**。
- **具体危害**：上一轮任务在 `clipPending=true` 时结束 → 新任务**第一步**就收到「目标文字已写入剪贴板并已长按输入框，请点粘贴」的**假提示**（此时根本没写过）。`rootPasteDone` 残留则会让新任务里相同文本跳过 `keyevent 279` 直连粘贴。
- **修复**：`runLoop` 初始化段补 `clipPending = false; rootPasteDone = null;`。

---

## 3. 验证（不以"编译通过"收尾）

**构建产物自证**（本项目文档明确警告「ZIP 大小会巧合重复，不可信」——本轮**再次复现该陷阱**）：
- 改动前后 agent-client APK **字节数完全相同（66458）**，字面看像"没重新构建"。按项目自己的方法核验 dex：`appStatic` **已消失（0）**，`cacheAppContext`/`clipboardDirty`/`clearClipboard` **均在（1）** → 确认为新构建。
- 这同时说明「构建产物无自证」这条 P2 不是纸面问题：**同一字节数对应两份不同源码**，只靠体积无法判断产物新旧。

**真机核验**（OnePlus PKX110 / ColorOS 16，本轮设备已在线）：
- 设备端 `base.apk` sha256 与源 APK **逐字节一致**（`4669b0a4…dce c34`）；
- 冷启 → 界面正常（`空闲` / `无障碍 ✗（点「设置」开启）` / `沙盒:关` / `运行`）；
- 返回键触发 `onDestroy` → 零 FATAL；二次进入 → 零 FATAL，界面重绘正常；
- 无障碍绑定状态与安装前一致（未被本次安装清掉）。

**未验证项（诚实声明）**：剪贴板清理、保活红点、跨任务状态重置这三条**需要跑真实任务**才能端到端确认，而跑任务依赖有效 API Key（DashScope 账户此前欠费/Key 待轮换）→ **本次仅做到代码级与构建级验证**，不做"已修复完成"的过度声明。

---

## 4. 纠正体检报告的过期结论（避免后续会话白做）

`docs/PROJECT_REVIEW_2026-09-13.zh-CN.md` §2 P0 四项中，**三项已被并行会话修掉但报告未更新**：

| 体检结论 | 当前实况（本轮复核） |
|---|---|
| P0-1 root 命令注入（`SandboxController.java:193/212` 直接拼 `su -c`） | ✅ **已修**：新增 `ActionValidator`（57 行），`launchApp`/`launchAction`/`launchUrl`/`reclaimTask` 四处入口全部白名单化（`pkg` / `action` / `url`） |
| P0-2 `ensureA11y()` 整体覆盖无障碍列表（`RootShell.java:117`） | ✅ **已修**：先 `settings get` 读当前值、已含则不动、否则冒号追加，用户已有服务完整保留 |
| P0-4 agentd-go `go.mod` 要 1.27 导致本机不可构建 | ✅ **已修**：已回 `go 1.24`，本轮 `vet` + `build` 零告警 |
| P0-3 剪贴板 NPE + 残留 | ⚠️ **NPE 已修**（`unsafeCheckOpNoThrow` 已移入 try + `aom != null` 判空）；**残留本轮才修**（见 §2.2）；P0-4 的 Activity 泄漏本轮才修（见 §2.1） |

---

## 5. 本轮发现但**未**处理（供决策，不擅自改动）

1. **【P0 · 仍是最大单点风险】核心工程未入版本控制**：修复后 `git status` 仍 **76 项**（`agentlite-server/`、`skills/project-work-log/`、4 份 docs、tests 半数未跟踪）。服务端源码唯一异地副本在线上 `/srv/`，本机磁盘故障即永久丢失。**建议分批提交**（源码/文档已审计无密钥）；`竞赛材料/` 归属待用户确认。
2. **agentd-go WebUI 与 a11y 桥无鉴权却以 root 运行**（`/api/config` 含 Key、`/api/screen` 可窃屏、8081 桥）。Android loopback 不是安全边界，设备上任一 App 可连。建议启动时 `crypto/rand` 生成 token 存 0600 文件并全量校验。
3. **agent-lite 账号令牌明文存 SharedPreferences**——`AkStore` 的 Keystore 模式已在仓内，迁移成本低。
4. **DashScope Key 待轮换**（用户动作）：曾随 APK 发布件与对话记录存在过，清文件 ≠ 吊销。
5. **`agent-unified/client` 与 `agent-lite` 谁是真身未定**——内购改造只动了 `agent-lite`，不先定归属会改一半。

---

## 6. 环境坑（会重复踩，已固化）

- 自备 Go 工具链在 `~/.agent-unified-go/go/bin/go`（go1.24.6），**不在 PATH**，需手动前置。
- `build-mac.sh` 的 `rm -rf build/` 会撞批量删除确认（build 目录 >50 文件）；**且 `| tail` 会吃掉退出码让 `&&` 继续执行**，看起来像"跑过了"——本轮首次构建即被此误导。判构建成败要看 `DONE: … bytes` 行，不要看退出码。
- 设备侧 `adb shell sha256sum` 是验证装机一致性的可靠手段（比 ZIP 体积可靠）。
