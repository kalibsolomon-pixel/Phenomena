package dev.phenomena;

import static dev.phenomena.Fixtures.attack;
import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.analytics.*;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.metrics.Statistics;
import dev.phenomena.combat.quality.HitQuality.Grade;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalyticsTest {
  @Test
  void derivedHitsMatchEngineStatistics() {
    var fight = Fixtures.sample();
    var s = fight.statistics();
    var a = FightAnalytics.of(fight);
    assertEquals(s.hits, a.hits.size());
    assertEquals(5, a.hits.size());
    assertEquals(s.attempts, a.hits.size() + a.misses.size());
    assertEquals(s.emptyMisses, a.misses.stream().filter(FightAnalytics.Miss::empty).count());
    assertEquals(s.uncertain, a.misses.stream().filter(m -> !m.empty()).count());
    assertEquals(s.longestCombo, a.longestCombo());
    assertEquals(
        s.averageInterval(), a.intervals.stream().mapToDouble(d -> d).average().orElse(0), 1e-9);
    assertEquals(s.reachCount, Arrays.stream(a.hitReachBins).sum());
    assertEquals(s.centerAim, a.center);
    assertEquals(s.edgeAim, a.edge);
    assertEquals(s.highAim, a.high);
    assertEquals(s.lowAim, a.low);
  }

  @Test
  void readinessUsesFrozenEvaluations() {
    var a = FightAnalytics.of(Fixtures.sample());
    // cooldowns of hits: 1, .97, .9, .4, 1 with thresholds .95 / .85
    assertArrayEquals(new int[] {3, 1, 0, 1}, a.readiness);
    assertEquals(Grade.POOR, a.hits.get(3).readiness());
    assertEquals(5, Arrays.stream(a.cooldownBins).sum());
    assertEquals(4, a.cooldownBins[9], "0.9 and above share the top bin");
  }

  @Test
  void momentumTimelineReproducesRecordedEvaluations() {
    var fight = Fixtures.sample();
    var a = FightAnalytics.of(fight);
    for (int i = 0; i < a.hits.size(); i++) {
      var h = a.hits.get(i);
      assertEquals(fight.evaluations().get(i).momentum(), a.momentumAt(h.evidenceTime()), 1e-9);
    }
    // Decay is exponential with the shared constant.
    var last = a.momentumChanges.getLast();
    double later = a.momentumAt(last.time() + 3);
    assertEquals(last.value() * Math.exp(-3e9 / Statistics.MOMENTUM_DECAY_NANOS), later, 1e-9);
    double[] samples = a.momentumSamples(50);
    assertEquals(50, samples.length);
    for (double v : samples) assertTrue(v >= -1 && v <= 1);
  }

  @Test
  void missesKeepEstimatedDistanceSeparateFromHits() {
    var a = FightAnalytics.of(Fixtures.sample());
    var uncertain = a.misses.stream().filter(m -> !m.empty()).toList();
    assertEquals(1, uncertain.size());
    assertEquals(3.4, uncertain.getFirst().estimatedReach(), 1e-9);
    assertTrue(uncertain.getFirst().geometry());
    assertTrue(a.misses.stream().noneMatch(FightAnalytics.Miss::empty));
    assertEquals(1, a.fight.statistics().excludedSwings, "unrelated empty swing is excluded");
    assertEquals(1, Arrays.stream(a.missReachBins).sum());
  }

  @Test
  void jumpTimingObservationsAndCandidates() {
    var fight = Fixtures.sample();
    var a = FightAnalytics.of(fight);
    assertEquals(2, a.jumps.size());
    assertEquals(1, a.jumps.stream().filter(FightAnalytics.Jump::candidate).count());
    assertEquals(
        fight.statistics().jumpCandidates,
        a.jumps.stream().filter(FightAnalytics.Jump::candidate).count());
    assertEquals(80, a.jumps.getFirst().offsetMillis(), 1);
    assertEquals(1, a.jumpsWithoutDamage);
    assertEquals(1, a.motionSamples);
  }

  @Test
  void comboChainsSplitOnRetaliation() {
    var a = FightAnalytics.of(Fixtures.sample());
    // hits at 0, .6, 1.8 form one chain, incoming at 2.0 resets, then 2.6 and 3.0.
    assertEquals(List.of(3, 2), a.comboLengths);
    assertEquals(2.5, a.averageCombo(), 1e-9);
  }

  @Test
  void aimUsesContactedFaceCoordinate() {
    var a = FightAnalytics.of(Fixtures.sample());
    var noContact = a.hits.get(3);
    assertFalse(noContact.rayContact());
    assertEquals(-1, noContact.aimHorizontal());
    // z = 0 (on the face) so the horizontal coordinate is x.
    assertEquals(.05, a.hits.get(1).aimHorizontal(), 1e-9);
  }

  @Test
  void aggregatePoolsAndReportsMissingAsNaN() {
    var full = Fixtures.sample();
    var noHits = new Fixtures().attackAt(0, attack(1, 3, .5, .5, false, false, true)).finish(1);
    var summaryOnly = Fixtures.sample().summaryOnly();
    var list = List.of(noHits, summaryOnly, full);
    var agg = new Aggregate(list, FightAnalytics::of);
    assertEquals(3, agg.fights);
    assertEquals(2, agg.timelineFights);
    assertEquals(full.statistics().hits * 2, agg.hits);
    assertEquals(full.statistics().attempts * 2 + 1, agg.attempts);
    assertEquals(full.statistics().hits, agg.hitSamples.size());
    double[] trend = agg.trend(TrendMetric.REACH);
    assertEquals(3, trend.length);
    assertTrue(Double.isNaN(trend[2]), "fight without hits has no reach sample");
    assertFalse(Double.isNaN(trend[0]));
    assertTrue(Double.isNaN(Aggregate.trendValue(noHits, TrendMetric.CRITICAL)));
    assertEquals(0, Aggregate.trendValue(noHits, TrendMetric.ACCURACY));
    var none = new Aggregate(List.of(), FightAnalytics::of);
    assertTrue(Double.isNaN(none.accuracy()));
    assertTrue(Double.isNaN(none.averageReach()));
    assertTrue(Double.isNaN(none.maxReachOrNaN()));
    assertTrue(Double.isNaN(none.intervalDeviation()));
    assertEquals(Format.NONE, Format.percent(none.accuracy()));
  }

  @Test
  void pooledIntervalDeviationMatchesAllIntervals() {
    var f1 = Fixtures.sample();
    var f2 = Fixtures.sample();
    var agg = new Aggregate(List.of(f1, f2), FightAnalytics::of);
    double[] all = agg.intervals.stream().mapToDouble(d -> d).toArray();
    double mean = Arrays.stream(all).average().orElseThrow();
    double sd = Math.sqrt(Arrays.stream(all).map(v -> (v - mean) * (v - mean)).sum() / all.length);
    assertEquals(mean, agg.averageInterval(), 1e-9);
    assertEquals(sd, agg.intervalDeviation(), 1e-9);
  }

  @Test
  void timelineFreeRecordHasNoDerivedSamples() {
    var a = FightAnalytics.of(Fixtures.sample().summaryOnly());
    assertTrue(a.hits.isEmpty());
    assertTrue(a.misses.isEmpty());
    assertEquals(0, a.momentumAt(1));
  }

  @Test
  void historyFilteringAndSorting() {
    var a = withTime(Fixtures.sample(), 100, FightRecord.Outcome.UNKNOWN);
    var b =
        withTime(
            new Fixtures().attackAt(0, attack(1, 3, .5, .5, false, false, true)).finish(1),
            200,
            FightRecord.Outcome.LOSS);
    var c = withTime(Fixtures.sample(), 300, FightRecord.Outcome.INTERRUPTED);
    var all = List.of(c, b, a);
    assertEquals(
        List.of(c, b, a),
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.ALL, HistoryQuery.Sort.NEWEST, false));
    assertEquals(
        List.of(a, b, c),
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.ALL, HistoryQuery.Sort.OLDEST, false));
    assertEquals(
        List.of(b),
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.LOSS, HistoryQuery.Sort.NEWEST, false));
    assertEquals(
        List.of(c, a),
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.ALL, HistoryQuery.Sort.NEWEST, true));
    var byReach =
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.ALL, HistoryQuery.Sort.REACH, false);
    assertEquals(b, byReach.getLast(), "missing reach sorts last");
    var byHits =
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.ALL, HistoryQuery.Sort.HITS, false);
    assertEquals(c, byHits.getFirst(), "ties fall back to newest");
    assertTrue(
        HistoryQuery.apply(all, HistoryQuery.OutcomeFilter.WIN, HistoryQuery.Sort.NEWEST, false)
            .isEmpty());
  }

  @Test
  void opponentLabelRecordedAndFormatted() {
    var f = Fixtures.sample();
    assertEquals("Husk", f.opponentLabel());
    assertEquals("Husk", Format.opponent(f.opponentLabel(), f.opponent()));
    assertEquals("Entity 11111111", Format.opponent(null, f.opponent()));
    assertEquals("Unknown", Format.opponent(null, null));
  }

  static FightRecord withTime(FightRecord f, long start, FightRecord.Outcome outcome) {
    return new FightRecord(
        f.schemaVersion(),
        UUID.randomUUID().toString(),
        start,
        start + 1,
        f.durationNanos(),
        f.opponent(),
        outcome,
        f.reason(),
        f.statistics(),
        f.events(),
        f.policy(),
        f.evaluations(),
        f.opponentLabel());
  }
}
