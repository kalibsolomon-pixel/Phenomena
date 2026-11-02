package dev.phenomena;

import com.google.gson.Gson;
import dev.phenomena.combat.events.*;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.recording.storage.FightStore;
import dev.phenomena.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;

/**
 * Deterministic review set for the HTML report: public synthetic evidence only, never player data.
 * Each scenario exercises a different report path (rounds, exchanges, manual sessions,
 * interruption, historical schema, missing metrics, a long match). Optional extra arguments are
 * recording files exported read-only alongside the set, for local validation with test-client
 * recordings. Usage: ReportSamples OUTPUT_DIR [RECORDING.json ...]
 */
public final class ReportSamples {
  static final long EPOCH = 1_793_000_000_000L; // 2026-10-25, fixed for reproducible output

  /** Re-identifies a synthetic recording with a fixed id and start time. */
  static FightRecord fixed(FightRecord f, int n) {
    String id = String.format("0210%04d-0000-4000-8000-000000000021", n);
    return new FightRecord(
        f.schemaVersion(),
        id,
        EPOCH + n * 3_600_000L,
        EPOCH + n * 3_600_000L + f.durationNanos() / 1_000_000,
        f.durationNanos(),
        f.opponent(),
        f.outcome(),
        f.reason(),
        f.statistics(),
        f.events(),
        f.policy(),
        f.evaluations(),
        f.opponentLabel(),
        f.matchEvidence(),
        f.resolution(),
        f.session());
  }

  /** Three announced rounds with incoming pressure, a shield block and a round loss. */
  static FightRecord multiRound() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.hit(1.2, 2.95).hit(1.8, 3.02).hit(2.4, 3.08).incoming(2.9).hit(3.5, 2.88);
    s.chat(4.2, "PlayerA won! 1 - 0");
    s.chat(7, "Round 2 started!");
    s.incoming(7.6).incoming(8.2).hit(8.5, 3.1).incoming(9.0).jump(9.0).incoming(9.6);
    s.attack(10.1, Sim.B, 3.0, true, true).shield(10.13, "BLOCK", "opponent");
    s.chat(11, "PlayerB won! 1 - 1");
    s.chat(14, "Round 3 started!");
    s.hit(14.6, 2.97).hit(15.2, 3.0).hit(15.8, 3.04).attack(16.3).hit(16.9, 2.92);
    s.incoming(17.4).hit(18.0, 3.06);
    s.chat(18.6, "PlayerA won! 2 - 1");
    s.chat(18.7, Sim.MM_WIN);
    return s.last();
  }

  /** Separated combat exchanges with breaks, an opening miss, duplicates and a shield. */
  static FightRecord exchanges() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.emit(
        1,
        CombatEvent.Kind.ATTACK,
        "local",
        Sim.B,
        Confidence.OBSERVED,
        Sim.attack(3.3, "minecraft:diamond_sword", null),
        "empty-swing; aimed near opponent",
        null);
    s.hit(1.5).attack(1.55).hit(2).hit(2.6).incoming(3.0).hit(3.4).tick(9.1);
    s.emit(
        9.3,
        CombatEvent.Kind.ATTACK,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "empty-swing; no aimed opponent",
        null);
    s.hit(11).incoming(11.5).attack(12, Sim.B, 3, true, true).shield(12.05, "BLOCK", "opponent");
    s.hit(12.7).hit(13.3).tick(20);
    s.incoming(21).incoming(21.6).hit(22.1).attack(22.7).hit(23.2).incoming(23.8).hit(24.4);
    s.chat(25, Sim.MM_WIN);
    return s.last();
  }

  /** Combat that ends when the client disconnects: no result evidence. */
  static FightRecord interrupted() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.hit(1, 3.0).hit(1.6, 2.9).incoming(2.1).hit(2.7, 3.15).attack(3.3).incoming(3.8);
    s.disconnect(4.4);
    return s.last();
  }

  /**
   * A schema-1 recording as KWAMSTER 0.1.0 stored it: no measurement, context, client ticks, match
   * evidence or resolution. Built as JSON and read by the production decoder.
   */
  static FightRecord historical() {
    List<Map<String, Object>> events = new ArrayList<>();
    long base = 50_000_000_000L;
    long[] seq = {1000};
    java.util.function.BiConsumer<Double, Map<String, Object>> add =
        (t, e) -> {
          e.put("nanoTime", base + Math.round(t * 1e9));
          e.putIfAbsent("confidence", "OBSERVED");
          e.putIfAbsent("detail", "");
          events.add(e);
        };
    double[] hits = {0.6, 1.2, 1.8, 3.5, 4.1, 6.2, 6.8};
    double[] reach = {2.91, 3.04, 2.98, 2.77, 3.12, 2.95, 3.01};
    for (int i = 0; i < hits.length; i++) {
      double t = hits[i];
      Map<String, Object> attack = new LinkedHashMap<>();
      attack.put("cooldown", i % 3 == 2 ? 0.86 : 1.0);
      attack.put("reach", reach[i]);
      attack.put("aimX", 0.5 + 0.05 * (i % 3));
      attack.put("aimY", 0.62 + 0.04 * (i % 2));
      attack.put("aimZ", 0.0);
      attack.put("sprinting", i % 2 == 0);
      attack.put("criticalConditions", i == 3);
      attack.put("rayContact", true);
      add.accept(
          t,
          new LinkedHashMap<>(
              Map.of(
                  "kind",
                  "ATTACK",
                  "actor",
                  "local",
                  "target",
                  "legacy-opponent",
                  "attack",
                  attack)));
      add.accept(
          t + 0.03,
          new LinkedHashMap<>(
              Map.of(
                  "kind",
                  "HURT",
                  "actor",
                  "local",
                  "target",
                  "legacy-opponent",
                  "confidence",
                  "CONFIRMED",
                  "detail",
                  "local-melee-source")));
    }
    for (double t : new double[] {2.5, 5.0})
      add.accept(
          t,
          new LinkedHashMap<>(
              Map.of(
                  "kind",
                  "DAMAGE_IN",
                  "actor",
                  "legacy-opponent",
                  "target",
                  "local",
                  "confidence",
                  "CONFIRMED")));
    add.accept(2.52, new LinkedHashMap<>(Map.of("kind", "JUMP", "actor", "local")));
    add.accept(
        5.4,
        new LinkedHashMap<>(
            Map.of(
                "kind", "ATTACK", "actor", "local", "detail", "empty-swing; no entity selected")));
    events.sort(Comparator.comparingLong(e -> (Long) e.get("nanoTime")));
    for (var e : events) e.put("sequence", ++seq[0]);
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("attempts", 8);
    stats.put("hits", 7);
    stats.put("emptyMisses", 1);
    stats.put("uncertain", 0);
    stats.put("contacts", 7);
    stats.put("criticalConditions", 1);
    stats.put("sprintHits", 4);
    stats.put("jumpCandidates", 1);
    stats.put("longestCombo", 3);
    stats.put("incoming", 2);
    stats.put("reachCount", 7);
    stats.put("reachSum", Arrays.stream(reach).sum());
    stats.put("maxReach", 3.12);
    stats.put("cooldownSum", 6.72);
    stats.put("intervalSum", 0);
    stats.put("intervalCount", 0);
    stats.put("centerAim", 6);
    stats.put("edgeAim", 1);
    Map<String, Object> policy =
        Map.of(
            "correlationMillis", 250.0,
            "comboSeconds", 3.0,
            "jumpWindowMillis", 150.0,
            "perfectCooldown", 0.95,
            "greatCooldown", 0.85);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", 1);
    root.put("id", "02100006-0000-4000-8000-000000000021");
    root.put("startEpochMillis", EPOCH);
    root.put("endEpochMillis", EPOCH + 7_400);
    root.put("durationNanos", 7_400_000_000L);
    root.put("opponent", "legacy-opponent");
    root.put("outcome", "WIN");
    root.put("reason", "Opponent death observed");
    root.put("statistics", stats);
    root.put("events", events);
    root.put("policy", policy);
    root.put("evaluations", List.of());
    root.put("opponentLabel", "Zombie");
    try {
      return FightStore.decode(new Gson().toJson(root));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Only empty swings and unconfirmed attacks: most metrics have no eligible samples. */
  static FightRecord incomplete() {
    return new Fixtures()
        .empty(0)
        .attackAt(.4, Fixtures.attack(1, 3.4, .5, .5, false, false, false))
        .empty(1.1)
        .attackAt(1.7, Fixtures.attack(.6, 3.6, .5, .5, false, false, false))
        .finish(2.4);
  }

  /** A long best-of match: 40 rounds, hundreds of events, both sides scoring. */
  static FightRecord longMatch() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    double t = 1;
    int a = 0, b = 0;
    for (int round = 1; round <= 40; round++) {
      if (round > 1) s.chat(t, "Round " + round + " started!");
      t += 0.6;
      boolean localWins = round % 3 != 0;
      for (int k = 0; k < 8; k++) {
        double reach = 2.75 + ((round * 7 + k * 3) % 11) * 0.05;
        if ((k + round) % 4 == 0) s.attack(t);
        else s.hit(t, reach);
        if (!localWins && k % 2 == 1) s.incoming(t + 0.25);
        if (k == 4 && round % 5 == 0) s.jump(t + 0.26);
        s.motion(t + 0.1, .12 * Math.sin(t), .1 * Math.cos(t));
        t += 0.62;
      }
      if (localWins) a++;
      else b++;
      s.chat(t, (localWins ? "PlayerA" : "PlayerB") + " won! " + a + " - " + b);
      t += 2;
    }
    s.chat(t, Sim.MM_WIN);
    return s.last();
  }

  static void export(Path out, String name, FightRecord f, List<String> index) throws Exception {
    ReportExporter.export(out.resolve(name), f, ReportExporter.Options.DEFAULT);
    index.add(name);
    System.out.println(
        name
            + ": schema "
            + f.schemaVersion()
            + ", "
            + f.events().size()
            + " events, "
            + f.resolution().classification());
  }

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args.length > 0 ? args[0] : "build/report-samples");
    Files.createDirectories(out);
    List<String> index = new ArrayList<>();
    export(out, "01-completed", fixed(ReportExample.build().last(), 1), index);
    export(out, "02-multi-round", fixed(multiRound(), 2), index);
    export(out, "03-exchanges", fixed(exchanges(), 3), index);
    export(out, "04-manual", fixed(ManualReportExample.build(), 4), index);
    export(out, "05-interrupted", fixed(interrupted(), 5), index);
    export(out, "06-historical-schema1", historical(), index);
    export(out, "07-incomplete", fixed(incomplete(), 7), index);
    export(out, "08-long-match", fixed(longMatch(), 8), index);
    for (int i = 1; i < args.length; i++) {
      var f = FightStore.decode(Files.readString(Path.of(args[i])));
      export(out, String.format("x%02d-schema%d", i, f.schemaVersion()), f, index);
    }
    Files.writeString(out.resolve("samples.json"), new Gson().toJson(index));
  }
}
