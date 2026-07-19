# 开发日志

本文是项目的工程进度摘要。精确的命令输出、设备指纹和历史验证细节以
[VALIDATION.md](VALIDATION.md) 为准；本文件记录每一阶段做了什么、为什么
这样做，以及仍然没有证明什么。

## 当前快照

更新时间：2026-07-19

当前交付物是一个安全边界实现骨架，不是已经刷入设备的完整产品：

| 区域 | 状态 | 说明 |
| --- | --- | --- |
| 便携 C++ 核心 | 可验证 | 200 ms 单步预算、状态图、双槽 CRC 状态存储、手势校验、Bezier 采样和 bounded subprocess。 |
| AOSP 截屏适配器 | 集成骨架 | 使用 Android 14 私有 `ScreenshotClient::captureDisplay(DisplayId, ...)`；当前是单帧采样和视觉 hash。 |
| 平台输入桥 | 集成骨架 | 结构化 AIDL、平台签名 Java bridge、五指以内多指状态机、取消和异步节拍。 |
| 窗口/设置元数据 | 部分实现 | bridge 发布受限顶层任务、进程、display、bounds、rotation，并监听固定 Settings allowlist。 |
| 会话控制面 | 本地可验证 | `SessionContext` 已接入带 UID 校验和 revision 的 AIDL；Java client 处理触发、transcript、取消、重连和状态回调。 |
| 真实决策策略 | 未实现 | AOSP 入口仍使用 `NoopDecision`，不会自动执行跨应用动作。 |
| 电源键、STT、overlay | 未实现 | 目前是设计/审计文档和集成边界。 |
| 完整 AOSP/设备验证 | 未完成 | 需要目标源码、Soong、平台签名、SELinux enforcing 和授权设备。 |

## 阶段记录

### 阶段 1：便携核心

- 建立 C++20 Agent loop，并将单步预算固定为 200 ms 的失败上限。
- 建立有上限的 `StateGraph` 和显式二进制序列化。
- 建立双槽 mmap 状态存储：generation、CRC、单写者 `flock`、`O_NOFOLLOW`、
  0600 权限和损坏槽回退。
- 加入确定性三次 Bezier 弧长采样、手势状态机和有界子进程执行器。
- 主机 ASan/UBSan、CTest 和合成输入测试通过。

### 阶段 2：Android stub 和 AIDL 边界

- 使用 API 34 arm64 NDK stub 验证便携核心可交叉编译。
- 结构化 AIDL 同时生成 Java 和 NDK 绑定，并限制帧数、指针数、坐标和时间。
- 将输入注入放入平台签名 Java bridge，不通过反射、raw Binder transaction 或
  `/dev/uinput` 规则绕过权限。
- bridge 增加异步、可取消、按时间戳发送的多指 MotionEvent 队列。
- bridge 只发布受限的顶层任务和 display 元数据，不把宽泛 `dumpsys` 权限交给
  native daemon。

### 阶段 3：AOSP 私有接口和权限路径审计

- 按 Android 14 AOSP 源码核对 `ScreenshotClient`、`ScreenCaptureResults`、
  `FenceResult` 和 `GraphicBuffer` 的字段/调用路径。
- 选择 `DisplayId` capture 路径，保持 `captureSecureLayers = false`，并为 Binder
  callback、fence 和 buffer map 设置剩余 deadline。
- 移除不必要的 `input`/`coredomain`/uinput 权限设想，保留独立 `agentd` 与
  `global_agent_bridge` SELinux 域和最小 privapp allowlist。
- 完成 init、service/property/file/seapp context 集成骨架。

### 阶段 4：恢复、取消和安全边界

- 增加 pending-action feedback settlement，使长手势不阻塞下一个 200 ms 感知步。
- 输入失败、bridge death 或 daemon 停止时发送 `ACTION_CANCEL`，并清理活动手势。
- 增加 `SessionContext`，拒绝锁屏/未确认触发、重复序号、非法 UTF-8 和超限文本；
  取消/过期/Binder death 时清空临时内容。
- 添加 power-key、离线 STT、microphone FGS、RuntimeShader overlay 的事实核查，
  但没有把这些设计误写成生产实现。

### 阶段 5：文档和发布

- 增加 AOSP 集成、安全模型、触发/STT、power-key audit 和操作手册。
- 建立独立 AI 审批的阻塞记录；该记录不是代码通过证明。
- 当前代码已发布到公开仓库 `TQYM/android-global-agent`，但公开仓库不等于已通过
  目标设备验收。

### 阶段 6：P0 契约和取消路径加固

- 将 native 手势限制提炼为共享常量（256 帧、五指、2 秒），并让 AOSP bridge
  使用同一组边界；Java 端现在对每个帧的 `actionIndex` 做统一范围校验。
- 截屏/感知失败发生在异步手势反馈期间时，AgentLoop 主动请求取消并清除待结算
  动作，避免输入桥继续发送旧手势。
- 增加 2 秒精确边界、超限拒绝、感知失败取消和电源键 10 秒边界测试。
- 安全/权限边界未改变；未增加 microphone、uinput、secure capture 或持久化字段。
- 本地验证命令和结果记录在 [VALIDATION.md](VALIDATION.md) 的 Iteration 15；
  目标 AOSP 私有 `libgui`、平台签名 bridge 和 enforcing 设备条件仍未验证。

### 阶段 7：单帧感知命名收敛

- 将 `Perception` 输入字段命名为 `single_frame_visual_hash`，并将 AOSP 适配器命名为
  `AospSingleFrameCapture`；状态图的 `visual_hash` 仍表示持久化状态键。
- 该变更只澄清 API 语义，不引入连续帧、Virtual Display、ROI 或模型推理。
- 将 Java 手势校验抽成可在主机 JVM 执行的 `GestureValidator`，覆盖 2 秒边界、
  非法 action index、重复指针、非有限坐标、空边界帧和负时间；空帧异常与
  `-1 ms` 误接受已修复。
- 增加注入拒绝、注入前 deadline 到期、无效状态跳转和取消后文本清理测试。
- 新增 `tools/validation-metadata.sh`，统一记录 commit、dirty 状态、工具版本以及
  可用设备的 fingerprint/SPL/SELinux；本轮设备不可用。
- P0 本地完成，后续开发进入需要目标 AOSP/OEM tree 和授权设备的 P1 阻塞点。

### 阶段 8：KernelSU 调试包

- 将现有部署 helper 补齐为 KernelSU/Magisk 通用模块结构，增加 arm64/API 检查、
  安装权限设置和 Manager `action.sh` 手动 smoke-test 入口。
- 模块只包含 API 34 arm64 便携核心 stub，不开机常驻，不包含平台 bridge、真实
  截屏/输入注入或通用 `sepolicy.rule`。
- 新增 `tools/package-kernelsu.sh`，构建结果输出到桌面并通过 `unzip -t`、权限、
  ELF 架构和 SHA-256 检查。

### 阶段 9：KernelSU WebUI

- 修复 KernelSU Manager 打开 WebUI 后立即退出：旧包没有规范要求的
  `webroot/index.html`，现在提供完全离线的 HTML/CSS/JS 入口。
- WebUI 支持模块/设备状态、synthetic smoke test、运行日志、日志复制和调试状态
  清理；没有 `ksu` Bridge 时安全降级为只读预览，不抛异常或执行命令。
- 使用固定版本 Lucide 图标并保留许可证；WebUI 不加载 CDN、字体或远程脚本。
- 按官方要求停止对整个模块递归设置权限，避免覆盖 KernelSU 为 `webroot`
  配置的权限和 SELinux context。
- 浏览器验证覆盖桌面、390px 移动视口、标签切换、无 Bridge 降级和模拟 Bridge
  成功路径；无控制台错误或横向溢出。

### 阶段 10：显式设备调试工具

- KernelSU WebUI 增加“设备工具”页：用户按键后调用 Android stock
  `screencap -p`，并提供整数 X/Y 坐标的单点 `input touchscreen tap`。
- 截图先写入模块私有 runtime 目录，加载为内存 Blob 后立即删除；关闭页面和移除
  预览时也尝试清理。安全/DRM surface 仍由 Android 屏蔽。
- 输入坐标根据 `wm size` 限制到当前 display 边界；尺寸不可用时仍有 100000 的
  硬上限。命令只由固定模板和已验证整数构成。
- 该变更扩大了可选 KernelSU 调试模块的显式 root-shell 能力，但不改变生产 AOSP
  daemon/平台签名 bridge，也没有增加自动决策、后台注入或 sepolicy 绕过。
- 浏览器模拟验证覆盖 1080x2400 尺寸、越界拒绝、合法点击、截图加载、临时文件
  清理、预览移除和移动布局；目标设备 OEM 行为仍待实测。

### 阶段 11：会话 AIDL 控制面

- 新增 `SessionTrigger`、`TranscriptUpdate`、`SessionStatus` DTO，服务端提供触发、
  transcript、状态跳转、取消和查询接口，并向 bridge 发布单调 revision 回调。
- native 服务校验调用 UID、锁定首个受 SELinux 约束的 bridge UID、限制 DTO，且
  final transcript 自动进入 `THINKING`；过期和退出都会清除临时状态。
- Java `AgentSessionClient` 统一处理显式/电源触发、递增 transcript 序号、同步返回
  与 oneway 回调乱序、Binder death 基线重置及取消。
- 将平台专用的 Binder 服务注册拆出，使会话服务主体可用 API 34 arm64 NDK 和生成
  的 AIDL 头做严格编译；`binder_manager.h` 注册部分仍必须在 AOSP Soong 中验证。
- 没有新增麦克风、overlay、uinput、secure capture 或持久化权限；本轮仍未在目标
  AOSP tree 或设备上运行。

### 阶段 12：显式用户会话入口

- 新增 `AgentSessionActivity` 作为 Launcher 入口，只有用户在可见前台点击后才调用
  `beginExplicitSession`；锁屏、屏幕非 interactive、服务断开或已有会话时按钮禁用。
- 页面支持提交一条最多 4096 UTF-8 字节的 final transcript、查看状态和取消；离开
  前台时排队取消活动会话，也覆盖 begin 请求与 `onStop()` 并发的情况。
- `AgentSessionClient` 改为多监听器发布，并以空状态通知 Binder 断开；Activity 和
  Application 日志不会把断线后的占位 IDLE 当成真实连接。
- `SessionEntryPolicy` 的 16 项 JVM 测试覆盖连接、锁屏、亮屏、活动状态、请求竞态、
  transcript 字节边界和取消条件。
- 权限边界未改变；没有接入 microphone FGS、STT、overlay、电源键或真实决策。
  Activity 仍需目标 Soong/platform 签名构建和设备生命周期测试。
- 主机、API 34 arm64 stub、AIDL 边界、静态检查和 diff 检查全部通过；当前 API 35
  userdebug/Enforcing 模拟器上的 stub 冒烟与 `SIGKILL` 恢复通过，但不覆盖 Activity。

### 阶段 13：Android 14/15/16 兼容性契约

- 工程手册增加 API 34/35/36 的 AVD、平台签名、Surface capture、InputManager、
  Power policy、SELinux、非 SDK API 和 Advanced Protection 适配规则。
- 新增 `tools/check-api-compat.sh` 并接入项目静态门禁；默认模式报告 SDK 状态，
  `--strict` 需要三棵 exact AOSP tree 并检查各版本私有源码入口。
- 本轮未声称旧 `createDisplay` overload、API 34 `services.jar`、policy 或 native
  daemon 可直接复用到 API 35/36；缺少目标树时统一 fail-closed。
- 安装 API 34/36 SDK 后新增 `tools/check-java-api-matrix.sh`，同一组 AIDL、显式
  Activity 和纯 Java 策略测试分别对 API 34、35、36 `android.jar` 编译并通过。
  该结果只证明公共 API 源码兼容，不替代 private platform ABI/Soong 验证。
- 创建 API 34/36 Google APIs arm64 AVD；两版 Root、remount、Enforcing、portable
  stub 冒烟和 `SIGKILL` 恢复通过。API 35 已有同类恢复证据，三版便携层运行矩阵
  形成，但 platform APK、私有截屏/输入和产品 sepolicy 仍未覆盖。
- 新增 `ModelGatewayPolicy` 和独立低权限网关边界文档。策略拒绝 HTTP、URL 内凭据、
  非 443 endpoint、超限字段、stale/非法响应和无确认的不可逆 intent；32 项测试
  在 API 34/35/36 编译矩阵通过。没有向高权限 bridge 增加 `INTERNET` 或保存 key。

### 阶段 14：ModelGateway 公开配置导入

- 将网关从纯策略类扩展为独立 `GlobalAgentModelGateway` APK。该 APK 在脚手架
  中使用非 platform 的标准 `shared` 证书（产品可换专用证书），只声明
  `INTERNET`，并以 network security config 禁止明文流量。
- 实现可在主机 JVM 运行的严格 JSON/parser/schema，拒绝重复/未知字段、
  secret 字段、路径歧义、越界整数、非 Keystore alias、重复 package/tool 与
  保留截图；当前只接受 `runtime=openclaw-host` 和 `dryRun=true`。
- 实现 root/shell-only `PublicConfigProvider.call()` 信封校验与严格
  Base64/UTF-8 解码，通过 flush/fsync + `AtomicFile` 存储已验证公开配置。
  Provider 不支持 query/insert/update/delete，也不接收凭据值。
- API 34/35/36 公共 SDK 编译和所有 endpoint/intent/schema/call/importer JVM 测试通过；
  host ASan/UBSan、API 34 arm64 NDK stub、AIDL boundary 和项目静态门通过。
- DeepSeek `deepseek-chat` 第四轮复核最终 `pass`。该结果不替代 Soong APK 构建、
  独立 UID/权限设备验收、Keystore、TLS/mock server 或 CaptureGrant 测试。

## 已验证的门

在当前工作区可重复执行：

```sh
tools/check-project.sh
tools/run-tests.sh
tools/build-android-stub.sh
tools/build-aidl-boundary-stub.sh
```

这些命令验证主机核心、静态安全检查、AIDL/Java 生成和 API 34 arm64 stub。
当前 API 35 enforcing emulator 已重新验证 stub 的 mmap 崩溃恢复；该结果不能
替代 Android 14 目标设备、平台签名 Activity 或私有 `libgui` 的设备验证。

## 已知限制与后续项

1. AOSP capture 当前只保留采样 hash，不提供连续 Virtual Display/BufferQueue、ROI
   图像或 OCR/模型推理。
2. `DecisionEngine` 没有真实策略；显式 Activity 已接入会话 AIDL，但尚未接入
   microphone FGS 或 STT 引擎。
3. power-key handoff、AudioRecord/Vosk、microphone FGS、overlay 和 SystemUI 状态
   指示均未接入生产入口。
4. init 只有 daemon 的秒级 restart/backoff，没有 supervisor、SurfaceFlinger death
   link 或两帧稳定后恢复逻辑。
5. 完整 `libgui`、platform AIDL、framework stubs、OEM sepolicy 和平台签名尚未在
   目标 AOSP tree 编译。
6. ModelGateway 当前只有公开配置导入；没有 HTTP client、credential UI、
   protocol-v2 AIDL、CaptureGrant 或任何模型调用。

## 日志维护规则

每次实现变更后追加一条阶段记录，至少包含：

- 变更范围和涉及文件；
- 安全/权限边界是否改变；
- 运行的命令和结果；
- 未验证的设备条件；
- 下一步或阻塞原因。

只完成设计、代码骨架或静态检查时，使用“设计完成”“集成骨架”或“本地验证”，
不要写成“设备可用”或“生产通过”。
