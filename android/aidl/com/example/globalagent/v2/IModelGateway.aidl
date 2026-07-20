package com.example.globalagent.v2;

import com.example.globalagent.v2.IModelGatewayCallback;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.ModelRequest;

interface IModelGateway {
    void openSession(in ModelRequest request, IV2GlobalAgent sessionCapability,
            IModelGatewayCallback callback);
    oneway void cancel(long sessionId, long revision, int reason);
}
