package com.example.globalagent.v2;

parcelable SessionHandle {
    int protocolVersion;
    byte[] serviceInstanceId;
    long sessionId;
    long revision;
    long deadlineElapsedNanos;
    int displayId;
    long focusEpoch;
    byte[] focusDigest;
}
