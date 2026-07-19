package com.example.globalagent;

import java.util.HashSet;
import java.util.Set;

final class GestureValidator {
  static final int MAX_FRAMES = 256;
  static final int MAX_POINTERS = 5;
  static final long MAX_DURATION_MILLIS = 2_000;

  static final int ACTION_DOWN = 0;
  static final int ACTION_POINTER_DOWN = 1;
  static final int ACTION_MOVE = 2;
  static final int ACTION_POINTER_UP = 3;
  static final int ACTION_UP = 4;
  static final int ACTION_CANCEL = 5;

  private GestureValidator() {}

  static String validate(GestureSpec gesture) {
    if (gesture == null || gesture.frames == null ||
        gesture.frames.length < 2) {
      return "at least two frames are required";
    }
    if (gesture.frames.length > MAX_FRAMES || gesture.displayId < 0) {
      return "gesture exceeds frame or display limits";
    }
    if (gesture.frames[0] == null ||
        gesture.frames[gesture.frames.length - 1] == null ||
        gesture.frames[0].action != ACTION_DOWN ||
        gesture.frames[gesture.frames.length - 1].action != ACTION_UP) {
      return "gesture must start with DOWN and end with UP";
    }

    long previousTime = -1;
    final Set<Integer> activePointers = new HashSet<>();
    for (int frameIndex = 0; frameIndex < gesture.frames.length; frameIndex++) {
      final GestureFrame frame = gesture.frames[frameIndex];
      if (frame == null || frame.pointers == null ||
          frame.pointers.length < 1 || frame.pointers.length > MAX_POINTERS) {
        return "invalid pointer count";
      }
      if (frame.elapsedMillis < 0 || frame.elapsedMillis < previousTime ||
          frame.elapsedMillis > MAX_DURATION_MILLIS) {
        return "event times must be monotonic and bounded";
      }
      if (frame.actionIndex < 0 ||
          frame.actionIndex >= frame.pointers.length) {
        return "invalid pointer action index";
      }

      final Set<Integer> pointerIds = new HashSet<>();
      for (PointerSample pointer : frame.pointers) {
        if (pointer == null || pointer.pointerId < 0 ||
            !Float.isFinite(pointer.x) || !Float.isFinite(pointer.y) ||
            Math.abs(pointer.x) > 100_000.0f ||
            Math.abs(pointer.y) > 100_000.0f ||
            !pointerIds.add(pointer.pointerId)) {
          return "invalid pointer data";
        }
      }

      switch (frame.action) {
      case ACTION_DOWN:
        if (frameIndex != 0 || frame.pointers.length != 1 ||
            !activePointers.isEmpty()) {
          return "DOWN must initialize one pointer";
        }
        activePointers.add(frame.pointers[0].pointerId);
        break;
      case ACTION_POINTER_DOWN:
        if (frame.pointers.length != activePointers.size() + 1 ||
            !pointerIds.containsAll(activePointers)) {
          return "POINTER_DOWN has an invalid pointer set";
        }
        final int addedId = frame.pointers[frame.actionIndex].pointerId;
        if (activePointers.contains(addedId)) {
          return "POINTER_DOWN action index is not the new pointer";
        }
        activePointers.add(addedId);
        break;
      case ACTION_MOVE:
        if (!pointerIds.equals(activePointers)) {
          return "MOVE changed the active pointer set";
        }
        break;
      case ACTION_POINTER_UP:
        if (!pointerIds.equals(activePointers) || activePointers.size() < 2) {
          return "POINTER_UP has an invalid pointer set";
        }
        activePointers.remove(frame.pointers[frame.actionIndex].pointerId);
        break;
      case ACTION_UP:
        if (frameIndex != gesture.frames.length - 1 ||
            activePointers.size() != 1 || !pointerIds.equals(activePointers)) {
          return "UP must terminate the final pointer";
        }
        activePointers.clear();
        break;
      default:
        return "unsupported gesture action";
      }
      previousTime = frame.elapsedMillis;
    }
    return activePointers.isEmpty() ? null : "gesture left active pointers";
  }
}
