package com.example.globalagent;

import java.nio.charset.StandardCharsets;

final class SessionEntryPolicy {
  static final int MAX_TRANSCRIPT_BYTES = 4096;

  private SessionEntryPolicy() {}

  static boolean canStart(boolean connected, boolean active,
      boolean keyguardLocked, boolean displayInteractive,
      boolean requestInFlight) {
    return connected && !active && !keyguardLocked && displayInteractive &&
        !requestInFlight;
  }

  static boolean canSubmit(boolean connected, boolean active, int state,
      boolean requestInFlight, String text) {
    return connected && active && state == AgentSessionClient.STATE_LISTENING &&
        !requestInFlight && isTranscriptValid(text);
  }

  static boolean canCancel(boolean connected, boolean active,
      boolean requestInFlight) {
    return connected && active && !requestInFlight;
  }

  static boolean isTranscriptValid(String text) {
    return text != null && !text.isEmpty() && text.indexOf('\0') < 0 &&
        text.getBytes(StandardCharsets.UTF_8).length <= MAX_TRANSCRIPT_BYTES;
  }
}
