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

interface IPlatformAgentV2 {
    SessionHandle startSessionPrivileged(in SessionStartRequest request,
            IAgentSessionCallback callback);
    SessionStatusV2 submitTranscriptPrivileged(long sessionId,
            long expectedRevision, long sequence, boolean isFinal, String text);
    SessionStatusV2 notifyFocusChangedPrivileged(in FocusIdentity focus);
    CaptureGrant issueCaptureGrantFor(long sessionId, long expectedRevision,
            int granteeUid, long capabilityId, in CaptureSpec spec);
    PerceptionEnvelope captureOnceFor(in byte[] grantToken, int granteeUid,
            long capabilityId);
    PlanValidation validatePlanFor(in ActionPlan plan, int granteeUid,
            long capabilityId);
    ExecutionGrant approvePlanPrivileged(long sessionId,
            long expectedRevision, long serverPlanId, in byte[] planDigest);
    ActionReceipt injectInputPrivileged(in ApprovedInput approved);
    SessionStatusV2 cancelSessionPrivileged(long sessionId,
            long expectedRevision, int reason);
    void cancelAllPrivileged(int reason);
    SessionStatusV2 getSessionStatusPrivileged(long sessionId);
}
