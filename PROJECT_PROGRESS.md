# 项目进度

更新时间：2026-07-19 18:50（Asia/Shanghai）

## 结论

项目目前完成了 Android 14 全局 Agent 的便携核心、AOSP 集成骨架、平台签名
输入 bridge、单帧截屏适配器、会话 AIDL 控制面、显式会话 Activity 和 KernelSU
调试 WebUI。当前属于“本地可验证的系统边界实现”，不是已经在目标 Android 14
设备上验收的完整产品。

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
| 电源键长按 | 仅审计/设计 | AOSP 13/14 输入路径和兼容风险已有文档 | 目标 framework 源码、产品入口选择、设备测试 |
| 离线语音 STT | 仅设计 | AudioRecord/Vosk/FGS 的权限和线程边界已有设计 | Vosk 版本/模型/许可确认、用户可见入口、设备功耗测试 |
| 边缘光效 | 仅设计 | RuntimeShader/overlay 状态机与安全边界已有设计 | 实际 View/Service、overlay 授权和设备 GPU 测试 |
| 决策引擎 | 未实现 | 接口存在 | 首个自有测试 App、允许动作、置信度与停止规则 |
| 跨应用闭环 | 未完成 | 感知、状态、输入和反馈接口已拆分 | P1-P4 设备证据与受限策略实现 |

## 已完成的工程门

- 主机 C++20 ASan/UBSan 与 CTest。
- API 34 arm64-v8a 便携核心 NDK 交叉编译。
- 结构化 AIDL Java/NDK 代码生成。
- Java 手势校验 8 项、会话状态校验 8 项、显式入口策略校验 16 项。
- API 34 arm64 native Binder 服务主体 `-Werror` 编译。
- API 35 userdebug/Enforcing 模拟器运行 API 34 arm64 stub，并通过 `SIGKILL` 恢复。
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
本地实现。P1 目标 AOSP 集成仍被目标源码、平台签名和授权设备阻塞，因此 P2-P5
不能宣称设备可用。

下一阶段的必要输入：

1. Android 14 AOSP/OEM checkout 路径、branch/tag、设备 fingerprint 与 SPL。
2. 可用的 userdebug/eng 或明确授权 Root 设备，以及可工作的 ADB。
3. 平台签名与产品 sepolicy 合并方式。
4. 首个自有/授权测试 App 和允许执行的具体流程。
5. 是否还需要电源键 framework handoff；当前默认入口已选显式 bridge UI。
6. 是否引入 Vosk、模型语言/体积、许可证和目标 ABI。

## 事实来源

- 详细工程历史：`docs/DEVELOPMENT_LOG.md`
- 每轮验证证据：`docs/VALIDATION.md`
- 阶段计划与完成标准：`docs/ROADMAP.md`
- 当前问题清单：`PROJECT_ISSUES.md`
- 完整项目日志入口：`PROJECT_LOG.md`
