package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.analytics.*;
import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.metrics.Statistics;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.Config;
import dev.phenomena.recording.storage.*;
import dev.phenomena.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExchangeAccuracyTest {
  @TempDir Path dir;

  @Test
  void manualScopeLimitCannotSplitTwoTickGroupOrDropBoundaryInput() {
    var out = new ArrayList<FightRecord>();
    var engine = new CombatEngine(new Config(), out::add, (e, g) -> {});
    engine.manualExchanges = true;
    var attack = Sim.attack(3, "sword", null);
    for (long i = 0; i < 10000; i++)
      engine.accept(input(i + 1, 100 + i * 3, "local", "B", attack, ""));
    engine.accept(input(10001, 30098, "local", "B", attack, ""));
    assertTrue(out.isEmpty(), "the boundary duplicate belongs to the previous canonical attack");
    assertEquals(10000, engine.stats().uniqueAttacks);
    engine.accept(input(10002, 30110, "local", "B", attack, ""));
    assertEquals(1, out.size());
    assertEquals(10001, out.getFirst().statistics().totalInputs);
    assertEquals(10000, out.getFirst().statistics().uniqueAttacks);
    assertEquals(1, engine.stats().totalInputs, "the boundary input starts the next nested scope");
    assertEquals(1, engine.stats().uniqueAttacks);
  }

  @Test
  void historySummariesKeepCountsWithoutRetainingAttackMaps() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    var full = s.finish(2);
    var summary = full.summaryOnly();
    assertEquals(full.statistics().hits, summary.statistics().hits);
    assertEquals(full.statistics().uniqueAttacks, summary.statistics().uniqueAttacks);
    assertTrue(summary.statistics().attackCanonicalIds.isEmpty());
    assertTrue(summary.statistics().combatExchanges.isEmpty());
    assertFalse(full.statistics().attackCanonicalIds.isEmpty());
    assertFalse(full.statistics().combatExchanges.isEmpty());
  }

  @Test
  void defensiveSoundsOutsideExchangesCannotCreateDominance() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START).hit(1).tick(5);
    s.shield(5.1, "BLOCK", "local").shield(5.2, "BLOCK", "local").shield(5.3, "BLOCK", "local");
    var a = FightAnalytics.of(s.finish(6));
    assertTrue(Double.isNaN(a.dominance.at(5.5)));
    assertEquals(1, a.activeExchanges.size());
  }

  @Test
  void damageAfterAmbiguousShieldSoundUpgradesCanonicalHit() {
    var s = new Sim("unconfigured.test");
    s.attack(1, Sim.B, 3, true, true).shield(1.05, "DISABLE", "opponent").hurt(1.1, Sim.B);
    var f = s.finish(2);
    assertEquals(1, f.statistics().hits);
    assertEquals(0, f.statistics().shieldBlocks);
    assertEquals(0, f.statistics().shieldDisables);
    assertEquals(1, f.evaluations().size());
    assertEquals(1, FightAnalytics.of(f).hits.size());
  }

  @Test
  void unrelatedTargetInputIsPreservedButDoesNotContaminateMatch() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START).attack(1, Sim.C, 3, false, false);
    var f = s.finish(2);
    assertEquals(1, f.statistics().totalInputs);
    assertEquals(1, f.statistics().excludedSwings);
    assertEquals(0, f.statistics().attempts);
    assertTrue(FightAnalytics.of(f).timingSamples.isEmpty());
    assertTrue(FightAnalytics.of(f).activeExchanges.isEmpty());
  }

  static CombatEvent input(
      long id, long tick, String actor, String target, Attack a, String detail) {
    return new CombatEvent(
        id, tick * 50_000_000L, Kind.ATTACK, actor, target, Confidence.OBSERVED, a, detail, tick);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void inclusiveWindowKeepsRawInputsAndOneCanonical(int gap) {
    var s = new Statistics();
    var tracker = new AttackObservations(s);
    var first = input(1, 100, "local", "B", Sim.attack(3, "sword", null), "");
    tracker.observe(first);
    var duplicate = tracker.observe(input(2, 100 + gap, "local", "B", first.attack(), ""));
    assertTrue(duplicate.duplicate());
    assertEquals(1, duplicate.event().sequence());
    assertEquals(2, s.totalInputs);
    assertEquals(1, s.uniqueAttacks);
    assertEquals(1, s.attempts);
    assertEquals(1, s.deduplicatedAttacks);
    assertEquals(1L, s.attackCanonicalIds.get(2L));
  }

  @Test
  void threeTickSeparationAndAnchoredChain() {
    var stats = new Statistics();
    var t = new AttackObservations(stats);
    var a = Sim.attack(3, "sword", null);
    t.observe(input(1, 100, "local", "B", a, ""));
    t.observe(input(2, 102, "local", "B", a, ""));
    assertFalse(t.observe(input(3, 104, "local", "B", a, "")).duplicate());
    assertFalse(t.observe(input(4, 107, "local", "B", a, "")).duplicate());
    assertEquals(3, stats.uniqueAttacks);
    assertEquals(4, stats.totalInputs);
  }

  @Test
  void tickIndicesTakePrecedenceOverWallTime() {
    var s = new Statistics();
    var t = new AttackObservations(s);
    var a = Sim.attack(3, "sword", null);
    t.observe(input(1, 100, "local", "B", a, ""));
    var delayed =
        new CombatEvent(
            2, 90_000_000_000L, Kind.ATTACK, "local", "B", Confidence.OBSERVED, a, "", 102L);
    assertTrue(t.observe(delayed).duplicate());
  }

  @Test
  void missingTicksPreserveUncertaintyWithoutMillisSubstitute() {
    var s = new Statistics();
    var t = new AttackObservations(s);
    var a = Sim.attack(3, "sword", null);
    t.observe(new CombatEvent(1, 1, Kind.ATTACK, "local", "B", Confidence.OBSERVED, a, ""));
    assertFalse(
        t.observe(new CombatEvent(2, 2, Kind.ATTACK, "local", "B", Confidence.OBSERVED, a, ""))
            .duplicate());
  }

  @Test
  void differentTargetsWeaponsAndSourcesKeepRawIdentity() {
    var s = new Statistics();
    var t = new AttackObservations(s);
    var a = input(1, 100, "local", "B", Sim.attack(3, "sword", null), "");
    var b = input(2, 101, "local", "C", Sim.attack(3, "axe", null), "");
    t.observe(a);
    t.observe(b);
    assertEquals(1, s.uniqueAttacks);
    assertEquals(2, s.totalInputs);
    assertTrue(t.targets(1, "B"));
    assertTrue(t.targets(1, "C"));
    assertFalse(AttackObservations.outgoing(input(3, 101, "other", "local", a.attack(), "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> t.observe(input(3, 101, "other", "local", a.attack(), "")));
    assertEquals(1, AttackObservations.canonicalEvents(List.of(a, b), s).size());
  }

  @Test
  void duplicatesCannotInflateHitsCombosFeedbackOrReach() {
    var c = new Config();
    var out = new ArrayList<FightRecord>();
    int[] sounds = {0};
    var e = new CombatEngine(c, out::add, (a, g) -> sounds[0]++);
    var a = Sim.attack(3, "sword", null);
    e.accept(input(1, 100, "local", "B", a, ""));
    e.accept(input(2, 101, "local", "B", a, ""));
    e.accept(
        new CombatEvent(
            3,
            5_100_000_000L,
            Kind.HURT,
            "local",
            "B",
            Confidence.CONFIRMED,
            null,
            "local-melee-source",
            102L));
    e.accept(
        new CombatEvent(
            4,
            5_110_000_000L,
            Kind.HURT,
            "local",
            "B",
            Confidence.CONFIRMED,
            null,
            "local-melee-source",
            102L));
    e.finish(6_000_000_000L, FightRecord.Outcome.UNKNOWN, "test");
    var f = out.getFirst();
    var an = FightAnalytics.of(f);
    assertEquals(1, f.statistics().hits);
    assertEquals(1, sounds[0]);
    assertEquals(1, f.statistics().perfectReachHits);
    assertEquals(1, an.hits.size());
    assertEquals(1, an.timingSamples.size());
    assertEquals(1, an.reach.eligible());
    assertEquals(1, an.longestCombo());
    assertEquals(2, f.events().stream().filter(AttackObservations::outgoing).count());
  }

  @Test
  void delayedHitUpgradesEmptyCanonicalBeforeExpiry() {
    var s = new Sim("unconfigured.test");
    s.config.correlationMillis = 500;
    s.emit(
        1,
        Kind.ATTACK,
        "local",
        Sim.B,
        Confidence.OBSERVED,
        Sim.attack(3, "sword", null),
        "empty-swing; aimed",
        null);
    s.attack(1.1).hurt(1.4, Sim.B);
    var f = s.finish(2);
    assertEquals(1, f.statistics().uniqueAttacks);
    assertEquals(1, f.statistics().hits);
    assertEquals(0, f.statistics().eligibleMisses);
    assertEquals(0, f.statistics().uncertain);
  }

  @Test
  void duplicateTargetEvidenceCanUpgradeAnExcludedFirstInput() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.emit(1, Kind.ATTACK, "local", null, Confidence.OBSERVED, null, "empty-swing", null);
    s.attack(1.05).hurt(1.1, Sim.B);
    var f = s.finish(2);
    assertEquals(2, f.statistics().totalInputs);
    assertEquals(1, f.statistics().uniqueAttacks);
    assertEquals(0, f.statistics().excludedSwings);
    assertEquals(1, FightAnalytics.of(f).hits.size());
  }

  @Test
  void openingMissStartsExchangeWithoutRequiringAHit() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.emit(
        1,
        Kind.ATTACK,
        "local",
        Sim.B,
        Confidence.OBSERVED,
        Sim.attack(3, "sword", null),
        "empty-swing; aimed",
        null);
    assertEquals(0, s.engine.stats().eligibleMisses);
    s.tick(1.3);
    assertEquals(1, s.engine.stats().eligibleMisses);
    assertTrue(s.engine.combatActive());
    var f = s.finish(2);
    assertEquals(0, f.statistics().hits);
    assertEquals(1, f.statistics().attempts);
    assertEquals(0, f.statistics().accuracy());
    assertEquals(1, FightAnalytics.of(f).activeExchanges.size());
  }

  @Test
  void unrelatedSwingsDoNotExtendExchangeOrBecomeMisses() {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START).hit(1);
    for (int i = 0; i < 15; i++)
      s.emit(
          1.5 + i * .2, Kind.ATTACK, "local", null, Confidence.OBSERVED, null, "empty-swing", null);
    assertFalse(s.engine.combatActive());
    assertTrue(s.engine.engaged());
    var f = s.finish(5);
    assertEquals(15, f.statistics().excludedSwings);
    assertEquals(0, f.statistics().eligibleMisses);
    assertEquals(1, f.statistics().attempts);
    assertEquals(100, f.statistics().accuracy());
  }

  @Test
  void unresolvedAndUncertainShieldDoNotBecomeMisses() {
    var s = new Sim("unconfigured.test");
    s.attack(1).attack(2, Sim.B, 3, true, true).tick(3);
    var f = s.finish(4);
    assertEquals(0, f.statistics().eligibleMisses);
    assertEquals(2, f.statistics().uncertain);
    assertEquals(1, f.statistics().uncertainShield);
    assertEquals(2, f.statistics().accuracyDenominator());
    assertEquals("UNRESOLVED", FightAnalytics.of(f).attemptOutcomes.get(1L));
  }

  @Test
  void correlatedShieldAndSimultaneousIncomingStaySeparate() {
    var s = new Sim("unconfigured.test");
    s.attack(1, Sim.B, 3, true, true).attack(1.05);
    s.shield(1.1, "BLOCK", "opponent").incoming(1.11);
    var f = s.finish(2);
    assertEquals(1, f.statistics().shieldBlocks);
    assertEquals(1, f.statistics().incoming);
    assertEquals(0, f.statistics().accuracyDenominator());
    assertEquals(0, f.statistics().hits);
  }

  @Test
  void independentTimeoutsAndEnvironmentalDamage() {
    var s = new Sim("unconfigured.test");
    s.config.inactivitySeconds = 12;
    s.hit(1).unattributed(3).tick(4.02);
    assertFalse(s.engine.combatActive());
    assertTrue(s.engine.engaged());
    s.tick(12);
    assertTrue(s.engine.engaged());
    s.tick(13.02);
    assertFalse(s.engine.engaged());
    assertEquals(1, s.out.size());
  }

  @Test
  void configurableExchangeTimeoutAndExactBoundary() {
    var s = new Sim("minemen.club");
    s.config.exchangeInactivitySeconds = 5;
    s.chat(0, Sim.MM_START).hit(1).tick(5);
    assertTrue(s.engine.combatActive());
    s.config.exchangeInactivitySeconds = 2;
    s.tick(5.1);
    assertFalse(s.engine.combatActive());
    assertEquals(1, s.engine.stats().combatExchanges.size());
    s.hit(6);
    var f = s.finish(7);
    assertEquals(2, FightAnalytics.of(f).activeExchanges.size());
    var exact = new Sim("minemen.club");
    exact.chat(0, Sim.MM_START).hit(1).hit(4.01);
    var intervals = FightAnalytics.of(exact.finish(5)).activeExchanges;
    assertEquals(2, intervals.size(), "the exact three-second deadline starts a new exchange");
    assertEquals(1, intervals.get(0).hits());
    assertEquals(1, intervals.get(1).hits(), "the boundary attack belongs to the new interval");
  }

  @Test
  void twoExchangesOneMatchBreakCombosAndDominance() {
    var s = new Sim("minemen.club");
    s.config.comboSeconds = 10;
    s.chat(0, Sim.MM_START).hit(1).hit(1.5).tick(5);
    assertTrue(s.engine.engaged());
    assertFalse(s.engine.combatActive());
    s.hit(6).hit(6.5).chat(8, Sim.MM_WIN);
    assertEquals(1, s.out.size());
    var f = s.last();
    var a = FightAnalytics.of(f);
    assertEquals(2, a.activeExchanges.size());
    assertEquals(2, a.longestCombo());
    assertTrue(Double.isNaN(a.dominance.at(5)));
    assertEquals(4, a.activeExchanges.stream().mapToInt(FightAnalytics.ActiveExchange::hits).sum());
    assertEquals(f.statistics().accuracy(), a.accuracy());
    assertEquals(
        f.statistics().averageInterval(),
        a.intervals.stream().mapToDouble(Double::doubleValue).average().orElse(0),
        1e-9);
    assertEquals(
        2,
        a.timingSamples.stream()
            .filter(t -> t.grade() == dev.phenomena.combat.quality.AttackTiming.Grade.OPENER)
            .count());
  }

  @Test
  void historicalCountsAndNewMetadataRoundTrip() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    var f = s.finish(2);
    var decoded = FightStore.decode(AtomicJson.GSON.toJson(f));
    assertEquals(f.statistics().attackCanonicalIds, decoded.statistics().attackCanonicalIds);
    assertEquals(f.statistics().combatExchanges, decoded.statistics().combatExchanges);
    var old =
        FightStore.decode(
            AtomicJson.GSON.toJson(f).replace("\"schemaVersion\": 5", "\"schemaVersion\": 4"));
    assertEquals(f.outcome(), old.outcome());
    var index =
        ReportExporter.export(dir.resolve("report"), decoded, ReportExporter.Options.DEFAULT);
    String html = Files.readString(index);
    assertTrue(html.contains("combatExchanges"));
    assertTrue(html.contains("Total swings"));
    assertFalse(html.contains("unconfigured.test"));
    assertTrue(html.contains("attackCanonicalIds"));
  }
}
