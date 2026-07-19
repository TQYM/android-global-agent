package com.example.globalagent.gateway;

import java.util.Set;

public final class PublicConfigCallPolicyTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static void rejects(Runnable operation) {
    boolean rejected = false;
    try {
      operation.run();
    } catch (IllegalArgumentException | SecurityException expected) {
      rejected = true;
    }
    check(rejected);
  }

  public static void main(String[] args) {
    PublicConfigCallPolicy.validate(PublicConfigImporter.ROOT_UID,
        PublicConfigImporter.IMPORT_METHOD, null, Set.of("config_b64"));
    check(true);
    PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, null, Set.of("config_b64"));
    check(true);

    rejects(() -> PublicConfigCallPolicy.validate(10_000,
        PublicConfigImporter.IMPORT_METHOD, null, Set.of("config_b64")));
    rejects(() -> PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        "read_public_config", null, Set.of("config_b64")));
    rejects(() -> PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, "unexpected", Set.of("config_b64")));
    rejects(() -> PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, null, null));
    rejects(() -> PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, null, Set.of()));
    rejects(() -> PublicConfigCallPolicy.validate(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, null,
        Set.of("config_b64", "api_key")));

    System.out.println("public config call policy checks passed: " + checks);
  }
}
