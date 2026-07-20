package com.example.globalagent.v2;

import com.example.globalagent.v2.ActionDto;

parcelable ActionPlan {
    int protocolVersion;
    byte[] serviceInstanceId;
    long sessionId;
    long expectedRevision;
    long perceptionId;
    byte[] perceptionDigest;
    long expectedFocusEpoch;
    byte[] expectedFocusDigest;
    long clientPlanId;
    long deadlineElapsedNanos;
    ActionDto[] actions;
}
