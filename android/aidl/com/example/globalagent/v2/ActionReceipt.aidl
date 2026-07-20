package com.example.globalagent.v2;

parcelable ActionReceipt {
    long sessionId;
    long revision;
    long serverPlanId;
    int status;
    int executedActionCount;
    boolean verificationRequired;
}
