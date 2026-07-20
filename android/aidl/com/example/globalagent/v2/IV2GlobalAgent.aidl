package com.example.globalagent.v2;

import com.example.globalagent.v2.ActionPlan;
import com.example.globalagent.v2.ActionReceipt;
import com.example.globalagent.v2.ApprovedInput;
import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.CaptureSpec;
import com.example.globalagent.v2.ExecutionGrant;
import com.example.globalagent.v2.FocusIdentity;
import com.example.globalagent.v2.IAgentSessionCallback;
import com.example.globalagent.v2.PerceptionEnvelope;
import com.example.globalagent.v2.PlanValidation;
import com.example.globalagent.v2.SessionHandle;
import com.example.globalagent.v2.SessionStartRequest;
import com.example.globalagent.v2.SessionStatusV2;

interface IV2GlobalAgent {
    const int PROTOCOL_VERSION = 2;

    SessionHandle startSession(in SessionStartRequest request,
            IAgentSessionCallback callback);
    SessionStatusV2 submitTranscript(long sessionId, long expectedRevision,
            long sequence, boolean isFinal, String text);
    SessionStatusV2 notifyFocusChanged(in FocusIdentity focus);
    CaptureGrant issueCaptureGrant(long sessionId, long expectedRevision,
            in CaptureSpec spec);
    PerceptionEnvelope captureOnce(in byte[] grantToken);
    PlanValidation validatePlan(in ActionPlan plan);
    ExecutionGrant approvePlan(long sessionId, long expectedRevision,
            long serverPlanId, in byte[] planDigest);
    ActionReceipt injectInput(in ApprovedInput approved);
    SessionStatusV2 cancelSession(long sessionId, long expectedRevision,
            int reason);
    void cancelAll(int reason);
    SessionStatusV2 getSessionStatus(long sessionId);
}
