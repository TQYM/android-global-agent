# Validation Record

Date: 2026-07-19

## Iteration 1

- Fixed mmap commit marker atomics for Android NDK libc++.
- Host ASan/UBSan build and tests passed.
- API 34 / arm64-v8a NDK stub build passed.
- Stub changed to static libc++ so it is a single-file debug binary.

## Iteration 2

- Structured AIDL compiled with SDK 35 `aidl --lang=java --structured -Werror`.
- NDK AIDL headers generated with `--min_sdk_version=34`.
- Generated Java parcelables compiled against `android-35/android.jar`.
- XML, shell syntax and unsafe-pattern checks passed.
- Platform metadata moved out of daemon-side `dumpsys` polling; the bridge uses
  bounded task/process/display snapshots.

## Iteration 3: Emulator

Emulator properties:

```text
ro.build.version.sdk=35
ro.build.fingerprint=google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys
getenforce=Enforcing
```

The API 34 arm64 stub was pushed to `/data/local/tmp/global-agentd` and ran as
root. It generated a validated five-frame gesture and persisted the mmap state.
The process was killed with `SIGKILL`, restarted, and loaded the last valid
generation without corruption. The VM is API 35, so this validates the portable
ABI/state layer, not AOSP 14 private `libgui` or platform Java APIs.

## Iteration 4

- Added single-writer flock and `O_NOFOLLOW` to the state file.
- Added CRC fallback, graph-limit, bounded subprocess and multi-pointer gesture
  tests.
- Changed Java input injection to an asynchronous, paced queue with cancellation.
- Added pending-action feedback settlement so a long gesture does not block the
  200 ms perception/dispatch deadline.
- Rebuilt host, NDK stub and emulator deployment after the changes.

## Iteration 5: AOSP boundary and recovery re-check

- Re-checked Android 14 r1/r30 Gitiles headers for `ScreenshotClient`,
  `DisplayCaptureArgs`, `ScreenCaptureResults::fenceResult` and
  `FenceResult`; corrected the platform adapter to the root-authorized
  `ScreenshotClient::captureDisplay(DisplayId, listener)` path and documented
  the private ABI.
- Removed the Magisk `/dev/uinput` relabel operation and removed the bridge APK's
  unnecessary `coredomain` attribute. The seapp rule now requires
  `isPrivApp=true` and `privapp_data_file`.
- Re-ran `tools/check-project.sh`, `tools/run-tests.sh` and
  `tools/build-android-stub.sh`; all passed.
- On the running `Codex_Pixel_7_API_35` emulator (`getenforce=Enforcing`),
  pushed the API 34 arm64 stub, ran a long-lived process, killed it with
  `SIGKILL`, and ran it again against the same state file. The recovery run
  reported `generation=17 nodes=2 edges=1` and generated the validated five-frame
  demo gesture. This remains a portable-core test, not a full AOSP 14 private
  `libgui` or device sepolicy build.

## Iteration 6: privilege and label minimization

- Added a product-installed privapp allowlist containing only
  `android.permission.REAL_GET_TASKS`; `INJECT_EVENTS` remains signature-only
  and requires the platform certificate.
- Removed the daemon's unused `input` supplementary group and removed the
  bridge APK's unnecessary `coredomain` attribute. The custom seapp rule now
  matches only `isPrivApp=true` and uses `privapp_data_file`.
- Aligned the optional Magisk staging labels with `global_agent_data_file` and
  `agentd_exec`; unknown custom labels are skipped rather than replacing stock
  policy. `/dev/uinput` is never relabeled.
- Re-ran static safety checks, AIDL generation/Java compilation, host tests and
  the API 34 arm64 NDK stub build successfully. Checks must be run serially
  because the AIDL and host scripts share generated build directories.
- Repeated the emulator recovery check with a fresh state file: after a
  long-lived process was killed with `SIGKILL`, the next four-iteration run
  loaded the file and reported `generation=31 nodes=2 edges=1`.

## Iteration 7: SurfaceFlinger permission-path audit

- Verified AOSP 14 `SurfaceFlinger.cpp`: the `DisplayCaptureArgs` overload checks
  `READ_FRAME_BUFFER`/matching UID, while `captureDisplayById` admits
  `AID_ROOT`, `AID_GRAPHICS`, `AID_SYSTEM` and `AID_SHELL` and keeps secure and
  protected capture disabled.
- Kept the adapter on
  `ScreenshotClient::captureDisplay(display_ids.front(), listener)` and added a
  static regression check against the wrong overload. Later API-34 deprecation
  warnings are locally suppressed for this compatibility wrapper.
- Re-ran static checks, AIDL generation, ASan/UBSan CTest, API 34 arm64 build and
  emulator demo; all passed (`generation=32 nodes=2 edges=5`). The emulator
  still validates only the portable core, not a platform `libgui` link.

## Iteration 8: cancellable input pacing

- Replaced one-shot Java `SystemClock.sleep` frame waits with a 4 ms-bounded
  cancellable wait loop and a second cancellation check before each injection.
  A daemon/SystemUI disconnect can therefore cancel a long gesture without
  waiting for its full frame delay.
- Re-ran AIDL/Java generation, ASan/UBSan CTest, API 34 arm64 stub build and
  static safety checks successfully. The emulator recovery result in iteration
  7 remains valid because this change is in the platform bridge, which is not
  part of the portable NDK stub.

## Iteration 9: platform-stub boundary

- A direct `javac` pass with public `android-35/android.jar` intentionally does
  not compile the bridge: that SDK jar omits hidden platform symbols used by a
  platform-signed app (`ServiceManager`, injection constants,
  `MotionEvent.setDisplayId` and hidden `RunningTaskInfo` fields). The product
  build must use Soong `platform_apis: true` and the target framework stubs;
  this is documented rather than “fixed” with reflection.
- A later attempt to pull a framework jar from the emulator was blocked because
  the local ADB server could not start under the current sandbox. The earlier
  Enforcing-emulator NDK gesture/recovery results remain recorded; a fresh
  platform-bridge smoke requires an environment with a working ADB server.

## Iteration 10: requested VM re-check

- Attempted to reconnect to `Codex_Pixel_7_API_35`. The local ADB server failed
  before device discovery with `could not install *smartsocket* listener:
  Operation not permitted`; process enumeration was also unavailable in the
  current sandbox.
- Re-ran the host fallback:
  `build/host/global-agentd --state /tmp/global-agent-vm-fallback.bin
  --iterations 4 --interval-ms 5 --demo-action` produced a validated five-frame
  gesture and `generation=1 nodes=2 edges=0`. This is explicitly not a new VM
  result. A VM rerun requires an environment where ADB can start.

## Iteration 11: trigger/STT/visual design audit

- Added AOSP 14 power-key path audit covering InputReader, InputDispatcher,
  JNI, InputManagerService, PhoneWindowManager and SingleKeyGestureDetector,
  including QPR3 private-signature drift and FactoryTest/NO_CONFIRM boundaries.
- Added offline STT and edge-overlay design with Vosk/AudioRecord bounded queues,
  microphone foreground-service rules, RuntimeShader API 33+ and public overlay
  limits. No microphone, power-key hook, LSPosed module or SELinux/uinput
  bypass was added to the production code.
- Added a session/trigger integration contract documenting bounded Binder DTOs,
  user confirmation, lockscreen denial, visual-state reset and test cases.
- Re-ran `tools/run-tests.sh` and `tools/build-android-stub.sh`; host ASan/UBSan,
  CTest, AIDL/static checks and API 34 arm64 build passed.

## Iteration 12: bounded session context

- Added `SessionContext` for opt-in trigger, transcript and visual-state
  lifecycle. It rejects unconfirmed/locked triggers, malformed UTF-8, replayed
  sequence numbers and oversized text; it wipes text on cancellation/expiry.
- Added host tests for duplicate triggers, final transcript handling, state
  transitions and the 15-second deadline.
- Re-ran `tools/run-tests.sh`, `tools/build-android-stub.sh` and
  `tools/check-project.sh`; all passed. This remains a portable-core test and
  does not imply microphone, power-key or overlay access on an emulator.

## Iteration 13: operations manual

- Added `docs/OPERATIONS_MANUAL.md` covering host/NDK/emulator use, AOSP product
  build, optional power-key/STT/overlay integration, runtime checks, rollback,
  troubleshooting and security acceptance.
- Linked the manual from `README.md`. No production behavior or privilege policy
  changed in this documentation-only iteration.
- Re-ran the existing project checks after the manual update; the manual's
  commands remain subject to the target device's ADB, framework and policy
  prerequisites.

## Iteration 14: development log and roadmap

- Added `docs/DEVELOPMENT_LOG.md` as the maintained implementation-status and
  engineering-history summary, while keeping this file as the detailed
  validation record.
- Added `docs/ROADMAP.md` with staged P0-P5 work, dependencies, blockers,
  completion criteria, explicit non-goals and the product decisions required
  before device/session work begins.
- Recorded the current two-second gesture limit versus the older ten-second
  security-document statement as a contract mismatch to resolve in P0.
- Linked both documents from `README.md` and ran `git diff --check` plus
  `tools/check-project.sh`; both passed. No runtime code or permission boundary
  changed in this documentation-only iteration.

## Iteration 15: P0 contract and cancellation hardening

- Centralized the native gesture limits (256 frames, five pointers, two-second
  duration) and used the same constants at the AOSP bridge boundary.
- Tightened Java `actionIndex` validation so every frame rejects negative or
  out-of-range indices consistently with native validation.
- If perception capture fails while an asynchronous gesture is pending, the
  loop now requests `ACTION_CANCEL` and drops the pending feedback record.
- Added host coverage for the exact two-second boundary, over-limit rejection,
  capture-failure cancellation, and the ten-second power-trigger boundary.
- No new permission, SELinux rule, capture mode, or persistence field was added.

- `git diff --check` passed.
- `tools/run-tests.sh` passed (ASan/UBSan, CTest, AIDL/static checks).
- `tools/build-android-stub.sh` passed (API 34 arm64-v8a).
- `tools/check-project.sh` passed.
- No AOSP source-tree or device bridge smoke test was available; the private
  `libgui` and platform framework conditions remain unverified.

## Iteration 16: single-frame API and reproducible metadata

- Renamed the transient perception field to `single_frame_visual_hash` and the
  AOSP adapter class to `AospSingleFrameCapture`. The serialized state-node
  field remains `visual_hash`; its binary layout did not change.
- Added AgentLoop coverage for bridge rejection and a deadline that expires
  before injection. Added SessionContext coverage for invalid state transitions
  and transcript clearing on cancellation.
- Extracted the platform-independent Java `GestureValidator` and ran eight JVM
  checks from `tools/check-aidl.sh`. This found and fixed null boundary-frame
  exceptions and acceptance of a `-1 ms` first-frame timestamp.
- Added `tools/validation-metadata.sh` and included it in static project checks.
  The 2026-07-19 run reported:

```text
git_commit=30b9da2a0d8cb1ae8b398cfcff9c536edcc4e680
git_worktree=dirty
host=Darwin 25.6.0 arm64
host_os_version=26.6
cmake=cmake version 3.22.1-g37088a8
ninja=1.10.2
host_cxx=Apple clang version 21.0.0 (clang-2100.1.1.101)
javac=javac 21.0.2
android_build_tools=35.0.0
android_ndk=26.1.10909125
android_stub_target=android-34/arm64-v8a
device=unavailable
```

- `tools/run-tests.sh`, `tools/build-android-stub.sh`,
  `tools/check-project.sh`, and `git diff --check` passed.
- Independent review was not produced: the configured review proxy rejected
  the request with HTTP 403 before Gemini/DeepSeek execution. The blocked
  report is recorded in `outputs/ai-review.md` and is not an approval.
- No permission or persistence-format change was made. A full AOSP build and
  device validation remain unavailable.

## Iteration 17: KernelSU debug package

- Added KernelSU/Magisk-compatible `customize.sh`, `post-fs-data.sh`, and
  `action.sh` around the API 34 arm64 portable-core stub. Installation rejects
  non-arm64 and pre-API-34 devices; no boot-time daemon or runtime sepolicy rule
  is installed.
- Added `tools/package-kernelsu.sh` and generated
  `GlobalAgent-KernelSU-v0.2.0-arm64-debug.zip` on the desktop.
- Archive validation passed with seven root-level/module files, executable mode
  preserved for scripts and binary, and no macOS metadata entries.
- Packaged binary: AArch64 Android PIE using `/system/bin/linker64`.
- SHA-256:
  `1b044a541790c47ebb6c5fa1728dd95175fabe38a88e0ebd1247b51c1521c037`.
- This is a synthetic portable-core smoke-test module, not the full AOSP
  `libgui`/platform-bridge product.

## Iteration 18: KernelSU WebUI recovery

- Added the required `webroot/index.html` entry plus offline CSS, JavaScript,
  and pinned Lucide SVG assets. The previous archive had no WebUI entry, so
  KernelSU Manager had no page to load.
- Added a bounded asynchronous wrapper for KernelSU's injected `ksu.exec`
  interface. Missing bridge, callback timeout, command failure, page errors,
  and rejected promises are rendered as states instead of closing the page.
- Removed recursive install-time permission changes over `webroot`; KernelSU
  remains responsible for its WebUI permissions and SELinux context.
- Browser QA passed at the default desktop size and a 390 x 844 mobile viewport:
  no horizontal overflow, no console errors, tabs work, and no-bridge mode is
  read-only. A local mock of the documented bridge verified status refresh,
  smoke-test execution, log rendering, and button recovery.
- `tools/run-tests.sh`, `tools/build-android-stub.sh`, `tools/check-project.sh`,
  Node syntax checking, `git diff --check`, and `unzip -t` passed.
- Generated `GlobalAgent-KernelSU-v0.3.0-arm64-debug.zip` with 21 entries.
  SHA-256:
  `24db6598edd1235ba59f0c9a9d4cff9f1421148cbb2452f12e5d9aa01be661a7`.
- Device-level WebView/KernelSU testing remains required because ADB is not
  available in this workspace.

## Iteration 19: explicit screenshot and tap diagnostics

- Added a KernelSU WebUI device-tools tab using Android's stock
  `/system/bin/screencap -p` and `/system/bin/input touchscreen tap` commands.
  Both require an explicit user action; no service or automatic policy invokes
  them.
- Display bounds come from `wm size`. X/Y values must be finite non-negative
  integers inside the active bounds, with a 100000 hard fallback limit when the
  display size cannot be read.
- Screenshot files use a module-private runtime directory with 0700/0600 modes,
  are converted to an in-memory Blob, and are deleted after image load, on
  preview removal, and on page hide. Secure/DRM pixels remain subject to Android
  screencap redaction.
- Browser QA with a documented-API mock passed at 390 x 844: 1080 x 2400 bounds
  were applied, `(1080, 1200)` was rejected, `(100, 200)` was accepted, capture
  loaded through a Blob URL, preview removal restored the empty state, and no
  warning/error console entries were emitted.
- `tools/run-tests.sh`, `tools/build-android-stub.sh`, `tools/check-project.sh`,
  Node syntax checking, `git diff --check`, and archive integrity passed.
- Generated `GlobalAgent-KernelSU-v0.4.0-arm64-debug.zip`. SHA-256:
  `4bb7fc975b69a6614485917f9c9f979ae80a9c78bc076227a6e3a6b2d985183f`.
- No physical KernelSU device was available, so OEM `screencap`, `input`,
  display rotation, multi-display behavior, and secure-surface output remain
  unverified.

## Iteration 20: authenticated session AIDL boundary

- Added structured trigger, transcript and session-status DTOs plus protocol
  version `1`. Native mutations enforce caller UID, session id, transcript
  sequence, state transitions and bounded values; final text transitions to
  `THINKING`, while timeout and shutdown clear the ephemeral session.
- Added monotonic bridge callbacks and `AgentSessionClient`. It tolerates the
  valid race between an oneway callback and the synchronous method return,
  rejects conflicting same/older revisions, and resets its baseline after
  Binder death.
- Split the AOSP-only service-manager registration from the service logic.
  `tools/build-aidl-boundary-stub.sh` now regenerates both AIDL backends, runs
  eight gesture and eight session-validator JVM checks, then compiles the
  native service logic with API 34 arm64 NDK, C++20 and `-Werror`.
- The standard NDK intentionally has no `android/binder_manager.h`; therefore
  service registration, private `libgui`, platform Java symbols and the final
  link remain target-Soong gates. No device or AOSP checkout was available.
- No microphone, overlay, uinput, secure capture or persisted transcript was
  added. Production decision behavior remains `NoopDecision`.

## Iteration 21: explicit bridge session Activity

- Added `AgentSessionActivity` as a launcher entry. Start is enabled only when
  the native session service is connected, the display is interactive, the
  keyguard is unlocked, and no session/request is active.
- Added bounded final-text submission, status display and explicit cancel. An
  Activity leaving the foreground queues cancellation even if a begin request
  has not returned yet; configuration changes retain the visible session.
- Changed `AgentSessionClient` to support multiple listeners and publish a null
  disconnected state. This prevents Binder death from appearing as a usable
  placeholder IDLE connection.
- Added `SessionEntryPolicy` and 16 JVM checks covering connection, keyguard,
  display, active/request state, UTF-8 byte limits and cancellation.
- `tools/run-tests.sh`, `tools/build-android-stub.sh`,
  `tools/build-aidl-boundary-stub.sh`, `tools/check-project.sh`,
  `tools/validation-metadata.sh`, and `git diff --check` passed.
- Validation metadata found `emulator-5554`: API 35 arm64 userdebug,
  `ro.debuggable=1`, root adbd, SELinux Enforcing, fingerprint
  `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`,
  SPL `2024-09-05`.
- The API 34 arm64 portable stub ran on that emulator. A long-running process
  was killed with `SIGKILL`; the next run against the same state file reported
  `generation=34 nodes=2 edges=1` and a validated five-frame demo gesture.
- This device evidence covers only the portable ABI/state layer. The launcher
  Activity, platform certificate, private `libgui`, Binder registration and
  Android 14 product policy remain target-Soong/device gates.

## Remaining gate

A full AOSP 14 build on the target device source drop is still required to
compile `libgui`, `libbinder_ndk`, generated platform AIDL and custom SELinux
policy. The current workspace does not contain an AOSP checkout. Remote tag
metadata indicates later API-34 tags exist, but this session's network policy
blocked fetching their source; no unverified security-patch commit or later-tag
permission behavior is claimed. Re-run the SurfaceFlinger permission check from
the target checkout and record its fingerprint/SPL before deployment.
