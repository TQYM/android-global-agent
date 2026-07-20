# Android 14 全局 Agent

[English](README.md) | 简体中文

本仓库是一个面向 Android 14 全局 Agent 的安全边界实现脚手架，仅适用于自有设备或已获明确授权的设备。项目将可移植状态机与 AOSP 私有的屏幕捕获、输入 API 分离。

本项目不会绕过受限设置（Restricted Settings）、增强确认模式（Enhanced Confirmation Mode）、Play Protect、安全界面、硬件支持的密钥存储、应用沙箱或第三方防篡改机制。

## 已实现

- 采用 C++20 编写的感知、决策和输入循环，单步截止时间为 200 ms。
- 可从崩溃中恢复的 mmap 状态存储，使用两代 CRC 保护数据。
- 带有显式二进制序列化的有界状态图。
- 按近似弧长采样的确定性三次贝塞尔路径。
- 面向低频 `dumpsys activity/window` 诊断输出的标准化解析器。
- 有界子进程运行器，在超时时终止诊断命令。
- AOSP 14 `ScreenshotClient::captureDisplay(DisplayId, ...)` 后端，包含有界回调和 fence 等待；经 Root 授权的 `captureDisplayById` 路径仍会禁用安全内容捕获。这是私有平台 ABI，必须针对设备对应的源码版本进行编译。
- 使用经过校验的结构化 AIDL 消息、由平台签名的 Java 输入桥接服务。
- 带调用方鉴权和单调 revision 的会话 AIDL，支持显式触发、有界 transcript、取消
  和视觉状态回调；bridge 客户端会在 Binder death 后重置 revision 基线。
- 用户可见的 bridge Launcher Activity，提供解锁/亮屏门禁、显式开始、文本 final
  transcript、状态显示、取消和退后台自动取消。
- 平台任务元数据发布器，无需向守护进程授予宽泛的 dumpsys 权限。
- 独立低权限 ModelGateway APK，仅声明 `INTERNET`，并实现严格的公开配置
  schema v2、root/shell 调用方校验和原子持久化；尚未实现 HTTP client 或凭据保存。
- 未冻结的 protocol v2 AIDL 本地契约，覆盖会话 capability、一次性
  `CaptureGrant`、脱敏 perception 和有界 action plan，并提供便携 grant 状态机与
  API 34/35/36 DTO 校验。signature 权限保护的 Gateway Service 与包名/证书绑定的
  Java capability 已可编译；private native v2 service 和真实截图路径仍固定禁用，
  运行时仍为 v1。
- ModelGateway 公共 SDK 调试 APK 已在 API 34/35/36 Enforcing AVD 验证为独立
  `untrusted_app` UID，Manifest 只请求 `INTERNET`，公开配置导入成功。
- init、SELinux、属性和服务上下文集成脚手架。
- 主机单元测试和 Android NDK 桩交叉编译。

## 明确不实现

- 选择真实用户操作的策略或模型。`DecisionEngine` 是一个接口，生产环境的 AOSP 二进制默认不执行任何操作。
- 捕获 `FLAG_SECURE`、DRM 或受保护缓冲区。
- 在运行时向 `system_app` 注入 SELinux 规则，或直接访问 `/dev/uinput`。
- LSPosed Hook，或从第三方应用中提取私有数据。
- 声称 init 能在 50 ms 内重启进程。

## 主机构建

```sh
tools/run-tests.sh
build/host/global-agentd \
  --state /tmp/global-agent-demo.bin \
  --iterations 4 \
  --demo-action
```

主机可执行文件使用合成帧和仅记录日志的输入注入器。它可以在不向电脑或设备发送输入的情况下验证状态转换。

## Android NDK 桩构建

```sh
tools/build-android-stub.sh
tools/build-aidl-boundary-stub.sh
tools/validation-metadata.sh
```

该流程用于验证可移植核心能否针对 API 34/arm64 完成交叉编译。NDK 桩不包含 `libgui` 或隐藏的 Framework API，因为它们不属于 NDK。元数据命令会记录准确的本地提交和工具版本；如果存在可用的 ADB 设备，还会记录设备指纹、安全补丁级别（SPL）和 SELinux 状态。
其中 AIDL 边界命令还会重新生成 Java/NDK binding、运行 JVM DTO 校验，并编译
native Binder 服务逻辑；仅平台可用的服务注册仍必须由 Soong 验证。

## KernelSU 调试 WebUI

`tools/package-kernelsu.sh` 会创建一个面向 Android 14 arm64 的调试模块，其中包含离线 `webroot/index.html`。KernelSU 管理器可以显示模块和设备状态、运行可移植核心的合成冒烟测试、显示测试输出，并且只清理调试状态文件。设备工具标签页还提供需要明确手动操作的 `screencap` 和有界单次点击诊断。该软件包并非完整的 AOSP 屏幕捕获和输入产品，也不会捕获安全或 DRM 界面。

## 完整 AOSP 构建

将本仓库复制到 Android 14 源码树中，例如 `system_ext/global_agent`，然后添加：

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    GlobalAgentModelGateway \
    privapp-permissions-com.example.globalagent
```

通过产品的 `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` 或等效配置合并 `android/sepolicy/`。请针对设备对应的准确标签或 OEM 源码版本进行构建，因为 `libgui` 是私有平台 ABI。桥接服务还需要 Soong `platform_apis` 和目标 Framework 桩；公共 SDK 中的 `android.jar` 不包含所需的隐藏平台符号。

部署到设备前，请先阅读 [AOSP 集成](docs/AOSP_INTEGRATION.md)和[安全模型](docs/SECURITY.md)。具体步骤请参阅[操作手册](docs/OPERATIONS_MANUAL.md)。实现历史记录在[开发日志](docs/DEVELOPMENT_LOG.md)中，后续阶段的工作列在[路线图](docs/ROADMAP.md)中。项目根目录还提供[项目进度](PROJECT_PROGRESS.md)、[完整项目日志](PROJECT_LOG.md)和[项目问题](PROJECT_ISSUES.md)。主动触发、离线 STT、视觉状态和会话生命周期的边界记录在[触发与 STT 集成](docs/TRIGGER_STT_INTEGRATION.md)中。Android 14 电源键事件路径见 [POWER_KEY_AUDIT.md](docs/POWER_KEY_AUDIT.md)，离线语音和边缘光效的实现边界见 [STT_OVERLAY_ANDROID14.md](docs/STT_OVERLAY_ANDROID14.md)。更广泛的 [Android 14 工程手册](docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md)汇总了 Root/AOSP 备选开发路径和验收门；其中的备选方案是参考资料，并非当前生产配置全部启用。

## 运行时数据

平台二进制会写入 `/data/misc/global_agent/state.bin`。普通视觉观察结果最多每秒持久化一次，操作反馈则会立即提交。恢复时会忽略损坏或未写完的数据槽。

## 验证状态

主机测试和 NDK 交叉编译是本地检查门槛。由于当前工作区并非 Android 平台源码树，无法在本地编译 AOSP 私有头文件，因此仍需完成完整的 AOSP 构建和设备测试。

Android 14/15/16 的版本适配、strict 源码树门禁和 Advanced Protection 降级策略见[跨版本工程手册](docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md)。
外部模型 API 的低权限网关、凭据和响应 DTO 边界见[模型 API 网关说明](docs/MODEL_API_GATEWAY.md)；当前生产配置仍不发起网络请求。
当前 OpenClaw 风格的宿主机/Android 映射、公开配置 v2 与 capture grant
路线见 [OpenClaw API Agent 工程手册](docs/OPENCLAW_API_AGENT_ENGINEERING_MANUAL.md)。
