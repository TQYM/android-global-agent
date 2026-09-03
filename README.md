# Android 14 Global Agent

English | [简体中文](README.zh-CN.md)

This repository is a security-bounded implementation scaffold for an Android 14
global agent on an owned or explicitly authorized device. It separates the
portable state machine from AOSP-private capture and input APIs.

It does not bypass Restricted Settings, Enhanced Confirmation Mode, Play
Protect, secure surfaces, hardware-backed key storage, app sandboxing, or
third-party anti-tamper controls.

## Implemented

- C++20 perception/decision/input loop with a 200 ms step deadline.
- Crash-recoverable mmap state store using two CRC-protected generations.
- Bounded state graph with explicit binary serialization.
- Deterministic cubic Bezier paths sampled by approximate arc length.
- Normalizers for low-frequency `dumpsys activity/window` diagnostic output.
- Bounded subprocess runner that kills diagnostic commands on timeout.
- AOSP 14 `ScreenshotClient::captureDisplay(DisplayId, ...)` backend with a
  bounded callback and fence wait; the root-authorized `captureDisplayById`
  path keeps secure capture off. This is a private platform ABI and must be
  compiled against the exact device source drop.
- Platform-signed Java input bridge using validated structured AIDL messages.
- Platform task metadata publisher without granting the daemon broad dumpsys
  access.
- init, SELinux, property and service context integration skeletons.
- Host unit tests and Android NDK stub cross-build.

## Deliberately not implemented

- A policy/model that chooses real user actions. `DecisionEngine` is an
  interface and the production AOSP binary defaults to no action.
- Capture of `FLAG_SECURE`, DRM or protected buffers.
- Runtime SELinux injection into `system_app` or direct `/dev/uinput` access.
- LSPosed hooks or extraction of private data from third-party apps.
- A claim that init can restart the process within 50 ms.

## Host build

```sh
tools/run-tests.sh
build/host/global-agentd \
  --state /tmp/global-agent-demo.bin \
  --iterations 4 \
  --demo-action
```

The host executable uses synthetic frames and a logging-only input injector. It
exercises state transitions without sending input to the computer or a device.

## Shell command backend (no AOSP build required)

The portable loop also drives real devices through plain Android shell
commands — `screencap` for perception, `input tap`/`swipe`/`keyevent` for
injection — over adb or directly on-device. See
[docs/SHELL_BACKEND.md](docs/SHELL_BACKEND.md) for the command mapping, latency
budgets, and limitations.

```sh
build/host/global-agentd \
  --backend shell-adb \
  --state /tmp/global-agent-demo.bin \
  --iterations 4 \
  --demo-action
```

## Android NDK stub build

```sh
tools/build-android-stub.sh
```

This verifies that the portable core cross-compiles for API 34/arm64. The NDK
stub does not contain `libgui` or hidden framework APIs because those are not
part of the NDK.

## Full AOSP build

Copy the repository into the Android 14 source tree, for example
`system_ext/global_agent`, then add:

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    privapp-permissions-com.example.globalagent
```

Merge `android/sepolicy/` through the product's `SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS`
or equivalent product policy configuration. Build against the exact device tag
or OEM source drop; `libgui` is a private platform ABI. The bridge also needs
Soong `platform_apis` and target framework stubs; a public SDK `android.jar` is
insufficient for its hidden platform symbols.

See [AOSP integration](docs/AOSP_INTEGRATION.md) and
[security model](docs/SECURITY.md) before device deployment.
The step-by-step procedure is in the
[operations manual](docs/OPERATIONS_MANUAL.md).
The opt-in trigger, offline STT, visual-state, and session-lifecycle boundary is
documented in [trigger/STT integration](docs/TRIGGER_STT_INTEGRATION.md).
The Android 14 power-key event path is audited in
[POWER_KEY_AUDIT.md](docs/POWER_KEY_AUDIT.md), and the offline speech/edge-glow
implementation boundary is in
[STT_OVERLAY_ANDROID14.md](docs/STT_OVERLAY_ANDROID14.md).

## Runtime data

The platform binary writes `/data/misc/global_agent/state.bin`. Ordinary visual
observations are persisted at most once per second; action feedback is committed
immediately. A damaged or incomplete slot is ignored during recovery.

## Verification status

Host tests and NDK cross-compilation are local gates. A full AOSP build and
device test remain required because the workspace is not an Android platform
source tree and cannot compile AOSP-private headers locally.
