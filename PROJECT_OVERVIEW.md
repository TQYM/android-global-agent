# Android Global Agent — 项目全景文档

> 本文档供后续 AI 接手时快速了解项目现状、技术决策与已知边界。
> 最后更新：2025-09-05

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

### 新平板：华为 YLP-W00
- Android 17（HarmonyOS 5.0 兼容层），**无 root**
- 分辨率 2136×3200，密度 420
- 序列号 `AAQLBB5C03000706`
- **零 root 适配目标设备**

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

### 为什么必须 root
ColorOS（实测 ColorOS 16）会把**任何**普通/MediaProjection 虚拟屏的任务投射到物理屏（`canHostTasks=false`）。只有 root 进程 + `TRUSTED`(1024) + `OWN_DISPLAY_GROUP`(2048) + `STEAL_TOP_FOCUS_DISABLED`(65536) 才能建出真正隔离的虚拟屏。

### 实现
- **守护进程**：`vd-daemon/VdMain.java` → `assets/vd_daemon.jar`
- 以 `app_process` 在 root 下运行，用 `ActivityThread.systemMain()` 获取系统 Context
- 守护进程每 10s `pgrep -f com.dsh.agent`，宿主死亡则自动销毁，防止泄漏
- 沙盒开关 = **纯点按，零弹窗**（删除了 MediaProjection 授权流）

### 通道
| 操作 | 命令 |
|---|---|
| 建屏 | `app_process ... VdMain W H DPI` |
| 起 App | `am start --display <id> ...` |
| 截图 | `screencap -p -d <SF-id> <file>` |
| 触控 | `input -d <displayId> tap/swipe/keyevent` |

### 已知边界
- 单实例应用已在真屏运行时，沙盒无法接管（引擎明确报错，不乱动真屏）
- 系统弹窗/IME 仍可能出现在默认显示
- 沙盒创建必须后台线程（主线程 su 链会 ANR）
- `pkill -f` 模式不能包含自身命令行 → 用 `"agent_[v]d.jar"` 反匹配

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

---

## 10. 当前待办

- [ ] **平板零 root 适配**：在 YLP-W00（Android 17，2136×3200）上安装、验证零 root 链路（a11y 节点、截图、手势、文本注入、语音）
- [ ] **沙盒稳定性**：长任务中守护进程偶发死亡（需加持久化/看门狗）
- [ ] **ASR 质量**：Omni 音频转写对实际中文语音的准确率待用户验证
- [ ] **模型包名 cheat sheet 扩展**：继续补充更多常用 App
- [ ] **平板横屏/多窗口适配**：分辨率差异、密度差异、横屏布局
- [ ] **HarmonyOS 兼容性**：确认 Android 17 兼容层下 a11y API 行为是否一致

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

---

## 12. 安全红线

- `api_key` **永不入 git**，仅存在设备 SharedPreferences
- 用户活跃使用手机时（聊天、支付、浏览），**禁止任何盲触输入**
- root 是可选加速器，**永远不是零 root 路径的依赖**
