package com.example.globalagent.v2;

import com.example.globalagent.v2.ImagePayload;
import com.example.globalagent.v2.OcrNode;
import com.example.globalagent.v2.RectDto;
import com.example.globalagent.v2.SensitiveRegion;

parcelable PerceptionEnvelope {
    int protocolVersion;
    byte[] serviceInstanceId;
    long sessionId;
    long revision;
    long perceptionId;
    long capturedAtElapsedNanos;
    long focusEpoch;
    byte[] focusDigest;
    int status;
    int displayId;
    int rotation;
    RectDto capturedRegion;
    boolean secureContentExcluded;
    int redactionPolicyVersion;
    SensitiveRegion[] redactions;
    OcrNode[] ocr;
    byte[] perceptionDigest;
    @nullable ImagePayload image;
}
