package dev.kwamster.recording.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

public final class AtomicJson {
  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private AtomicJson() {}

  public static void write(Path path, Object value) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path tmp =
        Files.createTempFile(
            path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
    try {
      Files.writeString(tmp, GSON.toJson(value));
      try {
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
