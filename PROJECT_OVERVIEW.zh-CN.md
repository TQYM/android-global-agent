# Android Global Agent — 项目全景文档

> 本文档供后续 AI 接手时快速了解项目现状、技术决策与已知边界。
> 最近增量更新：2026-10-07（接续 10-06 审计，实现 T01 凭据行为回归切片与 T02 路由修复）。历史正文、审计与实测记录保留；§1–§12 主要描述早期完整版，不直接代表当前 Agent Lite 配置或验证结论。
> **最新实施状态先读 [§18.6](<#186-t01-凭据切片与-t02-路由修复2026-10-07>)；整体阶段仍见 §18，未来开发见[整合规划 §23](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md#23-面向老年人与手机入门者的开发续篇2026-10-06>)。**10-06 轮仅文档；本次已获准修改测试和代码，未安装、部署或发布。

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

> **边界声明（2026-09-21）**：本节描述的是 **root 专属的「可选增强」能力**，启停不影响主链路。
> 下文「为什么必须 root」只解释**这个可选能力本身**为何需要 root，
> **不构成任何功能 / 构建 / 测试 / 发版对 root 的依赖**（全项目底线见 §12.1）。

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
| 无线 adb 报 `No route to host`，但 `nc -z IP 端口` 明明是通的 | adb server 缓存了上一次的失败状态（2026-09-18 两次踩到，手机换无线调试端口后尤其容易） | `adb kill-server && adb start-server` 再 `adb connect IP:端口`，一次即通 |
| 语音按钮录音正常、就是识别不出字 | ASR 随 LLM 上游换成 DeepSeek 而失效（无音频能力）；即便指向明文 http 端点，Android 也会拦 `Cleartext HTTP traffic` | 见 §16.14：独立 ASR 端点 + `tools/asr_start.sh` + network_security_config |
| **主界面/设置页显示「剩余 0 units」，账号其实还有额度**（2026-09-21 真机实测：服务端 `quota=144`，界面显示 0） | 服务端 `quota` 语义是**剩余额度**（`quota.go` 每次调用执行 `Quota -= units; QuotaUsed += units`），客户端却在 5 处按 `quota - quota_used` **二次扣减**再 `max(0,…)` | **待修复**（本轮设备验证只记录不改码）：剩余额度直接取 `quota`，`quota_used` 仅作「累计已用」展示。见 §17.2 缺陷① |
| **点输入框后「运行」按钮点不到、输入内容也看不见** | `AndroidManifest.xml` 未声明 `windowSoftInputMode`；软键盘弹出后覆盖输入区（实测键盘顶 ≈y1180，输入框 `1536–1757`、运行键 `1562–1730` 全在覆盖范围内） | **待修复**：声明 `adjustResize`（或其他让输入区随键盘上移的方案）。见 §17.2 缺陷② |
| 真机验证期间 App 反复报「无障碍服务掉线」并执行 L1 抖动修复 | 主机跑 `uiautomator dump` 会拉起 `com.android.commands.uiautomator` 抢占无障碍绑定，与 AgentLite 服务互斥 | **测试设施问题，非产品缺陷**：验证期间改用 `screencap`，静默 60s 实测零掉线。见 §17.3 |

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
- [x] **模糊语义理解与流程转译 P0**（2026-09-18）：`planTask` 由 3 行散文升级为结构化 `FlowSpec` 转译
  （新增 `TextNormalizer` / `FuzzyFlow` / `SemanticTranslator`，含 token 预算与截断修复）。
  `make check` 全绿（转译门禁 22 项 + 桌面回归 85 断言），**Android 14 虚拟机 4 场景端到端通过**。
  设计见 `docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md`（附录 D 为实施记录）。
  **待办**：① 真机 + 真 Key 验证「真实模型能否稳定输出合规 FlowSpec」（最大遗留风险）；
  ② P1 澄清卡片 / P1 实体账本
- [x] **操作记忆库（原 P2「流程库复用」）**（2026-09-19）：每一步成功执行的操作自动入库
  （`FlowPlan` 纯逻辑 + `FlowMemory` 落盘，`filesDir/flowmem.json`），同一句话下次**直接复现、零模型往返**；
  复现失败即贬值并回退模型规划。**默认开启**，记忆库页可查 / 可删 / 可清空 / 可关。
  设计见 `docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md` §4.4.1、§5.4.4、附录 E。

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
- **底线：全项目禁止依赖 root**（详见 §12.1，原文「root 是可选加速器」已升格）

### 12.1 底线规则：全项目禁止依赖 root

> **升格记录（2026-09-21）**：原表述「root 是可选加速器，**永远不是零 root 路径的依赖**」只覆盖客户端执行路径，
> 现扩为**全工作区底线**——覆盖 `agent-client` / `agent-lite` / `ios` / `agentlite-server` / `agent-unified` /
> `agentd` / `harmony-agent` / `repo`（含构建、部署、测试、文档、发布说明）。
> **同步承载**：仓库根 `AGENTS.md`（会话强制加载），两处内容冲突时以本节为准。

**语义（唯一定义）**：**零 root 是唯一必需基线**；root 只能是「**可用则用、不可用则无损降级**」的可选增强。

**禁止（MUST NOT，违反即缺陷；不因「开发机/目标机已 root」而豁免）**：

1. **前置条件化**：任何功能、构建、安装、部署、测试、发版不得以 root（`su`、`app_process`、系统签名、
   `adb root` 等）为前置条件。无 root 设备必须能走通主链路：安装 → 登录 → 建任务 → 执行完成。
2. **唯一路径化**：不得让任何能力只有 root 一条路径。root 路径必须配零 root 等价路径或**明确降级**；
   降级要静默切换且日志可见，**不得静默失效**。
3. **命令拼接**：禁止把模型输出/外部输入拼进 `su -c` 等 shell（提示注入 = 任意命令以 root 执行，见 §13.1 P0-2）；
   root 通道只允许**白名单固定命令 + 参数校验**。
4. **文档与门禁**：README / 手册 / CI / 发布说明不得把 root 写成「要求 / 依赖 / 前置」；
   不得让「设备未 root」导致门禁或测试失败。
5. **未登记新增**：新增 root 专属功能**必须先在下方例外表登记**，并写明零 root 影响面与降级方案；未登记即违规。

**允许（MAY）**：root 作为可选加速器存在——§6 零 root 能力矩阵是**基线能力**，§7 沙盒模式是 **root 专属可选能力**。
前提是「无 root 时能力可缺失但**不阻塞主链路**，且缺失对用户与日志**可见**」。

**例外登记表（现有 root 相关实现，一律按「可选增强」对待；新增即登记）**：

| 位置 | root 用途 | 零 root 路径 | 判定 |
|---|---|---|---|
| `agent-client`（`RootShell` / `SandboxController`） | 沙盒虚拟屏、a11y 保活、root 文本注入 | §6 无障碍矩阵（`root_mode=off` 全功能） | 合例（P0-2 注入面已加白名单） |
| `agent-lite`（`RootShell.java:44/62`，`Prefs.rootMode()` 默认 `auto`） | 手势/注入的 root 加速分支、`root_mode` 上报 | 无障碍通道即主路径 | 合例，**待补**「无 root / `root_mode=off`」显式回归 |
| `agentd`（`agentd-go` + `agentd-apk`） | 守护以 root 运行、a11y 桥 8081、WebUI | 当前**无**零 root 形态 | **待评估**：补零 root 形态，或明确定位为 root 专属组件并在文档声明 |
| `tools/set-glm-key.sh:60-69` | `su -c` 改写设备 prefs 写入 ASR key | 可改走应用内设置界面写入 | 合例（运维脚本，非产品路径） |
| `tools/harmony-agent` | HDC 外挂通道 | — | 合例（不要求设备 root） |
| `tests/integrated_features_e2e.py` | 通过 `su -c` 读写 `agent-lite` 私有 prefs/flowmem，构造集成 E2E | `tests/flow_memory_e2e_mock.py` 的设备路径、应用内测试入口或导出的测试接口 | **不合例：不得作为必跑基线；当前仅能标为 root 专属增强/待改造** |

**验证（SHOULD，作为门禁候选）**：
无 root 设备（或 `root_mode=off`）跑通主链路 = 硬门禁项；文档层面扫 README / 手册 / 门禁脚本，
确认不存在把 root 写成前置换取的表述。

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

### 16.12 更新后项目问题复盘与指导（2026-09-15 17:37）

本轮相较 §16.9 的状态已有实质推进，但项目仍不应直接宣称“零门槛完成”或“全场景可用”。当前结论应以本节为准；§15 中早期的“仍未修复”条目如果与 §16.7–§16.11 冲突，以最新实测记录为准。

#### 已确认完成

- 低门槛规范、静态门禁、首跑无凭据拦截、失败卡片、渐进权限、动作版本和部分完成复验已经落地。
- DeepSeek `deepseek-flash` 已接入网关，思考链关闭，服务端模型映射和计费链路已经实测。
- DeepSeek 错误策略已有明显改善：应用不存在时不会误跳应用商店；计算器运算、多步导航、Wi-Fi 状态和跨设备手机→手表链路已通过实测。
- agentd root API、账号令牌、密码节点脱敏、流式计费、并发闸门和外部 Intent 驱动等高风险问题已有修复记录。
- 发布基线 `v0.6.0-zerofriction` 已建立，`make check`、契约门禁和动作协议门禁通过。

#### 当前仍存在的主要软件问题

1. **模型/环境失败归因仍可能混淆（P1）**：此前 S4/S5/S7 的失败表面像模型失败，实际是账号额度耗尽；虽然现在支持 402 后切换自备接口，但额度、通道、模型质量和设备感知仍需在用户界面分开显示。建议把失败分类固定为“额度/网络/模型决策/设备能力/用户确认”，并在历史记录中保存分类字段，不要只保存一段文字。
2. **DeepSeek 低额度情况下的回退语义需要产品化（P1）**：账号额度耗尽后回退自备接口可以恢复任务，但可能让用户误以为仍在使用账号额度。必须在执行前和切换时明确显示当前通道、费用承担方和 Key 来源；未配置自备接口时只给充值/稍后处理，不要反复重试。
3. **跨设备只有单向闭环（P1）**：手机→手表已取得服务端审计、目标前台包名和日志三重证据；手表→手机仍被无障碍自动重绑、输入通道和 USB 调试状态阻塞。应把“设备在线、无障碍已连接、能输入、能回报”做成发送前能力探测；能力不足时提前拒绝并给出人话操作，不要发送后才 ack failed。
4. **真机基线仍未完成（P1）**：20 任务曾达到 16 条采集、浅任务 11/16=69%，难场景 5/8 通过，但部分失败先由额度耗尽造成，修复回退后 S4/S5/S7 尚未完整复测。因此不能用旧批次成功率代表当前 DeepSeek 质量；应清额度、固定模型和配置后重跑完整 20+8 场景。
5. **完成复验仍覆盖不全（P1）**：`app`、`setting`、部分 URL/API 已有弱期望验证，但跨设备、搜索、输入、拨号、支付前暂停等仍需要各自的终态证据。尤其不能把“请求已发出”“远端已领取”当成“用户目标已完成”。
6. **敏感操作遗留项仍未关闭（P1）**：当前日志仍列出 `ask` 超时处理、IME 权限、剪贴板清理、`api_key/lan_key` 加密、锁屏通知可见性和 `agent-client` 备份策略等待办。它们直接影响普通用户的信任和凭据安全，应从“后续优化”提升为发布前清单。
7. **多项目发布状态仍有漂移风险（P1）**：源码工作区有大量并行修改与新增文件；`agent-lite`、`agent-unified/client`、服务端、APK 和 tag 必须绑定同一个发布清单。建议每次发布生成 `release-manifest.json`，记录 commit、tag、APK SHA-256、服务端二进制 SHA-256、模型名和配置摘要（不含密钥）。
8. **工具链验证存在环境阻塞（P2）**：本轮执行涉及 iOS 检查时被 Xcode license 未接受阻断（退出码 69）。这不是业务缺陷，但必须让 `make check` 将 iOS 环境缺失标为明确 `MANUAL/BLOCKED`，不能混入 Android/Go 通过结果。
9. **文档优先级存在重复和过期表述（P2）**：§16.6 的旧优先级仍把已经完成的 SPEC-UX、首跑拦截和 DeepSeek 接入列为待办；建议改成“已完成/未完成”状态表，并以 §16.12 为当前索引，避免后续模型按旧章节重复劳动。

#### 下一轮实施顺序

1. **先重跑完整真机基线**：额度充足、固定 `deepseek-flash`、固定账号和设备，重跑 `task_baseline.py`、`advanced_regression.py`、`cross_device_e2e.py`；输出成功率、误跳率、重复率、耗时、通道和失败分类。
2. **再处理发布前安全遗留**：优先 `ask` 超时=拒绝、剪贴板终态清理、`api_key/lan_key` Keystore 化、锁屏通知脱敏；每项均需源码门禁+真机验证。
3. **补能力探测和失败归因**：跨设备发送前探测 a11y/输入/在线/回报能力；统一 UI、日志和历史记录的失败分类。
4. **最后整理发布清单**：更新过期章节，核对 `agent-lite` 真身与 unified 副本，重新生成 APK/服务端哈希，并确认 tag 不落后于源码。

#### DeepSeek 4.1 Flash 继续指导

- 不要用增加 `max_tokens` 解决决策错误；Flash 的输出应保持短、单动作、强约束。
- 继续保留“应用启动优先 `app`、禁止商店绕行、动作后验证、失败换策略”的硬规则；可把常见应用映射和设备能力作为引擎注入事实，不让模型猜。
- 规划模型暂时不要与执行模型分叉；先用同一 `deepseek-flash` 建立可重复基线，再评估专用规划模型。
- 每条任务日志必须同时记录：模型输出、引擎拒绝/改写、实际执行、复验结果和最终失败分类；否则无法判断是模型问题还是软件问题。
- 只把“用户目标已验证”定义为成功；模型说 `done`、HTTP 200、指令已下发、页面发生变化都只能算中间证据。

### 16.13 极细复盘：更新后仍需优先修复的问题（2026-09-16 18:57）

本节只记录本次逐文件复核后仍可确认的风险，不重复把已修复事项列为待办。判断依据：最新工作日志、当前源码搜索结果、已记录真机证据和门禁结果。Xcode 检查因未接受 license 退出码 69，属于环境阻塞，不等同于 Android 功能通过。

#### P0/P1 发布前问题

1. **用户 API Key 仍是明文 SharedPreferences（P1）**
   - `agent-lite/src/com/dsh/agentlite/Prefs.java:29,65,74` 仍直接读取/写入 `api_key`；现有 `TokenStore` 只覆盖账号令牌。`PurchaseActivity` 虽然不再完整回显 Key，但存储本身仍可被 root、备份或调试路径取得。
   - 最小修复：新增独立 `ApiKeyStore`，使用 Keystore AES-GCM；迁移成功后删除旧 `api_key`，Keystore 失败禁止退化明文；设置页只显示掩码，统一清除和换 Key 流程。

2. **局域网密钥仍是明文 SharedPreferences（P1）**
   - `LanSync.java:102,110` 将 `lan_key` Base64 明文落盘；该密钥用于设备间加密，泄露后可解密/伪造局域网同步流量。
   - 最小修复：复用 Keystore 封装存储二进制密钥，迁移完成后删除明文；补重启、升级和跨设备互通测试。

3. **`ask` 超时仍可能回灌为普通模型输入（P1）**
   - `AgentEngine.askUser()` 超时返回“用户未回答或超时”，调用方随后把返回值加入下一轮消息；模型可能把“未回答”误当成许可或继续执行。
   - 最小修复：超时返回结构化 `USER_TIMEOUT`，立即进入暂停/拒绝终态；只有用户明确提交的回答才允许进入决策上下文。超时验收必须证明敏感动作未执行。

4. **Lite 的 `open_url` 仍需确认与冻结完整白名单（P1）**
   - 当前 `AgentEngine` 执行 URL 的入口虽已在部分路径加校验，但 Lite 与冻结版 `agent-client` 的校验实现不完全同源；URL/自定义 scheme 是提示注入和误触外部应用的高风险边界。
   - 最小修复：共享纯 Java `ActionValidator`；默认只允许 `https/http`（http 仅本机白名单）与明确登记的 scheme；拒绝 `javascript:`, `file:`, `content:` 和嵌套控制字符；门禁逐项测试。

5. **前台通知仍可能泄露任务内容或状态（P1）**
   - `KeepAliveService` 部分通知使用 `VISIBILITY_PUBLIC`；任务标题、步骤或账号状态可能出现在锁屏。项目自身已将部分 Guard 通知设为 `SECRET`，但两处策略不一致。
   - 最小修复：默认 `VISIBILITY_SECRET`，锁屏只显示“Agent 正在运行”；用户主动解锁后才显示任务摘要。补 Android 版本和厂商锁屏实机检查。

6. **剪贴板残留需确认 Lite 与冻结客户端一致（P1）**
   - 当前证据显示冻结 `agent-client` 已有 `clipboardDirty` 和终态清理，而 Lite 代码仍存在剪贴板写入路径；跨副本搜索不能把 agent-client 的修复当成 Lite 已修。
   - 最小修复：Lite 写入时标记 dirty，任务所有终态（done/fail/stop/exception/timeout）统一 `finally` 清理；只清除本引擎写入的内容，不能误删用户剪贴板；补真实密码/验证码任务验证。

7. **跨设备发送前能力探测不足（P1）**
   - 手机→手表已验证下发/拉取，但手表→手机曾受 a11y、输入和 USB 调试状态阻塞；如果发送端只看设备列表在线，仍可能发送后才失败。
   - 最小修复：设备心跳上报 `a11y_connected`、`can_input`、`can_execute`、`last_seen` 和客户端版本；发送前短路能力不足，并给用户可执行修复步骤。

8. **服务端注册防刷仍偏弱（P1）**
   - `agentlite-server` 仍以 IP 滑动窗口限注册；IPv6、代理池和分布式来源可绕过。注册赠送额度会消耗真实上游 Key，存在批量盗刷风险。
   - 最小修复：设备/账号/安装实例多维限流，注册挑战或邮箱/设备证明（按产品隐私要求选择）；对单账号每日上游预算设硬上限，并在异常时自动停发额度。

9. **服务端额度扣减需要幂等和故障补偿审计（P1）**
   - 网关先读上游响应再扣额度；若扣账落盘失败或响应重试，可能出现“上游已收费、平台未扣”或重复扣账。日志有错误记录，但未见面向请求的幂等 ID/补偿状态。
   - 最小修复：为每次请求生成 `request_id`，记录上游结果、usage、扣账状态和重试次数；扣账采用幂等键，失败进入可重放的 pending ledger，不静默丢失。

#### P2 功能与工程问题

10. **发布清单与工作区事实不一致风险**：当前状态存在大量修改/新增文件，且 `git status` 检查本身受 Xcode license 命令链干扰；应拆分读取命令，不要因组合命令失败而误判仓库状态。发布时锁定源码 commit、tag、APK/服务端 SHA-256 和模型配置摘要。
11. **iOS 环境检查应隔离**：Xcode license 未接受导致退出码 69；`make check` 应把 iOS 检查独立为 `make ios-check`，主 Android 门禁不应被无关 Apple 环境阻断。
12. **任务历史缺少机器可检索失败分类**：目前历史和日志以文本为主；应增加 `failure_class`、`channel`、`model`、`step`、`request_id`、`evidence` 字段，供基线工具准确统计。
13. **成功率数据可能混入环境假失败**：`force-stop` 会清理无障碍绑定，历史记录已有假性 timeout；测试脚本虽增加补绑定和分类，报告必须分离 `env:a11y`、`channel`、`model_or_env`，不能合并成模型成功率。
14. **设置页仍暴露过多技术配置**：低门槛守则虽已建档，普通用户仍可能看到模型、同步地址、Token 统计、API Key 等高级项；应默认折叠，并显示当前通道/费用承担方，而不是内部参数。

#### 面向 DeepSeek 4.1 Flash 的极细指导

- **输入**：每轮只注入最新快照、目标、完成判据、当前设备能力和上一动作结果；历史只保留必要的失败摘要，避免把过多日志交给 Flash。
- **决策**：应用启动优先 `app`；设备名/型号/序号命中时强制 `remote`；包名不明先 `list_apps`；不允许应用商店搜索/安装替代“打开应用”。
- **输出**：只允许一个 JSON，动作/字段/范围由引擎校验；建议动作绑定 `snapshot_id` 和 `expected_effect`，非法输出最多回灌一次。
- **敏感**：支付、转账、发送、删除、协议授权、跨设备敏感动作由引擎强制 `ask`；模型不得通过解释文字绕过。
- **完成**：`done` 必须绑定证据；HTTP 200、动作发出、远端领取、屏幕变化均只能算中间证据。
- **评估**：固定模型 `deepseek-flash`、固定温度和最大输出，分开统计模型错误、环境错误、通道错误和用户拒绝；不要用混合失败率调提示词。

#### 当前执行顺序

1. 先修 `ApiKeyStore`、`lan_key`、`ask` 超时、剪贴板清理和锁屏通知脱敏。
2. 再做跨设备发送前能力探测、注册防刷和额度幂等审计。
3. 独立 iOS 检查与 Android 门禁；更新发布清单和任务历史结构。
4. 修复后重新跑 20+8 真机任务、双向跨设备和 UX-1 五分钟首成功。


> 复跑入口：`python3 tests/cross_device_e2e.py --check` → `python3 tests/cross_device_e2e.py`。
> 结论只看**可核对的证据**（服务端指令审计 + 两端 engine.log + 目标设备前台包名），不采信模型自述。

**已通过：手机 → 手表（端到端）**

- 手机端下达「Ask the OWW261 watch to open the Calculator」→ 模型**第 1 步**即选 `remote OPPO OWW261`，
  服务端审计 `POST /api/v1/device/command 200`（指令 `c_1852113485e955ea`）；
- 手表 19:54:11 领取「[跨设备指令] 打开计算器」→ 19:54:31 执行 `app com.heytap.wearable.calculator2`
  → 19:54:33「任务完成：计算器已打开」；**手表前台 = 计算器**（决定性证据，非自述）。

**未完成：手表 → 手机**（三层阻塞，按遇到顺序）

1. **手表 a11y 在覆盖安装 / force-stop 后无法自动重绑**：`AgentA11yService.get() == null` 时接收端
   直接 ack failed（`RemoteCommand.java` 的 a11y 前置检查）；而 ColorOS Watch 上 A11yGuard 的 L1 因
   `SecurityException` 不可用、L2（拉起主界面）无效、系统详情页显示「无法运行」；用 `settings put` 写入
   列表与总开关后系统**仍不绑定**。任务发起同样需要 a11y（`AgentEngine.java:163`）。
2. **手表端没有可用的 adb 文本输入通道**：前台正确、输入框已聚焦（占位符消失）时，`input text` 与
   `input keyevent` 都不产生字符（偶发只进 1 个字符）；禁用/启用搜狗手表 IME 均无效。
   → 手表端任务只能**人工输入**，自动化只能覆盖到"下发之后"。
3. **手表重启会关闭 USB 调试**：为修 a11y 重启手表后 `adb devices` 里再无手表，需人工重插/重开调试。

**本轮据此修掉的两个真实缺陷**（均已提交 + 门禁全绿）

- 跨设备事件（下发/领取/回报/拒绝）此前**只写 logcat、不写 engine.log**，失败零痕迹——手机说"已下发"、
  手表 ack 了 failed，两端日志却一行没有，一度被误判为「指令根本没下发」。现在关键事件同时落 engine.log
  （`4de1303`，已同步 `agent-unified/client` 副本）。
- `task_baseline.py` 每条任务 `am force-stop` 会清掉 ColorOS 的 a11y 绑定，之后的任务在"感知不到节点"
  状态下空跑成**假性 timeout**（旧批次 20 条里 2 条如此，会被误记成模型失败）。现在每条任务前补写绑定，
  并新增失败归因（env:a11y / channel / model_or_env）与批次标签（`ab1b658`）。

**新增资产**：`tests/cross_device_e2e.py`（双向验证脚本）、`tests/watch_ui.py`（小屏 UI 原语：
按 resource-id 实时定位、落点避开底部系统手势区、滚动到位——写死坐标在 466×466 表上必然串位）。

**本轮测试账号**：自建 `xdev09151936`（口令随机生成、只落 `/tmp`、不入库不入仓），手机与手表都登录它。
手机若要切回自己的账号：App 内「设置 → 登录 / 切换账号」用原口令登录即可。



### 16.14 极细复盘：更新后仍需优先修复的问题（2026-09-16 18:57）

#### 已确认的新增进展

- 跨设备令牌已从单令牌改为多令牌，上限 10 把；同账号双设备登录不再互踢。
- 手表→手机、手机→手表均有成功记录；任务回环已修复，20 轮交叉测试回环次数为 0。
- 额度不足时已能切换自备接口；服务端已强制流式 usage、限制赠送额度并增加并发闸门。
- 密码节点脱敏、外部 Intent 驱动隔离、DeepSeek 充值流程改版、光晕贴合已有修复记录。

#### 仍需优先处理的缺陷

1. **用户 API Key 未加密存储（P1）**：`Prefs.java` 仍直接读写 `api_key`，现有 `TokenStore` 只保护账号令牌。应新增 `ApiKeyStore`，Keystore AES-GCM 迁移成功后删除明文，失败禁止降级。
2. **局域网密钥未加密存储（P1）**：`LanSync.java` 仍将 `lan_key` Base64 写入普通偏好。应迁移到 Keystore，并验证重启、升级和双端互通。
3. **ask 超时仍可能进入模型上下文（P1）**：超时文本可能被模型误当成许可。应改为结构化 `USER_TIMEOUT`，直接拒绝敏感动作，不得继续执行。
4. **剪贴板清理需确认 Lite 真身实现（P1）**：冻结 `agent-client` 已有清理逻辑，但 Lite 仍有剪贴板写入路径；必须在 done/fail/stop/exception/timeout 统一 finally 清理，且不误删用户内容。
5. **锁屏通知策略不一致（P1）**：部分保活通知可能显示任务标题或状态。默认应为 `VISIBILITY_SECRET`，锁屏仅显示“Agent 正在运行”。
6. **跨设备发送前缺能力探测（P1）**：在线不等于 a11y、输入、执行和回报能力可用。发送前应检查 `last_seen/a11y_connected/can_input/can_execute/client_version`，不满足时提前给出修复步骤。
7. **跨设备真实成功率仍偏低（P1）**：20 轮测试为 25/40 段通过；失败包含模型执行、手表能力边界和 a11y 掉线 78 次，必须分层统计，不能合并为模型成功率。
8. **发布前安全遗留未关闭（P1）**：IME 权限范围、`agent-client` `allowBackup`、锁屏脱敏、剪贴板、Key 加密仍有待办，应建立发布阻断清单。
9. **额度服务需要请求级幂等（P1）**：上游响应与扣账分离，重试或落盘失败可能重复扣账或漏记。应引入 `request_id`、幂等 ledger 和 pending/replay 状态。
10. **注册赠送额度可能被批量刷取（P1）**：主要按 IP 限流，IPv6/代理池可绕过。应增加设备/安装实例/账号维度限制、每日预算和异常停发。
11. **设置页仍有技术门槛（P2）**：API Key、模型、同步地址、Token 统计应默认折叠，普通路径只显示任务、登录、额度、权限和通道说明。
12. **测试结果必须分层（P2）**：报告应拆分 `env:a11y`、`env:capability`、`model`、`channel`、`user`，不能用混合成功率指导 DeepSeek 提示词。
13. **iOS 工具链应隔离（P2）**：Xcode license 未接受会退出码 69；应提供独立 `make ios-check`，不阻断 Android/Go 门禁。

#### 下一步指导

1. 先完成 `ApiKeyStore`、`lan_key`、ask 超时拒绝、剪贴板清理、锁屏通知脱敏。
2. 再做跨设备能力探测和请求级额度幂等；重跑 20+8 场景并按失败分类出报告。
3. 最后生成发布清单，绑定源码 commit、tag、APK/服务端 SHA-256、`deepseek-flash` 配置摘要。

#### DeepSeek 4.1 Flash 针对性约束

- Flash 只负责短决策，不负责安全授权、计费、权限和终态裁决。
- 每轮只给最新快照、目标、完成判据、设备能力和上一步结果。
- 设备名/型号命中必须 `remote`；打开应用优先 `app`；禁止用应用商店搜索替代启动。
- 失败必须改变策略；连续同动作、同屏无变化和跨设备回环由引擎硬终止。
- `done` 只接受可验证证据；HTTP 200、指令下发、远端领取和屏幕变化均是中间证据。
- 固定模型、温度、最大输出和设备矩阵，分离模型失败与 a11y/能力/额度失败。

### 16.13 20 轮双端互相交叉测试（2026-09-15 21:44–22:26）

> 复跑：`python3 tests/cross_20_rounds.py`（`--rounds N` 只跑 N 轮、`--start N` 续跑、`--check` 只自检）。
> 明细 `tests/results-cross-20.jsonl`、表格 `tests/results-cross-20.md`。
> 每轮两段：**A = 手机 → 手表**（手机下达、手表本机执行）；**B = 手表 → 手机**（手机下达「让手表转告手机做事」
> → 手机 remote 给手表 → **手表自主 remote 回手机** → 手机执行）。

**结果：25/40 段通过（62%）**——A 段 10/20，B 段 15/20，**跨设备回环 0 次**。

| 指标 | 值 |
|---|---|
| A 段（手机→手表） | 10/20，平均 37s，其中 8 段手表 ack=done |
| B 段（手表→手机） | 15/20，平均 49s；**进入第二跳的 18 段里，手表自主 remote 回手机 18/18 成功** |
| 回环（两端互转） | **0 次**（§16.12 修复前同一矩阵 A 段 2/2 回环） |

**目标设备步数**（口径：设备 engine.log 按「收到一条跨设备指令 = 启动一次任务」的顺序回溯，
工具 `tests/backfill_cross_evidence.py`）

| 项 | 有值段数 | 中位 | 范围 |
|---|---|---|---|
| 手表执行步数（A 段，含多步点击如 7×8） | 15/20 | 2 | 1–16 |
| 手表转达步数（B 段第二跳） | 18/20 | 2 | 1–13 |
| 手机执行步数（B 段领取后） | 18/20 | 2 | 1–50 |

> 首轮跑时结果文件只落了成功率/耗时/指令号/终态/前台包名，**漏了步数**（目标要求的四类证据之一）。
> 现靠设备日志回溯补齐 33/40 段，并把 `cross_20_rounds.py` 改为**运行期原生采集**
> （`dispatch_steps` 发起方决策步数、`exec_steps` 目标设备执行步数、`relay_steps` 第二跳转达步数），
> 下次运行不再需要回溯。

**失败归因（15 段，分三类）**

| 类别 | 段数 | 证据 |
|---|---|---|
| 测试脚本鲁棒性（非产品问题） | 4 | A 段「手机端找不到运行按钮」：上一段任务把界面留在完成卡片/别的应用上，复位重试仍失败 |
| 任务矩阵不适配手表 | 3 | 手表模型明确回报「圆形屏手表设置里只有账号/关于手表/开发者选项，没有电池/显示页面」（第 7/9/19 轮） |
| 真实产品问题 | 8 | ① 手表 a11y 掉线致任务失败（第 4 轮；全程 engine.log 有 78 次掉线记录）② 手表执行卡死「连续 6 步屏幕无变化」（第 14 轮）③ 模型未走 remote（第 18 轮 A、第 10/18 轮 B）④ 手机侧 relay 子任务未打开目标应用（第 12/14/19 轮；日历/文件/电话**应用确实存在**，已用 pm 核验） |

**新增发现**

1. **手表 a11y 在 ColorOS Watch 上反复掉线**（40 分钟内 78 次）：这是「手机控制手表」最大的失败源。
   手表无 root → A11yGuard 的 L1（写设置）抛 `SecurityException`、L2（拉起主界面）无效，只能靠用户手动重开。
   → 后续要么把「掉线一键恢复」做得更显眼，要么给手表换保活手段。
2. **手表能力边界**（模型自述 + 实测）：OWW261 的设置只有账号/关于手表/开发者选项等手表专属项，
   **没有电池、显示页面**。给手表的任务应限于「打开计算器 / 时钟 / 设置主页」这类确定可用的目标；
   跨设备任务矩阵必须按设备能力分开设计，不能照搬手机侧的任务集。
3. **relay 两跳是可靠的**：B 段失败全部发生在第一跳（手机模型没走 remote）或手机侧执行上，
   **第二跳（手表自主决策并 remote 回手机）18/18 成功**——「手表控制手机」这条链路本身没问题。
4. 第 11 轮 A 段手表正确完成**多步**任务：「计算器已算出 7×8=56」（手表端连续点击可用）。

### 16.14 语音输入修复：ASR 与 LLM 上游解耦（2026-09-18）

**症状**：PKX110 上按语音按钮，录音正常（`appops` 记录 `RECORD_AUDIO allow` 5.8s），
但识别不出任何文字；界面只留一句「语音识别失败」，**看不出原因**。

**根因（实证，非推测）**：语音原先复用「主模型原生音频输入」，而上游已经切成 DeepSeek——
- `POST /v1/chat/completions` 带 `input_audio` → 400：`unknown variant 'input_audio',
  expected one of 'text', 'image_url', 'file'`
- `POST /v1/audio/transcriptions` → 404

设备上 `asr_model` 为空（走"主模型音频"分支）、`base_url = api.deepseek.com` ⇒ 语音必然失败。
ColorOS 也没有可退回的系统识别服务（`voice_recognition_service=null`，`RECOGNIZE_SPEECH` 无接收者）。

**修复（三处，缺一不可）**
1. **解耦**：`LlmClient.transcribe(wav, model, asrBase, asrKey)` 支持独立 ASR 端点；
   `Prefs.asrBaseUrl()/asrApiKey()` 缺省为空 = 原行为，向后兼容。以后再换 LLM 上游也不影响语音。
2. **离线转写服务**：`tools/asr_server.py`（mlx-whisper + medium-4bit 489MB，跑在 Mac）。
   手机录的正是 **16k 单声道 PCM16 WAV**，与 whisper 原生要求一致 ⇒ **不需要 ffmpeg**。
   实测 **4 秒音频 0.63 秒出文本**。
3. **放行局域网明文**：局域网 ASR 只能是 http，Android 默认拦（`Cleartext HTTP traffic to
   192.168.1.108 not permitted`）——这是修复途中的第二个卡点，界面上同样看不出原因。
   加 `res/xml/network_security_config.xml` 放开明文。
   ⚠ **安全权衡**：这是全局放开（私网自签 HTTPS 不现实，`agentd-apk` 早用
   `usesCleartextTraffic="true"`）；默认端点仍全为 https。要收紧就改成按域名白名单。

**运维**
- **常驻方式（2026-09-18 加固）**：`~/Library/LaunchAgents/com.agentlite.asr.plist`，
  `RunAtLoad` + `KeepAlive`，开机自启、崩溃自拉。手工开关用
  `launchctl load/unload ~/Library/LaunchAgents/com.agentlite.asr.plist`；
  日志 `tail -f /tmp/asr_server.log`；备选 `sh tools/asr_start.sh start|stop|status`。
- **为什么必须常驻**：第一版用 `nohup` 启动、venv 还放在 `/tmp` —— Mac 一重启/清理就全没，
  用户第二天只会看到「语音又不能输入」，**看不出原因**。现已把 venv 与模型都放到
  `~/.cache/`（`asr-venv`、`asr-models/whisper-medium-mlx-4bit`）。
- 手机端独立 ASR 端点当前为 `http://192.168.1.108:18888/v1`（**Mac 换网/关机 = 语音不可用，
  但不影响任务执行**——LLM 仍直连 DeepSeek）；Mac 换 IP 后要同步改端点。
- **失败提示已自解释**：`MainActivity.asrHint()` 把 `Cleartext` / `Connection refused` /
  `timeout` / `404` / `UnknownHost` 分别翻成"该怎么做"的一句话（此前用户只看到
  「语音识别失败：<原始异常>」，等于没有信息）。
- 国内拉模型：必须 `HF_ENDPOINT=https://hf-mirror.com`，且 **hub 的 xet 传输会超时**；
  可靠办法是用 curl 分段（`-r start-end`）并行下载 `weights.npz` 再拼接（本机实测 0.6→2.2 MB/s）。

**遗留**：设置页还没有「ASR 端点」输入框（现在靠 `su` 写 `asr_base_url`），应补 UI——
补上后用户即可在手机上自查端点与连通性，不必每次找开发者。

### 16.15 语音转写改为**手机本地离线**（whisper.cpp，2026-09-18 定稿）

> 用户两条硬约束：**不要 root**、**不要依赖 Mac**。本节方案是在这两条约束下的最终形态。

**为什么要第三次返工**：§16.14 把 ASR 解耦成独立端点后指向了 Mac 上的 whisper 服务——
结果 Mac 一关机/换网/换 IP 语音立刻失效。实测故障现场：Mac 的 `en0` 变成 `10.127.194.126`
（不在 192.168.1.x），手机连着 `B607`，`ping` 100% 丢包，App 只能报「连不上 ASR 服务」。

**现在的架构：whisper.cpp 直接编进 APK，完全离线**

| 组成 | 说明 |
|---|---|
| 原生库 | `libwhisper.so` / `libggml{,-base,-cpu}.so` / `libagentasr.so` / `libc++_shared.so` → `lib/arm64-v8a/`（APK 0.2MB → **18.6MB**） |
| JNI | `agent-lite/jni/whisper_jni.cpp`，自写；**不复用**官方 `examples/whisper.android` 的 Kotlin/Gradle，保持「纯 Java + 手写构建」 |
| 入口 | `AsrRouter` 三级回退：**本地离线** → 独立 ASR 端点 → 主模型音频。任一环失效都不会让语音整条哑掉 |
| 模型 | `ggml-small-q5_1`（181MB），**首次下载一次**，之后永久离线（服务器 / hf-mirror 双源，也可直接放进 `getExternalFilesDir`） |
| 预热 | App 启动时 `AsrRouter.warmUp()`，免掉首次说话的模型加载等待 |
| 重建 | `sh tools/build-asr-libs.sh`（全量）或 `--jni-only`（只重链 JNI） |

**真机验证（OnePlus 13T，关闭 Wi-Fi 与移动数据）**

| 阶段 | 结果 |
|---|---|
| 首版 | `本地转写: 5.9s 音频 → 8.6s：打开设置` ✓ |
| 关掉温度回退重试 + 6 线程 | `本地转写: 6.1s 音频 → 6.5s：打开计算器` ✓ |

**三个必须记住的坑**（已固化进 `tools/build-asr-libs.sh` 与 manifest）

1. **必须 `-DGGML_OPENMP=OFF`**：whisper.cpp 默认开 OpenMP，而 NDK 不提供 `libomp.so`，
   运行期报 `UnsatisfiedLinkError: dlopen failed: library "libomp.so" not found`——
   更坑的是它被上层日志误报为「本地转写库未打包（ABI 不匹配）」，把排查方向带偏。
2. **必须 `android:extractNativeLibs="true"`**：ggml 的 CPU 后端是**运行时 dlopen**，
   库若留在 APK 内（系统对已对齐的库会跳过解压）它找不到。
3. 本机 `make`/`git` 走 Xcode 工具链会因「未同意 license」直接失败 → 脚本内导出 `DEVELOPER_DIR`。

**已知限制**：6.5s / 6.1s ≈ **1.07× 实时**，偏慢；换 `ggml-base-q5_1`（60MB）约可再快 2 倍、
准确率略降（`WhisperAsr.MODEL_FILE` 一行即可切换）。设置页仍缺「离线模型管理」入口
（下载/切换/显示当前模型），应补 UI。

#### 16.15.1 云端 ASR（智谱 GLM，2026-09-18 追加）

用户要求「增加一个云语言转译，模型用 glm」。因为智谱的接口是 **OpenAI 兼容**的，**协议代码一行没改**，
只补了配置入口与优先级——这也是 §16.14 把 ASR 从 LLM 通道解耦的价值直接兑现。

| 配置项（都在设置页） | 值 |
|---|---|
| 云端语音端点 `etAsrBase` | `https://open.bigmodel.cn/api/paas/v4` |
| 语音模型 `etAsr` | `glm-asr-2512` |
| 云端语音密钥 `etAsrKey` | 用户自己的智谱 API Key（密码框；**开发者不索取任何密钥**） |

- **优先级**：云端（填了端点就用）→ 本地离线 → 主模型音频；**云端失败或返回空后进入 5 分钟冷却**，
  期间直接用本地离线，冷却结束再试云端、恢复即自动切回（用户要求「优先 GLM，不可用时用本地」）。
  没有冷却的话，云端一旦不可用，每次语音都要先等一次失败才回退。
- **ASR 专用短超时**：连接 6s / 读取 20s（主对话仍是 15s/120s）——ASR 是"说一句立刻要结果"的交互，
  不能在 120s 读取超时上干等。
- 云端成功也记日志（否则无法判断某次到底走了哪条通道）。
- **端点正确性已实测**：`POST /api/paas/v4/audio/transcriptions` 返回 **401**
  「Header中未收到Authorization参数」（而非 404），说明路径与 multipart(file+model) 格式都对。
- ⚠ **踩过的坑**：`Prefs.asrModel()` 有两处历史逻辑会把模型名改坏——把 `glm-asr-2512`
  当"失效默认值"清空，以及「单模型模式」把语音收敛到主模型。结果是 App 把 `deepseek-flash`
  发给智谱，云端报 `1211 模型不存在`（而本机用同 Key 同模型名 curl 是成功的）。已修为：
  **配了云端端点就不再收敛到主模型**，且不再清空 `glm-asr-2512`。
- 设置页新增「**测试语音转写链路**」按钮：弹窗直接列出当前优先级、云端配置、本地模型状态，
  不必再靠 adb 写 prefs 排障。
- 实测清理掉残留的 Mac 端点后，语音不再"先失败一次再回退"，直接走本地：
  `本地转写: 5.9s 音频 → 6.7s：打开设置`。
- ⚠ **安全**：`asr_api_key` 目前与 `api_key` 一样**明文落 SharedPreferences**，属安全审查 P1-7
  的同一类问题，后续应与 `api_key`/`lan_key` 一起迁 Keystore。

### 16.16 项目进度推到灵动岛（文件桥，2026-09-18）

用户要求「把当前项目进度推到灵动岛上」。实现刻意避开 P0-A1 那条安全线：

- **`ProgressBridge`**：每 3 秒读 `getExternalFilesDir()/project_progress.txt` 的**一行文本**，
  内容变化时调到灵动岛胶囊；文件不存在/为空则收起。桥在 `AgentA11yService` 连上时启动。
- **`OverlayController.showProgressNote/hideProgressNote`**：既有的 `progress(text)` 带
  `taskActive` 判断，只在任务执行期间生效，空闲时显示不了这类常驻信息，故新增一对方法。
- **为什么用文件而不是广播/Intent**：P0-A1 之后外部 Intent（含 `task`/`auto_run` extra）一律被忽略，
  就是为了防止任意 App 一条 intent 驱动无障碍代理。文件是「进程外**只写数据、不触发行为**」的
  最小通道，且必须 App 主动轮询才生效；删掉文件即回到常态。

用法（进度文本建议 ≤18 字，胶囊很窄）：

```bash
adb shell "echo '语音✓GLM✓ 测试25/40' > /sdcard/Android/data/com.dsh.agentlite/files/project_progress.txt"
adb shell "rm /sdcard/Android/data/com.dsh.agentlite/files/project_progress.txt"   # 收起
```

真机验证（OnePlus 13T）：胶囊显示「● 语音✓GLM✓ 测试25/40 ↻」，日志
`进度已推送到灵动岛：语音✓GLM✓ 测试25/40`。提交 `6d5f9f6`。

### 16.17 协议类弹窗改为「默认代点」（无限制模式，2026-09-18）

用户要求「取消服务条款限制，现在改成完全无限制」。

**背景**：`autoAgreeTerms()` 此前是被**刻意移除**的，原注释写着「那是在替用户作出不可逆的
法律授权，违反手册 §16.2 P0-2 与 §16.5 第 4 步」。所以这次不是修 bug，而是**产品决策变更**。

**做法**（恢复能力，但保留可逆性）
- `Prefs.autoAcceptTerms()`：默认 **true**（无限制）；`setAutoAcceptTerms(false)` 立刻回到
  「暂停并把控制权交还用户」的安全默认。
- `AgentEngine.autoAgreeTerms()`：先勾「已阅读并同意」类复选框、再点同意按钮（顺序反了通常点不动）；
  找不到可点按钮时仍退回暂停，不会把任务卡死。
- ⚠️ **风险（已知并接受）**：代点「同意并继续」等于**代替用户作出法律授权**且通常**不可逆**。
  建议只在自己信任的 App 上使用。
- **范围**：只覆盖协议类弹窗；支付/转账/验证码/删除数据等红线**不在例外内**。

**规范与门禁同步修订**（重要）
改动先被自带门禁拦下：`FAIL [UX-6] 条款/隐私弹窗只检测不代点`——这正是项目治理在起作用。
于是**改规范而不是绕过**：`SPEC_UX_001` 新增 §1.1 例外条款，门禁断言从「绝不代点」改为
「**代点必须可关闭**」，并保留「`isTermsDialog` 只检测、不掺点击逻辑」的约束。

## 17. 真机验证记录（2026-09-21 · OnePlus 13T / ColorOS 16 / Android 16）

> 设备 `PKX110`，已安装 `com.dsh.agentlite` 0.6.0（versionCode 6，含 M0-01/M0-02/M3-01 源码类）。
> 全程零 root 业务路径（仅 adb + App 自身能力，`su` 只用于读取证据）。**本轮只验证与记录，未改任何源码。**

### 17.1 验证矩阵（通过项）

| 项 | 结果 | 证据 |
|---|---|---|
| 冷启 | **204ms**（COLD），无 FATAL/AndroidRuntime | `am start -W`；`logcat` 无崩溃 |
| 无障碍绑定与自愈 | 绑定成功；`force-stop` 后仍保持；`kill -9` 后自动重绑 | `dumpsys accessibility` + `settings get secure` |
| 真机首成功 | 任务「Open the Calculator」**5 秒完成**（2 步，`app com.coloros.calculator` 直达） | engine.log `18:13:01→18:13:06` |
| 记忆库闭环 | 第一次写入 1 条；**第二次命中并直接复现，零模型往返** | engine.log `命中操作记忆库 → 记忆库复现 1/1`，且无「流程转译」记录；`flowmem.json` 落盘 |
| 用户停止 | 「运行」后 1.5s 点停止 → `收到停止请求` → `任务已被用户停止`，**未记为失败** | engine.log `18:15:46` |
| 进程死亡恢复 | `kill -9` 后重启：账号、模型、设备 ID、记忆库全部保持 | prefs + `flowmem.json`（368 字节） |
| 升级安装 | `install -r` 平滑，登录与 a11y 绑定未丢 | `dumpsys package` |

### 17.2 本轮发现的缺陷（**未修**，按用户要求只记录）

**缺陷①：额度显示二次扣减（P1，共 5 处）**

> **状态更新（2026-09-21 20:10 复验）：本项在真机上已不成立。** 当前构建（20:05 安装）主界面显示
> 「账号：xdev09151936 · 剩余 **144** units」，与 prefs `account_quota=144` / `account_quota_used=956`
> 及服务端权威值一致，**不再是 0 units**。仓库侧门禁 `m3_tool_registry_guard.py` 亦有
> 「额度展示直接使用剩余 quota」与「设置页/手表/购买页不二次扣减」断言（本轮 31/31 通过）。
> 下方为 2026-09-21 18:16 的历史记录，保留以溯源。

服务端 `quota.go:217-231` 的语义是 `Quota -= units; QuotaUsed += units`，即 `quota` 是**剩余额度**、`quota_used` 是**累计已用**，两者互相独立、不可相减。客户端却在多处相减后截断：

| 位置 | 计算 | 实测表现 |
|---|---|---|
| `MainActivity.java:262` | `max(0, quota - used)` | 主界面显示「剩余 **0** units」 |
| `SettingsActivity.java:279` | `max(0, q - used)` | 设置页本地缓存路径 |
| `SettingsActivity.java:317` | `max(0, m.quota - m.quotaUsed)` | 设置页服务端路径，实测显示「额度：**0 / 144** units（已用 956…）」 |
| `WatchSettingsActivity.java:111` | `max(0, quota - used)` | 手表端同源 |
| `PurchaseActivity.java:410` | `max(0, quota - used)` | 购买页「我方额度」 |

**实测三方对照**（`quota` = 剩余 / `quota_used` = 累计已用）：

| 数据源 | quota | quota_used | 界面显示 |
|---|---|---|---|
| 服务端权威 | **144** | 956 | — |
| 客户端缓存（重启前） | 242（陈旧） | 858 | — |
| 客户端缓存（重启后已刷新） | **144**（与服务端一致） | 956 | **仍显示 0 units** |
| 客户端 UI | — | — | **0 units** |

★ **排除「数据陈旧」这一解释**：App 重启后缓存已刷新到与服务端一致的 `144/956`，界面**依旧显示「剩余 0 units」**——证明根因是**纯计算错误**（`144 - 956 = -812` 被截断为 0），而非数据未同步。缓存陈旧只是叠加因素。

**影响**：账号实际仍有 **144 units** 可用，界面却告知「剩余 0」，用户会误判额度耗尽而放弃使用；也容易把后续失败误归因为「额度耗尽」。该缺陷**不拦截请求**（`LlmClient` 不校验本地额度），任务本身仍可执行。

**缺陷②：软键盘遮挡输入区与「运行」按钮（P2）**

`AndroidManifest.xml` 未声明 `android:windowSoftInputMode`。真机实测（1216×2640）：软键盘弹出后顶部约在 `y≈1180`，而输入框 bounds `[112,1536][558,1757]`、运行按钮 bounds `[810,1562][1104,1730]`——**两者被完全覆盖**，布局未上移（仅因焦点变化位移 26px）。

**影响**：用户点输入框输入时既看不到自己输入的内容，也点不到「运行」（实测点击落在键盘上，任务未启动、日志无「任务启动」记录）。需先手动收起键盘才能继续，属可用性缺陷。

### 17.3 测试设施注意事项（非产品缺陷，但会污染验证结论）

1. **不要用 `uiautomator dump`**：它会拉起 `com.android.commands.uiautomator` 抢占无障碍绑定，与 AgentLite 服务互斥，触发 `A11yGuard` 反复报「服务掉线」并执行 L1 抖动修复（实测累计 10 次）。**静默 60s（期间只用 `screencap`）零掉线、日志零新增**，据此判定为测试设施干扰而非产品缺陷。附带风险：抖动修复会**清空 `enabled_accessibility_services` 再写回**，若用户同时启用其他无障碍服务（TalkBack 等）会被短暂清掉，建议登记为后续观察项。
2. **`adb shell input keyevent 67`（DEL）无法清空 App 输入框**：App 使用自研 IME 通道，实测 30 次 DEL 后旧文本仍在，导致任务文本被拼接成 `Open the WLAN settings page Calculator`。真机验证应改用「清空 App 数据后的干净输入框」或直接以 `flowmem`/prefs 预置任务文本。

### 17.4 本轮未验证项（不得以静态门禁替代）

- **旋转/分屏/形态注入**：`settings put system user_rotation 1` 未生效（设备保持 `rotation 0`），ColorOS 对非游戏应用默认锁竖屏，本机无法构造横屏场景；无崩溃、无形态变化日志。
- **敏感场景（支付/验证码）接管与 `ask` 阻塞**：需构造真实支付界面，风险与成本高，本轮未做。
- **纯视觉降级（节点为空）**：本轮未触发（计算器/设置页节点正常）。
- **M3-01/M3-02 工具声明与参数校验**：`ToolRegistry` 仍未接入 `AgentEngine`，无效参数校验目前是纯 Java 独立层；M3-03/04/05 的「Invocation/状态机、无效参数不触碰设备、审批与停止取消、新旧执行路径对等」仍待 Router 接线后在设备上验证。

### 17.5 第二轮真机验证（2026-09-21 20:05–20:11，同一设备）

构建：本轮 `sh agent-lite/build-mac.sh` 产物（19,574,129 bytes，`versionCode 6 / versionName 0.6.0`）
覆盖安装。**只验证与记录，未改任何源码。**

| 项 | 结果 | 证据 |
|---|---|---|
| 覆盖安装 | `install -r` 成功（890ms），登录态 / a11y 绑定 / 记忆库全部保留 | `dumpsys package`（`lastUpdateTime=20:05:52`） |
| 冷启 | **465ms**（COLD），无 FATAL/ANR | `am start -W`；`logcat` 未见本应用崩溃 |
| 无障碍绑定 | 安装后保持绑定；本轮 `uiautomator dump` **未**触发互斥掉线 | `settings get secure` + `dumpsys accessibility` |
| 真机任务（模型路径） | 「Open _the _Calculator」**4.0s 完成**：转译 `intent=open_app` → 第 1 步 `app com.coloros.calculator` → 完成 | `logcat AgentEngine` 20:06:49→20:06:55 |
| **记忆库零往返复现** | **命中并直接完成，1.2s**：`命中操作记忆库：1 步 → 打开计算器` → `记忆库复现 1/1` → `已按记忆库直接完成（1 步，未重新规划）`，**全程无「流程转译」记录** | `logcat AgentEngine` 20:10:24→20:10:26；`flowmem.json` `hits=2 oks=3` |
| 缺陷① | **已不成立**：主界面显示「剩余 144 units」（= prefs `account_quota=144`） | 截图 `_local/verify-20260921-ui/ui-01-main.png` |
| 缺陷② | **仍然存在**：键盘顶部 `y≈1568`，输入框 `y 1508–1642`、运行按钮 `y 1508–1730`，仅露出约 60px 上沿 | 截图对比 + 亮度扫描（`_local/verify-20260921-ui/ui-16-row-zoom.png`） |

**记忆库匹配边界（本轮新增实测结论）**：命中要求**归一化文本精确相等**。
`Open _the _Calculator`（下划线降级为空格导致分词差异）**未命中**、走模型转译，并**另存为独立条目**；
`Open the Calculator` 才命中。即当前记忆库不会把「同一意图的不同书写」合并为一条记录。

### 17.6 测试设施陷阱补充（本轮实测，非产品缺陷）

1. **`adb shell input text "a b"` 的空格会变成下划线**：实测得到 `Open_the_Calculator`，导致
   （a）任务文本与记忆库原文不一致而无法命中；（b）带空格任务难以用 adb 复现。
   需要真实空格时用 `input keyevent 62` 分段注入（本轮即以此输入出精确原文）。
2. **`KEYCODE_DEL` / `KEYCODE_MOVE_END` 仍无法清空输入框**（复验 §17.3 第 2 条）：连续 40 次 DEL
   后文本一字未减。本轮可行替代：长按输入框 → 点「剪切」。
3. **坐标点击前必须确认前台应用**：本轮中途 `keyevent 4` 把 AgentLite 退到后台（前台变为计算器），
   此后对 (942,1575) 的「运行」点击全部落在计算器上、日志无任何 agentlite 记录，白花 3 轮排查。
   判定手段：`dumpsys activity activities` 的 `topResumedActivity`，或 `uiautomator dump`
   （内容里带 `package=`）；**不要靠截图**——陈旧画面与真实前台可能不一致。
4. **`cmd clipboard set-text` 在 ColorOS 不可用**（返回 `No shell command implementation.`），
   无法用命令行预置剪贴板来做精确文本输入。
5. **`adb shell input text` 不能注入中文**：中文会直接抛
   `java.lang.NullPointerException ... InputShellCommand.sendText`，任务文本为空。中文任务只能靠拼音输入法
   或先切到 AgentLite 之外的中文输入通道；验证英文任务时逐词 `input text` + `keyevent 62` 注入空格。
6. **「运行」按钮的坐标会随输入框行数变化**（本轮最耗时的一坑）：输入框内容 1 行时按钮在
   `y≈1508–1730`，内容涨到 4 行后整行下移，真实边界变为 `[810,1933]-[1104,2101]`，
   继续用经验坐标 `y=1575` 点击会**完全落空且没有任何日志**。
   正确做法：`uiautomator dump` 读 `resource-id="com.dsh.agentlite:id/btnRun"` 的 `bounds` 再点，
   不要靠截图目测或记忆坐标。

### 17.7 高难度生活型任务实测：给第一条朋友圈点赞（2026-09-21 20:16–20:19）

**任务**：「打开微信朋友圈，给第一条朋友圈点赞」（因中文无法注入，实际提交的英文文本为
`Like_the_first_WeChat_Momentns_and_the_first`，含累加残文，但关键词齐全，模型可理解）。
前置态：微信已在后台且停在主页。构建同 §17.5。

**基线（任务前人工采集）**：第一条动态 = 「9.28 龙玥彤 · 孩子用不到俩月给自己吃胖五斤」（9 分钟前）；
点赞区只有「田一涵, 来子晨」，**不含本机账号**，即确实未点赞。

**结果：任务失败，未产生任何点赞**。微信被启动过一次，但停在「奶奶」聊天页，从未进入朋友圈。

| 步 | 动作 | 说明 |
|---|---|---|
| 1–2 | 决策/wait | 前三步日志已被 logcat 环形缓冲冲掉，只能看到第 2 步起 |
| 3 | wait 3000ms | 感知恒为 5 个节点 |
| 4 | **app com.tencent.mm** | 唯一一次有效动作：把微信拉到前台 |
| 5 | wait 4000ms | 仍未识别界面 |
| 6 | **wake** | 误判屏幕未点亮 |
| 7 | app com.tencent.mm | 再次尝试启动 |
| — | **看门狗终止** | `任务失败——屏幕连续 6 步无任何变化，疑似卡死，已自动停止` |

全程 **0 次 tap/swipe/text**；flowmem 未被污染（失败任务不入库，符合预期）。

**第二次对照复现（20:21:28–20:21:48，前置态改为微信主页）**：结果与首次一致——
第 1 步 `app com.tencent.mm`，第 2–3 步 `wait`，第 4 步又 `app com.tencent.mm`，
第 5 步 `wait 4000ms`，第 6 步 `wake`，随后**同样被指纹看门狗终止**（`屏幕连续 6 步无任何变化`）。
两次步序几乎逐字相同，**2/2 稳定复现**，始终 0 次点击。
→ 可判定为**系统性缺陷而非随机失败**：只要「应用屏蔽无障碍 + 系统状态栏提供恒定节点」，
任务就会在 6~7 步内被看门狗误杀，与任务文本和前置界面无关。
（该次任务文本因 adb 逐词注入而累积成
`打开微Open WeChat MomeLike the first WeChat Momentnts and the first Moment信，帮我给微信朋友圈的第一个好友点一个赞`，
转译结果 `intent=general`——即**文本污染也未改变失败步序**，进一步说明瓶颈在引擎侧。）


#### 暴露的缺陷（本轮重点，待高级模型修复）

**缺陷A（P0，感知层）：微信应用窗口节点被完全屏蔽，感知只剩系统状态栏**

`AgentA11yService.collectNodes` 在本次 32 次采集中**全部**只记录到
`顶层窗口: pkg=com.android.systemui type=3 bounds=0 0 1216 135`；
`getWindows()` 里微信的 `TYPE_APPLICATION` 窗口 `w.getRoot()` 返回 null（ColorOS 16 实测），
于是节点集 = 状态栏的 5 个节点（电量/信号/时间）。
`collectNodes` 的输出**没有携带「本次只采到系统窗口」的标记**，调用方无法区分
「真的看到 5 个元素」与「应用窗口被屏蔽，只有状态栏」。

**缺陷B（P0，判定层）：非空节点让「纯视觉模式」与空节点哨兵双双失效**

引擎的纯视觉降级与看门狗哨兵都只判 `nodes.isEmpty()`。微信场景下节点数恒为 5（状态栏），
既不会触发「连续 2 步 0 节点 → 纯视觉模式」，也不会写入 `empty-0/empty-1` 交替哨兵，
于是模型拿到的是**与截图完全不一致的节点表**，同时看门狗认为「有节点、屏幕有信息」。

**缺陷C（P0，判定层，最致命）：指纹看门狗在纯视觉场景误杀任务**

`NodeInfo.fingerprint` 只由节点数量与 `text|desc|id` 计算，**完全不含截图**。
状态栏 5 节点恒定 → 每步指纹相同 → 第 7 步判定「屏幕连续 6 步无任何变化」并终止。
而截图实际一直在变（微信首页 → 奶奶聊天页），真实进展被无视。
推论：**在「应用屏蔽无障碍 + 有系统状态栏」的常见组合下，看门狗必然在 6~7 步内误杀**，
这解释了本轮为何没等到模型进入朋友圈。
（对照：§17.4 记录的「空节点屏看门狗失效」是**漏杀**，本条是**误杀**，两者根因同源——
指纹只看节点、不看像素。）

**缺陷D（P1，提示词/决策层）：状态栏节点污染节点表**

模型看到的节点表含状态栏元素，与截图显示的微信界面完全不匹配；
本次任务文本又被 adb 输入污染（`Momentns` + 残文），叠加后模型前两步只在 `wait`，
未能建立「先点『发现』→ 再点『朋友圈』」的路径。

#### 后续修复候选（按优先级，供高级模型执行）

1. **看门狗改用截图差异（最高优先）**：纯视觉/节点可疑时，指纹应以「截图降采样哈希」为主，
   而不是节点哈希；或至少在节点指纹集合 ≤2 种时**不判定卡死**，改为注入「换策略」警告。
2. **纯视觉判定改为「顶层窗口非本应用且节点数 < 阈值」**，而不是死等 `isEmpty()`；
   `collectNodes` 应回传 `topPackage`/`topSystem`/`onlySystemWindows` 等信号供调用方决策。
3. **状态栏节点与页面节点分开送模**：系统窗口节点单独成段并标注「系统状态栏，非应用内容」，
   避免模型把状态栏当页面元素。
4. **补一条真机回归**：微信（屏蔽无障碍）场景下，任务至少能进入朋友圈并完成一次点击，
   且不得在 6 步内被看门狗终止。


---

### 17.8 纯视觉看门狗修复后真机验证（2026-09-21 21:28–21:34）

构建：修复版 `agent-lite/build/agent-lite.apk`，19,574,129 bytes；覆盖安装，未清理登录态、额度或记忆库。
本轮只执行低风险任务 `Open WeChat`，**不进入朋友圈、不点赞、不发送内容**，用于验证感知降级和看门狗，不冒充朋友圈端到端成功。

| 项 | 结果 | 证据 |
|---|---|---|
| APK 安装 | 成功，耗时约 923ms | `adb install -r` |
| 无障碍服务 | 绑定保持，未 root | `settings get secure enabled_accessibility_services` |
| 窗口污染识别 | **成功**：识别 `top=com.android.systemui`，忽略状态栏节点并切换纯视觉 | `logcat AgentEngine 21:29:38` |
| 纯视觉截图 | 成功，截图亮度≈49，节点表降为 0 | `logcat AgentEngine` |
| 低风险任务 | **成功**：第 1 步启动微信，第 2 步完成「微信已打开，当前显示在聊天界面」 | `logcat AgentEngine 21:29:38–21:29:40` |
| 看门狗 | 未在第 6~7 步误杀；本次 2 步完成 | `logcat` |
| 前台复验 | `com.tencent.mm/.ui.LauncherUI` | `dumpsys activity activities` |
| 副作用 | 未点赞、未发言、未修改聊天内容；仅启动微信 | 人工限定任务范围 |

**修复结论**：缺陷 A/B/C（SystemUI-only 识别、纯视觉触发、状态栏指纹误杀）已完成代码修复并通过真机最小链路。
朋友圈「进入发现→朋友圈→第一条点赞」仍需单独复验；本轮不宣称该高风险社交动作已完成。

**验证门禁**：纯 Java 感知回归 `55/55`；全局复核 `9/9`；SPEC-UX 静态项 `17/0`；M3-01 `31/31`；M3-02 `28/28`；
`agent-unified/client` 与 `agent-lite` 逐字节同步校验通过。Xcode license 未接受仍会阻断聚合 `git`/`make` 路径，不影响独立 Android 构建。

### 17.9 朋友圈点赞端到端验证与「胶囊吃点击」缺陷修复（2026-09-21 22:12–22:27）

**任务**：`Like the first Moment in WeChat Moments`（用户已明确授权真实点赞；点赞对象为真实好友，事后可自行取消）。
**基线（22:21 人工采集）**：朋友圈首条 = `traever-旅行者`「这就是我退抖音前最后一个点赞吗」（50 分钟前），
动态下方**只有「50分钟前」与「···」，没有任何点赞条目**——确认本机当时未点赞。

**修复前两次失败（22:15–22:17）**：Agent 全程空转，动作在 `tap@766,2442`（发现 Tab 坐标）与另一坐标间 A/B 震荡，
最多跑到第 45 步仍停在微信主页/聊天页，**从未进入朋友圈**；两次运行均需人工强停。

**根因实测（缺陷 E：悬浮层吃掉应用底部导航点击）**：

| 悬浮窗 | 实测 frame | 影响 |
|---|---|---|
| 实时控制面板 | `[42,1524]-[1174,2374]` | 覆盖页面下半屏（任务运行期间） |
| **状态胶囊** | **`[264,2437]-[952,2542]`** | **正压在微信 Tab 栏「发现」命中区上** |

对照实验（同一坐标 `tap(766,2442)`、同一微信主页）：

| 条件 | 画面变化 |
|---|---|
| 无悬浮窗（`am force-stop com.dsh.agentlite` 后） | **36.6%**（成功进入发现页） |
| 任务运行中（胶囊存在） | **0.0%**（点击被吞） |

胶囊窗口使用 `FLAGS_TOUCH`（`FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCH_MODAL`）且设置了 `setOnClickListener`，
因此会吃掉自身区域内的全部触摸——Agent 点的正是「发现」Tab 的真实坐标，却永远点不动。
这与 §17.6 记录的「残留结果卡片吃点击」同源（同文件同类窗口），本轮是在**任务运行期间**再次复现。

**修复**：`OverlayController` 新增 `FLAGS_DISPLAY = FLAG_NOT_TOUCHABLE|FLAG_NOT_FOCUSABLE`，
底部胶囊改用该标志并移除点击监听；进度文案回归**纯展示**职责，控制面板仍可由「暂停接管」等卡片入口唤起。

**修复后真机复验（22:26:13–22:27:00，19 步完成）**：

| 步 | 动作 | 结果 |
|---|---|---|
| 1 | `app com.tencent.mm` | 启动微信 |
| 2 | `tap@753,2481` | **成功进入「发现」页**（修复前同坐标 0% 响应） |
| 3 | `tap@304,409` | **成功进入朋友圈**（前台 = `ImproveSnsTimelineUI`） |
| 4–12 | `tap@1106,2230` ×9 | 反复点空白区，未命中点赞入口 |
| 13 | `swipe` | 下拉刷新 |
| 14 | `tap@608,1320` | 点中动态正文 → 进入动态详情页 |
| 15 | `back` | 返回时间线 |
| 16–18 | `tap@1118,792`、`tap@1118,765` | 命中点赞按钮 |
| 19 | `tap@535,765` | 确认/退出 |
| — | `任务完成：已给朋友圈第一条动态（55分钟前那条）点赞，点赞列表已显示「报道北方」` | 22:27:00 |

**独立核验（不采信模型自述）**：22:27 截图显示该动态下方出现点赞条目 **「❤ 报道北方」**，
而 22:21 基线同一位置**没有任何点赞条目**；`task_history.jsonl` 记录成功终态。
**结论：点赞真实发生，端到端链路打通；用户可自行取消该赞。**

**遗留（不阻塞本次结论）**：
1. **路径效率**：时间线视图上模型未直接命中右下角「···」（手册抄本：点动态右下角「···」弹「赞」），
   而是经「刷新 → 进详情页 → 点赞」绕行；19 步对单次点赞偏高，且 19 > 12 故**未写入操作记忆库**（符合既有阈值）。
2. 胶囊改为不可点后，任务期间展开控制面板只剩卡片入口；如需常驻入口，应选**不覆盖应用底栏**的位置。

### 17.10 更新后源码审计与门禁复验（2026-10-02 12:36–13:00 +08）

本节针对最新脏工作区做证据审计，不把历史真机记录重复计为当前缺陷。审计范围为 `agent-lite/` 真身、`agent-unified/client/` 镜像、根门禁和新增 M0/M3 模块；本轮除门禁入口修复外未改产品执行逻辑。

> **状态更新（2026-10-02 16:37 +08，读本节前先读这段）**
> 本节是**审计当时**的证据快照，下列条目此后已被处理，勿再当作现存缺陷：
>
> | 本节条目 | 当前状态 | 落地位置 |
> |---|---|---|
> | A-1 `open_url` 可回放 | **已修**：移出 `REPLAYABLE` + 采集/还原分支拆除 + 旧库加载过滤 | §17.11-1、§17.12 A2/D1 |
> | A-2 `ask` 超时伪装回答 | **已修**：`AskResult` 三态 + `USER_TIMEOUT` 系统事件 + 连续超时终止；真机补出「主动放弃 ≠ 超时」并拆为 `ASK_SKIPPED` | §17.11-2、§17.12 C1–C3、A7 |
> | A-3 M3 未接线 | **已修**：`TOOL_REGISTRY` 接入 `exec()` 前网关（开关 `feature_tool_router`，默认关）；真机确认非法参数零设备动作 | §17.11-3、§17.12 B2 |
> | A-4 集成 E2E 以 root 为前提 | **已修（声明层面）**：降级为 root 专属增强诊断；已核实 `make check = go+contract+ui+jvm`，且 `ui` 目标为显式脚本白名单，真机脚本不在其中 | §17.11-4 |
> | A-5 默认自动同意条款 | **已修**：`autoAcceptTerms()` 默认 `false`；仍缺「首次启用风险确认弹窗」（Lite 版无该开关 UI 入口） | §17.11-5（残余见 §17.12 未修项）|
> | B 非原子写入 | **已修**：tmp + `fsync` + rename；损坏备份 `.corrupt`；写失败可见；真机验证损坏分支 | §17.11-6、§17.12 D2 |
> | B `hasCredential` 非通用分类器 / `ScreenSnapshot` 只掩码密码节点 | **仍未修**（通用脱敏与截图/OCR 负向测试留待后续主题） | —（待立项）|
> | C 两处门禁结论（「未排除 `open_url`」「未接入 M3 被当作设计事实守护」）| **已过时**：两条门禁断言均已反转为「必须接线/必须排除」 | §17.11-1/3 |
> | D「边界阻止通用自动化结论」列举的五条 | **四条已修**（`open_url`/`ask`/M3/协议默认），root E2E 已降级；结论本身仍成立，但依据需按 §17.12 重读 | §17.12 |
> | E-2 结构化标签要求 | **部分落地**：`USER_TIMEOUT` 已实现；**`TOOL_INVALID` 尚未落地**——M3 拒绝回灌仍是自然语言句子（「动作 X 参数校验未通过：…」），与「不伪装成普通话语」的纪律不完全一致 | 待办（见下）|
>
> 下方各项末尾的「最小修复」保留为**审计当时的建议原文**，不代表未修。

#### A. 已确认的当前问题

1. **P1：`open_url` 仍可进入操作记忆回放，与 R-5 规范冲突。**
   - 现象：规范 §4.4 R-5 要求包含 `remote`、`open_api` 等直达/跨设备动作的整条路径不得入库；但 `FlowPlan.REPLAYABLE` 仍包含 `open_url`（`agent-lite/src/com/dsh/agentlite/FlowPlan.java:68–71`），`captureStep` 与 `stepToAction` 也分别保存、还原 URL（`AgentEngine.java:1314–1317,1373–1375`）。
   - 根因：静态门禁只排除了 `open_api`，没有把 `open_url` 视为同类外部 Intent/DeepLink 副作用；回放最终通过 `Uri.parse(url)` 直接发起 `ACTION_VIEW`（`AgentEngine.java:2085–2088`），不经过模型重新确认当前语境。
   - 影响：历史任务中的 `tel:`、应用 Scheme、带参数网页或其他外部链接可能被零模型往返重放；记忆库中还会明文保存 URL 查询参数。当前未证明已发生真实误拨/误打开，但这是已由源码确认的策略边界缺口。
   - 最小修复：默认把 `open_url` 从可复现白名单移除；若产品必须保留，应建立严格的 scheme/host/参数白名单、任务语义绑定和单独终态复验，并同步 `flow_memory_guard.py`、JVM 回归与规范。

2. **P1：`ask` 超时仍以普通自然语言回答回灌模型。**
   - 现象：`askUser()` 在 5 分钟无回答时返回「用户未回答或超时」（`AgentEngine.java:2264–2277`），调用方不区分明确回答、停止和超时，统一追加为“用户回答”继续下一轮（`AgentEngine.java:828–843`）。
   - 根因：用户回答契约仍是可空字符串，而不是 `ANSWERED / TIMEOUT / STOPPED` 结构化结果；超时没有硬拒绝敏感动作或直接进入暂停/拒绝终态。
   - 影响：模型可能把“未回答或超时”误解为允许继续，尤其不应发生在发送、删除、协议、支付等不可逆动作前。本轮只确认了静态控制流，未宣称已在真实敏感界面完成误执行复现。
   - 最小修复：返回不可混淆的结构化结果；只有 `ANSWERED` 的用户提交内容进入上下文，`TIMEOUT` 直接拒绝当前敏感动作并结束/暂停，`STOPPED` 走用户停止终态；补超时后设备零动作回归。

3. **P1：M3 `ToolRegistry`/参数校验仍未接入 `AgentEngine`，不能作为运行时安全边界。**
   - 现象：`ToolRegistry.validate()` 已能拒绝未知工具、缺参、类型、范围、枚举和未知字段（`agent-lite/src/com/dsh/agentlite/tools/ToolRegistry.java:35–43`），但 `AgentEngine.java` 没有 `ToolRegistry` 引用；`FeatureFlags.toolRouter()` 默认关闭（`FeatureFlags.java:40`）。
   - 根因：M3 当前是独立声明/桌面测试层，旧 `ActionSchema` 检查只确认动作名登记，不会自动调用 M3 参数校验器。
   - 影响：当前产品路径仍依赖旧执行分支的字段读取与各动作自有检查；“M3-02 参数校验已完成”只能理解为模型层/纯 Java 声明完成，不能理解为“无效参数绝不触碰设备”已成立。静态门禁明确把“`AgentEngine` 未接入 M3 Registry”列为通过项，因此这不是测试失败，而是功能尚未接线。
   - 最小修复：在执行前增加受开关控制的 Router/Validator 网关，校验失败不得调用 `exec()`；保持旧路径回退时也有等价拒绝语义，并补未知字段、越界坐标、敏感参数和停止取消的设备侧测试。

4. **P1：集成真机脚本把 root 写成必需测试前提，违反全项目零 root 基线。**
   - 现象：`tests/integrated_features_e2e.py` 文件头明确写“root 真机”，私有目录读写集中经过 `root()` 与 `su -c`（`tests/integrated_features_e2e.py:8–9,35–51`）。
   - 根因：脚本直接读写 `/data/data/com.dsh.agentlite/...` 的 prefs 和 `flowmem.json`，没有应用内或零 root 的备份/预置接口。
   - 影响：该脚本不能作为 §12.1 所要求的无 root 主链路或发布门禁证据；普通未 root 设备即使主功能可用，也无法通过这条集成验证。此前日志中的 `16/16` 只能标为 root 环境增强验证，不足以证明零 root 基线。
   - 最小修复：将脚本明确降级为 root 专属诊断增强并从必跑门禁剥离，或增加零 root 方案：应用内测试入口、调试构建专用导出/导入、或仅通过公开 UI 完成备份与断言。禁止把模型/外部输入继续拼进 root shell。

5. **P1：无限制模式默认自动同意条款/隐私政策，仍是明确的法律与产品风险。**
   - 现象：`Prefs.autoAcceptTerms()` 默认值为 `true`（`Prefs.java:44`）；主循环检测到协议弹窗后调用 `autoAgreeTerms()`，自动勾选并点击同意（`AgentEngine.java:639–644,1607–1628`）。
   - 根因：这是 2026-09-18 已记录的产品决策，不是偶发回归；“无限制模式”把不可逆授权从人工接管改为自动代点。
   - 影响：用户可能未逐条阅读即完成服务协议、隐私政策或个人信息处理授权；它与默认的敏感场景人工确认原则存在显著张力。
   - 最小修复：默认关闭，或至少在首次启用时做明确、可追溯的风险确认；将协议、隐私、权限和付款分开设定，不以一个“无限制”开关覆盖全部法律授权。若维持现状，发布说明和 UI 必须明确告知代点范围与不可逆后果。

#### B. 记忆库与隐私边界：已确认的残余风险

- **凭据闸门不是通用敏感数据分类器。** `FlowPlan.hasCredential()` 只拦截显式“密码/验证码/口令”和 4 位以上纯数字形态（`FlowPlan.java:169–185`）；`FlowMemory.toJson()` 会把原始任务、归一化文本、步骤 `text`、`open_url` 地址写入应用私有的明文 JSON（`FlowMemory.java:243–275`）。因此普通姓名、地址、订单号、带 token 查询参数的 URL 等不会因为“不是数字密码”而自动脱敏。操作记忆不上云降低了扩散面，但不等于本地文件、备份、root 或调试读取不可见。
- **文件写入不是原子提交，异常被静默吞掉。** `FileWriter(..., false)` 直接覆盖 `flowmem.json`，写失败只记录为空操作、不向 UI 或诊断层报告（`FlowMemory.java:231–240`）；读取损坏文件也直接按空库处理（`FlowMemory.java:46–62`）。这不会把任务判失败，但进程死亡/磁盘异常时可能丢失整个加速库，且用户无法区分“没有记录”和“记录损坏”。最小修复是临时文件 + `fsync`/rename、版本/大小上限、损坏备份和可见诊断。
- **结构化契约只强制掩码密码节点。** `ScreenSnapshot` 自己明确只对 `password` 节点掩码，并要求采集入口预先完成其他隐私策略（`contracts/ScreenSnapshot.java:9–12,77–85`）；`NodeInfo` 另有验证码数字脱敏，但截图/OCR/非数字秘密没有同等由契约保证的过滤。当前证据足以确认“契约不是完整隐私证明”，不足以断言某个具体版本已经泄露真实凭据；截图/OCR 路径仍需专门的负向测试。

#### C. 本轮验证结果与状态边界

- 首次 `make check` 失败并已定位为门禁入口缺陷：`Makefile` 的 `ToolRegistryTest` 源列表漏掉 `ValidationResult.java`，导致桌面 JVM 编译失败；补入该源文件后，`make check` 全部通过。当前结果包括 Go 单测、契约 26/26、UI 文案/控件、各静态门禁、M3 JVM 58/0、PopupGuard JVM 27/0。
- `python3 tests/flow_memory_guard.py`：40 项通过、0 失败、1 项真机待验；该绿色结果不能掩盖其未排除 `open_url` 的规范偏差。（**已过时**：门禁现要求排除 `open_url`，45/0，见本节顶部状态更新）
- `python3 tests/m3_tool_registry_guard.py`：31 项通过、0 失败；其中“`AgentEngine` 未接入 M3 Registry”被当作当前设计事实守护，而不是运行时安全已完成。（**已过时**：断言已反转为「必须接线且网关先于 `exec()`」，33/0）
- `bash agent-unified/scripts/sync-sources.sh --check`：`agent-unified/client` 与 `agent-lite` 逐字节同步通过。
- 本轮没有新的 OnePlus 真机执行，因此 §17.2 的额度/键盘历史状态继续按既有记录解释：源码和静态门禁显示额度计算、`adjustResize` 与 IME 辅助已存在，但不能把本轮免设备验证写成新的真机复验；M3 行为、`ask` 超时、截图/OCR 隐私仍未设备验证。（**已过时（部分）**：M3 行为与 `ask` 超时已于 §17.12 完成真机行为验证；**截图/OCR 隐私仍未经设备负向测试**，见本节状态更新）

#### D. 是否实质降低手机自动化门槛

**结论：对常见、低风险、可验证任务，已经实质降低；对通用手机自动化，证据不足。**

已被证据支持的降门槛能力包括：自然语言归一化与结构化转译、已装应用匹配、`app`/设置直达、无障碍到纯视觉的降级、系统状态栏污染识别、弹窗处置、任务停止、失败回退和本地流程复现。真机曾验证“打开计算器”模型路径完成，以及第二次记忆库命中后约 1.2 秒零模型往返；微信纯视觉和底部胶囊触控缺陷也有修复后的真实证据。

但以下边界阻止“通用自动化/零风险”结论：`open_url` 回放策略与 R-5 冲突，`ask` 超时没有拒绝型结构化结果，M3 工具校验未进入执行网关，协议默认自动同意，集成 E2E 仍 root 前置，且当前完整真机基线并未覆盖所有动作、ROM 和敏感场景。因此更准确的产品表述应是“降低常见任务的技术门槛，并在部分 ROM 上提供可回退执行”，而不是“用户无需理解权限、风险和设备差异即可自动完成任意任务”。（**依据已更新**：前四条与 root 前置均已处理，见本节顶部状态更新与 §17.12；末尾「真机基线未覆盖所有动作/ROM/敏感场景」仍然成立——本轮只覆盖确定性 mock 场景 + 一次真实超时静置。）

#### E. 给 DeepSeek 4.1 Flash 的实施约束

1. 继续使用单动作、短 JSON、固定动作词表；把参数拒绝、敏感暂停、完成复验放在引擎，不依赖 Flash 自觉。
2. 将 `USER_TIMEOUT`、`REPLAY_STALE`、`REPLAY_INTERRUPTED`、`TOOL_INVALID` 等结果作为结构化上下文标签，不把它们伪装成普通用户话语。（**部分落地**：`USER_TIMEOUT` 已实现并经真机验证；`TOOL_INVALID` 尚未落地——M3 网关的拒绝回灌仍是自然语言句子，见本节顶部状态更新）
3. 先修复 `open_url` 回放边界和 M3 Router 接线，再扩充动作；每次只改变一个策略主题，并以 `make check` + 真机负向场景验收。
4. 记录“模型看到的节点/截图、引擎拒绝或改写、实际动作、复验结果、最终归因”五元证据，避免把感知问题归咎于模型。
5. 继续保留 `app` 优先、禁止应用商店绕行、动作后验证、失败换策略和敏感人工确认；不要用增大 `max_tokens` 掩盖工具契约或环境判定缺陷。


### 17.11 §17.10 五项 P1 修复落地（2026-10-02 13:52 起 +08）

按 §17.10.E 实施顺序（先修 `open_url` 回放边界与 M3 接线，每次一个策略主题）完成
五项 P1 与记忆库落盘健壮性修复。除注明外全部为源码 + 门禁 + 规范同步的免设备改动。

#### 1. P1-1 `open_url` 移出可复现白名单（R 系列 → 设计文档 §9.3）

- `FlowPlan.REPLAYABLE` 删除 `open_url`；javadoc 记录判定理由（外部 Intent/DeepLink
  参数与当次语境绑定，照抄旧 URL 等于零确认重放 `tel:`/应用 Scheme/带 token 查询参数）。
- 采集与还原双侧拆除：`captureStep`/`stepToAction` 删除 `open_url` 分支——采集侧由
  白名单入口判定直接作废整条；还原侧（旧库遗留记录）落入 `default` 放弃复现、回退模型。
- 旧库迁移防线：`FlowMemory.list()` 跳过含 `open_url` 步骤的记录（`hasOpenUrl`），
  防止经历史记录绕过白名单；下次任意 save 自然清除出库。
- 同步：设计文档 §9.3 动作清单补 `open_url`；`flow_memory_guard.py` FM-6 banned 列表
  + 旧记录过滤断言；`FlowMemoryTest` 新增「含 open_url → 拒绝」用例（56/0）。
- 附带收益：URL 明文不再进 `flowmem.json`，§17.10.B「带 token 查询参数入库」的
  最大暴露面被消解。

#### 2. P1-2 `ask` 超时结构化三态

- 新增 `AskResult`（ANSWERED / TIMEOUT / STOPPED）与 `MAX_ASK_TIMEOUTS=2`；
  `askUser` 不再返回可空字符串。
- 只有 `ANSWERED` 的用户文本进入模型上下文；`TIMEOUT` 首次走 `enterPause` 交还用户
  并注入 `USER_TIMEOUT` 结构化系统事件标签（明确禁止在无确认时执行不可逆动作），
  连续超时到阈值直接 `failTask` 终止；`STOPPED` 走用户停止终态，不再伪装成回答回灌。
- 门禁：`action_schema_guard.py` 新增 A6 节（结构化契约、超时不回灌、连续超时终止
  三组断言），12 通过 / 0 失败。

#### 3. P1-3 M3 ToolRegistry 接入执行网关

- `AgentEngine` 持有 `TOOL_REGISTRY = ToolRegistry.builtIn()`（不可变快照）；
  `feature_tool_router` 开关开启时在 `exec()` 之前校验参数——未知参数/缺参/类型/
  范围/枚举错误**不触碰设备**，回灌一次短错误，连续非法（>3 次）终止任务。
- `actionToArgs`：JSONObject → Map 转换剔除 `action` 键（避免 unknown-parameter 误拒）、
  整数值浮点归一化为整数串（避免 org.json 把 `3` 承载为 `3.0` 造成假阴性）。
- 开关默认仍关闭（灰度语义不变，旧路径等价拒绝 = `isRegistered` + `exec` 自检）。
- 门禁契约同步演进：`m3_tool_registry_guard` / `m3_tool_validation_guard` 的
  「未接线」断言反转为「已接线 + 网关先于 exec + 开关控制」；`m0_feature_flags_guard`
  第 57 行从「不出现 featureFlags() 调用」演进为「所有读取必须且仅是 toolRouter() 判定，
  网关调用位于开关之后」。33/0、29/0、31/0。

#### 4. P1-4 root E2E 降级声明

- `tests/integrated_features_e2e.py` 文件头明确**root 专属增强诊断**定位：非门禁、
  非零 root 基线证据（`make check` 与发布门禁均不含它），零 root 路径走
  `flow_memory_e2e_mock.py`；root 命令全部来自脚本内固定白名单路径、参数经
  `shlex.quote` 转义，不拼接模型/外部输入。§12.1 例外表已登记，无需重复登记。

#### 5. P1-5 `autoAcceptTerms` 默认关闭

- `Prefs.autoAcceptTerms()` 默认值 `true` → `false`：与敏感场景人工确认的安全默认
  对齐；历史已显式存过 `true` 的设备不受影响（SharedPreferences 已存值优先）。
- 引擎与 Prefs 的 javadoc 同步改写（记录 2026-09-18 → 2026-10-02 两次决策的脉络）。
- 残余：Lite 版无该开关的设置 UI 入口，「首次启用时风险确认弹窗」无从落地——
  已列为后续项（补 UI 入口时一并实现确认对话框）。

#### 6. P1-B FlowMemory 原子写入与损坏可见化

- `save()` 改为临时文件（`flowmem.json.tmp`）+ `getFD().sync()` + `rename` 原子提交；
  rename 失败（个别厂商 FUSE 层）退回直写保底。进程死亡/磁盘异常最坏丢一次增量，
  不再把整个库截断成半个 JSON。
- 读取损坏时把现场备份为 `flowmem.json.corrupt`（不覆盖已有备份）再按空库继续，
  用户与排障可区分「没有记录」与「记录损坏」；写失败从静默空操作改为 `Log.w` 留痕。
- 门禁：`flow_memory_guard.py` 新增 FM-11 三项断言（45 通过 / 0 失败）。

#### 验证边界（本节不越界宣称）

- `make check` 全绿（Go 单测、契约 26/0、UI/SPEC-UX/FuzzyFlow/FlowMemory 45/0、
  PopupGuard、全局复核、M0/M3 门禁、桌面 JVM 回归含 FlowMemoryTest 56/0）；
  `agent-lite.apk` 重新构建成功（19,574,129 字节）；`sync-sources.sh` 镜像同步通过
  （client=23 未提交条目）。
- **真机行为验证已于同日补做**（见 §17.12，OnePlus PKX110）：ask 三态、M3 网关设备侧
  零动作、`open_url` 不入库与旧记录不回放、损坏库恢复均已取得行为级证据；
  ask/M3/open_url 的源码级结论不再单独悬置。原子写的**崩溃时序**仍未复现
  （不可精确控制进程死亡时刻），但损坏分支与 `.corrupt` 备份已真机验证。
- `hasCredential` 仍是形态闸门（姓名/地址等非凭据敏感文本不在拦截目标内）——
  本轮只消解 `open_url` 明文 URL 这个最大暴露面，通用脱敏留待后续主题。


### 17.12 §17.11 待真机项的行为级验证（2026-10-02 15:35–16:37 +08 · OnePlus PKX110）

**设备与设施**：OnePlus PKX110（ColorOS / Android 16，本机 root 诊断机）。新增验证脚本
`tests/p1_device_verify.py`（A–E 五阶段、26 条断言、PASS/FAIL/**SKIP** 三分类；文件头
明确 **root 专属增强诊断**定位——非门禁、非零 root 基线证据，零 root 路径仍是
`make check` 静态门禁 + 桌面 JVM 回归）。模型侧用 `tests/mock_llm_server.py` 新增三个
确定性场景：`openurl`（open_url 后收尾）、`ask`（提问直到拿到「用户回答：」或
`USER_TIMEOUT` 标签）、`m3invalid`（越界 px → 未知字段 foo → 缺 direction → 合法 key → done）。

**结果矩阵：26 通过 / 0 失败 / 1 需人工**

| 组 | 断言 | 结论 |
|---|---|---|
| A1 | flow 首跑入库 1 条；同款任务二次**零模型往返**复现 | PASS |
| A2 | `open_url` 执行成功，但**无任何入库日志** + 库文件不含 open_url 步骤 + 记录数不变 | PASS |
| B1 | 关网关基线：`m3invalid` 序列走完、无拒绝日志 | PASS |
| B2 | 开网关：三个非法动作**各被拒一次**，理由覆盖 `OUT_OF_RANGE(px)` / `UNKNOWN_PARAMETER(foo)` / `MISSING_REQUIRED(direction)` | PASS |
| B2 | 网关开时设备动作**恰好只剩 1 个合法项**（`key 4` 放行执行），任务被引导至完成 | PASS |
| C1 | 放弃确认（「不执行」）→ 独立终态终止；不被谎报为 5 分钟超时；待确认操作未执行 | PASS |
| C2 | 回答提交后完成，模型上下文确实收到「用户回答：」 | PASS |
| C3 | **真实静置 5 分钟**（16:19:58）→ 结构化 TIMEOUT（第 1 次）+ 暂停 + 整个窗口**零设备动作** | PASS |
| C3-4 | 交还后 `USER_TIMEOUT` 以系统事件回灌模型 | 16:20 人工补验 PASS（见下），脚本轮 SKIP |
| D1 | 含 open_url 的旧记录**不命中** + 加载侧 `Log.w` 留痕 + 任务回退模型规划完成 | PASS |
| D2 | 捏坏 `flowmem.json` → `.corrupt` 备份 + 按空库继续完成 + 新库重新可写 | PASS |

**C3-4 的证据链（16:20，人工补验）**：悬浮窗「我已完成，交还 AI」不在 uiautomator 枚举范围，
改用截图色块定位（浅蓝按钮质心 `608,2243`）→ `input tap` → 日志「用户已交还控制权，AI 继续执行」
→ 引擎下一轮把 `USER_TIMEOUT` 作为**系统事件**回灌 → mock 的 `ask` 场景只在 user 上下文含
`USER_TIMEOUT` 时回专用回复 → 最终任务摘要为「任务完成：已收到超时事件」。
即：**超时既没有被伪装成用户话语，也没有解禁待确认动作**。

**本轮发现的新缺陷（未修，按「一次一个主题」留待下一轮）**

1. **悬浮窗卡片渲染不稳定**：16:20 轮 TIMEOUT 暂停时「请手动操作」卡片可见（人工借此完成
   C3-4）；16:34 轮同一路径、同一构建未渲染卡片（截图仅剩 AlertDialog）。`appops get
   com.dsh.agentlite SYSTEM_ALERT_WINDOW` = `default`，`rejectTime` 约 10 天前。
   影响：AgentLite 不在前台时，「暂停」态的唯一恢复入口在悬浮窗上——卡片不显示即**没有出口**
   （前台时仍可用主界面「停止」）。根因未定位，需专门排查（overlay attach 时机 / 权限态）。
2. **超时后提问对话框未关闭**：暂停期间 MainActivity 的 AlertDialog 仍显示「回答 / 不执行」，
   此时点「不执行」只写入 `askAnswer`，而 `askLatch` 早已在超时后返回——点击被静默丢弃，
   用户以为任务已停、实际停在暂停态等交还。属 UI 生命周期缺口，修复需引擎通知 UI 关闭弹层。

**测试设施陷阱（本轮实测，非产品缺陷，已固化进脚本注释）**

1. `adb shell su -c 'sed …'` 会剥掉 XML 属性引号（写成 `name=base_url`）→ SharedPreferences
   解析失败回退重建、注入键全丢，表现为「任务启动」都不出现（UX-1 凭据守卫拦截）。
   改为主机端拼**完整** XML 后 push + `cat >` 原地写入（保属主）。
2. mock 的步进若依赖「msgs 里 assistant 计数」会失真：引擎**正常路径不向对话累积 assistant
   历史**（纯视觉模式每轮重建消息），实测序列停在 `key 4`（BACK）把 app 自己退到桌面后
   无限打转。改为 mock 侧有状态计数，并由 `DELETE /` 复位——同名场景反复用作对照组时**必须**
   复位，否则第二轮第一次调用直接拿到序列末项 done（表现为「任务秒完成、零设备动作」）。
3. logcat 的 main/crash 双缓冲把同一条日志**镜像两份**：拒绝日志与「第 N 步：执行」计数一律
   ×2，统计前必须去重（否则 3 条拒绝误判 6 条）。
4. 悬浮窗（`TYPE_APPLICATION_OVERLAY`）不在 uiautomator 枚举范围，`dump` 里只有 AlertDialog
   节点；定位 overlay 按钮只能靠截图色块（`find_blue_overlay_button`，无 PIL 时降级为 SKIP）。
5. **备份套娃**：验证脚本的「还原源」若在测试态下重建，会把 mock 配置固化成用户态（实测三份
   备份层层含 mock 端点，最后分不清真实用户态）。已在脚本加护栏：prefs 含 `127.0.0.1` 或
   `test-mock-key` 时**拒绝**建立备份并中止。

**真机暴露的 P2 缺陷已修（同日）**：用户主动放弃确认（原「跳过」按钮）与「5 分钟无回答」
共用 `ASK_TIMEOUT`，日志谎报超时并进入暂停。改为独立终态 `ASK_SKIPPED`（终止任务、不执行
待确认动作），按钮文案改为语义明确的「不执行」；`action_schema_guard.py` 新增 A7 两条断言（14/0）。

**收尾状态**：验证结束已把设备 prefs 还原为验证前用户态（仅 `intro_shown`）、删除验证期
`flowmem.json`、还原无障碍绑定、移除 `adb reverse`。设备上运行的 APK 为本轮构建
（含 `ASK_SKIPPED` 与「不执行」文案，19,574,129 字节，dex 内字符串已核对、旧文案 0 残留）。


## 18. 当前进度评估与未来开发入口（2026-10-06）

### 18.1 范围、阶段与证据口径

本次归属 `repo`，以 `agent-lite` 当前工作树为 Android 产品真身；`agent-client` 是另一客户端，`agent-unified/client` 是镜像，不能混用其能力或安装指引。保留原生 Java、SDK 命令行构建、无障碍执行架构。未来规划已存在于[成熟模块整合规划](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md>)，本次增量续写其 §23，而非另起手册。服务端专用开发手册不承担全产品规划。

**阶段结论：具备较完整执行链与离线回归的工程原型／受控验证阶段，尚非普通无 root 用户可独立、可靠、安全使用的发布候选。** 可输入文字，调用模型，采集节点/截图，启动应用、输入文字和执行手势，进行有限复验、提问、停止与流程复用。但首用入口、安全授权、成功证据、用户控制与隐私仍有发布阻断项。没有按类数量计算完成率，也没有本轮用户试用或端到端成功率。

- 基准：分支 `shell-command-backend`，HEAD `2c73a9f`（2026-09-18）；工作树已有大量后续修改/新增文件。**本评估针对工作树，不等于提交版、已安装 APK 或线上配置。**不回滚、不提交、不覆盖既有修改。
- 本轮证据：实际源码、公开默认值、四项静态门禁、桌面 JVM 行为回归、官方公开资料。未读取本地密钥、设备私有备份或真实用户数据；未操作真实账号、购买、充值、部署或发布。
- 历史证据：早期荣耀无 root 基础通道、§17 生活任务记录、§17.12 root 诊断机 mock 验证分别保留。**基础通道可用 ≠ 当前普通用户完整首跑；root/mock 通过 ≠ 无 root/真实模型任务通过。**本轮未重演历史设备记录。
- Hindsight 已检索/读取的四页均为 `Generating content...`，未作为实现证据。
- 状态使用：已实现且有验证证据（注明层级）、已实现但验证不足、部分实现、仅有设计、尚未开始、存在阻塞、信息不足。“未检查”不等于“尚未开始”。

### 18.2 模块进度矩阵

状态评价整个用户能力，不把类存在等同于端到端完成；源码行号改码后应按符号重定位。

| 模块/能力 | 现有能力 | 状态 | 证据与边界 | 主要缺口 | 用户影响 | 下一步 |
|---|---|---|---|---|---|---|
| 安装与首次使用 | Android 11+ APK、首启说明、设置入口 | 部分实现 | [Manifest](<agent-lite/AndroidManifest.xml>)；[README](<agent-lite/README.md>)仍有另一客户端目录/包名；UX-1 手工项未验 | 无电脑安装到首成功未闭环 | 装错版本或误以为必须 ADB | T01/T07，统一普通用户入口 |
| 账号、模型接入与费用 | 登录、网关、自带 Key、官方充值跳转 | 存在阻塞 | [入口:387–390](<agent-lite/src/com/dsh/agentlite/MainActivity.java#L387-L390>)、[默认:170](<agent-lite/src/com/dsh/agentlite/Prefs.java#L170>)与[路由:194–227](<agent-lite/src/com/dsh/agentlite/LlmClient.java#L194-L227>)矛盾；未真机复现 | 默认自带 Key 标志挡住已登录无 Key；密钥不可找回；units 不直观 | 登录后仍被要求配置、害怕扣费 | T02 统一凭据/费用路由 |
| 权限与运行条件 | 无障碍引导、延迟录音/通知授权、服务恢复 | 部分实现 | [MainActivity:359–405](<agent-lite/src/com/dsh/agentlite/MainActivity.java#L359-L405>)；[A11yGuard:22–28](<agent-lite/src/com/dsh/agentlite/A11yGuard.java#L22-L28>) | `WRITE_SECURE_SETTINGS` 声明不等于普通用户获权；主动撤销需优先 | 系统页迷路、服务状态误解 | T05/T07，分层权限与退出 |
| 输入与语音 | 文字、录音、显式记忆 | 部分实现 | [AsrRouter:42–87](<agent-lite/src/com/dsh/agentlite/AsrRouter.java#L42-L87>) | 配置云端后先云端，与“离线优先”注释不同；本地库/模型需就绪；本轮未找到 TTS 接线 | 语音失败需文字兜底，不能承诺开箱离线 | T08，识别后可改再执行 |
| 自然语言与澄清 | 归一化、转译、规划、ask | 已实现但验证不足 | [规划接线:546–560](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L546-L560>)；[转译回归](<tests/FuzzyTranslatorTest.java>)本轮通过 | 缺口语、否定、重名联系人真实样本；问答自由文本 | 多轮纠错或认错对象 | T08，槽位校验与少量选项 |
| 屏幕感知与状态 | 多窗口节点、截图、纯视觉降级、密码节点掩码 | 部分实现 | [截图:538–562](<agent-lite/src/com/dsh/agentlite/AgentA11yService.java#L538-L562>)；[节点掩码:58–78](<agent-lite/src/com/dsh/agentlite/NodeInfo.java#L58-L78>) | 截图未见等价掩码；模型返回与当前快照未绑定 | 旧坐标误触、截图泄露 | T03/T06，快照身份与出网控制 |
| 规划、执行、复验 | 单动作、动作词表、包名/屏幕弱复验、循环上限 | 部分实现 | [AgentEngine:913–1000](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L913-L1000>)；动作静态门禁通过 | 无证据 done 追问一次后可放行；M3 默认关 | 错报成功并写入流程记忆 | T03/T04，按目标判定终态 |
| 敏感确认与安全 | 支付/验证关键词接管、协议默认不代点、ask 四态 | 部分实现 | [SensitiveGuard:42–49](<agent-lite/src/com/dsh/agentlite/SensitiveGuard.java#L42-L49>)；[ask:861–911](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L861-L911>) | 空节点不扫描；发送/发布/删除未统一硬控；批准未绑定对象/金额/内容 | 提示词漏判可产生不可逆副作用 | T03，统一确定性策略 |
| 暂停、停止、接管 | 停止取消模型请求、接管/交还、超时暂停 | 部分实现 | [停止:283–306](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L283-L306>)；[问答 UI:624–654](<agent-lite/src/com/dsh/agentlite/MainActivity.java#L624-L654>)；§17.12 悬浮/弹层缺陷 | UI 与引擎超时不联动；复用控制需统一；没有通用撤销 | 停止不可达、旧回答误用、误解“停止=撤销” | T05，统一生命周期 |
| 历史、记忆与复用 | 历史、事实记忆、操作记忆默认开、删除入口、URL 旧库过滤 | 部分实现 | [FlowMemory:45–83](<agent-lite/src/com/dsh/agentlite/FlowMemory.java#L45-L83>)、[260–282](<agent-lite/src/com/dsh/agentlite/FlowMemory.java#L260-L282>)；静态 45/0、JVM 通过 | 明文任务/文本；成功入库依据不足；rename 失败退直写 | 误学流程、隐私遗留与损坏 | T04/T06/T09 |
| UI、适老化、可访问性 | 简化主页、状态文案、尺寸令牌 | 部分实现 | [主布局](<agent-lite/res/layout/activity_main.xml>)；[尺寸令牌](<agent-lite/res/values/dimens.xml>)：正文 15sp、常规触控 44dp、主按钮 48dp | 单行省略/固定高度；无 200% 字体/TalkBack 实测 | 确认读不全、停止难找 | T05/T07，列为 P0 |
| 网络、后台与重启 | 超时、429 重试、取消、前台服务/Job 守护 | 部分实现 | [LlmClient:281–338](<agent-lite/src/com/dsh/agentlite/LlmClient.java#L281-L338>)；[保活:123–138](<agent-lite/src/com/dsh/agentlite/KeepAliveService.java#L123-L138>) | 服务重启不等于任务恢复；关键状态内存化；重试/换端点影响成本 | 长等、重进不知此前结果 | T05/T06，安全恢复不盲重放 |
| 日志、测试、发布与隐私 | 脱敏日志、纯逻辑回归、账号令牌加密、禁备份 | 部分实现 | [日志:45–74](<agent-lite/src/com/dsh/agentlite/EngineLog.java#L45-L74>)；[TokenStore:109–125](<agent-lite/src/com/dsh/agentlite/TokenStore.java#L109-L125>)；[Makefile](<Makefile>) | API/ASR Key 普通偏好；登录默认同步；缺无 root 发布证据 | 数据/费用误解、故障不可诊断 | T01/T06/T10 |
| M0/M3 基础切片 | 开关、契约、ToolRegistry/Validation、主循环可选校验 | 已实现且有验证证据（桌面层） | [FeatureFlags](<agent-lite/src/com/dsh/agentlite/FeatureFlags.java>)；[M3 接线:955–976](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L955-L976>)；本轮 JVM/M3 静态通过 | 不等于完整 Policy/Router；复用直接 exec | 不能称“统一安全层已完成” | 接续 M3-03/04 与 T03 |
| Policy/Perceptor v2/Trace/ReplayGuard 等完整整合 | 有契约、M1–M10 规划和关闭的开关 | 仅有设计（完整运行时整合） | [整合规划](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md>)；开关声明不是接线证明 | 核查范围内未见完整目标架构 | 不能用计划替代实际可用性 | 按 §23 分小切片实施 |

### 18.3 发布阻断项与易被误读的进度

1. **入口矛盾（P0，源码证明，设备复现待补）**：`useOwnKey()` 默认 true，入口先拒绝无 Key；登录收尾 [afterAuthOk](<agent-lite/src/com/dsh/agentlite/LoginActivity.java#L197-L209>)、[saveAccount](<agent-lite/src/com/dsh/agentlite/Prefs.java#L391-L424>)未改变该选择，但模型客户端允许登录网关。需共同解析器与新装/升级真值表。
2. **完成证据不足（P0）**：[done](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L913-L947>)二次可接受模型摘要，并写入记忆。屏幕变化仅证明“可能发生动作”，不证明目标达成；证据不足必须“不确定”，不入成功库。
3. **策略覆盖不足（P0）**：提示词不能代替发送、发布、删除、付款硬闸门。主循环与[复用直接 exec](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L1250-L1255>)应共用校验/策略。[requestAuth](<agent-lite/src/com/dsh/agentlite/AgentEngine.java#L1666-L1685>)无覆盖层会返回允许；本轮仅定位到定义，不能把其 UI 当作通用授权已接线的证据，需补调用链测试。
4. **控制生命周期（P0）**：§17.12 的旧 ask 弹层/悬浮窗问题未因静态测试通过而消失；引擎超时而 Activity 仍保留 pendingAsk。需 requestId、失效通知、可达停止和恢复前重新感知。停止不承诺撤销既成动作。
5. **数据路径告知不实（P0）**：[凭据卡:409–413](<agent-lite/src/com/dsh/agentlite/MainActivity.java#L409-L413>)写“设备与本机”，但云模型会出网；[syncEnabled:291–304](<agent-lite/src/com/dsh/agentlite/Prefs.java#L291-L304>)在未选择且已登录时 true，[登录后](<agent-lite/src/com/dsh/agentlite/LoginActivity.java#L217-L223>)启动同步。登录不能自动替代独立同步同意。
6. **“已修”需要限定（P1）**：[FlowMemory.save](<agent-lite/src/com/dsh/agentlite/FlowMemory.java#L260-L282>)主路径 tmp/fsync/rename，但 rename 失败退回直写，不是所有路径原子提交。[截图间隔](<agent-lite/src/com/dsh/agentlite/AgentA11yService.java#L550-L562>)当前为 900ms，不能沿用旧 README 数值。[RootShell.ensureA11y](<agent-lite/src/com/dsh/agentlite/RootShell.java#L114-L118>)覆盖服务列表，需验证不影响 TalkBack；此增强不能成为普通恢复路径。

**普通无 root 主链路**：节点/截图/手势/输入有公开 API 路径，不需要 root 才能设计核心流程；但本轮未走通“安装→必要设置→任务→结果”，且默认入口确有控制流矛盾。开发 README 的电脑/ADB、官方充值的离开 App/建 Key/粘贴、无障碍与可选系统设置，都是实际门槛，不能由主页无技术词抵消。

### 18.4 本轮实际检查及未验证项

| 检查 | 结果 | 不可外推的结论 |
|---|---|---|
| `pwd`、Git 状态/分支/最近日志 | 工作区与脏树已核对 | 不证明设备 APK 或线上服务相同 |
| `python3 tests/spec_ux_001_guard.py` | 17 通过、0 失败、1 需真机；退出 0 | 入口“有守卫”未测凭据真值表；“有完成复验”未测假成功；五分钟首成功仍未验 |
| `python3 tests/action_schema_guard.py` | 14 通过、0 失败、1 警告；退出 0 | 警告是另一客户端缺少 `list_apps/open_api/remote`；不证明设备动作有效 |
| `python3 tests/flow_memory_guard.py` | 45 通过、0 失败、1 待真机；退出 0 | 不证明全部持久化失败分支或业务终态 |
| `python3 tests/m3_tool_validation_guard.py` | 29 通过、0 失败；退出 0 | 不证明默认启用或所有执行入口必经 |
| `make jvm` | 七组桌面 Java 回归通过，退出 0 | 覆盖签名、购买差异、转译、FlowPlan、契约、工具校验、弹窗；无设备/真实网络验证，仅生成检查产物 |
| 官方资料读取 | 公告、API、视觉、价格/限制、平台协议与隐私政策 | [能力登记见 §23.2](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md#232-de-41flash-身份能力与接入边界>)；不是实际模型调用 |

本轮未执行 APK 构建、Go 全套、完整 `make check`、设备脚本、付费模型、性能/成本或目标用户测试；是选择最小相关离线验证集，不表示这些测试失败。未检查线上实际模型配置；未探测设备连接。历史 `26/0/1` 或全套门禁只按原日期/条件引用。后续设备验证使用已授权测试设备与虚构联系人，不操作真实交易。

### 18.5 手册衔接

保留 §16/§17 历史；其“快速、低成本”、实施模型称呼不是本轮效果证据。协议自动代点新装默认 false，旧设备显式 true 不会自动改成 false。下一阶段任务、10 个代表用例、测量口径和发布门禁集中在[整合规划 §23](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md#23-面向老年人与手机入门者的开发续篇2026-10-06>)；优先修安全可用主链路，不以更多动作或虚拟屏替代。

### 18.6 T01 凭据切片与 T02 路由修复（2026-10-07）

本次为用户明确选择的代码实施轮，2026-10-08 续接收尾。§18.1–§18.4 保留 10-06 审计快照：其中登录无 Key 的入口矛盾、自动跨付费通道回退和“只在本机处理”文案已在本次修正；其他发布阻断项并未随之完成。

- **入口与请求统一**：[CredentialRoute](<agent-lite/src/com/dsh/agentlite/CredentialRoute.java>)提供纯逻辑结果，[主页](<agent-lite/src/com/dsh/agentlite/MainActivity.java>)与 [LlmClient](<agent-lite/src/com/dsh/agentlite/LlmClient.java>)共用。默认无 Key 但已登录可走账号；显式选择自带而缺 Key 时仍等待用户处理，不擅自换来源。
- **保存不等于换付费主体**：无显式选择的升级配置保留旧的有 Key 优先规则；保存、清空 Key 和登录不自动换来源。手机/手表设置共用[显式选择卡](<agent-lite/src/com/dsh/agentlite/CredentialRouteUi.java>)，说明账号/自己的服务分别计费及云端数据流。
- **任务内稳定**：规划器沿用任务客户端的地址、凭据和付费主体快照；设置修改作用于新任务。工厂不登记另一通道作为备用，HTTP 自动重定向关闭；自带服务 402 的指引归到对应服务账号，不引导充错账号。
- **不替换用户模型**：保留 `de 4.1flash` 原名与配置；本次没有验证网关实际模型映射、远端鉴权、余额或价格。配置齐备只代表可发起请求，不代表服务有效。

| 实际检查 | 结果 | 证据边界 |
|---|---|---|
| 初始凭据行为测试，问题版 | 109 通过 / 68 失败 | 真实失败基线；扩充后的最终断言数量不同 |
| [凭据行为回归](<tests/CredentialRouteTest.java>) | **245 通过 / 0 失败** | 生产 Prefs/路由/请求层和抽取的主页守卫；连接全为内存替身 |
| `GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local make check` | **退出 0**，含八组 JVM | Go、契约、UI 静态与 JVM；保留手工待验项及既有客户端能力差异警告 |
| Go 检查细分 | 有测试的 agentd 包命中缓存；其余包/服务端无测试文件 | 执行成功不等于服务端业务覆盖 |
| Android 34 SDK / Java 11 编译 | **退出 0**，有既有 API 弃用提示 | 使用现有生成资源类；只编译 class，未重新打包资源或安装 APK |
| 镜像 `sync-sources.sh --check` | 客户端/服务器逐字节一致 | 按脚本排除本地配置、证书 pin、构建产物 |

**阶段判定**：T01 完成凭据切片，重复 `done`、旧 `ask`、空节点敏感页行为用例待后续；T02 完成本地实现与离线检查，零 root 真机首跑、配置选择交互、字体/TalkBack 和真实模型仍待验收。没有安装、部署、购买、充值或调用付费 API，也没有把此修复计为适老化产品发布完成。

重跑命令、路由真值表和迁移说明见[整合规划 §23.11](<docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md#2311-t01-凭据切片与-t02-实施记录2026-10-07>)。保留原工作树的既有改动；镜像从原项目同步，不独立修补。

### 18.7 真机回归：凭据路由在真实设备上的验证（2026-10-08）

用户报告真机已连接后，本轮把上述修复放到真实设备上跑了一遍。**这是 §18.6 的补证，不是新功能**：桌面 JVM 只能证明纯逻辑，证明不了首启把用户推到哪一页、点「运行」是否真的发请求、请求带着哪个凭据去了哪个端点。

- **设备**：OnePlus PKX110（OP60F5L1），Android 16（API 36），arm64-v8a，1216×2640 @560dpi。该机已 root（KernelSU），但**主链路按零 root 执行**——只用 uiautomator dump / input tap / input text（与普通用户操作等价）；root 仅用于布置前置状态与取证（读写 shared_prefs、读任务历史与日志）。应用自身不依赖 root。
- **方法**：重装本轮构建的 `agent-lite/build/agent-lite.apk`，用[真机回归脚本](<tests/credential_real_device.py>)自带的两个**本机**假端点（19101 = 自己的配置、19102 = 账号网关，经 `adb reverse` 转发）。全程不出公网、不调用付费 API、不操作真实账号。
- **结果**：**43 通过 / 0 失败**，一次连续运行（A–F）。

| 阶段 | 项数 | 关键结论 |
|---|---|---|
| A 首启与登录态 | 9 | **已登录无 Key 且无历史选择 → 任务真启动，请求打到账号网关并带账号令牌**（§18.2 记录的发布阻断项在真机上确认修复）；未登录时准确提示「需要先登录」且零请求 |
| B 自己的配置 | 8 | 显式自带且有 Key → 只打自己的端点、只带自己的凭据；缺 Key/坏地址停住且**零任务请求**（不自动切账号） |
| C 账号通道 | 7 | 显式账号未登录时**旧 Key 不兜底**；已登录时只用网关并带账号令牌，而非本机旧 Key |
| D 模型缺失 | 1 | 模型名为空即前置拦截 |
| E 故障归因 | 12 | 自带 402 归因到自己的服务账号且不引导应用内充值；401/500 均不跨付费主体；终态文案不泄漏测试凭据 |
| F 设置页选择卡 | 6 | 保存配置弹出显式选择卡；「暂不切换」保持原主体；主动选择才切换（选账号会重新启用被关掉的网关标志） |

**真机发现并修复的缺陷**：自带通道欠费时，失败卡片的「下一步」建议写的是"在设置里配置自己的接口作为备用通道"——而他正在用自己那份接口，正确出口是给自己的服务账号充值。根因是两处叠加：[UiText.nextStep](<agent-lite/src/com/dsh/agentlite/UiText.java>) 的「账号额度」分支先命中了自带欠费的归因串，[AgentEngine](<agent-lite/src/com/dsh/agentlite/AgentEngine.java>) 又对自带通道无条件追加"（在「设置 → 高级」里填写的那份接口配置）"。已修正并在[文案行为测试](<tests/ui_text_test/UiTextTest.java>)固化三条断言（13 通过 / 0 失败）。

**证据边界（必须连着结论一起读）**：

- 失败结果卡片是**悬浮窗**，不在 uiautomator 能 dump 的窗口树里；脚本断言的是**文案内容**（取自外部存储的 `task_history.jsonl` 与 logcat），视觉呈现只有截图（`.check-tmp/device/` 下 E1/E2/E3）。E1 截图已人工确认为「任务未完成 / 自己的模型服务额度不足…」。
- 测试注入的"已登录"是本地判定的假令牌（走 prefs 明文迁移路径）；**真实账号登录、真实余额与计费、真实模型输出质量均未验证**。
- 只覆盖一台机型/ROM（Android 16 定制 ROM）。字号放大、TalkBack、手表圆形屏、重启后状态保持未纳入本轮。
- 无障碍服务由脚本 `settings put` 代开（普通用户需在系统设置里手动开启；路径等价，但不等于人工操作）。
- 回归结束后已还原设备用户态：prefs 恢复为回归前内容、测试令牌文件删除、应用停止、无障碍设置复位为 `null`。

### 18.8 三卡最小切片实施与真机回归（2026-10-09）

按 [T03a/T04a/T05a 最小切片方案](<docs/PLAN_T03_T05_DS41FLASH_2026-10-09.zh-CN.md>)（顺序 T05a → T03a → T04a）完成实施，并在真机上跑通行为回归。**本节是 §18.3 发布阻断项第 2 条（完成证据不足）的直接兑现**，也是 §18.7 之后的第二轮真机验证。

- **T05a 任务代际防护**：`OverlayController.taskEnded(keepCard=true)` 的 4 秒收尾回调补上 `taskActive` 守卫（此前它无条件撤胶囊，而 15 秒分支有守卫）；[AgentEngine](<agent-lite/src/com/dsh/agentlite/AgentEngine.java>) 引入 `RUN_SEQ` 代际号，旧任务的延迟回调只在「仍是最新一代」时才改 `KeepAliveService` 状态。
- **T03a 动作入口收敛（第一刀）**：代点/代勾/代同意三处从裸 `svc.tap`/`svc.tapNode` 改为经 [`exec()`](<agent-lite/src/com/dsh/agentlite/AgentEngine.java>)，获得统一的节点直点、坐标修正与执行日志；失败原因进日志，不再静默落空。`injectViaClipboard` 的 root 聚焦点击登记为**受约束例外**（`DisplaySpec.applyOffset` 非幂等，改 exec 会二次偏移），门禁要求该例外始终处于 `RootShell.available` 守卫下并保留零 root 降级。
- **T04a done 证据分级**：新增纯静态 [`judgeDone`](<agent-lite/src/com/dsh/agentlite/AgentEngine.java>) → `PROVEN / WEAK / UNPROVEN`；`arrivedExpectPkg` 记住「确实到达过被期望的目标」，让 `PROVEN` 在真机上真正可达；**二次无证据 done 不再放行「完成」**——终态降级为「不确定」，`recordable=false` 不入记忆库，新增琥珀色 `showUncertain` 卡片与绿色 `showDone` 语义位分离。

| 回归 | 结果 |
|---|---|
| `make check`（免设备全量） | 通过（新增三个门禁已纳入 `make ui` / `make jvm`） |
| 凭据路由真机（不回退底线） | A–E **41 通过 / 0 失败**；阶段 F 单跑 **6 通过 / 0 失败** |
| 三卡真机行为 | **14 通过 / 0 失败 / 1 跳过**（[脚本](<tests/t03_t05_real_device.py>)） |

真机关键结论：任务 A 完成后 **0.2 秒**启动任务 B，B 运行期间保活锁仍持有、覆盖层胶囊仍在、通知仍是运行态（旧回调没有再改写新任务）；连续两次无证据 done → 「不确定」且记忆库文件 344 → 344 字节未增长；`app com.coloros.calculator` 到达后 done → 「完成」且入库（344 → 707 字节）。

**证据边界（必须连着结论一起读）**：

- 跳过 1 项：本机系统已记住「允许打开」，未复现跨应用启动确认框，`autoConfirmLaunch` 代点分支**未被触发**——该路径只有静态结构门禁（15/0）与桌面证据，缺真机行为证据。
- 结果卡片是 `FLAGS_TOUCH` 覆盖层且创建有延迟：卡片在场期间点「运行」存在竞态（脚本用重试规避）。这是**本轮未改的产品侧问题**，建议另立卡。
- 真机只覆盖一台机型/ROM（OnePlus PKX110 / Android 16 定制 ROM）；假模型经 `adb reverse` 注入本机 HTTP（不出网不花钱），**真实模型输出质量、真实账号与计费仍未验证**。
- 主链路只用 `input` / `uiautomator dump` / `am start` 等用户态命令；root 仅用于布前置状态（写 prefs）与取证（读记忆库文件），结束已还原（prefs 恢复、测试令牌删除、`adb reverse` 撤除）。
- 无障碍服务由脚本用 `settings put` 代开、通知权限由 `pm grant` 预授（普通用户走系统设置/弹窗，路径等价但不等于人工操作）。

---

## 工作日志

<!-- 2026-10-06：补回缺失的日志节标题；以下历史会话原文保留。 -->

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

### 2026-09-15 19:33 +08 · 项目:agent-lite +repo · 会话:3340f9c6 · 跨设备双向测试（手表侧受阻）
- 19:33 首次运行检查：读项目清单/手册 §16.10/日志尾；起 20 任务基线后按用户新指令停掉、让出手机（源:用户请求+文件:PROJECT_OVERVIEW.zh-CN.md）
- 19:37 修基线假性 timeout 根因：`am force-stop` 会清掉 ColorOS 的 a11y 绑定，后续任务在无节点下空跑；每条任务前补写绑定+失败归因（源:文件:tests/task_baseline.py）
- 19:40 澄清跨设备前提：手机 prefs 里的 `sync_token` 是遗留明文（真令牌在 Keystore，据此判「失效」是误读）；手表登录的是 tqym，与手机异号（源:命令:adb prefs + ssh accounts.json）
- 19:50 打通测试前提：自建测试账号 xdev09151936（口令随机、只落 /tmp）；手机 UI 登录；手表无可用 IME 输入 → 装 debuggable 变体 + run-as 写 prefs 登录同账号（源:命令:adb+文件:/tmp/watch_prefs_v2.xml）
- 19:54 方向 1（手机→手表）**端到端通过**：手机 remote 下发 c_1852113485e955ea → 手表 19:54:11 领取 → 19:54:31 app com.heytap.wearable.calculator2 → 前台=计算器（源:命令:adb+ssh journalctl）
- 20:00 方向 2（手表→手机）受阻：以手机身份下发指令给手表，两次 ack failed，且两端 engine.log 无任何记录（源:命令:ssh journalctl）
- 20:05 定位：接收端要求 a11y 已连接否则 ack failed，且跨设备事件只写 logcat；手表 a11y 在覆盖安装后 ColorOS 拒绝重绑（源:文件:agent-lite/.../RemoteCommand.java+命令:dumpsys accessibility）
- 20:09 修可观测性：跨设备下发/领取/回报/拒绝同时落 engine.log；同步 unified 副本，make check 全绿（源:文件+命令:make check）
- 20:12 尝试重启手表修复 a11y → 手表重启后 adb 掉线（ColorOS Watch 关 USB 调试），需人工重插（源:命令:adb devices）
- 20:16 新增 tests/cross_device_e2e.py（双向验证）与 tests/watch_ui.py（小屏 UI 原语）；提交 055c89c/ab1b658/4de1303（源:文件+命令:git）
- 20:18 结论写入手册 §16.11（证据链、三层阻塞、两个已修缺陷、复跑入口）（源:文件:PROJECT_OVERVIEW.zh-CN.md）
- 20:18 终态: blocked（方向 1 通过；方向 2 待手表人工重连后跑 `python3 tests/cross_device_e2e.py`）

### 2026-09-15 21:20 +08 · 项目:agent-lite +repo · 会话:3340f9c6 · 20 轮双端互相交叉测试
- 21:22 首次运行检查：读手册 §16.10/§16.12 与日志尾，确认本轮目标=20 轮双端交叉测试（源:用户请求）
- 21:25 测试账号额度仅 83 units → 走产品自身兑换码链路（admin 建码 + /redeem）充到 1083 units（源:命令:curl）
- 21:30 手表因重启掉出 adb（ColorOS 关 USB 调试）→ 用户重插；拉起两端主界面，确认手表轮询恢复（源:命令:adb+用户请求）
- 21:36 前 2 轮全失败：A 段任务文本没点名设备 → 模型本机执行，整段失去意义（源:测试:tests/cross_20_rounds.py）
- 21:40 暴露**真实产品缺陷「跨设备任务回环」**：手机→手表→手机→手表… 两端互转直到步数上限（源:文件:agent-lite/.../AgentEngine.java+命令:adb engine.log）
- 21:44 修回环：下发侧加归属标注 + 接收侧禁止转回发起方 + 提示词双保险；只装手机侧即生效，2 轮复测 4/4（源:步骤5）
- 21:44 启动 20 轮交叉测试（后台，A 手机→手表 / B 手表→手机 relay 两跳）（源:命令:python3 tests/cross_20_rounds.py）
- 22:28 20 轮跑完：**25/40 段通过（A 10/20、B 15/20），回环 0 次**（源:测试:tests/results-cross-20.jsonl）
- 22:32 核实失败真因：手机目标应用都存在（失败在模型执行侧）；手表设置无电池/显示页（能力边界）；手表 a11y 全程掉线 78 次（源:命令:pm list+dumpsys accessibility）
- 22:35 提交 8ac82e1（回环修复+20 轮脚本；同步 unified 副本、make check 全绿）+ 结论写入手册 §16.13（源:命令:git+文件:PROJECT_OVERVIEW.zh-CN.md）
- 22:52 复核四类证据发现漏采步数 → 新增 backfill 脚本按「一指令一任务」顺序回溯，补齐 33/40 段（源:文件:tests/results-cross-20.jsonl）
- 22:56 cross_20_rounds.py 改为运行期原生采集步数（发起/执行/转达三类），报告重建、手册 §16.13 补口径（源:文件:tests/cross_20_rounds.py）
- 22:58 终态: done（待办：手表 a11y 保活、A 段复位鲁棒性、跨设备任务矩阵按设备能力拆分）

### 2026-09-16 18:57 +08 · 项目:repo · 会话:local · 更新后极细问题复盘
- 18:57 读取最新跨设备、充值、DeepSeek、门禁与安全变更，确认多令牌、双向链路、回环修复和计费闸门已有进展（源:文件:PROJECT_OVERVIEW.zh-CN.md:727-796）
- 18:57 复核源码仍存在用户 API Key/局域网密钥存储、ask 超时、剪贴板、锁屏通知、能力探测与额度幂等等软件风险（源:文件:agent-lite/src/com/dsh/agentlite；文件:agentlite-server）
- 18:57 将最新 P1/P2 问题、DeepSeek 4.1 Flash 约束与下一步顺序写入手册 §16.14（源:文件:PROJECT_OVERVIEW.zh-CN.md:871）
- 18:57 终态: done

### 2026-09-18 17:45 +08 · 项目:repo +agentlite-server · 会话:local · 竞赛材料补齐与作品展示页上线
- 17:45 首次运行检查：读项目清单与手册工作日志尾，确认本次属共享文档项目、展示页部署属服务端（源:文件:docs/PROJECTS.zh-CN.md）
- 17:47 实测 ECS 80 端口公网可达：nginx 起站后 HTTP 200/0.16s（8888/39001 不可达 → 安全组按端口放行而非全开）（源:命令:curl+ssh）
- 17:52 配套资源对话示例文档成稿：素材取自真机结果 results-advanced.jsonl / results-baseline.jsonl，含 8 个示例与真实动作序列（源:文件:竞赛材料/配套资源_对话示例.md）
- 17:55 作品展示页上线 http://8.133.225.105，含 APK v0.6.0、对话示例页、开发流程图与运行原理图（源:命令:scp+curl 200）
- 17:58 复核发现文档 AI 能力描述已过时：默认上游由百炼通义千问改为 DeepSeek（源:文件:agent-lite/build/gen/com/dsh/agentlite/DevConfig.java）
- 17:59 另发现语音链路已解耦为独立 ASR 端点（9/18 实测 input_audio 被 400、/audio/transcriptions 404 致语音静默失效）（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 18:00 修正 开发文档 / 整合版 / 附件1(md+docx) 共 12 处过时描述，重生成两份 PDF（6 页、9 页）（源:步骤6+命令:Edge headless）
- 18:00 产出提交包 9 文件并按「区-学校-作者-」命名；APK 敏感串扫描 0 命中（源:文件:竞赛材料/提交包/）
- 18:02 修复致命运维隐患：nginx 开机自启原为 **disabled** —— ECS 一旦重启作品链接即失效，已 enable 并验证服务重启后仍 HTTP 200（源:命令:ssh+curl）
- 18:02 终态: done（待用户本人：录制演示视频、手写签署原创声明与授权书）

### 2026-09-18 17:30 +08 · 项目:agent-lite +repo · 会话:3340f9c6 · 无线 adb 接入 + 修复语音输入失效
- 17:30 连 192.168.1.110:44121 报 `No route to host`（`nc` 证明端口其实通）→ `adb kill-server/start-server` 后一次连通（源:命令:adb connect）
- 17:40 定位语音失效根因：上游换 DeepSeek 后无音频能力（`input_audio` 400、`/audio/transcriptions` 404），
  而设备 `asr_model` 为空、走"主模型音频"分支 ⇒ 必然失败（源:命令:curl+adb prefs）
- 17:50 确认 ColorOS 无系统识别服务（`voice_recognition_service=null`、`RECOGNIZE_SPEECH` 无接收者）
  ⇒ 选本机离线 ASR 路线（源:命令:adb settings/pm list）
- 18:00 改 App：`LlmClient.transcribe` 支持独立 ASR 端点 + `Prefs.asrBaseUrl/asrApiKey`（缺省 = 原行为）（源:文件:agent-lite/.../LlmClient.java）
- 18:10 搭 `tools/asr_server.py`（mlx-whisper medium-4bit，OpenAI 兼容 `/audio/transcriptions`）（源:文件:tools/asr_server.py）
- 18:20 模型下载踩坑：HF 直连超时→换 hf-mirror；hub 的 xet 传输超时→改 curl 分段并行（0.6→2.2MB/s）；
  期间两个 curl 混写坏了文件，按字节数截断重下并 zip 校验（源:命令:curl）
- 18:36 复验暴露两个卡点：Android 拦明文（`Cleartext HTTP traffic`）+ 服务端不认 `/v1` 前缀（404）
  → 加 network_security_config + 路径兼容（源:命令:adb logcat -s AgentUI）
- 18:38 **端到端打通**：adb 触发录音 → Mac 转写（4.1s 音频 0.63s）→ App 填框并自动执行任务（源:命令:Mac 服务日志+adb logcat）
- 18:45 提交 8e92b10/21d30ed；§16.14 + §9 坑表入库；`make check` 全绿（源:命令:git+make check）
- 18:50 **真机语音验证通过**：Mac 扬声器播 TTS「打开蓝牙设置」→ 手机录音 → 转写（8.7s 音频 0.66s）
  → App 自动执行并成功打开蓝牙设置页（源:命令:Mac 服务日志+命令:adb logcat）
- 18:52 终态: done（遗留：设置页无「ASR 端点」输入框；Mac 关机/换网则语音不可用，但不影响任务执行）

### 2026-09-18 18:41 +08 · 项目:repo · 会话:local · 模糊语义转译方案落地
- 18:41 首次运行检查：读项目清单与手册工作日志尾，确认本次属共享文档项目（源:文件:docs/PROJECTS.zh-CN.md）
- 18:42 定位缺口：planTask 仅 3 行散文，无结构化/澄清/复用/指代（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:1211）
- 18:42 只读调研入口链路：6 条入口、仅 `[跨设备指令] ` 一种前缀、无磁贴入口（源:文件:RemoteCommand.java:58；MainActivity.java:416）
- 18:43 产出方案 docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md：五层管线+三数据结构+P0/P1/P2+6 项门禁（源:文件:docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md）
- 18:43 核对文中 13 处行号证据（AgentEngine/Prefs/MemoryStore/Makefile/OverlayController）全部准确（源:命令:sed 抽样核对）
- 18:43 方案索引写入手册 §10 待办（源:文件:PROJECT_OVERVIEW.zh-CN.md:218）
- 18:43 终态: done（未动任何代码；待用户评审后决定是否落 P0）

### 2026-09-18 20:00 +08 · 项目:agent-lite +repo · 会话:3340f9c6 · 语音转写改手机本地离线
- 20:00 用户定下两条硬约束「不要 root」「不要依赖 Mac」→ 语音方案重定（源:用户请求）
- 20:05 定位语音失效真因：Mac 已换网（en0=10.127.194.126），与手机（192.168.1.110）不同网段、ping 100% 丢包（源:命令:ip/arp/ping）
- 20:10 ASR 服务层压测 20/20 通过（平均 0.66s）→ 排除服务本身问题（源:测试:5 段音频×4 轮）
- 20:20 定方案：whisper.cpp 编进 APK（完全离线、零外部依赖）；NDK 26 + cmake 3.22 交叉编译（源:命令:cmake）
- 20:30 自写 JNI（whisper_jni.cpp）+ WhisperAsr/AsrRouter（三级回退：本地→独立端点→主模型音频）（源:文件:agent-lite/jni+src）
- 20:35 坑①：NDK 不带 libomp.so → UnsatisfiedLinkError，且被上层日志误报成「ABI 不匹配」→ -DGGML_OPENMP=OFF 重编（源:文件:engine.log）
- 20:45 坑②：ggml 的 CPU 后端运行时 dlopen，库留在 APK 内找不到 → manifest 加 extractNativeLibs="true"（源:文件:AndroidManifest.xml）
- 21:00 模型 ggml-small-q5_1（181MB）推入设备；APK 0.2MB → 18.6MB（6 个 .so）（源:命令:adb push+unzip -l）
- 21:05 **断网验证通过**：关闭 Wi-Fi 与移动数据后仍识别出「打开设置」（源:测试:engine.log 本地转写行）
- 21:10 优化推理：关温度回退重试 + 6 线程 → 5.9s 音频由 8.6s 降到 6.1s→6.5s（源:文件:whisper_jni.cpp）
- 21:15 固化 tools/build-asr-libs.sh（三个坑写进注释与默认值）；提交 b4871a1；make check 全绿（源:命令:git+make check）
- 21:20 手册 §16.15 记录架构/真机验证/坑/限制（源:文件:PROJECT_OVERVIEW.zh-CN.md）
- 21:30 用户要求「增加云语言转译，模型用 glm」→ 设置页补云端 ASR 三件套（端点/密钥/模型）+ 链路自检按钮（源:用户请求）
- 21:35 AsrRouter 改为云端优先、本地兜底；实测智谱端点返回 401 而非 404（路径与格式正确）（源:命令:curl）
- 21:40 清掉设备上残留的 Mac 端点，语音不再先失败一次；本地直转 5.9s 音频→6.7s（源:命令:adb+engine.log）
- 21:42 提交 caff7fe；手册 §16.15.1 记录云端接入方式与优先级（源:命令:git+文件:PROJECT_OVERVIEW.zh-CN.md）
- 21:42 终态: done（待办：base 模型提速选项；离线模型管理 UI；asr_api_key 迁 Keystore）

### 2026-09-19 09:25 +08 · 项目:repo +agent-lite · 会话:local · 模糊语义转译 P0 实施与虚拟机验证
- 09:25 实现归一化层与转译核心：`TextNormalizer` + `FuzzyFlow`（纯逻辑、零 android 依赖，可桌面回归）（源:文件:agent-lite/src/com/dsh/agentlite/FuzzyFlow.java）
- 09:28 门禁并入 `make check`（`make ui` + `make jvm`）；桌面回归 85 断言全绿（源:文件:Makefile；测试:tests/FuzzyTranslatorTest.java）
- 09:30 接线 `AgentEngine.planTask`；修 `LlmClient` 把 `max_tokens` 硬编码 160 —— 会截断整份 FlowSpec 并**静默**废掉转译层（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java）
- 09:31 建本地假模型服务 + 虚拟机 e2e 脚本（离线、可重复、不依赖真 Key）（源:文件:tests/mock_llm_server.py）
- 09:44 虚拟机抓出产品缺陷：被截断的 JSON 被当散文喂给引擎 → 改为丢弃并回退（源:测试:tests/fuzzy_e2e_mock.py）
- 09:45 重建 APK 装机；4 场景（good/unsafe/broken/prose）各 **10/10** 通过，含危险流程被拒且不入上下文（源:命令:python3 tests/fuzzy_e2e_mock.py）
- 09:49 修门禁脚本自身缺陷：朴素括号计数被新增的字符字面量 `'{'` 骗到 → 改为词法感知扫描（源:文件:tests/fuzzy_flow_guard.py）
- 09:50 `make check` 全绿 + 两端契约对齐；方案附录 D 与手册 §10 入库（源:命令:make check）
- 09:50 终态: done（待办：**真机 + 真 Key 验证真实模型的 JSON 遵循度**——最大遗留风险；P1 澄清卡片 / P2 流程库复用）

### 2026-09-19 10:06 +08 · 项目:repo +agent-lite · 会话:local · 有/无转译层 A/B 对照实验
- 10:06 构造对照版本：同源仅回退 `planTask` + 移除三个转译类（`LlmClient` 逐字节相同，唯一变量成立）（源:文件:/tmp/agent-lite-nostl）
- 10:18 mock 加 `ab` 场景：假模型**只依据提示词内容**决策，两版差异才会传导到行为（源:文件:tests/mock_llm_server.py）
- 10:31 首轮数据失真：与并发会话共用 emulator-5554（场景被改、mock 被杀、任务被抢）→ 新建独立 AVD `Agent_AB`（emulator-5556）隔离（源:命令:avdmanager+ps+lsof）
- 10:43 排查设备侧静默失败：无障碍未绑定（`adb root` 清设置）→ 截图 + `dumpsys accessibility` 定位，脚本补 `ensure_a11y()` 绑定校验（源:文件:tests/fuzzy_ab_compare.py）
- 10:53 实验完成：提示词完备度 A 24 项 / B **0 项**；**B 版对资金与发送任务未确认直接动手，A 版 100% 先 ask**（源:命令:python3 tests/fuzzy_ab_compare.py）
- 10:53 结论与局限写入方案附录 F（源:文件:docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md）
- 10:53 终态: done（清理：停 mock 与 5556；AVD `Agent_AB` 保留复用）

### 2026-09-19 10:15 +08 · 项目:agent-lite +repo · 会话:local · 操作记忆库：成功路径自动入库并直接复现
- 10:15 首次运行检查：读项目清单与工作日志尾恢复上下文，确认属 agent-lite +repo（源:文件:docs/PROJECTS.zh-CN.md）
- 10:16 用户要求「记忆库改成默认的，每步成功操作入库，之后直接复现即可完成」→ 对应设计文档 P2「流程库复用」，骨架/指纹纯函数已随 P0 落地（源:文件:docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md）
- 10:20 新增 `FlowPlan`（纯逻辑：指纹/入库把关/锚点定位/新鲜度与退役/容量淘汰）与 `FlowMemory`（落盘 `flowmem.json`、统计、UI；**不接 SyncClient**）（源:文件:agent-lite/src/com/dsh/agentlite/FlowPlan.java）
- 10:24 接线 `AgentEngine`：逐步采集 + `done` 入库 + 命中直接复现（`replayFlow`/`stepToAction`/`captureStep`），复用既有 `exec()` 与敏感守卫（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 10:25 `Prefs#flowMemory` 默认开；记忆库页把「操作记忆 + 用户记忆」同页，可删/可清空/可关（源:文件:agent-lite/src/com/dsh/agentlite/MainActivity.java）
- 10:33 回归抓出实现与注释不符：包含匹配未限制锚点长度（单字「搜」会命中「搜索」）→ 改为两边都 ≥2 字（源:测试:tests/FlowMemoryTest.java）
- 10:35 修时序缺陷：采集原在 `waitForChange` 之前，`app` 启动异步致 `pkgAfter` 记成旧应用、复现首步必判失败（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java）
- 10:47 e2e 修掉三处**测试设施**缺陷：`font_scale=2.0` 残留挤出输入框；设备端点残留指向另一 mock 实例（18998/18999）；`logcat -d` 全量致 FM-6/FM-9 假通过（源:测试:tests/flow_memory_e2e_mock.py）
- 10:52 ⚠️ 并发冲突（如实记录）：与上一块「A/B 对照」会话共用 emulator-5554 —— 我 `pkill` 旧 mock、`set_scene(flow)`、重装 APK 打断了对方首轮（对方 10:31 已改用 5556 隔离）；反向干扰亦让我三次 e2e 归因困难（源:文件:PROJECT_OVERVIEW.zh-CN.md:1613）
- 10:55 虚拟机端到端 **12/12 通过**：同一任务第二次命中记忆库、复现完成、**模型调用数不增加**；APK 构建成功 19.5 MB（源:命令:python3 tests/flow_memory_e2e_mock.py）
- 10:57 门禁全绿（桌面回归 85+48 断言、转译 22、零门槛 17、动作词表 8、契约 26）；方案 §4.4.1/§5.4.4/§9.3/§10 + 附录 E 与 CHANGELOG 入库（源:测试:各守卫脚本等价执行）
- 10:58 环境限制：`/usr/bin/make` 与 `/usr/bin/git` 因**未同意 Xcode 许可**一律退出 69 → 门禁改用脚本等价执行；本次**未能提交**（源:命令:make --version）
- 10:59 终态: done（待办：真机+真 Key 长周期观察命中率与复现成功率；P1 澄清卡片 / 实体账本未做）

### 2026-09-19 10:52 +08 · 项目:agent-lite +repo · 会话:local · 突发界面守则与门禁
- 10:52 定位缺口：SCHEMA 对弹窗/广告仅一句「跳过 tap」，无权限分层/运营弹窗/登录墙/异常跳转规则（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:338）
- 10:56 SCHEMA 新增守则 4「突发界面优先处置」：权限最小必要/广告只点退出按钮严禁点主体/运营弹窗不点改变态按钮/登录墙先绕后 fail「需要登录」/系统对话框拒绝/异常跳转 key4 返回复验；原守则 4~9 顺延 5~10（源:文件:同上）
- 10:58 新增 tests/popup_ad_guard.py（22 断言）并入 make ui；初版编号正则失配、修正后 22/22 绿（源:测试:python3 tests/popup_ad_guard.py）
- 10:59 APK 构建 19.5MB；dex grep「突发界面优先处置」1 命中；11:00 装 emulator-5554 启动零 FATAL（两个并发会话已终态，无干扰）（源:命令:build-mac.sh+adb）
- 11:01 Skill §3 环境态表扩五类+§8 清单+§9 溯源；SPEC 升 v1.0.4 §4.2 同步；CHANGELOG 入库（源:文件:skills/software-interaction-logic/SKILL.md）
- 11:02 make 同样被 Xcode license 挡（EXIT 69，与上块 10:58 同因）→ 脚本等价直跑：词表/UX/文案/转译/记忆库门禁全绿（源:命令:各门禁脚本）
- 11:02 契约 1 失败＝镜像未同步（改源码未跑 sync）→ sync-sources.sh 后 26/0/0 对齐（源:命令:verify-contract.py）
- 11:03 终态: done（待办：真机弹窗/广告场景回归；Xcode license 同意后整跑 make check 与 git 提交）

### 2026-09-19 11:04 +08 · 项目:agent-lite +repo · 会话:local · 软件引导跟随守则
- 11:04 SCHEMA 守则 4 标题加「新手引导」类别，新增 g) 分步指引双策略：任务相关跟随（点高亮元素/下一步、每步复验）、无关快跳（跳过/×优先，无跳过连续下一步 3~6 步）；红线不豁免；超 6 步循环 → fail「引导无法完成」（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:318）
- 11:05 门禁新增 PU-11 八项断言 → 30/30 绿；Skill §3 表加引导行+§8 清单；SPEC v1.0.5；CHANGELOG 入库（源:测试:python3 tests/popup_ad_guard.py）
- 11:06 APK 19.5MB；dex「跟随引导」「引导无法完成」各 1 命中；sync 后契约 26/0/0；词表/UX/文案门禁全绿；模拟器 5554 装机零 FATAL（源:命令:build-mac.sh+adb）
- 11:06 终态: done（待办：真机引导场景回归；上一块 3 处预估时间戳已按真实时钟修正）（源:步骤3）

### 2026-09-19 12:15 +08 · 项目:repo +agent-lite · 会话:local · 真机 + 真模型验证（销掉最大遗留风险）
- 12:15 真机接入：OnePlus 13T（PKX110 / Android 16 / root=KernelSU），已装 v0.6.0；配置 model=deepseek-flash 且 single_model_mode=true（源:命令:adb+su cat prefs）
- 12:18 打通中文注入：`input text` 不支持非 ASCII、剪贴板 cmd 不可用 → 改用项目自带 FlorisBoard 缝合桥广播 `com.dsh.agent.IME_COMMIT`（源:文件:agent-client/floris-merge/AgentImeBridge.java）
- 12:20 首轮 8 个中文模糊任务：转译结构化 **7/8**，风险识别 **3/3**（含把「点个奶茶送到公司」判为 money）（源:命令:python3 tests/fuzzy_real_device.py）
- 12:22 定位唯一失败：模型把「去公司怎么走」过度标成 personal_info 却无确认点 → 被 FF-1 硬约束拦下**（此类问题只有真模型会暴露）**（源:文件:/tmp/real_device_result.json）
- 12:24 修提示词 risk 边界（明确「纯查询不算风险」）→ `make check` 全绿 → 重建装机（源:文件:agent-lite/src/com/dsh/agentlite/SemanticTranslator.java）
- 12:28 复测 **8/8**：R5 转为 navigate/none，风险判定仍 3/3 无漏判（源:命令:python3 tests/fuzzy_real_device.py）
- 12:30 结论入方案附录 G；恢复真机默认输入法（测试期曾切 FlorisBoard 桥）（源:文件:docs/FUZZY_SEMANTIC_TO_FLOW_DESIGN.zh-CN.md）
- 12:30 终态: done（待查：R4 转译成功但步数=0、耗时 45.9s；未测：端到端完成率、语音 ASR 链路）

### 2026-09-19 12:46 +08 · 项目:agent-lite +repo · 会话:local · 三大功能整合：复现路径突发处置
- 12:46 首次运行检查：读日志尾恢复上下文；用户确认整合范围＝协同缺口修补＋全链路验证（源:用户请求）
- 12:52 定位三处协同缺口：①replayFlow 零模型往返、无弹窗处置，遇弹窗必定位失败且无差别 markFail（两次即误退役好记录）；②采集侧把模型关弹窗的 tap 记入库，下次弹窗不再现该步必定位失败；③无三者一体 e2e（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:1016）
- 14:16 续: 上次未收尾；复核补丁并跑门禁，确认全跳过伪成功、弹窗上限误贬值及旧签名门禁（源:测试）

### 2026-09-20 19:40 +08 · 项目:repo · 会话:session- · 下载同定位开源项目
- 19:40 首次运行检查并明确定位：Android 视觉/语义感知、自然语言规划、操作复验闭环（源:文件:README.zh-CN.md:15）
- 19:40 筛选 4 个同类候选；确认 Open-AutoGLM/Apache-2.0、MobileAgent/MIT 等许可（源:web:github.com）
- 19:46 下载 4 个 main 源码快照至 _local/reference-projects，共 458MB/3057 文件，来源说明齐全（源:命令:curl+tar）
- 19:46 核验四项目均含 README、LICENSE、.source-info，且无归档/解包临时文件残留（源:测试）
- 19:46 终态: done（系统 git 受 Xcode License 阻断，故快照不含 .git 历史）（源:步骤3）

### 2026-09-20 20:02 +08 · 项目:repo · 会话:session- · 拆解同类成熟模块
- 20:02 首次运行检查：恢复上次下载结果；建立 Agent Lite 感知、执行、记忆、安全与测试基线（源:文件:agent-lite/README.md:6）
- 20:02 建立「同类手机 Agent 成熟模块拆解」知识页，隔离提取且不直接合并源码（源:记忆）
- 20:19 对比四项目与现有基线，筛出感知、安全策略、轨迹、技能、复现守卫、模型层、虚拟屏、评测等模块（源:文件:_local/mature-modules/README.md）
- 20:21 隔离提取 9 个候选模块至 _local/mature-modules，共 13MB；逐项标注用途、证据、依赖、风险与优先级（源:步骤3）
- 20:21 核验 724 个文件、9 份模块说明及全部 Apache-2.0/MIT 许可证齐全；未修改 Agent Lite 运行源码（源:测试）
- 20:21 终态: done（候选均为研究副本，后续须逐模块适配与验证后才能集成）（源:步骤5）

### 2026-09-20 21:13 +08 · 项目:repo · 会话:local · 制定成熟模块全面整合规划
- 21:13 读取工作日志尾部并确认本次属于共享文档项目，基线为 agent-lite 纯 Java、零第三方依赖与 make check 门禁（源:文件:PROJECT_OVERVIEW.zh-CN.md:1672）
- 21:13 汇总 9 类隔离候选模块，规划按安全、感知、复现、轨迹、评测、技能、知识、模型、虚拟屏分阶段整合（源:文件:_local/mature-modules/README.md）
- 21:48 编写 22 节全面整合手册，覆盖 M0–M11、契约、迁移、测试、灰度、发布、回滚与收尾验收（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md）
- 21:48 覆盖核验通过；git diff 检查受本机 Xcode license 未接受阻断，未执行代码构建（源:测试）
- 21:48 终态: done（完成规划文档，下一步按 M0-01 开始逐卡实现）（源:步骤3）

### 2026-09-20 21:59 +08 · 项目:repo · 会话:local · 三功能整合真机闭环（模糊语意 + 记忆库 + 弹窗治理）
- 21:59 读工作日志尾部恢复上下文；确认本次目标是把语义转译、操作记忆库、突发界面治理打通在同一条任务路径上（源:文件:PROJECT_OVERVIEW.zh-CN.md）
- 21:59 定位并修复 mock 假模型的多模态崩溃：vision 请求 content 是 list，`re.search` 抛 TypeError → mock 500 → 引擎静默切同步服务网关、真模型接管（此前「主模型不经过 mock」悬案的根因）（源:日志:/tmp/mock_server.log）
- 21:59 定位真机测试纯净性缺口：`LlmClient.forPrefs` 对已登录账号会挂网关 fallback；测试期清空 `sync_token`/`account_id` 掐断（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java:205）
- 21:59 定位引擎首步感知残缺：a11y 服务 bound 后窗口枚举未就绪，`getWindows()` 只回 SystemUI，模型只能在状态栏瞎点；服务热身后再提交（源:日志:AgentEngine 第1步感知6节点）
- 21:59 定位提交通道时序：夹具弹窗须在**提交成功之后**补弹才能进入首步感知窗口（提交前弹会被 MainActivity 的 NEW_TASK 盖掉）（源:日志:补弹夹具后窗口现场）
- 21:59 加固测试脚本：主机侧备份且不覆盖首次（设备端备份曾被下一轮覆盖，一次 USB 掉线把 mock 配置写回用户机）、恢复链逐条容错、测试期挂起 USB 抢台的 com.microsoft.appmanager（源:文件:tests/integrated_features_e2e.py）
- 21:59 真机端到端 16/16 全绿：转译成功 → 弹窗步识别且不入库 → 业务步入库带骨架指纹 → 第二次命中记忆库直接完成 → 零模型往返 → 噪音不加失败计数（源:测试:tests/integrated_features_e2e.py）
- 21:59 门禁全绿：`make check` 契约 26 项 + 守卫 + JVM 测试通过；镜像 `sync-sources.sh` 已同步（源:命令:make check）
- 21:59 文档同步：SPEC 升 v1.0.6（§4.4 R-1~R-6 + 附录 C）、Skill §9 三条溯源行 + §7.5 机制说明、CHANGELOG 整合条目（源:文件:docs/INTERACTION_LOGIC_SPEC.zh-CN.md）
- 21:59 真机用户态复核还原：prefs（deepseek 端点/真实 Key/同步令牌）、a11y 绑定、appmanager 挂起状态、夹具卸载、测试记忆清理（源:命令:adb su cat prefs）
- 21:59 终态: done（三功能整合闭环，真机 e2e 与免设备门禁全绿）（源:测试）

### 2026-09-20 22:25 +08 · 项目:repo · 会话:local · 全局代码复核与高风险修复
- 22:20 完成全局审查：确认无障碍节点释放与账号保存一致性为高风险缺陷，未将既有警告误报为代码失败（源:文件:agent-lite/src/com/dsh/agentlite/AgentA11yService.java:131）
- 22:23 修复服务解绑/销毁、前台包名、主屏/虚拟屏输入、校准与节点点击路径的 `AccessibilityNodeInfo` 释放边界（源:文件:agent-lite/src/com/dsh/agentlite/AgentA11yService.java:90）
- 22:24 修复账号登录原子性：令牌密文同步提交并校验，账号元数据后提交，失败时回滚令牌（源:文件:agent-lite/src/com/dsh/agentlite/Prefs.java:387）
- 22:24 新增全局复核回归门禁，覆盖节点生命周期与账号一致性 9 项断言（源:文件:tests/global_review_guard.py:1）
- 22:24 门禁通过：全局复核 9/9、动作/交互/文案/记忆/弹窗守卫无失败；存在既有真机 MANUAL 项与 agent-client 差异警告（源:测试）
- 22:25 Android 自定义构建成功，生成 `agent-lite/build/agent-lite.apk`（19,574,129 bytes）；编译仅有过时 API 警告（源:命令:sh agent-lite/build-mac.sh）
- 22:24 `make jvm` 与聚合 `make check` 受本机 Xcode license 未接受阻断，已改跑各核心 Python/JVM 守卫；源码构建不受该限制（源:命令:make check）
- 22:25 终态: done（完成全局复核、高风险修复、回归门禁与 Android 构建）

### 2026-09-20 23:06 +08 · 项目:repo · 会话:local · M0-01 功能开关基线整合
- 22:40 读取整合手册与候选模块清单，确认首卡为 M0-01，默认不接入新运行时行为（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:408）
- 22:48 新增 `FeatureFlags` 与 `Prefs.featureFlags()`，冻结 8 个模块开关、2 个影子模式和契约版本 1（源:文件:agent-lite/src/com/dsh/agentlite/FeatureFlags.java:1）
- 22:52 新增 M0-01 门禁并入 `make ui`，覆盖默认值、入口、版本和 AgentEngine 未接入边界（源:文件:tests/m0_feature_flags_guard.py:1）
- 22:57 新增 ADR 与 CHANGELOG 条目，记录兼容边界、风险和关闭开关回滚方式（源:文件:docs/ADR-001-FEATURE-FLAGS.zh-CN.md:1）
- 23:00 M0-01 门禁 30/30 通过；既有 UI、交互、安全、记忆、弹窗、全局复核门禁均无失败（源:测试）
- 23:01 Android APK 构建与可读性检查通过，未发现 API Key/令牌明文；真机人工项未执行（源:命令:sh agent-lite/build-mac.sh）
- 23:04 同步 `agent-unified/client` 镜像成功；未改动镜像真相源（源:命令:bash agent-unified/scripts/sync-sources.sh）
- 23:05 聚合 `make ui`/`make contract` 受本机 Xcode license 未接受阻断，已保留独立门禁结果（源:命令:make ui）
- 23:06 更新整合规划：M0-01 已完成，唯一下一卡为 M0-02（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:1573）
- 23:06 终态: done（完成 M0-01 功能开关基线整合；M0-02 待执行）

### 2026-09-20 23:21 +08 · 项目:repo · 会话:local · M0-02 契约冻结整合
- 23:21 读取 M0-02 四类契约、项目手册和现有动作/节点模型，确认本卡只冻结纯 Java 契约不接入主循环（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:218）
- 23:25 取得用户决策：仅密码脱敏、默认保留脱敏截图快照、本地固定轮转、旧流程仅低风险直放、手写稳定渲染（源:用户请求）
- 23:34 新增四类契约模型、版本常量和稳定字段渲染器，保持零 Android/第三方运行时依赖（源:文件:agent-lite/src/com/dsh/agentlite/contracts/ScreenSnapshot.java:1）
- 23:38 新增 M0-02 静态门禁 29 项与 JVM 行为测试 25 项，覆盖掩码、终态不可逆、事件白名单和旧流程兼容（源:测试）
- 23:43 修正旧流程未知/空风险直放缺口，改为仅明确 `LOW`/`NORMAL` 允许直放并同步 ADR/CHANGELOG（源:文件:agent-lite/src/com/dsh/agentlite/contracts/ScreenState.java:37）
- 23:48 规范升至 1.0.7，新增结构化契约章节、门禁核对项和 Trace 本地轮转边界（源:文件:docs/INTERACTION_LOGIC_SPEC.zh-CN.md:11）
- 23:52 M0-02 静态门禁 29/29、JVM 测试 25/25、规范同步检查全部通过（源:测试）
- 23:54 Android APK 构建成功，产物 `agent-lite/build/agent-lite.apk` 为 19,574,129 bytes（源:命令:sh agent-lite/build-mac.sh）
- 23:56 APK 可读性检查通过，未发现 API Key/令牌明文；真机未执行（源:测试）
- 23:57 同步 `agent-unified/client` 镜像成功；`git diff --check` 受本机 Xcode license 未接受阻断（源:命令:bash agent-unified/scripts/sync-sources.sh）
- 23:57 更新整合规划：M0-02 已完成，唯一下一卡为 M0-03（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:1575）
- 23:57 终态: done（完成 M0-02 契约冻结整合；M0-03 待执行）

### 2026-09-21 06:32 +08 · 项目:repo · 会话:local · M3-01 工具注册表整合
- 06:32 读取 M3 阶段和现有 `ActionSchema`/`AgentEngine.exec()` 边界，确认先做纯 Java ToolSpec/ToolRegistry，不接入执行路径（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:663）
- 06:34 取得用户决策：严格覆盖 25 动作、最小参数 schema、重复注册拒绝、ask/done/fail 登记为控制工具（源:用户请求）
- 06:38 新增 `ToolParameter`、`ToolSpec`、`ToolRegistry`，从 `ActionSchema` 双集合生成完整声明，M3 开关保持默认关闭（源:文件:agent-lite/src/com/dsh/agentlite/tools/ToolRegistry.java:1）
- 06:41 新增 M3-01 静态门禁和 JVM 测试，首轮发现并修正门禁匹配错误（源:测试）
- 06:44 发现并修正长按 schema 与旧 `exec()` 不一致：`longpress` 补齐 index/比例坐标/像素坐标可选路径（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:1924）
- 06:46 M3-01 静态门禁 22/22、JVM 测试 47/47；动作词表 8 项通过、保留既有 agent-client 能力差异警告，全局复核 9/9、弹窗守卫 35/35（源:测试）
- 06:48 Android APK 重建成功，产物 `agent-lite/build/agent-lite.apk` 为 19,574,129 bytes（源:命令:sh agent-lite/build-mac.sh）
- 06:49 APK 可读性检查通过，未发现 API Key/令牌明文；无实体机，ToolRouter/设备执行取消/旧 exec 对等性留待后续卡和真机（源:测试）
- 06:50 同步 `agent-unified/client` 镜像成功；门禁脚本语法检查通过（源:命令:bash agent-unified/scripts/sync-sources.sh）
- 06:50 更新整合规划：M3-01 已完成，唯一下一卡为 M3-02（源:文件:docs/MATURE_MODULE_INTEGRATION_PLAN.zh-CN.md:1603）
- 06:50 终态: done（完成 M3-01 工具注册表整合；M3-02 待执行）

### 2026-09-21 18:05 +08 · 项目:repo +agent-lite · 会话:local · 真机设备验证（只记录不改码）
- 18:05 设备接入 PKX110/Android 16，备份 prefs 后覆盖安装最新 APK 并重绑无障碍（源:命令:adb install -r）
- 18:06 冷启 204ms 无 FATAL；无障碍自愈通过：force-stop 后保持、kill -9 后自动重绑（源:命令:adb am start -W + dumpsys accessibility）
- 18:13 真机首成功：任务「Open the Calculator」5 秒完成 2 步，`app com.coloros.calculator` 直达（源:文件:engine.log）
- 18:13 记忆库闭环通过：第二次命中复现且**零模型往返**（无「流程转译」记录，`flowmem.json` 落盘）（源:文件:engine.log）
- 18:15 用户停止 1.5s 响应且不记失败；进程死亡恢复后账号/模型/设备 ID/记忆库全部保持（源:测试）
- 18:16 缺陷①（P1）：额度显示二次扣减共 5 处，服务端 `quota=144` 而界面显示「剩余 0 units」，用户误判额度耗尽（源:文件:agent-lite/src/com/dsh/agentlite/MainActivity.java:262）
- 18:16 缺陷②（P2）：软键盘遮挡输入框与「运行」按钮，manifest 缺 `windowSoftInputMode`，实测点击落在键盘上导致任务未启动（源:文件:agent-lite/AndroidManifest.xml）
- 18:17 辨明 a11y 反复掉线为 `uiautomator dump` 抢占绑定所致（静默 60s 零掉线零新增），非产品缺陷（源:测试）
- 18:18 排除缺陷①的「数据陈旧」解释：重启后缓存刷新为与服务端一致的 144/956，界面**仍显示 0 units**，确认为纯计算错误（源:命令:adb + su cat prefs）
- 18:18 清理工作区凭据风险：删除验证期复制的 `prefs-now.xml`/`cred-now.xml`/`backup/`（含 API Key 与令牌），仅保留无凭据截图（源:命令:find + grep）
- 18:18 设备恢复干净态：输入框已清空、a11y 绑定在位、账号/模型/设备 ID/记忆库完好（源:命令:adb force-stop + am start）
- 18:19 手册新增 §17 真机验证记录（矩阵/缺陷/测试设施注意事项/未验证项）及 §9 三条速查行（源:文件:PROJECT_OVERVIEW.zh-CN.md:1136）
- 18:19 终态: done（设备验证完成；2 项缺陷已入册未修，按用户要求不改代码）

### 2026-09-21 19:32 +08 · 项目:agent-lite +repo · 会话:local · 续接 M3-02 参数校验
- 19:32 读取上一会话日志与整合计划，确认 M3-01 已完成、M3-02 参数 Validation 为当前唯一下一卡（源:文件:PROJECT_OVERVIEW.zh-CN.md:1813）
- 19:46 新增 `ValidationResult` 与 `ToolSpec/ToolRegistry.validate`，覆盖必填、未知字段、null、类型、范围、枚举，错误不回显输入值（源:文件:agent-lite/src/com/dsh/agentlite/tools/ValidationResult.java）
- 19:50 扩展 JVM 测试至 58 项并修正整数严格十进制校验；M3-02 门禁 28/28、M3-01 门禁 31/31（源:测试）
- 19:56 APK 构建成功，`agent-lite/build/agent-lite.apk` 为 19,574,129 bytes；源码镜像同步成功（源:命令:sh agent-lite/build-mac.sh + sync-sources.sh）
- 20:02 完整 `make jvm` 受本机 Xcode license 未接受阻断；直接 M3 JVM 回归 58/58，通过门禁与脚本语法检查；未上真机（源:测试）
- 20:03 终态: done（M3-02 完成；M3-03 ToolInvocation/ToolCallState 待执行）

### 2026-09-21 20:05 +08 · 项目:agent-lite · 会话:local · 真机验证（第二轮，只记录不改码）
- 20:05 设备 PKX110/Android 16 接入，备份 prefs+flowmem 到 /tmp 后覆盖安装本轮 APK，登录态/a11y/记忆库全保留（源:命令:adb install -r）
- 20:06 冷启 465ms 无 FATAL；a11y 绑定保持；界面实测缺陷①已不成立：显示「剩余 144 units」= prefs account_quota（源:命令:am start -W + screencap）
- 20:06 真机任务通过：「Open _the _Calculator」4.0s 完成（转译 open_app → app com.coloros.calculator）（源:文件:logcat AgentEngine 20:06:49）
- 20:10 记忆库闭环复现通过：命中「1 步 → 打开计算器」→ 复现 1/1 → 直接完成，**1.2s 零模型往返**、无转译记录（源:文件:logcat AgentEngine 20:10:24）
- 20:10 新增实测结论：记忆库要求归一化文本精确相等，下划线变体不命中且另存独立条目（hits=0 oks=1）（源:文件:flowmem.json）
- 20:10 缺陷②仍成立：键盘顶部 y≈1568，输入框/运行按钮仅露 60px 上沿（源:命令:screencap + 亮度扫描）
- 20:11 补充测试陷阱 4 条：input text 空格变下划线、DEL 仍无法清空、坐标点击前须确认前台（本轮误点计算器白花 3 轮）、cmd clipboard 不可用（源:测试）
- 20:11 清理临时凭据备份与设备 ui.xml；仓库无凭据残留；git 提交因本机 Xcode license 拦截跳过（源:命令:rm + git status）
- 20:11 手册新增 §17.5 第二轮验证、§17.6 陷阱补充，并给缺陷①加状态更新（源:文件:PROJECT_OVERVIEW.zh-CN.md:1199）
- 20:12 终态: done（真机验证完成：核心链路与记忆库零往返通过，缺陷②遗留，M3-02 无设备行为可验）

### 2026-09-21 20:16 +08 · 项目:agent-lite · 会话:local · 高难度任务实测（朋友圈点赞，只记录不改码）
- 20:16 采集基线：朋友圈第一条=「9.28 龙玥彤·孩子用不到俩月给自己吃胖五斤」，点赞区仅「田一涵,来子晨」，确认本机未点赞（源:命令:screencap）
- 20:16 实测微信无障碍树被完全屏蔽：uiautomator 仅 1 个空节点；`input text` 注入中文直接抛 NPE（源:测试）
- 20:19 任务 1 失败：第 7 步被指纹看门狗终止「屏幕连续 6 步无变化」，微信确实被启动但停在奶奶聊天页，0 次点击（源:文件:logcat AgentEngine）
- 20:19 定位缺陷A：32/32 次 `collectNodes` 只拿到 systemui 状态栏（type=3），微信应用窗口 root=null（源:文件:logcat AgentA11y）
- 20:21 定位缺陷C：`NodeInfo.fingerprint` 只算节点 text/desc/id、不含截图，状态栏 5 节点恒定→必然误杀（源:文件:agent-lite/src/com/dsh/agentlite/NodeInfo.java:82）
- 20:22 任务 2 对照复现：前置态改微信主页仍第 6 步被同一看门狗终止，步序逐字相同，2/2 系统性复现（源:文件:logcat AgentEngine 20:21:28）
- 20:22 手册新增 §17.7（基线/步骤表/缺陷 A–D/4 项修复候选）与 §17.6 陷阱 5–6（中文注入 NPE、运行按钮坐标随行数变化）（源:文件:PROJECT_OVERVIEW.zh-CN.md:1230）
- 20:22 清理设备导出文件与临时截图；记忆库仍 2 条未污染；归档基线/失败现场图至 _local/verify-20260921-moments（源:命令:rm + scp）
- 20:22 终态: done（高难度任务按预期暴露引擎缺陷；点赞未执行，待高级模型修复缺陷 A–D 后复验）

### 2026-09-21 20:24 +08 · 项目:repo · 会话:local · 补「全项目禁止依赖 root」底线
- 20:20 首次运行检查：读项目清单与手册工作日志尾，确认本次属共享文档项目 repo（源:文件:docs/PROJECTS.zh-CN.md:27）
- 20:21 核查 root 现状：agent-client 52 / agent-lite 18 / tools 3 / agentd-go 1 处引用，ios 与服务端为 0；两份 README 已声明「不需要 root」（源:命令:grep 统计）
- 20:23 用户裁定：语义=零 root 为唯一必需基线、root 仅可选增强；落点=手册 §12 + 新建 AGENTS.md（源:用户请求）
- 20:23 手册 §12 第三条升格为 §12.1 完整底线（5 条禁止 + 例外登记表 + 验证方式）（源:文件:PROJECT_OVERVIEW.zh-CN.md:286）
- 20:24 新建仓库根 AGENTS.md 承载同规则，本会话运行时已自动加载生效（源:文件:AGENTS.md:6）
- 20:24 §7 加边界声明，避免「沙盒必须 root」被误读为全项目前置（源:文件:PROJECT_OVERVIEW.zh-CN.md:132）

### 2026-09-21 20:30 +08 · 项目:agent-lite +repo · 会话:local · 修复纯视觉看门狗误杀
- 20:30 读取项目清单、工作日志尾与相关实现，确认微信问题集中在系统状态栏节点污染、纯视觉未触发、节点指纹看门狗误判（源:文件:AgentEngine.java:622）
- 21:29 零 root 真机安装复验成功：检测 `top=com.android.systemui` 后切换纯视觉，第 2 步完成 `Open WeChat`，未再触发第 6 步看门狗误杀（源:命令:adb logcat）
- 21:34 清理设备 XML 残留，确认前台 `com.tencent.mm/.ui.LauncherUI` 与无障碍绑定在位；§17.8 记录修复后真机结果（源:命令:adb）
- 21:44 校正本会话日志时间，仅保留可由构建、门禁、同步和真机日志证明的结果（源:步骤1）
- 22:21 续: 开始朋友圈点赞端到端验证，采基线首条=traever-旅行者「退抖音前最后一个点赞」（无点赞条目）（源:命令:adb screencap）
- 22:17 修复前 2 次失败：动作在 tap@766,2442 与另一坐标 A/B 震荡，最多 45 步仍未进入朋友圈（源:文件:logcat AgentEngine）
- 22:24 定位缺陷E：胶囊窗口 frame=[264,2437]-[952,2542] 压住微信「发现」Tab 且用 FLAGS_TOUCH 吃点击（源:命令:dumpsys window）
- 22:24 对照实验：同坐标 tap(766,2442) 无悬浮窗变化 36.6%、有胶囊 0.0%（源:测试）
- 22:25 胶囊改 FLAGS_DISPLAY（不可触）+ 移除点击监听；构建 APK 并覆盖安装（源:文件:OverlayController.java:107）
- 22:27 端到端复验成功：19 步完成点赞，首条动态出现「❤ 报道北方」，基线该位置无任何点赞（源:命令:adb screencap + logcat）
- 22:30 手册新增 §17.9（缺陷 E + 对照实验 + 步序表 + 独立核验）；镜像同步与门禁 9/9、17/0 通过（源:步骤7）
- 22:30 终态: done（缺陷 A–E 修复；朋友圈点赞端到端打通，用户可自行取消该赞）

### 2026-09-23 00:04 +08 · 项目:repo · 会话:a700e9c2 · NPU 小主机永久离线（登记·无影响）
- 00:04 用户告知 NPU 小主机（yylamd / 192.168.5.11）永久离线（源:用户请求）
- 00:04 核查本项目零引用该机：真机验证走本机 adb + OnePlus 设备，无远端 Windows 依赖（源:命令:grep -rn 192.168.5.11）
- 00:04 终态: done（仅登记备查，无动作）

### 2026-10-02 12:36 +08 · 项目:repo · 会话:local · AgentLite 更新审计
- 12:36 复核新增模块、执行链、零 root 规则与真机证据边界（源:文件:docs/PROJECTS.zh-CN.md:27）
- 12:40 首轮 make check 揭示 ToolRegistryTest 漏编 ValidationResult，修正 Makefile（源:命令:make check）
- 12:48 记录 open_url 回放、ask 超时、M3 未接线及 root 集成测试问题（源:文件:PROJECT_OVERVIEW.zh-CN.md:1440）
- 12:54 补记忆库明文/非原子写入、快照脱敏边界与能力评估（源:文件:agent-lite/src/com/dsh/agentlite/FlowMemory.java:231）
- 13:00 make check 全过；记忆门禁 40/0、M3 门禁 31/0、源码镜像同步通过（源:测试）
- 13:09 §17.10 完成，记录当前缺陷、残余风险及 DeepSeek 4.1 Flash 指导（源:步骤4）
- 13:09 终态: done

### 2026-10-02 13:52 +08 · 项目:agent-lite +repo · 会话:local · 修复 §17.10 五项 P1
- 13:52 首次运行检查：读项目清单与工作日志尾，确认目标 = §17.10 五项 P1 + 记忆库落盘（源:文件:PROJECT_OVERVIEW.zh-CN.md:1440）
- 13:55 P1-1 open_url 移出 REPLAYABLE，采集/还原分支拆除、旧记录加载过滤（源:文件:agent-lite/src/com/dsh/agentlite/FlowPlan.java:68）
- 13:58 P1-1 同步门禁与用例：flow_memory_guard 45/0、FlowMemoryTest 56/0、设计文档 §9.3 补 open_url（源:测试）
- 14:00 P1-2 ask 改 AskResult 三态：超时暂停+连续 2 次终止+USER_TIMEOUT 标签；A6 门禁 12/0（源:文件:AgentEngine.java:852）
- 14:02 P1-3 M3 网关接线：TOOL_REGISTRY+actionToArgs+exec 前校验，toolRouter 开关默认关；APK 构建通过（源:文件:AgentEngine.java:897）
- 14:03 P1-3 门禁契约同步：M3 两门禁反转、M0:57 改「仅 toolRouter 判定」；33/0·29/0·31/0（源:步骤4）
- 14:04 P1-4 root E2E 文件头降级为「root 专属增强诊断，非门禁非基线证据」（源:文件:tests/integrated_features_e2e.py:1）
- 14:04 P1-5 autoAcceptTerms 默认 true→false，Prefs/引擎 javadoc 记录两次决策脉络（源:文件:Prefs.java:44）
- 14:05 P1-B FlowMemory 原子写（tmp+fsync+rename）+损坏备份 .corrupt+写失败留痕；FM-11 门禁（源:文件:FlowMemory.java:259）
- 14:05 make check 全绿（契约 26/0、FlowMemory 45/0、FlowMemoryTest 56/0 等）；镜像同步 client=23（源:命令:make check + sync-sources.sh）
- 14:05 手册新增 §17.11；无真机在线，ask/M3/open_url 行为级验证列为待真机项（源:步骤8）
- 14:05 终态: done

### 2026-10-02 16:37 +08 · 项目:agent-lite +repo · 会话:local · §17.11 待真机项行为级验证
- 15:35 真机上线（OnePlus PKX110，root 诊断机）；按 §17.11 待真机清单定 A–E 验证矩阵（源:用户:已有真机链接）
- 15:40 新增 tests/p1_device_verify.py（A–E、26 断言、PASS/FAIL/SKIP 三分类）与 mock 三场景 openurl/ask/m3invalid（源:文件:tests/p1_device_verify.py）
- 15:42 排障：su -c 剥 XML 属性引号致 prefs 注入失效（任务启动都不出现）→ 改主机端完整 XML 推写（源:命令:adb shell su -c sed）
- 15:44 排障：mock 按 assistant 计数失真（引擎正常路径不累积历史），序列停 key 4 把 app 退到桌面打转 → 改服务端计数 + DELETE 复位（源:文件:tests/mock_llm_server.py）
- 15:50 真机暴露 P2：主动放弃确认与 5 分钟超时混淆（谎报超时+误暂停）→ 新增 ASK_SKIPPED 独立终态、按钮「跳过」改「不执行」（源:文件:AgentEngine.java:167）
- 15:51 action_schema_guard 新增 A7 两条断言（14/0）；APK 重建，dex 核对含新文案、旧文案 0 残留（源:命令:build-mac.sh）
- 16:05 A 组通过：flow 入库 + 二次零模型往返复现；open_url 执行成功但无入库日志、库无步骤、计数不变（源:测试:p1_device_verify A）
- 16:10 B 组通过：网关开时三种非法动作各拒一次（OUT_OF_RANGE/UNKNOWN_PARAMETER/MISSING_REQUIRED），设备动作仅剩合法 key 1 个（源:测试:p1_device_verify B）
- 16:18 C1/C2 通过：放弃确认→独立终态且待确认操作未执行；回答以「用户回答：」进模型上下文（源:测试:p1_device_verify C）
- 16:19 C3 通过：真实静置 5 分钟 → 结构化 TIMEOUT（第 1 次）+ 暂停 + 窗口内零设备动作（源:测试:p1_device_verify C3）
- 16:20 C3-4 人工补验通过：截图色块定位悬浮窗「我已完成，交还 AI」→ 交还后 USER_TIMEOUT 以系统事件回灌，完成任务摘要「已收到超时事件」（源:命令:adb exec-out screencap + input tap）
- 16:22 C3-4 自动化（find_blue_overlay_button）并加备份护栏：prefs 含 mock 端点时拒绝建立还原源，防测试态套娃固化成用户态（源:文件:tests/p1_device_verify.py）
- 16:30 真机发现 2 项新缺陷（未修，留待下轮主题）：悬浮窗卡片渲染不稳定（一次显示一次未显示，appops default）；超时后提问弹层未关闭、点「不执行」被静默丢弃（源:测试:截图 + appops get）
- 16:36 完整回归 A–E：26 通过 / 0 失败 / 1 需人工（C3-4 脚本轮 SKIP，人工证据已闭环）；验证后还原设备 prefs/flowmem/无障碍与 adb reverse（源:测试:p1_device_verify 全量）
- 16:37 手册新增 §17.12，§17.11 验证边界改为指向已闭环结论；make check 全绿、镜像 client=23（源:文件:PROJECT_OVERVIEW.zh-CN.md）
- 16:37 终态: done

### 2026-10-02 16:44 +08 · 项目:agent-lite +repo · 会话:local · 手册复读与状态一致性修正
- 16:40 复读手册最新三节（§17.10–§17.12）与三个 2026-10-02 日志块，核对结论是否与已修事实一致（源:文件:PROJECT_OVERVIEW.zh-CN.md:1440）
- 16:42 发现缺口：§17.10 五项 P1 与 B 节残余风险仍以「现存缺陷」语气记载，C/D/E 节结论亦过时 → 加「状态更新」总表 + 四处行内标注（保留审计原文不改写）（源:文件:PROJECT_OVERVIEW.zh-CN.md:1444）
- 16:43 核实两处声明：make check = go+contract+ui+jvm 不含任何真机脚本；ui 目标为显式脚本白名单（非 glob），新增真机脚本不会误入门禁（源:命令:grep Makefile + make check）
- 16:43 记录一处未落地项：§17.10.E-2 要求的结构化标签中 TOOL_INVALID 尚未实现（M3 拒绝回灌仍为自然语言句子），与 USER_TIMEOUT 已实现形成不一致（源:文件:AgentEngine.java:962）
- 16:44 手册改动后 make check 复验全绿（契约 26/0、A6+A7 14/0、FlowMemory 45/0、M3 33/0·29/0、M0 31/0）（源:命令:make check）
- 16:44 终态: done

### 2026-10-05 23:14 +08 · 项目:repo · 会话:local · 适老化未来开发手册续写
- 23:14 完成首次运行检查：读项目清单与日志尾；确认仅改共享文档，保留已有脏树（源:命令:git status --short）
- 23:14 核查 Hindsight 四页均为生成占位，未作为实现证据；确定以工作树源码与测试为准（源:记忆）

### 2026-10-06 04:10 +08 · 项目:repo · 会话:local · 续写适老化开发手册
- 04:10 续: 上次未收尾；复读日志与工作树，接续只读核查并保留原有修改（源:命令:git status --short）
- 04:10 核实官方 deepseek-flash 映射 V4.1 Flash 与视觉等接口；产品效果、语音及数据条款边界分列（源:web:api-docs.deepseek.com）
- 04:10 查明登录无 Key 入口与客户端路由矛盾、done 复验放行及确认生命周期风险（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:913）
- 14:08 续: 中断后复读日志；复核四项静态门禁及七组 JVM 均通过，不外推为无 root 真机成功（源:命令:make jvm）
- 14:08 已增全景 §18 进度矩阵；规划 §23 含模型边界、十张任务卡、十个场景与验收线（源:文档:§18/§23）
- 14:08 已修正 UX 协议默认关及新装/升级区别；保留历史决策，未改业务代码（源:文档:UX-001 §1.1）
- 14:11 校验新增区块 90 个本地链接、10 张任务卡与 10 个场景均通过；UX 复验 17/0/1（源:命令:文档自检）
- 14:11 git diff --check 通过；本轮仅改三份文档，保留既有脏树；真机、用户和付费模型验证未执行（源:命令:git diff）
- 14:11 终态: done（文档核查与续写完成；T01–T10 为待实施计划，不代表业务缺陷已修）

### 2026-10-06 19:09 +08 · 项目:agent-lite +repo +agent-unified · 会话:local · 凭据路由回归与首跑修复
- 19:09 完成首次检查并获准扩展到测试与代码；复读日志、保留已有脏树，不访问真实账号或付费接口（源:用户请求）
- 19:09 确认入口误用默认偏好、请求层自动跨付费通道回退；登录本身不改用户选择（源:文件:agent-lite/src/com/dsh/agentlite/LlmClient.java:194）

### 2026-10-07 11:57 +08 · 项目:agent-lite +repo +agent-unified · 会话:local · 续完凭据路由修复
- 11:57 续: 上轮未收尾；复读日志，已落地离线测试及解析器，继续接线而非重复规划（源:文件:tests/credential_route_guard.py）
- 11:57 核对问题版回归为 109 通过/68 失败；全用内存连接与假凭据，未访问真实服务（源:测试:凭据回归基线）

### 2026-10-08 19:02 +08 · 项目:agent-lite +repo +agent-unified · 会话:local · 凭据路由修复收尾
- 19:02 续: 复读前两轮日志；核对 10-07 已完成接线，今日更新手册并重新执行离线验收（源:用户请求）
- 19:02 确认共用路由、显式选择、保存/清 Key 保持主体、规划快照及欠费归因已接通（源:代码复核）
- 19:02 最终凭据行为回归 245/0，内存连接覆盖正常与故障响应，不访问真实模型（源:测试:凭据回归）
- 19:02 完整 make check 退出 0；Go 缓存/无测试包、UI 手工项与既有差异警告保留（源:命令:make check）
- 19:02 Android 34/Java 11 编译退出 0，仅有弃用提示；未打包、签名或安装（源:命令:javac）
- 19:02 原项目与镜像逐字节检查通过，未独立改镜像；工作树既有修改保留（源:命令:sync-sources --check）
- 19:02 全景 §18.6、规划 §23.11、凭据规范已同步；新增章节 17 个本地链接通过（源:检查:文档链接）
- 19:02 终态: done（T01 凭据切片/T02 本地实现完成；其余行为夹具和零 root 真机首跑待验）（源:本轮验收）

### 2026-10-08 21:25 +08 · 项目:agent-lite +repo +agent-unified · 会话:local · 凭据路由真机回归
- 20:15 用户报告真机已连接；设备 OnePlus PKX110 安卓16/API36，已装版本是 10-02 旧包故重建安装（源:用户请求）
- 20:26 新增真机回归脚本，自带两个本机假端点经 adb reverse，全程不出网不花钱（源:文件:tests/credential_real_device.py）
- 20:26 零 root 主链路：只走 uiautomator+input，root 仅布前置状态与取证；设备用户态先备份后还原（源:测试:真机准备）
- 20:39 阶段 A/B/C/D 全通过：已登录无 Key 原缺陷修复得到真机证实，请求到网关并带账号令牌（源:测试:真机ABCD）
- 20:45 阶段 E 全通过：自带 402 归自己的服务账号、401/500 不跨付费主体、终态不泄漏测试凭据（源:测试:真机E）
- 20:50 阶段 F 全通过：保存配置弹选择卡、暂不切换保持主体、主动选择才切换（源:测试:真机F）
- 21:10 真机暴露文案错向：自带欠费却建议"配置自己的接口作为备用通道"；修 UiText/AgentEngine（源:代码:UiText.java:76）
- 21:12 桌面固化 3 条「下一步」断言，ui_text_guard 13/0；make ui jvm 全通过（源:测试:UiTextTest）
- 21:24 最终连续 A–F 真机回归 43 通过/0 失败；镜像逐字节一致，设备用户态已还原（源:测试:真机最终）
- 21:25 终态: done（T01/T02 经零 root 真机主链路验证；真实账号/余额、其他机型、字号与无障碍朗读仍待验）（源:本轮验收）

### 2026-10-09 16:41 +08 · 项目:repo · 会话:local · 真机无线 adb 连接打通
- 16:33 目标 192.168.1.106:41061 直连报 No route to host；nc/curl 可连证明端口在线（源:命令:adb connect）
- 16:36 对照定位根因：蓝猫VPN 系统扩展拦第三方进程直连局域网，nc/curl/bash/perl 不受影响（源:命令:python3/node 对照）
- 16:38 USB 连上 PKX110；切 adbd tcpip 5555，perl 中继 5599→192.168.1.106:5555 后无线连接成功（源:命令:adb connect 127.0.0.1:5599）
- 16:40 固化 tools/adb-wifi/ 一键与守护脚本，--stop/重建复测通过（源:文件:tools/adb-wifi/adb-wifi.sh）
- 16:41 终态: done（无线通道 127.0.0.1:5599 可用；拔线独立性、手机重启后需重跑 tcpip 待用户验证）

### 2026-10-09 16:44 +08 · 项目:repo · 会话:local · 手机检查与Flash修改方案
- 16:47 恢复上轮基线：真机凭据43/0、桌面245/0；本轮仅检查与方案撰写，保留既有工作树（源:文件:PROJECT_OVERVIEW.zh-CN.md:2569）
- 16:47 核实PKX110经127.0.0.1:5599在线，API36、应用0.6.0；当前shell为uid=0，另列严格无root验收（源:命令:adb只读核验）
- 16:55 复跑凭据离线回归245/0、镜像--check一致、UiText行为13/0；子代理中断遗留的行号发现逐条亲核属实（源:测试:凭据复跑）
- 16:57 固化三卡证据：代点绕过exec、二次done放行并入库、胶囊4秒分支缺taskActive守卫、finally延迟回调无代际（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:1712）
- 16:59 产出DS4.1 Flash三卡最小切片方案（T05a→T03a→T04a，含回归与回滚），文档链接9项目标全过（源:文件:docs/PLAN_T03_T05_DS41FLASH_2026-10-09.zh-CN.md）
- 16:59 终态: done（检查与方案撰写完成，未改业务代码；实施留待后续会话）

### 2026-10-09 17:03 +08 · 项目:agent-lite +repo +agent-unified · 会话:local · T05a代际防护/T03a入口收敛/T04a证据分级
- 17:01 复读方案与日志尾；认领 agent-lite+repo+agent-unified，按 T05a→T03a→T04a 顺序实施（源:文件:docs/PLAN_T03_T05_DS41FLASH_2026-10-09.zh-CN.md）
- 17:02 T05a 先写 tests/generation_guard.py，旧代码跑出 9 通过/5 失败（4秒分支缺守卫、无 RUN_SEQ、finally无代际）（源:测试:代际门禁）
- 17:03 T05a 修复：taskEnded 4秒分支补 taskActive 守卫；AgentEngine 加 RUN_SEQ 代际，finally 回调改 mySeq==RUN_SEQ.get()（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:283）
- 17:03 门禁转 14/0；make ui jvm 全绿（凭据 245/0 未回退）；镜像同步后 --check 逐字节一致（源:命令:make ui jvm）
- 17:08 T03a 先写 tests/auto_tap_guard.py + 公共 tests/java_source_util.py（剥注释，防"把守卫写进注释"假绿），旧代码 FAIL 点为 1718/1751/1759 三处（源:测试:入口门禁）
- 17:08 T03a 改造：代点/代勾/代同意三处改为 exec(svc,{tap,index},nodes)，失败原因进日志不再静默（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:1721）
- 17:08 injectViaClipboard 的 root 聚焦点击登记为受约束例外（applyOffset 非幂等，改 exec 会二次偏移；例外须在 RootShell 守卫下且保留 clipPending 降级）（源:文件:tests/auto_tap_guard.py）
- 17:08 门禁转 15/0；make ui 全绿（含既有 9 项）；make jvm 凭据 245/0；镜像 --check 一致（源:命令:make ui）
- 17:08 T04a 先写 tests/DoneEvidenceTest.java + tests/done_evidence_guard.py（抽取真实 judgeDone），旧代码 5 通过/7 失败（源:测试:证据门禁）
- 17:08 T04a 落地：三档 PROVEN/WEAK/UNPROVEN + arrivedExpectPkg 强证据源；UNPROVEN 终态「不确定」、recordable=false、不入库；新增 showUncertain 琥珀卡（源:文件:agent-lite/src/com/dsh/agentlite/AgentEngine.java:938）
- 17:08 复跑：真值表 18/0、结构守卫 18/0；make ui jvm 全绿；APK 重建退出 0（19.6MB）；镜像 --check 一致（源:命令:bash agent-lite/build-mac.sh）
- 17:12 真机前置：用户解锁后恢复 a11y 绑定（装 APK 会被系统摘除），安装本轮包（sha256 d5262e9f…）（源:命令:adb install）
- 17:20 凭据真机回归 A–E **41 通过 / 0 失败**；阶段 F 两次都在收尾的 uiautomator dump 上超时崩溃（脚本脆弱点，非断言失败）→ 单跑 F **6 通过 / 0 失败**（源:测试:真机凭据）
- 17:35 新增 tests/t03_t05_real_device.py：自带本机假模型（19111，经 adb reverse）+ 零 root 主链路（input / uiautomator dump / am start），root 仅布前置态与取证并还原（源:文件:tests/t03_t05_real_device.py）
- 17:45 真机首轮 6 通过/4 失败：T05a 六项全绿；「有证据 done」假失败连查四处**测试侧**真凶——dump 挤掉 a11y 致服务重连初期只枚举 SystemUI、首跑通知弹窗把前台变 systemui、mock 的 app 参数写成 target（词表要 package）、mock 未分流转译/规划请求（源:测试:真机三卡）
- 17:52 另两处注入坑：点「运行」前弹出的 IME 盖住按钮（ESC 收键盘）；任务结束后布局整体下移 24px（改点按钮下缘上方 1/4）（源:文件:tests/t03_t05_real_device.py）
- 18:04 真机终轮 **14 通过 / 0 失败 / 1 跳过**：A 完成后 0.2 秒启动 B，B 的保活锁/覆盖层胶囊/运行态通知均未被旧回调破坏；无证据 done → 记忆库 344→344 未增长；有证据 done → 「完成」且入库 344→707（源:测试:真机终轮）
- 18:05 make check 全绿；镜像 --check 逐字节一致；git diff --check 干净（源:命令:make check）
- 18:06 规范三处同步：守则升 1.0.8（第四种终态「不确定」/ T-3a 完成语义位独占 / T-3b 不入库 / DEV-10·DEV-11）、Skill 补 I-5 与四条溯源、规划 §23.12、全景 §18.8、CHANGELOG（源:文件:docs/INTERACTION_LOGIC_SPEC.zh-CN.md:11）
- 18:09 终态: done（三卡实施 + 真机回归 14/0/1；未做 T04b 与 T03a 第二刀；真机暴露的「结果卡片挡运行」竞态已登记 DEV-11）（源:本轮验收）

### 2026-10-09 17:37 +08 · 项目:dsh-env · 会话:local · SSH 链路与隧道体检
- 17:35 到 ECS 8.133.225.105 免密登录正常（uptime 14 天，agentlite-server 监听 *:18443）（源:命令:ssh aliyun-ecs）
- 17:36 六条隧道实测：19001/19003/19004/8417/19005/19006 均通；19002→18446 无监听为死链路（源:命令:curl 127.0.0.1 各端口）
- 17:36 19001 的 404 与 ECS 本机 8888 行为一致（BT-Panel），非隧道故障；18446 属手册所述历史临时实例（源:文件:docs/DEVELOPMENT_MANUAL.zh-CN.md:518）
- 17:37 手机端无 sshd（22/8022 refused）、本机 sshd 未监听；utm-dev(19010) 未起系 UTM 虚拟机未运行（源:命令:nc/adb devices）
- 17:37 终态: done（隧道体检完成；19002 目标端口未改，修复命令已交付用户自行决定）

### 2026-10-09 21:27 +08 · 项目:repo · 会话:local · 功能接口与最小架构审计
- 21:27 读取项目清单与日志尾，确认源码及未提交工作树为审计基线，仅交付文档（源:用户请求）
- 21:40 自查完成全局拓扑：7 子项目职责、服务端 23 路由、agent-lite 动作词表与网络客户端分布（源:自查）
- 22:10 四路并行子代理深挖（iOS/服务器/agent-lite/agent-client+agentd），三份首报告回收；agent-client 首任务失败重派收窄范围（源:子代理报告）
- 22:20 核实两处子代理报告偏差：BailianClient 非第 4 份 LLM 客户端（纯 Key 管理面）但确认为 ≈933 行死链路；contracts/ 整包零接线改判死代码，修正 §8.5 裁决（源:grep 验证）
- 22:34 文档 docs/INTERFACE_AUDIT_2026-10-09.zh-CN.md 定稿 459 行：§1-§9（拓扑/路由与契约清单/词表/分项发现 S17+I24+A25+X16/第一性分析/重构建议 R1-R9）（源:文档核读）
- 终态:完成——审计文档交付，源码零改动；最高优先级建议 R1-R4（HTTP 栈收敛为单实现/兑换码单入口/push 批量合并/网关解码 4→1），死代码清除 R5 ≈2600+ 行

### 2026-10-10 00:26 +08 · 项目:repo · 会话:local · 功能接口与架构证据复核
- 00:26 确认七子项目及现有审计，基于含未提交变更的工作树复核，保留业务源码（源:命令:git status --short）
- 05:33 完成功能边界及关键链路源码复核：22 个服务端方法路由、Android 25 动作（计数于05:40校正）；确认同步游标跳页、SSE 包装能力丢失、ASR 重走失败端点，纠正旧审计的静态接线判断（源:文件:agentlite-server/main.go:95-130；文件:ios/Sources/AgentLiteCore/SyncService.swift:72-82；文件:agent-lite/src/com/dsh/agentlite/AsrRouter.java:42-87）
- 05:40 完成401行接口审计文档：七子项目功能映射、14项有源码证据的问题、数据搬运账本及最短实施顺序；旧版已添加纠正指引（源:文件:docs/INTERFACE_AUDIT_2026-10-10.zh-CN.md）
- 05:40 机械校验通过：191个源码引用路径及行号、22个服务端HTTP操作、25个Android动作；仅静态审计，未运行全项目测试/真机/压测（源:命令:Python文档覆盖校验，exit 0）
- 05:40 终态: done（接口清单与去冗余架构文档交付；业务源码未改；文档范围git diff --check通过；无遗留后台作业）（源:本轮文档验收）

### 2026-10-10 16:54 +08 · 项目:repo +agentlite-server +agent-lite +ios +agentd · 会话:local · 审计方案实施
- 16:54 读取日志与审计，确认38个已有变更文件并保留；恢复免设备验证入口（源:命令:git status --short；make帮助）
- 17:03 基线make check通过；四项新增服务端回归先红后绿，修正next_since分页、账号域fingerprint、任务终态保留与幂等ACK（源:测试:agentlite-server/interface_correctness_test.go；命令:go test）
- 17:23 完成单日志账务原子提交与日志wrapper流式透传，12项Go动态测试及race全过；iOS新增15项同步/账号隔离/请求计数回归通过，Android请求合并与结果接线进行中（源:命令:go test -race ./...；swift test；工具:time）
- 02:59 续至10月11日：收齐独立实现回归；Go存储/网关与单帧感知race通过，Android账号60场景通过（源:命令:go test -race；测试）
- 02:59 收敛HTTP、ASR与LAN批处理；补三来源字节预算，修正四向翻页旧测试预期（源:文件:agent-lite/src/com/dsh/agentlite/SyncClient.java）
- 13:49 续至10月11日：已回收全量make check绿、服务端32测试与两Go模块race/vet、Swift57项含10真实环回TLS E2E全过；Android账号66场景1125断言及动作51断言通过（源:命令:make check；go test -race ./...；swift test；后台作业结果）
- 13:49 Android真实APK与iOS真App不签名构建通过，未安装/部署；实施报告215行覆盖F01–F14与迁移/回滚边界，现行手册对齐新分页/指令/单日志契约（源:文件:docs/INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md；命令:bash agent-lite/build-mac.sh；xcodebuild）
- 13:55 最终镜像同步/逐字节检查、make check、git diff --check全过；文档校验243本地路径、17现行源码行锚点、5标题锚点，历史审计140旧行锚点保留快照不重编号，APK指纹复核一致（源:命令:后台作业bash-361，exit 0；Python文档/产物校验）
- 13:55 终态: done（F01–F14源码收敛、回归、两端构建、镜像和实施文档交付；既有工作树保留；无遗留后台作业；未自动提交、线上部署或设备安装）（源:本轮验收；工具:time）

### 2026-10-16 19:30 +08 · 项目:repo · 会话:local · Agent Lite 参赛演示 PPT 制作
- 18:26 加载 ppt-master skill，完成 Stage1/Stage2 确认与设计规格落盘（深色发布、15 页、Speaker Notes 开启）（源:文件:~/ppt-master/projects/agentlite-ppt_20261016/design_spec.md；文件:spec_lock.md）
- 19:20 产出 15 页 SVG 并通过最终质量门禁 0 错误；修复 §VIII 图片 Type Source→Diagram 与 p15 组边界溢出（源:命令:svg_quality_checker.py --canonical-authoring --stage final，exit 0）
- 19:25 拆分 15 段演讲备注、生成自包含预览并导出原生 PPTX（15 页 + 15 备注；zip 完整性、字体可移植、外链图片检查全过）（源:命令:total_md_split.py；finalize_svg.py；svg_to_pptx.py）
- 19:30 产物复制至 竞赛材料/Agent Lite 参赛演示.pptx；画布越界校验 117 bounds 全过（源:命令:Python 边界校验）
- 19:30 终态: done（PPT 交付：15 页深色发布风格 + 15 段答辩备注；未提交 Git、未部署）（源:本轮验收）
- 19:45 按用户反馈（m00618「这几个图片文字出来了」→ m00634 选方案 1）将 P04 开发流程图、P05 运行原理图由嵌入位图改为原生矢量重画：53 个 DrawingML 预设形状（roundRect 39 + downArrow 14）+ 可编辑文字，节点文字 16px、卡标题 22px（源:文件:~/ppt-master/projects/agentlite-ppt_20261016/svg_output/p04.svg；p05.svg）
- 19:55 门禁重跑 EXIT=0（blocking 0、errors 0，关系页 2，未声明字号与 p01 组溢出已修）；finalize_svg + svg_to_pptx 重导出 PPTX 81674B（较上版 267180B 减少 217738B 即两张被移除位图），postflight quality_gate=passed（源:命令:svg_quality_checker.py --canonical-authoring --stage final；finalize_svg.py；svg_to_pptx.py）
- 20:00 交付副本与总览图更新至 竞赛材料/；design_spec §VIII 图片清单清空为「不使用外部位图」说明、§IX Part 2 改写为原生矢量（源:文件:竞赛材料/Agent Lite 参赛演示.pptx；文件:竞赛材料/Agent Lite 参赛演示_15页总览.png）
- 20:00 终态: done（P04/P05 位图改矢量完成并重导出，15 页交付更新；未提交 Git、未部署）（源:本轮验收）

### 2026-10-18 14:41 +08 · 项目:repo · 会话:local · Git 工作区提交并推送 GitHub
- 14:43 核对仓库实况：远程仅剩 shell-command-backend（4c4a2c9，2026-09-11），本地领先 107 提交且工作区 179 处改动（源:命令:git ls-remote；git rev-list --left-right）
- 14:44 提交前安全扫描：改动文件无真实密钥字面量，8.133.225.105 等线上地址早存于 HEAD 历史，无手机号/邮箱新增（源:命令:grep 密钥/IP/邮箱模式）
- 14:45 校验 agent-unified 镜像逐字节一致（client/server 均 ✓，exit 0）（源:命令:bash agent-unified/scripts/sync-sources.sh --check）
- 14:45 .gitignore 增加 /.device-verify/ 与 /.ppt-master-work/ 本地产物，避免截图与转换中间件入库（源:文件:.gitignore）
- 14:46 分 5 个主题提交：客户端模块 e02c648、服务端计费 874f627、测试 af148d2、文档 3565cdb、契约脚本 cc05cf4（源:命令:git commit）
- 14:46 推送 4c4a2c9..cc05cf4 并补推本地独有 tag v0.6.0-zerofriction（源:命令:git push origin）
- 14:47 make check 全量免设备门禁通过（源:命令:make check，exit 0）
- 14:47 终态: done（工作区零未提交、远程止于 cc05cf4；默认分支与 Release 未动）
