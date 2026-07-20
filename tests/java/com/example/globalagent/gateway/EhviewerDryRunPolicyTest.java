package com.example.globalagent.gateway;

import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.ModelRequest;
import com.example.globalagent.v2.SessionHandle;

public final class EhviewerDryRunPolicyTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static ModelRequest request(String packageName) {
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
    request.session.deadlineElapsedNanos = 2_000_000_000L;
    request.deadlineElapsedNanos = request.session.deadlineElapsedNanos;
    request.finalTranscript = "open favorites";
    request.focusedPackage = packageName;
    request.providerProfile = DeepSeekV4TextAdapter.PROFILE_ID;
    request.imageAllowed = false;
    return request;
  }

  private static String validPlan(String packageName) {
    return "{\"schemaVersion\":2,\"dryRun\":true,\"sessionId\":41," +
        "\"revision\":3,\"focusedPackage\":\"" + packageName + "\"," +
        "\"confidenceMilli\":900,\"actions\":[" +
        "{\"id\":1,\"type\":\"FIND_TEXT\",\"targetText\":\"Favorites\"}," +
        "{\"id\":2,\"type\":\"TAP_CANDIDATE\",\"candidateId\":17}," +
        "{\"id\":3,\"type\":\"WAIT\",\"waitMs\":200}," +
        "{\"id\":4,\"type\":\"VERIFY\",\"targetText\":\"Favorites\"}]}";
  }

  private static void denied(ModelRequest request, String json) {
    final EhviewerDryRunPolicy.Result result =
        EhviewerDryRunPolicy.validate(request, json);
    check(!result.valid());
    check(result.injectedEvents() == 0);
  }

  public static void main(String[] args) {
    final ModelRequest release = request(EhviewerDryRunPolicy.RELEASE_PACKAGE);
    final EhviewerDryRunPolicy.Result valid = EhviewerDryRunPolicy.validate(
        release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE));
    check(valid.valid());
    check(valid.injectedEvents() == 0);
    check(valid.plan().actions().size() == 4);
    check(valid.plan().actions().get(1).candidateId() == 17);
    check(EhviewerDryRunPolicy.isAllowedPackage(
        EhviewerDryRunPolicy.DEBUG_PACKAGE));
    check(!EhviewerDryRunPolicy.isAllowedPackage("com.android.settings"));
    check(EhviewerDryRunPolicy.isAuthorizedBridgeCaller(10001, 10001, 10001,
        true,
        EhviewerDryRunPolicy.RELEASE_PACKAGE));
    check(!EhviewerDryRunPolicy.isAuthorizedBridgeCaller(10002, 10001, 10001,
        true,
        EhviewerDryRunPolicy.RELEASE_PACKAGE));
    check(!EhviewerDryRunPolicy.isAuthorizedBridgeCaller(10001, 10001, 10002,
        true,
        EhviewerDryRunPolicy.RELEASE_PACKAGE));
    check(!EhviewerDryRunPolicy.isAuthorizedBridgeCaller(10001, 10001, 10001,
        false,
        EhviewerDryRunPolicy.RELEASE_PACKAGE));
    check(!EhviewerDryRunPolicy.isAuthorizedBridgeCaller(10001, 10001, 10001,
        true,
        "com.android.settings"));
    try {
      EhviewerDryRunPolicy.requireZeroInjectedEvents(1);
      throw new AssertionError("non-zero dry-run events accepted");
    } catch (SecurityException expected) {
      checks++;
    }
    try {
      EhviewerDryRunPolicy.validateDryRunInputBoundary(1);
      throw new AssertionError("input boundary accepted injection");
    } catch (SecurityException expected) {
      checks++;
    }

    denied(request("com.android.settings"),
        validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"revision\":3", "\"revision\":4"));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"confidenceMilli\":900", "\"confidenceMilli\":799"));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"id\":2", "\"id\":1"));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"type\":\"WAIT\"", "\"type\":\"INPUT_TEXT\""));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"dryRun\":true", "\"dryRun\":false"));
    denied(release, validPlan(EhviewerDryRunPolicy.RELEASE_PACKAGE)
        .replace("\"actions\":[", "\"unknown\":1,\"actions\":["));

    final String debugPlan = validPlan(EhviewerDryRunPolicy.DEBUG_PACKAGE);
    check(EhviewerDryRunPolicy.validate(
        request(EhviewerDryRunPolicy.DEBUG_PACKAGE), debugPlan).valid());

    System.out.println("ehviewer dry-run policy checks passed: " + checks);
  }
}
