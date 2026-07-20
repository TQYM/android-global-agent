package com.example.globalagent;

import com.example.globalagent.v2.ActionPlan;

public final class V2SessionCapabilityPolicyTest {
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
    rejects(() -> new V2SessionCapabilityPolicy(0, 10001, 1));
    rejects(() -> new V2SessionCapabilityPolicy(1, -1, 1));
    rejects(() -> new V2SessionCapabilityPolicy(1, 10001, 0));

    final V2SessionCapabilityPolicy policy =
        new V2SessionCapabilityPolicy(7, 10001, 9);
    check(policy.requireGateway(10001) == 10001);
    rejects(() -> policy.requireGateway(10002));
    check(policy.requireBoundSession(7) == 7);
    rejects(() -> policy.requireBoundSession(8));
    check(policy.capabilityId() == 9);

    final byte[] token = new byte[32];
    token[0] = 1;
    final byte[] copy = policy.copyGrantToken(token);
    check(copy != token);
    check(copy[0] == 1);
    token[0] = 2;
    check(copy[0] == 1);
    rejects(() -> policy.copyGrantToken(null));
    rejects(() -> policy.copyGrantToken(new byte[31]));

    final ActionPlan plan = new ActionPlan();
    plan.sessionId = 7;
    check(policy.requireBoundPlan(plan) == plan);
    plan.sessionId = 8;
    rejects(() -> policy.requireBoundPlan(plan));
    rejects(() -> policy.requireBoundPlan(null));
    check(policy.bridgeOnly().getMessage().contains("gateway capability"));

    System.out.println("v2 session capability policy checks passed: " + checks);
  }
}
