package com.example.globalagent;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import java.util.Map;
import java.util.function.Consumer;

final class SettingsObserver extends ContentObserver {
  private static final String[] ALLOWED_KEYS = {
      Settings.Global.ANIMATOR_DURATION_SCALE,
      Settings.Global.TRANSITION_ANIMATION_SCALE,
      Settings.Global.WINDOW_ANIMATION_SCALE,
  };

  private final Context context;
  private final Consumer<String> listener;
  private final Map<Uri, String> keysByUri;

  SettingsObserver(Context context, Handler handler,
                   Consumer<String> listener) {
    super(handler);
    this.context = context;
    this.listener = listener;
    final android.util.ArrayMap<Uri, String> keys =
        new android.util.ArrayMap<>();
    for (String key : ALLOWED_KEYS) {
      keys.put(Settings.Global.getUriFor(key), key);
    }
    keysByUri = keys;
  }

  void start() {
    for (Uri uri : keysByUri.keySet()) {
      context.getContentResolver().registerContentObserver(uri, false, this);
    }
  }

  @Override
  public void onChange(boolean selfChange, Uri uri) {
    final String key = keysByUri.get(uri);
    if (key != null) {
      listener.accept(key);
    }
  }
}
