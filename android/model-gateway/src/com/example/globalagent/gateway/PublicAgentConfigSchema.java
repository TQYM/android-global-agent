package com.example.globalagent.gateway;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PublicAgentConfigSchema {
  public static final int SCHEMA_VERSION = 2;
  public static final int MAX_CONFIG_BYTES = 96 * 1024;
  private static final int MAX_PROVIDERS = 8;
  private static final int MAX_ALLOW_PACKAGES = 64;
  private static final int MAX_TOOLS = 16;

  private static final Set<String> ROOT_FIELDS = Set.of(
      "schemaVersion", "runtime", "dryRun", "providers", "agents",
      "privacy", "limits", "tools");
  private static final Set<String> PROVIDER_FIELDS = Set.of(
      "kind", "apiBase", "credentialRef", "model", "reasoningEffort",
      "visionDetail");
  private static final Set<String> AGENT_FIELDS = Set.of(
      "provider", "timeoutMs", "enabled");
  private static final Set<String> PRIVACY_FIELDS = Set.of(
      "sendImage", "redactNotifications", "redactKeyboard", "allowPackages",
      "retainScreenshots");
  private static final Set<String> LIMIT_FIELDS = Set.of(
      "maxActionsPerPlan", "maxOutputTokens", "maxImageLongEdge",
      "maxRetries", "maxRequestsPerMinute", "dailyTokenBudget",
      "endToEndDeadlineMs");
  private static final Set<String> PROVIDER_KINDS = Set.of(
      "openai-responses", "openai-compatible",
      "openai-compatible-text-only", "anthropic-messages");
  private static final Set<String> REASONING_EFFORTS = Set.of(
      "low", "medium", "high", "xhigh", "max", "ultra");
  private static final Set<String> VISION_DETAILS = Set.of(
      "low", "high", "original", "auto");
  private static final Set<String> IMAGE_POLICIES = Set.of(
      "never", "ask-once-per-session");
  private static final Set<String> ALLOWED_TOOLS = Set.of(
      "observe_screen", "get_ui_context", "find_text", "tap", "swipe",
      "pinch", "input_text", "press_back", "wait_for", "verify");
  private static final Set<String> SECRET_FIELD_NAMES = Set.of(
      "apikey", "token", "secret", "password", "authorization",
      "accesstoken", "refreshtoken");

  private PublicAgentConfigSchema() {}

  public static ParsedConfig parse(String json) {
    if (json == null || json.isEmpty() ||
        json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
      throw invalid("config-size");
    }
    final Map<String, Object> root = object(StrictJsonParser.parse(json));
    rejectSecretFields(root);
    requireExactFields(root, ROOT_FIELDS, ROOT_FIELDS);
    requireLong(root, "schemaVersion", SCHEMA_VERSION, SCHEMA_VERSION);
    requireEnum(root, "runtime", Set.of("openclaw-host"));
    requireBoolean(root, "dryRun");
    if (!Boolean.TRUE.equals(root.get("dryRun"))) {
      throw invalid("dry-run-required");
    }

    final Map<String, Object> providers = object(root.get("providers"));
    if (providers.isEmpty() || providers.size() > MAX_PROVIDERS) {
      throw invalid("provider-count");
    }
    for (Map.Entry<String, Object> entry : providers.entrySet()) {
      if (!isProfileName(entry.getKey())) {
        throw invalid("provider-name");
      }
      validateProvider(object(entry.getValue()));
    }

    validateAgents(object(root.get("agents")), providers.keySet());
    validatePrivacy(object(root.get("privacy")));
    validateLimits(object(root.get("limits")));
    validateTools(array(root.get("tools")));
    return new ParsedConfig(json, providers.size());
  }

  private static void validateProvider(Map<String, Object> provider) {
    requireAllowedFields(provider, PROVIDER_FIELDS);
    requireFields(provider, Set.of("kind", "apiBase", "credentialRef", "model"));
    requireEnum(provider, "kind", PROVIDER_KINDS);
    final String apiBase = string(provider.get("apiBase"));
    if (!ModelGatewayPolicy.isEndpointAllowed(apiBase)) {
      throw invalid("provider-endpoint");
    }
    final String credentialRef = string(provider.get("credentialRef"));
    if (!credentialRef.startsWith("keystore://") ||
        !ModelGatewayPolicy.isCredentialAliasValid(
            credentialRef.substring("keystore://".length()))) {
      throw invalid("credential-ref");
    }
    if (!ModelGatewayPolicy.isModelIdValid(string(provider.get("model")))) {
      throw invalid("model-id");
    }
    optionalEnum(provider, "reasoningEffort", REASONING_EFFORTS);
    optionalEnum(provider, "visionDetail", VISION_DETAILS);
  }

  private static void validateAgents(Map<String, Object> agents,
      Set<String> providerNames) {
    requireAllowedFields(agents, Set.of("planner", "verifier"));
    requireFields(agents, Set.of("planner"));
    validateAgent(object(agents.get("planner")), providerNames);
    if (agents.containsKey("verifier")) {
      validateAgent(object(agents.get("verifier")), providerNames);
    }
  }

  private static void validateAgent(Map<String, Object> agent,
      Set<String> providerNames) {
    requireAllowedFields(agent, AGENT_FIELDS);
    requireFields(agent, Set.of("provider", "timeoutMs"));
    if (!providerNames.contains(string(agent.get("provider")))) {
      throw invalid("agent-provider");
    }
    requireLong(agent, "timeoutMs", 100, 10_000);
    if (agent.containsKey("enabled")) {
      requireBoolean(agent, "enabled");
    }
  }

  private static void validatePrivacy(Map<String, Object> privacy) {
    requireExactFields(privacy, PRIVACY_FIELDS, PRIVACY_FIELDS);
    requireEnum(privacy, "sendImage", IMAGE_POLICIES);
    requireBoolean(privacy, "redactNotifications");
    requireBoolean(privacy, "redactKeyboard");
    requireBoolean(privacy, "retainScreenshots");
    if (Boolean.TRUE.equals(privacy.get("retainScreenshots"))) {
      throw invalid("screenshot-retention");
    }
    final List<Object> packages = array(privacy.get("allowPackages"));
    if (packages.size() > MAX_ALLOW_PACKAGES) {
      throw invalid("package-count");
    }
    final Set<String> seen = new HashSet<>();
    for (Object value : packages) {
      final String packageName = string(value);
      if (!isPackageName(packageName) || !seen.add(packageName)) {
        throw invalid("package-name");
      }
    }
  }

  private static void validateLimits(Map<String, Object> limits) {
    requireExactFields(limits, LIMIT_FIELDS, LIMIT_FIELDS);
    requireLong(limits, "maxActionsPerPlan", 1, 8);
    requireLong(limits, "maxOutputTokens", 1, 1200);
    requireLong(limits, "maxImageLongEdge", 64, 1280);
    requireLong(limits, "maxRetries", 0, 1);
    requireLong(limits, "maxRequestsPerMinute", 1, 12);
    requireLong(limits, "dailyTokenBudget", 1, 10_000_000);
    requireLong(limits, "endToEndDeadlineMs", 200, 2000);
  }

  private static void validateTools(List<Object> tools) {
    if (tools.isEmpty() || tools.size() > MAX_TOOLS) {
      throw invalid("tool-count");
    }
    final Set<String> seen = new HashSet<>();
    for (Object value : tools) {
      final String tool = string(value);
      if (!ALLOWED_TOOLS.contains(tool) || !seen.add(tool)) {
        throw invalid("tool-name");
      }
    }
  }

  private static void rejectSecretFields(Object value) {
    if (value instanceof Map<?, ?>) {
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        final String key = string(entry.getKey());
        final String normalized = key.toLowerCase().replace("_", "")
            .replace("-", "");
        if (!"credentialref".equals(normalized) &&
            SECRET_FIELD_NAMES.contains(normalized)) {
          throw invalid("secret-field");
        }
        rejectSecretFields(entry.getValue());
      }
    } else if (value instanceof List<?>) {
      for (Object item : (List<?>) value) {
        rejectSecretFields(item);
      }
    }
  }

  private static boolean isProfileName(String value) {
    return value != null && value.length() <= 64 && value.matches("[A-Za-z0-9._-]+");
  }

  private static boolean isPackageName(String value) {
    return value != null && value.length() <= 255 &&
        value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
  }

  private static void requireExactFields(Map<String, Object> object,
      Set<String> allowed, Set<String> required) {
    requireAllowedFields(object, allowed);
    requireFields(object, required);
  }

  private static void requireAllowedFields(Map<String, Object> object,
      Set<String> allowed) {
    if (!allowed.containsAll(object.keySet())) {
      throw invalid("unknown-field");
    }
  }

  private static void requireFields(Map<String, Object> object,
      Set<String> required) {
    if (!object.keySet().containsAll(required)) {
      throw invalid("missing-field");
    }
  }

  private static void requireEnum(Map<String, Object> object, String field,
      Set<String> allowed) {
    if (!allowed.contains(string(object.get(field)))) {
      throw invalid("enum-value");
    }
  }

  private static void optionalEnum(Map<String, Object> object, String field,
      Set<String> allowed) {
    if (object.containsKey(field)) {
      requireEnum(object, field, allowed);
    }
  }

  private static void requireBoolean(Map<String, Object> object, String field) {
    if (!(object.get(field) instanceof Boolean)) {
      throw invalid("boolean-value");
    }
  }

  private static void requireLong(Map<String, Object> object, String field,
      long minimum, long maximum) {
    final Object value = object.get(field);
    if (!(value instanceof Long) || (Long) value < minimum ||
        (Long) value > maximum) {
      throw invalid("integer-value");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw invalid("object-value");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value) {
    if (!(value instanceof List<?>)) {
      throw invalid("array-value");
    }
    return (List<Object>) value;
  }

  private static String string(Object value) {
    if (!(value instanceof String)) {
      throw invalid("string-value");
    }
    return (String) value;
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException("invalid-public-config:" + code);
  }

  public static final class ParsedConfig {
    private final String rawJson;
    private final int providerCount;

    private ParsedConfig(String rawJson, int providerCount) {
      this.rawJson = rawJson;
      this.providerCount = providerCount;
    }

    public String rawJson() {
      return rawJson;
    }

    public int providerCount() {
      return providerCount;
    }
  }
}
