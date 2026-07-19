package com.example.globalagent;

import com.example.globalagent.PointerSample;

parcelable GestureFrame {
    int action;
    int actionIndex;
    long elapsedMillis;
    PointerSample[] pointers;
}

