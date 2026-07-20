package com.example.globalagent;

import com.example.globalagent.v2.IPlatformAgentV2;

final class V2SessionCapabilityFactory {
  private final GatewayPackageAuthorizer authorizer;

  V2SessionCapabilityFactory(GatewayPackageAuthorizer authorizer) {
    if (authorizer == null) {
      throw new NullPointerException("authorizer");
    }
    this.authorizer = authorizer;
  }

  V2SessionCapability create(IPlatformAgentV2 nativeAgent, long sessionId,
      int gatewayUid, long capabilityId) {
    authorizer.requireAuthorized(gatewayUid);
    return new V2SessionCapability(nativeAgent, sessionId, gatewayUid,
        capabilityId);
  }
}
