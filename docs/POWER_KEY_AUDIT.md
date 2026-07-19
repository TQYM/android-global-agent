# Android 14 电源键路径审计

本文基于 AOSP `android-14.0.0_r1` 主线文件核对。它只描述在自有
`userdebug`/`eng` 构建中的合法集成边界，不提供绕过锁屏、关机确认、
Restricted Settings、Play Protect 或其他用户安全确认的做法。

## 事件路径

```text
Linux evdev (/dev/input/event*)
  -> EventHub::getEvents()
  -> InputReader::processEventsLocked()
  -> InputDevice::process() / KeyInputMapper
  -> InputReader 的 queued listener
  -> InputDispatcher::notifyKey()
  -> NativeInputManager::interceptKeyBeforeQueueing()
  -> InputManagerService.interceptKeyBeforeQueueing()
  -> WindowManagerCallbacks
  -> PhoneWindowManager.interceptKeyBeforeQueueing()
```

可核对的源码位置：

| 阶段 | AOSP 14 文件 | 关键事实 |
| --- | --- | --- |
| evdev 读取 | `frameworks/native/services/inputflinger/reader/EventHub.cpp` | `DEVICE_INPUT_PATH` 为 `/dev/input`；`EventHub::getEvents()` 产生 `RawEvent`。 |
| 原始事件转换 | `frameworks/native/services/inputflinger/reader/InputReader.cpp`、`InputDevice.cpp`、`reader/mapper/KeyboardInputMapper.cpp` | `KeyboardInputMapper::process()` 处理 `EV_KEY`，`processKey()` 通过设备 key map 映射 `AKEYCODE_POWER` 并构造 `NotifyKeyArgs`；之后经 queued listener 转发。 |
| 入队前策略 | `frameworks/native/services/inputflinger/dispatcher/InputDispatcher.cpp` | `InputDispatcher::notifyKey()` 在入队前调用 `mPolicy.interceptKeyBeforeQueueing()`；策略耗时会记录 slow warning。 |
| Java JNI 桥 | `frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp` | `NativeInputManager::interceptKeyBeforeQueueing()` 将可信 `KeyEvent` 回调到 Java。 |
| Java 回调 | `frameworks/base/services/core/java/com/android/server/input/InputManagerService.java` | 私有 native callback 转发到 `mWindowManagerCallbacks`。 |
| 系统策略 | `frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java` | `interceptKeyBeforeQueueing()` 处理 `KEYCODE_POWER`，并清除 `ACTION_PASS_TO_USER`。 |

`InputDispatcher::notifyKey()` 还会在真正发往应用前调用
`interceptKeyBeforeDispatching()`。电源键在策略阶段已被系统消费，普通应用因此
没有一个稳定的公开回调可以监听它；`KeyEvent` 的普通应用分发并不是电源键的
所有权入口。

## Android 14 的长按实现

`PhoneWindowManager.interceptKeyBeforeQueueing()` 在非 fallback 事件上调用
`handleKeyGesture()`。该方法先让 `KeyCombinationManager` 检查组合键，然后把
电源键交给 `SingleKeyGestureDetector.interceptKey()`。相关文件是：

```text
frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java
frameworks/base/services/core/java/com/android/server/policy/SingleKeyGestureDetector.java
```

`PhoneWindowManager` 内部的 `PowerKeyRule` 注册为 `KEYCODE_POWER` 规则：

```text
PowerKeyRule.onLongPress() -> PhoneWindowManager.powerLongPress()
PowerKeyRule.onVeryLongPress() -> powerVeryLongPress()
PowerKeyRule.onPress()/onMultiPress() -> powerPress()
```

`interceptPowerKeyDown()` 只处理按下时的 wake lock、电话/电源策略、状态栏通知和
`mPowerKeyHandled` 状态；它不是长按计时器。计时消息由
`SingleKeyGestureDetector` 的 `Handler` 投递。

默认长按时长来自：

```text
frameworks/base/core/res/res/values/config.xml
  com.android.internal.R.integer.config_globalActionsKeyTimeout
```

AOSP 14 主线默认值是 `500` ms。`PowerKeyRule` 在解析为 Assistant 行为时，才
使用 `config_longPressOnPowerDurationMs` 及
`Settings.Global.POWER_BUTTON_LONG_PRESS_DURATION_MS`；不能把后者当成所有
电源长按行为的统一计时器。把 `config_globalActionsKeyTimeout` 改成 2000 ms
还会影响其他 global-action key gesture，必须在目标产品上回归。

若产品确实需要“仅 Agent 为 2 秒”，应新增产品私有 resource（例如
`config_agentPowerLongPressDurationMs`），并在专用 `PowerKeyRule` 分支使用；不要
复用或全局修改 `config_globalActionsKeyTimeout`。AOSP 注释或历史 UI 文案中出现
的“two seconds”不能替代当前资源值和设备实测。

设备还读取以下资源/设置：

```text
config_supportLongPressPowerWhenNonInteractive
config_longPressOnPowerBehavior
config_veryLongPressOnPowerBehavior
Settings.Global.POWER_BUTTON_LONG_PRESS
Settings.Global.POWER_BUTTON_VERY_LONG_PRESS
```

AOSP 14 默认 `config_supportLongPressPowerWhenNonInteractive` 为 `false`。
因此从熄屏/非 interactive 状态开始的按压，`PowerKeyRule.onLongPress()` 会直接
返回；不能仅靠普通应用或 shell 假定“锁屏/熄屏也会触发”。锁屏但仍 interactive
时，后续 Assistant/Voice Assist/Global Actions 仍受 keyguard、角色和设备策略
限制。

## 集成选项与边界

### 自有 AOSP 分支

在匹配设备源码中修改 `PowerKeyRule`/`powerLongPress()`，增加明确的、可审计的
Agent handoff，并保留原有 key-up、取消、组合键、多击和 very-long-press 状态
机。Agent 应仅在设备已解锁或产品明确允许的状态下接管；安全 UI（关机、紧急呼叫、
认证、受保护内容）必须保持系统优先级。

这要求重编 `system_server`/framework 并使用匹配的签名、ART oat/vdex 和 AVB
产物。通过 Magisk 单独覆盖 `framework.jar` 不是 AOSP 支持的部署方式，可能因
classpath、预编译 oat/vdex、分区校验或版本不匹配导致 bootloop；不能把它写成
“直接可实施”的生产方案。

### LSPosed（仅测试 flavor）

`PhoneWindowManager` 和 `interceptPowerKeyDown()` 是 system_server 中的私有
实现，不是 SDK API。LSPosed 理论上可以在 system_server scope 观察该方法，但：

* hook `interceptPowerKeyDown()` 只能看到 DOWN，不能自然表示 2 秒长按；
  长按由 `SingleKeyGestureDetector` 定时并回调 `PowerKeyRule.onLongPress()`。
* 方法、内部类、ART/OEM 优化和进程注入方式都可能在安全补丁或 ROM 更新后变化。
* hook 不会自动获得 keyguard、音频焦点、录音或系统 UI 权限，也不应通过修改
  `mPowerKeyHandled` 来隐藏关机/确认 UI。

因此 LSPosed 只适合自有测试应用或实验 ROM 的观测/验证。生产系统应使用平台签名
的 framework 改动或显式系统服务接口，并记录用户可见的触发状态。

## 锁屏、关机和确认冲突

`PhoneWindowManager.powerLongPress()` 的行为由资源/设置决定：

* `LONG_PRESS_POWER_GLOBAL_ACTIONS` 调用 `showGlobalActions()`；
* `LONG_PRESS_POWER_SHUT_OFF` 与 `LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM` 调用
  `WindowManagerFuncs.shutdown(...)`，确认语义不同；
* `LONG_PRESS_POWER_GO_TO_VOICE_ASSIST`/`LONG_PRESS_POWER_ASSISTANT` 还要经过
  setup、keyguard、Assistant 角色和系统策略检查。

`getResolvedLongPressOnPowerBehavior()` 还会在
`FactoryTest.isLongPressOnPowerOffEnabled()` 为真时选择
`LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM`；这是工厂测试语义，不是 Agent 的授权
接口，产品构建不得用它来消除用户确认。设备尚未 provisioned 时，Assistant 长按
也会降级为 Global Actions；Voice Assist 被设置禁用时则降级为 Nothing。

Agent 触发设计必须先定义唯一 owner 和取消窗口：在检测到合法长按后发送一个
受限的系统事件，并让原策略明确返回“已处理”或继续原行为；不能同时启动 Agent
和关机菜单，也不能把 `NO_CONFIRM` 当成绕过用户确认的接口。安全补丁具体差异
取决于设备 fingerprint、分支和 SPL，未拿到目标源码时不猜 commit ID。

## 兼容性结论

* AOSP 14 主线仍包含 `config_globalActionsKeyTimeout`，未发现一个可泛化到所有
  2025 安全补丁的“电源键路径被封堵”提交。OEM overlay 和 backport 必须实测。
* `android14-qpr3-release` 的同一策略已经出现可见的接口漂移：
  `SingleKeyGestureDetector.get(Context, Looper)`、带 `displayId` 的
  `MessageObject`/回调、`PhoneWindowManager.handleKeyGesture()` 的额外
  `defaultDisplayOn` 参数，以及 `PowerKeyRule.onKeyUp()`/early-short 处理。
  这不是安全绕过，而是证明按 `android-14.0.0_r1` 私有签名做 LSPosed hook 会
  在 QPR/OEM 分支失效或误拦截。必须以目标分支源码编译和测试。
* `KEYCODE_POWER` 处理位于 system_server/inputflinger 的受信路径；Root shell
  身份本身不等于 Java policy callback 或录音/显示捕获权限。
* 熄屏长按、锁屏 Assistant、关机确认和 secure surface 都是独立策略边界，不能
  用一个 hook 或 `setprop` 统一绕过。
* 若平台改动无法接受，降级为用户明确启用的 `AccessibilityService` 或普通
  Assistant/VoiceInteractionService；该降级不会获得电源键原始事件，但保留可
  审计的用户触发流程。

**置信度：源码路径与 AOSP 14 时序 0.96；OEM/2025 SPL 兼容性 0.70，必须按
目标 fingerprint、SPL 和源码 tag 编译验证。**
