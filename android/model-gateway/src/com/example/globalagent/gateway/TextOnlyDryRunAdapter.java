package com.example.globalagent.gateway;

import com.example.globalagent.v2.ModelRequest;

interface TextOnlyDryRunAdapter {
  String providerKind();

  String modelId();

  String buildRequestJson(ModelRequest request);

  EhviewerDryRunPolicy.Result parseAndValidate(
      ModelRequest request, String providerResponseJson);
}
