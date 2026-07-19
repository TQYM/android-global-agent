package com.example.globalagent;

parcelable SessionTrigger {
    int source;
    long monotonicNanos;
    int pressDurationMillis;
    int displayId;
    boolean keyguardLocked;
    boolean userConfirmed;
}
