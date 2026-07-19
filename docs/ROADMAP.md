# 开发方向与路线图

## 目标

项目目标是为自有设备、自编译 AOSP 镜像或明确授权的测试环境提供一个可审计的
Android 14 Agent 边界实现。路线图优先保证可停止、可恢复、可解释和最小权限，
不以绕过系统安全确认、Play Protect、`FLAG_SECURE`、DRM、TEE、第三方沙箱或
反篡改为目标。

## 当前阶段

**阶段：P0 本地完成，P1 设备集成阻塞。**

便携核心和 bridge 骨架已有本地验证。手势限制、失败路径测试、单帧 API 命名和
验证元数据已在 P0 收敛；P3 的会话 AIDL 控制面和显式 bridge Activity 也已提前
完成本地实现，但没有麦克风。下一步不是直接加入“会自己操作 App”的模型，而是
取得目标 AOSP/设备输入；没有目标源码和设备时，P1 之后的运行能力不能可靠完成。

## 阶段路线

### P0：契约和可重复验证（本地完成）

目标：让代码、AIDL、测试和文档对同一组限制负责。

工作项：

- 统一手势最大时长（2 秒；电源键按压时长另有 2--10 秒触发范围）；
- 为 `AgentLoop`、`SessionContext`、bridge validator 补齐失败路径和边界测试；
- 为每次验证记录工具版本、commit、设备 fingerprint/SPL（若有设备）；
- 将“单帧视觉 hash”和“真实连续帧/模型”在 API 命名和文档中明确区分。

完成标准：

- `tools/check-project.sh`、`tools/run-tests.sh`、`tools/build-android-stub.sh` 全部
  通过；
- AIDL 与 Java/native 对限制值没有漂移；
- 文档不再把设计骨架描述为已接入能力。

### P1：目标 AOSP 集成（当前阻塞）

目标：在匹配的 Android 14 AOSP/OEM tree 编译并运行最小产品包。

必须输入：

- AOSP/OEM checkout 路径、branch/tag 和设备 fingerprint/SPL；
- Soong framework stubs、平台签名和授权 userdebug/eng 设备；
- 可用的 `adb`、`adb root`（若产品允许）和 enforcing SELinux 环境。

工作项：

- 编译 `global-agentd` 的私有 `libgui`/`libbinder_ndk` 依赖；
- 编译 platform `GlobalAgentBridge` 和生成的 hidden API bindings；
- 验证 service/property/file/seapp contexts 及 privapp allowlist；
- 记录正常屏幕、secure/DRM 屏幕、SurfaceFlinger/bridge/daemon 重启结果。

完成标准：

- 完整 Soong 构建通过；
- enforcing 下没有未解释的 Agent AVC；
- secure/protected buffer 不可读；
- daemon、bridge、SurfaceFlinger 重启后回到 IDLE 且不继续注入。

### P2：感知链升级（依赖 P1）

目标：从单帧 hash 变成可测量的受限视觉输入。

顺序：

1. 先完善单帧 capture 的 display、rotation、stride、format 和 deadline 遥测；
2. 再评估常驻 Virtual Display + BufferQueue consumer；
3. 只在有明确自有 App 场景和性能数据后加入 ROI/模型推理；
4. 任何模型都必须输出置信度和拒绝原因，不能默认产生动作。

完成标准：

- 有 P50/P95/P99 的 capture/map/inference 数据；
- 单步预算超时会跳过动作，不在超时后继续注入；
- secure、protected、黑帧和格式不支持都有可观测失败状态；
- 连续帧资源有背压、释放和 SurfaceFlinger death 处理。

### P3：明确授权的会话输入（依赖 P1，产品选择必需）

目标：实现一个用户可见、可取消、默认关闭的触发会话。

可选入口：

- 匹配 AOSP framework 的 power-key handoff；或
- bridge 应用中的显式用户操作。

工作项：

- [本地完成] 将 `SessionContext` 接到带 caller 校验的窄 AIDL；
- [本地完成] 增加 `submitTranscript`/session-state DTO、协议版本和 stale sequence
  拒绝，并实现 bridge 侧重连/乱序处理；
- [本地完成] 增加显式 Launcher Activity、解锁/亮屏门禁、文本 final transcript、
  用户取消和退后台自动取消；
- 只在解锁、用户确认、麦克风权限和 FGS 条件全部满足时开始会话；
- Binder death、取消、超时后回到 IDLE 并清除 transcript/visual state。

完成标准：

- 没有静默麦克风启动；
- 锁屏、全局麦克风关闭、AppOps 拒绝都安全失败；
- StateStore 不包含原始文本或 PCM；
- 所有状态变化都有用户可见或可审计结果。

### P4：离线 STT 和视觉反馈（依赖 P3）

目标：提供可停止的语音和状态提示，不改变权限边界。

工作项：

- bridge microphone foreground service + `AudioRecord` 有界队列；
- 锁定 Vosk/其他离线引擎的版本、ABI、模型 hash 和许可证；
- 以 `TYPE_APPLICATION_OVERLAY` 或自有 SystemUI 集成绘制不可触摸边缘状态；
- API 33+ 使用 RuntimeShader，低版本提供简单回退；
- measure audio、识别、GPU 和电量成本。

完成标准：

- FGS 通知、麦克风指示器和停止入口始终可见；
- 取消/静音/通话占用/切后台时释放 AudioRecord 和模型；
- overlay 不拦截输入、不伪装 trusted/status-bar 层；
- P50/P95 延迟和资源预算有设备实测记录。

### P5：受限决策策略（最后实现）

目标：只针对一个明确授权的自有 App 测试流程提供可解释动作。

前置条件：

- 已有 P1-P4 的设备证据；
- 用户明确指定目标 App、测试流程、允许的动作和停止条件；
- 决策输入是 allowlist DTO/视觉状态，不读取第三方私有对象；
- 每个动作都有置信度阈值、超时、取消和 no-op 分支。

禁止把这一步扩展成通用跨应用自动化、隐身、风控规避或安全确认绕过。

完成标准：

- 默认构建仍然是 `NoopDecision`；
- 策略只在显式 feature flag/测试 flavor 启用；
- 低置信度、secure surface、锁屏、权限错误和状态未知时拒绝动作；
- 自有测试 App 有正向、负向、取消、重启和回滚测试。

## 当前不安排的工作

以下内容不属于本项目的开发目标：

- 给 `system_app` 或 `agentd` 增加宽泛 `/dev/uinput` 权限；
- 通过 Magisk、LSPosed、反射或 raw Binder 绕过系统权限；
- 捕获 `FLAG_SECURE`、DRM、protected buffer 或读取 TEE/StrongBox 密钥；
- 隐藏录音、隐藏 overlay、绕过锁屏/关机确认或 Play Protect；
- 对未授权第三方 App 提取 ViewHolder、Bundle、Parcelable 或私有数据。

## 需要用户确认的决策

在 P1 或 P3 开始前，需要明确：

1. 目标 AOSP/OEM 源码路径、分支和设备 fingerprint/SPL；
2. 首个自有/授权测试 App 和要验证的用户流程；
3. 触发入口选择 power-key framework 集成还是显式 bridge UI；
4. 是否允许引入 Vosk 等第三方库，以及模型许可证和 ABI 范围。

在这些输入缺失时，继续扩大权限或添加真实决策逻辑是不合适的；只继续完善可在
本地真实验证的契约、测试和编译边界。

## 每轮开发的完成定义

每个 roadmap 变更必须同时更新：

- 代码和对应测试；
- [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) 的阶段记录；
- 本文件的状态和完成标准；
- [VALIDATION.md](VALIDATION.md) 的命令、结果和未验证条件。
