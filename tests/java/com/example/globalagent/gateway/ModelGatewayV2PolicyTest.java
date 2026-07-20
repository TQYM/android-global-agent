package com.example.globalagent.gateway;

import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.ModelRequest;
import com.example.globalagent.v2.SessionHandle;

public final class ModelGatewayV2PolicyTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static byte[] bytes(int length) {
    final byte[] value = new byte[length];
    value[0] = 1;
    return value;
  }

  private static ModelRequest valid(long now) {
    final ModelRequest request = new ModelRequest();
    request.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    request.session = new SessionHandle();
    request.session.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    request.session.serviceInstanceId = bytes(16);
    request.session.sessionId = 7;
    request.session.revision = 2;
    request.session.deadlineElapsedNanos = now + 1_000_000_000L;
    request.session.displayId = 0;
    request.session.focusDigest = bytes(32);
    request.finalTranscript = "open settings";
    request.focusedPackage = "com.android.settings";
    request.providerProfile = "openai-primary";
    request.imageAllowed = false;
    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    return request;
  }

  public static void main(String[] args) {
    final long now = 10_000;
    final ModelRequest request = valid(now);
    check(ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.imageAllowed = true;
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.imageAllowed = false;
    request.session.deadlineElapsedNanos = now;
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.session.deadlineElapsedNanos = now + 1_000_000_000L;
    request.focusedPackage = "com.android.settings?bad";
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.focusedPackage = "com.android.settings";
    request.finalTranscript = "x".repeat(
        ModelGatewayV2Policy.MAX_TRANSCRIPT_BYTES + 1);
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.finalTranscript = "open settings";
    request.captureGrant = new CaptureGrant();
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.captureGrant = null;
    request.protocolVersion = 1;
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    request.session.focusDigest = bytes(31);
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));
    request.session.focusDigest = bytes(32);
    request.deadlineElapsedNanos++;
    check(!ModelGatewayV2Policy.isOpenRequestValid(request, now));

    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    request.providerProfile = DeepSeekV4TextAdapter.PROFILE_ID;
    request.focusedPackage = EhviewerDryRunPolicy.RELEASE_PACKAGE;
    check(ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request, now));
    request.focusedPackage = "com.android.settings";
    check(!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request, now));
    request.focusedPackage = EhviewerDryRunPolicy.RELEASE_PACKAGE;
    request.session.deadlineElapsedNanos = now +
        ModelGatewayV2Policy.MAX_DEADLINE_NS + 1;
    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    check(!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request, now));
    request.session.deadlineElapsedNanos = now + 1;
    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    request.finalTranscript = "";
    check(!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request, now));
    request.finalTranscript = "x";
    check(!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request, -1));
    check(!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(
        request, Long.MIN_VALUE));

    System.out.println("model gateway v2 policy checks passed: " + checks);
  }
}
