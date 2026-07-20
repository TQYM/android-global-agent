package com.example.globalagent.gateway;

import com.example.globalagent.v2.ActionDto;
import com.example.globalagent.v2.ActionPlan;
import com.example.globalagent.v2.CaptureGrant;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.OcrNode;
import com.example.globalagent.v2.PerceptionEnvelope;
import com.example.globalagent.v2.RectDto;

public final class ProtocolV2ValidatorTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static byte[] bytes(int count) {
    final byte[] value = new byte[count];
    value[0] = 1;
    return value;
  }

  private static RectDto rect() {
    final RectDto value = new RectDto();
    value.right = 100;
    value.bottom = 200;
    return value;
  }

  private static CaptureGrant grant(long now) {
    final CaptureGrant value = new CaptureGrant();
    value.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    value.serviceInstanceId = bytes(16);
    value.token = bytes(32);
    value.grantId = 1;
    value.sessionId = 2;
    value.revision = 0;
    value.displayId = 0;
    value.crop = rect();
    value.expiresAtElapsedNanos = now + 1_000_000_000L;
    value.maxImageBytes = 1024;
    value.redactionPolicyVersion = 1;
    return value;
  }

  private static ActionDto action(long id) {
    final ActionDto value = new ActionDto();
    value.actionId = id;
    value.type = 1;
    value.displayId = 0;
    value.target = rect();
    value.startX = 100;
    value.startY = 200;
    value.endX = 300;
    value.endY = 400;
    value.durationMillis = 100;
    value.text = "Settings";
    return value;
  }

  private static ActionPlan plan(long now) {
    final ActionPlan value = new ActionPlan();
    value.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    value.serviceInstanceId = bytes(16);
    value.sessionId = 2;
    value.perceptionId = 3;
    value.perceptionDigest = bytes(32);
    value.expectedFocusDigest = bytes(32);
    value.clientPlanId = 4;
    value.deadlineElapsedNanos = now + 1_000_000_000L;
    value.actions = new ActionDto[] {action(1)};
    return value;
  }

  private static PerceptionEnvelope perception() {
    final PerceptionEnvelope value = new PerceptionEnvelope();
    value.protocolVersion = IV2GlobalAgent.PROTOCOL_VERSION;
    value.serviceInstanceId = bytes(16);
    value.sessionId = 2;
    value.perceptionId = 3;
    value.capturedAtElapsedNanos = 4;
    value.focusDigest = bytes(32);
    value.displayId = 0;
    value.rotation = 0;
    value.capturedRegion = rect();
    value.secureContentExcluded = true;
    value.redactionPolicyVersion = 1;
    value.perceptionDigest = bytes(32);
    return value;
  }

  public static void main(String[] args) {
    final long now = 10_000;
    final CaptureGrant validGrant = grant(now);
    check(ProtocolV2Validator.isCaptureGrantValid(validGrant, now));
    check(!ProtocolV2Validator.isCaptureGrantValid(validGrant, -1));
    validGrant.token = bytes(31);
    check(!ProtocolV2Validator.isCaptureGrantValid(validGrant, now));
    validGrant.token = bytes(32);
    validGrant.expiresAtElapsedNanos = now;
    check(!ProtocolV2Validator.isCaptureGrantValid(validGrant, now));
    validGrant.expiresAtElapsedNanos = now + 3_000_000_001L;
    check(!ProtocolV2Validator.isCaptureGrantValid(validGrant, now));
    validGrant.expiresAtElapsedNanos = now + 1;
    validGrant.maxImageBytes = ProtocolV2Validator.MAX_IMAGE_BYTES + 1;
    check(!ProtocolV2Validator.isCaptureGrantValid(validGrant, now));

    final ActionPlan validPlan = plan(now);
    check(ProtocolV2Validator.isActionPlanValid(validPlan, now));
    check(!ProtocolV2Validator.isActionPlanValid(validPlan, -1));
    validPlan.actions = new ActionDto[] {action(1), action(1)};
    check(!ProtocolV2Validator.isActionPlanValid(validPlan, now));
    validPlan.actions = new ActionDto[] {action(1)};
    validPlan.actions[0].startX = -1;
    check(!ProtocolV2Validator.isActionPlanValid(validPlan, now));
    validPlan.actions[0].startX = 0;
    validPlan.actions[0].text = "x".repeat(
        ProtocolV2Validator.MAX_ACTION_TEXT_BYTES + 1);
    check(!ProtocolV2Validator.isActionPlanValid(validPlan, now));
    validPlan.actions[0].text = "ok";
    validPlan.deadlineElapsedNanos = now;
    check(!ProtocolV2Validator.isActionPlanValid(validPlan, now));

    final PerceptionEnvelope validPerception = perception();
    check(ProtocolV2Validator.isPerceptionValid(validPerception));
    validPerception.ocr = new OcrNode[0];
    check(ProtocolV2Validator.isPerceptionValid(validPerception));
    validPerception.secureContentExcluded = false;
    check(!ProtocolV2Validator.isPerceptionValid(validPerception));
    validPerception.secureContentExcluded = true;
    final OcrNode badNode = new OcrNode();
    badNode.nodeId = 1;
    badNode.bounds = rect();
    badNode.text = "bad\0text";
    badNode.confidenceMilli = 900;
    validPerception.ocr = new OcrNode[] {badNode};
    check(!ProtocolV2Validator.isPerceptionValid(validPerception));
    badNode.text = "safe";
    check(ProtocolV2Validator.isPerceptionValid(validPerception));
    badNode.confidenceMilli = 1001;
    check(!ProtocolV2Validator.isPerceptionValid(validPerception));

    check(ProtocolV2Validator.isImageMetadataValid(1024, bytes(32),
        "image/png", 100, 200));
    check(!ProtocolV2Validator.isImageMetadataValid(-1, bytes(32),
        "image/png", 100, 200));
    check(!ProtocolV2Validator.isImageMetadataValid(
        ProtocolV2Validator.MAX_IMAGE_BYTES + 1L, bytes(32), "image/png",
        100, 200));
    check(!ProtocolV2Validator.isImageMetadataValid(1024, bytes(31),
        "image/png", 100, 200));
    check(!ProtocolV2Validator.isImageMetadataValid(1024, bytes(32),
        "image/jpeg", 100, 200));

    System.out.println("protocol v2 validator checks passed: " + checks);
  }
}
