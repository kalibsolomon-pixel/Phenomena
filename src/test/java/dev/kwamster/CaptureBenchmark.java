package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.Kind;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.*;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;

/** Reproducible synthetic costs; not an FPS or live-server latency measurement. */
public final class CaptureBenchmark {
  private static volatile Object blackhole;

  public static void main(String[] args) throws Exception {
    Path root = Path.of("build/performance/v015-capture");
    Files.createDirectories(root);
    Map<String, Object> result = new LinkedHashMap<>();
    String start = "Casual Match ● Map: Village ● Opponent: PlayerB ● Ping: 40 ms";
    String end = "Match Results (Click to view) 🏆 Winner: PlayerA ▏ ☠ Loser: PlayerB";
    for (String mode :
        List.of("host-policy", "matching", "lifecycle", "diagnostics-off", "diagnostics-on")) {
      List<Double> ns = new ArrayList<>();
      int count = mode.equals("lifecycle") ? 2000 : mode.startsWith("diagnostics") ? 10000 : 100000;
      for (int run = 0; run < 4; run++) {
        try (var store =
            new FightStore(
                root.resolve(mode + run + "/fights"),
                s -> {
                  throw new IllegalStateException(s);
                })) {
          var config = new Config();
          var engine =
              new CombatEngine(
                  config,
                  f -> {
                    blackhole = f;
                  },
                  (e, g) -> {});
          engine.context("minemen.club", "PlayerA");
          var d = new Diagnostics(store, root.resolve(mode + run), s -> {});
          d.enabled(mode.equals("diagnostics-on"));
          d.policy(engine::capturePolicy, engine::captureLimitation);
          var a =
              Announcements.interpret("minemen.club", start, Announcements.Origin.SYSTEM)
                  .orElseThrow();
          var b =
              Announcements.interpret("minemen.club", end, Announcements.Origin.SYSTEM)
                  .orElseThrow();
          long before = System.nanoTime();
          for (int i = 0; i < count; i++) {
            long t = 1_000_000_000L + i * 10_000_000_000L;
            switch (mode) {
              case "host-policy" ->
                  blackhole = CapturePolicy.resolve("east.catpvp.net", true, false);
              case "matching" ->
                  blackhole =
                      Announcements.interpret(
                          i % 2 == 0 ? "minemen.club" : "bad.minemen.club",
                          i % 3 == 0 ? "Unrelated message" : end,
                          Announcements.Origin.SYSTEM);
              case "lifecycle" -> {
                engine.announcement("minemen.club", "PlayerA", a, t);
                engine.tick(t + 5_000_000_000L);
                engine.announcement("minemen.club", "PlayerA", b, t + 6_000_000_000L);
              }
              default ->
                  d.announcement(
                      "minemen.club",
                      start,
                      Announcements.Origin.SYSTEM,
                      a,
                      t,
                      "Accepted configured match start");
            }
          }
          double elapsed = (System.nanoTime() - before) / (double) count;
          if (run > 0) ns.add(elapsed);
          d.flush();
          if (!store.await(20000)) throw new IllegalStateException("writer timeout");
        }
      }
      result.put(mode, Map.of("nsPerOperation", ns, "operationsPerRun", count));
    }
    List<FightRecord> records = new ArrayList<>();
    var engine = new CombatEngine(new Config(), records::add, (e, g) -> {}, id -> "PlayerB");
    long seq = 0, time = 1_000_000_000L;
    engine.announcement(
        "minemen.club",
        "PlayerA",
        Announcements.interpret("minemen.club", start, Announcements.Origin.SYSTEM).orElseThrow(),
        time);
    long before = System.nanoTime();
    for (int round = 1; round <= 250; round++) {
      engine.announcement(
          "minemen.club",
          "PlayerA",
          Announcements.interpret(
                  "minemen.club", "Round " + round + " started!", Announcements.Origin.SYSTEM)
              .orElseThrow(),
          time += 1_000_000_000L);
      for (int hit = 0; hit < 10; hit++) {
        time += 100_000_000L;
        engine.accept(
            new CombatEvent(
                ++seq,
                time,
                Kind.ATTACK,
                "local",
                "B",
                Confidence.OBSERVED,
                new CombatEvent.Attack(1, 3, .5, .5, 0, false, false, true),
                "benchmark",
                seq));
        engine.accept(
            new CombatEvent(
                ++seq,
                time + 1_000_000,
                Kind.HURT,
                "local",
                "B",
                Confidence.CONFIRMED,
                null,
                "local-melee-source",
                seq));
      }
      // Hours of inter-round inactivity cannot finalize the match.
      engine.tick(time += 60_000_000_000L);
    }
    engine.announcement(
        "minemen.club",
        "PlayerA",
        Announcements.interpret("minemen.club", end, Announcements.Origin.SYSTEM).orElseThrow(),
        time + 1);
    double eventNs = (System.nanoTime() - before) / 5000.0;
    if (records.size() != 1 || records.getFirst().matchEvidence().rounds().size() != 250)
      throw new AssertionError("long match lifecycle");
    before = System.nanoTime();
    Path html =
        ReportExporter.export(
            root.resolve("long-match-report"), records.getFirst(), ReportExporter.Options.DEFAULT);
    result.put(
        "long-match",
        Map.of(
            "events",
            5000,
            "rounds",
            250,
            "simulatedSeconds",
            time / 1e9,
            "nsPerEventIncludingRoundMatchingAndFinalization",
            eventNs,
            "exportMillis",
            (System.nanoTime() - before) / 1e6,
            "htmlBytes",
            Files.size(html),
            "recordings",
            records.size()));
    result.put(
        "limits",
        "One discarded warmup and three measured runs. Diagnostic serialization runs"
            + " asynchronously; long-match timing includes rules, events, ticks and finalization."
            + " No live FPS claim.");
    AtomicJson.write(root.resolve("capture.json"), result);
    System.out.println(AtomicJson.GSON.toJson(result));
  }
}
