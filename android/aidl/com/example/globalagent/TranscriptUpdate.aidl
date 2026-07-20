package com.example.globalagent;

parcelable TranscriptUpdate {
    long sessionId;
    long sequence;
    boolean isFinal;
    String text;
}
