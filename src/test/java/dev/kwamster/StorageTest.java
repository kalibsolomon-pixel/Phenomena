package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.metrics.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
  @TempDir Path dir;

  FightRecord sample(long time) {
    return new FightRecord(
        1,
        UUID.randomUUID().toString(),
        time,
        time + 1000,
        1_000_000_000L,
        "private-opponent",
        FightRecord.Outcome.UNKNOWN,
        "test",
        new Statistics(),
        List.of());
  }

  @Test
  void roundtripAndRetention() throws Exception {
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(dir, errors::add)) {
      var one = sample(1);
      var two = sample(2);
      store.save(one, 1, 1_000_000);
      store.save(two, 1, 1_000_000);
      assertTrue(store.await(5000));
      var loaded = store.readAll();
      assertEquals(1, loaded.size());
      assertEquals(two.id(), loaded.getFirst().id());
      assertTrue(errors.isEmpty());
    }
  }

  @Test
  void unsupportedAndCorruptPreserved() throws Exception {
    Files.writeString(dir.resolve("future.json"), "{schemaVersion:99}");
    Files.writeString(dir.resolve("bad.json"), "{");
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(dir, errors::add)) {
      assertTrue(store.readAll().isEmpty());
      assertEquals(2, errors.size());
      assertTrue(Files.exists(dir.resolve("future.json")));
      assertTrue(Files.exists(dir.resolve("bad.json")));
    }
  }

  @Test
  void atomicWriteLeavesNoTemporaryFiles() throws Exception {
    var p = dir.resolve("report.json");
    AtomicJson.write(p, sample(1));
    AtomicJson.write(p, sample(2));
    try (var files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
    assertEquals(2, FightStore.decode(Files.readString(p)).startEpochMillis());
  }

  @Test
  void exportIsPortableAndAnonymized() throws Exception {
    ReportExporter.export(dir, sample(1), true);
    String html = Files.readString(dir.resolve("index.html"));
    assertTrue(html.contains("KWAMSTER Fight Report"));
    assertFalse(html.contains("private-opponent"));
    assertFalse(html.contains(ReportExporter.PLACEHOLDER));
    assertFalse(Files.readString(dir.resolve("report.json")).contains("private-opponent"));
  }

  @Test
  void exportScriptBreakoutEscaped() throws Exception {
    var f = sample(1);
    var injection =
        new FightRecord(
            1,
            f.id(),
            1,
            2,
            1,
            "</script><script>alert(1)</script>",
            f.outcome(),
            "</script>",
            f.statistics(),
            List.of());
    ReportExporter.export(dir, injection, false);
    String html = Files.readString(dir.resolve("index.html"));
    assertFalse(html.contains("<script>alert(1)"));
  }

  @Test
  void rawStreamRecalculatesStats() throws Exception {
    var config = new Config();
    List<FightRecord> records = new ArrayList<>();
    var first = new CombatEngine(config, records::add, (e, g) -> {});
    var a = new CombatEvent.Attack(1, 2.5, .5, .5, 0, true, false, true);
    first.accept(
        new CombatEvent(
            1, 1, CombatEvent.Kind.ATTACK, "local", "target", Confidence.OBSERVED, a, ""));
    first.accept(
        new CombatEvent(
            2,
            50_000_000,
            CombatEvent.Kind.HURT,
            "local",
            "target",
            Confidence.CONFIRMED,
            null,
            "local-melee-source"));
    first.finish(100_000_000, FightRecord.Outcome.UNKNOWN, "test");
    var saved = FightStore.decode(AtomicJson.GSON.toJson(records.getFirst()));
    var replay = new CombatEngine(config, f -> {}, (e, g) -> {});
    saved.events().forEach(replay::accept);
    replay.finish(100_000_000, FightRecord.Outcome.UNKNOWN, "replay");
    assertEquals(
        AtomicJson.GSON.toJson(saved.statistics()), AtomicJson.GSON.toJson(replay.stats()));
  }

  @Test
  void deleteLimitedToUuid() throws Exception {
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(dir, errors::add)) {
      Files.writeString(dir.resolve("keep.txt"), "keep");
      store.delete("../keep.txt", () -> fail("Should not delete"));
      assertTrue(store.await(5000));
      assertEquals("keep", Files.readString(dir.resolve("keep.txt")));
      assertEquals(1, errors.size());
    }
  }

  @Test
  void hudAnchorStableAcrossViewportSizes() {
    var h = new Config.Hud(.7, .3, 1, .8f, true, true);
    int x = dev.kwamster.hud.layout.HudLayout.x(h, 1920, 200);
    dev.kwamster.hud.layout.HudLayout.move(h, x, 200, 1920, 1080, 200, 100);
    double anchor = h.x;
    assertEquals(
        (int) Math.round(anchor * (854 - 200)), dev.kwamster.hud.layout.HudLayout.x(h, 854, 200));
  }
}
