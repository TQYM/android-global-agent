# Trigger, Speech and Visual-State Integration

This document is the safe integration boundary for adding a user-triggered
session to the existing Agent loop. It is deliberately narrower than a
privilege-escalation design: it does not grant `uinput` access, disable
SELinux, intercept protected input without a platform build, or collect data
from applications that the operator does not own.

## Existing Extension Points

The portable core currently has these boundaries:

| Concern | Existing API | Extension rule |
| --- | --- | --- |
| Screen/window perception | `PerceptionBackend::Capture()` | Return a bounded `Perception`; secure/protected content remains unavailable. |
| Decision | `DecisionEngine::Decide(graph, perception)` | Add session input as an explicit, ephemeral context; do not read global mutable state from a decision thread. |
| Input | `InputInjector::Inject()` | Keep the existing gesture validator and platform permission checks. |
| Memory | `StateGraph` + `StateStore` | Persist hashes and outcomes, never raw microphone text or third-party payloads. |
| Platform bridge | `IAgentService`/`IAgentBridge` | Accept only bounded DTOs and reject untrusted callers in the native service. |

`src/platform/aosp/main_aosp.cpp` currently installs `NoopDecision`, so adding a
trigger or transcript does not cause an action until an explicitly configured
decision implementation consumes it.

## Session Contract

A trigger starts a short-lived session. The source must be one of:

* a platform/framework integration built from the matching AOSP tree; or
* an explicit user action in the bridge application.

An ordinary application cannot observe `KEYCODE_POWER` globally. The input path
is `InputReader` -> `InputDispatcher` -> policy (`PhoneWindowManager` in
`frameworks/base/services/core/java/com/android/server/policy/`) and the policy
consumes the power key before application dispatch. A framework change or an
LSPosed hook is therefore an optional device-specific integration, not a
portable permission workaround. A power-key integration must preserve the
default long-press/shutdown behavior when the agent is disabled, the keyguard
policy rejects the session, or the trigger handler times out.

The proposed internal event shape is:

```text
TriggerEvent {
  source = POWER_LONG_PRESS | EXPLICIT_UI;
  monotonic_ns;
  press_duration_ms;       // must be >= 2000 for POWER_LONG_PRESS
  display_id;
  keyguard_locked;
  user_confirmed;          // false until the user-visible affordance confirms
}
```

Validation rules:

1. Reject a non-monotonic timestamp, a duration outside `[2000, 10000]` ms, an
   invalid display id, or a duplicate active session.
2. Do not treat a broadcast received from an arbitrary UID as a trigger. The
   native Binder service should check the caller UID (platform/system UID or
   the installed bridge UID) before accepting it; otherwise return
   `EX_SECURITY`.
3. A locked keyguard is a policy input, not a bypass signal. The default
   configuration refuses microphone capture and cross-application actions while
   locked or while the display is non-interactive.
4. Keep a session deadline (recommended 15 s) and clear its transcript and
   visual state on timeout, cancellation, Binder death, or process restart.

## Offline STT Boundary

The bridge, rather than the root daemon, owns microphone capture. A compatible
Android 14 implementation may use Vosk or another offline engine, subject to
its model license and ABI. The service must declare and request:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

and start a user-visible foreground service with
`android:foregroundServiceType="microphone"`. Android 14 background-start and
while-in-use microphone rules still apply; root does not make an invisible
recording legal. On a locked screen the service must stop or require an
explicit, user-visible unlock/confirmation flow. No raw audio is sent to the
native daemon.

The STT worker should use a bounded pipeline:

```text
AudioRecord (bridge worker) -> 16 kHz PCM queue (<= 1 s)
  -> offline recognizer thread -> partial/final text
  -> Binder DTO (<= 4096 UTF-8 bytes) -> ephemeral agent context
```

The queue is bounded and drops the oldest partial result when full. Final text
is normalized and rate-limited (for example, one update per 100 ms and at most
32 final updates per session). The recognizer is closed immediately after
silence (recommended 800 ms), a hard session timeout, cancellation, or an
error. Do not persist the text in `StateStore`; if analytics are needed, store
only a keyed hash and a length.

The existing `IAgentService` can be extended with a narrow method such as:

```aidl
void submitTranscript(in TranscriptChunk chunk);
void setSessionState(int state);
```

where `TranscriptChunk` contains a sequence number, `isFinal`, and a bounded
string. Before adding these methods to a shipping interface, enforce the caller
UID in `AgentBinderService` and add a protocol version. Unknown fields must not
be forwarded as arbitrary `Parcelable` objects; Binder transaction limits and
`enforceNoDataAvail()` make that pattern fragile.

## Visual State and Overlay

The visual feedback is a state indicator, not a covert overlay:

```text
IDLE -> LISTENING -> THINKING -> EXECUTING -> FEEDBACK -> IDLE
                    \-> ERROR -> IDLE
```

The bridge may render a thin edge effect using a user-visible
`TYPE_APPLICATION_OVERLAY` window after the user grants
`SYSTEM_ALERT_WINDOW`. Recommended flags are `FLAG_NOT_TOUCHABLE`,
`FLAG_NOT_FOCUSABLE`, and a bounded alpha; the view must not consume input or
claim focus. A `SurfaceView`/`RuntimeShader` implementation is optional, but
the shader must be stopped when the session ends and must not run continuously
in the background. Overlaying the keyguard, status bar, or secure content is
subject to platform/OEM policy and should be treated as a best-effort visual
cue, not a permission bypass.

The native service should publish only the enum and a monotonically increasing
sequence number. The Java bridge ignores stale states and resets to `IDLE` on
Binder death. This avoids coupling the decision loop to a UI thread and lets
SystemUI/overlay restarts recover without replaying microphone data.

## Implemented Core Shape

The repository now contains an ephemeral, thread-safe session context in
`include/global_agent/session_context.h` and `src/session_context.cpp`. It
implements the validation and lifecycle rules below without opening any
device, microphone, or input node:

```cpp
SessionContext context;
TriggerEvent trigger{
    .source = TriggerSource::kPowerLongPress,
    .monotonic_ns = monotonic_ns,
    .press_duration_ms = 2000,
    .display_id = 0,
    .keyguard_locked = false,
    .user_confirmed = true,
};
context.Begin(trigger, clock_now, &error);
const SessionSnapshot input = context.Snapshot();
// Pass `input` to an explicitly enabled, session-aware DecisionEngine.
```

The current `DecisionEngine` signature remains unchanged so existing products
do not silently start consuming user text. A future session-aware decision
adapter can copy a `SessionSnapshot` at the start of `Step()`, clear it after a
final feedback decision, and never block the Binder callback on screen capture
or input injection. `SessionContext` enforces increasing trigger/partial
sequence numbers, strict UTF-8 and 4096-byte limits, the 15-second timeout,
allowed visual-state transitions, and transcript wiping/clearing on
cancel/expiry.
The current pending-action logic supplies the appropriate place to transition
`EXECUTING` to `FEEDBACK` when that adapter is explicitly enabled.

## Init and SELinux

`android/init/global-agent.rc` may continue to start the daemon after
`sys.boot_completed`, but it must not start microphone capture by itself. The
bridge foreground service owns that lifecycle. `restart_period` is an init
backoff hint, not a 50 ms guarantee; exact restart latency requires device
measurement and can be delayed by init crash-rate limiting.

No new rule should grant `agentd` or `system_app` access to `uinput_device`,
`sysfs`, or a permissive domain. `chcon` is appropriate only for the project's
own data/executable paths and does not replace compiled sepolicy. Keep the
existing `agentd` and `global_agent_bridge` domains minimal.

## Verification Plan

Host tests:

* reject malformed/oversized trigger and transcript DTOs;
* verify duplicate triggers, sequence rollback, session timeout, and context
  clearing;
* verify visual-state transitions and Binder-death reset without persisting
  raw text;
* run ASan/UBSan and CTest through `tools/run-tests.sh`.

Android/API 34 tests on an authorized emulator or engineering device:

* grant microphone and overlay permissions explicitly through Settings;
* verify the foreground-service notification and Android 14 start restrictions;
* trigger a session while unlocked, then cancel and verify `AudioRecord` closes;
* lock the device and verify no recording or cross-app action starts;
* kill/restart the bridge and daemon, then verify visual state returns to `IDLE`
  and the mmap store contains no transcript;
* measure trigger-to-listening and final-STT-to-decision P50/P95 latency. The
  200 ms target is a budget, not a hard real-time guarantee.

A full AOSP build must additionally compile the exact framework/policy revision,
check the power-key integration against `PhoneWindowManager`, and exercise
SELinux enforcing mode. Do not infer compatibility from a different OEM ROM.

## Confidence and Limits

* Trigger dispatch and keyguard boundaries: **0.90** for AOSP 14 mainline; OEM
  policy hooks require source and device testing.
* Android 14 microphone foreground-service requirements: **0.95**; exact OEM
  restrictions require testing.
* Overlay state machine and Binder recovery: **0.90**; rendering performance
  depends on the device GPU.

Pure Root cannot perfectly evade Google Play Protect, cannot retrieve keys from
TEE/StrongBox, and cannot decrypt financial-app traffic. Any design claiming
otherwise is incorrect. AccessibilityService plus explicit user actions is the
fallback when a platform build or authorized bridge is unavailable.
