package com.example.globalagent.gatewayprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class GatewayBindProbeActivity extends Activity {
  private static final ComponentName TARGET = new ComponentName(
      "com.example.globalagent.gateway",
      "com.example.globalagent.gateway.ModelGatewayService");

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    final Intent serviceIntent = new Intent().setComponent(TARGET);
    try {
      final boolean bound = bindService(serviceIntent, new NoopConnection(),
          Context.BIND_AUTO_CREATE);
      writeResult(bound ? "unexpected-bind" : "bind-failed");
    } catch (SecurityException expected) {
      writeResult("security-rejected");
    } finally {
      finish();
    }
  }

  private void writeResult(String result) {
    try (FileOutputStream output = openFileOutput(
        "bind-result.txt", Context.MODE_PRIVATE)) {
      output.write(result.getBytes(StandardCharsets.UTF_8));
      output.flush();
      output.getFD().sync();
    } catch (IOException exception) {
      throw new IllegalStateException("failed to persist probe result", exception);
    }
  }

  private static final class NoopConnection implements ServiceConnection {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {}

    @Override
    public void onServiceDisconnected(ComponentName name) {}
  }
}
