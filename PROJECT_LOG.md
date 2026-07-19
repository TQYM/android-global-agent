# 完整项目日志

更新时间：2026-07-19

本文提供项目从便携核心到当前会话 AIDL 控制面的完整迭代索引。每次命令结果、
设备 fingerprint、工具版本和限制声明的权威记录位于 `docs/VALIDATION.md`；面向
开发阶段的中文摘要位于 `docs/DEVELOPMENT_LOG.md`。

## Iteration 1：NDK 可移植性

- 修复 Android NDK libc++ 下 mmap commit marker 的原子操作。
- 主机 ASan/UBSan、CTest 和 API 34 arm64 stub 构建通过。
- stub 改为静态 libc++，形成单文件调试二进制。

## Iteration 2：结构化 AIDL

- 使用 Android build-tools 35 生成 structured Java/NDK AIDL。
- Java parcelable 通过 `android-35/android.jar` 编译。
- daemon 不再轮询宽泛 dumpsys，平台 bridge 发布有界任务/窗口元数据。

## Iteration 3：API 35 Emulator 恢复测试

- 在 Enforcing API 35 arm64 userdebug 模拟器运行 API 34 stub。
- 验证五帧手势、状态持久化、`SIGKILL` 后恢复和 generation 完整性。
- 该结果不覆盖 Android 14 私有 `libgui` 或平台 Java API。

## Iteration 4：存储与输入加固

- 状态文件增加单写者 `flock`、`O_NOFOLLOW`、CRC 回退和图上限测试。
- Java 注入改为异步节拍队列，增加取消路径。
- pending action 反馈结算不再阻塞下一次 200 ms 感知步骤。

## Iteration 5：AOSP 截屏边界复核

- 按 Android 14 AOSP 头核对 ScreenshotClient、ScreenCaptureResults、FenceResult
  和 GraphicBuffer。
- 修正为 root 可授权的 `captureDisplay(DisplayId, listener)` 路径。
- 删除 `/dev/uinput` relabel 和不必要的 bridge `coredomain` 设想。

## Iteration 6：权限与标签最小化

- privapp allowlist 只保留 `REAL_GET_TASKS`；`INJECT_EVENTS` 依赖平台签名。
- daemon 移除无用 input supplementary group。
- seapp 规则收紧到指定 platform privapp，KernelSU helper 不修改 `/dev/uinput`。

## Iteration 7：SurfaceFlinger 权限路径审计

- 核对 `captureDisplayById` 对 root/graphics/system/shell 的调用边界。
- 保持 secure/protected capture 关闭，加入错误 overload 静态回归检查。
- 再次完成主机、NDK 与模拟器 smoke/recovery。

## Iteration 8：可取消输入节拍

- Java 帧等待改为 4 ms 上限的可取消循环。
- 每帧注入前二次检查取消，缩短 daemon/SystemUI 断线后的停止延迟。

## Iteration 9：平台 stub 边界

- 证明公共 `android.jar` 缺失 bridge 所需隐藏平台符号。
- 明确必须使用 Soong `platform_apis: true` 和目标 framework stubs，不用反射绕过。

## Iteration 10：VM 重试与环境阻塞

- 当前沙箱内 ADB server 因 socket 权限无法启动，未获得新的 VM 结果。
- 主机 fallback smoke test 通过，但明确不作为设备证据。

## Iteration 11：触发、STT 与视觉反馈审计

- 记录 Android 14 电源键 InputReader -> InputDispatcher -> policy 路径及版本漂移。
- 设计 Vosk/AudioRecord microphone FGS、有界 PCM 队列和 RuntimeShader overlay。
- 未加入隐藏录音、LSPosed、uinput 绕过或生产自动决策。

## Iteration 12：有界 SessionContext

- 实现显式确认、锁屏拒绝、严格 UTF-8、递增序号、4096 字节 transcript 和 15 秒
  生命周期。
- 取消、过期和析构时擦除 transcript；补充 host 单元测试。

## Iteration 13：操作手册

- 新增 host/NDK/emulator/AOSP 构建、运行、回滚、排障和安全验收手册。
- 该轮仅文档变更，不改变运行行为或权限。

## Iteration 14：开发日志与路线图

- 建立 `docs/DEVELOPMENT_LOG.md` 和 `docs/ROADMAP.md`。
- 明确 P0-P5 依赖、完成标准、非目标和需要用户提供的产品输入。

## Iteration 15：P0 契约与取消加固

- 统一 256 帧、五指、2 秒手势限制；电源键触发单独限制为 2-10 秒。
- 感知失败时取消正在异步发送的手势。
- 增加边界、超限、deadline 和失败路径测试。

## Iteration 16：单帧 API 命名与验证元数据

- 明确 `single_frame_visual_hash`，避免将单帧 hash 描述成连续视觉模型。
- 提取纯 JVM GestureValidator，八项测试发现并修复空帧异常和负时间误接受。
- 新增可复现的工具链、commit、设备元数据记录脚本。

## Iteration 17：KernelSU 调试包

- 增加 KernelSU/Magisk 通用模块结构、arm64/API 34 安装检查和手动 action。
- 包内只有便携核心 smoke stub，不开机常驻、不包含宽泛 sepolicy。

## Iteration 18：KernelSU WebUI 闪退修复

- 补齐规范要求的 `webroot/index.html`、完全离线 CSS/JS 和固定版 Lucide 图标。
- 缺失 ksu bridge、超时、命令失败和 Promise rejection 均显示错误状态，不关闭页面。
- 桌面与 390 x 844 模拟浏览器 QA 通过；物理 KernelSU WebView 仍待回归。

## Iteration 19：真实截图与单点输入诊断

- WebUI 增加显式 stock `screencap -p` 与 `input touchscreen tap`。
- 坐标按 `wm size` 限制；截图使用私有临时文件，读入 Blob 后立即删除。
- 生成 v0.4.0 包，SHA-256 为
  `4bb7fc975b69a6614485917f9c9f979ae80a9c78bc076227a6e3a6b2d985183f`。

## Iteration 20：会话 AIDL 控制面

- 新增 SessionTrigger、TranscriptUpdate、SessionStatus 和协议版本 1。
- native Binder 服务增加 UID 鉴权、revision、触发、transcript、状态跳转、取消、
  查询、超时和退出清理。
- final transcript 自动进入 `THINKING`，原始文本不进入 StateStore。
- Java `AgentSessionClient` 处理 transcript 序号、同步返回/oneway 回调竞态、取消、
  Binder death 和重连 revision 基线。
- AOSP-only 服务注册与可用 NDK 编译的服务逻辑拆分；新增 API 34 arm64 AIDL
  边界构建脚本。

## Iteration 21：显式会话 Activity

- bridge 增加 Launcher Activity，由用户明确点击开始会话，并在锁屏、屏幕未亮或
  native 服务断开时禁用入口。
- Activity 可提交一条有界 final transcript、显示会话状态并随时取消；离开前台时
  会排队取消，包括 begin 请求尚未返回的竞态。
- `AgentSessionClient` 支持多监听器，并用 `null` 明确发布断开状态，避免 UI 把
  Binder death 误判为已连接。
- 新增纯 Java `SessionEntryPolicy` 与 16 项 JVM 检查；没有新增麦克风、overlay、
  输入权限或自动决策。
- 全量本地门禁通过；API 35 userdebug/Enforcing 模拟器运行 API 34 arm64 stub，
  `SIGKILL` 后恢复报告 `generation=34 nodes=2 edges=1`。

## 当前验证快照

- Host：Darwin 25.6.0 arm64。
- CMake：3.22.1；Ninja：1.10.2。
- Java：javac 21.0.2；Android build-tools：35.0.0。
- NDK：26.1.10909125；目标：android-34/arm64-v8a。
- 设备：API 35 arm64 userdebug emulator、Enforcing；目标 Android 14 设备仍缺失。
- 当前生产决策：`NoopDecision`。

## 日志维护约定

后续每轮实现必须同步更新：本文件、`PROJECT_PROGRESS.md`、`PROJECT_ISSUES.md`、
`docs/DEVELOPMENT_LOG.md`、`docs/VALIDATION.md` 和 `docs/ROADMAP.md`。只有完成目标
源码编译和设备测试后，才能使用“设备可用”或“生产通过”的表述。
