# Android 14 Global Agent 操作手册

版本：2026-07-19

本文面向已获授权的 Android 14 `userdebug`/`eng` 设备、AOSP 产品工程师和
测试人员。操作前先确认设备属于你或你有明确授权。手册中的“Agent”是一个
安全边界实现骨架，不是用来绕过锁屏、关机确认、麦克风隐私开关、SELinux、
Play Protect、`FLAG_SECURE`、DRM、TEE 或第三方应用沙箱的工具。

## 1. 先看状态

| 能力 | 当前状态 | 运行条件 |
| --- | --- | --- |
| 主机状态机 Demo | 可运行 | macOS/Linux、CMake、Ninja、C++20 |
| API 34 arm64 stub | 可运行 | Android SDK、NDK 26.1、CMake 3.22、`adb` |
| AOSP 14 屏幕感知 | 集成骨架 | 目标 AOSP/OEM 源码、`libgui` 私有 ABI、root system service |
| 平台输入桥 | 集成骨架 | platform certificate、Soong `platform_apis`、`INJECT_EVENTS` |
| 电源键 2 秒触发 | 设计/审计完成，未接入生产入口 | 修改匹配的 framework 或受控系统服务 |
| 离线 STT | 设计/服务骨架，未随产品启用 | 用户授予麦克风权限、microphone FGS、Vosk 模型/许可 |
| 边缘光效 | 设计/渲染骨架，未随产品启用 | 用户授予悬浮窗权限或自有 SystemUI 集成 |
| 独立 AI 审批 | `blocked` | 审批插件当前无可用模型/受管凭据 |

当前 AOSP 主程序使用 `NoopDecision`，默认只做感知、状态记录和 Binder
注册，不会自行执行跨应用动作。新增的 `SessionContext` 只提供有界的临时
触发/文本/视觉状态，不会自动打开麦克风或接管电源键。

详细设计参考：

- [AOSP 集成](AOSP_INTEGRATION.md)
- [电源键审计](POWER_KEY_AUDIT.md)
- [STT 与边缘光效](STT_OVERLAY_ANDROID14.md)
- [触发与会话契约](TRIGGER_STT_INTEGRATION.md)
- [安全模型](SECURITY.md)

## 2. 工作区和工具链

进入项目目录：

```sh
cd "/Users/tqym_16/Desktop/项目/android agent"
```

默认脚本查找以下 SDK 目录：

```text
$ANDROID_SDK_ROOT
$ANDROID_HOME
$HOME/Library/Android/sdk
```

需要的固定工具版本：

```text
Android SDK platform 35（用于 AIDL/生成类检查）
Android NDK 26.1.10909125
CMake 3.22.1
Ninja（随 CMake 安装）
JDK 17
```

如果 SDK 不在默认路径，先设置：

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
```

不要用 public `android.jar` 编译平台 bridge。它缺少 `ServiceManager`、隐藏
的 `InputManager` 常量、`MotionEvent.setDisplayId` 和部分隐藏的
`RunningTaskInfo` 字段；完整构建必须使用目标 AOSP 的 Soong
`platform_apis: true` 和 framework stubs。

## 3. 主机快速验证

### 3.1 一键测试

```sh
./tools/run-tests.sh
```

该脚本依次执行：

1. Debug C++20 构建；
2. ASan/UBSan 主机测试；
3. CTest；
4. AIDL Java/NDK 生成和 Java 编译；
5. XML、Shell 和安全模式静态检查。

成功时应看到：

```text
100% tests passed, 0 tests failed out of 1
```

### 3.2 运行合成 Demo

```sh
build/host/global-agentd \
  --state /tmp/global-agent-demo.bin \
  --iterations 4 \
  --interval-ms 5 \
  --demo-action
```

预期输出类似：

```text
validated demo gesture with 5 frames
generation=... nodes=... edges=...
```

这里的 perception 是合成数据，input injector 只写日志，不会控制主机或手机。
`--state` 文件使用双槽 CRC 格式；测试结束后可删除该临时文件：

```sh
rm -f /tmp/global-agent-demo.bin
```

只删除 `/tmp` 下的 Demo 文件；不要在不了解状态的情况下删除设备上的
`/data/misc/global_agent/state.bin`。

## 4. API 34 arm64 Stub 和模拟器

### 4.1 构建

```sh
./tools/build-android-stub.sh
```

产物：

```text
build/android-arm64/global-agentd
```

这是便携核心 stub，不包含 AOSP 私有 `libgui`、隐藏 framework API、真实录音
或真实悬浮窗。

### 4.2 连接设备

使用授权的 `userdebug`/root 模拟器或工程机：

```sh
adb start-server
adb devices -l
adb root                 # 仅 userdebug/eng 支持；失败时不要强行绕过
```

设备应显示为 `device`，而不是 `unauthorized` 或 `offline`。

### 4.3 推送并运行

```sh
./tools/push-debug-stub.sh
```

脚本会把二进制推到 `/data/local/tmp/global-agentd`，使用：

```text
/data/local/tmp/global-agent-state.bin
```

并运行四轮合成 Demo。成功输出包含 `validated demo gesture with 5 frames`。

### 4.4 手动恢复测试

以下步骤只适用于你自己的测试模拟器/工程机：

```sh
adb shell rm -f /data/local/tmp/global-agent-recovery.bin
adb shell 'nohup /data/local/tmp/global-agentd \
  --state /data/local/tmp/global-agent-recovery.bin \
  --iterations 1000000 --interval-ms 10 --demo-action \
  >/data/local/tmp/global-agent-recovery.log 2>&1 &'
adb shell pidof global-agentd
adb shell ls -l /data/local/tmp/global-agent-recovery.bin
```

记录 PID 后杀掉进程：

```sh
adb shell kill -9 <PID>
adb shell /data/local/tmp/global-agentd \
  --state /data/local/tmp/global-agent-recovery.bin \
  --iterations 4 --interval-ms 5 --demo-action
```

恢复运行应能读取上一个有效 generation。该测试验证 mmap/CRC/状态恢复，不等于
完整 AOSP 14 `libgui` 或 platform Java 验证。

### 4.5 模拟器测试故障

如果出现：

```text
could not install *smartsocket* listener: Operation not permitted
```

说明当前运行环境不能启动 ADB server。不要通过修改系统网络策略或绕过沙箱
解决；记录为环境阻断，改跑 `tools/run-tests.sh` 和主机 Demo，并在有可用 ADB
的工程环境重试。

## 5. 完整 AOSP 14 集成

### 5.1 放置源码

在目标 AOSP checkout 中放置项目，例如：

```text
system_ext/global_agent/
```

不要把 native daemon 当成 vendor binary 构建；它链接 `libgui`、`libui` 和
`libbinder` 的私有 platform ABI。

### 5.2 产品配置

在产品 makefile 中加入：

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    privapp-permissions-com.example.globalagent

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += \
    system_ext/global_agent/android/sepolicy
```

确认以下文件随产品安装：

```text
/system_ext/bin/global-agentd
/system_ext/etc/permissions/privapp-permissions-com.example.globalagent.xml
/system_ext/etc/init/global-agent.rc（具体分区由 Soong 决定）
```

### 5.3 编译

```sh
source build/envsetup.sh
lunch <device>-userdebug
m global-agentd GlobalAgentBridge \
  privapp-permissions-com.example.globalagent
```

编译时必须使用目标设备的 exact branch/tag。重点检查：

- `ScreenshotClient::captureDisplay(DisplayId, listener)` 的签名；
- `ScreenCaptureResults::buffer` 与 `fenceResult`；
- 生成的 classic Binder/NDK AIDL header；
- `WindowConfiguration`、`InputManager` 隐藏 platform API；
- `global_agent_bridge` 和 `agentd` 的 policy neverallow；
- privapp allowlist 是否被 PackageManager 读取。

### 5.4 Framework 电源键集成（可选）

只有在自有 AOSP 分支中实现。不要直接修改公共 SDK，也不要把
`framework.jar` 用 Magisk 覆盖到未知 ROM。

实施顺序：

1. 固定 device fingerprint、SPL、AOSP branch 和 framework commit；
2. 在 `PhoneWindowManager.PowerKeyRule.onLongPress()` 或同等目标分支的
   policy handoff 点增加 Agent 检查；
3. 使用产品私有资源，例如 `config_agentPowerLongPressDurationMs=2000`，
   不要把全局 `config_globalActionsKeyTimeout` 改为 2000；
4. 保留 key-up、取消、多击、very-long、组合键和 wake-lock 状态机；
5. 只向受签名权限保护的 Binder 服务发送有限 `TriggerEvent`；
6. 服务不可用、Keyguard 拒绝、屏幕非 interactive 或用户未确认时，回到原生
   电源键行为；
7. 在解锁、锁屏、关机菜单、紧急呼叫和 FactoryTest 场景分别回归。

AOSP 14 的 `interceptPowerKeyDown()` 不是长按计时器；计时由
`SingleKeyGestureDetector` 完成。LSPosed hook 私有方法只适合测试 flavor，
不能当作跨 ROM 生产方案。

### 5.5 STT 集成

STT 必须由 bridge APK 的用户可见 microphone foreground service 负责，native
daemon 不直接读 ALSA 或 `/dev/snd`。

最小权限和组件：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

服务声明：

```xml
<service
    android:name=".SpeechService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

用户必须在可见 Activity 中完成：

1. 授予 `RECORD_AUDIO`；
2. 如启用边缘光效，进入系统设置授权 `SYSTEM_ALERT_WINDOW`；
3. 确认前台服务通知和麦克风隐私指示器；
4. 解锁设备后启动一次会话；
5. 静音约 0.8--1.2 秒或达到 8--10 秒硬时限后自动停止。

Android 14 的 `RECORD_AUDIO` 是 while-in-use 权限。Root、SAW 悬浮窗权限或
`pm grant` 不能把锁屏后台启动变成合法隐式录音。被 `ActiveServices`、AppOps、
通话状态或全局麦克风开关拒绝时，应显示失败状态并释放 `AudioRecord`。

Vosk 管线建议：

```text
AudioRecord 16 kHz mono PCM
  -> <=1s 有界队列
  -> 单独 recognizer executor
  -> partial/final transcript
  -> 受签名 Binder 的 TranscriptChunk（<=4096 UTF-8 bytes）
  -> SessionContext
```

不要把原始 PCM 或完整 transcript 写入 mmap 状态文件；SessionContext 会在取消、
超时和切换会话时清除文本。

### 5.6 边缘光效集成

普通 bridge 使用 `TYPE_APPLICATION_OVERLAY`，并在用户授权 SAW 后创建：

```text
FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE
```

光效应只绘制四条窄边带，状态绑定如下：

```text
ARMING -> LISTENING（快速亮起）
LISTENING -> THINKING（脉动）
THINKING -> EXECUTING（稳定）
COMPLETE/ERROR -> IDLE（约 250 ms 渐出）
```

`RuntimeShader` 仅在 API 33+ 使用；API 32 以下回退到 `LinearGradient`/`SweepGradient`。
普通 overlay 不保证覆盖 Keyguard、状态栏、导航栏或 secure window。需要系统级
层级时必须做自有 SystemUI/platform-signed 集成，不能伪造 `TYPE_STATUS_BAR` 或
trusted overlay 标志。

## 6. 运行时检查

### 6.1 init 与 Binder

```sh
adb shell getprop sys.boot_completed
adb shell getprop sys.agent.enabled
adb shell service check global_agent
adb shell setprop sys.agent.enabled 1
adb shell service check global_agent
```

成功时应看到服务已注册。查看日志：

```sh
adb logcat -s global-agentd GlobalAgentBridge GlobalAgentInput
```

停止服务：

```sh
adb shell setprop sys.agent.enabled 0
```

`restart_period 1` 是秒级 backoff hint，不是 50 ms 保证；init 的 crash-rate
限制可能延迟再次启动。

### 6.2 文件上下文和权限

```sh
adb shell ls -ldZ /data/misc/global_agent
adb shell ls -lZ /system_ext/bin/global-agentd
adb shell id
adb shell getenforce
```

期望：

```text
/data/misc/global_agent       u:object_r:global_agent_data_file:s0
/system_ext/bin/global-agentd u:object_r:agentd_exec:s0
Enforcing
```

不要执行 `setenforce 0`。只检查与 Agent 预期操作相关的 AVC：

```sh
adb shell dmesg | grep -E 'avc: denied.*(agentd|global_agent)'
```

不要把整份 denial 日志直接交给 `audit2allow`。

### 6.3 屏幕感知

在普通非 secure 画面上检查 visual hash 是否变化；在 DRM/`FLAG_SECURE` 画面上
应得到空白、拒绝或失败。任何能读取 secure buffer 的结果都应视为安全缺陷并
停止测试。

### 6.4 会话和文本

当前 native 服务尚未开放实际 `submitTranscript` AIDL。接入时必须验证：

- caller UID/SID 是 platform/system 或已安装 bridge；
- session ID、序号严格递增；
- 文本是合法 UTF-8、非空、最多 4096 bytes；
- 15 秒超时、Binder death、取消后文本清空；
- StateStore 中不出现原始 transcript。

## 7. 常见问题

| 症状 | 可能原因 | 处理 |
| --- | --- | --- |
| `adb devices` 无设备 | ADB server、授权或端口问题 | 先在可控工程环境启动 ADB；确认设备 `device` 状态 |
| `global_agent` 不存在 | daemon 未启动或 SELinux/service context 错误 | 查 `getprop`、`logcat`、`ls -Z`，不要放宽 policy |
| `PERMISSION_DENIED` 截屏 | 走了错误的 `DisplayCaptureArgs` 路径、UID/分支不匹配 | 编译目标分支的 `ScreenshotClient::captureDisplay(DisplayId, ...)`，核对 SPL |
| capture callback timeout | SurfaceFlinger 重启、Fence 未完成或设备过载 | 取消本轮动作，等待下一帧；不要无限等待 |
| `TransactionTooLargeException` | 传递了未知 Parcelable/过大文本 | 只传固定 DTO，文本限制 4096 bytes |
| FGS 启动被拒 | 后台/锁屏 while-in-use 或 AppOps 不允许 | 回到可见 Activity，用户授权后重试一次 |
| 麦克风无声音 | 全局麦克风开关、通话路由、AudioRecord 状态错误 | 记录 AppOps/AudioService，释放并显示错误 |
| 光效不显示 | SAW 未授权、Keyguard/OEM 遮挡、窗口 token 错误 | 只做可见授权和 `BadTokenException` 恢复 |
| 光效挡住点击 | 缺少 `FLAG_NOT_TOUCHABLE` | 修正 flags，重新创建窗口 |
| 长按触发关机菜单 | Agent handoff 未成为唯一 owner | 回滚 framework 改动，保留原生策略并重新设计取消窗口 |
| daemon 被杀后未立即恢复 | init crash backoff | 查看 init 日志；不能承诺或伪造 50 ms 恢复 |

## 8. 回滚和卸载

### 8.1 运行时停用

```sh
adb shell setprop sys.agent.enabled 0
adb shell stop global-agentd
```

停用后确认：

```sh
adb shell service check global_agent
adb logcat -d -s global-agentd GlobalAgentBridge
```

### 8.2 AOSP 产品回滚

1. 从产品 `PRODUCT_PACKAGES` 移除 `global-agentd`、`GlobalAgentBridge` 和
   privapp allowlist；
2. 移除对应 `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`；
3. 重新构建并刷回经过签名的 system/system_ext 镜像；
4. 确认 `getenforce=Enforcing`、原生电源键/关机菜单和麦克风指示器恢复；
5. 只在明确需要清理测试数据时删除 Agent 自有状态文件，并保留日志证据。

不要在量产设备上直接删除 framework jar、修改 AVB 分区或使用未知 Magisk
模块覆盖 system service。

### 8.3 清理测试状态

仅对测试路径执行：

```sh
adb shell rm -f /data/local/tmp/global-agent-state.bin
adb shell rm -f /data/local/tmp/global-agent-recovery.bin
```

生产状态文件 `/data/misc/global_agent/state.bin` 应通过产品卸载/数据清理流程
处理，不要把它和 `/data/local/tmp` 混用。

## 9. 安全验收清单

在标记“可部署”前逐项打勾：

```text
[ ] 目标 fingerprint、SPL、AOSP/OEM branch 已记录
[ ] 完整 Soong framework/libgui/platform AIDL 编译通过
[ ] SELinux enforcing 下无未解释 AVC
[ ] 没有 system_app/agentd -> uinput_device 规则
[ ] 没有 setenforce 0、未知 Magisk framework 覆盖或隐式 hook
[ ] INJECT_EVENTS/REAL_GET_TASKS 权限来自正确签名/allowlist
[ ] 电源键短按、长按、very-long、多击、组合键和关机确认全部回归
[ ] 锁屏/熄屏不会静默启动麦克风或跨应用动作
[ ] FGS 通知、麦克风指示器和停止入口可见
[ ] SAW/overlay 只在用户授权后启用，且不拦截输入
[ ] secure/DRM/FLAG_SECURE 内容不可读
[ ] STT 文本不进入 StateStore，超时/取消后已清除
[ ] SystemUI、SurfaceFlinger、daemon 重启后能安全回到 IDLE
[ ] 已完成 P50/P95/P99 延迟、CPU、内存、电量测量
```

## 10. 事实核查和限制

电源键、STT、overlay 的接口和版本事实分别见三个专项文档；若目标设备是
Android 14 QPR/OEM 分支，必须重新生成 framework stubs 并核对私有签名。不能
用 `android-14.0.0_r1` 的行号直接套用到 2025 安全补丁设备。

纯 Root 方案无法完美绕过 Google Play Protect 的检测，无法从 TEE/StrongBox
获取密钥，不能解密金融 App 的加密传输。任何声称可以做到这些的操作手册都不
可信。

**当前交付结论：** 主机核心和 API 34 stub 可验证；完整 AOSP 14 集成、真实
电源键 handoff、麦克风 FGS、Vosk 模型和 overlay 仍属于待设备/产品授权的
集成工作，不应把本手册当作已经刷入量产设备的证明。
