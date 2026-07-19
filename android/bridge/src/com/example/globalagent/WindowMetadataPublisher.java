package com.example.globalagent;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class WindowMetadataPublisher {
  private static final long POLL_INTERVAL_MILLIS = 500;

  private final ActivityManager activityManager;
  private final DisplayManager displayManager;
  private final Handler handler;
  private final Consumer<WindowSnapshot> listener;
  private String lastFingerprint = "";

  WindowMetadataPublisher(Context context, Handler handler,
                          Consumer<WindowSnapshot> listener) {
    activityManager = context.getSystemService(ActivityManager.class);
    displayManager = context.getSystemService(DisplayManager.class);
    this.handler = handler;
    this.listener = listener;
  }

  void start() { handler.post(this::poll); }

  void publishNow() {
    lastFingerprint = "";
    handler.post(this::poll);
  }

  private void poll() {
    try {
      final WindowSnapshot snapshot = readSnapshot();
      if (snapshot != null) {
        final String fingerprint =
            snapshot.componentName + ':' + snapshot.focusedPid + ':' +
            snapshot.displayId + ':' + snapshot.rotation + ':' + snapshot.left +
            ':' + snapshot.top + ':' + snapshot.right + ':' + snapshot.bottom;
        if (!Objects.equals(fingerprint, lastFingerprint)) {
          lastFingerprint = fingerprint;
          listener.accept(snapshot);
        }
      }
    } catch (RuntimeException ignored) {
      // Metadata is advisory. Capture remains available when task queries fail.
    } finally {
      handler.postDelayed(this::poll, POLL_INTERVAL_MILLIS);
    }
  }

  private WindowSnapshot readSnapshot() {
    final List<ActivityManager.RunningTaskInfo> tasks =
        activityManager.getRunningTasks(1);
    if (tasks.isEmpty()) {
      return null;
    }
    final ActivityManager.RunningTaskInfo task = tasks.get(0);
    final ComponentName component = task.topActivity;
    if (component == null) {
      return null;
    }

    final WindowSnapshot snapshot = new WindowSnapshot();
    snapshot.componentName = component.flattenToShortString();
    snapshot.focusedPid = findForegroundPid(component.getPackageName());
    snapshot.displayId = task.displayId;
    final Display display = displayManager.getDisplay(task.displayId);
    snapshot.rotation = display == null ? 0 : display.getRotation();

    final Rect bounds = task.configuration.windowConfiguration.getBounds();
    snapshot.left = bounds.left;
    snapshot.top = bounds.top;
    snapshot.right = bounds.right;
    snapshot.bottom = bounds.bottom;
    return snapshot;
  }

  private int findForegroundPid(String packageName) {
    final List<ActivityManager.RunningAppProcessInfo> processes =
        activityManager.getRunningAppProcesses();
    if (processes == null) {
      return -1;
    }
    int fallback = -1;
    for (ActivityManager.RunningAppProcessInfo process : processes) {
      if (process.pkgList == null) {
        continue;
      }
      for (String candidate : process.pkgList) {
        if (!packageName.equals(candidate)) {
          continue;
        }
        if (process.importance ==
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
          return process.pid;
        }
        fallback = process.pid;
      }
    }
    return fallback;
  }
}
