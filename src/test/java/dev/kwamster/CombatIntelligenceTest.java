package dev.kwamster;

import static dev.kwamster.Sim.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.analytics.*;
import dev.kwamster.combat.events.CombatEvent.Attribution;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.metrics.Combos;
import dev.kwamster.combat.metrics.Combos.Side;
import dev.kwamster.combat.quality.AttackTiming;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Attribution, shields, combos, dominance, jump resets, reach, timing and aim analytics. */
class CombatIntelligenceTest {
  @Test
  void unattributedAndThirdPartyDamageNeverBreakCombos() {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.5);
    s.unattributed(1.7); // fall damage between hits
    s.incoming(1.8, C, Attribution.OTHER_ENTITY, true); // third party
    s.hit(2).hit(2.5);
    var f = s.finish(4);
    var st = f.statistics();
    assertEquals(0, st.incoming, "only attributed opponent hits count as incoming");
    assertEquals(1, st.unattributedIncoming);
    assertEquals(1, st.thirdPartyIncoming);
    assertEquals(4, st.longestCombo);
    var a = FightAnalytics.of(f);
    assertEquals(4, a.longestCombo());
    assertEquals("Unavailable", a.opponentComboAvailability(), "no attributed incoming at all");
  }

  @Test
  void fourHitComboEndedByRetaliationThenOpponentCombo() {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.6).hit(2.2).hit(2.8);
    s.incoming(3.3).incoming(3.9).incoming(4.5);
    s.hit(5.2);
    var a = FightAnalytics.of(s.finish(6));
    var local = a.combos.combos(Side.LOCAL);
    assertEquals(1, local.size());
    assertEquals(4, local.getFirst().length());
    assertEquals(Combos.End.RETALIATION, local.getFirst().endedBy());
    assertEquals(3, a.combos.longest(Side.OPPONENT));
    assertEquals(4, a.combos.comboHits(Side.LOCAL));
    assertEquals(4, a.combos.longestUnansweredLocal());
    var interpretation = Interpretation.of(a);
    assertTrue(
        interpretation.contains(
            "You landed four consecutive hits before an attributed return hit."),
        interpretation.toString());
  }

  @Test
  void sameTickTradeExtendsNeitherCombo() {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.5);
    s.attack(2.0).hurt(2.01, B);
    s.incoming(2.02); // same client tick as the local hit
    s.hit(2.5);
    var a = FightAnalytics.of(s.finish(4));
    assertEquals(2, a.combos.marks().stream().filter(Combos.Mark::traded).count());
    var streaks = a.combos.streaks();
    assertEquals(Combos.End.TRADE, streaks.getFirst().endedBy());
    assertEquals(2, streaks.getFirst().length());
    assertEquals(1, streaks.getLast().length(), "the hit after the trade starts fresh");
  }

  @Test
  void disengagementSegmentsExchangesWithoutRetaliation() {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.5);
    s.hit(6).hit(6.5).hit(7); // gap > 3 s combo timeout
    var a = FightAnalytics.of(s.finish(8));
    assertEquals(2, a.combos.exchanges().size());
    assertEquals(Combos.End.DISENGAGE, a.combos.streaks().getFirst().endedBy());
    assertEquals(3, a.longestCombo());
    assertEquals(5, a.combos.longestUnansweredLocal(), "unanswered streak ignores disengagement");
  }

  @Test
  void duplicateIncomingObservationsCountOnce() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    s.incoming(2).incoming(2.01); // same tick
    var f = s.finish(3);
    assertEquals(1, f.statistics().incoming);
    assertEquals(1, FightAnalytics.of(f).combos.longest(Side.OPPONENT));
  }

  @Test
  void dominanceFollowsConfirmedExchangesAndDecays() {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.5).hit(2).hit(2.5);
    s.incoming(3).incoming(3.5).incoming(4);
    var a = FightAnalytics.of(s.finish(20));
    var d = a.dominance; // analytics time is relative to the first event (sim time 1)
    assertTrue(d.at(1.6) > .5, "four unanswered hits favor you");
    assertTrue(d.at(3.1) < d.at(1.6) - .3, "three attributed hits move toward the opponent");
    assertEquals(Dominance.State.INSUFFICIENT, d.stateAt(18), "inactivity is not advantage");
    assertTrue(Double.isNaN(d.at(18)));
    assertTrue(d.localSeconds > 0 && d.opponentSeconds >= 0);
    assertTrue(d.largestSwing != null && d.largestSwing.size() > .5);
    for (double v : d.values) assertTrue(Double.isNaN(v) || (v > -1 && v < 1));
  }

  @Test
  void largestSwingMatchesBruteForce() {
    var s = new Sim("unconfigured.test");
    double t = 1;
    var rnd = new Random(7);
    for (int i = 0; i < 60; i++) {
      t += .2 + rnd.nextDouble() * (i % 17 == 0 ? 5 : .8);
      if (rnd.nextDouble() < .55) s.hit(t);
      else s.incoming(t);
    }
    var d = FightAnalytics.of(s.finish(t + 1)).dominance;
    int window = (int) Math.round(Dominance.SWING_WINDOW_SECONDS / d.step);
    double best = -1;
    for (int a = 0; a < d.values.length; a++) {
      if (!active(d.states[a])) continue;
      for (int b = a + 1; b < d.values.length && b <= a + window; b++) {
        if (!active(d.states[b])) break;
        best = Math.max(best, Math.abs(d.values[b] - d.values[a]));
      }
    }
    assertNotNull(d.largestSwing);
    assertEquals(best, d.largestSwing.size(), 1e-12);
  }

  private static boolean active(Dominance.State s) {
    return s == Dominance.State.LOCAL
        || s == Dominance.State.OPPONENT
        || s == Dominance.State.NEUTRAL;
  }

  @Test
  void dominanceIsNotConfidentWhenIncomingIsUnattributed() {
    var s = new Sim("unconfigured.test");
    for (int i = 0; i < 6; i++) {
      s.hit(1 + i * .6);
      s.unattributed(1.3 + i * .6);
    }
    var a = FightAnalytics.of(s.finish(6));
    assertEquals(Dominance.Availability.INCOMPLETE, a.dominance.availability);
    assertTrue(a.dominance.incompleteSeconds > 0);
    assertTrue(
        Interpretation.of(a)
            .contains(
                "Incoming-hit attribution was incomplete, so encounter dominance could not be"
                    + " established confidently."));
  }

  @Test
  void singleEventCannotProduceConfidentDominance() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    var a = FightAnalytics.of(s.finish(2));
    assertTrue(a.dominance.at(.05) <= .5 + 1e-9, "the +1 prior bounds a single hit at 0.5");
    assertEquals(Dominance.Availability.INSUFFICIENT, a.dominance.availability);
  }

  @Test
  void confirmedShieldBlockLeavesAccuracyDenominator() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    s.attack(2, B, 3, true, true).shield(2.05, "BLOCK", "opponent");
    s.attack(3, B, 3, true, true); // no sound, no damage: estimated
    s.attack(4); // plain uncertain
    s.tick(5);
    var f = s.finish(6);
    var st = f.statistics();
    assertEquals(4, st.attempts);
    assertEquals(1, st.shieldBlocks);
    assertEquals(1, st.estimatedShieldBlocks);
    assertEquals(2, st.attacksIntoShield);
    assertEquals(2, st.uncertain, "estimated block stays uncertain");
    assertEquals(3, st.accuracyDenominator());
    assertEquals(100.0 / 3, st.accuracy(), 1e-9);
    var a = FightAnalytics.of(f);
    assertEquals(1, a.shields.confirmedBlocks());
    assertEquals(1, a.shields.estimatedBlocks());
    assertEquals(
        "SHIELD_BLOCK",
        a.attemptOutcomes.values().stream()
            .filter(v -> v.startsWith("SHIELD"))
            .findFirst()
            .orElseThrow());
    assertEquals(1, a.hits.size(), "a blocked attack is not a hit");
    assertEquals(100.0 / 3, a.accuracy(), 1e-9);
  }

  @Test
  void axeDisableThenFollowUpHit() {
    var s = new Sim("unconfigured.test");
    s.attack(1, B, 3, true, true)
        .shield(1.03, "DISABLE", "opponent")
        .shield(1.04, "BLOCK", "opponent");
    s.hit(1.8);
    var f = s.finish(3);
    assertEquals(1, f.statistics().shieldDisables);
    assertEquals(1, f.statistics().shieldBlocks);
    var a = FightAnalytics.of(f);
    assertEquals(1, a.shields.disables());
    assertEquals(1, a.shields.hitsAfterDisable());
  }

  @Test
  void localDefensiveShieldEventsAreSeparate() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    s.shield(1.5, "BLOCK", "local").shield(2, "DISABLE", "local");
    var f = s.finish(3);
    assertEquals(1, f.statistics().localShieldBlocks);
    assertEquals(1, f.statistics().localShieldDisabled);
    assertEquals(0, f.statistics().shieldBlocks);
  }

  @Test
  void hitIntoRaisedShieldFromBehindCountsAsBypass() {
    var s = new Sim("unconfigured.test");
    s.attack(1, B, 3, true, false).hurt(1.01, B);
    var a = FightAnalytics.of(s.finish(2));
    assertEquals(1, a.shields.bypassed());
    assertEquals(0, a.shields.estimatedBlocks());
  }

  @Test
  void serverCritAnimationCorrelatesWithHit() {
    var s = new Sim("unconfigured.test");
    s.hit(1).crit(1.02, "opponent");
    s.hit(2);
    var f = s.finish(3);
    assertEquals(1, f.statistics().serverCrits);
    var a = FightAnalytics.of(f);
    assertEquals(Boolean.TRUE, a.hits.get(0).serverCrit());
    assertEquals(Boolean.FALSE, a.hits.get(1).serverCrit());
  }

  @Test
  void jumpResetClassificationPerIncomingHit() {
    var s = new Sim("unconfigured.test");
    s.hit(.5);
    s.incoming(1).jump(1.01); // same tick
    s.incoming(2).jump(2.12); // +2 ticks
    s.incoming(3).jump(3.4); // +8 ticks: outside
    s.incoming(4, B, Attribution.OPPONENT_DIRECT, false); // in water: excluded
    s.incoming(6); // no jump within 20 ticks
    s.unattributed(8).jump(8.0);
    var a = FightAnalytics.of(s.finish(9));
    var classes = a.incoming.stream().map(FightAnalytics.Incoming::jumpClass).toList();
    assertEquals(
        List.of(
            "IDEAL_CANDIDATE",
            "ATTEMPT_CANDIDATE",
            "OUTSIDE_WINDOW",
            "EXCLUDED",
            "NO_JUMP",
            "NOT_APPLICABLE"),
        classes);
    assertEquals(0L, a.incoming.get(0).jumpOffsetTicks());
    assertEquals(2L, a.incoming.get(1).jumpOffsetTicks());
    assertNull(a.jumpEffect, "no effectiveness estimate without enough knockback samples");
  }

  @Test
  void damageAndKnockbackObservedWithinTicks() {
    var s = new Sim("unconfigured.test");
    s.hit(.5).health(.6, 20);
    s.incoming(1).velocity(1.01, .3, .4).health(1.06, 16.5);
    var a = FightAnalytics.of(s.finish(2));
    var in = a.incoming.getFirst();
    assertEquals(3.5, in.damage(), 1e-9);
    assertEquals(.5, in.knockback(), 1e-9);
    assertEquals(3.5, s.last().statistics().damageTaken, 1e-9);
  }

  @Test
  void healthDropWithoutDamageEventMarksIncomplete() {
    var s = new Sim("unconfigured.test");
    s.hit(.5).health(.6, 20).health(1.5, 17);
    var a = FightAnalytics.of(s.finish(2));
    assertEquals(1, a.unexplainedHealthDrops);
  }

  @Test
  void experimentalJumpEffectNeedsThreeSamplesPerGroup() {
    var s = new Sim("unconfigured.test");
    s.hit(.2);
    for (int i = 0; i < 4; i++) {
      double t = 1 + i * 2;
      s.incoming(t).velocity(t + .01, .4, 0).jump(t + .01).motion(t + .15, .2, 0);
      double u = 1.9 + i * 2;
      s.incoming(u).velocity(u + .01, .4, 0).motion(u + .15, .35, 0);
    }
    var a = FightAnalytics.of(s.finish(10));
    assertNotNull(a.jumpEffect);
    assertEquals(4, a.jumpEffect.candidates());
    assertEquals(.2, a.jumpEffect.candidateSpeed(), 1e-9);
    assertEquals(.35, a.jumpEffect.otherSpeed(), 1e-9);
  }

  @Test
  void reachWindowAndPerWeaponSummary() {
    var s = new Sim("unconfigured.test");
    s.hit(1, 2.95).hit(1.6, 3.06).hit(2.2, 3.07).hit(2.8, 2.5);
    var a = FightAnalytics.of(s.finish(4));
    var r = a.reach;
    assertEquals(4, r.eligible());
    assertEquals(2, r.within(), "inclusive 3.00 ± 0.06 window");
    assertEquals(50, r.withinPercent(), 1e-9);
    assertEquals(3.07, r.max(), 1e-9);
    assertEquals((.05 + .06 + .07 + .5) / 4, r.meanAbsoluteError(), 1e-9);
    assertEquals(4, r.byWeapon().get("minecraft:diamond_sword").eligible());
    assertTrue(
        Interpretation.of(a)
            .contains(
                "Your reach was within the configured target window (3.00 ± 0.06 m) on 2 of 4"
                    + " eligible hits."));
  }

  @Test
  void timingBelowResolutionAndPerWeaponSamples() {
    var t = new AttackTiming();
    t.attack("fast", 0, 1, 1);
    assertEquals(AttackTiming.Grade.BELOW_RESOLUTION, t.attack("fast", 1, 1, 1).grade());
    var u = new AttackTiming();
    u.attack("sword", 0, 12.5, 1);
    var r = u.attack("sword", 13, 12.5, 1);
    assertEquals(AttackTiming.Grade.PERFECT, r.grade());
    var s = new Sim("unconfigured.test");
    s.emit(
        1,
        dev.kwamster.combat.events.CombatEvent.Kind.ATTACK,
        "local",
        B,
        dev.kwamster.combat.events.Confidence.OBSERVED,
        Sim.attack(
            3,
            "minecraft:diamond_axe",
            new AttackTiming.Result(AttackTiming.Grade.TOO_FAST, 15, 20)),
        "t",
        null);
    var a = FightAnalytics.of(s.finish(2));
    var sample = a.timingSamples.getFirst();
    assertEquals("minecraft:diamond_axe", sample.weapon());
    assertEquals(
        AttackTiming.Grade.OPENER,
        sample.grade(),
        "canonical timing is recomputed after deduplication");
    assertTrue(Double.isNaN(sample.deltaTicks()));
    assertEquals("UNRESOLVED", sample.outcome());
  }

  @Test
  void aimSamplesKeepRawCoordinatesAndFace() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    var a = FightAnalytics.of(s.finish(2));
    var sample = a.aim.samples().getFirst();
    assertEquals(.5, sample.x());
    assertEquals(.7, sample.y());
    assertEquals(0, sample.z());
    assertEquals("NORTH", sample.face());
    assertTrue(Double.isFinite(sample.distance()));
    assertEquals(1, a.aim.grades()[0] + a.aim.grades()[1] + a.aim.grades()[2] + a.aim.grades()[3]);
    assertEquals("2.5-3.0 m", FightAnalytics.reachBand(2.7));
  }

  @Test
  void legacyRecordingWithoutContextKeepsAvailabilityHonest() {
    var f = Fixtures.sample();
    var a = FightAnalytics.of(f);
    assertTrue(a.contextAvailable, "fixtures are recorded by the current engine");
    var old =
        new FightRecord(
            2,
            f.id(),
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
            f.opponentLabel());
    var b = FightAnalytics.of(old);
    assertFalse(b.contextAvailable);
    assertFalse(b.shields.available());
    assertNull(b.hits.getFirst().serverCrit(), "crit confirmation unavailable before 0.1.3");
  }
}
