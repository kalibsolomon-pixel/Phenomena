package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.metrics.Geometry.*;
import dev.kwamster.combat.quality.*;
import dev.kwamster.combat.tracking.CombatEngine;
import dev.kwamster.config.Config;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class MeasurementCorrectionsTest {
  @Test
  void zeroServerAttackSpeedSerializesAsUnavailable() {
    var timing = new AttackTiming().attack("item", 20, Double.POSITIVE_INFINITY, 1);
    var m =
        new Measurement(
            "item", 0, Double.POSITIVE_INFINITY, timing, null, null, null, null, "MELEE_ATTEMPT");
    assertEquals(0, m.cooldownTicks());
    assertEquals(AttackTiming.Grade.UNAVAILABLE, m.timing().grade());
    assertDoesNotThrow(() -> dev.kwamster.recording.storage.AtomicJson.GSON.toJson(m));
  }

  @ParameterizedTest
  @CsvSource({
    "sword,1.6,12.5",
    "axe,1,20",
    "trident,1.1,18.1818181818",
    "fist,4,5",
    "ordinary_item,4,5",
    "mace,.6,33.3333333333",
    "modified_sword,2.4,8.3333333333"
  })
  void cooldownComesFromEffectiveAttribute(String weapon, double speed, double ticks) {
    assertEquals(ticks, AttackTiming.cooldownTicks(speed), 1e-8);
  }

  @Test
  void invalidSpeedIsUnavailable() {
    assertTrue(Double.isNaN(AttackTiming.cooldownTicks(0)));
    assertTrue(Double.isNaN(AttackTiming.cooldownTicks(Double.NaN)));
  }

  @Test
  void openerPerfectFastSlowAndExpiry() {
    var t = new AttackTiming();
    assertEquals(AttackTiming.Grade.OPENER, t.attack("sword", 0, 12.5, 1).grade());
    assertEquals(AttackTiming.Grade.PERFECT, t.attack("sword", 12, 12.5, 1).grade());
    assertEquals(AttackTiming.Grade.TOO_FAST, t.attack("sword", 16, 12.5, 1).grade());
    assertEquals(AttackTiming.Grade.TOO_SLOW, t.attack("sword", 40, 12.5, 1).grade());
    assertEquals(AttackTiming.Grade.OPENER, t.attack("sword", 66, 12.5, 1).grade());
  }

  @Test
  void previousWeaponCooldownDefinesIntervalDespiteAttributeChange() {
    var t = new AttackTiming();
    t.attack("sword", 0, 12.5, 1);
    var result = t.attack("sword", 13, 8, 1);
    assertEquals(12.5, result.expectedTicks());
    assertEquals(AttackTiming.Grade.PERFECT, result.grade());
    assertEquals(AttackTiming.Grade.PERFECT, t.attack("sword", 21, 8, 1).grade());
  }

  @Test
  void switchingAwayAndBackResetsEvenWithoutAnAttack() {
    var t = new AttackTiming();
    t.attack("sword", 0, 12.5, 1);
    t.held("axe");
    t.held("sword");
    assertEquals(AttackTiming.Grade.OPENER, t.attack("sword", 12, 12.5, 1).grade());
    assertEquals(AttackTiming.Grade.OPENER, t.attack("mace", 13, 33, 1).grade());
  }

  @Test
  void twoCooldownBoundaryIsInclusive() {
    var t = new AttackTiming();
    t.attack("fist", 0, 5, 0);
    assertEquals(AttackTiming.Grade.TOO_SLOW, t.attack("fist", 10, 5, 0).grade());
    assertEquals(AttackTiming.Grade.OPENER, t.attack("fist", 21, 5, 0).grade());
  }

  static CombatEvent event(long seq, long tick, Kind kind) {
    return new CombatEvent(
        seq,
        seq * 1_000_000,
        kind,
        kind == Kind.DAMAGE_IN ? "target" : "local",
        null,
        Confidence.OBSERVED,
        null,
        "actual jump",
        tick);
  }

  @ParameterizedTest
  @CsvSource({
    "-5,OUTSIDE_WINDOW",
    "-4,ATTEMPT_CANDIDATE",
    "-1,ATTEMPT_CANDIDATE",
    "0,IDEAL_CANDIDATE",
    "1,ATTEMPT_CANDIDATE",
    "4,ATTEMPT_CANDIDATE",
    "5,OUTSIDE_WINDOW"
  })
  void signedJumpTickBoundaries(long delta, JumpResets.Grade grade) {
    var sample =
        JumpResets.analyze(
                List.of(event(1, 20, Kind.DAMAGE_IN), event(2, 20 + delta, Kind.JUMP)), 4)
            .getFirst();
    assertEquals(delta, sample.offsetTicks());
    assertEquals(grade, sample.grade());
  }

  @Test
  void sameTickBothEventOrdersAndDuplicateJump() {
    var j = event(1, 20, Kind.JUMP);
    var d = event(2, 20, Kind.DAMAGE_IN);
    assertEquals(
        JumpResets.Grade.IDEAL_CANDIDATE,
        JumpResets.analyze(List.of(j, d, j), 4).getFirst().grade());
    assertEquals(1, JumpResets.analyze(List.of(j, d, j), 4).size());
    assertEquals(
        JumpResets.Grade.IDEAL_CANDIDATE, JumpResets.analyze(List.of(d, j), 4).getFirst().grade());
  }

  @Test
  void preventedJumpInputIsNotActualJump() {
    assertTrue(
        JumpResets.analyze(List.of(event(1, 10, Kind.DAMAGE_IN), event(2, 10, Kind.JUMP_INPUT)), 4)
            .isEmpty());
  }

  @Test
  void historicalJumpWithoutTicksIsUnavailable() {
    var old = new CombatEvent(1, 1, Kind.JUMP, "local", null, Confidence.OBSERVED, null, "");
    assertEquals(
        JumpResets.Grade.UNAVAILABLE,
        JumpResets.analyze(List.of(old, event(2, 20, Kind.DAMAGE_IN)), 4).getFirst().grade());
  }

  @Test
  void tickDistanceWinsOverWallClockDistance() {
    var jump =
        new CombatEvent(
            3,
            300_000_000,
            Kind.JUMP,
            "local",
            null,
            Confidence.OBSERVED,
            null,
            "actual jump",
            20L);
    var closeTick =
        new CombatEvent(
            1, 1, Kind.DAMAGE_IN, "target", "local", Confidence.CONFIRMED, null, "", 20L);
    var closeTime =
        new CombatEvent(
            2, 299_000_000, Kind.DAMAGE_IN, "target", "local", Confidence.CONFIRMED, null, "", 18L);
    assertEquals(
        1L, JumpResets.analyze(List.of(closeTick, closeTime, jump), 4).getFirst().damageSequence());
    var stalled =
        new CombatEvent(
            4,
            3_000_000_000L,
            Kind.JUMP,
            "local",
            null,
            Confidence.OBSERVED,
            null,
            "actual jump",
            20L);
    assertEquals(
        JumpResets.Grade.IDEAL_CANDIDATE,
        JumpResets.analyze(List.of(closeTick, stalled), 4).getFirst().grade());
  }

  @Test
  void duplicateDamageObservationCannotConsumeTwoSameTickAttempts() {
    var e = new CombatEngine(new Config(), f -> {}, (a, g) -> {});
    var a = new Attack(1, 3, .5, .5, 0, false, false, true);
    e.accept(new CombatEvent(1, 1, Kind.ATTACK, "local", "target", Confidence.OBSERVED, a, "", 1L));
    e.accept(new CombatEvent(2, 2, Kind.ATTACK, "local", "target", Confidence.OBSERVED, a, "", 1L));
    e.accept(
        new CombatEvent(
            3,
            3,
            Kind.HURT,
            "local",
            "target",
            Confidence.CONFIRMED,
            null,
            "local-melee-source",
            1L));
    e.accept(
        new CombatEvent(
            4,
            4,
            Kind.HURT,
            "local",
            "target",
            Confidence.CONFIRMED,
            null,
            "local-melee-source",
            1L));
    assertEquals(1, e.stats().hits);
    assertEquals(0, e.stats().uncertain);
    assertEquals(1, e.stats().uniqueAttacks);
    assertEquals(1, e.stats().deduplicatedAttacks);
  }

  @Test
  void preCombatEarlyJumpCorrelatesToIncoming() {
    var e = new CombatEngine(new Config(), f -> {}, (a, g) -> {});
    e.accept(
        new CombatEvent(
            1, 1, Kind.JUMP, "local", null, Confidence.OBSERVED, null, "actual jump", 10L));
    e.accept(
        new CombatEvent(
            2, 50_000_000, Kind.DAMAGE_IN, "target", "local", Confidence.CONFIRMED, null, "", 11L));
    assertEquals(1, e.stats().jumpCandidates);
  }

  @ParameterizedTest
  @CsvSource({"2.94,true", "3.0,true", "3.06,true", "2.939,false", "3.061,false"})
  void reachWindowIndependentOfCooldown(double distance, boolean eligible) {
    assertEquals(
        eligible,
        PerfectReach.eligible(new Attack(.05, distance, .5, .5, 0, false, false, true), 3, .06));
  }

  @Test
  void nearestBoxFallbackAndInvalidReachCannotReward() {
    assertFalse(PerfectReach.eligible(new Attack(1, 3, 0, 0, 0, false, false, false), 3, .06));
    assertFalse(
        PerfectReach.eligible(new Attack(1, Double.NaN, 0, 0, 0, false, false, true), 3, .06));
  }

  @Test
  void onlyUniqueConfirmedMeleeInvokesReachFeedback() {
    int[] sounds = {0};
    var e =
        new CombatEngine(
            new Config(),
            f -> {},
            (a, g) -> {
              if (PerfectReach.eligible(a.attack(), 3, .06)) sounds[0]++;
            });
    var a = new Attack(.1, 3, .5, .5, 0, false, false, true);
    e.accept(new CombatEvent(1, 1, Kind.ATTACK, "local", "target", Confidence.OBSERVED, a, ""));
    assertEquals(0, sounds[0]);
    e.accept(
        new CombatEvent(
            2, 2, Kind.HURT, "other", "target", Confidence.CONFIRMED, null, "local-melee-source"));
    assertEquals(0, sounds[0]);
    e.accept(
        new CombatEvent(
            3, 3, Kind.HURT, "local", "target", Confidence.CONFIRMED, null, "local-melee-source"));
    e.accept(
        new CombatEvent(
            4, 4, Kind.HURT, "local", "target", Confidence.CONFIRMED, null, "local-melee-source"));
    assertEquals(1, sounds[0]);
    assertEquals(1, e.stats().perfectReachHits);
  }

  final Box box = new Box(0, 0, 0, .6, 1.8, .6);

  @Test
  void idealRegionFollowsElevationRatherThanOpponentEyes() {
    var level = MeleeAim.region(new Vec(.3, 1.6, -2), box, 3);
    var low = MeleeAim.region(new Vec(.3, .3, -2), box, 3);
    assertTrue(level.ideal().y() > low.ideal().y());
    assertEquals(0, level.ideal().z());
    var above = MeleeAim.region(new Vec(.3, 3, .3), box, 3);
    assertEquals(1, above.ideal().y());
    assertEquals(MeleeAim.Grade.IDEAL, MeleeAim.classify(above.ideal(), above, box, true));
  }

  @Test
  void unreachableOrOccludedRayIsPoorAndCoordinatesStayRaw() {
    var region = MeleeAim.region(new Vec(.3, 1, -4), box, 3);
    assertFalse(region.reachable());
    assertEquals(MeleeAim.Grade.POOR, MeleeAim.classify(new Vec(.5, .5, 0), region, box, true));
    region = MeleeAim.region(new Vec(.3, 1, -2), box, 3);
    assertEquals(MeleeAim.Grade.POOR, MeleeAim.classify(region.ideal(), region, box, false));
    assertEquals(new Vec(.5, .5, 0), box.normalize(new Vec(.3, .9, 0)));
  }

  @Test
  void poseAndSizeChangeRegionGeometry() {
    var standing = MeleeAim.region(new Vec(.3, 1.6, -2), box, 3);
    var crawling = MeleeAim.region(new Vec(.3, 1.6, -2), new Box(0, 0, 0, .6, .6, .6), 3);
    assertNotEquals(standing.ideal().y(), crawling.ideal().y());
    assertEquals(MeleeAim.Grade.IDEAL, MeleeAim.classify(standing.ideal(), standing, box, true));
    var good = new Vec(.5, standing.ideal().y() - .1, 0);
    assertEquals(MeleeAim.Grade.GOOD, MeleeAim.classify(good, standing, box, true));
  }
}
