package com.example.globalagent.gateway;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class AtomicPublicConfigStore implements PublicConfigImporter.ConfigStore {
  private final AtomicFile file;

  AtomicPublicConfigStore(File path) {
    file = new AtomicFile(path);
  }

  @Override
  public synchronized void replace(String validatedJson) throws IOException {
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(validatedJson.getBytes(StandardCharsets.UTF_8));
      output.flush();
      output.getFD().sync();
      file.finishWrite(output);
    } catch (IOException exception) {
      if (output != null) {
        file.failWrite(output);
      }
      throw exception;
    }
  }
}
