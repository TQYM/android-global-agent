package com.example.globalagent.gateway;

import com.example.globalagent.v2.ModelRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EhviewerDryRunPolicy {
  static final String RELEASE_PACKAGE = "com.xjs.ehviewer";
  static final String DEBUG_PACKAGE = "com.xjs.ehviewer.debug";
  static final int MIN_CONFIDENCE_MILLI = 800;
  static final int MAX_ACTIONS = 8;
  static final int MAX_TARGET_TEXT_BYTES = 256;

  private static final Set<String> ALLOWED_PACKAGES = Set.of(
      RELEASE_PACKAGE, DEBUG_PACKAGE);
  private static final Set<String> ROOT_FIELDS = Set.of(
      "schemaVersion", "dryRun", "sessionId", "revision",
      "focusedPackage", "confidenceMilli", "actions");

  private EhviewerDryRunPolicy() {}

  static boolean isAllowedPackage(String packageName) {
    return ALLOWED_PACKAGES.contains(packageName);
  }

  static boolean isAuthorizedBridgeCaller(int callerUid, int bridgeUid,
      int capabilityOwnerUid, boolean capabilityBound, String focusedPackage) {
    return callerUid >= 0 && callerUid == bridgeUid &&
        callerUid == capabilityOwnerUid && capabilityBound &&
        isAllowedPackage(focusedPackage);
  }

  static void requireZeroInjectedEvents(long injectedEvents) {
    if (injectedEvents != 0) {
      throw new SecurityException("dry-run execution is disabled");
    }
  }

  static void validateDryRunInputBoundary(long proposedInjectedEvents) {
    requireZeroInjectedEvents(proposedInjectedEvents);
  }

  static Result validate(ModelRequest request, String planJson) {
    try {
      if (request == null || request.session == null ||
          !isAllowedPackage(request.focusedPackage)) {
        return Result.denied("GA_PLAN_PACKAGE_DENIED");
      }
      final Map<String, Object> root = object(StrictJsonParser.parse(planJson));
      requireExactFields(root, ROOT_FIELDS);
      requireLong(root, "schemaVersion", 2, 2);
      if (!Boolean.TRUE.equals(root.get("dryRun"))) {
        throw invalid("dry-run-required");
      }
      requireLong(root, "sessionId", request.session.sessionId,
          request.session.sessionId);
      requireLong(root, "revision", request.session.revision,
          request.session.revision);
      if (!request.focusedPackage.equals(string(root.get("focusedPackage")))) {
        return Result.denied("GA_PLAN_FOCUS_CHANGED");
      }
      requireLong(root, "confidenceMilli", MIN_CONFIDENCE_MILLI, 1000);

      final List<Object> rawActions = array(root.get("actions"));
      if (rawActions.isEmpty() || rawActions.size() > MAX_ACTIONS) {
        throw invalid("action-count");
      }
      final List<Action> actions = new ArrayList<>();
      final Set<Long> actionIds = new HashSet<>();
      for (Object rawAction : rawActions) {
        final Action action = validateAction(object(rawAction));
        if (!actionIds.add(action.id())) {
          throw invalid("duplicate-action-id");
        }
        actions.add(action);
      }
      return Result.validated(new Plan(
          request.session.sessionId, request.session.revision,
          request.focusedPackage, Collections.unmodifiableList(actions)));
    } catch (IllegalArgumentException exception) {
      return Result.denied("GA_PLAN_SCHEMA_INVALID");
    }
  }

  private static Action validateAction(Map<String, Object> action) {
    final String type = string(action.get("type"));
    final long id = requiredPositiveLong(action, "id");
    switch (type) {
      case "FIND_TEXT":
        requireExactFields(action, Set.of("id", "type", "targetText"));
        return new Action(id, type, 0, 0, boundedTargetText(action));
      case "TAP_CANDIDATE":
        requireExactFields(action, Set.of("id", "type", "candidateId"));
        return new Action(id, type,
            requiredPositiveLong(action, "candidateId"), 0, "");
      case "PRESS_BACK":
        requireExactFields(action, Set.of("id", "type"));
        return new Action(id, type, 0, 0, "");
      case "WAIT":
        requireExactFields(action, Set.of("id", "type", "waitMs"));
        final long waitMs = requireLong(action, "waitMs", 1, 1000);
        return new Action(id, type, 0, waitMs, "");
      case "VERIFY":
        requireExactFields(action, Set.of("id", "type", "targetText"));
        return new Action(id, type, 0, 0, boundedTargetText(action));
      default:
        throw invalid("action-denied");
    }
  }

  private static String boundedTargetText(Map<String, Object> action) {
    final String value = string(action.get("targetText"));
    if (value.isEmpty() || value.indexOf('\0') >= 0 ||
        value.getBytes(StandardCharsets.UTF_8).length > MAX_TARGET_TEXT_BYTES) {
      throw invalid("target-text");
    }
    return value;
  }

  private static long requiredPositiveLong(Map<String, Object> object,
      String field) {
    return requireLong(object, field, 1, Long.MAX_VALUE);
  }

  private static long requireLong(Map<String, Object> object, String field,
      long minimum, long maximum) {
    final Object value = object.get(field);
    if (!(value instanceof Long) || (Long) value < minimum ||
        (Long) value > maximum) {
      throw invalid("integer-value");
    }
    return (Long) value;
  }

  private static void requireExactFields(Map<String, Object> object,
      Set<String> fields) {
    if (!object.keySet().equals(fields)) {
      throw invalid("fields");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw invalid("object");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value) {
    if (!(value instanceof List<?>)) {
      throw invalid("array");
    }
    return (List<Object>) value;
  }

  private static String string(Object value) {
    if (!(value instanceof String)) {
      throw invalid("string");
    }
    return (String) value;
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException("invalid-ehviewer-plan:" + code);
  }

  static final class Action {
    private final long id;
    private final String type;
    private final long candidateId;
    private final long waitMs;
    private final String targetText;

    Action(long id, String type, long candidateId, long waitMs,
        String targetText) {
      this.id = id;
      this.type = type;
      this.candidateId = candidateId;
      this.waitMs = waitMs;
      this.targetText = targetText;
    }

    long id() { return id; }
    String type() { return type; }
    long candidateId() { return candidateId; }
    long waitMs() { return waitMs; }
    String targetText() { return targetText; }
  }

  static final class Plan {
    private final long sessionId;
    private final long revision;
    private final String focusedPackage;
    private final List<Action> actions;

    Plan(long sessionId, long revision, String focusedPackage,
        List<Action> actions) {
      this.sessionId = sessionId;
      this.revision = revision;
      this.focusedPackage = focusedPackage;
      this.actions = actions;
    }

    long sessionId() { return sessionId; }
    long revision() { return revision; }
    String focusedPackage() { return focusedPackage; }
    List<Action> actions() { return actions; }
  }

  static final class Result {
    private final boolean valid;
    private final String safeCode;
    private final Plan plan;

    private Result(boolean valid, String safeCode, Plan plan) {
      this.valid = valid;
      this.safeCode = safeCode;
      this.plan = plan;
    }

    static Result validated(Plan plan) {
      return new Result(true, "GA_DRY_RUN_VALIDATED", plan);
    }

    static Result denied(String safeCode) {
      return new Result(false, safeCode, null);
    }

    boolean valid() { return valid; }
    String safeCode() { return safeCode; }
    Plan plan() { return plan; }
    long injectedEvents() {
      requireZeroInjectedEvents(0);
      return 0;
    }
  }
}
