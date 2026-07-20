package com.example.globalagent.v2;

import android.os.ParcelFileDescriptor;

parcelable ImagePayload {
    @nullable ParcelFileDescriptor dataFd;
    long byteLength;
    byte[] sha256;
    String mimeType;
    int width;
    int height;
}
