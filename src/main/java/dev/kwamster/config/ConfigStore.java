package dev.kwamster.config;

import dev.kwamster.recording.storage.AtomicJson;
import java.io.IOException;
import java.nio.file.*;

public final class ConfigStore {
  private final Path path;

  public ConfigStore(Path path) {
    this.path = path;
  }

  public Config load() throws IOException {
    if (!Files.exists(path)) return new Config();
    try {
      String json = Files.readString(path);
      Config c = AtomicJson.GSON.fromJson(json, Config.class);
      if (c == null) throw new IllegalArgumentException("null config");
      boolean migrating = c.schemaVersion != Config.SCHEMA;
      c.validate();
      if (migrating) {
        // Keep the pre-migration file once; the next save writes the current schema.
        Path backup = path.resolveSibling(path.getFileName() + ".v1-backup");
        if (!Files.exists(backup)) Files.writeString(backup, json);
      }
      return c;
    } catch (RuntimeException ex) {
      Files.copy(
          path,
          path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()),
          StandardCopyOption.REPLACE_EXISTING);
      throw new IOException("Invalid config preserved; using defaults", ex);
    }
  }

  public void save(Config config) throws IOException {
    config.validate();
    AtomicJson.write(path, config);
  }
}
