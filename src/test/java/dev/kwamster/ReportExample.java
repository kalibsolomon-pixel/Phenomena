package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.events.CombatEvent.Kind;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.metrics.Geometry.*;
import dev.kwamster.combat.quality.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Public synthetic measurements only. Never reads multiplayer data or player identifiers. */
public final class ReportExample {
  public static void main(String[] args) throws Exception {
    List<FightRecord> records = new ArrayList<>();
    var config = new Config();
    var timing = new AttackTiming();
    var engine = new CombatEngine(config, records::add, (e, g) -> {}, id -> "TrainingOpponent");
    var message =
        Announcements.interpret(
                "minemen.net",
                "Casual Match\n ● Map: Example\n ● Opponent: TrainingOpponent\n ● Ping: 40 ms",
                Announcements.Origin.SYSTEM)
            .orElseThrow();
    engine.announcement("minemen.net", "ExamplePlayer", message, 0);
    String[] weapons = {
      "sword",
      "sword",
      "sword",
      "sword",
      "axe",
      "axe",
      "trident",
      "trident",
      "mace",
      "mace",
      "fist",
      "fist",
      "ordinary_item",
      "ordinary_item"
    };
    long[] ticks = {50, 62, 66, 90, 150, 170, 210, 228, 260, 293, 330, 335, 350, 355};
    double[] speeds = {1.6, 1.6, 1.6, 1.6, 1, 1, 1.1, 1.1, .6, .6, 4, 4, 4, 4};
    long seq = 0;
    for (int n = 0; n < ticks.length; n++) {
      long tick = ticks[n], time = tick * 50_000_000L;
      var box = new Box(0, 0, 3, .6, 1.8, 3.6);
      var eye = new Vec(.3, 1.62, 0);
      var region = MeleeAim.region(eye, box, 3.1);
      Vec impact =
          new Vec(
              n % 4 == 0 ? .5 : n % 4 == 1 ? .7 : .15,
              n % 4 == 0 ? .85 : n % 4 == 1 ? .8 : n % 4 == 2 ? .65 : .1,
              0);
      var contact = new Vec(impact.x() * .6, impact.y() * 1.8, 3);
      double cooldown = AttackTiming.cooldownTicks(speeds[n]);
      var measurement =
          new Measurement(
              weapons[n],
              speeds[n],
              cooldown,
              timing.attack(weapons[n], tick, cooldown, 1),
              eye,
              box,
              region,
              MeleeAim.classify(impact, region, box, true),
              "MELEE_ATTEMPT");
      var attack =
          new Attack(
              1,
              eye.distance(contact),
              impact.x(),
              impact.y(),
              impact.z(),
              n % 3 == 0,
              false,
              true,
              measurement);
      engine.accept(
          new CombatEvent(
              ++seq,
              time,
              Kind.ATTACK,
              "local",
              "synthetic-target",
              Confidence.OBSERVED,
              attack,
              "synthetic melee attempt",
              tick));
      engine.accept(
          new CombatEvent(
              ++seq,
              time + 1_000_000,
              Kind.HURT,
              "local",
              "synthetic-target",
              Confidence.CONFIRMED,
              null,
              "local-melee-source",
              tick));
      if (n == 4 || n == 8) {
        engine.accept(
            new CombatEvent(
                ++seq,
                time + 40_000_000,
                Kind.JUMP_INPUT,
                "local",
                null,
                Confidence.OBSERVED,
                null,
                "jump pressed",
                tick));
        engine.accept(
            new CombatEvent(
                ++seq,
                time + 45_000_000,
                Kind.JUMP,
                "local",
                null,
                Confidence.OBSERVED,
                null,
                "actual jump",
                tick));
        engine.accept(
            new CombatEvent(
                ++seq,
                time + 48_000_000,
                Kind.DAMAGE_IN,
                "synthetic-target",
                "local",
                Confidence.CONFIRMED,
                null,
                "synthetic server damage",
                n == 4 ? tick + 1 : tick));
      }
    }
    var result =
        Announcements.interpret(
                "minemen.net",
                "Match Results (Click to view)\n"
                    + " 🏆 Winner: ExamplePlayer  ▏  ☠ Loser: TrainingOpponent",
                Announcements.Origin.SYSTEM)
            .orElseThrow();
    engine.announcement("minemen.net", "ExamplePlayer", result, 18_000_000_000L);
    var f = records.getFirst();
    long epoch = Instant.parse("2026-10-22T22:04:31Z").toEpochMilli();
    f =
        new FightRecord(
            2,
            "01200000-0000-4000-8000-000000000012",
            epoch,
            epoch + f.durationNanos() / 1_000_000,
            f.durationNanos(),
            f.opponent(),
            f.outcome(),
            f.reason(),
            f.statistics(),
            f.events(),
            f.policy(),
            f.evaluations(),
            f.opponentLabel(),
            f.matchEvidence());
    Path output = Path.of(args.length == 0 ? "build/example-report-v012" : args[0]);
    ReportExporter.export(output, f, ReportExporter.Options.DEFAULT);
    System.out.println(output.toAbsolutePath().resolve("index.html"));
  }
}
