package com.example.globalagent.v2;

import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.SessionHandle;

parcelable ModelRequest {
    int protocolVersion;
    SessionHandle session;
    CaptureGrant captureGrant;
    String finalTranscript;
    String focusedPackage;
    String providerProfile;
    boolean imageAllowed;
    long deadlineElapsedNanos;
}
