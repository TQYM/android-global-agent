package com.example.globalagent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class GatewayPackageAuthorizerTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  public static void main(String[] args) throws Exception {
    check(GatewayPackageAuthorizer.containsPackage(
        new String[] {"other", "com.example.globalagent.gateway"},
        "com.example.globalagent.gateway"));
    check(!GatewayPackageAuthorizer.containsPackage(
        new String[] {"other"}, "com.example.globalagent.gateway"));
    check(!GatewayPackageAuthorizer.containsPackage(null,
        "com.example.globalagent.gateway"));

    final byte[] certificate =
        "dedicated-gateway-certificate".getBytes(StandardCharsets.UTF_8);
    final byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(certificate);
    check(GatewayPackageAuthorizer.certificateMatches(certificate, digest));
    digest[0] ^= 0xff;
    check(!GatewayPackageAuthorizer.certificateMatches(certificate, digest));
    check(!GatewayPackageAuthorizer.certificateMatches(null, digest));
    check(!GatewayPackageAuthorizer.certificateMatches(certificate,
        new byte[31]));
    check(GatewayPackageAuthorizer.certificateMatchesAny(certificate,
        new byte[][] {new byte[32], MessageDigest.getInstance("SHA-256")
            .digest(certificate)}));
    check(!GatewayPackageAuthorizer.certificateMatchesAny(certificate,
        new byte[][] {new byte[32]}));

    System.out.println("gateway package authorizer checks passed: " + checks);
  }
}
