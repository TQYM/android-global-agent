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
- 平台任务元数据发布器，无需向守护进程授予宽泛的 dumpsys 权限。
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
```

该流程用于验证可移植核心能否针对 API 34/arm64 完成交叉编译。NDK 桩不包含 `libgui` 或隐藏的 Framework API，因为它们不属于 NDK。

## 完整 AOSP 构建

将本仓库复制到 Android 14 源码树中，例如 `system_ext/global_agent`，然后添加：

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    privapp-permissions-com.example.globalagent
```

通过产品的 `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` 或等效配置合并 `android/sepolicy/`。请针对设备对应的准确标签或 OEM 源码版本进行构建，因为 `libgui` 是私有平台 ABI。桥接服务还需要 Soong `platform_apis` 和目标 Framework 桩；公共 SDK 中的 `android.jar` 不包含所需的隐藏平台符号。

部署到设备前，请先阅读 [AOSP 集成](docs/AOSP_INTEGRATION.md)和[安全模型](docs/SECURITY.md)。具体步骤请参阅[操作手册](docs/OPERATIONS_MANUAL.md)。主动触发、离线 STT、视觉状态和会话生命周期的边界记录在[触发与 STT 集成](docs/TRIGGER_STT_INTEGRATION.md)中。Android 14 电源键事件路径见 [POWER_KEY_AUDIT.md](docs/POWER_KEY_AUDIT.md)，离线语音和边缘光效的实现边界见 [STT_OVERLAY_ANDROID14.md](docs/STT_OVERLAY_ANDROID14.md)。

## 运行时数据

平台二进制会写入 `/data/misc/global_agent/state.bin`。普通视觉观察结果最多每秒持久化一次，操作反馈则会立即提交。恢复时会忽略损坏或未写完的数据槽。

## 验证状态

主机测试和 NDK 交叉编译是本地检查门槛。由于当前工作区并非 Android 平台源码树，无法在本地编译 AOSP 私有头文件，因此仍需完成完整的 AOSP 构建和设备测试。
