package com.example.globalagent;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import com.example.globalagent.v2.ActionPlan;
import com.example.globalagent.v2.ActionReceipt;
import com.example.globalagent.v2.ApprovedInput;
import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.CaptureSpec;
import com.example.globalagent.v2.ExecutionGrant;
import com.example.globalagent.v2.FocusIdentity;
import com.example.globalagent.v2.IAgentSessionCallback;
import com.example.globalagent.v2.IPlatformAgentV2;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.PerceptionEnvelope;
import com.example.globalagent.v2.PlanValidation;
import com.example.globalagent.v2.SessionHandle;
import com.example.globalagent.v2.SessionStartRequest;
import com.example.globalagent.v2.SessionStatusV2;
import java.util.concurrent.atomic.AtomicBoolean;

final class V2SessionCapability extends IV2GlobalAgent.Stub {
  private final IPlatformAgentV2 nativeAgent;
  private final V2SessionCapabilityPolicy policy;
  private final AtomicBoolean nativeAlive = new AtomicBoolean(true);
  private final IBinder.DeathRecipient nativeDeath =
      () -> nativeAlive.set(false);

  V2SessionCapability(IPlatformAgentV2 nativeAgent, long sessionId,
      int gatewayUid, long capabilityId) {
    if (nativeAgent == null) {
      throw new NullPointerException("nativeAgent");
    }
    this.nativeAgent = nativeAgent;
    policy = new V2SessionCapabilityPolicy(sessionId, gatewayUid, capabilityId);
    try {
      nativeAgent.asBinder().linkToDeath(nativeDeath, 0);
    } catch (RemoteException exception) {
      throw new IllegalStateException("native v2 service is already dead",
          exception);
    }
  }

  @Override
  public SessionHandle startSession(SessionStartRequest request,
      IAgentSessionCallback callback) {
    throw policy.bridgeOnly();
  }

  @Override
  public SessionStatusV2 submitTranscript(long sessionId,
      long expectedRevision, long sequence, boolean isFinal, String text) {
    throw policy.bridgeOnly();
  }

  @Override
  public SessionStatusV2 notifyFocusChanged(FocusIdentity focus) {
    throw policy.bridgeOnly();
  }

  @Override
  public CaptureGrant issueCaptureGrant(long sessionId, long expectedRevision,
      CaptureSpec spec) {
    throw policy.bridgeOnly();
  }

  @Override
  public PerceptionEnvelope captureOnce(byte[] grantToken)
      throws RemoteException {
    requireNativeAlive();
    final int gatewayUid = policy.requireGateway(Binder.getCallingUid());
    final byte[] copiedToken = policy.copyGrantToken(grantToken);
    final long identity = Binder.clearCallingIdentity();
    try {
      return nativeAgent.captureOnceFor(copiedToken, gatewayUid,
          policy.capabilityId());
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  @Override
  public PlanValidation validatePlan(ActionPlan plan) throws RemoteException {
    requireNativeAlive();
    final int gatewayUid = policy.requireGateway(Binder.getCallingUid());
    final ActionPlan boundPlan = policy.requireBoundPlan(plan);
    final long identity = Binder.clearCallingIdentity();
    try {
      return nativeAgent.validatePlanFor(boundPlan, gatewayUid,
          policy.capabilityId());
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  @Override
  public ExecutionGrant approvePlan(long sessionId, long expectedRevision,
      long serverPlanId, byte[] planDigest) {
    throw policy.bridgeOnly();
  }

  @Override
  public ActionReceipt injectInput(ApprovedInput approved) {
    throw policy.bridgeOnly();
  }

  @Override
  public SessionStatusV2 cancelSession(long sessionId, long expectedRevision,
      int reason) throws RemoteException {
    requireNativeAlive();
    policy.requireGateway(Binder.getCallingUid());
    policy.requireBoundSession(sessionId);
    final long identity = Binder.clearCallingIdentity();
    try {
      return nativeAgent.cancelSessionPrivileged(sessionId, expectedRevision,
          reason);
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  @Override
  public void cancelAll(int reason) {
    throw policy.bridgeOnly();
  }

  @Override
  public SessionStatusV2 getSessionStatus(long sessionId)
      throws RemoteException {
    requireNativeAlive();
    policy.requireGateway(Binder.getCallingUid());
    policy.requireBoundSession(sessionId);
    final long identity = Binder.clearCallingIdentity();
    try {
      return nativeAgent.getSessionStatusPrivileged(sessionId);
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
  }

  private void requireNativeAlive() {
    if (!nativeAlive.get()) {
      throw new IllegalStateException("native v2 service is disconnected");
    }
  }
}
