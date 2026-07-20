package com.example.globalagent.v2;

parcelable SessionStartRequest {
    int protocolVersion;
    int triggerSource;
    long triggerElapsedNanos;
    int displayId;
    long clientRequestId;
}
