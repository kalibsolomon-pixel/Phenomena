package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.config.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {
  @TempDir Path dir;

  @Test
  void configRoundTrip() throws Exception {
    var store = new ConfigStore(dir.resolve("config.json"));
    var c = store.load();
    c.chatSummary = true;
    c.density = Config.Density.COMPACT;
    c.reducedMotion = true;
    c.trendWindow = 40;
    c.analytics.x = .7;
    store.save(c);
    var loaded = store.load();
    assertTrue(loaded.chatSummary);
    assertEquals(Config.Density.COMPACT, loaded.density);
    assertTrue(loaded.reducedMotion);
    assertEquals(40, loaded.trendWindow);
    assertEquals(.7, loaded.analytics.x);
    assertEquals(Config.SCHEMA, loaded.schemaVersion);
  }

  @Test
  void corruptionPreservesOriginal() throws Exception {
    var p = dir.resolve("config.json");
    Files.writeString(p, "{");
    assertThrows(java.io.IOException.class, () -> new ConfigStore(p).load());
    assertEquals("{", Files.readString(p));
    try (var files = Files.list(dir)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void invalidValuesBounded() {
    var c = new Config();
    c.analytics.x = Double.NaN;
    c.retention = -1;
    c.volume = 4;
    c.validate();
    assertEquals(0, c.analytics.x);
    assertEquals(1, c.retention);
    assertEquals(1, c.volume);
  }
}
