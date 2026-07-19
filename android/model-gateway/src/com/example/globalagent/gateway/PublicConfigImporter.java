package com.example.globalagent.gateway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PublicConfigImporter {
  public static final String IMPORT_METHOD = "import_public_config";
  public static final int ROOT_UID = 0;
  public static final int SHELL_UID = 2000;
  public static final int MAX_ENCODED_CHARS = 128 * 1024;

  private final ConfigStore store;

  public PublicConfigImporter(ConfigStore store) {
    if (store == null) {
      throw new NullPointerException("store");
    }
    this.store = store;
  }

  public PublicAgentConfigSchema.ParsedConfig importConfig(int callerUid,
      String method, String encodedConfig) throws IOException {
    if (callerUid != ROOT_UID && callerUid != SHELL_UID) {
      throw new SecurityException("public config import requires root or shell");
    }
    if (!IMPORT_METHOD.equals(method)) {
      throw new IllegalArgumentException("unsupported public config method");
    }
    if (encodedConfig == null || encodedConfig.isEmpty() ||
        encodedConfig.length() > MAX_ENCODED_CHARS) {
      throw new IllegalArgumentException("invalid encoded public config size");
    }

    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encodedConfig);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("invalid public config encoding");
    }
    if (decoded.length == 0 ||
        decoded.length > PublicAgentConfigSchema.MAX_CONFIG_BYTES) {
      throw new IllegalArgumentException("invalid decoded public config size");
    }

    final String json = decodeUtf8(decoded);
    final PublicAgentConfigSchema.ParsedConfig parsed =
        PublicAgentConfigSchema.parse(json);
    store.replace(parsed.rawJson());
    return parsed;
  }

  private static String decodeUtf8(byte[] value) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(value)).toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("invalid public config utf8");
    }
  }

  public interface ConfigStore {
    void replace(String validatedJson) throws IOException;
  }
}
