package dev.phenomena;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.metrics.Geometry;
import dev.phenomena.combat.quality.AttackTiming;
import dev.phenomena.combat.quality.MeleeAim;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.Config;
import java.util.*;

/**
 * Deterministic simulated fights through the real engine with 0.1.3 structured context. Times are
 * seconds; client ticks are 20 + floor(seconds * 20), so events within the same 50 ms share a tick.
 */
final class Sim {
  static final String B = "B", C = "C";
  final Config config = new Config();
  final List<FightRecord> out = new ArrayList<>();
  final CombatEngine engine;
  String host = "minemen.club";
  private long seq;

  Sim() {
    this("minemen.club");
  }

  Sim(String host) {
    this.host = host;
    engine =
        new CombatEngine(
            config,
            out::add,
            (e, g) -> {},
            id -> B.equals(id) ? "PlayerB" : C.equals(id) ? "PlayerC" : "Zombie");
  }

  static long ns(double s) {
    return 1_000_000_000L + Math.round(s * 1e9);
  }

  static long clientTick(double s) {
    return 20 + (long) Math.floor(s * 20 + 1e-9);
  }

  Sim emit(
      double s,
      Kind k,
      String actor,
      String target,
      Confidence c,
      Attack a,
      String detail,
      Context ctx) {
    engine.context(host, "PlayerA"); // as ClientCapture.tick does every tick
    engine.accept(
        new CombatEvent(++seq, ns(s), k, actor, target, c, a, detail, clientTick(s), ctx));
    return this;
  }

  static Attack attack(double reach, String weapon, AttackTiming.Result timing) {
    var eye = new Geometry.Vec(0, 1.62, 0);
    var box = new Geometry.Box(-.4, 0, 2.6, .4, 1.9, 3.4);
    var region = MeleeAim.region(eye, box, 3);
    var normalized = new Geometry.Vec(.5, .7, 0);
    return new Attack(
        1,
        reach,
        .5,
        .7,
        0,
        false,
        false,
        true,
        new Measurement(
            weapon + ";slot=0;attributes=0",
            1.6,
            12.5,
            timing,
            eye,
            box,
            region,
            MeleeAim.classify(normalized, region, box, true),
            "MELEE_ATTEMPT"));
  }

  Sim attack(double s, String target, double reach, boolean blocking, boolean facing) {
    return emit(
        s,
        Kind.ATTACK,
        "local",
        target,
        Confidence.OBSERVED,
        attack(
            reach,
            "minecraft:diamond_sword",
            new AttackTiming.Result(AttackTiming.Grade.OPENER, 0, 12.5)),
        "test",
        Context.target(blocking, facing));
  }

  Sim attack(double s) {
    return attack(s, B, 3, false, false);
  }

  Sim hurt(double s, String target) {
    return emit(
        s, Kind.HURT, "local", target, Confidence.CONFIRMED, null, "local-melee-source", null);
  }

  /** Attack confirmed in the same tick. */
  Sim hit(double s) {
    return attack(s).hurt(s + .01, B);
  }

  Sim hit(double s, double reach) {
    return attack(s, B, reach, false, false).hurt(s + .01, B);
  }

  Sim incoming(double s) {
    return incoming(s, B, Attribution.OPPONENT_DIRECT, true);
  }

  Sim incoming(double s, String actor, Attribution a, boolean validMovement) {
    return emit(
        s,
        Kind.DAMAGE_IN,
        actor,
        "local",
        Confidence.CONFIRMED,
        null,
        "server damage event",
        Context.incoming(
            a,
            a == Attribution.UNATTRIBUTED ? "minecraft:fall" : "minecraft:player_attack",
            a == Attribution.OPPONENT_DIRECT,
            true,
            false,
            validMovement));
  }

  Sim unattributed(double s) {
    return incoming(s, null, Attribution.UNATTRIBUTED, true);
  }

  Sim shield(double s, String type, String subject) {
    return emit(
        s,
        Kind.SHIELD,
        null,
        null,
        Confidence.OBSERVED,
        null,
        "shield sound",
        Context.shield(type, subject, .8));
  }

  Sim crit(double s, String subject) {
    return emit(
        s,
        Kind.CRIT,
        null,
        "opponent".equals(subject) ? B : "local",
        Confidence.CONFIRMED,
        null,
        "crit",
        Context.subject(subject));
  }

  Sim jump(double s) {
    return emit(
        s, Kind.JUMP, "local", null, Confidence.OBSERVED, null, "jump", Context.jump(false));
  }

  Sim health(double s, double hp) {
    return emit(
        s, Kind.HEALTH, "local", null, Confidence.OBSERVED, null, "health", Context.health(hp, 0));
  }

  Sim velocity(double s, double vx, double vz) {
    return emit(
        s,
        Kind.VELOCITY,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "velocity",
        Context.velocity(vx, .3, vz, true));
  }

  Sim motion(double s, double vx, double vz) {
    return emit(
        s,
        Kind.MOTION,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "vx=" + vx + ";vy=0;vz=" + vz + ";ground=true",
        null);
  }

  Sim death(double s, String target) {
    return emit(s, Kind.DEATH, null, target, Confidence.OBSERVED, null, "death", null);
  }

  Sim disconnect(double s) {
    return emit(
        s,
        Kind.DISCONNECT,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "world or dimension changed",
        null);
  }

  Sim teleport(double s) {
    return emit(
        s,
        Kind.TELEPORT,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "teleport",
        Context.distance(20));
  }

  Sim chat(double s, String text) {
    Announcements.interpret(host, text, Announcements.Origin.SYSTEM)
        .ifPresent(m -> engine.announcement(host, "PlayerA", m, ns(s)));
    return this;
  }

  Sim title(double s, String text) {
    Announcements.interpret(host, text, Announcements.Origin.TITLE)
        .ifPresent(m -> engine.announcement(host, "PlayerA", m, ns(s)));
    return this;
  }

  Sim tick(double s) {
    engine.tick(ns(s));
    return this;
  }

  FightRecord finish(double s) {
    engine.finish(ns(s), FightRecord.Outcome.UNKNOWN, "test");
    return out.getLast();
  }

  FightRecord last() {
    return out.getLast();
  }

  static final String MM_START =
      "\nSword Duel\n ● Map: Park\n ● Opponent: PlayerB\n ● Ping: 40 ms\n";
  static final String MM_WIN =
      "\nMatch Results (Click to view)\n 🏆 Winner: PlayerA  ▏  ☠ Loser: PlayerB\n";
  static final String MM_LOSS =
      "\nMatch Results (Click to view)\n 🏆 Winner: PlayerB  ▏  ☠ Loser: PlayerA\n";
}
