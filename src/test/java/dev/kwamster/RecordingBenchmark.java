package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.*;
import dev.kwamster.recording.storage.*;
import java.nio.file.*;
import java.util.*;

/** Diagnostic/recording event overhead, including periodic snapshot copies, no rendering. */
public final class RecordingBenchmark {
  public static void main(String[] args) throws Exception {
    Path root = Path.of("build/performance/v014");
    Files.createDirectories(root);
    Map<String, Object> results = new LinkedHashMap<>();
    for (String mode : List.of("automatic", "manual", "manual-diagnostics")) {
      List<Double> runs = new ArrayList<>();
      for (int run = 0; run < 4; run++)
        try (var store = new FightStore(root.resolve(mode + run + "/fights"), s -> {})) {
          var config = new Config();
          RecordingController[] ref = new RecordingController[1];
          var engine = new CombatEngine(config, f -> ref[0].automaticFinished(f), (e, g) -> {});
          var c = new RecordingController(config, engine, f -> {}, f -> {}, s -> {}, id -> id);
          ref[0] = c;
          var d = new Diagnostics(store, root.resolve(mode + run), s -> {});
          d.enabled(mode.contains("diagnostics"));
          long time = 1, seq = 0;
          if (!mode.equals("automatic")) c.start("test.invalid", "PlayerA", "world", time, 0);
          var attack = new Attack(1, 3, .5, .7, 0, false, false, true);
          long started = System.nanoTime();
          int pairs = 5000;
          for (int i = 0; i < pairs; i++) {
            time += 100_000_000;
            var a =
                new CombatEvent(
                    ++seq,
                    time,
                    Kind.ATTACK,
                    "local",
                    "B",
                    Confidence.OBSERVED,
                    attack,
                    "synthetic",
                    (long) i * 2);
            var h =
                new CombatEvent(
                    ++seq,
                    time + 10_000_000,
                    Kind.HURT,
                    "local",
                    "B",
                    Confidence.CONFIRMED,
                    null,
                    "local-melee-source",
                    (long) i * 2);
            c.accept(a);
            d.event("test.invalid", a);
            c.accept(h);
            d.event("test.invalid", h);
            if (i % 50 == 0) c.tick(time + 20_000_000, i * 2);
            if (i % 1000 == 999)
              engine.finish(
                  time + 20_000_000,
                  dev.kwamster.combat.fights.FightRecord.Outcome.UNKNOWN,
                  "benchmark exchange");
          }
          double ns = (System.nanoTime() - started) / (double) (pairs * 2);
          if (run > 0) runs.add(ns);
          c.shutdown(time + 30_000_000, pairs * 2, "benchmark complete");
          d.flush();
          store.await(10000);
        }
      results.put(
          mode,
          Map.of(
              "nsPerEvent",
              runs,
              "eventsPerRun",
              10000,
              "limits",
              "Synthetic; checkpoint copies included; disk and HTML export excluded from event"
                  + " timing"));
    }
    AtomicJson.write(root.resolve("recording.json"), results);
    System.out.println(AtomicJson.GSON.toJson(results));
  }
}
