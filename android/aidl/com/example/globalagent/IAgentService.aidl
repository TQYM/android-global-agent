package com.example.globalagent;

import com.example.globalagent.IAgentBridge;
import com.example.globalagent.WindowSnapshot;

interface IAgentService {
    void registerBridge(IAgentBridge bridge);
    void notifySettingChanged(String key);
    void notifyWindowChanged(in WindowSnapshot snapshot);
}
