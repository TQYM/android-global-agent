package com.example.globalagent.gateway;

import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.ModelRequest;
import com.example.globalagent.v2.SessionHandle;

public final class DeepSeekV4TextAdapterTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static ModelRequest request() {
    final ModelRequest request = new ModelRequest();
    request.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    request.session = new SessionHandle();
    request.session.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    request.session.serviceInstanceId = new byte[16];
    request.session.serviceInstanceId[0] = 1;
    request.session.sessionId = 41;
    request.session.revision = 3;
    request.session.displayId = 0;
    request.session.focusEpoch = 9;
    request.session.focusDigest = new byte[32];
    request.session.focusDigest[0] = 1;
    request.session.deadlineElapsedNanos = 1_000_010_000L;
    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    request.finalTranscript = "find \"Favorites\"\nthen stop";
    request.focusedPackage = EhviewerDryRunPolicy.RELEASE_PACKAGE;
    request.providerProfile = DeepSeekV4TextAdapter.PROFILE_ID;
    request.imageAllowed = false;
    return request;
  }

  private static String plan() {
    return "{\"schemaVersion\":2,\"dryRun\":true,\"sessionId\":41," +
        "\"revision\":3,\"focusedPackage\":\"com.xjs.ehviewer\"," +
        "\"confidenceMilli\":900,\"actions\":[" +
        "{\"id\":1,\"type\":\"FIND_TEXT\",\"targetText\":\"Favorites\"}," +
        "{\"id\":2,\"type\":\"VERIFY\",\"targetText\":\"Favorites\"}]}";
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static String response(String model, String content) {
    return "{\"id\":\"safe-mock-id\",\"model\":\"" + model +
        "\",\"choices\":[{\"index\":0,\"message\":{" +
        "\"role\":\"assistant\",\"content\":" + quote(content) +
        "},\"finish_reason\":\"stop\"}]}";
  }

  public static void main(String[] args) {
    final DeepSeekV4TextAdapter adapter = new DeepSeekV4TextAdapter();
    check(DeepSeekV4TextAdapter.PROVIDER_KIND.equals(adapter.providerKind()));
    check(DeepSeekV4TextAdapter.MODEL_ID.equals(adapter.modelId()));

    final ModelRequest request = request();
    final String body = adapter.buildRequestJson(request, 1_000_000_000L);
    check(body.contains("\"model\":\"deepseek-v4\""));
    check(body.contains("find \\\"Favorites\\\"\\nthen stop"));
    check(!body.contains("apiKey"));
    check(!body.contains("image_url"));
    check(StrictJsonParser.parse(body) != null);

    final ModelRequest oversized = request();
    oversized.finalTranscript = "x".repeat(
        ModelGatewayV2Policy.MAX_TRANSCRIPT_BYTES + 1);
    try {
      adapter.buildRequestJson(oversized, 1_000_000_000L);
      throw new AssertionError("oversized transcript accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    oversized.finalTranscript = "测".repeat(1365) + "x";
    check(oversized.finalTranscript.getBytes(
        java.nio.charset.StandardCharsets.UTF_8).length == 4096);
    check(adapter.buildRequestJson(oversized, 1_000_000_000L) != null);
    oversized.finalTranscript += "x";
    check(oversized.finalTranscript.getBytes(
        java.nio.charset.StandardCharsets.UTF_8).length == 4097);
    try {
      adapter.buildRequestJson(oversized, 1_000_000_000L);
      throw new AssertionError("oversized UTF-8 transcript accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    oversized.finalTranscript = "a".repeat(4096);
    check(adapter.buildRequestJson(oversized, 1_000_000_000L) != null);
    oversized.finalTranscript += "a";
    try {
      adapter.buildRequestJson(oversized, 1_000_000_000L);
      throw new AssertionError("4097-byte ASCII transcript accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    oversized.finalTranscript = "a";
    check(adapter.buildRequestJson(oversized, 1_000_000_000L) != null);
    oversized.finalTranscript = "a".repeat(4095);
    check(adapter.buildRequestJson(oversized, 1_000_000_000L) != null);
    oversized.finalTranscript = "";
    try {
      adapter.buildRequestJson(oversized, 1_000_000_000L);
      throw new AssertionError("empty transcript accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    oversized.finalTranscript = "\uD83D\uDE00".repeat(1024);
    check(oversized.finalTranscript.getBytes(
        java.nio.charset.StandardCharsets.UTF_8).length == 4096);
    check(adapter.buildRequestJson(oversized, 1_000_000_000L) != null);
    oversized.finalTranscript = "测".repeat(1366);
    check(oversized.finalTranscript.getBytes(
        java.nio.charset.StandardCharsets.UTF_8).length == 4098);
    try {
      adapter.buildRequestJson(oversized, 1_000_000_000L);
      throw new AssertionError("4098-byte UTF-8 transcript accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }

    final EhviewerDryRunPolicy.Result valid = adapter.parseAndValidate(
        request, response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L);
    check(valid.valid());
    check(valid.injectedEvents() == 0);
    final ModelRequest deadlineBoundary = request();
    deadlineBoundary.session.deadlineElapsedNanos = 3_000_000_000L;
    deadlineBoundary.deadlineElapsedNanos = 3_000_000_000L;
    check(adapter.parseAndValidate(deadlineBoundary,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    deadlineBoundary.deadlineElapsedNanos--;
    check(!adapter.parseAndValidate(deadlineBoundary,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());

    check(!adapter.parseAndValidate(request,
        response("deepseek-other", plan()), 1_000_000_000L).valid());
    check(!adapter.parseAndValidate(request,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()) + "garbage",
        1_000_000_000L).valid());
    check(!adapter.parseAndValidate(request,
        response(DeepSeekV4TextAdapter.MODEL_ID,
            plan().replace("FIND_TEXT", "SHELL")), 1_000_000_000L).valid());
    check(!adapter.parseAndValidate(request,
        "{\"model\":\"deepseek-v4\",\"choices\":[]}",
        1_000_000_000L).valid());
    check(!adapter.parseAndValidate(request,
        "x".repeat(DeepSeekV4TextAdapter.MAX_RESPONSE_BYTES + 1),
        1_000_000_000L).valid());
    check(!adapter.parseAndValidate(request,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        request.session.deadlineElapsedNanos).valid());
    final ModelRequest expired = request();
    expired.session.deadlineElapsedNanos = 999_999_999L;
    expired.deadlineElapsedNanos = expired.session.deadlineElapsedNanos;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    expired.session.deadlineElapsedNanos = 0;
    expired.deadlineElapsedNanos = 0;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    expired.session.deadlineElapsedNanos = -1;
    expired.deadlineElapsedNanos = -1;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    expired.session.deadlineElapsedNanos = Long.MAX_VALUE;
    expired.deadlineElapsedNanos = Long.MAX_VALUE;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()), 0).valid());
    check(adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        Long.MAX_VALUE - 1).valid());
    expired.session.deadlineElapsedNanos = Long.MIN_VALUE;
    expired.deadlineElapsedNanos = Long.MIN_VALUE;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()), -1).valid());
    final long overflowedDeadline = 1_000_000_000L + Long.MAX_VALUE;
    check(overflowedDeadline < 0);
    expired.session.deadlineElapsedNanos = overflowedDeadline;
    expired.deadlineElapsedNanos = overflowedDeadline;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()),
        1_000_000_000L).valid());
    expired.session.deadlineElapsedNanos = 0;
    expired.deadlineElapsedNanos = 0;
    check(!adapter.parseAndValidate(expired,
        response(DeepSeekV4TextAdapter.MODEL_ID, plan()), 0).valid());

    System.out.println("deepseek v4 text adapter checks passed: " + checks);
  }
}
