package com.example.globalagent;

import com.example.globalagent.GestureSpec;

interface IAgentBridge {
    boolean injectGesture(in GestureSpec gesture);
    void cancelActiveGesture();
}
