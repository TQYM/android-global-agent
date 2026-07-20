package com.example.globalagent;

import com.example.globalagent.GestureSpec;
import com.example.globalagent.SessionStatus;

interface IAgentBridge {
    boolean injectGesture(in GestureSpec gesture);
    void cancelActiveGesture();
    oneway void onSessionStateChanged(in SessionStatus status);
}
