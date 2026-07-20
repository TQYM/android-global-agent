package com.example.globalagent.gateway;

import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.ModelRequest;
import com.example.globalagent.v2.SessionHandle;
import java.nio.charset.StandardCharsets;

final class ModelGatewayV2Policy {
  static final int STATUS_DISABLED = 1;
  static final int STATUS_INVALID_REQUEST = 2;
  static final int MAX_TRANSCRIPT_BYTES = 4096;
  static final int MAX_PACKAGE_BYTES = 256;
  static final int MAX_PROVIDER_PROFILE_BYTES = 128;
  static final long MAX_DEADLINE_NS = 2_000_000_000L;

  private ModelGatewayV2Policy() {}

  static boolean isOpenRequestValid(ModelRequest request,
      long nowElapsedNanos) {
    if (request == null || nowElapsedNanos < 0 ||
        request.protocolVersion != com.example.globalagent.v2.IV2GlobalAgent.PROTOCOL_VERSION ||
        !isSessionValid(request.session, nowElapsedNanos) ||
        !isBoundedText(request.finalTranscript, MAX_TRANSCRIPT_BYTES) ||
        !isBoundedToken(request.focusedPackage, MAX_PACKAGE_BYTES) ||
        !isBoundedToken(request.providerProfile, MAX_PROVIDER_PROFILE_BYTES) ||
        request.imageAllowed ||
        request.deadlineElapsedNanos != request.session.deadlineElapsedNanos) {
      return false;
    }
    final CaptureGrant grant = request.captureGrant;
    return grant == null || ProtocolV2Validator.isCaptureGrantValid(
        grant, nowElapsedNanos);
  }

  static boolean isDeepSeekDryRunRequestValid(ModelRequest request,
      long nowElapsedNanos) {
    return isOpenRequestValid(request, nowElapsedNanos) &&
        DeepSeekV4TextAdapter.PROFILE_ID.equals(request.providerProfile) &&
        EhviewerDryRunPolicy.isAllowedPackage(request.focusedPackage) &&
        !request.finalTranscript.isEmpty() &&
        request.captureGrant == null;
  }

  private static boolean isSessionValid(SessionHandle session,
      long nowElapsedNanos) {
    return nowElapsedNanos >= 0 && session != null &&
        session.protocolVersion == com.example.globalagent.v2.IV2GlobalAgent.PROTOCOL_VERSION &&
        session.serviceInstanceId != null && session.serviceInstanceId.length == 16 &&
        session.sessionId > 0 && session.revision >= 0 && session.displayId >= 0 &&
        session.deadlineElapsedNanos > nowElapsedNanos &&
        session.deadlineElapsedNanos - nowElapsedNanos <= MAX_DEADLINE_NS &&
        session.focusEpoch >= 0 && session.focusDigest != null &&
        session.focusDigest.length == 32;
  }

  private static boolean isBoundedText(String value, int maxBytes) {
    return value != null && value.indexOf('\0') < 0 &&
        value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }

  private static boolean isBoundedToken(String value, int maxBytes) {
    if (!isBoundedText(value, maxBytes) || value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); ++index) {
      final char character = value.charAt(index);
      if (!(Character.isLetterOrDigit(character) || character == '.' ||
          character == '_' || character == '-' || character == '/')) {
        return false;
      }
    }
    return true;
  }
}
