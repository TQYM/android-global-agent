# Security Model

## Trust boundary

The native daemon is privileged because it talks to SurfaceFlinger and owns the
state file. It does not parse arbitrary network input and does not receive raw
Parcelable objects from applications.

The Java bridge is platform-signed and is the only component allowed to call
the hidden InputManager injection method. The native-to-Java AIDL contract is
bounded to 256 frames, five pointers, a two-second gesture duration and finite
coordinates. A power-key trigger has a separate 2--10 second press-duration
range; that range does not extend the duration of an injected gesture.

The optional KernelSU debug WebUI is a separate manual diagnostic surface. It
may invoke Android's stock `screencap` and single-point `input tap` commands only
after an explicit button press. It does not enable an automatic policy, retain
screenshots after preview loading, or weaken secure/DRM surface handling. The
production AOSP agent continues to require the platform-signed bridge and
structured gesture validation.

## Required invariants

- `captureSecureLayers` remains false.
- No `setenforce 0`, permissive domain or blanket `system_app` uinput rule.
- No arbitrary command execution or shell-form command strings.
- No unknown `Bundle` or Parcelable forwarding across Binder.
- No automatic action when semantic confidence is insufficient.
- A failed partial gesture is followed by `ACTION_CANCEL`.
- The state file contains hashes and transition metadata, not screenshots,
  secrets, tokens or app-private payloads.

## Known residual risks

- Root, an unlocked bootloader, platform modifications and LSPosed are
  detectable.
- AOSP-private `libgui` APIs can change across releases and OEM branches.
- Top-task PID matching is approximate for applications with multiple
  foreground processes.
- `RunningTaskInfo` identifies an Activity container, not Fragment or Compose
  navigation state.
- Frequent screen composition has power and thermal cost.
- A clean independent AI review does not replace a device-specific SELinux and
  framework source review.

Pure root cannot reliably evade Google Play Protect and cannot retrieve keys
from a hardware TEE or StrongBox. Claims of decrypting arbitrary financial-app
transport solely through this architecture are false.
