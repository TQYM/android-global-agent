# Android Global Agent

> A vision- and semantics-driven phone automation agent for Android 11+. The project uses the Accessibility Service as its universal execution substrate, and can enable faster screenshots, input, and isolated virtual display capability on rooted devices that have been explicitly authorized.

[中文 README](README.zh-CN.md)

[Project Overview](PROJECT_OVERVIEW.zh-CN.md) · [Compatibility Notes](agent-lite/COMPATIBILITY.zh-CN.md) · [Security Review](SECURITY_REVIEW_2026-09-05.zh-CN.md) · [Apache-2.0 License](LICENSE)

## Three-Step Quick Start (Android 11+)

1. **Install**: download the APK from [Releases](https://github.com/TQYM/android-global-agent/releases) and sideload it;
2. **Authorize**: enable the **Agent Lite Accessibility Service** in system settings, and allow notifications and the microphone as needed;
3. **Fill in the Key**: enter the Base URL / API Key / model in the app, then type a task and run it.

> Agent Lite requires no root. The API Key is stored only on the local device.

## Project Positioning

Android Global Agent attempts to turn natural-language tasks into a verifiable phone-operation loop:

```text
User task
   ↓
Semantic planning (optional)
   ↓
Screen nodes + screenshot perception
   ↓
OpenAI-compatible multimodal model decision
   ↓
Tap / swipe / type / launch app / system actions
   ↓
Re-verify page changes until done, failed, or user hand-over required
```

The current primary focus is `agent-lite/`: it does not depend on root, implements perception and action with native Java and Android Framework APIs, and adds interaction capabilities such as task visibility, authorization, hand-over in sensitive scenarios, history, and explicit memory.

## Current Capabilities

### Zero-Root General Capabilities

- Collect accessible windows and semantic nodes through `AccessibilityService`;
- Capture screen images through `takeScreenshot()`, and switch to pure vision coordinate mode when nodes are unavailable;
- Perform taps, long presses, swipes, and the edge back gesture through `dispatchGesture()`;
- Type Unicode/Chinese text through `ACTION_SET_TEXT`, with fallback to the IME or clipboard channel on failure;
- Support global actions such as back, home, recents, notification shade, quick settings, and lock screen;
- Support launching installed apps, jumping directly to common system settings, and adjusting brightness and volume;
- Support audio recording and voice input through a compatible endpoint;
- Use a foreground service to improve background survival on Chinese OEM ROMs.

### Agent Lite Interaction and Safety Capabilities

- **Visible agent state**: capsule, edge glow, authorization card, hand-over card, and terminal task-state card;
- **Pre-authorization**: per "operation type × app", support allow once, always allow, or deny;
- **In-flight control**: stop the task or add requirements while it is running;
- **Post-hoc audit**: task results are written to local history, and authorization, ecosystem policies, and memory can be managed;
- **Hand-over in sensitive scenarios**: pause automatic operation in scenarios such as payments, verification codes, agreement confirmation, and system permissions;
- **Ecosystem policies**: restrict automatic operation for specific apps, or attempt vision-only mode after authorization;
- **Planning up front**: use a separate model to organize colloquial tasks into goals, steps, completion criteria, and risk warnings.

### Optional Root Enhancements

The full version `agent-client/` can use root as an accelerator after explicit user authorization:

- `screencap` for higher screenshot frequency and stability;
- `input tap/swipe/keyevent` as a synchronous input channel;
- A root daemon creates a trusted virtual display and migrates the target app into an isolated screen to run;
- Stop operating and attempt recovery when the sandbox fails; downgrading to blind taps on the real screen is prohibited.

Root is not a prerequisite for running `agent-lite`. The isolated virtual display applies only to unlocked test devices under user control.

## Subprojects

| Directory | Status | Purpose |
|---|---|---|
| `agent-lite/` | **Recommended** | Zero-root native client, the current main development direction, package name `com.dsh.agentlite` |
| `agent-client/` | Optional | Full client, package name `com.dsh.agent`, including optional root acceleration and a virtual-display sandbox |
| `agentlite-server/` | **Server** | Accounts / device binding / account-level sync / LAN rendezvous / quota gateway (Go, stdlib only) |
| `agent-unified/` | **Integrated** | Integrates `agent-lite` with `agentlite-server` into one system with one-command build, contract verification, end-to-end validation, and release/deploy; both halves stay byte-identical copies — zero change to features or logic (see [integration notes](agent-unified/README.md)) |
| `agentd-go/` + `agentd-apk/` | Experimental | Root-resident Go daemon, WebUI, and accessibility bridging approach |
| `tests/`, `tools/` | Tooling | Device test plans, test harnesses, and UI capture tools |
| `docs/` | Documentation | Architecture, security, compatibility, operation records, and dedicated design notes |

## Requirements

### Building Agent Lite / Agent Client

- macOS (the scripts provided by the repository are written for the default macOS Android SDK location);
- JDK 11 or later;
- Android SDK Platform and Build Tools;
- ADB (needed for installation and debugging);
- An Android 11+ device (`minSdk 30`).

The client uses a pure command-line toolchain, with no Gradle, no Kotlin, and no third-party runtime dependencies:

```text
aapt2 → javac --release 11 → d8 → zipalign → apksigner
```

## Quick Start: Agent Lite

### 1. Build

```bash
git clone https://github.com/TQYM/android-global-agent.git
cd android-global-agent/agent-lite
sh build-mac.sh
```

Output:

```text
agent-lite/build/agent-lite.apk
```

If the Android SDK is not in the default location, set it first:

```bash
export ANDROID_HOME=/path/to/Android/sdk
```

### 2. Install

```bash
adb install -r build/agent-lite.apk
```

### 3. Complete Device Setup

1. Open the app;
2. Enable the **Agent Lite Accessibility Service** in system settings;
3. Grant notification, microphone, and battery-optimization-ignore permissions as needed;
4. In the app settings, fill in the compatible endpoint's Base URL, API Key, main model, and optional planning model;
5. Enter a natural-language task and run it.

> The API Key should only be stored in the local configuration. Do not commit real keys to Git, and do not publish an APK that contains keys.

### 4. Optional Development Configuration

`agent-lite/build-mac.sh` supports reading the untracked `agent-lite/dev.local`, for local test builds only:

```bash
API_KEY="your-local-key"
BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
MODEL="qwen3.5-omni-plus"
```

This file is ignored by `.gitignore`. It should be deleted before public builds to ensure the APK contains no development keys.

## Model Interface

The client uses an OpenAI-style compatible HTTP endpoint; the current default configuration targets DashScope:

| Setting | Default or description |
|---|---|
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| Main model | `qwen3.5-omni-plus` |
| Planning model | `qwen3.5-flash`, can be disabled |
| Vision | Can be enabled when the main model supports image input |
| Speech | Depends on the provider: either the model's native audio input or a compatible transcription endpoint |

Providers are not fully consistent in their support for message structure, images, audio, and reasoning parameters. After switching models, validate with a simple task first.

## Building Other Components

### Full Client

```bash
cd agent-client
sh build-mac.sh
adb install -r build/agent-client.apk
```

The output is `agent-client/build/agent-client.apk`. Root and sandbox modes require explicit authorization from the on-device root manager.

### Go Daemon (Experimental)

```bash
cd agentd-go
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -ldflags="-s -w" -o build/agentd .
```

`agentd-go` runs with root privileges and exposes a local control plane, which is significantly riskier than an ordinary app. Unless a review of listen addresses, authentication, data permissions, and network boundaries has been completed, it should be evaluated only on isolated test devices and trusted networks.

## Verified Environments

The current code has been developed and tested in the following environments:

- Android 16 / ColorOS 16 / OnePlus 13T: the zero-root path and the root sandbox path;
- Android 17 / MagicOS / HONOR tablet: the zero-root path for node collection, screenshots, gestures, and Chinese input.

These results do not mean all OEM ROMs are compatible. After a coverage install or a force stop, some MagicOS devices clear the accessibility service binding and the user must re-enable it.

## Known Limitations

- Accessibility screenshots are available from Android 11 onward and may be rate-limited by the system;
- Apps such as WeChat may hide the accessibility node tree, leaving only screenshots and vision coordinates;
- Without root, the agent cannot silently toggle Wi-Fi or Bluetooth, or bypass system permission confirmations;
- System dialogs, payments, verification codes, and authorization agreements must be checked or taken over by the user;
- Multi-window, portrait/landscape switching, OEM background policies, and complex IMEs still require ongoing adaptation;
- Models may misinterpret the interface or generate incorrect actions; a natural-language model must not be treated as a reliable security authorization mechanism.

## Security and Privacy

This project may only be used on devices you own or devices for which you have obtained explicit authorization. Observe the following boundaries:

- Do not capture or bypass `FLAG_SECURE`, DRM, hardware keystores, app sandboxes, or system security mechanisms;
- Do not attempt to bypass restricted settings, Play Protect, permission confirmations, or third-party anti-tampering;
- Do not allow the agent to confirm high-risk steps such as payments, transfers, verification codes, and privacy agreements on its own;
- Screenshots, on-screen text, task content, and audio recordings may be sent to the model service configured by the user; assess the provider's privacy policy yourself;
- API keys, signing files, test APKs, logs, screenshots, and device identifiers must not be committed to public repositories;
- Root components should be used only on isolated test devices, and should follow the principles of least privilege and local access.

For the historical security review, see [`SECURITY_REVIEW_2026-09-05.zh-CN.md`](SECURITY_REVIEW_2026-09-05.zh-CN.md). The project is still in a development and research stage and is not recommended for unattended production environments or any high-risk business.

## Documentation

- [`docs/PROJECTS.zh-CN.md`](docs/PROJECTS.zh-CN.md): **project registry and separation rules** — which sub-projects live in this workspace, which one a path belongs to, and how a session declares its project ID; read this first when opening a new session;
- [`docs/DEVELOPMENT_MANUAL.zh-CN.md`](docs/DEVELOPMENT_MANUAL.zh-CN.md): **development manual — how to reach the project's dedicated server** (SSH alias `aliyun-ecs`, service endpoints, deploy and ops, API list); belongs to `agentlite-server`;
- [`PROJECT_OVERVIEW.zh-CN.md`](PROJECT_OVERVIEW.zh-CN.md): project architecture, technical decisions, and current status;
- [`agent-lite/COMPATIBILITY.zh-CN.md`](agent-lite/COMPATIBILITY.zh-CN.md): Android 11+ compatibility and installation notes;
- [`docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md`](docs/DOUBAO_SPEC_ADAPTATION.zh-CN.md): Agent Lite interaction-spec implementation;
- [`docs/INTERACTION_LOGIC_SPEC.zh-CN.md`](docs/INTERACTION_LOGIC_SPEC.zh-CN.md): software interaction logic specification (SPEC-INTERACT-001; companion skill at `skills/software-interaction-logic/`);
- [`docs/PROGRESS_2026-09-06.zh-CN.md`](docs/PROGRESS_2026-09-06.zh-CN.md): the latest on-device test run and to-dos;
- [`docs/AGENT_FRAMEWORKS.zh-CN.md`](docs/AGENT_FRAMEWORKS.zh-CN.md): third-party agent framework selection and the DSH direct-drive manual;
- [`SECURITY_REVIEW_2026-09-05.zh-CN.md`](SECURITY_REVIEW_2026-09-05.zh-CN.md): security review report.

## Development Notes

Before committing code, run at least the checks corresponding to the scope of your change:

```bash
# Agent Lite build
cd agent-lite && sh build-mac.sh

# Full client build
cd agent-client && sh build-mac.sh
```

Do not use `git add -A` to blindly commit the entire working directory; check `git status` first and exclude keys, APKs, ZIPs, device logs, uploaded files, and one-off operations scripts.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
