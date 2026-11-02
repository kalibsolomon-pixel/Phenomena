package dev.phenomena.config;

import dev.phenomena.recording.storage.AtomicJson;
import java.io.IOException;
import java.nio.file.*;

public final class ConfigStore {
  private final Path path;
  /** Optional pre-rename config (kwamster.json); read once when the primary is absent. */
  private final Path legacy;

  public ConfigStore(Path path) {
    this(path, null);
  }

  public ConfigStore(Path path, Path legacy) {
    this.path = path;
    this.legacy = legacy;
  }

  public Config load() throws IOException {
    Path source = Files.exists(path) ? path : legacySource();
    if (source == null) return new Config();
    try {
      String json = Files.readString(source);
      Config c = AtomicJson.GSON.fromJson(json, Config.class);
      if (c == null) throw new IllegalArgumentException("null config");
      boolean migrating = c.schemaVersion != Config.SCHEMA;
      c.validate();
      if (migrating) {
        // Keep the pre-migration file once; the next save writes the current schema.
        Path backup = source.resolveSibling(source.getFileName() + ".v1-backup");
        if (!Files.exists(backup)) Files.writeString(backup, json);
      }
      return c;
    } catch (RuntimeException ex) {
      Files.copy(
          source,
          source.resolveSibling(source.getFileName() + ".invalid-" + System.currentTimeMillis()),
          StandardCopyOption.REPLACE_EXISTING);
      throw new IOException("Invalid config preserved; using defaults", ex);
    }
  }

  public void save(Config config) throws IOException {
    config.validate();
    AtomicJson.write(path, config);
  }

  private Path legacySource() {
    if (legacy == null) return null;
    return Files.isRegularFile(legacy) ? legacy : null;
  }
}
