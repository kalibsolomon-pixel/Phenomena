package dev.phenomena;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.Config;
import dev.phenomena.recording.storage.*;
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
    Path out = Path.of(args.length > 0 ? args[0] : "build/performance");
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

    // Dashboard data at the in-memory caps: 1,000 fights, 50,000 timeline events in 50 fights.
    List<FightRecord> timelines = new ArrayList<>();
    var maker = new CombatEngine(config, timelines::add, (e, g) -> {});
    long s2 = 0, t2 = 1;
    for (int f = 0; f < 50; f++) {
      for (int i = 0; i < 500; i++) {
        t2 += 700_000_000;
        maker.accept(
            new CombatEvent(
                ++s2, t2, Kind.ATTACK, "local", "opponent" + f, Confidence.OBSERVED, a, ""));
        maker.accept(
            new CombatEvent(
                ++s2,
                t2 + 10_000_000,
                Kind.HURT,
                "local",
                "opponent" + f,
                Confidence.CONFIRMED,
                null,
                "local-melee-source"));
      }
      maker.finish(t2 + 20_000_000, FightRecord.Outcome.UNKNOWN, "benchmark");
    }
    List<FightRecord> history = new ArrayList<>(timelines);
    while (history.size() < 1000) history.add(timelines.get(history.size() % 50).summaryOnly());
    double best = Double.MAX_VALUE;
    for (int rep = 0; rep < 7; rep++) {
      long t0 = System.nanoTime();
      var agg =
          new dev.phenomena.analytics.Aggregate(history, dev.phenomena.analytics.FightAnalytics::of);
      if (agg.hitSamples.size() != 25_000) throw new IllegalStateException("unexpected samples");
      best = Math.min(best, (System.nanoTime() - t0) / 1e6);
    }
    result.put("dashboardAggregate1000Fights50kEventsMs", best);
    var big = timelines.getFirst();
    Path report = Files.createTempDirectory(out, "report-benchmark-");
    long r0 = System.nanoTime();
    dev.phenomena.sharing.ReportExporter.export(
        report, big, dev.phenomena.sharing.ReportExporter.Options.DEFAULT);
    result.put("reportExport1000EventsMs", (System.nanoTime() - r0) / 1e6);
    result.put("reportExport1000EventsBytes", Files.size(report.resolve("index.html")));

    // 0.1.3: realistic event mix with structured context (attempts, hits, attributed and
    // unattributed incoming damage, shields, health, velocity, motion, jumps).
    List<FightRecord> rich = new ArrayList<>();
    var richEngine = new CombatEngine(config, rich::add, (e, g) -> {}, id -> "PlayerB");
    long rs = 0, rt = 1_000_000_000L;
    long richAllocated = 0, richStart = 0;
    int richEvents = 0;
    for (int f = 0; f < 40; f++) {
      if (f == 20) {
        // The first 20 fights warm the JIT; only the second 20 are measured.
        rich.clear();
        richEvents = 0;
        richAllocated = bean.getThreadAllocatedBytes(thread);
        richStart = System.nanoTime();
      }
      for (int i = 0; i < 1100; i++) {
        long tick = rt / 50_000_000L;
        rt += 150_000_000;
        int k = i % 11;
        CombatEvent e =
            switch (k) {
              case 0, 4, 8 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.ATTACK,
                      "local",
                      "B",
                      Confidence.OBSERVED,
                      a,
                      "",
                      tick,
                      Context.target(k == 8, k == 8));
              case 1, 5 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.HURT,
                      "local",
                      "B",
                      Confidence.CONFIRMED,
                      null,
                      "local-melee-source",
                      tick,
                      null);
              case 2 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.DAMAGE_IN,
                      "B",
                      "local",
                      Confidence.CONFIRMED,
                      null,
                      "server damage event",
                      tick,
                      Context.incoming(
                          Attribution.OPPONENT_DIRECT,
                          "minecraft:player_attack",
                          true,
                          true,
                          false,
                          true));
              case 3 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.VELOCITY,
                      "local",
                      null,
                      Confidence.OBSERVED,
                      null,
                      "",
                      tick,
                      Context.velocity(.3, .36, .1, true));
              case 6 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.HEALTH,
                      "local",
                      null,
                      Confidence.OBSERVED,
                      null,
                      "",
                      tick,
                      Context.health(20 - (i % 7), 0));
              case 7 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.JUMP,
                      "local",
                      null,
                      Confidence.OBSERVED,
                      null,
                      "",
                      tick,
                      Context.jump(false));
              case 9 ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.SHIELD,
                      null,
                      null,
                      Confidence.OBSERVED,
                      null,
                      "",
                      tick,
                      Context.shield("BLOCK", "opponent", .7));
              default ->
                  new CombatEvent(
                      ++rs,
                      rt,
                      Kind.MOTION,
                      "local",
                      null,
                      Confidence.OBSERVED,
                      null,
                      "vx=0.1;vy=0;vz=0.2;ground=true",
                      tick,
                      null);
            };
        richEngine.accept(e);
        richEvents++;
      }
      richEngine.finish(rt + 10_000_000, FightRecord.Outcome.UNKNOWN, "benchmark");
    }
    long richElapsed = System.nanoTime() - richStart;
    result.put("richEvents", richEvents);
    result.put("richNsPerEvent", richElapsed / (double) richEvents);
    result.put(
        "richAllocatedBytesPerEvent",
        (bean.getThreadAllocatedBytes(thread) - richAllocated) / (double) richEvents);

    // Worst-case single recording: the 10,000-event cap.
    List<FightRecord> capped = new ArrayList<>();
    var capEngine = new CombatEngine(config, capped::add, (e, g) -> {}, id -> "PlayerB");
    long cs = 0, ct = 1_000_000_000L;
    while (capped.isEmpty()) {
      long tick = ct / 50_000_000L;
      ct += 60_000_000;
      int k = (int) (cs % 6);
      capEngine.accept(
          switch (k) {
            case 0 ->
                new CombatEvent(
                    ++cs, ct, Kind.ATTACK, "local", "B", Confidence.OBSERVED, a, "", tick, null);
            case 1 ->
                new CombatEvent(
                    ++cs,
                    ct,
                    Kind.HURT,
                    "local",
                    "B",
                    Confidence.CONFIRMED,
                    null,
                    "local-melee-source",
                    tick,
                    null);
            case 2 ->
                new CombatEvent(
                    ++cs,
                    ct,
                    Kind.DAMAGE_IN,
                    "B",
                    "local",
                    Confidence.CONFIRMED,
                    null,
                    "",
                    tick,
                    Context.incoming(
                        Attribution.OPPONENT_DIRECT,
                        "minecraft:player_attack",
                        true,
                        true,
                        false,
                        true));
            case 3 ->
                new CombatEvent(
                    ++cs,
                    ct,
                    Kind.HEALTH,
                    "local",
                    null,
                    Confidence.OBSERVED,
                    null,
                    "",
                    tick,
                    Context.health(10 + cs % 9, 0));
            case 4 ->
                new CombatEvent(
                    ++cs,
                    ct,
                    Kind.VELOCITY,
                    "local",
                    null,
                    Confidence.OBSERVED,
                    null,
                    "",
                    tick,
                    Context.velocity(.3, .3, .1, true));
            default ->
                new CombatEvent(
                    ++cs,
                    ct,
                    Kind.JUMP,
                    "local",
                    null,
                    Confidence.OBSERVED,
                    null,
                    "",
                    tick,
                    Context.jump(false));
          });
      if (cs > 20000) capEngine.finish(ct, FightRecord.Outcome.UNKNOWN, "benchmark");
    }
    var worst = capped.getFirst();
    double bestAnalytics = Double.MAX_VALUE;
    for (int rep = 0; rep < 5; rep++) {
      long t0 = System.nanoTime();
      var fa = dev.phenomena.analytics.FightAnalytics.of(worst);
      if (fa.dominance.values.length == 0) throw new IllegalStateException();
      bestAnalytics = Math.min(bestAnalytics, (System.nanoTime() - t0) / 1e6);
    }
    result.put("cappedFightEvents", worst.events().size());
    result.put("fightAnalytics10kEventsMs", bestAnalytics);
    Path capReport = Files.createTempDirectory(out, "report-benchmark-capped-");
    long c0 = System.nanoTime();
    dev.phenomena.sharing.ReportExporter.export(
        capReport, worst, dev.phenomena.sharing.ReportExporter.Options.DEFAULT);
    result.put("reportExport10kEventsMs", (System.nanoTime() - c0) / 1e6);
    result.put("reportExport10kEventsBytes", Files.size(capReport.resolve("index.html")));

    // Dashboard aggregate with 0.1.3 context-rich timelines at the in-memory caps.
    List<FightRecord> richHistory = new ArrayList<>(rich);
    while (richHistory.size() < 1000)
      richHistory.add(rich.get(richHistory.size() % rich.size()).summaryOnly());
    double bestRich = Double.MAX_VALUE;
    for (int rep = 0; rep < 7; rep++) {
      long t0 = System.nanoTime();
      new dev.phenomena.analytics.Aggregate(richHistory, dev.phenomena.analytics.FightAnalytics::of);
      bestRich = Math.min(bestRich, (System.nanoTime() - t0) / 1e6);
    }
    result.put("dashboardAggregate1000FightsRich22kEventsMs", bestRich);
    System.gc();
    var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    result.put("heapUsedAfterBenchmarkMiB", memory.getUsed() / 1048576.0);
    AtomicJson.write(out.resolve("analytics.json"), result);
    System.out.println(AtomicJson.GSON.toJson(result));
  }
}
