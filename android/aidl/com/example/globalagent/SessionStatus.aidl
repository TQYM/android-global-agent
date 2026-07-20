package com.example.globalagent;

parcelable SessionStatus {
    int protocolVersion;
    long revision;
    long sessionId;
    int source;
    long startedNanos;
    int displayId;
    int state;
    boolean userConfirmed;
    long transcriptSequence;
    boolean transcriptFinal;
    boolean active;
}
