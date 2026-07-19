package com.example.globalagent;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class InputController {
  private static final String TAG = "GlobalAgentInput";
  private static final int MAX_FRAMES = 256;
  private static final int MAX_POINTERS = 5;
  private static final long MAX_DURATION_MILLIS = 2_000;

  private static final int ACTION_DOWN = 0;
  private static final int ACTION_POINTER_DOWN = 1;
  private static final int ACTION_MOVE = 2;
  private static final int ACTION_POINTER_UP = 3;
  private static final int ACTION_UP = 4;
  private static final int ACTION_CANCEL = 5;

  private final InputManager inputManager;
  private final Handler inputHandler;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicBoolean cancelRequested = new AtomicBoolean();

  InputController(Context context) {
    inputManager = context.getSystemService(InputManager.class);
    final HandlerThread inputThread = new HandlerThread("global-agent-input");
    inputThread.start();
    inputHandler = new Handler(inputThread.getLooper());
  }

  boolean injectGesture(GestureSpec gesture) {
    final String validationError = validate(gesture);
    if (validationError != null) {
      Log.w(TAG, "rejected gesture: " + validationError);
      return false;
    }
    if (!busy.compareAndSet(false, true)) {
      Log.w(TAG, "rejected gesture while another gesture is active");
      return false;
    }
    cancelRequested.set(false);
    inputHandler.post(() -> injectValidatedGesture(gesture));
    return true;
  }

  void cancelActiveGesture() { cancelRequested.set(true); }

  private void injectValidatedGesture(GestureSpec gesture) {
    final long downTime = SystemClock.uptimeMillis();
    GestureFrame lastFrame = null;
    try {
      for (GestureFrame frame : gesture.frames) {
        if (cancelRequested.get()) {
          if (lastFrame != null) {
            injectCancel(gesture.displayId, downTime, lastFrame);
          }
          return;
        }

        final long delay =
            downTime + frame.elapsedMillis - SystemClock.uptimeMillis();
        if (delay > 0 && !waitUntil(downTime + frame.elapsedMillis)) {
          if (lastFrame != null) {
            injectCancel(gesture.displayId, downTime, lastFrame);
          }
          return;
        }
        if (cancelRequested.get()) {
          if (lastFrame != null) {
            injectCancel(gesture.displayId, downTime, lastFrame);
          }
          return;
        }

        lastFrame = frame;
        final MotionEvent event =
            toMotionEvent(gesture.displayId, downTime, frame);
        final boolean accepted;
        try {
          accepted = inputManager.injectInputEvent(
              event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT);
        } finally {
          event.recycle();
        }
        if (!accepted) {
          injectCancel(gesture.displayId, downTime, frame);
          return;
        }
      }
    } catch (RuntimeException exception) {
      Log.e(TAG, "gesture injection failed", exception);
      if (lastFrame != null) {
        injectCancel(gesture.displayId, downTime, lastFrame);
      }
    } finally {
      cancelRequested.set(false);
      busy.set(false);
    }
  }

  private MotionEvent toMotionEvent(int displayId, long downTime,
                                    GestureFrame frame) {
    final int pointerCount = frame.pointers.length;
    final MotionEvent.PointerProperties[] properties =
        new MotionEvent.PointerProperties[pointerCount];
    final MotionEvent.PointerCoords[] coordinates =
        new MotionEvent.PointerCoords[pointerCount];

    for (int index = 0; index < pointerCount; index++) {
      final PointerSample source = frame.pointers[index];
      final MotionEvent.PointerProperties property =
          new MotionEvent.PointerProperties();
      property.id = source.pointerId;
      property.toolType = MotionEvent.TOOL_TYPE_FINGER;
      properties[index] = property;

      final MotionEvent.PointerCoords coordinate =
          new MotionEvent.PointerCoords();
      coordinate.x = source.x;
      coordinate.y = source.y;
      coordinate.pressure = 1.0f;
      coordinate.size = 1.0f;
      coordinates[index] = coordinate;
    }

    final int action = toMotionAction(frame.action, frame.actionIndex);
    final MotionEvent event =
        MotionEvent.obtain(downTime, downTime + frame.elapsedMillis, action,
                           pointerCount, properties, coordinates, 0, 0, 1.0f,
                           1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    event.setDisplayId(displayId);
    return event;
  }

  private boolean waitUntil(long targetUptimeMillis) {
    while (true) {
      if (cancelRequested.get()) {
        return false;
      }
      final long remaining = targetUptimeMillis - SystemClock.uptimeMillis();
      if (remaining <= 0) {
        return true;
      }
      SystemClock.sleep(Math.min(remaining, 4));
    }
  }

  private static int toMotionAction(int action, int actionIndex) {
    switch (action) {
    case ACTION_DOWN:
      return MotionEvent.ACTION_DOWN;
    case ACTION_POINTER_DOWN:
      return MotionEvent.ACTION_POINTER_DOWN |
          (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    case ACTION_MOVE:
      return MotionEvent.ACTION_MOVE;
    case ACTION_POINTER_UP:
      return MotionEvent.ACTION_POINTER_UP |
          (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    case ACTION_UP:
      return MotionEvent.ACTION_UP;
    case ACTION_CANCEL:
      return MotionEvent.ACTION_CANCEL;
    default:
      throw new IllegalArgumentException("unsupported action " + action);
    }
  }

  private void injectCancel(int displayId, long downTime, GestureFrame frame) {
    try {
      final GestureFrame cancel = new GestureFrame();
      cancel.action = ACTION_CANCEL;
      cancel.actionIndex = 0;
      cancel.elapsedMillis = frame.elapsedMillis;
      cancel.pointers = frame.pointers;
      final MotionEvent event = toMotionEvent(displayId, downTime, cancel);
      try {
        inputManager.injectInputEvent(
            event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
      } finally {
        event.recycle();
      }
    } catch (RuntimeException exception) {
      Log.w(TAG, "failed to inject cancellation", exception);
    }
  }

  private static String validate(GestureSpec gesture) {
    if (gesture == null || gesture.frames == null ||
        gesture.frames.length < 2) {
      return "at least two frames are required";
    }
    if (gesture.frames.length > MAX_FRAMES || gesture.displayId < 0) {
      return "gesture exceeds frame or display limits";
    }
    if (gesture.frames[0].action != ACTION_DOWN ||
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
      if (frame.elapsedMillis < previousTime ||
          frame.elapsedMillis > MAX_DURATION_MILLIS) {
        return "event times must be monotonic and bounded";
      }
      if ((frame.action == ACTION_POINTER_DOWN ||
           frame.action == ACTION_POINTER_UP) &&
          (frame.actionIndex < 0 ||
           frame.actionIndex >= frame.pointers.length)) {
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
