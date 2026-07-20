package com.example.globalagent;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentBridgeApplication extends Application {
  private static final String TAG = "GlobalAgentBridge";
  private static final String SERVICE_NAME = "global_agent";
  private static final long RETRY_MILLIS = 1000;

  private final AtomicReference<IAgentService> service =
      new AtomicReference<>();
  private AgentSessionClient sessionClient;
  private HandlerThread workerThread;
  private Handler worker;
  private InputController inputController;
  private SettingsObserver settingsObserver;
  private WindowMetadataPublisher windowPublisher;

  private final IAgentBridge.Stub bridge = new IAgentBridge.Stub() {
    @Override
    public boolean injectGesture(GestureSpec gesture) {
      return inputController != null && inputController.injectGesture(gesture);
    }

    @Override
    public void cancelActiveGesture() {
      if (inputController != null) {
        inputController.cancelActiveGesture();
      }
    }

    @Override
    public void onSessionStateChanged(SessionStatus status) {
      sessionClient.acceptCallback(status);
    }
  };

  private final IBinder.DeathRecipient deathRecipient = () -> {
    if (inputController != null) {
      inputController.cancelActiveGesture();
    }
    service.set(null);
    sessionClient.detach();
    if (worker != null) {
      worker.post(this::connectToNativeService);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    sessionClient = new AgentSessionClient(status -> {
      if (status == null) {
        Log.i(TAG, "session service disconnected");
      } else {
        Log.i(TAG, "session state=" + status.state +
            " active=" + status.active + " revision=" + status.revision);
      }
    });
    SessionClientRegistry.install(sessionClient);
    inputController = new InputController(this);
    workerThread = new HandlerThread("global-agent-bridge");
    workerThread.start();
    worker = new Handler(workerThread.getLooper());
    settingsObserver =
        new SettingsObserver(this, worker, this::notifySettingChanged);
    settingsObserver.start();
    windowPublisher =
        new WindowMetadataPublisher(this, worker, this::notifyWindowChanged);
    windowPublisher.start();
    worker.post(this::connectToNativeService);
  }

  private void connectToNativeService() {
    if (service.get() != null) {
      return;
    }
    final IBinder binder = ServiceManager.checkService(SERVICE_NAME);
    if (binder == null) {
      worker.postDelayed(this::connectToNativeService, RETRY_MILLIS);
      return;
    }
    try {
      binder.linkToDeath(deathRecipient, 0);
      final IAgentService candidate = IAgentService.Stub.asInterface(binder);
      candidate.registerBridge(bridge);
      sessionClient.attach(candidate);
      service.set(candidate);
      windowPublisher.publishNow();
      Log.i(TAG, "registered platform input bridge");
    } catch (RemoteException exception) {
      Log.w(TAG, "native service unavailable", exception);
      service.set(null);
      sessionClient.detach();
      worker.postDelayed(this::connectToNativeService, RETRY_MILLIS);
    }
  }

  AgentSessionClient sessionClient() {
    return sessionClient;
  }

  @Override
  public void onTerminate() {
    SessionClientRegistry.clear(sessionClient);
    super.onTerminate();
  }

  private void notifySettingChanged(String key) {
    final IAgentService current = service.get();
    if (current == null) {
      return;
    }
    try {
      current.notifySettingChanged(key);
    } catch (RemoteException exception) {
      Log.w(TAG, "failed to publish setting change", exception);
      service.set(null);
      sessionClient.detach();
      worker.post(this::connectToNativeService);
    }
  }

  private void notifyWindowChanged(WindowSnapshot snapshot) {
    final IAgentService current = service.get();
    if (current == null) {
      return;
    }
    try {
      current.notifyWindowChanged(snapshot);
    } catch (RemoteException exception) {
      Log.w(TAG, "failed to publish window metadata", exception);
      service.set(null);
      sessionClient.detach();
      worker.post(this::connectToNativeService);
    }
  }

}
