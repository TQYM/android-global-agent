package com.example.globalagent;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.concurrent.atomic.AtomicBoolean;

final class InputController {
  private static final String TAG = "GlobalAgentInput";
  private static final int ACTION_DOWN = GestureValidator.ACTION_DOWN;
  private static final int ACTION_POINTER_DOWN =
      GestureValidator.ACTION_POINTER_DOWN;
  private static final int ACTION_MOVE = GestureValidator.ACTION_MOVE;
  private static final int ACTION_POINTER_UP = GestureValidator.ACTION_POINTER_UP;
  private static final int ACTION_UP = GestureValidator.ACTION_UP;
  private static final int ACTION_CANCEL = GestureValidator.ACTION_CANCEL;

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
    final String validationError = GestureValidator.validate(gesture);
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

}
