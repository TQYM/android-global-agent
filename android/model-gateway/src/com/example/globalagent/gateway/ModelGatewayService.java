package com.example.globalagent.gateway;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import com.example.globalagent.v2.GatewayResult;
import com.example.globalagent.v2.IModelGateway;
import com.example.globalagent.v2.IModelGatewayCallback;
import com.example.globalagent.v2.IV2GlobalAgent;
import com.example.globalagent.v2.ModelRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ModelGatewayService extends Service {
  private final ConcurrentMap<Long, PendingRequest> pending =
      new ConcurrentHashMap<>();

  private final IModelGateway.Stub binder = new IModelGateway.Stub() {
    @Override
    public void openSession(ModelRequest request, IV2GlobalAgent sessionCapability,
        IModelGatewayCallback callback) throws RemoteException {
      requireExternalBridgeCaller();
      if (!ModelGatewayV2Policy.isOpenRequestValid(request,
          android.os.SystemClock.elapsedRealtimeNanos()) ||
          sessionCapability == null || callback == null) {
        throw new IllegalArgumentException("invalid model gateway request");
      }
      final long sessionId = request.session.sessionId;
      final PendingRequest entry = new PendingRequest(sessionId,
          request.session.revision);
      if (pending.putIfAbsent(sessionId, entry) != null) {
        throw new IllegalStateException("session already has a gateway request");
      }
      boolean deathLinked = false;
      try {
        callback.asBinder().linkToDeath(entry, 0);
        deathLinked = true;
        final GatewayResult result = new GatewayResult();
        result.sessionId = sessionId;
        result.revision = request.session.revision;
        result.status = ModelGatewayV2Policy.STATUS_DISABLED;
        result.safeProviderRequestId = "";
        result.latencyMillis = 0;
        result.inputTokens = 0;
        result.outputTokens = 0;
        callback.onComplete(result);
      } catch (RemoteException exception) {
        pending.remove(sessionId, entry);
        throw exception;
      } finally {
        if (deathLinked) {
          callback.asBinder().unlinkToDeath(entry, 0);
        }
        pending.remove(sessionId, entry);
      }
    }

    @Override
    public void cancel(long sessionId, long revision, int reason) {
      requireExternalBridgeCaller();
      final PendingRequest entry = pending.get(sessionId);
      if (entry != null && entry.revision == revision) {
        pending.remove(sessionId, entry);
      }
    }
  };

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    pending.clear();
    return false;
  }

  private static void requireExternalBridgeCaller() {
    final int callerUid = Binder.getCallingUid();
    if (callerUid == Process.myUid() || callerUid == 0 || callerUid == 2000) {
      throw new SecurityException("gateway service caller is not a bridge");
    }
  }

  private final class PendingRequest implements IBinder.DeathRecipient {
    private final long sessionId;
    private final long revision;

    PendingRequest(long sessionId, long revision) {
      this.sessionId = sessionId;
      this.revision = revision;
    }

    @Override
    public void binderDied() {
      pending.remove(sessionId, this);
    }
  }
}
