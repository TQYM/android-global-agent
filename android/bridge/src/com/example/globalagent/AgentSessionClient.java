package com.example.globalagent;

import android.os.RemoteException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

final class AgentSessionClient {
  static final int SOURCE_POWER_LONG_PRESS = 0;
  static final int SOURCE_EXPLICIT_UI = 1;

  static final int STATE_IDLE = 0;
  static final int STATE_LISTENING = 1;
  static final int STATE_THINKING = 2;
  static final int STATE_EXECUTING = 3;
  static final int STATE_FEEDBACK = 4;
  static final int STATE_ERROR = 5;

  interface Listener {
    void onStatusChanged(SessionStatus status);
  }

  private final Object lock = new Object();
  private final List<Listener> listeners = new ArrayList<>();
  private IAgentService service;
  private SessionStatus status;

  AgentSessionClient(Listener listener) {
    if (listener != null) {
      listeners.add(listener);
    }
  }

  void addListener(Listener listener) {
    if (listener == null) {
      return;
    }
    final SessionStatus current;
    synchronized (lock) {
      if (!listeners.contains(listener)) {
        listeners.add(listener);
      }
      current = status;
    }
    if (current != null) {
      listener.onStatusChanged(current);
    }
  }

  void removeListener(Listener listener) {
    synchronized (lock) {
      listeners.remove(listener);
    }
  }

  void attach(IAgentService candidate) throws RemoteException {
    if (candidate == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    final SessionStatus initial = candidate.getSessionStatus();
    if (!SessionStatusValidator.shouldAccept(null, initial)) {
      throw new RemoteException("native service returned invalid status");
    }
    synchronized (lock) {
      service = candidate;
    }
    acceptStatus(initial);
  }

  void detach() {
    synchronized (lock) {
      service = null;
      status = null;
    }
    notifyListener(null);
  }

  void acceptCallback(SessionStatus candidate) {
    acceptStatus(candidate);
  }

  SessionStatus beginExplicitSession(int displayId, boolean keyguardLocked,
      boolean userConfirmed) throws RemoteException {
    return beginSession(SOURCE_EXPLICIT_UI, 0, displayId, keyguardLocked,
        userConfirmed);
  }

  SessionStatus beginPowerSession(int pressDurationMillis, int displayId,
      boolean keyguardLocked, boolean userConfirmed) throws RemoteException {
    return beginSession(SOURCE_POWER_LONG_PRESS, pressDurationMillis,
        displayId, keyguardLocked, userConfirmed);
  }

  SessionStatus submitTranscript(String text, boolean isFinal)
      throws RemoteException {
    if (!SessionEntryPolicy.isTranscriptValid(text)) {
      throw new IllegalArgumentException("invalid transcript text");
    }
    final IAgentService current;
    final TranscriptUpdate update = new TranscriptUpdate();
    synchronized (lock) {
      current = requireServiceLocked();
      if (status == null || !status.active || status.state != STATE_LISTENING) {
        throw new IllegalStateException("no listening session");
      }
      update.sessionId = status.sessionId;
      if (status.transcriptSequence == Long.MAX_VALUE) {
        throw new IllegalStateException("transcript sequence exhausted");
      }
      update.sequence = status.transcriptSequence + 1;
      update.isFinal = isFinal;
      update.text = text;
    }
    return acceptRequired(current.submitTranscript(update));
  }

  SessionStatus transitionTo(int state) throws RemoteException {
    if (state < STATE_IDLE || state > STATE_ERROR) {
      throw new IllegalArgumentException("invalid session state");
    }
    final IAgentService current;
    final long sessionId;
    synchronized (lock) {
      current = requireServiceLocked();
      sessionId = requireActiveSessionLocked();
    }
    return acceptRequired(current.transitionSession(sessionId, state));
  }

  SessionStatus cancel() throws RemoteException {
    final IAgentService current;
    final long sessionId;
    synchronized (lock) {
      current = requireServiceLocked();
      sessionId = requireActiveSessionLocked();
    }
    return acceptRequired(current.cancelSession(sessionId));
  }

  SessionStatus snapshot() {
    synchronized (lock) {
      return status;
    }
  }

  private SessionStatus beginSession(int source, int pressDurationMillis,
      int displayId, boolean keyguardLocked, boolean userConfirmed)
      throws RemoteException {
    if (displayId < 0 || pressDurationMillis < 0) {
      throw new IllegalArgumentException("invalid trigger metadata");
    }
    final IAgentService current;
    synchronized (lock) {
      current = requireServiceLocked();
      if (status != null && status.active) {
        throw new IllegalStateException("a session is already active");
      }
    }
    final SessionTrigger trigger = new SessionTrigger();
    trigger.source = source;
    trigger.monotonicNanos = SystemClock.elapsedRealtimeNanos();
    trigger.pressDurationMillis = pressDurationMillis;
    trigger.displayId = displayId;
    trigger.keyguardLocked = keyguardLocked;
    trigger.userConfirmed = userConfirmed;
    return acceptRequired(current.beginSession(trigger));
  }

  private SessionStatus acceptRequired(SessionStatus candidate) {
    if (acceptStatus(candidate)) {
      return candidate;
    }
    synchronized (lock) {
      if (!isSameStatus(status, candidate)) {
        throw new IllegalStateException("service returned an invalid status");
      }
      return status;
    }
  }

  private boolean acceptStatus(SessionStatus candidate) {
    synchronized (lock) {
      if (!SessionStatusValidator.shouldAccept(status, candidate)) {
        return false;
      }
      status = candidate;
    }
    notifyListener(candidate);
    return true;
  }

  private IAgentService requireServiceLocked() {
    if (service == null) {
      throw new IllegalStateException("native service is disconnected");
    }
    return service;
  }

  private long requireActiveSessionLocked() {
    if (status == null || !status.active) {
      throw new IllegalStateException("no active session");
    }
    return status.sessionId;
  }

  private static boolean isSameStatus(SessionStatus first,
      SessionStatus second) {
    return first != null && second != null &&
        first.protocolVersion == second.protocolVersion &&
        first.revision == second.revision &&
        first.sessionId == second.sessionId && first.source == second.source &&
        first.startedNanos == second.startedNanos &&
        first.displayId == second.displayId && first.state == second.state &&
        first.userConfirmed == second.userConfirmed &&
        first.transcriptSequence == second.transcriptSequence &&
        first.transcriptFinal == second.transcriptFinal &&
        first.active == second.active;
  }

  private void notifyListener(SessionStatus changed) {
    final List<Listener> snapshot;
    synchronized (lock) {
      snapshot = new ArrayList<>(listeners);
    }
    for (Listener listener : snapshot) {
      listener.onStatusChanged(changed);
    }
  }
}
