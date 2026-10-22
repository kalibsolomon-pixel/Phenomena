package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.metrics.*;
import dev.kwamster.combat.quality.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import java.util.*;
import org.junit.jupiter.api.*;

class CombatTest {
  Config c;
  CombatEngine engine;
  List<FightRecord> records;
  long seq;
  final Attack attack = new Attack(1, 2.7, .5, .6, 0, true, true, true);

  @BeforeEach
  void setup() {
    c = new Config();
    records = new ArrayList<>();
    engine = new CombatEngine(c, records::add, (a, g) -> {});
  }

  CombatEvent event(
      long ms,
      Kind k,
      String actor,
      String target,
      Confidence confidence,
      Attack a,
      String detail) {
    return new CombatEvent(++seq, ms * 1_000_000, k, actor, target, confidence, a, detail);
  }

  void attack(long ms) {
    engine.accept(event(ms, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, attack, ""));
  }

  void hit(long ms) {
    engine.accept(
        event(
            ms, Kind.HURT, "local", "opponent", Confidence.CONFIRMED, null, "local-melee-source"));
  }

  @Test
  void emptySwingsOutsideFightIgnored() {
    engine.accept(event(1, Kind.ATTACK, "local", null, Confidence.OBSERVED, null, ""));
    assertFalse(engine.engaged());
    assertEquals(0, engine.stats().attempts);
  }

  @Test
  void oneAttackCannotCountTwice() {
    attack(1);
    hit(100);
    hit(120);
    assertEquals(1, engine.stats().hits);
    assertEquals(0, engine.stats().uncertain);
    assertEquals(1, engine.stats().criticalConditions);
  }

  @Test
  void sequenceDuplicatesIgnored() {
    var e = event(1, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, attack, "");
    engine.accept(e);
    engine.accept(e);
    assertEquals(1, engine.stats().attempts);
  }

  @Test
  void animationAndForeignDamageNeverConfirm() {
    attack(1);
    engine.accept(
        event(10, Kind.HURT, "other", "opponent", Confidence.OBSERVED, null, "hurt animation"));
    assertEquals(0, engine.stats().hits);
    assertEquals(1, engine.stats().uncertain);
  }

  @Test
  void expiredAttackRemainsUncertain() {
    attack(1);
    hit(600);
    assertEquals(0, engine.stats().hits);
    assertEquals(1, engine.stats().uncertain);
  }

  @Test
  void unrelatedOpponentIgnored() {
    attack(1);
    engine.accept(
        event(20, Kind.HURT, "local", "other", Confidence.CONFIRMED, null, "local-melee-source"));
    assertEquals(0, engine.stats().hits);
  }

  @Test
  void combosAndRetaliation() {
    attack(1);
    hit(50);
    attack(700);
    hit(750);
    assertEquals(2, engine.stats().longestCombo);
    engine.accept(event(800, Kind.DAMAGE_IN, "opponent", "local", Confidence.CONFIRMED, null, ""));
    assertEquals(0, engine.stats().currentCombo);
    assertEquals(2, engine.stats().longestCombo);
    assertEquals(.699, engine.stats().averageInterval(), 1e-9);
  }

  @Test
  void inactivityTransitionsAndFinalizesOnce() {
    attack(1);
    engine.tick(7_000_000_000L);
    assertEquals(CombatEngine.State.ENDING, engine.state());
    engine.tick(9_000_000_000L);
    engine.tick(10_000_000_000L);
    assertEquals(1, records.size());
    assertEquals(FightRecord.Outcome.UNKNOWN, records.getFirst().outcome());
  }

  @Test
  void opponentDeathIsNotWin() {
    attack(1);
    hit(20);
    engine.accept(event(30, Kind.DEATH, null, "opponent", Confidence.OBSERVED, null, ""));
    assertEquals(FightRecord.Outcome.UNKNOWN, records.getFirst().outcome());
  }

  @Test
  void jumpRequiresNearbyDamage() {
    attack(1);
    engine.accept(event(10, Kind.JUMP, "local", null, Confidence.OBSERVED, null, ""));
    assertEquals(0, engine.stats().jumpCandidates);
    engine.accept(event(20, Kind.DAMAGE_IN, "opponent", "local", Confidence.CONFIRMED, null, ""));
    engine.accept(event(100, Kind.JUMP, "local", null, Confidence.OBSERVED, null, ""));
    assertEquals(1, engine.stats().jumpCandidates);
  }

  @Test
  void interruptedOnTargetSwitch() {
    attack(1);
    engine.accept(event(10, Kind.ATTACK, "local", "other", Confidence.OBSERVED, attack, ""));
    assertEquals(FightRecord.Outcome.INTERRUPTED, records.getFirst().outcome());
    assertEquals(1, engine.stats().attempts);
  }

  @Test
  void qualityTransparent() {
    assertEquals(HitQuality.Grade.PERFECT, HitQuality.classify(attack, c));
    assertEquals(
        HitQuality.Grade.POOR,
        HitQuality.classify(new Attack(.1, 3, 0, 0, 0, false, false, true), c));
  }

  @Test
  void snapshotNotChangedByNextFight() {
    attack(1);
    hit(20);
    engine.finish(30_000_000, FightRecord.Outcome.UNKNOWN, "test");
    attack(100);
    assertEquals(1, records.getFirst().statistics().hits);
    assertEquals(0, engine.stats().hits);
  }

  @Test
  void backwardsTimeRejected() {
    attack(100);
    engine.accept(event(50, Kind.ATTACK, "local", "opponent", Confidence.OBSERVED, attack, ""));
    assertEquals(1, engine.stats().attempts);
  }

  @Test
  void zeroDenominatorsSafe() {
    assertEquals(0, engine.stats().accuracy());
    assertEquals(0, engine.stats().averageInterval());
    assertEquals(0, engine.stats().intervalDeviation());
  }

  @Test
  void plausibleEmptyDoesNotBecomeUncertainOrConfirm() {
    attack(1);
    engine.accept(
        event(
            10,
            Kind.ATTACK,
            "local",
            "opponent",
            Confidence.OBSERVED,
            attack,
            "empty-swing; plausible opponent"));
    hit(30);
    hit(40);
    assertEquals(2, engine.stats().attempts);
    assertEquals(1, engine.stats().emptyMisses);
    assertEquals(1, engine.stats().hits);
    assertEquals(0, engine.stats().uncertain);
  }

  @Test
  void perFightPolicyFrozen() {
    attack(1);
    c.correlationMillis = 50;
    hit(200);
    assertEquals(1, engine.stats().hits);
    c.comboSeconds = .5;
    attack(700);
    hit(750);
    assertEquals(2, engine.stats().longestCombo);
  }

  @Test
  void momentumDecays() {
    attack(1);
    hit(10);
    assertTrue(engine.stats().momentumAt(3_000_000_000L) < engine.stats().momentum);
  }

  @Test
  void boundedStreamInterrupts() {
    attack(1);
    for (int i = 0; i < 10000; i++)
      engine.accept(event(i + 2, Kind.MOTION, "local", null, Confidence.OBSERVED, null, "trace"));
    assertFalse(engine.engaged());
    assertEquals(10000, records.getFirst().events().size());
    assertEquals(FightRecord.Outcome.INTERRUPTED, records.getFirst().outcome());
  }

  @Test
  void delayedConfirmationDoesNotBackdateMomentum() {
    attack(1);
    engine.accept(event(50, Kind.DAMAGE_IN, "opponent", "local", Confidence.CONFIRMED, null, ""));
    hit(100);
    assertEquals(100_000_000L, engine.stats().momentumAt);
    assertEquals(-.25 * Math.exp(-.05 / 3) + .2, engine.stats().momentum, 1e-9);
  }
}
