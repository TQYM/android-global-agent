# Model API Gateway Boundary

## Current status

The production AOSP daemon still uses `NoopDecision`. The project does not yet
send network requests and does not store an API key. A separate
`GlobalAgentModelGateway` APK now exists with `INTERNET` only, a strict public
configuration schema v2, a root/shell-only `ContentProvider.call()` import and
`AtomicFile` persistence. The repository also has an unstable protocol-v2 AIDL
contract, bounded DTO validator and portable single-use capture-grant state
machine. The Gateway APK now exposes a Bridge-signature-permission-protected
`IModelGateway` service which validates v2 text-only requests and returns an
explicit disabled result. The per-session Java capability binds UID, package
and certificate identity. The private native v2 service remains unregistered
and fail-closed, so this is not a Provider HTTP client or a working capture path.
The local dry-run layer now has a provider-neutral `TextOnlyDryRunAdapter`, a
DeepSeek V4 text-only adapter, strict bounded response parsing, and an
Ehviewer-only release/debug plan policy. It is mock-only: no credential is read,
no image is accepted, and every result reports zero injected events.

## Required architecture

The model client is a separate, non-privileged application/process with
`INTERNET` only. It does not share the bridge UID, hold `INJECT_EVENTS`, access
SurfaceFlinger or read the Agent state file. The current implementation receives
no screenshots or raw PCM. A future redacted image path must require a
single-use session/revision/deadline-bound `CaptureGrant`.

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

The current public importer accepts only Base64-encoded UTF-8 configuration
from root or shell. It rejects unknown call arguments, unknown JSON fields,
duplicate keys, raw secret field names, non-Keystore credential references and
out-of-range limits. Until the network/parser path exists it also requires
`dryRun=true`. Credential entry is deliberately not available through this
provider.

## Response boundary

`ModelGatewayPolicy` rejects percent-encoded, backslash-containing or non-ASCII
endpoint strings before URI parsing to avoid normalization ambiguity. Protocol
version 1 accepts only a session/revision-bound
intent enum, confidence in `[0,1000]`, bounded target text and an explicit
confirmation flag. Irreversible actions require confirmation. Unknown fields,
coordinates, arbitrary JSON objects and provider tool calls are not forwarded
to the input bridge.

## Remaining work

1. Confirm the DeepSeek V4 model identifier and authentication/compliance
   approval: API key, OAuth or device-bound token.
2. Wire the existing Bridge capability to the private native v2 service after
   exact-tree calling-SID validation, without granting the gateway capture/input
   permissions.
3. Implement Android Keystore credential encryption and user-visible config UI.
4. Implement the approved provider-specific HTTPS request/response mapping with
   cancellation, timeout, certificate rules and redacted logging. The current
   adapter deliberately stops at a mock response.
5. Connect the validated intent to a session-aware local planner behind a
   feature flag; default builds retain `NoopDecision`.
6. Add mock-server, malformed response, stale revision, timeout, TLS failure,
   Binder death and credential deletion tests.
