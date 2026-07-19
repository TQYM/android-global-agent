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

## Remaining gate

A full AOSP 14 build on the target device source drop is still required to
compile `libgui`, `libbinder_ndk`, generated platform AIDL and custom SELinux
policy. The current workspace does not contain an AOSP checkout. Remote tag
metadata indicates later API-34 tags exist, but this session's network policy
blocked fetching their source; no unverified security-patch commit or later-tag
permission behavior is claimed. Re-run the SurfaceFlinger permission check from
the target checkout and record its fingerprint/SPL before deployment.
