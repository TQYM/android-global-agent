# Model API Gateway Boundary

## Current status

The production AOSP daemon still uses `NoopDecision`. The project does not yet
send network requests and does not store an API key. Internal AIDL/Binder APIs
are implemented; an external model HTTP API is not production-ready.

## Required architecture

The model client must be a separate, non-privileged application/process with
`INTERNET` only. It must not share the bridge UID, hold `INJECT_EVENTS`, access
SurfaceFlinger, read the Agent state file, or receive screenshots/raw PCM.

The privileged bridge sends a bounded request containing only session id,
revision, final user transcript, focused package/activity and allowlisted UI
candidates. The gateway returns a bounded intent DTO. Coordinates and gestures
are produced and validated locally; a remote response never directly invokes
input injection.

## Credential boundary

- Store only a credential alias in configuration.
- Generate an AES key in Android Keystore and encrypt the provider credential
  before storing it in app-private preferences.
- Never place credentials in WebUI, module properties, AIDL, logs, native mmap,
  shell command lines or screenshots.
- Require HTTPS, reject URL user-info/query/fragment credentials, and define a
  provider-specific certificate policy before deployment.

## Response boundary

`ModelGatewayPolicy` rejects percent-encoded, backslash-containing or non-ASCII
endpoint strings before URI parsing to avoid normalization ambiguity. Protocol
version 1 accepts only a session/revision-bound
intent enum, confidence in `[0,1000]`, bounded target text and an explicit
confirmation flag. Irreversible actions require confirmation. Unknown fields,
coordinates, arbitrary JSON objects and provider tool calls are not forwarded
to the input bridge.

## Remaining work

1. Select provider and authentication type: API key, OAuth or device-bound
   token.
2. Create the separate Android app/service and signature-protected narrow AIDL.
3. Implement Android Keystore credential encryption and user-visible config UI.
4. Implement provider-specific HTTPS request/response mapping with cancellation,
   timeout, certificate rules and redacted logging.
5. Connect the validated intent to a session-aware local planner behind a
   feature flag; default builds retain `NoopDecision`.
6. Add mock-server, malformed response, stale revision, timeout, TLS failure,
   Binder death and credential deletion tests.
