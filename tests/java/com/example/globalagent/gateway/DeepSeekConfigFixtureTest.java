package com.example.globalagent.gateway;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DeepSeekConfigFixtureTest {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new AssertionError("expected config path");
    }
    final String json = Files.readString(Path.of(args[0]));
    final PublicAgentConfigSchema.ParsedConfig parsed =
        PublicAgentConfigSchema.parse(json);
    if (parsed.providerCount() != 1 ||
        !json.contains("\"deepseek-v4-text\"") ||
        !json.contains("\"kind\": \"openai-compatible-text-only\"") ||
        !json.contains("\"apiBase\": \"https://api.deepseek.com/v1\"") ||
        !json.contains("\"credentialRef\": \"keystore://global_agent_deepseek_v4\"") ||
        !json.contains("\"model\": \"deepseek-v4\"") ||
        !json.contains("\"provider\": \"deepseek-v4-text\"") ||
        !json.contains("\"sendImage\": \"never\"") ||
        !json.contains("\"allowPackages\"") ||
        !json.contains("\"com.xjs.ehviewer\"") ||
        !json.contains("\"com.xjs.ehviewer.debug\"") ||
        !json.contains("\"dryRun\": true") ||
        json.contains("apiKey") || json.contains("authorization")) {
      throw new AssertionError("unsafe DeepSeek/Ehviewer fixture");
    }
    try {
      PublicAgentConfigSchema.parse(json.replace(
          "\"com.xjs.ehviewer\",\n      \"com.xjs.ehviewer.debug\"", ""));
      throw new AssertionError("empty allowlist accepted");
    } catch (IllegalArgumentException expected) {
      // The public schema rejects an empty target allowlist.
    }
    System.out.println("deepseek config fixture passed");
  }
}
