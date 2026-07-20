package com.example.globalagent;

import com.example.globalagent.v2.ActionPlan;

final class V2SessionCapabilityPolicy {
  static final int TOKEN_BYTES = 32;

  private final long boundSessionId;
  private final int gatewayUid;
  private final long capabilityId;

  V2SessionCapabilityPolicy(long boundSessionId, int gatewayUid,
      long capabilityId) {
    if (boundSessionId <= 0 || gatewayUid < 0 || capabilityId <= 0) {
      throw new IllegalArgumentException("invalid session capability scope");
    }
    this.boundSessionId = boundSessionId;
    this.gatewayUid = gatewayUid;
    this.capabilityId = capabilityId;
  }

  int requireGateway(int callerUid) {
    if (callerUid != gatewayUid) {
      throw new SecurityException("caller does not own this capability");
    }
    return gatewayUid;
  }

  long requireBoundSession(long sessionId) {
    if (sessionId != boundSessionId) {
      throw new SecurityException("session is outside capability scope");
    }
    return boundSessionId;
  }

  byte[] copyGrantToken(byte[] token) {
    if (token == null || token.length != TOKEN_BYTES) {
      throw new IllegalArgumentException("invalid capture grant token");
    }
    return token.clone();
  }

  ActionPlan requireBoundPlan(ActionPlan plan) {
    if (plan == null) {
      throw new IllegalArgumentException("action plan is null");
    }
    requireBoundSession(plan.sessionId);
    return plan;
  }

  long capabilityId() {
    return capabilityId;
  }

  SecurityException bridgeOnly() {
    return new SecurityException("method is not available to gateway capability");
  }
}
