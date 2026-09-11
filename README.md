# Android Global Agent（面向安卓的通用性Agent）

> 面向 Android 11+ 的视觉与语义驱动手机自动化 Agent。项目以无障碍服务为通用执行底座，并可在已授权的 Root 设备上启用更快的截图、输入和隔离虚拟屏能力。

[项目全景](PROJECT_OVERVIEW.md) · [兼容性说明](agent-lite/COMPATIBILITY.zh-CN.md) · [安全审查](SECURITY_REVIEW_2026-09-05.zh-CN.md) · [Apache-2.0 许可证](LICENSE)

## 三步上手（Android 11+）

1. **装**：从 [Releases](https://github.com/TQYM/android-global-agent/releases) 下载 APK 侧载安装；
2. **授权**：系统设置中开启 **Agent Lite 无障碍服务**，按需允许通知与麦克风；
3. **填 Key**：应用内填写 Base URL / API Key / 模型，输入中文任务运行。

> Agent Lite 不需 Root。API Key 只保存在本机。

## 项目定位

Android Global Agent 尝试把自然语言任务转换为可验证的手机操作循环：

```text
用户任务
   ↓
语义规划（可选）
   ↓
屏幕节点 + 截图感知
   ↓
OpenAI 兼容多模态模型决策
   ↓
点击 / 滑动 / 输入 / 启动应用 / 系统动作
   ↓
页面变化复验，直到完成、失败或需要用户接管
```

当前主推 `agent-lite/`：它不依赖 Root，使用原生 Java 与 Android Framework API 实现感知和操作，并加入任务可见性、授权、敏感场景接管、历史记录与显式记忆等交互能力。

## 当前能力

### 零 Root 通用能力

- 通过 `AccessibilityService` 采集可访问窗口和语义节点；
- 通过 `takeScreenshot()` 获取屏幕图像，节点不可见时可切换纯视觉坐标模式；
- 通过 `dispatchGesture()` 完成点击、长按、滑动和边缘返回；
- 通过 `ACTION_SET_TEXT` 输入 Unicode/中文，失败时可降级到输入法或剪贴板通道；
- 支持返回、主页、最近任务、通知栏、快捷设置、锁屏等全局动作；
- 支持启动已安装应用、直达常用系统设置、调节亮度和音量；
- 支持录音并调用兼容接口完成语音输入；
- 使用前台服务提高国产 ROM 上的后台存活能力。

### Agent Lite 交互与安全能力

- **代理状态可见**：胶囊、边缘光晕、授权卡片、接管卡片和任务终态卡片；
- **事前授权**：按“操作类型 × 应用”支持单次允许、始终允许或拒绝；
- **事中控制**：任务运行时可停止或补充要求；
- **事后追溯**：任务结果写入本地历史记录，可管理授权、生态策略与记忆；
- **敏感场景接管**：遇到支付、验证码、协议确认、系统权限等场景暂停自动操作；
- **生态策略**：可对特定应用限制自动操作，或在授权后仅使用视觉模式尝试；
- **规划前置**：可用独立模型把口语任务整理为目标、步骤、完成判据和风险提示。

### 可选 Root 增强

完整版 `agent-client/` 在获得用户明确授权后可使用 Root 作为加速器：

- `screencap` 提高截图频率与稳定性；
- `input tap/swipe/keyevent` 提供同步输入通道；
- Root 守护进程创建可信虚拟显示，将目标应用迁入隔离屏幕运行；
- 沙盒失效时停止操作并尝试恢复，禁止降级到真实屏幕盲触。

Root 不是 `agent-lite` 的运行前提。隔离虚拟屏仅适用于已解锁且由用户控制的测试设备。

## 子项目说明

| 目录 | 状态 | 用途 |
|---|---|---|
| `agent-lite/` | **推荐** | 零 Root 原生客户端，当前主要开发方向，包名 `com.dsh.agentlite` |
| `agent-client/` | 可选 | 完整客户端，包名 `com.dsh.agent`，包含可选 Root 加速和虚拟屏沙盒 |
| `agentd-go/` + `agentd-apk/` | 实验性 | Root 常驻 Go 守护进程、WebUI 与无障碍桥接方案 |
| `tests/`、`tools/` | 工具 | 设备测试计划、测试 harness 与 UI 采集工具 |
| `docs/` | 文档 | 架构、安全、兼容性、操作记录和专项设计说明 |

## 环境要求

### 构建 Agent Lite / Agent Client

- macOS（仓库提供的脚本按 macOS Android SDK 默认目录编写）；
- JDK 11 或更高版本；
- Android SDK Platform 与 Build Tools；
- ADB（安装和调试时需要）；
- Android 11+ 设备（`minSdk 30`）。

客户端采用纯命令行工具链，无 Gradle、无 Kotlin、无第三方运行时依赖：

```text
aapt2 → javac --release 11 → d8 → zipalign → apksigner
```

## 快速开始：Agent Lite

### 1. 构建

```bash
git clone https://github.com/TQYM/android-global-agent.git
cd android-global-agent/agent-lite
sh build-mac.sh
```

产物：

```text
agent-lite/build/agent-lite.apk
```

如果 Android SDK 不在默认位置，请先设置：

```bash
export ANDROID_HOME=/path/to/Android/sdk
```

### 2. 安装

```bash
adb install -r build/agent-lite.apk
```

### 3. 完成设备设置

1. 打开应用；
2. 在系统设置中启用 **Agent Lite 无障碍服务**；
3. 按需授予通知、麦克风和忽略电池优化权限；
4. 在应用设置中填写兼容接口的 Base URL、API Key、主模型和可选规划模型；
5. 输入自然语言任务并运行。

> API Key 只应保存在本机配置中。不要把真实密钥提交到 Git，也不要把包含密钥的 APK 公开发布。

### 4. 可选开发配置

`agent-lite/build-mac.sh` 支持读取未跟踪的 `agent-lite/dev.local`，仅供本地测试构建：

```bash
API_KEY="your-local-key"
BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
MODEL="qwen3.5-omni-plus"
```

该文件已被 `.gitignore` 忽略。公开构建前应删除它，确保 APK 不包含开发密钥。

## 模型接口

客户端使用 OpenAI 风格的兼容 HTTP 接口，当前默认配置面向 DashScope：

| 配置项 | 默认值或说明 |
|---|---|
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 主模型 | `qwen3.5-omni-plus` |
| 规划模型 | `qwen3.5-flash`，可关闭 |
| 视觉 | 主模型支持图像输入时可启用 |
| 语音 | 支持模型原生音频输入或兼容转写接口，取决于服务商 |

不同服务商对消息结构、图片、音频和推理参数的支持并不完全一致，切换模型后请先用简单任务验证。

## 构建其他组件

### 完整客户端

```bash
cd agent-client
sh build-mac.sh
adb install -r build/agent-client.apk
```

产物为 `agent-client/build/agent-client.apk`。Root 与沙盒模式需设备端 Root 管理器明确授权。

### Go 守护进程（实验性）

```bash
cd agentd-go
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -ldflags="-s -w" -o build/agentd .
```

`agentd-go` 以 Root 权限运行并暴露本地控制面，风险显著高于普通应用。除非已完成监听地址、鉴权、数据权限和网络边界审查，否则只应在隔离测试设备与可信网络中评估。

## 已验证情况

当前代码曾在以下环境进行开发测试：

- Android 16 / ColorOS 16 / OnePlus 13T：零 Root 链路及 Root 沙盒链路；
- Android 17 / MagicOS / 荣耀平板：节点采集、截图、手势和中文输入的零 Root 链路。

这些结果不代表所有厂商 ROM 均已兼容。覆盖安装或强制停止后，部分 MagicOS 设备会清除无障碍服务绑定，需要用户重新启用。

## 已知限制

- 无障碍截图从 Android 11 开始可用，并可能受到系统限频；
- 微信等应用可能隐藏无障碍节点树，只能依赖截图与视觉坐标；
- 无 Root 时不能静默切换 Wi-Fi、蓝牙或绕过系统权限确认；
- 系统弹窗、支付、验证码、授权协议等必须由用户检查或接管；
- 多窗口、横竖屏切换、OEM 后台策略和复杂输入法仍需持续适配；
- 模型可能误解界面或生成错误动作，不能把自然语言模型当作可靠的安全授权机制。

## 安全与隐私

本项目只能用于自有设备或已获得明确授权的设备。请遵守以下边界：

- 不捕获或绕过 `FLAG_SECURE`、DRM、硬件密钥存储、应用沙箱及系统安全机制；
- 不尝试绕过受限设置、Play Protect、权限确认或第三方反篡改；
- 不允许 Agent 在支付、转账、验证码、隐私协议等高风险步骤中自行确认；
- 截图、屏幕文本、任务内容和录音可能被发送到用户配置的模型服务，请自行评估服务商隐私政策；
- API Key、签名文件、测试 APK、日志、截图和设备标识不得提交到公共仓库；
- Root 组件只应在隔离测试设备上使用，并坚持最小权限和本机访问原则。

历史安全审查见 [`SECURITY_REVIEW_2026-09-05.zh-CN.md`](SECURITY_REVIEW_2026-09-05.zh-CN.md)。当前项目仍处于开发与研究阶段，不建议用于无人值守的生产环境或任何高风险业务。

## 文档导航

- [`PROJECT_OVERVIEW.md`](PROJECT_OVERVIEW.md)：项目架构、技术决策和当前状态；
- [`agent-lite/COMPATIBILITY.zh-CN.md`](agent-lite/COMPATIBILITY.zh-CN.md)：Android 11+ 兼容性和装机说明；
- [`docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md`](docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md)：Agent Lite 交互规格实现；
- [`docs/PROGRESS_2026-09-06.zh-CN.md`](docs/PROGRESS_2026-09-06.zh-CN.md)：最近一次真机实测与待办；
- [`docs/AGENT_FRAMEWORKS.md`](docs/AGENT_FRAMEWORKS.md)：第三方 Agent 框架选型与 DSH 直驱手册；
- [`SECURITY_REVIEW_2026-09-05.zh-CN.md`](SECURITY_REVIEW_2026-09-05.zh-CN.md)：安全审查报告。

## 开发建议

提交代码前至少执行与改动范围对应的检查：

```bash
# Agent Lite 构建
cd agent-lite && sh build-mac.sh

# 完整客户端构建
cd agent-client && sh build-mac.sh
```

请勿使用 `git add -A` 盲目提交整个工作目录；先检查 `git status`，排除密钥、APK、ZIP、设备日志、上传文件和一次性运维脚本。

## 许可证

本项目使用 [Apache License 2.0](LICENSE)。
