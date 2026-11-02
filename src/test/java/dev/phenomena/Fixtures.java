package dev.phenomena;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.Attack;
import dev.phenomena.combat.events.CombatEvent.Kind;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.tracking.CombatEngine;
import dev.phenomena.config.Config;
import java.util.*;

/** Builds fights through the real CombatEngine so derived analytics are checked against it. */
final class Fixtures {
  static final String TARGET = "11111111-2222-3333-4444-555555555555";

  private final Config config = new Config();
  private final List<FightRecord> out = new ArrayList<>();
  private final CombatEngine engine;
  private long seq;

  Fixtures() {
    this("Husk");
  }

  Fixtures(String label) {
    engine = new CombatEngine(config, out::add, (e, g) -> {}, id -> label);
  }

  static Attack attack(
      double cooldown,
      double reach,
      double ax,
      double ay,
      boolean sprint,
      boolean crit,
      boolean contact) {
    return new Attack(cooldown, reach, ax, ay, contact ? .0 : -1, sprint, crit, contact);
  }

  Fixtures attackAt(double seconds, Attack a) {
    emit(seconds, Kind.ATTACK, "local", TARGET, Confidence.OBSERVED, a, "test");
    return this;
  }

  Fixtures empty(double seconds) {
    emit(seconds, Kind.ATTACK, "local", null, Confidence.OBSERVED, null, "empty-swing");
    return this;
  }

  Fixtures hurt(double seconds) {
    emit(seconds, Kind.HURT, "local", TARGET, Confidence.CONFIRMED, null, "local-melee-source");
    return this;
  }

  Fixtures incoming(double seconds) {
    emit(
        seconds,
        Kind.DAMAGE_IN,
        TARGET,
        "local",
        Confidence.CONFIRMED,
        null,
        "server damage event");
    return this;
  }

  Fixtures jump(double seconds) {
    emit(seconds, Kind.JUMP, "local", null, Confidence.OBSERVED, null, "ground-to-air");
    return this;
  }

  Fixtures motion(double seconds) {
    emit(seconds, Kind.MOTION, "local", null, Confidence.OBSERVED, null, "vx=0;vy=0;vz=0");
    return this;
  }

  /** Attack immediately confirmed 40 ms later. */
  Fixtures hit(double seconds, Attack a) {
    return attackAt(seconds, a).hurt(seconds + .04);
  }

  private void emit(
      double seconds,
      Kind kind,
      String actor,
      String target,
      Confidence c,
      Attack a,
      String detail) {
    engine.accept(
        new CombatEvent(
            ++seq,
            1_000_000_000L + (long) (seconds * 1e9),
            kind,
            actor,
            target,
            c,
            a,
            detail,
            20L + (long) (seconds * 20)));
  }

  CombatEngine engine() {
    return engine;
  }

  FightRecord finish(double seconds) {
    engine.finish(1_000_000_000L + (long) (seconds * 1e9), FightRecord.Outcome.UNKNOWN, "test");
    return out.getLast();
  }

  /** A representative fight: hits at varied geometry, misses, retaliation and jumps. */
  static FightRecord sample() {
    var f = new Fixtures();
    f.hit(0, attack(1, 2.5, .5, .5, true, false, true));
    f.hit(.6, attack(.97, 2.9, .05, .9, false, true, true));
    f.attackAt(1.1, attack(.6, 3.4, .5, .1, false, false, true)); // uncertain, contact
    f.empty(1.3);
    f.hit(1.8, attack(.9, 2.1, .5, .3, true, false, true));
    f.incoming(2.0);
    f.jump(2.08); // candidate (80 ms)
    f.motion(2.1);
    f.hit(2.6, attack(.4, 3.1, .5, .6, false, false, false));
    f.hit(3.0, attack(1, 2.7, .95, .5, true, false, true));
    f.incoming(3.4);
    f.jump(3.9); // 500 ms: outside window
    f.jump(6.0); // no recent damage
    return f.finish(6.5);
  }
}
