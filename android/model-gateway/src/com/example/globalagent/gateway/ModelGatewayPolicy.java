package com.example.globalagent.gateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public final class ModelGatewayPolicy {
  public static final int PROTOCOL_VERSION = 1;
  public static final int MAX_MODEL_ID_BYTES = 128;
  public static final int MAX_CREDENTIAL_ALIAS_BYTES = 64;
  public static final int MAX_INTENT_TEXT_BYTES = 256;

  public static final int INTENT_NOOP = 0;
  public static final int INTENT_NAVIGATE = 1;
  public static final int INTENT_SEARCH = 2;
  public static final int INTENT_SELECT = 3;
  public static final int INTENT_INPUT_TEXT = 4;
  public static final int INTENT_CONFIRM_REQUIRED = 5;

  private ModelGatewayPolicy() {}

  public static boolean isEndpointAllowed(String endpoint) {
    if (endpoint == null || endpoint.isEmpty() || endpoint.indexOf('%') >= 0 ||
        endpoint.indexOf('\\') >= 0 || !isAscii(endpoint)) {
      return false;
    }
    try {
      final URI uri = new URI(endpoint);
      final int port = uri.getPort();
      return "https".equalsIgnoreCase(uri.getScheme()) &&
          uri.getHost() != null && !uri.getHost().isEmpty() &&
          uri.getRawUserInfo() == null && uri.getRawQuery() == null &&
          uri.getRawFragment() == null && (port == -1 || port == 443) &&
          uri.normalize().equals(uri);
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  public static boolean isModelIdValid(String modelId) {
    return isBoundedAsciiToken(modelId, MAX_MODEL_ID_BYTES, true);
  }

  public static boolean isCredentialAliasValid(String alias) {
    return isBoundedAsciiToken(alias, MAX_CREDENTIAL_ALIAS_BYTES, false);
  }

  public static boolean isIntentResponseValid(int protocolVersion,
      long sessionId, long revision, int intent, int confidenceMilli,
      String targetText, boolean requiresConfirmation) {
    if (protocolVersion != PROTOCOL_VERSION || sessionId <= 0 || revision < 0 ||
        intent < INTENT_NOOP || intent > INTENT_CONFIRM_REQUIRED ||
        confidenceMilli < 0 || confidenceMilli > 1000 ||
        !isBoundedText(targetText, MAX_INTENT_TEXT_BYTES) ||
        (intent != INTENT_NOOP && targetText.isEmpty())) {
      return false;
    }
    return intent != INTENT_CONFIRM_REQUIRED || requiresConfirmation;
  }

  private static boolean isBoundedAsciiToken(String value, int maxBytes,
      boolean allowSlashAndColon) {
    if (value == null || value.isEmpty() ||
        value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      return false;
    }
    for (int index = 0; index < value.length(); ++index) {
      final char character = value.charAt(index);
      final boolean common = Character.isLetterOrDigit(character) ||
          character == '.' || character == '_' || character == '-';
      if (!common && !(allowSlashAndColon &&
          (character == '/' || character == ':'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isBoundedText(String value, int maxBytes) {
    return value != null && value.indexOf('\0') < 0 &&
        value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }

  private static boolean isAscii(String value) {
    for (int index = 0; index < value.length(); ++index) {
      if (value.charAt(index) > 0x7f) {
        return false;
      }
    }
    return true;
  }
}
