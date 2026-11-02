package dev.phenomena;

import dev.phenomena.combat.events.CombatEvent;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.Config;
import dev.phenomena.recording.RecordingController;
import dev.phenomena.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;

/** Replays public synthetic evidence through the real recording controller. */
public final class ManualReportExample {
  public static FightRecord build() {
    var source = ReportExample.build().last();
    List<FightRecord> out = new ArrayList<>();
    RecordingController[] ref = new RecordingController[1];
    var config = new Config();
    var engine =
        new CombatEngine(
            config,
            f -> ref[0].automaticFinished(f),
            (e, g) -> {},
            id -> "B".equals(id) ? "PlayerB" : "PlayerC");
    var c =
        new RecordingController(
            config,
            engine,
            out::add,
            f -> {},
            s -> {},
            id -> "B".equals(id) ? "PlayerB" : "PlayerC");
    ref[0] = c;
    c.start("minemen.club", "PlayerA", "minecraft:overworld", Sim.ns(0), 20);
    c.announcement(
        "minemen.club",
        "PlayerA",
        Announcements.interpret("minemen.club", Sim.MM_START, Announcements.Origin.SYSTEM)
            .orElseThrow(),
        Sim.ns(0));
    long seq = 0;
    for (var e : source.events()) {
      c.accept(
          new CombatEvent(
              ++seq,
              e.nanoTime(),
              e.kind(),
              e.actor(),
              e.target(),
              e.confidence(),
              e.attack(),
              e.detail(),
              e.clientTick(),
              e.context()));
    }
    c.announcement(
        "minemen.club",
        "PlayerA",
        Announcements.interpret("minemen.club", Sim.MM_WIN, Announcements.Origin.SYSTEM)
            .orElseThrow(),
        Sim.ns(18.01));
    // A second opponent, independent of the first fight's result.
    for (int i = 0; i < 5; i++) {
      double t = 24 + i * .7;
      c.accept(
          new CombatEvent(
              ++seq,
              Sim.ns(t),
              CombatEvent.Kind.ATTACK,
              "local",
              "C",
              dev.phenomena.combat.events.Confidence.OBSERVED,
              Sim.attack(2.9, "minecraft:diamond_axe", null),
              "synthetic",
              Sim.clientTick(t)));
      c.accept(
          new CombatEvent(
              ++seq,
              Sim.ns(t + .02),
              CombatEvent.Kind.HURT,
              "local",
              "C",
              dev.phenomena.combat.events.Confidence.CONFIRMED,
              null,
              "local-melee-source",
              Sim.clientTick(t)));
    }
    c.end(Sim.ns(35), 720);
    var f = out.getFirst();
    return new FightRecord(
        f.schemaVersion(),
        "01400000-0000-4000-8000-000000000014",
        f.startEpochMillis(),
        f.endEpochMillis(),
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

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args.length == 0 ? "build/example-report-v014" : args[0]);
    ReportExporter.export(out, build(), ReportExporter.Options.DEFAULT);
    System.out.println(out.toAbsolutePath().resolve("index.html"));
  }
}
