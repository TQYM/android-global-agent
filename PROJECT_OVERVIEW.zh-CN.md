# Android Global Agent — 项目全景文档

> 本文档供后续 AI 接手时快速了解项目现状、技术决策与已知边界。
> 最后更新：2026-09-06

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
- 终态: blocked（同 01:42：等套餐↔官方档位对应关系 + 需先 curl 打通 CreateApiKey）- 02:28 提交 bc1fb13「feat(ui): 界面回归第一版 + 移除 Material 3 + 主界面精简 + 引擎震荡看门狗」（源:命令:git commit）
