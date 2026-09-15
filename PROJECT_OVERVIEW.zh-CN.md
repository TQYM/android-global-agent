# Android Global Agent — 项目全景文档

> 本文档供后续 AI 接手时快速了解项目现状、技术决策与已知边界。
> 最后更新：2026-09-13（新增 §14 iOS 端「账号+远程控制」与 §13 全面体检结论；正文 §1–§12 仍以 2026-09-06 为基准，刷新列入待办）

> 🧭 **多项目工作区——先认领项目再动手**：本工作区同时存在多个互不相干的子项目
> （`agent-client` / `agent-lite` / `ios` / `agentlite-server` / `agent-unified` / `agentd` /
> `harmony-agent` / `repo`，另有仓库外的 `dsh-env`）。
> **项目清单、目录归属与会话标注约定见 [`docs/PROJECTS.zh-CN.md`](docs/PROJECTS.zh-CN.md)**；
> 每个会话动手前必须先声明所属项目，并在下方「工作日志」的会话头写 `项目:<id>`。

---

## 1. 项目目标

在 Android 设备上运行一个**视觉+文本驱动的自动化 Agent**，能够：
- 看懂手机屏幕截图（视觉理解）
- 通过无障碍服务（AccessibilityService）或 root shell 操控手机
- 支持中文语音输入、中文文本注入
- **可选 root 加速**（截图、手势、粘贴更快更稳），但**零 root 也能跑**
- **沙盒模式**：把目标 App 关进独立虚拟屏，用户真屏完全不受影响（root 限定）

客户端包名：`com.dsh.agent`，GitHub 仓库 `TQYM/android-global-agent.git`，分支 `shell-command-backend`。

---

## 2. 设备环境

### 主力机：OnePlus 13T（PKX110）
- Android 16 / ColorOS 16，KernelSU root
- 分辨率 1216×2640，密度 560
- 序列号 `3B15B401NPF00000`
- **沙盒模式在此设备上开发验证**

### 新平板：荣耀 YLP-W00（MagicOS，hihonor 系包名）
- Android 17（API 37），**无 root**
- 分辨率 2136×3200（横屏 3200×2136），密度 420
- 序列号 `AAQLBB5C03000706`
- **零 root 全真屏链路已实测通过**（2026-09-06）：节点采集 ✓ / takeScreenshot 3200×2136 ✓ / dispatchGesture ✓ / ACTION_SET_TEXT 中文 ✓ / 默认输入法百度输入法荣耀版
- **荣耀特性坑**：App 被 `am force-stop` 或覆盖安装后，`enabled_accessibility_services` 里本 App 条目会被系统清空 → 需重新 `settings put` 开启（无需 force-stop，直接启动即可绑定）
- 默认模型配置已改为 DashScope `qwen3.5-omni-plus`（新装机零配置开箱即用，API Key 仍需手填）

---

## 3. 核心架构

```
┌─────────────────────────────────────────┐
│  MainActivity（UI + 设置 + 语音按钮）      │
├─────────────────────────────────────────┤
│  AgentEngine（循环：感知 → 决策 → 执行）    │
├─────────────────────────────────────────┤
│  LlmClient（OpenAI 兼容 HTTP 客户端）      │
├─────────────────────────────────────────┤
│  AgentA11yService（AccessibilityService） │
│    ├─ 节点采集（getWindowsOnAllDisplays） │
│    ├─ 手势注入（dispatchGesture）         │
│    ├─ 截图（takeScreenshot）              │
│    └─ 文本注入（ACTION_SET_TEXT）         │
├─────────────────────────────────────────┤
│  RootShell（可选加速器）                   │
│    ├─ screencap（无限频）                 │
│    ├─ input tap/swipe/keyevent            │
│    ├─ input keyevent 279（直接粘贴）       │
│    ├─ ensureA11y（强制开启无障碍）          │
│    └─ 截图/手势/粘贴同步执行               │
├─────────────────────────────────────────┤
│  SandboxController（沙盒虚拟屏）            │
│    ├─ root 守护进程建 TRUSTED VD           │
│    ├─ am start --display（起 App）         │
│    ├─ screencap -d（读帧）                │
│    └─ input -d（注入触控）                 │
├─────────────────────────────────────────┤
│  FlorisBoard 合并键盘（中文拼音输入）        │
│    └─ 广播接收 com.dsh.agent.IME_COMMIT   │
└─────────────────────────────────────────┘
```

---

## 4. 构建方式

纯命令行，**无 Gradle、无 Kotlin、零第三方依赖**。

```bash
cd agent-client/
sh build-mac.sh
# 输出 agent-client/build/agent-client.apk
```

工具链：aapt2 → javac --release 11 → d8 → zipalign → apksigner（debug key）。

**关键验证**：修改后必须用 `grep -ac` 在提取的 dex 上搜中文标记，确认 dex 已更新（ZIP 大小会巧合重复，不可信）。

---

## 5. 模型配置（设备端 SharedPreferences）

| 键 | 值 | 说明 |
|---|---|---|
| `base_url` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | DashScope 兼容端点 |
| `api_key` | *(用户私钥，设备存储，永不入 git)* | DashScope |
| `model` | `qwen3.5-omni-plus` | 主模型（文本+视觉+音频） |
| `vision_model` | *(空)* | 空表示主模型自带视觉 |
| `asr_model` | *(空)* | 空表示用主模型原生音频输入转写 |
| `root_mode` | `on` / `auto` / `off` | 用户可选 |
| `sandbox` | `true` / `false` | 沙盒开关 |
| `vision` | `true` | 视觉感知开启 |

**ASR 重建历史**：早期用 `glm-asr-2512`（智谱），换 DashScope 后失效。现改用 `qwen3.5-omni-plus` 的 `input_audio` 原生音频输入，`data` 字段必须是 **data-URI**（`data:;base64,...`），裸 base64 会被 400 拒绝。

---

## 6. 零 root 能力矩阵（Universal Android 11+）

| 能力 | 实现方式 | 备注 |
|---|---|---|
| 节点采集 | `getWindowsOnAllDisplays()` + `getRoot()` | 微信屏蔽节点树 → 自动切视觉模式 |
| 截图 | `takeScreenshot()` | ColorOS ~1.1s 限频，连续黑帧时切节点模式 |
| 手势 | `dispatchGesture()` | 异步，偶被取消；root 下用 `input` 同步替代 |
| 文本注入 | `ACTION_SET_TEXT` | CJK 支持好；失败时降级广播/剪贴板 |
| 剪贴板 | `ClipboardManager` | ColorOS 曾禁 WRITE_CLIPBOARD → 需 `appops allow` |
| 全局动作 | `performGlobalAction()` | 返回、主页、最近任务 |
| 设置直达 | `Settings.ACTION_*` | 亮度、WiFi 面板等 |
| 语音输入 | `AudioRecord` 16k WAV | 现走 Omni 音频输入 |
| 包名解析 | `getLaunchIntentForPackage()` | 模型给中文名时查 `APP_NAMES` 表 |

---

## 7. 沙盒模式（Root 限定）

### 为什么必须 root（三重实测证据）
1. Android 10+ 起 App 创建公有虚拟屏被拒（需 ADD_MIRROR_DISPLAY/CAPTURE_VIDEO_OUTPUT 或 MediaProjection token），OWN_CONTENT_ONLY 屏只能显示自己内容；
2. ColorOS（实测 ColorOS 16）会把**任何**普通/MediaProjection 虚拟屏的任务投射到物理屏（`canHostTasks=false`）；
3. 荣耀 MagicOS（YLP-W00 实测）更严：连 **adb shell** `am start --display <VD>` 都被 `SafeActivityOptions.checkPermissions` 拒绝。

只有 root 进程 + `TRUSTED`(1024) + `OWN_DISPLAY_GROUP`(2048) + `STEAL_TOP_FOCUS_DISABLED`(65536) 才能建出真正隔离的虚拟屏。

### 实现
- **守护进程**：`vd-daemon/VdMain.java` → `assets/vd_daemon.jar`
- 以 `app_process` 在 root 下运行，用 `ActivityThread.systemMain()` 获取系统 Context
- 守护进程每 10s 探活宿主（`pgrep` 失败自动降级扫 `/proc/*/cmdline`），宿主连续 20s 不在才退出
- 守护进程每 10s 检查 `vd.getDisplay().isValid()`，虚拟屏被系统销毁则打印 `VD_LOST` 主动退出（exit 2）
- 沙盒开关 = **纯点按，零弹窗**（删除了 MediaProjection 授权流）

### 看门狗自愈（2026-09-06 新增，已实测）
- `SandboxController.daemonAlive()`：2s 缓存的 `pgrep` 探活
- 引擎每步 `sandboxActive()`：守护死亡 → 限频 10s 自动 `create()` 重建（实测杀守护后 1s 内重建出新 VD）
- **安全红线**：沙盒开关开着但沙盒不可用时，引擎原地等待重试（12 次后中止任务），**绝不回退真屏盲触**；`exec()` 内所有动作走 `requireSandbox()`，拿不到实例直接抛错
- `resolvePoint` 在沙盒模式下改从虚拟屏收集节点（修复编号错位点错位置的 bug）

### 通道
| 操作 | 命令 |
|---|---|
| 建屏 | `app_process ... VdMain W H DPI` |
| 起 App | `am start --display <id> ...` |
| 截图 | `screencap -p -d <SF-id> <file>` |
| 触控 | `input -d <displayId> tap/swipe/keyevent` |

### 已知边界
- ~~单实例应用已在真屏运行时，沙盒无法接管~~ → **已修复（2026-09-06）**：`launchApp` 发现已有任务时改用
  `am display move-stack` 整体迁移进虚拟屏（实测微信从真屏前台迁入后稳定驻留），不再触发真屏弹跳
- **VD 销毁时其内任务会被系统甩到真屏**（守护死亡/重建的瞬间）：App 重启但守护活着时 `create()` 走
  `tryReattach` 只重建控制面不重建屏；守护真死则引擎看门狗重建 + `reclaimTask` 拉回
- 系统弹窗/IME 仍可能出现在默认显示
- 沙盒创建必须后台线程（主线程 su 链会 ANR）
- `pkill -f` 模式不能包含自身命令行 → 用 `"agent_[v]d.jar"` 反匹配（注意：pkill 与 nohup 不能放在同一条
  `su -c` 里，否则 nohup 部分的命令行会命中模式把自己杀掉）

---

## 8. 键盘与文本注入

### 三层降级
1. `ACTION_SET_TEXT`（零 root，最稳）
2. 合并 FlorisBoard 广播 `com.dsh.agent.IME_COMMIT`（自动借为默认 IME，900ms 绑定延迟）
3. 剪贴板 + 长按粘贴（或 root `input keyevent 279` 直接粘贴）

### FlorisBoard 合并
- 源码：`agent-client/floris-merge/`
- 桥接类：`dev.patrickgold.florisboard/.agent.AgentImeBridge`
- 权限：`dev.patrickgold.florisboard.agent.INJECT`（manifest 声明+使用）
- 中文拼音：内置 409 音节/2331 字/962 词字典

---

## 9. 常见坑与修复记录

| 现象 | 根因 | 修复 |
|---|---|---|
| 广播丢失 | IME 重新绑定后 receiver 未注册 | 桥接在 `onCreate` 注册，sleep 900ms |
| 剪贴板静默失败 | ColorOS appop 禁了 WRITE_CLIPBOARD | `appops set com.dsh.agent WRITE_CLIPBOARD allow` |
| dex 更新未生效 | ZIP 大小巧合重复 | 用 `grep -ac` 在 dex 上搜中文标记验证 |
| 沙盒启动异常被吞 | `launchApp` catch 了所有异常 | 改为 throw，引擎显式记录并回退 |
| 沙盒切出/闪退 | VD 抢焦点 + 主线程 ANR | `STEAL_TOP_FOCUS_DISABLED` + 后台线程创建 |
| 语音输入失效 | `glm-asr-2512` 在 DashScope 不存在 | 改用 Omni `input_audio`，data-URI 格式 |
| 模型乱猜包名 | 只有 OEM 别名，缺常用 App | `APP_NAMES` 表 + SCHEMA 内嵌 cheat sheet |
| 每次构建签名都变 | `build-mac.sh` 每次 `rm -rf build/` 连 keystore 一起删，重装必丢配置 | keystore 固化到 `agent-client/debug.keystore`（已入 .gitignore 同级持久位置） |
| KernelSU 授权后 App 仍提示无 root | `RootShell.sAvailable` 静态缓存永久记住 false | 探测缓存改 15s TTL + 沙盒按钮点击时强制重探 |
| 荣耀平板无障碍总是掉 | MagicOS 在 force-stop / 覆盖安装后清空 `enabled_accessibility_services` | 重新 `settings put` 即可；**不要 force-stop**（正常启动不影响） |
| 模型要"打开设置"报未知页 | `SETTINGS_PAGES` 没有通用 `settings` 别名 | 补 `settings → ACTION_SETTINGS` |

---

## 10. 当前待办

- [x] **平板零 root 适配**：YLP-W00 全链路实测通过（2026-09-06）
- [x] **沙盒稳定性**：看门狗自愈已上线并实测（守护死亡 1s 内重建）
- [x] **异形屏适配（代码+模拟器）**：DisplaySpec 采集/注入/clamp/自检已落地并模拟器矩阵验证（2026-09-11，见 `docs/PLAN_DISPLAY_HARMONY_2026-09-11.zh-CN.md`）；**两台真机回归待做**
- [ ] **DashScope 账户欠费**：dev.local 已把 key 编译进 APK（2026-09-06 实测 qwen3.5-omni-plus 正常调用），但 15:02 起返回 HTTP 400 Arrearage——需充值/换 key 后才能跑任务
- [ ] **ASR 质量**：Omni 音频转写对实际中文语音的准确率待用户验证
- [ ] **模型包名 cheat sheet 扩展**：继续补充更多常用 App
- [x] **平板横屏/多窗口适配**：分辨率差异由比例坐标天然解决；横屏/分屏/旋转已由 DisplaySpec 形态注入 + 分屏窗口区域提示覆盖（2026-09-11，待真机回归）
- [ ] **鸿蒙设备适配**：兼容层 2–4.x 验证与 NEXT HDC 外挂原型挂起等真机；NEXT 原生评估已出（`docs/HARMONY_NEXT_EVAL.zh-CN.md`：HDC 外挂式为主路线，原生重写冻结）
- [ ] ~~**HarmonyOS 兼容性**~~ → 实为荣耀 MagicOS（hihonor 系），a11y API 行为与 AOSP 一致，已验证

---

## 10.5 Agent Lite（轻量副本，2026-09-06 创建）

`agent-lite/`：面向**零 root 泛用性**的副本，包名 `com.dsh.agentlite`，与原版可同机共存。
- **删沙盒**：SandboxController/vd-daemon/UI 全摘除；root 仅作静默加速器（无 UI，默认 auto）
- **提速**：截图限频容忍 1.1s→0.9s、手势结果等待 4s→3s、动作后自适应等待 1.6s→1.0s（150ms 轮询）、视觉降采样 640→560px
- **提准**：SCHEMA 明示「节点编号每步重新分配，严禁沿用旧编号」
- **降门槛**：无 root 模式/沙盒开关 UI；无障碍未开时置顶「一键开启」横幅；调试台默认折叠
- **美观**：卡片式深色 UI 重写（pill 状态徽章、圆角卡片、渐变主按钮）
- **签名**：与原版共用 `debug.keystore`（副本见 agent-lite/）——共享 FlorisBoard `INJECT` 自定义权限需要同签名
- 平板实测（2026-09-06）：感知 22 节点 ✓ / 截图 ✓ / 新 UI ✓
- 构建：`cd agent-lite && sh build-mac.sh` → `build/agent-lite.apk`

### 10.5.1 豆包交互规格适配（2026-09-06，YLP-W00 实测通过）

按《豆包手机助手人机交互模式分析与复刻规格说明书》改造，**纯无障碍、零 root**（详见 `docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md`）：
- **代理可见性三信号**：`OverlayController` 用 `TYPE_ACCESSIBILITY_OVERLAY`（免悬浮窗权限）实现灵动岛胶囊（点按展开面板）+ 粉色呼吸边缘光晕（接管即熄灭、留素色边框）+ 接管/授权/终态底部卡片
- **三层授权**：事前「单次/始终/拒绝」按 操作类型×App 持久化（`AuthStore`）；事中胶囊停止/补充常驻；事后 `TaskHistory` JSONL 归档 + 授权撤回 + 生态/记忆管理页
- **敏感强制接管**：`SensitiveGuard` 节点关键词扫描（支付/验证码/协议/权限弹窗）→ 暂停 + 「请手动操作」卡片
- **生态三档清单**：`EcoPolicy`（默认 2025-12-10 实测快照：淘宝/美团/支付宝等完全封锁，微信/高德云可登录不可操作，高德→百度自动替代），任务预检 + app 动作强制检查
- **终态规范**：绿色完成卡片（摘要+产物）；失败统一「任务失败——归因」话术（新增 `fail` 动作）；全部终态归档
- **记忆**：「记一下…」显式写入 `MemoryStore`，系统提示词静默注入（明示不披露），管理页可删可清
- **补充通道**：胶囊面板输入 → 队列 → 下一步注入「用户补充：…」，任务不中断

---

## 11. 关键文件速查

| 文件 | 职责 |
|---|---|
| `src/com/dsh/agent/AgentEngine.java` | 主循环、动作执行、感知、ask 流程 |
| `src/com/dsh/agent/LlmClient.java` | HTTP 客户端、chat/audio/transcribe |
| `src/com/dsh/agent/AgentA11yService.java` | 无障碍服务、节点采集、手势/截图/文本 |
| `src/com/dsh/agent/RootShell.java` | root 加速器（截图、手势、粘贴、a11y 强制开启） |
| `src/com/dsh/agent/SandboxController.java` | 虚拟屏生命周期、守护进程管理 |
| `src/com/dsh/agent/MainActivity.java` | UI、设置、语音按钮、沙盒开关 |
| `vd-daemon/VdMain.java` | root 守护进程源码 |
| `build-mac.sh` | 完整构建脚本（aapt2 + javac + d8 + zipalign + apksigner） |
| `COMPATIBILITY.zh-CN.md` | 各 ROM 兼容性矩阵与权限授予指南 |
| `docs/DEVELOPMENT_MANUAL.zh-CN.md` | **开发手册**：项目专用服务器（`8.133.225.105`）的连接方式、部署与运维、接口清单 |

---

## 12. 安全红线

- `api_key` **永不入 git**，仅存在设备 SharedPreferences
- 用户活跃使用手机时（聊天、支付、浏览），**禁止任何盲触输入**
- root 是可选加速器，**永远不是零 root 路径的依赖**

---

## 13. 全面体检结论与改进路线（2026-09-13）

> 完整证据与逐条修复建议见 `docs/PROJECT_REVIEW_2026-09-13.zh-CN.md`（含 file:line 级根因）。
> 体检窗口 00:42–11:16，期间并行会话完成内购三件套、M3 回退、发布面泄露修复——重叠项已按最终状态复核。

### 13.1 必须立即处理（P0）

> ✅ **修复进展（2026-09-13 11:29，全部完成）**：P0-2/P0-3（注入/无障碍）→ `69ddf0b`，ActionValidator 白名单
> 网关 + 追加式 ensureA11y，dex 字符串常量验证进包；P0-4（构建）→ `cf04f6c`，go.mod 1.24 编译+vet 通过；
> P0-1（入库）→ `bd6dbb0`/`0cc7094`/`90c2ffb` 服务端/内购/文档规范分批入库；契约门禁 → `2bc7683`
> sync-sources 后 26/26 绿。工作区仅剩 apply.sh/rollback.sh（DSH 宿主补丁，刻意不入库）。
> P0-3 的「恢复按钮」未做（追加式修复后不再有可破坏面，降为可选）。

1. **核心工程未入版本控制**：`agentlite-server/`、`agent-unified/`、`skills/`、手册、测试大半未跟踪，另有 53 个文件未提交。服务端源码唯一异地副本在线上 `/srv/`——本机磁盘故障即永久丢失。**分批提交，已审计无密钥**（`竞赛材料/` 待确认）。
2. **agent-client root 命令注入**：模型输出（包名/URL）直接拼 `su -c`（`SandboxController.java:193/212`），提示注入可让任意命令以 root 执行。入口白名单即可修复。
3. **agent-client 无障碍列表整体覆盖**：`RootShell.ensureA11y()` 用 `settings put` 覆盖而非追加（`RootShell.java:117`），用户已有无障碍服务被静默关闭。先读后追加 + 恢复按钮。
4. **agentd-go 本机不可构建**：`go.mod` 写 `go 1.27` 但未用新 API，自备工具链 1.24.6 + 代理不可达 = 构建死锁。改回 `go 1.24`（已实测通过）。

### 13.2 本迭代内（P1）

- **轮换 DashScope Key**（用户动作）：曾随 APK 进入发布件与对话记录，清文件≠吊销。
- agent-client 五项：停止按钮无法中断（HTTP 无 cancel 通道，挂起 120s）；`MainActivity.sInstance` 泄漏 + 日志静默丢失；ask 弹窗与手势回调死锁链；`KeepAliveService.updateState()` 死代码；`RootShell.available()` 主线程阻塞。
- agentd-go：WebUI（`/api/config` 含 Key、`/api/screen` 窃屏、`/api/task`）与 a11y 桥 8081 **全部无鉴权且以 root 运行**——Android loopback 不是安全边界，设备上任一 App 可连。
- agent-unified：~~构建前先 `sync-sources.sh`（契约门禁当前红：内购文件未同步）~~ ✅ 已修复（`2bc7683`：sync 后 26/26 绿）；**定夺 `agent-unified/client` 与 `agent-lite` 谁是真身**——本轮已按整合层自身设计（agent-lite 为真身、副本零改动）执行同步，后续以此为准。
- 服务端部署后补手册 §7（本地代码已新增 5 条订单路由）。
- 本文档正文 §1–§12 刷新至 09-13 现状（未收录：服务端、整合工程、agentd、账号同步、内购、保活、手表）。
- agent-lite 账号令牌明文 → 复用 `AkStore` 的 Keystore 模式。

### 13.3 中期

agent-client P2 批量修复（LlmClient 重试/finally、ActionParser 括号配平、IME 借还补偿、沙盒迁移分屏判定、base_url 强制 https、构建产物 sha256 自证）；抽 `protocol/` 共享包统一两客户端协议层；Go 服务端补表驱动单测 + `make check` 免设备聚合检查；agentd 定位决策（整合 or 废弃）；测试序列号与 SYNC_URL 硬编码收敛。

### 13.4 长期方向

执行通道抽象（Sandbox/Root/A11y 三 Executor）+ 能力网关 `ActionValidator`（让注入在架构上不可表达）；统一 Diagnostics 可观测性（每步感知耗时/遍历次数/LLM 耗时/通道）；服务端记录流索引化；`make check` 进 CI。

### 13.5 体检中的正面结论（保持别退步）

服务端 Go 基本面扎实（四项超时/MaxBytesReader/恒时比较/PBKDF2 210000/tmp+rename）；agent-lite 密码学正确（TlsPin fail-closed、LanSync 每报文新 nonce+抗重放窗口）；`AkStore`/`AliyunV3Signer` 是凭据处理的教科书实现；构建 fail-closed 防密钥入包；门禁体系（契约/文案/控件/可读性/JVM 回归）多层且真实抓过 bug；`docs/HANDOFF` 交接文档质量高，建议固化为标准做法。

---

## 14. iOS 端（2026-09-13 新增）：账号 + 远程控制

**结论**：iOS 端按用户授权预案落档为**裁剪版** —— 保留账号 / 信息同步 / 远程控制 / 局域网加密互通，
**冻结设备端自动化执行**（iOS 不向三方 App 开放系统级无障碍与手势注入，成因与鸿蒙不同但结果相同）。

| 项 | 值 |
|---|---|
| 实现位置 | `ios/`（Swift Package，核心库不依赖 UIKit → 可在 macOS 上全量验证） |
| iOS App | `ios/App/`（Xcode 工程，脚本生成 `project.pbxproj`；SwiftUI 四屏 + 后台刷新 + 本地动作 + Keychain + 证书 pin） |
| 实现手册 | **`docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md`**（能力边界证据链 / 服务端实测契约 / 后台模型 / 安全红线 / 已知坑） |
| 验证命令 | `bash ios/build-mac.sh [--e2e]` |
| 测试基线 | **42 项全绿** = 13 密码学交叉验证（对 Java 逐字节）+ 19 协议/红线 + **10 真连服务端端到端** |
| 平台编译 | macOS + `arm64-apple-ios16.0`（iPhoneOS SDK）双通过 |

### 14.1 本次实测发现的服务端契约偏差（★ 写代码前必看）

三处「文档措辞与 Go 实现不符」，iOS 侧据此纠正，**Android 侧与文档同样需要复核**：

| # | 文档/直觉写法 | 实际字段（权威：Go 源码） | 位置 |
|---|---|---|---|
| C1 | `lan_key_params.key_length` | **`key_len`**（另有 `algorithm`） | `account.go:72-77` |
| C2 | push 响应 `next_seq` | **`server_seq`**，响应另含 `duplicates` | `api.go:349-355` |
| C3 | pull 响应 `next_seq` | **`server_seq`** | `api.go:370-376` |

另两条**行为**（非字段）事实，两端都应按此修订预期：

- **指令 ack 只接受 `running` / `done` / `failed`**，**没有 `cancelled`**（`command.go:166-171`）。
  「拒绝执行」必须以 `failed` + 理由回报，否则 ack 被 400 拒掉、指令状态原地不动。
- **终态 ack 后服务端删除该指令**（`command.go:195`），之后 `GET /device/command?id=` 一律 404。
  ⇒ 结果只能取 ack 的返回值，不能"回头再查"。
- **先 ack `running` 再执行是不可恢复错误**：`PendingFor` 只返回 `pending`（`command.go:149`），
  一旦标了 running 就不再被领取，执行途中被杀进程则该指令永久卡死。正确顺序是**先执行、后 ack 终态**。
- ★ **下发方无法确认已完成的指令**（本次实测）：目标设备回报终态 → 服务端删除 → 下发方再查即 404，
  拿不到结果文本。即「iPhone 下发 → Android 执行完 → iPhone 看结果」这条链路**当前是断的**。
  闭环需目标设备在 ack 之外另发一条 `/sync/push` 记录（服务端尚未提供该回执通道，见手册 §9 U3）。

### 14.2 iOS 工具链实况（本机 2026-09-13）

| 项 | 实测 |
|---|---|
| Xcode | 26.6（随附 SDK **iOS 26.5**） |
| 模拟器运行时 | **仅 iOS 26.4** → 低于 SDK，`xcodebuild` 判定设备不合格 |
| 签名身份 | **0 个**（`security find-identity -p codesigning` 为空） |
| 真机 | 未连接（`devicectl list devices` 无设备） |

**当前唯一阻塞**：`xcodebuild -showdestinations` 只给 generic device，报 `iOS 26.5 is not installed`；
CoreSimulator 日志 `Unable to discover any Simulator runtimes`。
注意 `simctl` 能正常 boot 设备 —— **两个工具口径不同，别被 simctl 骗了**。
修复：`Xcode → Settings → Components` 装 iOS 26.5 运行时（命令行 `xcodebuild -downloadPlatform iOS`
在本机跑到 5 分钟仍 0% CPU、无网络、缓存无增长，已中止）。
App 源码已对 **iOS 模拟器 SDK 类型检查通过**，工程与测试就绪，只差运行时。
签名的 Apple ID 也需要登录后才能真机部署。

### 14.3 未完成

真机端到端（无签名身份、无真机）；模拟器端到端（等 iOS 26.5 运行时）；
**线上服务端 `8.133.225.105:18443` 编写时连接被拒**（`curl: (7)`，需确认服务与安全组）；
服务端 `key_len` 等偏差的文档侧修复；指令结果回执通道（U3）；APNs 静默推送（真机才有意义）。

---

## 15. 当前项目问题分析（2026-09-14）

> 本节是基于当前工作区源码、既有体检报告、构建记录与 `git status` 的现状判断。优先级含义：P0=发布阻塞或可直接导致设备/凭据失控；P1=启用前应修复；P2=工程完备性与可维护性问题。证据以当前源码为准，历史报告中已修复的 P0 不重复当作现存问题。

### 15.1 P0/P1：必须优先收敛

1. **agentd-go 本地 HTTP 接口仍是 root 高危裸奔面（P0）**
   - 现象：`/api/config`、`/api/task`、`/api/stop`、`/api/screen`、`/api/asr` 等路由未统一鉴权，进程监听 `":"+cfg.Port`；同时 a11y 桥使用 `127.0.0.1:8081`。
   - 根因：代码只打印「监听 127.0.0.1」，但 `ListenAndServe(":port")` 实际绑定所有地址；且 Android 的 loopback 不是应用级安全边界，任意 App 可能访问本机端口。该进程又拥有 root 能力，攻击者可窃取配置中的 Key、读取屏幕或提交任务。
   - 最小修复：默认绑定 `127.0.0.1`；启动时用 `crypto/rand` 生成并以 `0600` 保存随机 token；所有 `/api/*` 与 8081 桥统一校验 token、HTTP 方法、JSON Content-Type 和 Origin/Host；更稳妥的方案是改用 Unix domain socket。

2. **agent-lite 账号令牌仍明文存储（P1）**
   - 现象：`Prefs.java` 直接把 `account_token` 写入普通 `SharedPreferences`。
   - 根因：凭据体系只有内购侧 `AkStore` 使用 Android Keystore，账号令牌尚未迁移。
   - 最小修复：新增 `TokenStore`，复用 `AkStore` 的 Keystore AES-GCM 模式；迁移期间兼容读取旧值，成功迁移后删除明文；同时关闭不必要的备份导出并增加升级迁移测试。

3. **DashScope/上游密钥必须轮换（P1，用户动作）**
   - 现象：历史构建产物与对话记录曾出现真实 Key。
   - 根因：历史构建脚本曾把 `dev.local` 密钥注入 APK；当前文件已清理且构建已 fail-closed，但清理文件不能撤销历史暴露。
   - 最小修复：立即在百炼控制台吊销旧 Key、生成新 Key；新 Key 只经本地忽略文件或安全环境变量使用，禁止进入命令回显、日志、手册和 APK。

4. **双份客户端源码仍需持续防漂移（P1）**
   - 现状：`agent-lite/` 是客户端真身，`agent-unified/client/` 是同步副本；契约门禁曾因未同步而失败，当前已通过 `sync-sources.sh` 恢复 26/26。
   - 风险：后续直接改副本会被覆盖，或构建旧代码；内购、配置和协议变化尤其容易出现行为不一致。
   - 最小修复：将同步作为构建前强制步骤；门禁失败不得发布；在贡献指南明确只改真身，并在 CI 校验来源 HEAD 与副本差异。

### 15.2 软件功能与运行时问题（本节重点）

1. **任务并发启动竞态（P1）**：`agentd-go/main.go:139-143` 先检查 `a.running`，到 `180` 才置位，两个并发请求可能同时启动 Runner，争用输入、截图和日志。应使用 `CompareAndSwap(false, true)`，并在统一 `defer` 中释放状态。
2. **停止任务不够即时（P1）**：`agent-lite/LlmClient.java:268-301` 网络读取最长 120 秒，`AgentEngine.stop()` 只设置标志，无法中断当前连接或阻塞 IPC。应建立任务取消句柄，停止时 `disconnect()`/中断线程，并在所有阻塞点检查取消状态。
3. **HTTP 资源释放不完整（P1）**：`LlmClient.postOnce()` 在 `readAll()` 或 JSON 解析异常时可能跳过 `disconnect()`，流也没有统一 `finally` 关闭。长任务网络异常后可能耗尽连接和文件描述符。应统一 try/finally，并限制响应体大小。
4. **配置保存存在竞争与覆盖错误（P1）**：`agentd-go` 在锁外保存共享 `a.cfg`，并发保存可能覆盖新配置；`strings.Contains(in.APIKey, "...")` 也会误判真实 Key。应锁内生成不可变快照、原子落盘，并使用独立掩码哨兵。
5. **URL 与输入协议校验不足（P1）**：`LlmClient` 直接拼接 `base + path`，没有统一限制 HTTPS、host 和异常端口；`agentd-go` 的 ASR `format` 也未采用有限枚举。应在配置保存时校验 URL，ASR 格式仅允许 `wav` 等明确值。
6. **两套客户端动作协议漂移（P1）**：`agent-lite` 和 `agent-client` 的 `SCHEMA`/执行分支不一致，部分动作只在一端存在；依赖提示词而非统一协议版本和严格字段校验。应抽共享 `protocol` 包，增加 `SCHEMA_VERSION`，未知动作明确回灌并有限重试。
7. **动作完成过度依赖模型自报（P1）**：`done` 主要由模型输出，启动应用、打开链接和设置跳转缺统一状态复验，容易出现“看似完成、实际未完成”。应为每类动作定义前台包名、节点文本、页面状态或屏幕变化等验收条件。
8. **节点编号和感知快照可能失效（P1）**：页面异步刷新、旋转、分屏或弹窗出现时，旧节点编号可能指向错误控件，截图和节点树也可能不同步。应绑定快照 ID、窗口 bounds、旋转和时间戳，执行前校验快照有效性。
9. **日志同步 IO 影响任务与审计（P2）**：引擎每条日志都同步打开、追加、关闭文件，异常被静默吞掉；长任务会增加延迟，失败时也无法确认是否落盘。应使用后台日志队列、轮转、脱敏和丢弃计数。
10. **资源边界与异常恢复不足（P2）**：音频、模型响应、节点树、消息历史和日志存在累计增长风险；空 choices、空文本、非 JSON 和超限响应的恢复策略不统一。应为请求/响应、历史、节点数和任务时长设硬上限，并输出可理解的失败原因。

### 15.3 P2：应纳入下一迭代

- **测试缺少统一入口**：Go 服务端核心账本/订单状态机/PBKDF2/限流仍缺表驱动单测；现有 JVM、Python、契约门禁各自执行，缺少 `make check`/等价聚合命令与 CI 门禁。
- **构建结果自证不足**：APK 体积可能相同而内容不同，不能用字节数判断是否重建；应输出并校验 APK/dex SHA-256，并让脚本在管道中保留真实退出码。
- **凭据与配置边界不统一**：账号令牌、用户 API Key、服务端配置的存储和白名单分散在多个类；应建立统一 `CredentialStore`/配置白名单，所有 URL 强制 HTTPS（本地例外显式限定）。
- **协议层重复维护**：`agent-client` 与 `agent-lite` 重复实现 `SCHEMA`、`ActionParser`、节点模型和看门狗，修复容易只落一端；应抽成纯 Java `protocol/` 共享包并加入版本常量。
- **主线程与生命周期风险**：旧体检仍列出 HTTP 无取消、节点遍历/Root 探测可能阻塞、录音线程直接 `performClick()`、回调注销不完整等问题；应以 StrictMode、LeakCanary、取消通道和真实任务回归逐项闭环。
- **设备与兼容性验证不足**：当前真机验证受手机不在线、有效 RAM AK 缺失影响，充值闭环尚未端到端验证；分屏/旋转/多显示与 HarmonyOS NEXT 仍有明确能力边界，不能用编译通过替代设备验证。
- **文档存在多轨漂移**：`PROJECT_OVERVIEW`、各子项目 README、开发手册和体检报告更新节奏不同；应维护文档地图、事实来源和每次接口/架构变更的同步清单。

### 15.3 推荐执行顺序

1. 先关闭或鉴权 `agentd-go` 的 root API，并完成密钥轮换。
2. 将账号令牌迁移到 Keystore，补迁移/备份/失效场景测试。
3. 固化 `agent-lite` 为源码真身，构建前强制同步 unified 副本，门禁失败即停止。
4. 建立 `make check` 聚合门禁，补 Go 核心逻辑单测与 SHA-256 产物自证。
5. 最后做真机矩阵回归：冷启、进程死亡恢复、无障碍重绑、充值创建/校验/回滚、分屏旋转和跨设备同步。

### 15.4 修复实施记录（2026-09-14 · 项目 agentd +agent-lite）

**已落地**（每项都带验证证据；**粗体**为对应的 §15 编号）：

1. **P0-1 agentd-go 本机 API 收敛**：新增 `agentd-go/auth.go`——随机令牌（32 字节 hex，`0600` 落 `<dir>/api.token`，重启复用，可用 `AGENTD_TOKEN` 覆盖）+ 常量时间比较 + `Host` 必须回环（挡 DNS 重绑定）+ `Origin` 必须同源（挡 CSRF）+ 方法白名单 + POST 强制 JSON（415），令牌为空时 fail-closed；`main.go` 监听显式绑 `127.0.0.1`（原 `":"+port` ＝ `0.0.0.0`）；WebUI 增加令牌输入（存 localStorage）、所有请求走统一封装，截图改 `fetch`+blob URL（`<img src>` 带不了自定义头）。
   *验证*：宿主二进制端到端——无令牌 401 / 错令牌 401 / Bearer 200 / `Host: evil.com` 403 / 跨源 Origin 403 / PUT 405 / 非 JSON 415 / 外网 `http://` base_url 400 / 哨兵保留 Key；`lsof` 确认只监听 `127.0.0.1`。新增 `auth_test.go`、`internal/config/config_test.go`（agentd Go 单测从 0 到 22 项）。
2. **P0-1 8081 a11y 桥**：`agentd-apk` 的 AccessibilityService 改为绑定回环（原来 `new ServerSocket(PORT)` ＝ `0.0.0.0`，**局域网可直连读屏/注入文本**），并要求 `X-Agentd-Bridge` 令牌（令牌写在 APK 私有目录 `files/bridge.token`，同机其他 App 读不到）；`agentd-go` 侧 `device.BridgeToken()/NewBridgeRequest()` 由 root 读取并携带，读不到时**降级为仅回环**（旧 APK 不会被打破）。
   *待真机验证*：root 能否读 `/data/data/com.dsh.agentd/files/bridge.token`（读不到则退化为仅回环，不影响功能）。
3. **P1-2 账号令牌 Keystore 化**：新增 `agent-lite/.../TokenStore.java`（AES-256-GCM，Keystore 别名 `agentlite_account_token_v1`，与 AK 别名分离，进程内缓存）；`Prefs.accountToken()` 首次读取执行一次「加密落盘 → 删除明文」，**只有加密成功才删**；`saveAccount` 改返回 `boolean`，`LoginActivity` 在 Keystore 不可用时明确报错（绝不退化成明文）；`clearAccount` 同时清密文、Keystore 别名与缓存；`AndroidManifest` 关闭备份导出（`allowBackup=false` + `fullBackupContent=false`）。
   *验证*：`agent-lite/build-mac.sh` 构建通过；`sync-sources.sh` 同步副本后契约门禁 26/26。
4. **§15.2-1 任务并发竞态**：`running` 改用 `CompareAndSwap(false, true)` 一步占位，运行位释放统一交给 goroutine 的 `defer`；顺带 fail-fast（未配置 API Key 时不占位、返回 409）。
5. **§15.2-4 配置保存竞争**：锁内 `Merge` 出不可变快照、锁外原子落盘（临时文件 + `fsync` + `rename`），落盘失败回滚内存；掩码判据从 `strings.Contains(key, "...")` 换成显式哨兵 `__AGENTD_KEEP__`，且**真实 Key 不再回流浏览器**（只回哨兵）。
6. **§15.2-5 输入校验（部分）**：`base_url` 强制 HTTPS（`127.0.0.1`/`localhost` 例外）、拒绝内联凭证与越界端口；`port` 限 1..65535（空值不再拼成 `":"`）；`/api/asr` 的 `format` 改白名单（wav/mp3/m4a/webm/ogg）。
7. **§15.2-2 停止任务即时性**：agent-lite `LlmClient` 新增 `cancel()`（断开进行中的连接）与 `CancelledException`——取消不再触发网关回退、不再被误报成「模型调用失败」，`AgentEngine.stop()` 随即断开，并把「用户停止」与「引擎内部异常」分开收尾（不打失败卡片）；agentd `Runner` 接入任务级 context，`/api/stop` 取消后模型请求立刻断开，不必再等最长 180 秒读超时（`llm.ChatCtx` + `Runner.Ctx`）。
   *验证*：`internal/llm/llm_test.go` 断言「已取消的 ctx 立即失败」「请求进行中取消 5 秒内被中断」；`TestHandleStopCancelsTaskContext` 断言停止请求确实取消了任务上下文。
8. **§15.2-3 HTTP 资源释放**：`LlmClient.postOnce` 的**所有**路径（含 402 抛出、JSON 解析失败、取消）都走 `finally` 断开连接，输出流显式关闭；响应体设 1MB 上限。
9. **§15.3 P2-1 测试统一入口**：新增根 `Makefile`——`make check` 聚合 Go 单测（agentd + agentlite-server）、契约门禁、UI 文案/控件守卫、桌面 JVM 回归（AliyunV3Signer/PurchaseFlow）。实跑全绿。

### 15.5 真机验证记录（2026-09-14 · OnePlus 13T / ColorOS 16 / Android 16）

> 设备 `PKX110`（Android 16，KernelSU 可用）；**客户端验证全程走零 root 路径**（仅用 adb shell + App 自身能力，root 只用于读取验证证据）。

**通过项**

1. **覆盖升级 + 令牌迁移**：升级前 `agent_prefs.xml` 里 `account_token` 为 43 字符**明文**；覆盖安装新版后启动 App，明文键被删除、`agentlite_cred.xml` 出现 `account_token_enc`（96 字符 = Base64(12 IV + 43 令牌 + 16 GCM tag)，长度吻合），App 界面仍显示「账号：tqym」——说明密文可正常解密、登录态未丢。
2. **零 root 任务链路**：`setting wifi` 直达 WLAN（约 5 秒完成）、多步 `About phone` 任务正常；无障碍服务处于绑定状态。
3. **停止语义**：日志为「任务已被用户停止」，不再误报「引擎内部异常/模型调用失败」。
4. **网关回退**：线上网关不可达时日志明确记录「额度网关不可用，回退直连」，随后用用户自备 Key 正常完成任务。

**真机抓到的两个缺陷（均已修）**

1. **Keystore 加密必失败**：`InvalidAlgorithmParameterException`——Android Keystore 生成的密钥默认 `randomizedEncryptionRequired=true`，**拒绝调用方自带的 IV**。原实现手工造 IV 并传给 `Cipher.init`，在真机上必然抛异常（迁移因此首次失败、明文按设计保留）。修复：加密不传 IV，改用 `cipher.getIV()` 取 Keystore 生成的随机 IV；`AkStore`（内购 AK 存储）是同源代码，**同样的问题也从没在真机成功过**，一并修掉。
   *教训*：桌面单测覆盖不到 Keystore 行为；这类代码必须在真机上验证，任何"看起来和 AkStore 一样"的复用都要警惕。
2. **停止后仍要等满 connect 超时**：黑洞地址场景实测——20:36:09 点停止，20:36:22 才结束（**13 秒**）。原因：`HttpURLConnection.disconnect()` 只能中断**已建立连接**的请求，连接阶段不响应；任务因此要等满 15 秒 connect 超时。修复：请求交给网络线程执行，主循环每 200ms 轮询结果并检查取消标志（`chatCancellable`），规划请求也登记 `activeLlm`。复测同一场景：**13 秒 → 约 1 秒**。

**环境阻塞（已解决）**

设备到 `8.133.225.105:18443` 被服务器 **firewalld** 拒绝（public zone 只放行 `20,21,22,80,443,8888,39000-40000`），真机上账号同步/额度刷新/兑换码全部不可用；服务端进程本身正常（`systemctl is-active` = active、服务器本机 `curl -k https://127.0.0.1:18443/health` = 200），所以问题纯在网络层。经授权已执行：

```sh
firewall-cmd --permanent --add-port=18443/tcp --add-port=18444/tcp && firewall-cmd --reload
```

放行后逐层复验：服务器本机 `health=200` → 本机 `https://8.133.225.105:18443/health=200` → 设备侧 `nc 18443` = OPEN。注意 `18444` 虽已放行，但**服务端并未监听该端口**（`ss -lntp` 只有 18443），局域网直连通道当前是关闭状态，需要时另行开启。

**令牌迁移的端到端确认**：放行后设备侧触发同步与「刷新额度」，服务端日志显示来自设备出口 IP 的全部请求 **200**（`POST /api/v1/sync/push`、`GET /api/v1/sync/pull`、`GET /api/v1/device/commands`、`GET /api/v1/account/me`）。这是「Keystore 密文可解密、解密出的令牌能通过服务端鉴权」的最强证据——若解密失败或迁移出错，这里会是 401。

**仍待办**：§15.2-6 两客户端动作协议漂移（抽 `protocol/` 包 + `SCHEMA_VERSION`）、§15.2-7/8 动作复验与快照 ID、§15.2-9/10 日志异步化与资源硬上限、§15.3 其余 P2、**§15.1 P1-3 密钥轮换（用户动作）**、真机矩阵回归（含 agentd 令牌流程与 8081 桥 token 的实机确认）。

## 16. 「极大降低技术门槛」守则与 DeepSeek 4.1 Flash 指导（2026-09-14）

### 16.1 审查结论：当前只降低了操作门槛，没有消除技术门槛

项目已经做到自然语言下达任务、语音输入、无障碍自动执行、API 优先、失败看门狗和多层输入降级；但“极大降低技术门槛”目前仍是口号，不是可验收的产品守则。首要问题是：新用户仍可能被要求理解 API Key、Base URL、模型名、RAM 子账号、资源包匹配和无障碍设置；这等于把使用门槛从“写代码”转移成“懂云服务”。

### 16.2 P0：违反低门槛宗旨的问题

1. **默认首跑可能必然失败**：构建脚本为安全而不内置 Key 是正确的，但用户不登录、不填 Key 直接运行时，系统才在模型请求后报“检查 API 配置”。应在点击运行前判断凭据状态，不发无效请求，展示唯一下一步：登录/领取试用额度或选择“我已有 API 配置”。
2. **授权能力存在承诺与实现不一致**：授权入口和文案让用户以为可管理应用权限，但 `requestAuth()` 仍可能没有真实调用链；自动同意服务条款也可能替用户作出不可逆授权。应接通真实授权状态机；协议、付费、权限条款必须暂停交给用户，不得自动代点。
3. **充值流程把云平台凭据前置给普通用户**：AccessKeyId/Secret、RAM 权限和模型资源包是高级运维概念，不应成为默认成功路径。应优先提供登录赠送额度/兑换码/托管额度；自带 Key 放入“高级设置”，录入前先解释风险、权限范围和撤销方式。
4. **失败没有可执行的下一步**：失败文本若只写网络、API 配置、额度或异常类名，用户无法恢复。每个失败终态必须提供一个主按钮（重试、开启无障碍、登录、修复网络、缩小任务、手动完成）和一个“为什么”入口。
5. **生态策略可能不持久**：若设置保存后又被初始化逻辑清理，用户看到的“受限/登录后/自动”选择不会真正生效。所有用户设置必须冷启动、进程死亡和升级后保持，并有回归测试。

### 16.3 P1：用户旅程中的主要门槛

- 首次启动不应同时请求录音、通知、无障碍、登录等多件事；当前应改为“先理解用途 → 用户输入第一条任务 → 需要时再请求对应权限”，录音权限延迟到第一次点击语音。
- 首屏设置项过多；默认只呈现任务输入、语音、运行、登录状态和无障碍状态，Base URL/API Key/模型/同步/Token 等归入“高级设置”并折叠。
- 无障碍开启应提供图示三步引导、实时检测、失败时明确说明“系统设置里点哪里”，而不是让用户自行查文档。
- 账号恢复不能只依赖“不可找回”的账号密钥；应有安全备份/恢复或明确可承受的额度迁移方案。
- 设备和助手状态必须可见：任务中、等待用户、网络失败、无障碍断开、已完成和已停止使用不同颜色与动作按钮；“用户停止”不应显示为“任务失败”。
- 自动化必须让用户知道数据去向、当前控制对象和停止方式；跨设备任务要在执行前明确提示目标设备，并在目标设备重新确认敏感操作。
- 任务完成不能只采信模型 `done`；至少验证前台应用、关键节点、页面状态或屏幕变化。否则“降低操作门槛”会变成“降低正确性门槛”。

### 16.4 SPEC-UX-001：零门槛守则（后续纳入 `make check`）

1. **五分钟首成功**：全新用户不查文档、不输入 Base URL/API Key/模型名，五分钟内完成一项“打开应用/打开设置”的安全任务。
2. **首屏零技术词**：首屏和首轮引导不得出现 API、Base URL、endpoint、模型名、RAM、token 等词；高级页可以出现并解释。
3. **无效请求前置拦截**：无凭据时禁止向模型发请求，改为一张可操作的解决卡。
4. **每个失败可恢复**：失败必须包含人话原因、下一步动作和可重试入口；禁止把 Java/HTTP/云厂商原始错误直接展示给普通用户。
5. **权限渐进申请**：只在功能真正需要时申请权限；一次只解释一类权限；拒绝后提供可用降级路径。
6. **状态可解释**：任何自动动作显示“正在做什么、操作哪个应用、如何停止”；敏感操作显示暂停并等待用户。
7. **结果可证明**：`done` 必须有动作对应的最小验收证据；无法证明时只能继续复验或 `fail`。
8. **设置可恢复**：用户配置冷启动、进程死亡、升级后保持；危险设置可撤销，清理必须版本化迁移。
9. **低端/受限设备可用**：无截图、无障碍被屏蔽、网络不稳定时，必须给出降级路径或清晰失败原因，不得让模型无限重试。
10. **副本和模型同源**：动作词表、执行分支、友好文案和测试必须有单一事实源，并带 `SCHEMA_VERSION`。

### 16.5 DeepSeek 4.1 Flash 实施指导

将 DeepSeek 4.1 Flash 定位为“快速、低成本的决策模型”，而不是可靠的业务规则引擎。安全、权限、金额、协议和完成判定必须由引擎硬编码，不能只依赖提示词。不同供应商是否支持 JSON Schema/结构化输出必须先以实际网关文档和探测结果确认；项目应保留纯 JSON 文本解析兜底，不要假定所有兼容接口行为一致。可参考 [DeepSeek API 文档](https://api-docs.deepseek.com/quick_start/pricing/)，但不能把第三方模型页面当作协议事实源。

**提示词原则**：

- system 只保留角色、动作契约、环境态、敏感红线和终态规则；把冗长教程移到代码规则或短格式帮助，减少每步输入成本。
- 每轮只给“当前任务、完成判据、最新快照、可用动作、最近一次失败原因”；不要把大量历史日志和重复规则塞给 Flash。
- 明确要求只输出一个 JSON 对象；字段用有限枚举；禁止编造包名、节点编号、坐标和不存在的动作。
- 给每个任务写清完成判据；例如“打开应用”=目标包在前台，而不是“已发送 startActivity”。
- 把敏感操作、权限条款、发送、支付、删除等设置为引擎不可绕过的 `ask`/人工接管，不要让 Flash 自己判断是否可以越过。

**推荐动作契约**：

```json
{"action":"tap","index":3,"snapshot_id":"s42"}
```

引擎必须校验 `action`、字段类型/范围、`snapshot_id`、节点编号和当前窗口；不合法动作只允许一次短错误回灌，随后走降级或失败。模型不得输出自由文本解释。

**任务拆分与实施顺序**：

1. 先建立 SPEC-UX-001、门槛指标和测试夹具。
2. 加入 `SCHEMA_VERSION`，清理 `exec()` 中未注册的 `back/home`，统一两客户端词表、设置页枚举和等待常量。
3. 实现无凭据前置守卫、明确登录/试用/自带 Key 三条路径。
4. 修正自动同意、授权状态机和 A11y 守护抖动；敏感协议必须人工确认。
5. 统一人话错误归因、失败卡片和重试/修复按钮。
6. 为 `app`、`setting`、`open_api` 先做最小完成复验，再扩展到复杂任务。
7. 最后做真实设备五分钟首成功、网络断开、空节点纯视觉、旋转分屏、进程死亡和敏感操作回归。

**DeepSeek 4.1 Flash 每次实施任务的工作协议**：

- 只改一个明确范围，优先一文件一验证；不得顺手重构。
- 开工前读取 `PROJECT_OVERVIEW.zh-CN.md`、`docs/INTERACTION_LOGIC_SPEC.zh-CN.md`、目标子项目 README 和 `Makefile`。
- 只修改 `agent-lite/` 真身；完成后运行 `sync-sources.sh`，禁止直接编辑 `agent-unified/client/`。
- 输出分为：证据（文件与行号）、改动、验证真实结果、未验证项与风险、下一步（最多三条）。禁止用“编译通过”代替真机验证。
- 每次必须回答：无凭据时怎么办？空节点/纯视觉时怎么办？模型输出非法动作时怎么办？用户如何停止？完成如何证明？
- 任何不确定的模型能力、API 字段或第三方协议都标记“未确认”，先做最小探测，不得猜测。

### 16.7 实施记录（2026-09-14/15 · 项目 agent-lite）

按 §16.6 优先级与 §16.5 实施顺序落地，每项都给出证据与验证方式。

1. **SPEC-UX-001 落盘并入门禁**：`docs/SPEC_UX_001.zh-CN.md`（10 条 + 逐条验收方式 + 三条凭据路径）；
   门禁 `tests/spec_ux_001_guard.py` 已并入 `make check`，当前 **14 通过 / 0 失败 / 1 需真机**。
   门禁只做能真伪判定的静态断言（源码级），需要真机的条款标 `MANUAL` 并提示，不假装通过。
2. **P0-1 首跑无凭据前置拦截**：`MainActivity` 新增 `hasUsableCredential()`（三条路径：账号登录 / 自带 Key / 未登录但已填 Key）与 `showCredentialCard()`（人话说明 + 主按钮「登录 / 领取额度」+「打开设置」+「稍后」）。
   *真机验证*：临时清空 api_key 并置 `use_own_key=true` 后点运行——**未发出任何模型请求**（日志「任务启动」计数 0），弹出解决卡；测后配置已完整还原。
3. **P0-4 失败可恢复**：`UiText.nextStep(归因)` 给出 8 类可执行下一步；`OverlayController.showFail(人话归因, 原始细节)` 卡片改为三段式（归因 → 下一步 → 「重试一次 / 为什么 / 关闭」），原始技术细节只在用户主动点「为什么」时展开；`AgentEngine.lastTask()` 支撑重试。
4. **上游错误分类归因**：`LlmClient.UpstreamRejectedException` + `classifyUpstream()` 识别欠费 / 密钥失效 / 无权限 / 模型不存在；并带**通道标识**（账号额度网关 or 用户自备配置），两者都失败时合并说明。
   *真机实测输出*：「任务失败——两条模型通道都不可用：接口账号余额不足（欠费），模型无法调用；备用通道同样不可用：…」，技术原文只进日志。
5. **自带 Key 失效自动改用账号额度**：`LlmClient.forPrefs` 在自备 Key 路径上把账号额度登记为备用通道，上游拒绝且「换通道可能有用」时自动切换（402 额度耗尽仍不回退，避免绕过额度）。
   *真机日志*：「上游拒绝（接口账号余额不足（欠费）），改用备用通道」。
6. **UX-10 单一事实源**：新增 `ActionSchema.java`——`SCHEMA_VERSION = 2026-09-14.1`、22 个执行动作 + 3 个终态动作、`isRegistered()` 校验；**把 `back`/`home` 注册进词表**（此前 `exec()` 已实现却从没告诉模型，属隐藏动作）；未注册动作只回灌一次、连续 3 次即终态。词表版本同时写进给模型看的提示词，排障时能对齐「模型看到的词表」与「设备跑的代码」。
7. **UX-5 权限渐进申请**：启动期不再一次性索要录音 + 通知权限（门禁 `UX-5` 会因此变红，已修）；录音权限在第一次点「语音」时申请，通知权限在第一次真正跑任务时申请且只问一次。
8. **UX-7 done 最小复验**：`AgentA11yService.activePackage()` + 引擎的期望包名（`app` → 目标包、`setting` → 设置应用片段）+ 「前台应用是否变化」证据；`done` 缺少证据时**追问一次**（只一次，第二次直接接受，避免把用户卡在循环里）；`app`/`setting` 未生效时回灌「上一步似乎没有生效」并要求换策略。
9. **§15.2-9 日志异步化**：新增 `EngineLog.java`——入队即返回（不再每条日志开关一次文件）、队列满时丢弃并**记录丢弃条数**、单文件 1 MB 轮转（`engine.log.1`）、落盘前**脱敏**（`sk-…`/`LTAI…`/`Bearer …`/`api_key=…`）、引擎与守护共用一个写入者，落盘失败只记异常类型并重试。
10. **§15.2-10 资源硬上限**：对话历史裁剪移到**每步开头**统一执行（原先只在循环末尾裁剪，多条 `continue` 分支会绕过它导致上下文膨胀）；单任务 20 分钟硬上限；模型空 `choices`/空内容统一为可理解错误；录音 30 秒上限并自动收尾（`VoiceRecorder.MAX_PCM_BYTES` + `setOnAutoStop`）。

11. **P0-2 引擎不再替用户同意协议**：删除 `autoAgreeTerms()` 的代点逻辑（它会在识别到条款/隐私政策弹窗后替用户点「同意并继续」、代勾「已阅读并同意」——那是替用户作出不可逆的法律授权），改为 `isTermsDialog()` 只做检测 + `enterPause()` **暂停并把控制权交还用户**，用户自行确认后点「交还 AI」继续。门禁新增静态断言：检测方法体内不得出现任何点击调用，且方法定义已不存在。
12. **P2 透明提示**：首次说明弹窗按「数据去哪 / 谁在控制 / 怎么停」三段重写——明确屏幕内容与截图会发给你配置的模型服务（登录时走账号额度网关）、光晕与胶囊的语义、停止与接管的区别。

**真机回归（OnePlus 13T / ColorOS 16 / Android 16，客户端零 root）**：安装升级 + 令牌迁移保持登录、无凭据守卫零请求、失败归因准确、账号与配置在升级与进程重启后保持、无障碍服务在绑。**未验证**：五分钟首成功、空节点纯视野、旋转分屏、敏感操作暂停——其中「五分钟首成功」被下述阻塞项直接阻断。

**⚠️ 上线阻塞项（2026-09-15 已解决，处置见 §16.8）**：当时两条模型通道的上游都欠费——
账号额度网关的上游是服务器上的 dashscope Key（`AGENTLITE_UPSTREAM_KEY`），用户自备的 dashscope Key 同样欠费（`Arrearage`）。
现象：任何任务都会在第一步失败（归因已能准确说明是"两条通道都不可用"）。

### 16.8 模型通道切换：接入 DeepSeek（2026-09-15）

**背景**：dashscope 侧两条通道的上游都欠费（§16.7 阻塞项），产品在真机上完全不可用。
处置：把额度网关的上游切到 DeepSeek API，模型用 `deepseek-flash`（DeepSeek-V4.1-Flash）。

**改动（服务端 `agentlite-server`）**

1. 新增 `-upstream-model` 参数 / `AGENTLITE_UPSTREAM_MODEL` 环境变量；`gatewayConfig` 增加 `upstreamModel` 字段。
2. 新增 `gatewayConfig.adaptRequest(body, model) ([]byte, string)`，转发前做两件事：
   - **模型名映射**：`upstreamModel` 非空时改写请求体的 `model`。换上游后客户端配置里的
     `qwen3.5-omni-plus` 在上游并不存在，映射放服务端 → **用户端零改动**（手册 §16「降低技术门槛」）。
   - **关闭思考链**：上游为 DeepSeek 时注入 `{"thinking":{"type":"disabled"}}`。
     *实测结论（2026-09-15，api.deepseek.com/v1）*：`thinking.type=disabled` ✅ 有效、
     `reasoning_effort:"none"` ✅ 同样有效、`enable_thinking:false` ❌ 无效、
     `chat_template_kwargs.enable_thinking` ❌ 无效。不关闭时思考会吃满 `max_tokens`，
     `content` 返回空串（UI Agent 的「只输出一个 JSON 动作」会直接失效）。
   解析失败一律原样转发（适配绝不把请求弄坏）；计费与日志用**映射后**的模型名。

**改动（客户端 `agent-lite`）**：`LlmClient.chatOnce` 的关思考条件加入 `deepseek`，直连 DeepSeek 时同样关闭思考链；
经网关时 URL 不含上游域名，由服务端注入。

**部署与配置**

- 服务器 `/etc/agentlite-server/env`（0600）：`AGENTLITE_UPSTREAM_URL=https://api.deepseek.com/v1`、
  `AGENTLITE_UPSTREAM_KEY=<DeepSeek Key>`、`AGENTLITE_UPSTREAM_MODEL=deepseek-flash`；旧 env 已备份为 `env.bak-*`。
- 二进制切换：旧版备份为 `/usr/local/bin/agentlite-server.bak-20260914`，新版就位后 `systemctl restart`。

**验证证据**

| 层级 | 结果 |
|---|---|
| 服务端启动日志 | 「额度网关已启用 → https://api.deepseek.com/v1」 |
| 本机端到端（服务器上） | 客户端发 `model=qwen3.5-omni-plus` → 响应 `model=deepseek-flash`、`content` 直接是 `{"action":"done",...}`（`finish_reason=stop`、无 `reasoning_content`） |
| 计费 | `gateway charged account=… model=deepseek-flash tokens=27 units=1 balance=499` |
| 真机（OnePlus 13T） | 任务启动 `model=deepseek-flash +vision`、规划简报就绪、多步执行且**未死循环**、空节点时进入纯视觉降级 |

**风险与遗留**

- 该 Key 曾出现在对话记录里，**建议上线后轮换一次**；它只落在服务器 0600 env 与设备本地 prefs，未进仓库。
- `deepseek-flash` 在「打开计算器」这类任务上会一路点到应用商店（模型决策质量问题，非通道问题）。
  建议后续：提示词里补「不得通过应用商店搜索/安装应用」红线，或给这类任务加 `app` 兜底动作优先级。
- 服务器上留有一个冒烟测试账号（`ops-smoke-*`），可随时删除。

### 16.6 当前优先级

1. P0：建立 SPEC-UX-001 并加入门禁；修复首跑无凭据路径、真实授权/协议人工确认、生态配置持久化。
2. P1：失败卡片与权限渐进申请；设置页分层；完成 `done` 最小验收和 `snapshot_id`。
3. P1：统一两客户端动作协议，加入 `SCHEMA_VERSION`，清除隐藏动作和提示词失配。
4. P2：补齐设备矩阵与五分钟首成功指标，建立“数据去哪/如何停止/谁在控制”的透明提示。

### 16.9 最新进程与下一阶段指导（2026-09-15 06:23）

#### 当前已经完成

- **低门槛规范已落盘**：`docs/SPEC_UX_001.zh-CN.md` 已建立，`tests/spec_ux_001_guard.py` 已接入 `make check`；当前免设备门禁为 **15 通过 / 0 失败 / 1 项需真机**。因此“低门槛”已从口号进入可检查阶段，但 UX-1 五分钟首成功仍不能仅凭静态门禁宣称完成。
- **首跑路径已改善**：无凭据会在运行前拦截，不再发无效模型请求；失败卡片、下一步按钮、上游错误分类、权限渐进申请和透明提示已实现。
- **交互协议已开始收敛**：新增 `ActionSchema` 和 `SCHEMA_VERSION=2026-09-14.1`，隐藏的 `back/home` 已注册，未知动作限制回灌次数；`done` 对 `app/setting` 已增加最小复验；条款弹窗改为暂停交还用户，不再自动代点。
- **工程稳定性已改善**：agentd-go 已具备鉴权、任务取消、配置原子保存、输入白名单；客户端账号令牌已迁移到 Keystore；日志异步化、响应/历史/任务时长上限已加入；`make check` 全部通过。
- **DeepSeek 通道已接入**：服务端可把客户端模型映射为 `deepseek-flash`，并关闭思考链；本机端到端和 OnePlus 真机已完成多步执行、规划、纯视觉降级验证。两条原 DashScope 通道欠费的阻塞已解除。

#### 当前仍未完成或需要重新确认

1. **UX-1 五分钟首成功仍是唯一正式门禁中的真机项**：需要清数据/新用户路径验证“登录 → 一句话 → 打开应用或设置”，并记录耗时、请求次数、最终状态；不能用 `make check` 代替。
2. **DeepSeek 任务成功率还不足以发布**：已出现“打开计算器却一路进入应用商店”的错误策略。应先补充“禁止通过应用商店搜索/安装应用、优先使用 `app` 直达”的规则，再做至少 20 个固定任务回归，记录完成率、误触率、平均步数和失败归因。
3. **协议统一仍未完成**：`agent-lite` 与 `agent-client` 的动作集合和等待/降级行为仍可能不同；`ActionSchema` 当前主要覆盖 lite。下一步应以 lite 为真身，生成或校验 unified 副本，并决定 `agent-client` 是同步协议还是冻结维护。
4. **完成复验覆盖面有限**：目前主要覆盖 `app/setting`；`open_api`、`open_url`、搜索、拨号、跨设备等动作仍需定义验收条件，不能只接受模型 `done`。
5. **真机安全回归未闭环**：仍需验证空节点纯视觉、旋转/分屏、敏感条款暂停、用户停止、进程死亡恢复和无障碍重连；这些是低门槛体验的关键，不是可选测试。
6. **配置和发布状态需整理**：工作区存在大量未提交改动，且最新功能尚未形成与 release tag 对齐的发布包；在继续扩大功能前应先生成变更清单、提交分层 commit，并标记可发布版本。

#### 面向 DeepSeek 4.1 Flash 的下一轮实施顺序

**T1：先做模型行为基线，不改代码。** 固定 20 个低风险任务（打开常用 App、打开 Wi-Fi 设置、打开蓝牙设置、打开系统设置、打开计算器等），统一设备、截图、模型和 `max_steps`，保存每次的任务文本、动作序列、完成证据、步数和失败原因。目标是区分“通道问题”和“模型决策问题”。

**T2：只修提示词和动作优先级。** 在 `agent-lite` 真身中加入：

- “打开应用”必须优先 `app`，禁止打开应用商店、搜索并安装；
- 包名不确定先 `list_apps`，禁止猜包名；
- 任务完成必须等待并验证前台包名；
- 每次只输出一个动作，失败后切换策略，不原样重试；
- 低风险任务不要额外 `ask`，支付、转账、发送、删除和协议授权必须暂停。

T2 验收：20 个任务中记录动作序列；“打开计算器”不得出现商店相关动作；非法 JSON/未知动作不超过一次回灌。

**T3：再扩展完成复验。** 为 `open_api`、`open_url` 和 `remote` 分别增加最小验收器；验收器失败时回灌“未完成原因 + 可用替代动作”，而不是直接失败或接受 `done`。

**T4：最后做真机低门槛回归。** 清数据后从普通用户视角执行；禁止开发者手工修改配置或通过 adb 注入凭据。记录首屏到首成功的时间、权限次数、用户点击数和是否出现技术术语。未满足 UX-1 就不能把版本称为“零门槛”。

#### DeepSeek 4.1 Flash 实施约束

- 把安全规则、动作字段校验、完成复验和敏感暂停放在引擎，不放在模型自觉中。
- `thinking` 关闭后仍要保留简短、结构化的失败反馈；不要用增加 `max_tokens` 掩盖决策错误。
- 规划模型与执行模型先统一为同一 `deepseek-flash` 基线，避免多个模型名导致资源和行为不可解释；确认稳定后再引入专用规划模型。
- 每次只改一个行为主题；改动前后都运行 `make check`，然后再做真机任务；禁止只看 APK 大小或编译退出码。
- 记录“模型看到的快照”和“引擎实际执行的动作”，否则无法区分模型错、感知错和执行错。

### 16.10 设备失联期间的实施记录（2026-09-15 06:30–08:20）

真机在 06:29 起失联（`adb devices` 为空），因此本轮只做**不需要设备、且可自证**的部分；
需要真机的项（UX-1、20 任务基线、纯视觉/旋转/分屏/进程恢复）留给设备回来后执行。

**T2 提示词与动作优先级（已落地）**

- 提示词新增 6–9 条守则：打开应用只用 `app`、**严禁**进商店搜索/安装、包名不确定先 `list_apps`、
  启动后先确认前台已是目标应用、同一动作连续两次无变化第三次必须换思路、低风险任务不要 `ask`。
- **引擎硬防线**（不依赖模型自觉，§16.9 实施约束首条）：`isAppStoreForeground()` + `taskAllowsStore()`——
  当前台是应用商店（`vending`/`market`/`qqdownloader`/…）而任务并未要求安装/搜索应用时，
  **直接拒绝该动作并回灌**，模型只能改用 `app`/`setting`/`open_url` 或如实 `fail`。

**T3 完成复验扩展（已落地）**

- 新增「弱期望」验收器：`expectEffect` + `NodeInfo.fingerprint`。`open_url`、`open_api`、
  纯视觉坐标点击（`tap` 不带 `index`）执行后，若下一步感知到的屏幕指纹与前台包名**都没有变化**，
  回灌「上一步可能没有生效，请换方式重试」。`text` 由无障碍层返回值保证；`remote` 是跨设备，本机屏幕
  本就不该变化，两者都不登记弱期望（避免误判）。

**T1 行为基线工具（就绪，待真机）**

- `tests/task_baseline.py`：固定 20 个低风险任务（打开各应用/设置页），逐条采集
  任务文本、动作序列、步数、耗时、终态、前台包名、商店误跳、最大重复次数，追加写入
  `tests/results-baseline.jsonl`（已在 .gitignore），并汇总成功率/误跳率/重复率/平均步数/平均耗时。
- 解析器带 `--selftest`（无需设备）：覆盖 done / fail / timeout / store_hit / repeat 五类判定。
- 使用：`python3 tests/task_baseline.py`（设备回来后直接跑）。

**协议统一（决策已定）**

- `agent-lite` 是**唯一真身**；`agent-unified/client` 只由 `sync-sources.sh` 生成。
- `agent-client` **冻结维护**：其动作集合必须是 lite 真身的子集，已由新门禁
  `tests/action_schema_guard.py` 强制（当前 19 个动作全部在 lite 词表内；
  已显式记录它缺少 `list_apps` / `open_api` / `remote`，不再演进）。
- 门禁同时检查「提示词承诺的动作 ⊆ 注册表」「注册的动作 ⊆ exec() 分支」「exec() 分支 ⊆ 注册表」
  （后者专门防止再次出现 `back`/`home` 那样的隐藏动作）。

**发布整理（已完成）**

- 7 个分层提交（门禁 / agentd / 服务端 / 客户端 / 同步副本+apk / agent-client / 文档），工作区干净。
- 打本地 annotated tag **`v0.6.0-zerofriction`** 作为可发布基线（对应 §16.4 零门槛与 §16.8 DeepSeek 接入）。
- 提交后复验：`make check` 全绿（契约 26 / SPEC-UX-001 17 / 动作词表 8 / JVM 47+20）。

**仍未闭环（必须真机，设备回来后按此顺序）**

1. `tests/task_baseline.py` 跑 20 任务基线 → 用 `store_hit_rate` 与 `repeat_action_rate` 判断 T2 是否见效；
2. 五分钟首成功（UX-1）：清数据 → 登录 → 一句话 → 完成，记录耗时/点击数/权限次数/是否出现技术术语；
3. 真机安全回归：纯视觉、旋转分屏、敏感条款暂停、用户停止、进程死亡恢复、无障碍重连。



### 2026-09-13 00:54 +08 · 会话 33f50b22 · 新建 project-work-log skill
- 00:52 考察 skill 机制：~/.dsh/skills 为安装目录、workspace skills/ 为源副本（源:命令:diff/ls）
- 00:53 编写 skills/project-work-log/SKILL.md 并安装至 ~/.dsh/skills/（源:用户请求）
- 00:54 目录刷新确认 skill 已上架；为本手册引导「工作日志」节（源:步骤2）
- 00:54 终态: done

### 2026-09-13 00:58 +08 · 会话 e9db9437 · 修复 Hindsight 与 DSH 卡死
- 00:58 完成现状诊断：GUI PATH 缺少 uvx/node/cargo，DSH 主渲染进程持续高负载（源:命令:ps/sample）
- 01:07 定位 Hindsight 根因：GUI PATH 仅 4 个系统目录，launchctl setenv 不送达（源:命令:ps eww/launchctl getenv）
- 01:08 给 dsh.js 与 daemon-start.js 打 PATH 前置补丁；最小 PATH 下复现→验证通过（源:文件:~/.dsh/local-patches/hindsight-daemon-path.md）
- 01:16 定位守护进程二次卡死：huggingface.co 超时、hf-mirror 正常，写入 HF_ENDPOINT（源:命令:curl）
- 01:20 Hindsight 服务就绪：/health=200，DSH 侧工具恢复正常（源:命令:curl /health）
- 01:16 定位卡死主因：~/Desktop 处于 iCloud 同步下，94,446 文件被持续上传/索引/缩略图（源:命令:defaults read/du）
- 01:18 用户关闭桌面同步后，把 14 项全部迁回本地 ~/Desktop，94,446 文件对账一致（源:用户请求+命令:mv/find）
- 01:20 核实 wallpaper-engine 早已 disabled、remote-web-ui 同；DSH 有 render-process-gone 自愈（源:文件:cordis.patch.yml）
- 01:23 Spotlight：给 16 个可再生构建目录加 .metadata_never_index，覆盖 42,385 文件退出索引（源:命令:find/touch）
- 01:27 定位 LLM 真因：本机 claude 靠 cc-switch 中转认证，被 Hindsight 的空 CLAUDE_CONFIG_DIR 隔离打断（源:测试:env CLAUDE_CONFIG_DIR 复现）
- 01:28 改为直连中转（provider=anthropic，令牌运行时读取不落盘），LLM 校验通过（源:文件:coding-agent.log）
- 01:29 抽取链路实跑成功：retain_extract_facts 对中转 200 OK（源:文件:coding-agent.log）
- 01:30 补丁与回滚记录在 ~/.dsh/local-patches/hindsight-daemon-path.md（源:文件:同路径）
- 01:30 终态: done（待办：DSH 本体重启一次以加载插件补丁）


### 2026-09-13 01:22 +08 · 会话 local · 收款界面（应用内买单页）
- 01:22 首次运行检查：读本手册「工作日志」尾 40 行，恢复上下文（源:用户请求）
- 01:22 确认需求：「首款界面」经确认为「收款界面」（应用内充值/买单页）（源:用户请求）

### 2026-09-13 01:27 +08 · 会话 f8d7e529 · 修设置页震荡+UI去药丸+可读性
- 01:00 读手册工作日志尾恢复上下文（源:文件:PROJECT_OVERVIEW.zh-CN.md:263）
- 01:05 排查「文字渲染溢出」：手机 UI 树/像素/静态宽度三路核查，App 自身页面均无越界（源:命令:uiautomator dump + tests/ui_layout_check.py）
- 01:12 定位真因在语音面板：等分按钮 135dp 装不下 7 字标签「按住/点按说话」（1.15× 字体下 113dp+内边距）→ 标签缩短+单行省略+内边距（源:文件:agent-lite/res/layout/activity_assist.xml:82）
- 01:16 去药丸风格：新增 Widget.AgentLite.Button{,.Tonal,.Outlined}（cornerRadius 8dp），51 个按钮全部切换（源:文件:agent-lite/res/values/styles.xml）
- 01:18 字阶再提一档 24/17/15/14/13sp；提示文字色改 on_surface_variant（3.63:1 → 约 7:1）（源:文件:agent-lite/res/values/dimens.xml）
- 01:20 溢出防护：63 个按钮补 maxLines/ellipsize；主界面 语音/运行 固定宽改 wrap_content+minWidth（源:文件:agent-lite/res/layout/activity_main.xml:266）
- 01:22 修问题2（setting home 震荡）：移除 home→ACTION_HOME_SETTINGS 映射 + SCHEMA 枚举同步；新增**震荡看门狗**（6 步内屏幕状态≤2 且动作≤2 → fail）（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:309）
- 01:24 三处一致：Skill §4 页面枚举去 home + §5 补提示；守则升 1.0.3 登记 §8 震荡看门狗（源:文件:docs/INTERACTION_LOGIC_SPEC.zh-CN.md:171）
- 01:26 门禁全绿（契约 26/26、文案 7+10、控件 16）+APK 4 条断言；确认 radius_btn=8dp、text_body=15sp 进包（源:命令:build-all.sh）
- 01:27 装机验证：与源包逐字节一致、无障碍授权保留、启动无 FATAL（源:命令:adb install -r）
- 01:27 终态: done
- 01:33 服务端 order.go：套餐(packs.json 可覆盖)+订单状态机+兑换码支付；8 项负向用例通过（源:命令:curl）
- 01:33 客户端 PurchaseActivity + activity_purchase.xml + AccountClient 订单方法（源:文件:agent-lite/src/com/dsh/agentlite/PurchaseActivity.java）
- 01:33 记账口径验证：小码付大套餐以码为准，不凭空产生额度（源:测试）
- 01:33 构建+守卫通过：ui_text_guard 7/7、ui_widget_guard 16/16（源:命令:bash tests/）
- 01:33 未完成：模拟器实例已关闭，收款界面的运行时验证未做（源:命令:adb devices）
- 01:33 终态: done（附未做项）

### 2026-09-13 01:45 +08 · 会话 f8d7e529 · UI 回归第一版 + 删除 M3
- 01:30 摸清 M3 足迹：3 个提交、6 布局 67 个 Material 组件、9 个 drawable、styles/colors/v31、build-mac.sh +93 行（源:命令:git show --stat）
- 01:34 恢复第一版资源：9 个 drawable（btn_primary 22dp/ghost 20dp/chip 14dp 圆角）+ 平台主题 Theme.Material.NoActionBar + 27 色板 + v31 动态取色（源:命令:git show 515d5cd）
- 01:37 转换布局：MaterialButton→Button 64 个（按样式语义映射到 btn_primary/ghost/chip）、MaterialSwitch→Switch 3 个、清除 app 命名空间（源:文件:agent-lite/res/layout/*.xml）
- 01:39 Java 回归平台控件：SettingsActivity/WatchSettingsActivity 的 MaterialSwitch → android.widget.Switch（源:文件:agent-lite/src/com/dsh/agentlite/SettingsActivity.java:31）
- 01:41 删除 M3 支持：build-mac.sh 移除 AAR 展开/库资源/extra-packages 与 LIB_JARS；删除 scripts/fetch-m3-deps.py 与 build-with-m3.sh；build-client.sh 去掉依赖准备步骤（源:文件:agent-lite/build-mac.sh）
- 01:43 构建验证：**APK 5900KB → 183KB（31×）**、dex 6→1、包内无 material 类；门禁全绿（契约 26/26、文案 7、控件 0 转型）（源:命令:build-all.sh）
- 01:44 装机核验：主题 parent=平台 Theme.Material.NoActionBar、btnMic 顶行填充占比 81.3%→27.6%（回到第一版 20dp 圆角）、渲染正常无崩溃（源:命令:adb install + 像素测量）
- 01:45 终态: done

### 2026-09-13 02:05 +08 · 会话 f8d7e529 · 主界面指令行合并+磁贴间距
- 02:00 主界面任务下达区：输入框独立一行、语音/运行在下一行 → 合并为**同一行**（输入框 0dp+weight=1；横向容器里这才是正确写法）（源:文件:agent-lite/res/layout/activity_main.xml:240）
- 02:02 四大磁贴「历史记录｜授权管理」原本两个 weight=1 按钮无间距顶在一起 → 授权管理/记忆库左侧补 12dp（源:文件:agent-lite/res/layout/activity_main.xml:180）
- 02:03 全布局扫描相邻 weight=1 无间距的按钮：修复后 0 处（源:命令:python3 扫描脚本）
- 02:04 装机实测：三者垂直中心差 0.0dp（同一行 ✓）、磁贴间距 12.0dp（✓）；构建与门禁全绿（源:命令:adb install + uiautomator dump）
- 02:05 终态: done

### 2026-09-13 01:42 +08 · 会话 local · 充值改版决策（跳百炼官方购买）
- 01:42 确认决策：AK 只存本地 / 不买 TokenPlan 买 token 资源包 / 用户自付自己百炼账号 / 隐藏充值 API / 另加「更改 API」（源:用户请求）
- 01:42 查实 CreateApiKey 存在且为 ROA 风格 + V3 签名（非经典 RPC HMAC-SHA1）（源:web:help.aliyun.com/model-studio）
- 01:42 决策与实现清单固化到 docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md，含两点实现前必须先验的事项（源:文件:docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md）
- 01:42 未开始实现：本会话上下文接近上限，避免写出不完整改动（源:步骤3）
- 01:42 终态: blocked（等用户提供套餐↔官方档位对应关系；另需先打通 CreateApiKey 的真实请求形状）

### 2026-09-13 02:20 +08 · 会话 f8d7e529 · 删除主页设置条与调试台
- 02:12 定位两处删除点及其 Java 绑定（btnSettings → openSettings、btnDebug/dbgScroll + 12 个直测按钮 + DbgAction/dbg 助手）（源:文件:agent-lite/src/com/dsh/agentlite/MainActivity.java:284）
- 02:15 首次用正则按块删除切多了（编译报 237 错）→ 从上一次整合层副本恢复该文件（源:命令:diff 行数 797 vs 879）
- 02:17 改用**按精确行号剪切 + 边界断言**（删 76 行：284 / 303–309 / 315–377 / 386–390）（源:步骤2）
- 02:18 构建通过；APK 核验：btnDebug/btnDbgNodes/dbgScroll/llDebug/btnSettings 的 id 已从资源表彻底消失（源:命令:aapt2 dump resources）
- 02:19 装机实测：主页元素自上而下为 顶栏(设置) → 状态 → 账号 → 四磁贴 → 下达指令/语音/运行；启动 568ms、FATAL 0（源:命令:adb install + uiautomator dump）
- 02:20 终态: done

### 2026-09-13 · 会话交接 · 充值改版交接清单落盘
- 交接清单写入 docs/HANDOFF_2026-09-13.zh-CN.md：两个卡点 + 卡点未解时**可先做的活**（AliyunV3Signer：纯标准库、零 android 依赖、可桌面单测）（源:用户请求）
- 复核设计文档 vs 仓库：AliyunV3Signer/BailianClient/AkStore 三文件在 agent-lite 与 agent-unified 均不存在 → 确认「实现未开始」、文档无漂移（源:命令:glob+ls）
- 读出设计 4 处缺口（返回检测机制 / 回滚语义 / AK 录入路径 / use_gateway 口径）+ agent-unified 平行副本归属未定，已记入交接 §4（源:文件:docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md）
- 提醒：本日志节非严格时间序（01:42 blocked 夹在 02:20 done 之前），判状态请按时间戳（源:本文件）
- 遗留：手机无障碍绑定很可能被装机清掉需重开；DashScope Key 需轮换后更新 agent-lite/dev.local（源:用户请求）
- 终态: blocked（同 01:42：等套餐↔官方档位对应关系 + 需先 curl 打通 CreateApiKey）

### 2026-09-13 02:28 +08 · 并行会话 · 界面回归提交
- 02:28 提交 bc1fb13「feat(ui): 界面回归第一版 + 移除 Material 3 + 主界面精简 + 引擎震荡看门狗」（源:命令:git commit）
- 注：本条由并行会话写入，与本文件下方「会话续」条目同时进行；提交后工作区仍有 53 个改动文件（源:命令:git status）

### 2026-09-13 · 会话续 · 充值改版：签名器落地 + CreateApiKey 形状查实
- `AliyunV3Signer.java` 实现完成（纯标准库、零 android.* 依赖）；**V3 不派生密钥**，直接 HMAC-SHA256(原始 AccessKeySecret)，与 V2 的 `secret+"&"`+SHA1 完全不同（源:web:help.aliyun.com/model-studio/roa-style-request-body-signature-mechanism）
- 桌面 JVM 回归 `tests/AliyunV3SignerTest.java` **47/47 通过**（编码/规范化 URI/查询串/11 行 CanonicalRequest 形状/两级签名形状/**独立复算防自证**/篡改敏感性/**官方固定向量**）（源:命令:javac+java）
- Android 构建未破坏：APK 187,018 bytes、零编译错误；已核验两类确在 `classes.dex` 内（源:命令:bash agent-lite/build-mac.sh + unzip strings）
- **V3 不派生密钥**：官方固定向量 `(7ea06492… → 06563a9e…)` 字节级复算，原始 secret 命中；`secret+"&"`（V2 式）与 `secret+"ACS3"` 派生均**不**命中（源:web:aliyun 通用 V3 规范 + 命令:python hmac 复算）
- ★ **纠正一处我的误判**：先用 `bailian.aliyuncs.com` 探测，见"凭据被拒"就断定 host 正确——**错了**。该网关对 `2023-12-29`（明显非 ModelStudio 版本）也返回同一扁平错误码 `100016`，**根本不校验版本**，故无法证明任何事。改标准网关探测后定位真值：**`modelstudio.cn-beijing.aliyuncs.com`**（返回 `InvalidAccessKeyId.NotFound` + 响应体 `product=ModelStudio` = 已进到凭据查找阶段）。教训：「版本被承认」≠「路由正确」（源:命令:4 host × 2 version 矩阵实测）
- 其余 host 判否：`bailian.cn-beijing.aliyuncs.com` → `400 InvalidVersion`；`modelstudio.aliyuncs.com` → DNS 不存在（regional 规则要求带 region）（源:同上矩阵）
- `AkStore.java` 实现完成：AES-256-GCM、密钥由 Android Keystore 生成（刻意不要求用户认证，否则后台自动签名会失败）；只落 `ak_id` 明文 + `ak_secret_enc` 密文；**Keystore 密钥失效时自动清空待重录**而非抛异常/返回乱码（源:文件:agent-lite/src/com/dsh/agentlite/AkStore.java）
- **卡点 1.2 大部分结案**（关键突破：用**假凭据**探测，不需要真 AK）：
  · host=`bailian.aliyuncs.com`（假凭据返回"凭据被拒"）【**此结论已被上方"纠正一处我的误判"推翻，勿依据**】；带区域前缀的 `bailian.cn-beijing.aliyuncs.com` 返回 `400 InvalidVersion` **不可用**（源:命令:实测两次对照）
  · method=POST、path=`/modelstudio/apikeys`、version=`2026-02-10`、参数 `workspaceId`/`description`（源:web:error-center.alibabacloud.com meta/v1 API JSON）
  · 返回 Key 字段 = `apiKey.apiKeyValue`（源:同上 metadata）
- ★ **实测坑**：**认证失败也返回 HTTP 200**（`{"Code":"100016","Message":"API令牌认证失败","Success":false}`）→ 客户端必须校验 `Success==true` 且 Key 非空；只看状态码会把失败当成功、写空 Key 进 Prefs（源:命令:实测）
- `tests/verify_create_apikey.sh`：curl 打通脚本，含**跨实现固定向量自检**（python 侧必须与 Java 侧签名一致才发真实请求）；AK 只走环境变量、签名头不落临时文件、响应默认遮蔽 Key（源:命令:实测假凭据跑通）
- ★ **发现设计风险（待用户决策）**：官文明确写「资源包按模型名称严格匹配、跨版本或子型号不通用，qwen-plus 资源包不抵扣 qwen-max」；而本 App **不只用一个模型**（`plannerModel` 默认 qwen3.5-flash + `visionModel` + `asrModel`）→ 买单个模型资源包会导致「付了钱但 App 大部分调用仍走按量、余额不足即全挂」（源:web:help.aliyun.com/model-studio/savings-plan-and-resource-package + 文件:Prefs.java:97）
- 购买页定位：资源包在 `https://common-buy.aliyun.com/?commodityCode=sfm_llminference_spn_public_cn`（另有 dp/图像/语音/向量 rerank 等 commodityCode）；**该页需登录、无法用 URL 预选档位** → 只能做到"打开对应商品页"、用户自己选档（源:命令:curl 实测 http=200 落到登录页）
- 设计文档 §5.1/§5.2/§5.3 已按新事实重写；交接文档 §1.2/§1.3 已更新（源:文件:docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md）
- 终态: blocked（唯一卡点＝套餐↔官方商品页/档位对应关系；另需用户提供有效 RAM AK 跑成功路径）

### 2026-09-13 · 会话续2 · 充值改版：三项决策落定 + 核心三件套完成
- 用户决策三条：①**仍买单模型资源包，但把 App 固定为单一模型** ②购买页 `sfm_llminference_spn_public_cn` ③返回检测用 **ListApiKeys 比对前后差异**（不猜时间窗，消除假阳性）（源:用户请求）
- `BailianClient.java` 完成：调 CreateApiKey；**两条实测红线写死**——成功须 `Success==true` 且 `apiKeyValue` 非空（认证失败可能 HTTP 200）、只签 6 个确定性头（不签 user-agent/content-type，防库改写致签名不一致）；错误码翻成中文并区分 retryable（源:文件:agent-lite/src/com/dsh/agentlite/BailianClient.java）
- **自查并修掉一处自己引入的安全问题**：`buildRequest` 里 `new String(secret)` 会把密钥明文 String 化（String 不可清零）→ 改为 `char[]` 全程传递，`charsToUtf8` 逐码点手工编码不经 String，HMAC 后即刻抹掉字节；重构后 47/47 仍通过、签名值不变（源:文件:AliyunV3Signer.java + 命令:java 回归）
- 构建：APK 187,018 → **191,114 bytes**（+4KB = BailianClient）。三件套齐：`AliyunV3Signer` / `AkStore` / `BailianClient`（源:命令:bash agent-lite/build-mac.sh）
- ★ **架构冲突（待确认，未擅改）**：决策①要「固定单一模型」，但 App 现在 `MODEL=qwen3.5-omni-plus` 而 `plannerModel` 默认 `qwen3.5-flash`——**两个模型名**，资源包只能抵扣其一。已查实 `qwen3.5-omni-plus` 是**全模态**（原生支持图像/音频输入），故单模型方案技术上可行；但需改 `plannerModel` 默认值/`visionModel`/`asrModel`，属 App 核心行为变更，**已列为待确认项而非擅自实施**（源:web:help.aliyun.com/model-studio/qwen3-5-omni-plus + 文件:Prefs.java:97）
- 待办：PurchaseActivity 改造 + SettingsActivity「更改 API」入口 + AkStore 录入界面 + ListApiKeys 返回检测 + 单一模型收敛（源:步骤4）
- 终态: in_progress（核心可复用类已完成；UI 接线与单模型收敛待做；真机端到端仍需一把有效 RAM AK）

### 2026-09-13 · 会话续3 · 单模型模式实现（用户授权后）
- 用户授权实施「固定单一模型」。实现落点 `Prefs.java`：新增 `singleModelMode()`（默认 **true**），`plannerModel()`/`visionModel()`/`asrModel()` 在该模式下**一律返回主模型**（源:用户请求）
- ★ **自查并推翻了自己的第一版实现**：初版 `converge()` 遇到"用户配了非主模型"就放行 → 收敛**永不生效**、功能等于没做。已改为「单模型模式下一律返回主模型」，想让多模型就明确关开关（源:步骤2）
- **两条关键取舍**：①只覆盖返回值、**绝不动存档**（关掉开关原配置一字不差回来）；②`plannerModel()` 在该模式下返回**主模型而非空**——返回空会让 `AgentEngine:986` 直接跳过规划、等于**静默关掉功能**（源:文件:AgentEngine.java:986）
- ★ **发现并修掉一个会永久覆盖用户配置的坑**：设置页原先用 `prefs.plannerModel()` 回填 `etPlanner`，单模型模式下那返回主模型名，用户一保存就把主模型写进 `planner_model`。新增 `plannerModelRaw()`/`asrModelRaw()` 专供回填，`setPlannerModel()` 在该模式下改为 **no-op**（源:文件:Prefs.java + SettingsActivity.java）
- UI：`activity_settings.xml` 新增 `swSingleModel` 开关 + `tvSingleModelHint` 说明；开关**即时生效**（不必等保存），非主模型输入框置灰并说明「值还在、只是没生效」（源:文件:res/layout/activity_settings.xml）
- 核验（不靠"编译通过"下结论）：`swSingleModel`/`tvSingleModelHint` 资源 id 已进资源表；4 条中文 UI 串已进 `classes.dex`（UTF-8 存储，`strings` 默认不显示）（源:命令:aapt2 dump resources + python 二进制查找）
- 门禁：UI 文案合规 7+10 项全通过、控件转型一致性 0 失败、签名器回归 47/47（源:命令:bash tests/ui_text_guard.sh + ui_widget_guard.py）
- ★★ **安全发现（既有问题，非本轮引入）**：`apk_readability.py` 报**硬违规——APK 内含明文 API Key**。经比对，`classes.dex` 里那把 `sk-ws-H.PM…Stpk`（116 字符）与 `agent-lite/dev.local` 的 `API_KEY` **完全一致**（同前后缀同长度）= `build-mac.sh` 构建期把真密钥注入了 APK，任何拿到 APK 的人 `strings` 即可提取。与交接文档 2.2「Key 需轮换」是同一把（源:命令:python 前后缀比对 + tests/apk_readability.py）
- 对比度不达标 22 处与硬编码色 13 处**均为既有**（全在 `activity_settings.xml` 原有按钮上），本轮新控件用 `@color/color_text_hint` 资源引用，未新增（源:命令:python3 tests/apk_readability.py）
- 终态: done（单模型模式已实现并核验；下一轮＝PurchaseActivity 改造 + AkStore 录入界面 + ListApiKeys 返回检测）

### 2026-09-13 · 会话续4 · 装机核验 + 发布面泄露修复 + 无障碍误报修复
- 装机（OnePlus PKX110）：`install -r` 成功；**设备端 sha256 与源 APK 逐字节一致**（191,114 bytes）；冷启 474ms、零 FATAL；**登录会话保住**（主界面 `账号：tqym · 剩余 82 units`）；**无障碍绑定未被清**（settings 与 dumpsys 双向确认）（源:命令:adb install + sha256sum + uiautomator dump）
- 装机核验时发现设备上有**另一个会话**在操作（T+12s START SettingsActivity、T+13s START PurchaseActivity，非我下发）→ 已停止对设备操作避免干扰（源:命令:adb logcat ActivityTaskManager）
- ★★ **发布面泄露修复（第①项）**：`build-mac.sh` 改 **fail-closed**——dev.local 含非空 API_KEY 时**直接中止构建**（实测退出码 1），`DevConfig.API_KEY` 恒为空串（源:文件:agent-lite/build-mac.sh）
- ★ **同时堵住更隐蔽的洞**：`BASE_URL` 同样由 dev.local 注入，被改写即等于**把密钥主动发给攻击者**——故注释明确「只防 API_KEY 不够」（源:步骤2）
- 递归扫描（含 zip/tar 内部）发现真密钥共 **12 处**：`agent-unified/dist/*.apk!classes.dex`（发布副本）+ `agent-lite/build-m3/`（191M 历史产物，含源码副本 DevConfig.java 与编译类）。已全删，复查 **0 处残留**（源:命令:python zip/tar 递归扫描）
- 同步修复 `agent-unified/client/build-mac.sh`（同源副本）；端到端验证 unified 路径亦 fail-closed（假密钥 → 退出码 1）（源:命令:bash scripts/gen-client-env.sh + build-mac.sh）
- 未做「adb 自动推送密钥」：包为 release（无 `android:debuggable`），`run-as` 不可用；用 `am start` 传 extra 会把密钥暴露给 logcat/dumpsys 与任何已装应用——**拒绝为便利引入更难查的泄露**（源:命令:adb shell run-as 实测 not debuggable）
- ⚠️ **密钥仍必须轮换**：它已随多个 APK 存在过（含 dist 发布件），清本地文件不等于吊销（源:步骤3）
- ★ **无障碍误报修复（第②项）**：定位为**异步竞态**——`onResume` 只调一次 `refreshA11y()`，而服务重绑异步完成时横幅已画完、之后无人刷新 → 一直挂「无障碍未开启」，该帧截图会成为引擎首帧感知输入**误导模型**。修法：`A11yGuard` 新增 `Observer` + `notifyConnected()`，`AgentA11yService.onServiceConnected()` 广播真实恢复事件，MainActivity 在 `onStart` 注册 / `onStop` 注销（静态引用必须注销否则泄漏 Activity），抽出 `renderA11y()` 供重画（源:文件:A11yGuard.java + AgentA11yService.java + MainActivity.java）
- 诚实记录：装机后首次 dump 确曾见「无障碍未开启」，但**后续复现不出**（T+2/5/10s 均「无障碍已开启」）→ 未夸大为稳定缺陷，按竞态修复并说明（源:命令:uiautomator dump 三轮）
- 构建：APK 191,114 → 195,210 bytes；门禁 UI 文案 7+10、控件转型 0 失败（源:命令:bash agent-lite/build-mac.sh）
- 取到 `ListApiKeys` 元数据（第③项前置）：`GET /modelstudio/apikeys`，响应 `apiKeys[]` 含 `apiKeyId`/`apiKeyValue`/`gmtCreate`/`disabled` → **`gmtCreate` 可作前后比对时间锚点**（源:web:error-center.alibabacloud.com meta/v1 ListApiKeys JSON）
- 待办：设备掉线（仅剩手表 OVW261，用户明示不装手表）→ 新包待手机插回后装机；③PurchaseActivity 改造未开始（源:命令:adb devices）
- 终态: blocked（③未开始；新包待装机，手机不在线）

### 2026-09-13 · 会话续5 · 返回检测逻辑落地（第③项前半）
- `BailianClient` 新增 `list()`：调 `GET /modelstudio/apikeys`（`maxResults=100&orderBy=gmtCreate&order=DESC`），只取 `apiKeyId`/`gmtCreate`/`description`/`disabled`——**不落 Key 明文**（判定差异用不到，少碰一次少一次泄露机会）（源:文件:BailianClient.java）
- ★ **把判定逻辑抽成零依赖纯类** `PurchaseFlow`：原 `findNewKey` 嵌在依赖 `org.json` 的类里、**无法桌面单测**，而它决定「用户的钱到没到账」。抽出后可固定向量回归（源:文件:agent-lite/src/com/dsh/agentlite/PurchaseFlow.java）
- `tests/PurchaseFlowTest.java` 桌面 JVM 回归 **20/20 通过**：含 id 新增主路径、**列表无变化不判成功（消除假阳性）**、多新增取最新、无基线拒绝推进、id 全缺失时按 `gmtCreate` 兜底、**60s 时钟宽限**、有可比对 id 时不走时间兜底（源:命令:javac+java）
- **安全取向写死在代码与测试里**：假阳性（把"切出去看了眼微信"当付款成功）会让流程去创建用不上的 Key；假阴性只是让用户手点一次。两者代价不对等 → **一切模糊地带偏向保守**（源:文件:PurchaseFlow.java 类注释）
- 重构 `BailianClient` 复用 `PurchaseFlow.KeyInfo`，删掉重复定义（单一事实源）；构建通过 APK 195,210 bytes（源:命令:bash agent-lite/build-mac.sh）
- 待办：`PurchaseActivity` 改造（跳官方购买页 + 前后快照 + 自动创建 + 校验回滚）与 SettingsActivity「更改 API」入口；设备装机待手机插回（源:步骤5）
- 终态: in_progress（③已完成判定逻辑与单测，UI 改造未完成）

### 2026-09-13 · 会话续6 · PurchaseActivity 改造完成（第③项后半）
- `PurchaseActivity` 重写：套餐卡 → **打开百炼官方购买页**（`common-buy.aliyun.com?commodityCode=sfm_llminference_spn_public_cn`）；返回后**用 `ListApiKeys` 快照比对**确认官方侧真的新增了 Key，再 `CreateApiKey` 并写入 `Prefs.apiKey`。兑换码/下单/订单 UI 按决策移除（服务端代码保留）（源:文件:agent-lite/src/com/dsh/agentlite/PurchaseActivity.java）
- **三条红线逐条落实在代码里**：①只打开浏览器，付款那一下留给用户（不得 WebView 注入自动确认）；②AccessKey 只写 `AkStore`（Keystore 加密），Secret 用完即 `wipe`；③不猜「付款了没」，以官方侧差异为准（源:文件:PurchaseActivity.java 类注释）
- ★ **拿不到基线就不许推进**：进入购买页前必须先取快照，取不到直接报错拒绝（「没有基线时的新增判定是无意义的」），避免在无参照的情况下误判成功（源:文件:PurchaseActivity.java）
- UI：新增 `swSingleModelBuy`（充值页也能切单模型——用户是在这里花钱的，必须在此刻讲清「资源包按模型名匹配」）、`etAkId`（AccessKeyId 录入，已配置时掩码显示并锁定）；`etCode` 复用为 Secret 输入；「更换 AccessKey」走 `AkStore.clear()`（源:文件:res/layout/activity_purchase.xml）
- 自查修掉 3 处自己写的不干净处：`UiText.safe` 不存在（该类只有 uiReason/friendlyLabel/isMapped）、多余的 ListView 局部变量、名不副实的 `adoptExistingKey` 绕行（列表接口拿不到 Key 明文，绕一圈仍要创建）（源:步骤6）
- `SettingsActivity` 指向充值页的注释改为新语义（「更改 API」入口即该页的 AccessKey 录入区）（源:文件:SettingsActivity.java:133）
- 核验：构建 APK 199,306 bytes；门禁 UI 文案 7+10 全过、控件转型 0 失败；`PurchaseFlowTest` **20/20**、`AliyunV3SignerTest` **47/47**（源:命令:bash agent-lite/build-mac.sh + tests/）
- 待办：设备仍未接回（仅剩手表 OVW261，用户明示不装手表）→ **新包待装机**；真机端到端（官方购买 + 创建 Key）仍需一把有效 RAM AK（源:命令:adb devices）
- 终态: blocked（③代码完成且门禁全绿；仅差装机与真机验证，手机不在线）

### 2026-09-13 · 会话续7 · 门禁转绿 + 补齐网关让路与校验回滚
- ★★ **`apk_readability` 从红转绿**（对比度不达标 **23 → 0**，`无硬违规`，退出码 0）。三条根因逐条定位（源:命令:python3 tests/apk_readability.py）
  · **根因1（我引入）**：`tvSingleModelHint` 用既有调色板的 `#ff64748b`，仅 3.51:1 → `color_text_hint` 改 `#7C8CA3`→`#8494AB`（实测 4.49→达标）
  · **根因2（既有、11 个按钮）**：`btn_chip` 按下态底色 `#1d3557` 与文字 `#2563eb` 仅 **2.39:1**，**按下瞬间文字几乎不可见**。改为按下态与常态同底、只加粗描边（深色主题下"描边变亮"也比"底色变暗"自然）
  · **根因3（设计缺陷）**：同一个 `color_primary` 被当两种互斥用途——"深底上的白字背景"（需够深）与"深底上的文字色"（需够亮）。**拆成两个 token**（新增 `color_primary_text`），否则改一处必坏另一处（源:文件:res/values/colors.xml + activity_settings.xml 11 处）
- ★ **发现并修正门禁自身的隐患**：`apk_readability.py` 默认目标硬编码整合层 dist 路径，而该文件会被清理——**指向陈旧文件时会给出与当前源码不符的结论，比直接报错更危险**。改为按存在性优先取 `agent-lite/build/agent-lite.apk`（源:文件:tests/apk_readability.py:27）
- ⚠️ **Android 12+ 动态取色会覆盖固定值**：`values-v31` 把 `color_primary` 映射到 `system_accent1_200`（浅色），故固定值修复在真机被覆盖；已在 v31 同步补 `color_primary_text`=`accent1_200`、`color_text_hint`=`neutral2_300`。**门禁量的是静态值，真机走动态值**——这条差异已记录，未夸大（源:文件:res/values-v31/colors.xml）
- ★ **补齐两处我先前漏掉的未完成项**（复查目标时发现，非用户提出）
  · **网关让路**：`LlmClient.forPrefs` 原逻辑在已登录时总是优先走我方额度网关 → 购买流程写进 `Prefs.apiKey` 的 Key **永远用不上**。新增 `Prefs.useOwnKey()`，为 true 时直接走用户自己的 Key（源:文件:LlmClient.java + Prefs.java）
  · **校验 + 回滚**：原先 `CreateApiKey` 成功即写 Prefs 报成功。改为**先校验再写入**——顺序刻意：先写后校验一旦 Key 不可用，就会停在最坏中间态（标记官方购买、网关已让路、Key 是废的）。校验失败**不写 Prefs、不置位 useOwnKey**，保持改动前可用状态（源:文件:PurchaseActivity.java）
- 核验：构建 199,306 bytes；`validateKey`/`validateThenCommit`/`useOwnKey`/`use_own_key` 均已确认在 `classes.dex` 内；门禁 apk_readability 退出码 0、UI 文案 7+10、控件 0 失败；`PurchaseFlowTest` **20/20**、`AliyunV3SignerTest` **47/47**（源:命令:unzip+python 查 dex + tests/）
- 待办：**仅剩装机与真机验证**——手机仍未接回（仅手表 OVW261 在线，用户明示不装手表）；真机端到端（官方购买 + 创建 Key + 校验）还需一把有效 RAM AK（源:命令:adb devices）
- 终态: in_progress（三项代码全部完成且门禁全绿；差装机/真机验证，受设备不在线阻塞）

### 2026-09-13 11:19 +08 · 会话 local · 全面体检+改进路线入册
- 00:44 摸底 7 个子工程与 git 状态：56 M + 35 ?? 未提交（源:命令:ls/git status）
- 00:52 构建矩阵实测：三 APK 构建通过；服务端 go vet/build 两轮零告警（源:命令:build-mac.sh/go）
- 00:53 agentd-go 构建失败：go.mod 要 1.27；/tmp 改 1.24 实测编译+vet 通过（源:命令:go build）
- 00:57 密钥审计：源码扫描 0 命中；dev.local/.e2e/keystore 全部已 ignore（源:命令:grep/git check-ignore）
- 00:58 收 agent-client 全量审查 28 条（P0×4 逐条对源码复核属实），报告初稿落盘（源:文件:docs/PROJECT_REVIEW_2026-09-13.zh-CN.md）
- 11:16 复核并行会话夜间成果（工作日志+HANDOFF）：M3 回退、内购三件套、APK 明文 Key 泄露已修——复扫全部 build/dist APK 0 命中，git 降为 53 M + 21 ??（源:文件+命令:zipfile 扫描）
- 11:17 契约门禁实测红：内购文件未同步 unified 副本；服务端新增 5 条订单路由未进手册 §7（源:命令:verify-contract.py）
- 11:18 体检报告定稿（4 组件定向审查 + P0–P3 路线图）；本手册新增 §13 摘录（源:文件:PROJECT_OVERVIEW.zh-CN.md §13）
- 11:19 开发手册 §7 补 5 条订单路由（标注待线上确认）+ §11 收录体检报告与 HANDOFF（源:文件:docs/DEVELOPMENT_MANUAL.zh-CN.md）
- 11:19 终态: done（4 通道子代理送达异常，已以主审定向审查+全量实测替代并在报告 §7.5 声明）

### 2026-09-13 11:22 +08 · local · 体检 P0 修复与全量入库
- 11:24 新增 ActionValidator 白名单网关（pkg/url/action），AgentEngine 入口 + SandboxController 四处 root 边界双重强制（源:文件:agent-client/src/com/dsh/agent/ActionValidator.java）
- 11:24 ensureA11y 改先读后追加，用户已有无障碍服务不再被覆盖（源:文件:agent-client/src/com/dsh/agent/RootShell.java:117）
- 11:25 agent-client 构建通过，dex 常量验证修复进包（首验误用注释文本得 0，改字符串常量复验命中）（源:命令:build-mac.sh）
- 11:26 agentd-go go.mod 1.27→1.24，编译+vet 通过（源:文件:agentd-go/go.mod:3）
- 11:28 服务端二进制误入暂存 → git rm --cached + amend 出库，补 ignore 规则 *.class 与 agentlite-server 产物（源:命令:git rm --cached）
- 11:29 分批入库：69ddf0b 安全 / cf04f6c 工具链 / bd6dbb0 服务端首入 / 0cc7094 内购+测试 / 90c2ffb 文档规范（源:命令:git commit）
- 11:29 sync-sources 同步内购改动，契约门禁 26/26 绿，提交 2bc7683（体检 P1-4 消红）（源:命令:verify-contract.py）
- 11:31 手册 §13 与体检报告 §7.1 标注修复进展；apply.sh/rollback.sh 刻意留盘不入库（源:文件:PROJECT_OVERVIEW.zh-CN.md §13.1）
- 11:31 终态: done

### 2026-09-13 11:54 +08 · 项目:ios · 会话:local · 增加 iOS 端（账号+远程控制）
- 11:54 首次运行检查：读工作日志尾恢复上下文；核实无 iPhone 在线、线上服务端 18443 连接被拒（源:用户请求+命令:curl）
- 11:56 起本地服务端（自签证书 SAN 含 127.0.0.1）作为端到端靶机（源:命令:agentlite-server -addr 127.0.0.1:18443）
- 11:58 建 ios/ Swift Package：核心库不依赖 UIKit，Mac 上即可全量验证协议（源:文件:ios/Package.swift）
- 12:00 密码学交叉验证：Java 生成向量 → Swift PBKDF2/GCM 逐字节一致（首验手抄向量错 1 字符致红，改由脚本注入修正）（源:文件:_local/ios-e2e/java/EmitVectors.java）
- 12:02 ★ 端到端抓出 5 处真实缺陷：lan_key_params 实为 key_len、push/pull 实为 server_seq、ack 无 cancelled 状态、终态 ack 后指令被删（404）、先 ack running 会致指令永久卡死（源:命令:swift test --filter EndToEndTests）
- 12:05 按服务端源码逐条纠正模型与指令状态机；CommandInbox 改为「先执行后 ack 终态」（源:文件:ios/Sources/AgentLiteCore/{Models,CommandInbox}.swift）
- 12:06 修 pinner 空集合误报：环回联调改为不挂 pinner，端到端测试改走生产同一条 pin 校验路径（源:文件:ios/Sources/AgentLiteCore/HTTPClient.swift）
- 12:07 测试基线 42 项全绿（13 密码学+19 协议+10 端到端）；macOS 与 iphoneos SDK 双平台编译通过（源:命令:bash ios/build-mac.sh --e2e）
- 12:07 iOS 实现手册落盘（能力边界/实测契约/后台模型/安全红线/已知坑 18 条），本手册新增 §14（源:文件:docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md）
- 12:08 sync-sources 同步（含证书 SAN 文档改动），契约门禁恢复 26/26（源:命令:verify-contract.py）
- 12:10 复跑 CLI 全链路（pin/注册/whoami/devices/send/collect/watch）均实测通过，暴露 U3：下发方无法确认已终态指令（源:命令:swift run agentlite-ios）
- 12:12 CLI watch 对 404 改为人话解释；手册 §4.3/§8.2/§9 补齐回执通道缺口（源:文件:docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md）
- 12:13 用户指出本机 iOS 开发环境良好 → 复核：Xcode 26.6 + 模拟器运行时 26.4；无签名身份、无真机（源:命令:xcodebuild -version/simctl/security）
- 12:15 建 Xcode 工程（脚本生成 pbxproj，避免手写 UUID 出错）+ SwiftUI 四屏 + 后台刷新 + 本地动作；App 源码对 iOS 模拟器 SDK 类型检查通过（源:文件:ios/App/）
- 12:16 类型检查抓出 3 处真实缺陷：ContentUnavailableView 需 iOS 17（改自建 EmptyStateView）、BGTaskScheduler 签名错、HTTPClient.isLoopback 非 public（源:命令:swiftc -typecheck）
- 12:20 定位模拟器阻塞：SDK 26.5 > 运行时 26.4 → xcodebuild destination 不可用（simctl 仍可 boot，两工具口径不同）；命令行下载运行时 5 分钟无进展已中止（源:文件:docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md §8.3）
- 12:22 离线 32 项 + 端到端 10 项复测全绿；模拟器模式如实报阻因（源:命令:bash ios/build-mac.sh --e2e）
- 12:22 终态: done（阻塞项：iOS 26.5 模拟器运行时需在 Xcode GUI 安装；真机需登录 Apple ID）

### 2026-09-13 12:20 +08 · 项目:repo · 会话:c768e453 · 项目分离与会话项目标注
- 12:14 首次运行检查：读工作日志尾恢复上下文，确认多个并发会话共用一份日志且会话头无项目字段（源:文件:PROJECT_OVERVIEW.zh-CN.md:565）
- 12:16 落实项目分离：`docs/PROJECTS.zh-CN.md` 定 9 个规范项目 ID + 目录归属规则 + 「改哪里=属于哪个项目」判定表（源:用户请求）
- 12:18 扩展 `skills/project-work-log` 至 v1.1.0：会话头新增「项目:<id>」必填字段与 §2.5 项目标识；源副本与 ~/.dsh 安装副本逐字节同步（源:文件:skills/project-work-log/SKILL.md）
- 12:19 补三处发现入口：PROJECT_OVERVIEW 顶部提示块、DEVELOPMENT_MANUAL 标注所属项目、中英 README 文档导航（源:步骤2）
- 12:20 并发实证：本次追加前日志尾已被并行会话写到 12:22，而本机时钟为 12:20——同一文件内时间戳非单调（源:命令:date+读尾）
- 12:20 终态: done

### 2026-09-14 20:07 +08 · 项目:repo · 会话:local · 当前项目问题分析入册
- 20:02 首次运行检查：读取项目清单、手册、体检报告、Debug 报告与工作日志尾，确认本次改动属于共享文档项目（源:文件:docs/PROJECTS.zh-CN.md）
- 20:05 复核当前源码：确认 agentd-go 仍使用全地址监听且 API 无统一鉴权，agent-lite 账号令牌仍写入明文 SharedPreferences（源:文件:agentd-go/main.go:321-340；文件:agent-lite/src/com/dsh/agentlite/Prefs.java:257-293）
- 20:07 将 P0/P1/P2 问题、根因、最小修复与执行顺序写入本手册 §15（源:文件:PROJECT_OVERVIEW.zh-CN.md:372-431）
- 20:07 终态: done

### 2026-09-14 20:16 +08 · 项目:agentd +agent-lite · 会话:8db3ace6 · 按手册 §15 修复 P0/P1
- 20:16 首次运行检查：读手册 §15 与日志尾，确认按「P0 鉴权 → P1 令牌 → 门禁」顺序修复（源:文件:PROJECT_OVERVIEW.zh-CN.md:421-427）
- 20:18 P0：agentd-go 新增 auth.go（0600 随机令牌/常量时间比较/Host+Origin+方法+JSON 白名单），监听改绑 127.0.0.1（源:文件:agentd-go/auth.go）
- 20:19 WebUI 适配令牌：统一 fetch 封装、截图改 blob URL、/api/test 改 POST（源:步骤2）
- 20:20 验证：新增 Go 单测 22 项全绿；宿主端到端 401/403/405/415/400，lsof 确认仅监听 127.0.0.1（源:测试）
- 20:21 收口 §15.2-1/4/5：CAS 占位、配置锁内快照+原子落盘+哨兵、URL/端口/ASR 白名单（源:文件:agentd-go/main.go）
- 20:22 8081 桥：agentd-apk 绑回环 + bridge token，agentd-go 读取携带、读不到自动降级（源:文件:agentd-apk/src/com/dsh/agentd/AgentA11yService.java）
- 20:23 P1：新增 TokenStore（Keystore AES-GCM）+ Prefs 一次性迁移 + 登录失败不降级明文 + 关闭备份导出（源:文件:agent-lite/src/com/dsh/agentlite/TokenStore.java）
- 20:24 构建与门禁：agent-lite/agentd-apk 构建通过、sync-sources 已同步、make check 全绿（契约 26/26）（源:命令:make check）
- 20:24 手册新增 §15.4 修复实施记录与剩余待办（源:文件:PROJECT_OVERVIEW.zh-CN.md:429）
- 20:26 §15.2-2/3：LlmClient.cancel 断开进行中请求（取消不触发回退、不误报失败）+ Runner 任务级 context + postOnce 全路径 finally（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 20:28 复验：APK 重建、副本再同步、make check 全绿（Go 取消测试 / 契约 26-26 / JVM 67 项）；CHANGELOG 与手册 §15.4 更新（源:命令:make check）
- 20:29 冒烟复核抓到并修掉一处真缺陷：无请求体的 POST（/api/stop）被 Content-Type 规则误拒 415，改为「仅有 body 才要求 JSON」并补用例（源:命令:curl 冒烟）
- 20:31 真机接入（PKX110 / Android 16，客户端全程零 root）：覆盖安装后令牌明文→Keystore 迁移成功（密文 96 字符、明文键已删），App 保持登录（源:命令:adb+su 读 shared_prefs）
- 20:33 真机抓到缺陷①：Keystore 加密必抛 InvalidAlgorithmParameterException（默认拒绝调用方 IV）→ 改为 Keystore 生成 IV，AkStore 同源缺陷一并修（源:文件:agent-lite/src/com/dsh/agentlite/TokenStore.java）
- 20:37 真机抓到缺陷②：点停止后仍等满 15s connect 超时（disconnect 只对已建连接有效）→ 网络线程+200ms 轮询取消，复测 13s→约 1s（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 20:38 零 root 回归：setting 直达 WLAN 5 秒完成、多步任务正常、网关不可达自动回退直连生效、停止文案正确（源:测试）
- 20:40 定位环境阻塞：服务器 firewalld 未放行 18443/18444，真机账号功能不可用（服务端进程 health=200 正常）；已写入手册 §15.5（源:命令:ssh aliyun-ecs）
- 20:41 真机记录入册 §15.5，CHANGELOG 补两条 Fixed（源:文件:PROJECT_OVERVIEW.zh-CN.md:445）
- 20:42 授权后放行服务器 firewalld 18443/18444；逐层复验本机与设备均可达（18444 已放行但服务端未监听）（源:命令:ssh firewall-cmd）
- 20:42 端到端确认：设备侧同步/额度请求在服务端全部 200（含 /account/me），证明 Keystore 密文解密后的令牌可正常鉴权（源:命令:ssh journalctl）
- 20:42 终态: done

### 2026-09-14 20:33 +08 · 项目:agent-lite · 会话:local · 充值页布局改版
- 20:33 首次运行检查：读清单与日志尾，确认并发会话 8db3ace6 已 20:29 终态 done，可接手界面（源:文件:PROJECT_OVERVIEW.zh-CN.md:688）
- 20:34 定位缺口：activity_purchase.xml 是全 App 唯一 0 令牌布局（其余 6 页 28–205 处）（源:命令:grep @dimen/@color）
- 20:35 先读门禁口径再动手：字号 ≥12sp、触控 ≥44dp、对比度 AA ≥4.5:1（源:文件:tests/apk_readability.py:12）
- 20:36 重排为任务流分区（说明→①AccessKey→②单模型→③官方页购买→状态），拆开「兑换码框+两按钮」同行挤压（源:文件:agent-lite/res/layout/activity_purchase.xml）
- 20:37 修孤儿标题缺陷：旧链路「订单记录」标题无 id，Java 只隐藏 tvOrders → 改包 orderArea 一并隐藏（源:文件:agent-lite/src/com/dsh/agentlite/PurchaseActivity.java）
- 20:38 新建 bg_card_inner：嵌套卡片与外层同色看不出边界（档位卡片在③卡片内部）（源:文件:agent-lite/res/drawable/bg_card_inner.xml）
- 20:39 补克制动效：按压 0.97/120ms、状态淡入 180ms、曲线 cubic-bezier(.23,1,.32,1)，并尊重 ANIMATOR_DURATION_SCALE（源:文件:PurchaseActivity.java）
- 20:41 首构建暴露 PathInterpolator 包名写错（android.view.animation）；★ `| tail` 把退出码吃成 0、失败看似通过，改重定向取 $?（源:命令:sh build-mac.sh）
- 20:43 复构建通过（APK 203478B）；sync-sources + 契约 26/26 + make ui 全绿；apk_readability 字号/触控/对比度达标，硬编码色仅剩 1 处（assist 页，非本次）（源:命令:make ui）
- 20:44 发现真机在线（PKX110），但其时设备正被并发会话 8db3ace6 的零 root 回归占用，等到 20:42 它终态后才动设备（源:命令:adb devices+dumpsys）
- 20:46 充值页 exported=false 无法 am start（正确设计，不为验证而放开）→ 改走 App 内导航；几何体检 0 越界/裁字/重叠（源:命令:python3 tests/ui_layout_check.py）
- 20:48 ★ 真机抓到两处静态门禁查不到的缺陷：主题把按钮英文转全大写（「保存 ACCESSKEY」）、字体放大档位下 hint 被截断 → 补 textAllCaps=false + 把 hint 挪成可换行说明（源:文件:agent-lite/res/layout/activity_purchase.xml）
- 20:54 三改三验（重建→装机→截图比对）：大小写正确、hint 完整、几何 0 问题；契约与 make ui 复跑全绿（源:命令:make ui）
- 20:55 两类真机缺陷入 Hindsight（静态门禁盲区），供后续页面复用（源:记忆）

### 2026-09-14 23:24 +08 · 项目:repo · 会话:local · 低门槛守则与模型指导
- 23:24 复核当前用户旅程、动作协议、权限与模型配置，确认“极大降低技术门槛”尚未形成可验收守则（源:文件:agent-lite/README.md；文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 23:24 将低门槛审查、SPEC-UX-001、P0/P1 缺口与 DeepSeek 4.1 Flash 实施协议写入手册 §16（源:文件:PROJECT_OVERVIEW.zh-CN.md:478）
- 23:24 终态: done

### 2026-09-14 23:33 +08 · 项目:agent-lite · 会话:8db3ace6 · 按手册 §16 落地零门槛守则与 P0 修复
- 23:36 读手册 §16 与交互规范，确认按 §16.6 优先级 + §16.5 实施顺序推进（源:文件:PROJECT_OVERVIEW.zh-CN.md:478-559）
- 23:50 §15.2-9/10：新增 EngineLog（异步/丢弃计数/轮转/脱敏）+ 历史裁剪每步统一 + 任务 20 分钟与录音 30 秒上限（源:文件:agent-lite/src/com/dsh/agentlite/EngineLog.java）
- 00:10 SPEC-UX-001 落盘并入门禁；首次运行即暴露 UX-5/7/10 三项 FAIL（源:文件:docs/SPEC_UX_001.zh-CN.md）
- 00:20 P0-1：无凭据前置守卫 + 解决卡；真机实测零模型请求（源:文件:agent-lite/src/com/dsh/agentlite/MainActivity.java）
- 00:35 UX-10/UX-5：ActionSchema（SCHEMA_VERSION + back/home 注册 + 未注册动作回灌）与权限渐进申请（源:文件:agent-lite/src/com/dsh/agentlite/ActionSchema.java）
- 00:45 UX-7：done 最小复验（activePackage + 期望包名 + 前台变化证据，缺证据追问一次）（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 00:55 上游归因分通道 + 自备 Key 欠费自动改用账号额度；真机日志实证「改用备用通道」（源:命令:adb logcat）
- 01:20 P0-2：移除条款代点，改为暂停交还用户；门禁新增「条款不得代点」断言（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 01:35 首次说明补「数据去哪/谁在控制/怎么停」；真机回归通过、make check 全绿（26 契约 / 15 UX / 47+20 JVM）（源:命令:make check）
- 01:47 最终版装机：无崩溃、登录态保持、明文令牌键 0（仍在 Keystore）（源:命令:adb install）
- 01:47 终态: done（⚠ 上线阻塞：两条模型通道上游均欠费，详见 §16.7）
- 02:20 诊断通道欠费：网关上游与自备 Key 均为 dashscope 且都欠费（Arrearage），产品在真机不可用（源:命令:ssh journalctl）
- 02:35 探测 DeepSeek API：`deepseek-flash` 可用；默认吐 reasoning_content 导致 content 为空，实测仅 `thinking.type=disabled` 与 `reasoning_effort:none` 能关闭（源:命令:curl 参数矩阵）
- 02:50 服务端加 `-upstream-model` 与 `adaptRequest()`（模型映射 + 关思考），客户端 LlmClient 同步支持 deepseek（源:文件:agentlite-server/gateway.go）
- 02:55 部署：env 切 `api.deepseek.com/v1` + `deepseek-flash`，二进制备份后切换并重启；日志「额度网关已启用 → https://api.deepseek.com/v1」（源:命令:ssh systemctl restart）
- 03:00 网关端到端验证：客户端发 qwen3.5-omni-plus → 响应 model=deepseek-flash、content 直接为 JSON、计费 charged tokens=27 units=1（源:命令:curl 网关）
- 03:10 真机切到 DeepSeek 并跑通多步任务（未死循环、空节点进纯视觉降级）；跑「打开计算器」时点到应用商店，记为模型质量问题待调提示词（源:文件:/sdcard/Android/data/com.dsh.agentlite/files/engine.log）
- 03:20 make check 全绿（契约 26 / UX 15 / JVM 47+20）；手册 §16.8 记录接入过程，§16.7 阻塞项标记已解决（源:命令:make check）
- 03:20 终态: done

### 2026-09-15 06:30 +08 · 项目:agent-lite · 会话:8db3ace6 · 设备失联期：T2/T3/协议统一/发布整理
- 06:30 读手册 §16.9，确认设备已失联（adb devices 空），把工作范围收敛为「不需要设备且可自证」的部分（源:文件:PROJECT_OVERVIEW.zh-CN.md:633-677）
- 06:40 T2 落地：提示词补 6-9 条（app 优先/禁进商店/list_apps/失败必换策略/低风险不 ask）+ 引擎硬防线 isAppStoreForeground（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 06:50 T3 落地：open_url/open_api/纯视觉坐标点击的屏幕指纹弱期望验收器（expectEffect + NodeInfo.fingerprint）（源:文件:同上）
- 07:00 新增 tests/action_schema_guard.py：词表↔提示词↔exec↔agent-client 四方一致门禁；实测 8 通过/1 警告（agent-client 缺 list_apps/open_api/remote，已显式记录）（源:命令:python3 tests/action_schema_guard.py）
- 07:15 协议统一决策：lite 为唯一真身、agent-client 冻结为子集并纳入门禁（源:文件:tests/action_schema_guard.py）
- 07:40 T1 工具就绪：tests/task_baseline.py（20 任务 + 五类判定解析器 + --selftest 免设备自测通过）（源:命令:python3 tests/task_baseline.py --selftest）
- 08:00 门禁扩展后全量复验：SPEC-UX-001 17 通过、动作词表 8 通过、契约 26、JVM 47+20 全绿（源:命令:make check）
- 08:10 发布整理：7 个分层提交、工作区干净、打 tag v0.6.0-zerofriction 作可发布基线（源:命令:git log/tag）
- 08:20 手册 §16.10 记录本轮与未闭环项（源:文件:PROJECT_OVERVIEW.zh-CN.md）
- 08:20 终态: done（待真机：20 任务基线、UX-1 五分钟首成功、纯视觉/旋转/进程恢复回归）

### 2026-09-15 17:00 +08 · 项目:agent-lite · 会话:8db3ace6 · 真机高难度回归（8 场景）
- 17:05 基线补跑至 16/20（浅任务成功率 11/16=69%）：setting 类 1 步 8-10 秒；Calculator/Messages 超时、Apps 页 fail（源:文件:tests/results-baseline.jsonl）
- 17:20 新增 tests/advanced_regression.py（8 个难场景，带 --selftest 与超时兜底 force-stop）（源:文件:tests/advanced_regression.py）
- 17:35 难场景 5/8 通过：S1 多步导航+版本提取(6 步) ✓、S2 计算器 123×456=56088 ✓、S3 WiFi 状态汇报 ✓、S6 应用不存在**未误跳商店** ✓、S8 转账任务未执行支付 ✓；S4/S5/S7 失败（源:命令:python3 tests/advanced_regression.py）
- 17:45 定位 S4/S5/S7 根因：**账号额度耗尽**（界面 0 units、服务端 quota=-3、网关持续 402），任务在第一步即失败（源:命令:ssh journalctl + 界面 dump）
- 17:50 修复①：402 额度耗尽时改用**用户自配接口**（原设计刻意不回退会把用户卡死）；真机验证日志「账号额度不足，改用你自己配置的接口」→ 任务完成（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 17:55 修复②：setting 完成复验误判——ColorOS 设置页包名不含 "settings"（com.oplus.battery / com.oplus.wirelesssettings），改为「只要离开本应用即可」；复测不再误报（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 17:58 终态: done（未闭环：额度充值、S4/S5/S7 复测、纯视觉/分屏/敏感暂停回归）

### 2026-09-15 21:00 +08 · 项目:agent-lite +agentlite-server · 会话:8db3ace6 · 安全 P0 五项 + 充值改 DeepSeek + 光晕贴合
- 21:05 修安全审查 P0-A1：导出的 MainActivity 处理外部 task/auto_run → 任意 App 一条 intent 即可驱动无障碍代理；改为**进程内静态通道**，Intent extra 一律忽略（源:文件:agent-lite/src/com/dsh/agentlite/MainActivity.java）
- 21:20 修 P0-A2：无障碍节点无 isPassword 过滤（密码/验证码明文进提示词）；agent-lite 与 agentd-apk 的 walk() 均掩码 + NodeInfo 出口第二道兜底（源:文件:agent-lite/src/com/dsh/agentlite/AgentA11yService.java）
- 21:35 屏幕适配定位：Overlay 窗口 `getRoundedCorner()` 取不到值 → 光晕圆角长期走兜底，实测 **83px vs 屏幕真实 154px**（差 71px，即用户说的"不贴合"）（源:命令:截图像素反推 + dumpsys window displays）
- 21:45 修复：圆角改「系统资源 rounded_corner_radius → 短边 12% 兜底」+ 四角取最大 + 描边同心几何；复测 **83px → 152px**（误差 2px）（源:文件:agent-lite/src/com/dsh/agentlite/ScreenGlowView.java）
- 22:00 充值改版（用户决策）：默认充值对象由百炼改 **DeepSeek**（top_up + API Keys 引导 + 应用内校验）；DeepSeek 无自动建 Key 接口，故去掉 AK/SK 链路；顺带修掉界面**回显完整 Key**（源:文件:agent-lite/src/com/dsh/agentlite/PurchaseActivity.java）
- 22:10 服务端 P0-S1 强制计费：客户端带 `stream_options.include_usage:false` 曾可跳过流式计费 → 改为服务端强制 true + 修 null map panic；真机验证恶意请求仍计费（源:文件:agentlite-server/gateway.go）
- 22:12 服务端 P0-S2 赠送额度 500 → 100 units（注册零验证 + 每 IP 限流可绕，50 万 tokens 足以盗刷）（源:文件:agentlite-server/quota.go）
- 22:15 服务端 P0-S3 并发闸门（每账号 2 / 全局 16）：5 并发实测恰好 2×200 + 3×429，超支由"无上限"变为有界（源:命令:ssh 并发 curl）
- 22:18 终态: done（未做：P1 若干——api_key/lan_key 迁 Keystore、ActionValidator、ask 超时视为拒绝、IME 权限升 signature、锁屏通知 VISIBILITY_SECRET、剪贴板清理、agent-client 关 allowBackup）

### 2026-09-15 18:00 +08 · 项目:agent-lite +agentlite-server · 会话:8db3ace6 · 双端/连接测试 + 手表与跨设备
- 18:05 默认上游改 DeepSeek（用户决策）：dev.local / build-mac.sh 默认改 api.deepseek.com + deepseek-flash；
  Prefs.useOwnKey() 默认 true（填了 Key 就走自备）；forPrefs 收紧为「有 Key 才走自备」（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 18:15 双端门禁全绿：契约 26/26、副本一致、SPEC-UX-001 17/0/1、动作词表 8/1/0、agentd Go 单测、JVM 47/47（源:命令:make check）
- 18:25 服务端 15 项接口冒烟全通过（含未绑定设备 403、未知路由 404、无令牌 401）；计费与闸门复验通过（源:命令:ssh curl）
- 18:35 真机↔服务端连接测试：18443 OPEN、额度刷新 0/-3 与服务端一致、服务端日志见 sync/push+pull+commands+account/me 全 200（源:命令:ssh journalctl）
- 18:45 手表（OWW261 / Android 11 / 466x466）接入：装上最新 APK、开启无障碍（adb settings 写入，服务已绑定）、
  手表版极简布局正常；手机端以 wm density 模拟手表形态同样进入手表版布局（源:命令:adb）
- 19:00 跨设备链路（服务端层）双向验证通过：手表→手机下发、手机拉取、ack 回执、给自己下发 400、跨账号 403、
  限流第 11 条被拒、5 分钟过期（源:命令:ssh curl 脚本）
- 19:30 ⚠ **真机跨设备测出产品级缺陷**：手机登录测试账号后，我用 curl 以同账号再登录取令牌 →
  服务端每次登录都**轮换令牌**（`account.go` LoginAccount：`delete(s.acctByToken, a.TokenHash)` + 换新 hash），
  于是手机的令牌立刻失效，轮询持续报 `HTTP 401: invalid_token`，跨设备指令永远收不到。
  → **同账号第二台设备登录会踢掉第一台**，而跨设备要求同账号 ⇒ 当前令牌模型下跨设备不可用（源:文件:agentlite-server/account.go:42-51；源:命令:adb logcat AgentLiteRemote）
- 19:45 终态: done（待办：令牌模型改造——多令牌/设备令牌 + 吊销入口；手表切账号后再做真机双向）

### 2026-09-15 18:10 +08 · 项目:agentlite-server +agent-lite · 会话:8db3ace6 · 多令牌改造与跨设备真机验证
- 18:10 令牌模型改造（跨设备前提）：Account.TokenHash 单令牌 → Tokens 多令牌数组 +
  addTokenLocked（登录**追加**而非替换，上限 10 把淘汰最旧），旧数据读取时自动迁移
  （源:文件:agentlite-server/account.go）
- 18:20 线上验证：同账号连续登录 3 次后，**第一把令牌仍 200**（改造前第二次登录即 401）（源:命令:ssh curl）
- 18:30 真机跨设备（手表→手机）**端到端成功**：以同账号第二台设备身份下发指令，
  手机 20s 内领取并执行，回报 `done：已打开系统设置的「日期和时间」页面`（源:命令:adb logcat AgentLiteRemote）
- 18:35 修可用性缺陷：令牌 401 后 `loggedIn()` 仍为 true → LoginActivity 直接 finish()，
  用户点「重新登录」被弹回失效页、**永远无法自救**；改为失效即不计登录态，并更新过时文案
  （多令牌后"旧令牌立即失效"已不成立）（源:文件:agent-lite/src/com/dsh/agentlite/Prefs.java）
- 18:40 修复后实测：App 直接进入登录页（不再卡死），重登成功、轮询自动恢复（源:命令:adb dump）
- 18:50 反向（手机→手表）未完成：任务未成功启动（engine.log 为空，疑运行按钮坐标/输入未生效），
  待下轮重试（源:命令:adb）
- 18:50 终态: done
- 19:15 反向测试（手机→手表）结论修正：任务**确实执行了**，但模型把「Ask the WatchSim device to open the Calculator」
  理解成**在本机打开计算器**（真机界面为计算器数字键盘），没有发出 remote 动作
  → 链路本身没问题，卡点在**模型是否选择 remote**（需提示词/触发条件强化，见 §16.9 T2 同类问题）（源:命令:adb dump）
- 19:20 终态: done（待办：remote 触发提示词强化；真手表双向待手表设备稳定后补测；安全审查 P1 若干）
- 19:40 remote 触发提示词强化：明确「任务出现同账号设备名/型号/序号并要求该设备做事 → 必须用 remote，
  绝不本机执行」（中英双语示例）（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 19:45 真机复测暴露下一环：模型已正确走 remote，但报「设备列表中找不到 WatchSim」；
  定位 `refreshDevices()` **没有任何调用点** → peer_devices 缓存永远为空 → 跨设备注定失败（源:文件:agent-lite/src/com/dsh/agentlite/RemoteCommand.java）
- 19:50 修复：轮询线程每 3 轮（约 60s）刷新一次同账号设备列表；真机日志「同账号设备列表已刷新：2 台」（源:文件:同上）
- 19:55 **反向跨设备（手机→手表）真机验证成功**：手机日志「任务完成：已向设备 WatchSim 下发「打开计算器」指令
  （指令号 c_d45dc301989364f4）」，目标设备侧拉取到该指令（from=手机 device_id）
  ⇒ **双向跨设备链路均已打通**（手表→手机：执行并回报 done；手机→手表：下发并被拉取）（源:命令:adb + ssh curl）
- 20:00 终态: done
