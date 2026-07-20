# 项目进度

更新时间：2026-07-20（Asia/Shanghai）

## 结论

项目目前完成了 Android 14 全局 Agent 的便携核心、AOSP 集成骨架、平台签名
输入 bridge、单帧截屏适配器、会话 AIDL 控制面、显式会话 Activity、KernelSU
调试 WebUI、Android 14/15/16 兼容性门禁，以及独立低权限 ModelGateway
的公开配置导入边界。当前属于“本地可验证的系统边界
实现”，不是已经在目标 Android 14/15/16 设备上验收的完整产品。

本次发布变更集以主线 commit
`0f38c18e784481cc83809222fa30bbb471f7ca91` 为父提交，包含此前本地完成的实现、
验证记录和文档。最终 GitHub commit/PR 是本快照的版本标识。

## 能力状态

| 模块 | 当前状态 | 已有结果 | 尚缺条件 |
| --- | --- | --- | --- |
| C++ Agent 核心 | 本地通过 | 200 ms 单步预算、状态图、CRC 双槽 mmap、崩溃恢复、bounded subprocess | 目标设备性能与断电测试 |
| 单帧系统截屏 | AOSP 集成骨架 | Android 14 `ScreenshotClient::captureDisplay(DisplayId, ...)`、deadline、fence、buffer map、视觉 hash | 目标 AOSP 私有头/库编译、真机格式与延迟实测 |
| 多点触控 | bridge 集成骨架 | 结构化 AIDL、最多五指/256 帧/2 秒、异步节拍、取消、`InputManager.injectInputEvent` | 平台签名 APK、enforcing SELinux、目标设备注入测试 |
| KernelSU 截屏/点击 | 调试可用候选 | WebUI 显式调用 stock `screencap` 与有界单点 `input tap` | 物理 KernelSU 设备与 OEM WebView 回归 |
| 会话控制面 | 本地通过 | trigger/transcript/status DTO、UID 鉴权、revision、超时、取消、Binder death 重连 | Soong 完整构建和设备 Binder 测试 |
| 显式会话入口 | 本地通过 | Launcher Activity、解锁/亮屏门禁、文本 final transcript、取消、退后台自动取消 | 平台 APK Soong 构建和设备生命周期测试 |
| API 34/35/36 兼容契约 | API 35 exact-header 通过、设备阻塞 | r36 完整 tree、calling-SID platform header 编译、规范 MLS SID/多用户 appId 边界、capture 签名核对；三版 SDK/AVD 公共门通过 | API 35 Linux Soong/镜像；API 34/36 exact trees 与逐版私有 ABI |
| 外部模型 API | text-only DeepSeek V4 mock dry-run 已完成、真实 HTTP 未接通 | Provider 中立 dry-run 接口、DeepSeek V4 严格响应解析、Ehviewer release/debug 包 allowlist、API 34/35/36 JVM 矩阵、API 35 Gateway 配置与未授权绑定证据 | exact-tree native v2 SID/注册、Keystore UI、经审批凭据、真实 HTTP/mock-server device 测试 |
| 电源键长按 | 仅审计/设计 | AOSP 13/14 输入路径和兼容风险已有文档 | 目标 framework 源码、产品入口选择、设备测试 |
| 离线语音 STT | 仅设计 | AudioRecord/Vosk/FGS 的权限和线程边界已有设计 | Vosk 版本/模型/许可确认、用户可见入口、设备功耗测试 |
| 边缘光效 | 仅设计 | RuntimeShader/overlay 状态机与安全边界已有设计 | 实际 View/Service、overlay 授权和设备 GPU 测试 |
| 决策引擎 | dry-run only | DeepSeek V4 文本计划解析、置信度/动作/焦点校验，`injectedEvents=0` | exact-tree perception、用户确认、ExecutionGrant 和仍保持关闭的执行路径 |
| 跨应用闭环 | 未完成 | 感知、状态、输入和反馈接口已拆分 | P1-P4 设备证据与受限策略实现 |

## 已完成的工程门

- 主机 C++20 ASan/UBSan 与 CTest。
- API 34 arm64-v8a 便携核心 NDK 交叉编译。
- 结构化 AIDL Java/NDK 代码生成。
- Java 手势校验 8 项、会话状态校验 8 项、显式入口策略校验 16 项。
- API 34 arm64 native Binder 服务主体 `-Werror` 编译。
- API 35 userdebug/Enforcing 模拟器运行 API 34 arm64 stub，并通过 `SIGKILL` 恢复。
- API 矩阵门禁检查 API 34/35/36 SDK、Surface/Input/Power/SELinux 源码入口和 strict 模式要求。
- 同一组 AIDL、显式 Activity 和 Java 策略测试已分别对 API 34/35/36 公共 SDK 编译通过。
- API 34/35/36 userdebug Root AVD 均已运行 API 34-minSdk arm64 stub；API 34/36 另完成 Enforcing 与 `SIGKILL` 恢复。
- 模型网关 endpoint/intent 策略、公开配置 schema/importer/call 负向测试
  已纳入 API 34/35/36 Java 矩阵；生产仍保持 `NoopDecision` 和无网络请求。
- DeepSeek V4 text-only adapter、严格 mock 响应解析和 Ehviewer release/debug
  allowlist 已纳入 API 34/35/36 Java 矩阵；配置 fixture 强制 `dryRun=true`、
  `sendImage=never`、无 API key，所有 dry-run 结果的 `injectedEvents` 固定为 0。
- Bridge caller policy 已覆盖 appId `10000..19999`、多用户 UID、规范 MLS
  category 单值/范围/升序列表和 16 线程有效/无效并发调用；v2 service type
  另有仅 Bridge 可 `find` 的静态 allow/neverallow 门禁。
- 当前 v2 capability/AVD 变更经 DeepSeek `deepseek-chat` 三轮独立复核最终 `pass`，无
  blocker/high/medium、缺失测试或待确认问题。
- 当前 DeepSeek V4/Ehviewer mock dry-run 变更经 DeepSeek `deepseek-chat`
  最终独立复核为 `pass`，无问题、缺失测试或待确认项。
- XML、Shell、WebUI JavaScript、离线资源和危险策略模式静态检查。
- KernelSU v0.4.0 包完整性检查。

标准复现命令：

```sh
tools/run-tests.sh
tools/build-android-stub.sh
tools/build-aidl-boundary-stub.sh
tools/check-project.sh
tools/validation-metadata.sh
```

## 当前发布物

文件：`GlobalAgent-KernelSU-v0.4.0-arm64-debug.zip`

- 用途：Android 14 arm64 KernelSU/Magisk 手动调试。
- 内容：便携核心 smoke test、离线 WebUI、显式系统截屏、单点点击诊断。
- 条目数：26。
- SHA-256：`4bb7fc975b69a6614485917f9c9f979ae80a9c78bc076227a6e3a6b2d985183f`。
- 限制：不含完整 AOSP `libgui` daemon、平台签名 bridge、自动决策、后台多点
  注入、电源键触发或 STT。

## 当前阶段

P0 本地契约与验证已完成。P3 的会话 AIDL 控制面和显式 bridge UI 已提前完成
本地实现。OpenClaw 手册中的独立 ModelGateway APK、公开配置 v2 导入、
protocol v2 AIDL、脱敏 DTO 校验与一次性 CaptureGrant 状态机已完成本地边界，
Gateway Service 与 Java capability 已接入编译边界；private native v2 service
现已实现 exact-tree calling-SID 注册，但所有方法仍固定 `UNSUPPORTED`，已部署运行时
仍为 v1 且不发起 HTTP。API 35 的完整 `android-15.0.0_r36` tree 已找到并 stage
定制 Cuttlefish 产品；P1 仍被 x86_64 Linux Soong 主机、足够的 `OUT_DIR`、匹配
platform key/集成镜像、API 34/36 trees 和完整 enforcing 验收阻塞，
因此 P2-P5 不能宣称设备可用。

下一阶段的必要输入：

1. Android 14/16 AOSP/OEM checkout 路径；Android 15 已固定为
   `aosp-android-15` / `android-15.0.0_r36` / `BP1A.250505.005.D1`。
2. x86_64 Linux VM 和容量充足的外置 `OUT_DIR`，以及匹配 platform key 的集成镜像。
3. 平台签名与产品 sepolicy 合并方式。
4. 首个自有/授权测试 App 和允许执行的具体流程（当前已收到 Ehviewer
   `com.xjs.ehviewer`/`.debug`，只完成启动和文本计划 dry-run，未授权真实注入）。
5. 是否还需要电源键 framework handoff；当前默认入口已选显式 bridge UI。
6. 是否引入 Vosk、模型语言/体积、许可证和目标 ABI。
7. 外部 Provider、认证方式、endpoint/区域、数据出境和日志保留策略。

## 事实来源

- 详细工程历史：`docs/DEVELOPMENT_LOG.md`
- 每轮验证证据：`docs/VALIDATION.md`
- 阶段计划与完成标准：`docs/ROADMAP.md`
- 当前问题清单：`PROJECT_ISSUES.md`
- 完整项目日志入口：`PROJECT_LOG.md`
