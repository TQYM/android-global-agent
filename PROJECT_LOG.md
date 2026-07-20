# 完整项目日志

更新时间：2026-07-20

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

## Iteration 22：Android 14/15/16 兼容性契约

- 将工程手册从 API 34 基线扩展为 API 34/35/36 适配规范，明确 SurfaceControl、
  Power policy、InputManager、SELinux 和 Android 16 Advanced Protection 的逐版本
  检查与安全降级边界。
- 新增 `tools/check-api-compat.sh`：默认盘点 API 34/35/36 SDK，strict 模式要求
  `AOSP_TREE_34`、`AOSP_TREE_35`、`AOSP_TREE_36` 并检查每棵树的 framework/native/
  sepolicy 入口。
- 将兼容性检查接入 `tools/check-project.sh`，并让 `validation-metadata.sh` 记录三
  个 SDK 是否安装。
- 当前主机结果：API 35 SDK 已安装，API 34/36 缺失；默认门禁通过，strict 门禁保持
  阻塞。没有声称 API 35/36 私有 ABI 或设备兼容已完成。
- 后续已安装 API 34/36 SDK 与 Google APIs arm64 镜像；新增三版本 Java/AIDL
  编译矩阵，API 34/35/36 均通过 Activity、DTO 和策略测试。exact AOSP tree/Soong
  验证仍保持 strict 阻塞。
- 新建 API 34/36 arm64 AVD；Root/remount/Enforcing、API 34-minSdk stub 冒烟和
  `SIGKILL` 恢复通过，补齐三版本便携层设备证据。
- 增加 provider-neutral 模型网关策略：仅 HTTPS、只接受 credential alias、响应
  限定为 session/revision 绑定的有界 intent DTO，不允许远程坐标/手势直达输入桥。
  32 项策略测试在 API 34/35/36 Java 矩阵通过；实际 HTTP/Keystore/provider 未接入。

## Iteration 23：低权限 ModelGateway 公开配置边界

- 新增独立 `GlobalAgentModelGateway` APK，使用独立 app UID，Manifest 仅声明
  `INTERNET`，禁止明文流量并只信任 system CA；高权限 bridge 仍无
  `INTERNET`。
- 实现公开配置 schema v2 严格解析：有界 JSON 深度/大小，拒绝重复键、
  未知字段、原始 secret 字段、非 HTTPS endpoint、非 `keystore://` 引用、
  未知 tool 和超限预算；当前阶段强制 `dryRun=true`。
- `PublicConfigProvider` 只允许 root/shell 使用固定 method 和唯一
  `config_b64` extra，严格 Base64/UTF-8 校验后使用 flush/fsync + `AtomicFile`
  原子替换；该路径不导入 API Key。
- endpoint/intent、schema、call envelope 和 importer 正反用例已纳入 API
  34/35/36 Java 矩阵。主机 ASan/UBSan、NDK stub、AIDL boundary 和静态门
  通过。
- DeepSeek `deepseek-chat` 四轮复核后返回 `pass`，无 blocker/high/medium、
  缺失测试或待确认问题。
- 控制协议仍是 v1，且没有 HTTP client、Keystore 凭据 UI、CaptureGrant、
  截图入站或输入执行；生产继续使用 `NoopDecision`。

## Iteration 24：protocol v2 与一次性 CaptureGrant 本地契约

- 新增 25 个 v2 AIDL 文件，覆盖会话 handle/status、Bridge capability、private
  native service、Gateway callback、CaptureGrant、脱敏 perception、ActionPlan、
  PlanValidation 和 ExecutionGrant；接口保持 `unstable`，未注册运行时服务。
- 新增便携 `CaptureGrantStore`，仅保存 32 字节 token 摘要，绑定 service instance、
  UID、capability、session、revision、focus、display、crop、TTL 和脱敏策略；合法
  grant 在 I/O 前锁内移除，16 线程并发重放测试保证仅一次成功。
- ModelGateway 增加 v2 DTO fail-closed 校验，限制 3 秒 TTL、2 MiB 图像、8 个动作、
  OCR/脱敏数组、UTF-8 文本、归一化坐标、时长、摘要和 secure-content 排除标志。
- Java/NDK AIDL 生成、API 34/35/36 Java 矩阵、host ASan/UBSan、API 34 arm64 NDK
  stub、AIDL boundary 和静态项目门通过。v2 Binder、真实截图、HTTP、Keystore 与
  输入执行仍未实现，运行时继续使用 v1 与 `NoopDecision`。

## Iteration 25：v2 capability 与三版 Gateway AVD

- Bridge 新增 per-session `V2SessionCapability`，Gateway 只能调用绑定 session 的
  capture、plan validation、取消和状态查询；start、focus、grant approval、input
  等 Bridge-only 方法稳定返回 `SecurityException`。
- capability factory 在创建前校验 Gateway 实际 UID、包名和单一当前签名证书
  SHA-256；token 在跨线程/嵌套 Binder 前复制，调用 native 前后成对 clear/restore
  calling identity。
- Gateway APK 新增 signature 权限保护的 `IModelGateway` Service。当前只接受
  text-only v2 request，Provider/Keystore 尚未完成时返回 `STATUS_DISABLED`，不发起
  网络请求，也不调用输入。
- private native v2 service 的全部方法签名已纳入 API 34 arm64 NDK `-Werror`
  编译；在 exact-tree calling SID 验证与注册完成前，固定 fail-closed。
- 新增无 Gradle 的公共 SDK Gateway APK 构建和 AVD 验证脚本。API 34/35/36
  Enforcing AVD 均验证独立非 system UID、`untrusted_app` 域、Manifest 只请求
  `INTERNET`、配置导入/原子落盘成功和未知方法拒绝。
- API 35 无权限 probe 的 Gateway bind 未成功；畸形配置未覆盖已验证配置。
  capability 增加 native Binder death 失效，证书 authorizer 支持显式批准的轮换摘要。
- DeepSeek `deepseek-chat` 第三轮独立复核最终返回 `pass`。

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
