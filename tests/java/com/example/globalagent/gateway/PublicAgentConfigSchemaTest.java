package com.example.globalagent.gateway;

import java.math.BigDecimal;

public final class PublicAgentConfigSchemaTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static void rejects(String json) {
    boolean rejected = false;
    try {
      PublicAgentConfigSchema.parse(json);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected);
  }

  public static void main(String[] args) {
    final PublicAgentConfigSchema.ParsedConfig parsed =
        PublicAgentConfigSchema.parse(validConfig());
    check(parsed.providerCount() == 1);
    check(parsed.rawJson().equals(validConfig()));
    check(PublicAgentConfigSchema.parse(validConfig().replace(
        "global_agent_openai", "access_token")).providerCount() == 1);
    check(StrictJsonParser.parse("1e-2").equals(new BigDecimal("1e-2")));

    rejects(validConfig().replace("\"schemaVersion\":2",
        "\"schemaVersion\":1"));
    rejects(validConfig().replace("\"dryRun\":true", "\"dryRun\":false"));
    rejects(validConfig().replace("\"dryRun\":true",
        "\"dryRun\":false,\"apiKey\":\"not-allowed\""));
    rejects(validConfig().replace("\"model\":\"gpt-5.6-sol\"",
        "\"model\":\"gpt-5.6-sol\",\"token\":\"not-allowed\""));
    rejects(validConfig().replace("https://api.openai.com/v1",
        "http://api.openai.com/v1"));
    rejects(validConfig().replace(
        "\"openai-primary\":{\"kind\":\"openai-responses\"," +
            "\"apiBase\":\"https://api.openai.com/v1\"," +
            "\"credentialRef\":\"keystore://global_agent_openai\"," +
            "\"model\":\"gpt-5.6-sol\"," +
            "\"reasoningEffort\":\"low\",\"visionDetail\":\"low\"}",
        ""));
    rejects(validConfig().replace("keystore://global_agent_openai",
        "plain-text-value"));
    rejects(validConfig().replace("\"planner\":{\"provider\":\"openai-primary\"",
        "\"planner\":{\"provider\":\"missing\""));
    rejects(validConfig().replace("\"maxActionsPerPlan\":8",
        "\"maxActionsPerPlan\":9"));
    rejects(validConfig().replace("\"retainScreenshots\":false",
        "\"retainScreenshots\":true"));
    rejects(validConfig().replace("\"tap\",\"verify\"",
        "\"tap\",\"tap\""));
    rejects(validConfig().replace("\"tap\",\"verify\"",
        "\"shell\",\"verify\""));
    rejects(validConfig().replace("\"schemaVersion\":2",
        "\"schemaVersion\":2,\"schemaVersion\":2"));
    rejects(validConfig().replace("\"providers\":{",
        "\"providers\":{\"openai-primary\":{},"));
    rejects(validConfig().replace("\"timeoutMs\":900",
        "\"timeoutMs\":900.0"));
    rejects(validConfig().replace("\"schemaVersion\":2",
        "\"schemaVersion\":999999999999999999999999999999"));
    rejects(validConfig().replace("\"runtime\":\"openclaw-host\"",
        "\"runtime\":\"termux-untrusted\""));
    rejects(validConfig().replace("\"sendImage\":\"ask-once-per-session\"",
        "\"sendImage\":\"always\""));
    rejects(validConfig().replace("\"com.android.settings\"",
        "\"invalid package\""));
    rejects(validConfig().substring(0, validConfig().length() - 1));
    rejects(validConfig() + " true");

    System.out.println("public config schema checks passed: " + checks);
  }

  static String validConfig() {
    return "{" +
        "\"schemaVersion\":2," +
        "\"runtime\":\"openclaw-host\"," +
        "\"dryRun\":true," +
        "\"providers\":{" +
          "\"openai-primary\":{" +
            "\"kind\":\"openai-responses\"," +
            "\"apiBase\":\"https://api.openai.com/v1\"," +
            "\"credentialRef\":\"keystore://global_agent_openai\"," +
            "\"model\":\"gpt-5.6-sol\"," +
            "\"reasoningEffort\":\"low\"," +
            "\"visionDetail\":\"low\"}}," +
        "\"agents\":{" +
          "\"planner\":{\"provider\":\"openai-primary\",\"timeoutMs\":900}," +
          "\"verifier\":{\"provider\":\"openai-primary\",\"timeoutMs\":600," +
            "\"enabled\":false}}," +
        "\"privacy\":{" +
          "\"sendImage\":\"ask-once-per-session\"," +
          "\"redactNotifications\":true," +
          "\"redactKeyboard\":true," +
          "\"allowPackages\":[\"com.android.settings\"]," +
          "\"retainScreenshots\":false}," +
        "\"limits\":{" +
          "\"maxActionsPerPlan\":8," +
          "\"maxOutputTokens\":1200," +
          "\"maxImageLongEdge\":1280," +
          "\"maxRetries\":1," +
          "\"maxRequestsPerMinute\":12," +
          "\"dailyTokenBudget\":200000," +
          "\"endToEndDeadlineMs\":2000}," +
        "\"tools\":[\"observe_screen\",\"tap\",\"verify\"]}";
  }
}
