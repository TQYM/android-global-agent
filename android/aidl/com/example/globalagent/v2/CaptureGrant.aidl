package com.example.globalagent.v2;

import com.example.globalagent.v2.RectDto;

parcelable CaptureGrant {
    int protocolVersion;
    byte[] serviceInstanceId;
    byte[] token;
    long grantId;
    long sessionId;
    long revision;
    long focusEpoch;
    int displayId;
    RectDto crop;
    long expiresAtElapsedNanos;
    int maxImageBytes;
    int redactionPolicyVersion;
}
