package com.example.globalagent.gateway;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;

public final class PublicConfigProvider extends ContentProvider {
  private PublicConfigImporter importer;

  @Override
  public boolean onCreate() {
    final File configFile = new File(requireContext().getFilesDir(),
        "agent-config-v2.json");
    importer = new PublicConfigImporter(new AtomicPublicConfigStore(configFile));
    return true;
  }

  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    final int callerUid = Binder.getCallingUid();
    if (extras == null) {
      throw new IllegalArgumentException("invalid public config extras");
    }
    PublicConfigCallPolicy.validate(callerUid, method, arg, extras.keySet());
    final String encodedConfig = extras.getString("config_b64");
    try {
      final PublicAgentConfigSchema.ParsedConfig config = importer.importConfig(
          callerUid, method, encodedConfig);
      final Bundle result = new Bundle();
      result.putString("status", "ok");
      result.putInt("schema_version", PublicAgentConfigSchema.SCHEMA_VERSION);
      result.putInt("provider_count", config.providerCount());
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("public config persistence failed", exception);
    }
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection,
      String[] selectionArgs, String sortOrder) {
    throw unsupported();
  }

  @Override
  public String getType(Uri uri) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw unsupported();
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    throw unsupported();
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection,
      String[] selectionArgs) {
    throw unsupported();
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("only call() is supported");
  }
}
