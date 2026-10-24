package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.recording.storage.AtomicJson;
import java.nio.file.*;
import java.util.*;

/** Bounded synthetic workloads, warmup then three runs. No FPS or zero-overhead claim. */
public final class ExchangeBenchmark {
  private static volatile long sink;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args.length > 0 ? args[0] : "build/performance/v016-exchanges");
    Files.createDirectories(out);
    List<Map<String, Object>> results = new ArrayList<>();
    var attack = Sim.attack(3, "minecraft:diamond_sword", null);
    for (int run = -1; run < 3; run++) {
      long began = System.nanoTime();
      for (int group = 0; group < 100; group++) {
        var s = new Statistics();
        var t = new AttackObservations(s);
        for (int i = 0; i < 1000; i++) {
          long tick = i * 3L, id = i * 2L + 1;
          t.observe(
              new CombatEvent(
                  id,
                  tick * 50_000_000L,
                  CombatEvent.Kind.ATTACK,
                  "local",
                  "B",
                  Confidence.OBSERVED,
                  attack,
                  "",
                  tick));
          t.observe(
              new CombatEvent(
                  id + 1,
                  (tick + 1) * 50_000_000L,
                  CombatEvent.Kind.ATTACK,
                  "local",
                  "B",
                  Confidence.OBSERVED,
                  attack,
                  "",
                  tick + 1));
        }
        sink += s.uniqueAttacks;
      }
      double dedup = (System.nanoTime() - began) / 200000.0;
      var exchange = new CombatExchange(3);
      began = System.nanoTime();
      for (int i = 0; i < 100000; i++) {
        exchange.interact(i * 10_000_000L);
        exchange.advance(i * 10_000_000L + 5_000_000);
      }
      double exchangeNs = (System.nanoTime() - began) / 200000.0;
      var s = new Statistics();
      var t = new AttackObservations(s);
      for (long i = 1; i <= 1000; i++)
        t.observe(
            new CombatEvent(
                i,
                i * 150_000_000L,
                CombatEvent.Kind.ATTACK,
                "local",
                "B",
                Confidence.OBSERVED,
                attack,
                "",
                i * 3));
      began = System.nanoTime();
      for (int i = 0; i < 200000; i++)
        t.classify(
            i % 1000 + 1L,
            i % 2 == 0
                ? AttackObservations.Classification.HIT
                : AttackObservations.Classification.UNRESOLVED);
      double classify = (System.nanoTime() - began) / 200000.0;
      if (run >= 0)
        results.add(
            Map.of(
                "run",
                run + 1,
                "dedupIncludingClassificationNsPerRawInput",
                dedup,
                "exchangeInteractionOrTickNsPerOperation",
                exchangeNs,
                "classificationUpdateNsPerOperation",
                classify));
    }
    var data =
        Map.of(
            "runs",
            results,
            "limits",
            "One discarded warmup; three synthetic runs. Dedup includes map/classification work,"
                + " not immutable snapshot serialization. No per-frame entity scans or live FPS"
                + " claim.");
    AtomicJson.write(out.resolve("exchanges.json"), data);
    System.out.println(AtomicJson.GSON.toJson(data));
  }
}
