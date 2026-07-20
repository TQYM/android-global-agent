package com.example.globalagent.v2;

import com.example.globalagent.v2.GatewayResult;

oneway interface IModelGatewayCallback {
    void onComplete(in GatewayResult result);
}
