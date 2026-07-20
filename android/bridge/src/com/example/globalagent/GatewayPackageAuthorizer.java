package com.example.globalagent;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class GatewayPackageAuthorizer {
  static final int SHA256_BYTES = 32;

  private final PackageManager packageManager;
  private final String expectedPackage;
  private final byte[][] expectedCertificateSha256;

  GatewayPackageAuthorizer(PackageManager packageManager,
      String expectedPackage, byte[] expectedCertificateSha256) {
    if (packageManager == null) {
      throw new NullPointerException("packageManager");
    }
    if (expectedPackage == null || expectedPackage.isEmpty() ||
        expectedCertificateSha256 == null ||
        expectedCertificateSha256.length != SHA256_BYTES) {
      throw new IllegalArgumentException("invalid gateway identity");
    }
    this.packageManager = packageManager;
    this.expectedPackage = expectedPackage;
    this.expectedCertificateSha256 = new byte[][] {
        expectedCertificateSha256.clone()
    };
  }

  GatewayPackageAuthorizer(PackageManager packageManager,
      String expectedPackage, byte[][] expectedCertificateSha256) {
    if (packageManager == null) {
      throw new NullPointerException("packageManager");
    }
    if (expectedPackage == null || expectedPackage.isEmpty() ||
        expectedCertificateSha256 == null || expectedCertificateSha256.length == 0) {
      throw new IllegalArgumentException("invalid gateway identity");
    }
    this.packageManager = packageManager;
    this.expectedPackage = expectedPackage;
    this.expectedCertificateSha256 = new byte[expectedCertificateSha256.length][];
    for (int index = 0; index < expectedCertificateSha256.length; ++index) {
      final byte[] digest = expectedCertificateSha256[index];
      if (digest == null || digest.length != SHA256_BYTES) {
        throw new IllegalArgumentException("invalid gateway certificate digest");
      }
      this.expectedCertificateSha256[index] = digest.clone();
    }
  }

  void requireAuthorized(int uid) {
    if (uid < 0 || !containsPackage(packageManager.getPackagesForUid(uid),
        expectedPackage)) {
      throw new SecurityException("gateway package does not own caller UID");
    }

    final PackageInfo info;
    try {
      info = packageManager.getPackageInfo(expectedPackage,
          PackageManager.PackageInfoFlags.of(
              PackageManager.GET_SIGNING_CERTIFICATES));
    } catch (PackageManager.NameNotFoundException exception) {
      throw new SecurityException("gateway package is not installed", exception);
    }
    final ApplicationInfo applicationInfo = info.applicationInfo;
    final SigningInfo signingInfo = info.signingInfo;
    final Signature[] signers = signingInfo == null ? null :
        signingInfo.getApkContentsSigners();
    if (applicationInfo == null || applicationInfo.uid != uid ||
        signers == null || signers.length != 1 ||
        !certificateMatchesAny(signers[0].toByteArray(),
            expectedCertificateSha256)) {
      throw new SecurityException("gateway identity verification failed");
    }
  }

  static boolean containsPackage(String[] packages, String expectedPackage) {
    if (packages == null || expectedPackage == null) {
      return false;
    }
    for (String packageName : packages) {
      if (expectedPackage.equals(packageName)) {
        return true;
      }
    }
    return false;
  }

  static boolean certificateMatches(byte[] encodedCertificate,
      byte[] expectedSha256) {
    if (encodedCertificate == null || expectedSha256 == null ||
        expectedSha256.length != SHA256_BYTES) {
      return false;
    }
    try {
      final byte[] actual = MessageDigest.getInstance("SHA-256")
          .digest(encodedCertificate);
      return MessageDigest.isEqual(actual, expectedSha256);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 unavailable", impossible);
    }
  }

  static boolean certificateMatchesAny(byte[] encodedCertificate,
      byte[][] expectedSha256) {
    if (expectedSha256 == null) {
      return false;
    }
    for (byte[] expected : expectedSha256) {
      if (certificateMatches(encodedCertificate, expected)) {
        return true;
      }
    }
    return false;
  }
}
