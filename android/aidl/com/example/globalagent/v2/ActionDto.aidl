package com.example.globalagent.v2;

import com.example.globalagent.v2.RectDto;

parcelable ActionDto {
    long actionId;
    int type;
    long candidateId;
    int displayId;
    RectDto target;
    int startX;
    int startY;
    int endX;
    int endY;
    long durationMillis;
    long waitMillis;
    String text;
}
