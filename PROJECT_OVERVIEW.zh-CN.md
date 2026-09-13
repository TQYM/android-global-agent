# Android Global Agent — 项目全景文档

> 本文档供后续 AI 接手时快速了解项目现状、技术决策与已知边界。
> 最后更新：2026-09-13（新增 §13 全面体检结论与改进路线；正文 §1–§12 仍以 2026-09-06 为基准，刷新列入待办）

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

## 工作日志

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
