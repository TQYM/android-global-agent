package com.example.globalagent.v2;

import com.example.globalagent.v2.RectDto;

parcelable FocusIdentity {
    long focusEpoch;
    byte[] focusDigest;
    int focusedUid;
    int displayId;
    int rotation;
    RectDto bounds;
}
