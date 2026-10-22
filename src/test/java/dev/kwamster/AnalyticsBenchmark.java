package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.storage.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Reproducible synthetic benchmark; deliberately not a threshold-based unit test. */
public final class AnalyticsBenchmark {
  public static void main(String[] args) throws Exception {
    var config = new Config();
    var a = new Attack(1, 2.5, .5, .6, 0, false, false, true);
    var engine = new CombatEngine(config, f -> {}, (e, g) -> {});
    long seq = 0, time = 1;
    for (int warm = 0; warm < 10; warm++) {
      for (int i = 0; i < 1000; i++) {
        time += 700_000_000;
        engine.accept(
            new CombatEvent(
                ++seq, time, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, a, ""));
        engine.accept(
            new CombatEvent(
                ++seq,
                time + 10_000_000,
                Kind.HURT,
                "local",
                "opponent",
                Confidence.CONFIRMED,
                null,
                "local-melee-source"));
      }
      engine.finish(time + 20_000_000, FightRecord.Outcome.UNKNOWN, "warmup");
    }
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
    int pairs = 100000;
    for (int i = 0; i < pairs; i++) {
      time += 700_000_000;
      engine.accept(
          new CombatEvent(
              ++seq, time, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, a, ""));
      engine.accept(
          new CombatEvent(
              ++seq,
              time + 10_000_000,
              Kind.HURT,
              "local",
              "opponent",
              Confidence.CONFIRMED,
              null,
              "local-melee-source"));
      if (i % 1000 == 999)
        engine.finish(time + 20_000_000, FightRecord.Outcome.UNKNOWN, "benchmark");
    }
    long elapsed = System.nanoTime() - start,
        bytes = bean.getThreadAllocatedBytes(thread) - allocated;
    var result = new LinkedHashMap<String, Object>();
    result.put("events", pairs * 2);
    result.put("elapsedMs", elapsed / 1e6);
    result.put("nsPerEvent", elapsed / (pairs * 2.0));
    result.put("allocatedBytesPerEvent", bytes / (pairs * 2.0));
    List<FightRecord> records = new ArrayList<>();
    var recording = new CombatEngine(config, records::add, (e, g) -> {});
    recording.accept(
        new CombatEvent(1, 1, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, a, ""));
    recording.accept(
        new CombatEvent(
            2,
            10_000_000,
            Kind.HURT,
            "local",
            "opponent",
            Confidence.CONFIRMED,
            null,
            "local-melee-source"));
    recording.finish(20_000_000, FightRecord.Outcome.UNKNOWN, "recording");
    Path out = Path.of("build/performance");
    Files.createDirectories(out);
    Path tmp = Files.createTempDirectory(out, "storage-benchmark-");
    List<String> errors = new ArrayList<>();
    start = System.nanoTime();
    try (var store = new FightStore(tmp, errors::add)) {
      for (int i = 0; i < 100; i++) store.save(records.getFirst(), 100, 64 * 1024 * 1024);
      if (!store.await(30000)) throw new IllegalStateException("Writer did not drain");
    }
    result.put("atomicReplaceMeanMs", (System.nanoTime() - start) / 1e6 / 100);
    result.put("storageErrors", errors);
    AtomicJson.write(out.resolve("analytics.json"), result);
    System.out.println(AtomicJson.GSON.toJson(result));
  }
}
