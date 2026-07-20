package com.example.globalagent.gateway;

import com.example.globalagent.v2.ActionDto;
import com.example.globalagent.v2.ActionPlan;
import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.ImagePayload;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.OcrNode;
import com.example.globalagent.v2.PerceptionEnvelope;
import com.example.globalagent.v2.RectDto;
import com.example.globalagent.v2.SensitiveRegion;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

public final class ProtocolV2Validator {
  public static final int SERVICE_INSTANCE_ID_BYTES = 16;
  public static final int TOKEN_BYTES = 32;
  public static final int DIGEST_BYTES = 32;
  // Image bytes travel through a sealed FD, not inside the Binder parcel.
  public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
  public static final int MAX_ACTIONS = 8;
  public static final int MAX_OCR_NODES = 128;
  public static final int MAX_REDACTIONS = 64;
  public static final int MAX_ACTION_TEXT_BYTES = 4096;
  public static final int MAX_OCR_TEXT_BYTES = 512;
  public static final int MAX_NORMALIZED_COORDINATE = 10000;
  public static final long MAX_ACTION_DURATION_MILLIS = 2000;
  public static final long MAX_WAIT_MILLIS = 2000;

  private ProtocolV2Validator() {}

  public static boolean isCaptureGrantValid(CaptureGrant grant,
      long nowElapsedNanos) {
    return nowElapsedNanos >= 0 && grant != null &&
        grant.protocolVersion == IV2GlobalAgent.PROTOCOL_VERSION &&
        hasLength(grant.serviceInstanceId, SERVICE_INSTANCE_ID_BYTES) &&
        hasLength(grant.token, TOKEN_BYTES) && grant.grantId > 0 &&
        grant.sessionId > 0 && grant.revision >= 0 && grant.focusEpoch >= 0 &&
        grant.displayId >= 0 && isRectValid(grant.crop) &&
        grant.expiresAtElapsedNanos > nowElapsedNanos &&
        grant.expiresAtElapsedNanos - nowElapsedNanos <= 3_000_000_000L &&
        grant.maxImageBytes > 0 && grant.maxImageBytes <= MAX_IMAGE_BYTES &&
        grant.redactionPolicyVersion > 0;
  }

  public static boolean isActionPlanValid(ActionPlan plan,
      long nowElapsedNanos) {
    if (nowElapsedNanos < 0 || plan == null ||
        plan.protocolVersion != IV2GlobalAgent.PROTOCOL_VERSION ||
        !hasLength(plan.serviceInstanceId, SERVICE_INSTANCE_ID_BYTES) ||
        plan.sessionId <= 0 || plan.expectedRevision < 0 ||
        plan.perceptionId <= 0 ||
        !hasLength(plan.perceptionDigest, DIGEST_BYTES) ||
        plan.expectedFocusEpoch < 0 ||
        !hasLength(plan.expectedFocusDigest, DIGEST_BYTES) ||
        plan.clientPlanId <= 0 || plan.deadlineElapsedNanos <= nowElapsedNanos ||
        plan.actions == null || plan.actions.length == 0 ||
        plan.actions.length > MAX_ACTIONS) {
      return false;
    }
    final HashSet<Long> actionIds = new HashSet<>();
    for (ActionDto action : plan.actions) {
      if (!isActionValid(action) || !actionIds.add(action.actionId)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isPerceptionValid(PerceptionEnvelope perception) {
    if (perception == null ||
        perception.protocolVersion != IV2GlobalAgent.PROTOCOL_VERSION ||
        !hasLength(perception.serviceInstanceId, SERVICE_INSTANCE_ID_BYTES) ||
        perception.sessionId <= 0 || perception.revision < 0 ||
        perception.perceptionId <= 0 || perception.capturedAtElapsedNanos <= 0 ||
        perception.focusEpoch < 0 ||
        !hasLength(perception.focusDigest, DIGEST_BYTES) ||
        perception.displayId < 0 || perception.rotation < 0 ||
        perception.rotation > 3 || !isRectValid(perception.capturedRegion) ||
        !perception.secureContentExcluded ||
        perception.redactionPolicyVersion <= 0 ||
        !hasAtMost(perception.redactions, MAX_REDACTIONS) ||
        !hasAtMost(perception.ocr, MAX_OCR_NODES) ||
        !hasLength(perception.perceptionDigest, DIGEST_BYTES)) {
      return false;
    }
    if (perception.redactions != null) {
      for (SensitiveRegion region : perception.redactions) {
        if (region == null || !isRectValid(region.bounds) || region.reason < 0) {
          return false;
        }
      }
    }
    if (perception.ocr != null) {
      for (OcrNode node : perception.ocr) {
        if (node == null || node.nodeId <= 0 || !isRectValid(node.bounds) ||
            !isBoundedText(node.text, MAX_OCR_TEXT_BYTES) ||
            node.confidenceMilli < 0 || node.confidenceMilli > 1000 ||
            node.candidateId < 0) {
          return false;
        }
      }
    }
    return perception.image == null || isImageValid(perception.image);
  }

  private static boolean isActionValid(ActionDto action) {
    return action != null && action.actionId > 0 && action.type >= 0 &&
        action.type <= 9 && action.candidateId >= 0 && action.displayId >= 0 &&
        (action.target == null || isRectValid(action.target)) &&
        isCoordinate(action.startX) && isCoordinate(action.startY) &&
        isCoordinate(action.endX) && isCoordinate(action.endY) &&
        action.durationMillis >= 0 &&
        action.durationMillis <= MAX_ACTION_DURATION_MILLIS &&
        action.waitMillis >= 0 && action.waitMillis <= MAX_WAIT_MILLIS &&
        isBoundedText(action.text, MAX_ACTION_TEXT_BYTES);
  }

  private static boolean isImageValid(ImagePayload image) {
    return image.dataFd != null && isImageMetadataValid(image.byteLength,
        image.sha256, image.mimeType, image.width, image.height);
  }

  static boolean isImageMetadataValid(long byteLength, byte[] sha256,
      String mimeType, int width, int height) {
    return byteLength > 0 && byteLength <= MAX_IMAGE_BYTES &&
        hasLength(sha256, DIGEST_BYTES) &&
        ("image/png".equals(mimeType) || "image/webp".equals(mimeType)) &&
        width > 0 && height > 0 && width <= 4096 && height <= 4096;
  }

  private static boolean isRectValid(RectDto rect) {
    return rect != null && rect.left >= 0 && rect.top >= 0 &&
        rect.right > rect.left && rect.bottom > rect.top;
  }

  private static boolean isCoordinate(int coordinate) {
    return coordinate >= 0 && coordinate <= MAX_NORMALIZED_COORDINATE;
  }

  private static boolean hasLength(byte[] value, int expected) {
    return value != null && value.length == expected;
  }

  private static boolean hasAtMost(Object[] value, int maximum) {
    return value == null || value.length <= maximum;
  }

  private static boolean isBoundedText(String value, int maximumBytes) {
    return value != null && value.indexOf('\0') < 0 &&
        value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
  }
}
