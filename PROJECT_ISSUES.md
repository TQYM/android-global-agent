# 项目问题清单

更新时间：2026-07-19

## 阻塞问题

| ID | 优先级 | 状态 | 问题 | 影响 | 解除条件 |
| --- | --- | --- | --- | --- | --- |
| GA-001 | P0 | 阻塞 | 缺少目标 Android 14 AOSP/OEM 源码树 | 无法编译私有 `libgui`、平台 Binder 注册、framework hidden API 和产品 sepolicy | 提供 checkout 路径、branch/tag、fingerprint、SPL |
| GA-002 | P0 | 阻塞 | 缺少授权 userdebug/eng 或 Root 设备 | 无法验证截图格式/延迟、多点注入、Binder death、SELinux AVC 和 OEM 行为 | 提供设备与可用 ADB，记录 enforcing 状态 |
| GA-003 | P0 | 未验证 | 完整 Soong 产品构建尚未运行 | 本地 NDK 编译不覆盖 `binder_manager.h`、`libgui` 链接或平台 Java | 在目标树构建 `global-agentd`、`GlobalAgentBridge` 和 policy |
| GA-004 | P1 | 未实现 | 电源键长按触发只有审计文档 | 不能通过长按电源键进入会话 | 选择 framework handoff 或显式 UI，并基于目标 `PhoneWindowManager` 实现 |
| GA-005 | P1 | 本地已实现、待设备 | 用户可见的 bridge 会话入口尚未设备验收 | Activity 已提供明确开始、文本提交、取消、解锁/亮屏门禁和退后台取消 | 在目标 Soong 构建并验证锁屏、旋转、Binder death 和生命周期 |
| GA-006 | P1 | 待决策 | Vosk 版本、模型和许可证未确定 | 无法实现真正离线 STT，不能评估 APK 体积/RSS/功耗 | 确认语言、模型、ABI、hash、分发与许可证 |

## 功能缺口

| ID | 优先级 | 状态 | 问题 | 当前边界/下一步 |
| --- | --- | --- | --- | --- |
| GA-007 | P1 | 未实现 | microphone foreground service 未接入 | 需 `RECORD_AUDIO`、FGS 权限、通知、用户可见停止入口和 Android 14 后台限制测试 |
| GA-008 | P1 | 未实现 | 屏幕边缘流光未接入 | 需不可触摸 overlay 或 SystemUI 方案、状态绑定、fade 和 GPU/功耗测试 |
| GA-009 | P1 | 未实现 | 决策引擎仍为 `NoopDecision` | 先指定自有测试 App、动作 allowlist、置信度和停止条件，再做受限策略 |
| GA-010 | P1 | 部分实现 | 截屏只生成单帧视觉 hash | 尚无 RGBA Bitmap、ROI、OCR、连续 BufferQueue 或模型输入 |
| GA-011 | P1 | 部分实现 | 生产多点 bridge 未在设备运行 | 代码支持最多五指；需平台签名和目标设备验证缩放、旋转、取消与 display id |
| GA-012 | P2 | 未实现 | 感知->决策->执行->验证闭环不完整 | 真实策略与结果验证尚无，不能自动跨应用执行 |
| GA-013 | P2 | 部分实现 | 恢复策略只有基础 init backoff | 缺 SurfaceFlinger death link、两帧稳定门、完整 supervisor 与 P50/P95/P99 指标 |
| GA-014 | P2 | 未实现 | 当前没有模型 API 配置页 | 项目默认离线且 `NoopDecision`，WebUI 没有 API Key 输入；若引入云模型需单独密钥存储与网络安全设计 |

## KernelSU 与 WebUI 问题

| ID | 优先级 | 状态 | 问题 | 说明 |
| --- | --- | --- | --- | --- |
| GA-015 | P1 | 本地已修复、待真机 | KernelSU WebUI 打开后闪退 | v0.3+ 已补 `webroot/index.html` 并处理 bridge 缺失/异常；尚无物理 KernelSU WebView 回归 |
| GA-016 | P1 | 部分实现 | KernelSU 包不是完整系统 Agent | v0.4.0 只有便携 smoke、手动截图和单点点击；没有 AOSP daemon/平台 bridge/多点自动输入 |
| GA-017 | P2 | 待真机 | OEM `screencap`、旋转和多 display 未验证 | 安全/DRM surface 预期仍被系统遮蔽，不能把黑帧视为程序错误 |
| GA-018 | P2 | 已知限制 | WebUI 预览模式没有 `ksu.exec` | 浏览器本地预览只读是预期行为；命令只能在 KernelSU Manager bridge 中执行 |

## 验证与工程问题

| ID | 优先级 | 状态 | 问题 | 处理方式 |
| --- | --- | --- | --- | --- |
| GA-019 | P1 | 当前环境已恢复 | ADB 曾因 smartsocket 权限无法启动 | 现有 API 35 userdebug 模拟器已通过 Root/Enforcing stub 冒烟；仍不能替代 GA-002 的目标 Android 14 设备 |
| GA-020 | P1 | 已知边界 | 公共 Android SDK 无法编译完整 bridge | `ServiceManager`、隐藏 InputManager 常量和平台字段缺失；必须用 target Soong `platform_apis` |
| GA-021 | P2 | 待补 | 缺 Binder 服务设备级集成测试 | 需覆盖未授权 UID、stale revision、重连、超时、并发 callback 和 daemon restart |
| GA-022 | P2 | 待补 | 缺性能/功耗数据 | 截屏、map、注入、STT、GPU 均无目标设备 P50/P95/P99 与耗电结果 |
| GA-023 | P2 | 本次发布已处理 | 多轮源码与本地 ZIP 曾混在工作区 | 源码/文档进入独立 GitHub 分支；生成 ZIP 保留本地并由 `.gitignore` 排除 |

## 安全与不可突破边界

以下不是待修复 bug，而是必须保留的系统边界：

- 不捕获 `FLAG_SECURE`、DRM 或 protected buffer。
- 不读取 TEE/StrongBox 密钥，也不声称可解密任意金融 App 通信。
- 不通过 `setenforce 0`、宽泛 `/dev/uinput` 规则、raw Binder transaction 或反射
  绕过平台权限。
- Root、解锁 bootloader、平台修改和 hook 可被检测，不能保证绕过 Play Protect。
- 不对未授权第三方 App 提取私有 ViewHolder、Bundle、Parcelable 或内部数据。
- 未获得目标设备证据前，不声称 Android 13/14 OEM 私有接口兼容。

## 建议处理顺序

1. 解决 GA-001 至 GA-003，完成目标 AOSP Soong/enforcing 基线。
2. 在目标设备验证 GA-005 的显式 UI；仅在产品需要时再实现 GA-004 电源键入口。
3. 确认 GA-006 后实现 GA-007，并先证明取消、超时和麦克风释放。
4. 完成 GA-008 和设备状态回调，再开始任何真实决策。
5. 只对指定自有 App 实现 GA-009/GA-012，并保留默认 `NoopDecision`。
6. 用设备数据关闭 GA-010、GA-011、GA-013、GA-021 和 GA-022。
