package com.example.globalagent.gateway;

public final class ModelGatewayPolicyTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  public static void main(String[] args) {
    check(ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com/v1/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "http://api.example.com/v1/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://key@api.example.com/v1/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com/v1/decision?key=secret"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com:8443/v1/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com/v1/%2e%2e/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com\\@internal.example/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://evil.example\\@good.example/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com/v1/%5c/decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.example.com/v1/../decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed("https:///decision"));
    check(!ModelGatewayPolicy.isEndpointAllowed(
        "https://api.ex\u00e4mple.com/v1/decision"));

    check(ModelGatewayPolicy.isModelIdValid("vendor/model-v1:stable"));
    check(!ModelGatewayPolicy.isModelIdValid("model with spaces"));
    check(ModelGatewayPolicy.isModelIdValid("a".repeat(
        ModelGatewayPolicy.MAX_MODEL_ID_BYTES)));
    check(!ModelGatewayPolicy.isModelIdValid("a".repeat(
        ModelGatewayPolicy.MAX_MODEL_ID_BYTES + 1)));
    check(ModelGatewayPolicy.isCredentialAliasValid("agent_model_key"));
    check(!ModelGatewayPolicy.isCredentialAliasValid(""));
    check(!ModelGatewayPolicy.isCredentialAliasValid("raw/key/value"));

    check(ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 900, "Settings", false));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION + 1, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 900, "Settings", false));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 1001, "Settings", false));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_CONFIRM_REQUIRED, 900, "Send", false));
    check(ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_CONFIRM_REQUIRED, 900, "Send", true));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 900, "bad\0text", false));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 0, 0,
        ModelGatewayPolicy.INTENT_NOOP, 0, "", false));
    check(ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, Long.MAX_VALUE, Long.MAX_VALUE,
        ModelGatewayPolicy.INTENT_NOOP, 0, "", false));
    check(ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 0, "Settings", false));
    check(ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 1000, "Settings", false));
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_SEARCH, 500, "", false));

    for (int intent = ModelGatewayPolicy.INTENT_NOOP;
        intent <= ModelGatewayPolicy.INTENT_CONFIRM_REQUIRED; ++intent) {
      final boolean requiresConfirmation =
          intent == ModelGatewayPolicy.INTENT_CONFIRM_REQUIRED;
      final String target = intent == ModelGatewayPolicy.INTENT_NOOP ? "" : "x";
      check(ModelGatewayPolicy.isIntentResponseValid(
          ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0, intent, 500, target,
          requiresConfirmation));
    }
    check(!ModelGatewayPolicy.isIntentResponseValid(
        ModelGatewayPolicy.PROTOCOL_VERSION, 1, 0,
        ModelGatewayPolicy.INTENT_CONFIRM_REQUIRED + 1, 500, "x", false));

    System.out.println("model gateway policy checks passed: " + checks);
  }
}
