package com.example.globalagent.gateway;

import com.example.globalagent.v2.ModelRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DeepSeekV4TextAdapter implements TextOnlyDryRunAdapter {
  static final String PROVIDER_KIND = "openai-compatible-text-only";
  static final String MODEL_ID = "deepseek-v4";
  static final String PROFILE_ID = "deepseek-v4-text";
  static final int MAX_RESPONSE_BYTES = 64 * 1024;
  static final int MAX_TRANSCRIPT_BYTES = ModelGatewayV2Policy.MAX_TRANSCRIPT_BYTES;

  private static final Set<String> RESPONSE_FIELDS = Set.of(
      "id", "object", "created", "model", "choices", "usage",
      "system_fingerprint");
  private static final Set<String> CHOICE_FIELDS = Set.of(
      "index", "message", "finish_reason", "logprobs");
  private static final Set<String> MESSAGE_FIELDS = Set.of(
      "role", "content", "reasoning_content", "tool_calls");
  private static final String SYSTEM_PROMPT =
      "Return one JSON object only. This is a dry run for the authorized " +
      "Ehviewer app. Allowed actions: FIND_TEXT, TAP_CANDIDATE, PRESS_BACK, " +
      "WAIT, VERIFY. Never emit coordinates, text input, URIs, shell, settings, " +
      "permissions, account, message, purchase, install, or security actions.";

  @Override
  public String providerKind() {
    return PROVIDER_KIND;
  }

  @Override
  public String modelId() {
    return MODEL_ID;
  }

  @Override
  public String buildRequestJson(ModelRequest request) {
    return buildRequestJson(request,
        android.os.SystemClock.elapsedRealtimeNanos());
  }

  String buildRequestJson(ModelRequest request, long nowElapsedNanos) {
    if (request == null || request.finalTranscript == null ||
        request.finalTranscript.getBytes(StandardCharsets.UTF_8).length >
            MAX_TRANSCRIPT_BYTES) {
      throw new IllegalArgumentException("deepseek transcript too large");
    }
    if (!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(request,
        nowElapsedNanos)) {
      throw new IllegalArgumentException("invalid deepseek dry-run request");
    }
    final String userContent = "sessionId=" + request.session.sessionId +
        "\nrevision=" + request.session.revision +
        "\nfocusedPackage=" + request.focusedPackage +
        "\nfinalTranscript=" + request.finalTranscript;
    return "{\"model\":" + quote(MODEL_ID) +
        ",\"messages\":[{\"role\":\"system\",\"content\":" +
        quote(SYSTEM_PROMPT) + "},{\"role\":\"user\",\"content\":" +
        quote(userContent) + "}],\"response_format\":{" +
        "\"type\":\"json_object\"},\"stream\":false}";
  }

  @Override
  public EhviewerDryRunPolicy.Result parseAndValidate(ModelRequest request,
      String providerResponseJson) {
    return parseAndValidate(request, providerResponseJson,
        android.os.SystemClock.elapsedRealtimeNanos());
  }

  EhviewerDryRunPolicy.Result parseAndValidate(ModelRequest request,
      String providerResponseJson, long nowElapsedNanos) {
    if (!ModelGatewayV2Policy.isDeepSeekDryRunRequestValid(
        request, nowElapsedNanos)) {
      return EhviewerDryRunPolicy.Result.denied("GA_PLAN_REQUEST_STALE");
    }
    if (providerResponseJson == null ||
        providerResponseJson.getBytes(StandardCharsets.UTF_8).length >
            MAX_RESPONSE_BYTES) {
      return EhviewerDryRunPolicy.Result.denied("GA_PROVIDER_RESPONSE_INVALID");
    }
    try {
      final Map<String, Object> response = object(
          StrictJsonParser.parse(providerResponseJson));
      requireAllowedFields(response, RESPONSE_FIELDS);
      requireFields(response, Set.of("id", "model", "choices"));
      if (!MODEL_ID.equals(string(response.get("model")))) {
        return EhviewerDryRunPolicy.Result.denied("GA_PROVIDER_MODEL_MISMATCH");
      }
      final List<Object> choices = array(response.get("choices"));
      if (choices.size() != 1) {
        throw invalid();
      }
      final Map<String, Object> choice = object(choices.get(0));
      requireAllowedFields(choice, CHOICE_FIELDS);
      requireFields(choice, Set.of("index", "message", "finish_reason"));
      if (!Long.valueOf(0).equals(choice.get("index")) ||
          !"stop".equals(string(choice.get("finish_reason")))) {
        throw invalid();
      }
      final Map<String, Object> message = object(choice.get("message"));
      requireAllowedFields(message, MESSAGE_FIELDS);
      requireFields(message, Set.of("role", "content"));
      if (!"assistant".equals(string(message.get("role"))) ||
          (message.containsKey("tool_calls") &&
              message.get("tool_calls") != null)) {
        throw invalid();
      }
      return EhviewerDryRunPolicy.validate(request,
          string(message.get("content")));
    } catch (IllegalArgumentException exception) {
      return EhviewerDryRunPolicy.Result.denied("GA_PROVIDER_RESPONSE_INVALID");
    }
  }

  private static String quote(String value) {
    final StringBuilder output = new StringBuilder(value.length() + 16);
    output.append('"');
    for (int index = 0; index < value.length(); ++index) {
      final char character = value.charAt(index);
      switch (character) {
        case '"': output.append("\\\""); break;
        case '\\': output.append("\\\\"); break;
        case '\b': output.append("\\b"); break;
        case '\f': output.append("\\f"); break;
        case '\n': output.append("\\n"); break;
        case '\r': output.append("\\r"); break;
        case '\t': output.append("\\t"); break;
        default:
          if (character < 0x20) {
            output.append(String.format("\\u%04x", (int) character));
          } else {
            output.append(character);
          }
      }
    }
    return output.append('"').toString();
  }

  private static void requireAllowedFields(Map<String, Object> object,
      Set<String> allowed) {
    if (!allowed.containsAll(object.keySet())) {
      throw invalid();
    }
  }

  private static void requireFields(Map<String, Object> object,
      Set<String> required) {
    if (!object.keySet().containsAll(required)) {
      throw invalid();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw invalid();
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value) {
    if (!(value instanceof List<?>)) {
      throw invalid();
    }
    return (List<Object>) value;
  }

  private static String string(Object value) {
    if (!(value instanceof String)) {
      throw invalid();
    }
    return (String) value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("invalid-deepseek-response");
  }
}
