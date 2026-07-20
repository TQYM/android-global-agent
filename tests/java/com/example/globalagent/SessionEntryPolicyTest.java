package com.example.globalagent;

public final class SessionEntryPolicyTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  public static void main(String[] args) {
    check(SessionEntryPolicy.canStart(true, false, false, true, false));
    check(!SessionEntryPolicy.canStart(false, false, false, true, false));
    check(!SessionEntryPolicy.canStart(true, false, true, true, false));
    check(!SessionEntryPolicy.canStart(true, false, false, false, false));
    check(!SessionEntryPolicy.canStart(true, true, false, true, false));
    check(!SessionEntryPolicy.canStart(true, false, false, true, true));

    check(SessionEntryPolicy.canSubmit(true, true,
        AgentSessionClient.STATE_LISTENING, false, "open settings"));
    check(!SessionEntryPolicy.canSubmit(true, true,
        AgentSessionClient.STATE_THINKING, false, "open settings"));
    check(!SessionEntryPolicy.canSubmit(true, true,
        AgentSessionClient.STATE_LISTENING, false, ""));
    check(!SessionEntryPolicy.isTranscriptValid("bad\0text"));
    check(SessionEntryPolicy.isTranscriptValid("a".repeat(4096)));
    check(!SessionEntryPolicy.isTranscriptValid("a".repeat(4097)));
    check(!SessionEntryPolicy.isTranscriptValid("\u4e2d".repeat(1366)));

    check(SessionEntryPolicy.canCancel(true, true, false));
    check(!SessionEntryPolicy.canCancel(true, false, false));
    check(!SessionEntryPolicy.canCancel(true, true, true));

    System.out.println("session entry policy checks passed: " + checks);
  }
}
