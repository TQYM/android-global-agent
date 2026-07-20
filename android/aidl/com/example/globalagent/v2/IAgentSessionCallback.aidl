package com.example.globalagent.v2;

import com.example.globalagent.v2.SessionStatusV2;

oneway interface IAgentSessionCallback {
    void onSessionChanged(in SessionStatusV2 status);
    void onCancelled(long sessionId, long revision, int reason);
}
