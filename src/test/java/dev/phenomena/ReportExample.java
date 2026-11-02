package dev.phenomena;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.metrics.Geometry.*;
import dev.phenomena.combat.quality.*;
import dev.phenomena.sharing.ReportExporter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Public synthetic measurements only. Never reads multiplayer data or player identifiers. Builds a
 * deterministic 0.1.3 fight through the real engine: several weapons, combos, a same-tick trade,
 * attributed and unattributed incoming damage, shield blocks and a disable, jumps near incoming
 * hits, knockback and health observations, a server critical hit and a confirmed server result.
 */
public final class ReportExample {
  private record Step(double time, int order, Runnable action) {}

  static Sim build() {
    var s = new Sim();
    List<Step> steps = new ArrayList<>();
    int[] order = {0};
    java.util.function.BiConsumer<Double, Runnable> at =
        (t, r) -> steps.add(new Step(t, order[0]++, r));
    var timing = new AttackTiming();
    String sword = "minecraft:diamond_sword",
        axe = "minecraft:diamond_axe",
        mace = "minecraft:mace";
    String[] weapons = {
      sword, sword, sword, sword, sword, axe, axe, axe, sword, sword, sword, mace, mace, sword,
      sword, sword
    };
    double[] times = {
      2.5, 3.13, 3.75, 4.5, 5.2, 7.0, 8.0, 9.05, 10.3, 10.95, 11.6, 13.0, 14.7, 16.1, 16.75, 17.4
    };
    double[] reach = {
      2.95, 3.02, 3.1, 2.7, 3.04, 2.6, 2.98, 3.2, 2.99, 3.05, 2.85, 2.4, 2.55, 3.01, 2.97, 3.3
    };
    double[] speeds = {1.6, 1.6, 1.6, 1.6, 1.6, 1, 1, 1, 1.6, 1.6, 1.6, .6, .6, 1.6, 1.6, 1.6};
    at.accept(0.0, () -> s.chat(0, Sim.MM_START));
    for (int i = 0; i < times.length; i++) {
      int n = i;
      double t = times[n];
      at.accept(
          t,
          () -> {
            long tick = Sim.clientTick(t);
            double cooldown = AttackTiming.cooldownTicks(speeds[n]);
            var box = new Box(-.3, 0, 2.7, .3, 1.8, 3.3);
            var eye = new Vec(0, 1.62, 0);
            var region = MeleeAim.region(eye, box, 3.1);
            var impact =
                new Vec(
                    n % 4 == 0 ? .5 : n % 4 == 1 ? .66 : n % 4 == 2 ? .3 : .82,
                    n % 3 == 0 ? .78 : n % 3 == 1 ? .6 : .9,
                    0);
            var m =
                new Measurement(
                    weapons[n] + ";slot=0;attributes=0",
                    speeds[n],
                    cooldown,
                    timing.attack(weapons[n], tick, cooldown, 1),
                    eye,
                    box,
                    region,
                    MeleeAim.classify(impact, region, box, true),
                    "MELEE_ATTEMPT");
            boolean blocking = n == 6 || n == 7;
            s.emit(
                t,
                Kind.ATTACK,
                "local",
                Sim.B,
                Confidence.OBSERVED,
                new Attack(1, reach[n], impact.x(), impact.y(), 0, n % 3 == 0, n == 13, true, m),
                "synthetic melee attempt",
                Context.target(blocking, blocking));
          });
      if (n == 6) at.accept(t + .03, () -> s.shield(t + .03, "BLOCK", "opponent"));
      else if (n == 7) at.accept(t + .03, () -> s.shield(t + .03, "DISABLE", "opponent"));
      else if (n != 3) at.accept(t + .02, () -> s.hurt(t + .02, Sim.B));
      if (n == 13) at.accept(t + .03, () -> s.crit(t + .03, "opponent"));
    }
    at.accept(1.0, () -> s.health(1, 20));
    for (double t : new double[] {5.9, 6.45, 12.2}) {
      at.accept(t, () -> s.incoming(t));
      at.accept(t + .01, () -> s.velocity(t + .01, .32, .18));
    }
    at.accept(5.91, () -> s.jump(5.91));
    at.accept(5.95, () -> s.health(5.95, 16));
    at.accept(6.6, () -> s.jump(6.6));
    at.accept(6.5, () -> s.health(6.5, 13));
    at.accept(9.5, () -> s.unattributed(9.5));
    at.accept(9.55, () -> s.health(9.55, 12));
    at.accept(10.31, () -> s.incoming(10.31)); // same tick as the hit at 10.30
    at.accept(10.35, () -> s.health(10.35, 9));
    at.accept(12.3, () -> s.jump(12.3));
    at.accept(12.25, () -> s.health(12.25, 6));
    at.accept(15.2, () -> s.shield(15.2, "BLOCK", "local"));
    for (int k = 0; k < 168; k++) {
      double t = 1.25 + k * .1;
      at.accept(t, () -> s.motion(t, .12 * Math.sin(t), .1 * Math.cos(t)));
    }
    at.accept(18.0, () -> s.chat(18, "PlayerB was killed by PlayerA!"));
    at.accept(18.01, () -> s.chat(18.01, Sim.MM_WIN));
    steps.sort(Comparator.comparingDouble(Step::time).thenComparingInt(Step::order));
    for (var step : steps) step.action().run();
    return s;
  }

  public static void main(String[] args) throws Exception {
    var f = build().last();
    long epoch = Instant.parse("2026-10-23T22:04:31Z").toEpochMilli();
    f =
        new FightRecord(
            f.schemaVersion(),
            "01300000-0000-4000-8000-000000000013",
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
            f.matchEvidence(),
            f.resolution());
    Path output = Path.of(args.length == 0 ? "build/example-report-v013" : args[0]);
    ReportExporter.export(output, f, ReportExporter.Options.DEFAULT);
    System.out.println(output.toAbsolutePath().resolve("index.html"));
  }
}
