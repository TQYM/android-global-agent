package com.example.globalagent;

import com.example.globalagent.IAgentBridge;
import com.example.globalagent.SessionStatus;
import com.example.globalagent.SessionTrigger;
import com.example.globalagent.TranscriptUpdate;
import com.example.globalagent.WindowSnapshot;

interface IAgentService {
    const int PROTOCOL_VERSION = 1;

    void registerBridge(IAgentBridge bridge);
    void notifySettingChanged(String key);
    void notifyWindowChanged(in WindowSnapshot snapshot);
    SessionStatus beginSession(in SessionTrigger trigger);
    SessionStatus submitTranscript(in TranscriptUpdate update);
    SessionStatus transitionSession(long sessionId, int state);
    SessionStatus cancelSession(long sessionId);
    SessionStatus getSessionStatus();
}
