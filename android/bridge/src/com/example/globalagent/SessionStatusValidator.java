package com.example.globalagent;

final class SessionStatusValidator {
  private SessionStatusValidator() {}

  static boolean shouldAccept(SessionStatus previous, SessionStatus candidate) {
    if (!isValid(candidate)) {
      return false;
    }
    return previous == null || candidate.revision > previous.revision;
  }

  private static boolean isValid(SessionStatus status) {
    if (status == null ||
        status.protocolVersion != IAgentService.PROTOCOL_VERSION ||
        status.revision < 0 || status.sessionId < 0 ||
        status.transcriptSequence < 0 || status.displayId < 0 ||
        status.state < 0 || status.state > 5) {
      return false;
    }
    if (!status.active) {
      return status.state == 0 && !status.userConfirmed;
    }
    return status.sessionId > 0 && status.startedNanos > 0 &&
        status.state != 0 && status.userConfirmed;
  }
}
