package com.example.globalagent.v2;

import com.example.globalagent.v2.RectDto;

parcelable CaptureSpec {
    int purpose;
    int displayId;
    RectDto crop;
    int maxLongEdge;
    int maxImageBytes;
    int imageFormat;
    int redactionPolicyVersion;
}
