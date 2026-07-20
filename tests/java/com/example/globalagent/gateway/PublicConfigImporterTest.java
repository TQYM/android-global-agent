package com.example.globalagent.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class PublicConfigImporterTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static void rejects(ThrowingRunnable operation) {
    boolean rejected = false;
    try {
      operation.run();
    } catch (IllegalArgumentException | SecurityException | IOException expected) {
      rejected = true;
    }
    check(rejected);
  }

  public static void main(String[] args) throws Exception {
    final MemoryStore store = new MemoryStore();
    final PublicConfigImporter importer = new PublicConfigImporter(store);
    final String encoded = encode(PublicAgentConfigSchemaTest.validConfig());

    check(importer.importConfig(PublicConfigImporter.ROOT_UID,
        PublicConfigImporter.IMPORT_METHOD, encoded).providerCount() == 1);
    check(store.writes == 1);
    check(store.value.equals(PublicAgentConfigSchemaTest.validConfig()));
    check(importer.importConfig(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, encoded).providerCount() == 1);
    check(store.writes == 2);

    rejects(() -> importer.importConfig(10_000,
        PublicConfigImporter.IMPORT_METHOD, encoded));
    rejects(() -> importer.importConfig(PublicConfigImporter.SHELL_UID,
        "read_public_config", encoded));
    rejects(() -> importer.importConfig(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, "%%%"));
    rejects(() -> importer.importConfig(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD, ""));
    rejects(() -> importer.importConfig(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD,
        Base64.getEncoder().encodeToString(new byte[] {(byte) 0xc3, 0x28})));
    rejects(() -> importer.importConfig(PublicConfigImporter.SHELL_UID,
        PublicConfigImporter.IMPORT_METHOD,
        encode(PublicAgentConfigSchemaTest.validConfig().replace(
            "\"dryRun\":true", "\"dryRun\":true,\"secret\":\"x\""))));
    check(store.writes == 2);

    final PublicConfigImporter failingImporter =
        new PublicConfigImporter(value -> { throw new IOException("disk"); });
    rejects(() -> failingImporter.importConfig(PublicConfigImporter.ROOT_UID,
        PublicConfigImporter.IMPORT_METHOD, encoded));

    System.out.println("public config importer checks passed: " + checks);
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(
        value.getBytes(StandardCharsets.UTF_8));
  }

  private interface ThrowingRunnable {
    void run() throws IOException;
  }

  private static final class MemoryStore
      implements PublicConfigImporter.ConfigStore {
    private String value;
    private int writes;

    @Override
    public void replace(String validatedJson) {
      value = validatedJson;
      writes++;
    }
  }
}
