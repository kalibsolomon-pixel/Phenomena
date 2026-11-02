package dev.phenomena.recording.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

public final class AtomicJson {
  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private AtomicJson() {}

  public static void write(Path path, Object value) throws IOException {
    writeString(path, GSON.toJson(value));
  }

  /** Writes text to a unique temporary sibling, then atomically replaces the target. */
  public static void writeString(Path path, String text) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path tmp =
        Files.createTempFile(
            path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
    try {
      Files.writeString(tmp, text);
      try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        throw new IOException("Atomic replacement unavailable; original file preserved", ex);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
