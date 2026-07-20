package com.example.globalagent.v2;

parcelable SessionStatusV2 {
    int protocolVersion;
    byte[] serviceInstanceId;
    long sessionId;
    long revision;
    int state;
    int displayId;
    long focusEpoch;
    byte[] focusDigest;
    long deadlineElapsedNanos;
    boolean active;
    int cancelReason;
}
