# AOSP 14 Integration

## Source placement

Place the repository under a system or system_ext source directory. Do not build
the native daemon as a vendor binary: it links private platform libraries such
as `libgui` and `libbinder`.

The implementation targets the Android 14 API shape represented by:

- `frameworks/native/libs/gui/include/gui/SurfaceComposerClient.h`
- `frameworks/native/libs/gui/include/gui/DisplayCaptureArgs.h`
- `frameworks/native/libs/gui/include/gui/ScreenCaptureResults.h`
- `frameworks/native/libs/gui/include/gui/SyncScreenCaptureListener.h` (reference
  only; its `waitForResults()` is intentionally not used because it waits
  forever)
- `frameworks/native/libs/ui/include/ui/FenceResult.h`
- `frameworks/base/services/core/java/com/android/server/input/InputManagerService.java`
- `frameworks/base/core/java/android/hardware/input/InputManager.java`

The Android 14 adapter calls
`android::ScreenshotClient::captureDisplay(android::DisplayId, ...)`.
The DisplayId overload reaches `captureDisplayById`, whose AOSP 14 service path
explicitly permits a root caller and forces secure-layer capture off. The result
is delivered through `gui::ScreenCaptureResults::fenceResult` and `buffer`; the
adapter waits for the Binder callback and fence only for the remaining step
budget. The `DisplayCaptureArgs` overload is a different permission path and
requires `READ_FRAME_BUFFER` (or a matching capture UID), so it is not suitable
for a root daemon with the default `uid = -1`. Do not mix headers or shared
objects from another branch. `SurfaceComposerClient::captureDisplay` is not an
Android 14 mainline entry point; code using that symbol must be treated as an
OEM/private fork and verified in that source drop.

OEM branches may change signatures. Compile against the device source drop and
repair adapter code at the boundary rather than copying private shared objects
between builds.

## Product configuration

```make
PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    GlobalAgentModelGateway \
    privapp-permissions-com.example.globalagent
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += \
    system_ext/global_agent/android/sepolicy
```

The bridge is platform-signed and persistent. It registers with the native
`global_agent` Binder service, publishes top-task metadata, observes a fixed
Settings.Global allowlist, and performs validated InputManager injection.
Injection is queued on a dedicated bridge thread and paced by event timestamps;
the native perception loop is not blocked for the duration of a swipe.
Do not compile the bridge against the public SDK `android.jar`: the bridge uses
hidden platform symbols intentionally exposed by Soong `platform_apis: true`.
Use the exact target framework stubs and platform certificate in the AOSP build;
reflection is not a compatibility strategy.

`GlobalAgentModelGateway` is a separate app UID and uses the standard `shared`
certificate in this scaffold rather than the platform certificate. A production
product may replace that with a dedicated gateway certificate. Its manifest
requests only `INTERNET`, uses system trust anchors with cleartext disabled, and
exposes a root/shell-only public-config import call. It must not receive the
bridge certificate, `INJECT_EVENTS`, frame-capture permissions or access to
`/data/misc/global_agent`.

## SELinux verification

Start in enforcing mode. Inspect only denials produced by the agent's expected
operations:

```sh
adb shell su -c 'dmesg | grep "avc: denied" | grep -E "agentd|global_agent"'
adb shell su -c 'ls -lZ /system_ext/bin/global-agentd /data/misc/global_agent'
adb shell service check global_agent
```

Do not feed the entire denial log to `audit2allow`. Each permission must map to
a documented capture, Binder, state-file or system-service operation.

## Device checks

1. Confirm `GlobalAgentBridge` has the platform certificate.
2. Confirm `global_agent` is registered and the bridge reconnects after daemon
   restart.
3. Verify a normal screen produces changing visual hashes.
4. Verify secure/DRM content is black or rejected.
5. Kill SurfaceFlinger on a disposable engineering build and verify the loop
   pauses, retries and recovers without injecting input.
6. Kill `global-agentd`; stock init should restart it on the configured
   second-level backoff. Do not assert a 50 ms guarantee. `restart_period` is
   expressed in seconds and init rate limiting can dominate after repeated
   crashes.

## Moving to a streaming virtual display

The initial platform adapter (`AospSingleFrameCapture`) uses asynchronous
single-frame `ScreenshotClient::captureDisplay`, which is easier to validate.
For continuous 30/60 fps capture, replace only `AospSingleFrameCapture` with a
persistent virtual Display + BufferQueue consumer. Keep H.264/PNG outside the
low-latency path and retain `captureSecureLayers = false`.
