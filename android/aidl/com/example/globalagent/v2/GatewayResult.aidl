package com.example.globalagent.v2;

import com.example.globalagent.v2.PlanValidation;

parcelable GatewayResult {
    long sessionId;
    long revision;
    int status;
    String safeProviderRequestId;
    long latencyMillis;
    long inputTokens;
    long outputTokens;
    @nullable PlanValidation validation;
}
