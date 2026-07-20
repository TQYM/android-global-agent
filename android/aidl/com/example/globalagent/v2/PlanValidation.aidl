package com.example.globalagent.v2;

import com.example.globalagent.v2.PolicyViolation;

parcelable PlanValidation {
    long serverPlanId;
    long validatedRevision;
    byte[] planDigest;
    boolean schemaValid;
    boolean policyValid;
    boolean requiresConfirmation;
    boolean executableInCurrentMode;
    PolicyViolation[] violations;
}
