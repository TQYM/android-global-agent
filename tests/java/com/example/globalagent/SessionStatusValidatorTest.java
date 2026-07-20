package com.example.globalagent;

public final class SessionStatusValidatorTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static SessionStatus idle(long revision) {
    final SessionStatus status = new SessionStatus();
    status.protocolVersion = IAgentService.PROTOCOL_VERSION;
    status.revision = revision;
    status.state = 0;
    status.active = false;
    status.userConfirmed = false;
    return status;
  }

  private static SessionStatus listening(long revision) {
    final SessionStatus status = idle(revision);
    status.sessionId = 1;
    status.startedNanos = 100;
    status.state = 1;
    status.active = true;
    status.userConfirmed = true;
    return status;
  }

  public static void main(String[] args) {
    final SessionStatus initial = idle(0);
    check(SessionStatusValidator.shouldAccept(null, initial));

    final SessionStatus active = listening(1);
    check(SessionStatusValidator.shouldAccept(initial, active));
    check(!SessionStatusValidator.shouldAccept(active, listening(1)));
    check(!SessionStatusValidator.shouldAccept(active, idle(0)));

    final SessionStatus wrongProtocol = listening(2);
    wrongProtocol.protocolVersion++;
    check(!SessionStatusValidator.shouldAccept(active, wrongProtocol));

    final SessionStatus inconsistentIdle = idle(2);
    inconsistentIdle.userConfirmed = true;
    check(!SessionStatusValidator.shouldAccept(active, inconsistentIdle));

    final SessionStatus inconsistentActive = listening(2);
    inconsistentActive.state = 0;
    check(!SessionStatusValidator.shouldAccept(active, inconsistentActive));

    final SessionStatus invalidSequence = listening(2);
    invalidSequence.transcriptSequence = -1;
    check(!SessionStatusValidator.shouldAccept(active, invalidSequence));

    System.out.println("session status validator checks passed: " + checks);
  }
}
