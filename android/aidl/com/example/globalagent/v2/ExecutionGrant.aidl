package com.example.globalagent.v2;

parcelable ExecutionGrant {
    int protocolVersion;
    byte[] serviceInstanceId;
    byte[] token;
    long sessionId;
    long revision;
    long focusEpoch;
    long serverPlanId;
    byte[] planDigest;
    long expiresAtElapsedNanos;
}
