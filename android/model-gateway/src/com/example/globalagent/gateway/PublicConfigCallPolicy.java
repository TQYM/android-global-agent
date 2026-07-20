package com.example.globalagent.gateway;

import java.util.Set;

public final class PublicConfigCallPolicy {
  private static final Set<String> ALLOWED_EXTRA_KEYS = Set.of("config_b64");

  private PublicConfigCallPolicy() {}

  public static void validate(int callerUid, String method, String arg,
      Set<String> extraKeys) {
    if (callerUid != PublicConfigImporter.ROOT_UID &&
        callerUid != PublicConfigImporter.SHELL_UID) {
      throw new SecurityException("public config import requires root or shell");
    }
    if (!PublicConfigImporter.IMPORT_METHOD.equals(method)) {
      throw new IllegalArgumentException("unsupported public config method");
    }
    if (arg != null) {
      throw new IllegalArgumentException("public config arg is unsupported");
    }
    if (extraKeys == null || !extraKeys.equals(ALLOWED_EXTRA_KEYS)) {
      throw new IllegalArgumentException("invalid public config extras");
    }
  }
}
