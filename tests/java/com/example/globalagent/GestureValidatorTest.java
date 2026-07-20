package com.example.globalagent;

public final class GestureValidatorTest {
  private static int checks;

  private static void check(boolean condition) {
    checks++;
    if (!condition) {
      throw new AssertionError("check " + checks + " failed");
    }
  }

  private static PointerSample pointer(int id, float x, float y) {
    final PointerSample pointer = new PointerSample();
    pointer.pointerId = id;
    pointer.x = x;
    pointer.y = y;
    return pointer;
  }

  private static GestureFrame frame(int action, int actionIndex, long elapsed,
                                    PointerSample... pointers) {
    final GestureFrame frame = new GestureFrame();
    frame.action = action;
    frame.actionIndex = actionIndex;
    frame.elapsedMillis = elapsed;
    frame.pointers = pointers;
    return frame;
  }

  private static GestureSpec gesture(GestureFrame... frames) {
    final GestureSpec gesture = new GestureSpec();
    gesture.displayId = 0;
    gesture.frames = frames;
    return gesture;
  }

  public static void main(String[] args) {
    final GestureSpec valid = gesture(
        frame(GestureValidator.ACTION_DOWN, 0, 0, pointer(0, 10, 10)),
        frame(GestureValidator.ACTION_UP, 0,
              GestureValidator.MAX_DURATION_MILLIS, pointer(0, 20, 20)));
    check(GestureValidator.validate(valid) == null);

    valid.frames[1].elapsedMillis = GestureValidator.MAX_DURATION_MILLIS + 1;
    check(GestureValidator.validate(valid) != null);
    valid.frames[1].elapsedMillis = 16;

    final GestureSpec invalidIndex = gesture(
        frame(GestureValidator.ACTION_DOWN, 0, 0, pointer(0, 10, 10)),
        frame(GestureValidator.ACTION_MOVE, -1, 8, pointer(0, 15, 15)),
        frame(GestureValidator.ACTION_UP, 0, 16, pointer(0, 20, 20)));
    check(GestureValidator.validate(invalidIndex) != null);

    final GestureSpec duplicatePointers = gesture(
        frame(GestureValidator.ACTION_DOWN, 0, 0, pointer(0, 10, 10)),
        frame(GestureValidator.ACTION_POINTER_DOWN, 1, 8,
              pointer(0, 10, 10), pointer(0, 20, 20)),
        frame(GestureValidator.ACTION_UP, 0, 16, pointer(0, 20, 20)));
    check(GestureValidator.validate(duplicatePointers) != null);

    final GestureSpec invalidCoordinate = gesture(
        frame(GestureValidator.ACTION_DOWN, 0, 0,
              pointer(0, Float.NaN, 10)),
        frame(GestureValidator.ACTION_UP, 0, 16, pointer(0, 20, 20)));
    check(GestureValidator.validate(invalidCoordinate) != null);

    final GestureSpec nullBoundary = gesture(
        null,
        frame(GestureValidator.ACTION_UP, 0, 16, pointer(0, 20, 20)));
    check(GestureValidator.validate(nullBoundary) != null);

    final GestureSpec negativeTime = gesture(
        frame(GestureValidator.ACTION_DOWN, 0, -1, pointer(0, 10, 10)),
        frame(GestureValidator.ACTION_UP, 0, 16, pointer(0, 20, 20)));
    check(GestureValidator.validate(negativeTime) != null);

    check(GestureValidator.validate(null) != null);
    System.out.println("gesture validator checks passed: " + checks);
  }
}
