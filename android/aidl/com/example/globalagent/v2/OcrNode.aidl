package com.example.globalagent.v2;

import com.example.globalagent.v2.RectDto;

parcelable OcrNode {
    long nodeId;
    RectDto bounds;
    String text;
    int confidenceMilli;
    int flags;
    long candidateId;
}
