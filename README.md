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
- Authenticated, revisioned session AIDL for opt-in triggers, bounded
  transcripts, cancellation and visual-state callbacks; the bridge-side client
  resets its revision baseline after Binder death.
- A user-visible bridge launcher Activity with unlocked/interactive gates,
  explicit start, bounded final-text submission, status, cancellation, and
  automatic cancellation when it leaves the foreground.
- Platform task metadata publisher without granting the daemon broad dumpsys
  access.
- A separate low-privilege ModelGateway APK with `INTERNET` only, strict public
  config schema v2 import, root/shell caller checks and atomic persistence. It
  still has no HTTP client, credential storage or capture grant path.
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

## Android NDK stub build

```sh
tools/build-android-stub.sh
tools/build-aidl-boundary-stub.sh
tools/validation-metadata.sh
```

This verifies that the portable core cross-compiles for API 34/arm64. The NDK
stub does not contain `libgui` or hidden framework APIs because those are not
part of the NDK. The AIDL boundary command additionally regenerates Java/NDK
bindings, runs the JVM DTO validators, and compiles the native Binder service
logic. Platform-only service registration is still a Soong build requirement.
The metadata command records the exact local commit and tool
versions, plus fingerprint/SPL/SELinux state when an ADB device is available.

## KernelSU debug WebUI

`tools/package-kernelsu.sh` creates an Android 14 arm64 debug module with an
offline `webroot/index.html`. KernelSU Manager can display module/device status,
run the synthetic portable-core smoke test, show its output, and clear only the
debug state file. Its device-tools tab also provides explicit manual
`screencap` and bounded single-tap diagnostics. This package is not the full
AOSP capture/input product and does not capture secure/DRM surfaces.

## Full AOSP build

Copy the repository into the Android 14 source tree, for example
`system_ext/global_agent`, then add:

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    GlobalAgentModelGateway \
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
The implementation history is tracked in the
[development log](docs/DEVELOPMENT_LOG.md), and staged follow-up work is in the
[roadmap](docs/ROADMAP.md).
Root-level snapshots are available in [project progress](PROJECT_PROGRESS.md),
[complete project log](PROJECT_LOG.md), and [project issues](PROJECT_ISSUES.md).
The opt-in trigger, offline STT, visual-state, and session-lifecycle boundary is
documented in [trigger/STT integration](docs/TRIGGER_STT_INTEGRATION.md).
The cross-version Android 14/15/16 adapter contract and strict source-tree gate
are documented in [the Android 14/15/16 engineering manual](docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md).
The external model boundary and current non-network status are documented in
[the model API gateway guide](docs/MODEL_API_GATEWAY.md).
The current OpenClaw-style host/Android mapping, public config v2 contract and
capture-grant roadmap are in
[the OpenClaw API Agent engineering manual](docs/OPENCLAW_API_AGENT_ENGINEERING_MANUAL.md).
The Android 14 power-key event path is audited in
[POWER_KEY_AUDIT.md](docs/POWER_KEY_AUDIT.md), and the offline speech/edge-glow
implementation boundary is in
[STT_OVERLAY_ANDROID14.md](docs/STT_OVERLAY_ANDROID14.md).
The broader [engineering manual](docs/ANDROID14_GLOBAL_AGENT_ENGINEERING_MANUAL.md)
collects alternative root/AOSP development paths and acceptance gates; those
alternatives are reference material, not all enabled by the current production
configuration.

## Runtime data

The platform binary writes `/data/misc/global_agent/state.bin`. Ordinary visual
observations are persisted at most once per second; action feedback is committed
immediately. A damaged or incomplete slot is ignored during recovery.

## Verification status

Host tests and NDK cross-compilation are local gates. A full AOSP build and
device test remain required because the workspace is not an Android platform
source tree and cannot compile AOSP-private headers locally.
