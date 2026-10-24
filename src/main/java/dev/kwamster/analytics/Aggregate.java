package dev.kwamster.analytics;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.HitQuality.Grade;
import java.util.*;

/**
 * Pooled analytics over a set of fights. Counting metrics use the per-fight Statistics (present
 * even for summary-only records); per-hit distributions use only fights whose timelines are in
 * memory, and report that coverage explicitly. Missing data is NaN, never zero.
 */
public final class Aggregate {
  public final int fights, timelineFights;
  public final int attempts, hits, emptyMisses, uncertain, contacts;
  public final int criticalConditions, sprintHits, jumpCandidates, incoming, longestCombo;
  public final double reachSum, maxReach;
  public final int reachCount;
  public final double intervalSum, intervalSquared;
  public final int intervalCount;
  public final int[] summaryReachBins = new int[8];

  /** Per-hit lists across timeline fights, oldest fight first. */
  public final List<FightAnalytics.Hit> hitSamples;

  public final List<FightAnalytics.Miss> missSamples;
  public final List<FightAnalytics.Jump> jumps;
  public final List<Double> intervals;
  public final List<Integer> comboLengths;
  public final int jumpsWithoutDamage, motionSamples;
  public final List<FightAnalytics.Timing> timingSamples = new ArrayList<>();
  public final int[] readiness = new int[4];
  public final int[] hitReachBins = new int[FightAnalytics.REACH_BINS + 1];
  public final int[] missReachBins = new int[FightAnalytics.REACH_BINS + 1];
  public final int[] cooldownBins = new int[FightAnalytics.COOLDOWN_BINS];
  public final int center, edge, high, low;

  /** Per-fight trend series, oldest first; values are NaN where a fight has no samples. */
  public final List<FightRecord> chronological;

  public final FightAnalytics latestTimeline;

  // ---- 0.1.3 pooled values
  public final int unattributedIncoming, thirdPartyIncoming;
  public final int attacksIntoShield, shieldBlocks, estimatedShieldBlocks, shieldDisables;
  public final int localShieldBlocks, localShieldDisabled, serverCrits, opponentCrits;
  public final int comboCount, comboHits, longestOpponentCombo, longestUnanswered;
  public final double damageTaken;

  /** Timeline fights recorded with schema 3 structured context (shields, attribution). */
  public final int contextFights;

  public final List<FightAnalytics.TimingSample> timing;
  public final List<FightAnalytics.Incoming> incomingSamples;
  public final List<FightAnalytics.AimSample> aimSamples;
  public final List<FightAnalytics.ShieldEvent> shieldEvents;
  public final List<Integer> localCombos, opponentCombos;
  public final double[] reachValues;
  public final String[] reachWeapons;
  public final int reachEligible, reachWithin, hitsAfterDisable, bypassedShields;
  public final double reachTarget, reachTolerance;
  public final boolean mixedReachPolicy;
  public final double dominanceLocal, dominanceOpponent, dominanceNeutral;
  public final double dominanceIncomplete, dominanceInsufficient;
  public final int[] dominanceAvailability = new int[3];
  public final List<FightAnalytics> timelines;

  /**
   * @param newestFirst fights as kept in history (newest first)
   * @param analytics resolves timeline analytics for a fight with events (may cache)
   */
  public Aggregate(
      List<FightRecord> newestFirst,
      java.util.function.Function<FightRecord, FightAnalytics> analytics) {
    fights = newestFirst.size();
    int at = 0,
        h = 0,
        em = 0,
        un = 0,
        co = 0,
        cr = 0,
        sp = 0,
        ju = 0,
        in = 0,
        lc = 0,
        rc = 0,
        ic = 0;
    int ua = 0, tp = 0, ais = 0, sb = 0, esb = 0, sd = 0, lsb = 0, lsd = 0, scr = 0, ocr = 0;
    int cc = 0, ch = 0, loc = 0, lu = 0;
    double dt = 0;
    double rs = 0, mr = 0, is = 0, isq = 0;
    for (var f : newestFirst) {
      Statistics s = f.statistics();
      ua += s.unattributedIncoming;
      tp += s.thirdPartyIncoming;
      ais += s.attacksIntoShield;
      sb += s.shieldBlocks;
      esb += s.estimatedShieldBlocks;
      sd += s.shieldDisables;
      lsb += s.localShieldBlocks;
      lsd += s.localShieldDisabled;
      scr += s.serverCrits;
      ocr += s.opponentCrits;
      cc += s.comboCount;
      ch += s.comboHits;
      loc = Math.max(loc, s.longestOpponentCombo);
      lu = Math.max(lu, s.longestUnanswered);
      dt += s.damageTaken;
      at += s.attempts;
      h += s.hits;
      em += s.emptyMisses;
      un += s.uncertain;
      co += s.contacts;
      cr += s.criticalConditions;
      sp += s.sprintHits;
      ju += s.jumpCandidates;
      in += s.incoming;
      lc = Math.max(lc, s.longestCombo);
      rs += s.reachSum;
      rc += s.reachCount;
      mr = Math.max(mr, s.maxReach);
      is += s.intervalSum;
      isq += s.intervalSquared;
      ic += s.intervalCount;
      if (s.reachDistribution != null)
        for (int i = 0; i < Math.min(8, s.reachDistribution.length); i++)
          summaryReachBins[i] += s.reachDistribution[i];
    }
    attempts = at;
    hits = h;
    emptyMisses = em;
    uncertain = un;
    contacts = co;
    criticalConditions = cr;
    sprintHits = sp;
    jumpCandidates = ju;
    incoming = in;
    longestCombo = lc;
    reachSum = rs;
    reachCount = rc;
    maxReach = mr;
    intervalSum = is;
    intervalSquared = isq;
    intervalCount = ic;
    unattributedIncoming = ua;
    thirdPartyIncoming = tp;
    attacksIntoShield = ais;
    shieldBlocks = sb;
    estimatedShieldBlocks = esb;
    shieldDisables = sd;
    localShieldBlocks = lsb;
    localShieldDisabled = lsd;
    serverCrits = scr;
    opponentCrits = ocr;
    comboCount = cc;
    comboHits = ch;
    longestOpponentCombo = loc;
    longestUnanswered = lu;
    damageTaken = dt;

    List<FightRecord> chrono = new ArrayList<>(newestFirst);
    Collections.reverse(chrono);
    chronological = List.copyOf(chrono);

    List<FightAnalytics.Hit> hs = new ArrayList<>();
    List<FightAnalytics.Miss> ms = new ArrayList<>();
    List<FightAnalytics.Jump> js = new ArrayList<>();
    List<Double> iv = new ArrayList<>();
    List<Integer> cl = new ArrayList<>();
    int tf = 0, nd = 0, mo = 0, ce = 0, ed = 0, hi = 0, lo = 0;
    FightAnalytics latest = null;
    List<FightAnalytics.TimingSample> tsl = new ArrayList<>();
    List<FightAnalytics.Incoming> inl = new ArrayList<>();
    List<FightAnalytics.AimSample> aml = new ArrayList<>();
    List<FightAnalytics.ShieldEvent> shl = new ArrayList<>();
    List<Integer> lcs = new ArrayList<>(), ocs = new ArrayList<>();
    List<Double> rv = new ArrayList<>();
    List<String> rw = new ArrayList<>();
    List<FightAnalytics> tl = new ArrayList<>();
    int re = 0, rwi = 0, had = 0, byp = 0, ctx = 0;
    double target = Double.NaN, tolerance = Double.NaN;
    boolean mixed = false;
    double dl = 0, dop = 0, dn = 0, di = 0, dins = 0;
    for (var f : chrono) {
      if (!f.hasTimeline()) continue;
      var a = analytics.apply(f);
      tf++;
      latest = a;
      tl.add(a);
      if (a.contextAvailable) ctx++;
      tsl.addAll(a.timingSamples);
      inl.addAll(a.incoming);
      aml.addAll(a.aim.samples());
      shl.addAll(a.shields.events());
      for (var st : a.combos.streaks())
        if (st.combo())
          (st.side() == dev.kwamster.combat.metrics.Combos.Side.LOCAL ? lcs : ocs).add(st.length());
      for (int k = 0; k < a.reach.values().length; k++) {
        rv.add(a.reach.values()[k]);
        rw.add(a.reach.weapons()[k]);
      }
      re += a.reach.eligible();
      rwi += a.reach.within();
      had += a.shields.hitsAfterDisable();
      byp += a.shields.bypassed();
      if (!Double.isNaN(target) && (target != a.reach.target() || tolerance != a.reach.tolerance()))
        mixed = true;
      target = a.reach.target();
      tolerance = a.reach.tolerance();
      var d = a.dominance;
      dl += d.localSeconds;
      dop += d.opponentSeconds;
      dn += d.neutralSeconds;
      di += d.incompleteSeconds;
      dins += d.insufficientSeconds;
      dominanceAvailability[d.availability.ordinal()]++;
      hs.addAll(a.hits);
      ms.addAll(a.misses);
      js.addAll(a.jumps);
      iv.addAll(a.intervals);
      cl.addAll(a.comboLengths);
      nd += a.jumpsWithoutDamage;
      mo += a.motionSamples;
      ce += a.center;
      ed += a.edge;
      hi += a.high;
      lo += a.low;
      timingSamples.addAll(a.timings);
      add(readiness, a.readiness);
      add(hitReachBins, a.hitReachBins);
      add(missReachBins, a.missReachBins);
      add(cooldownBins, a.cooldownBins);
    }
    timelineFights = tf;
    latestTimeline = latest;
    contextFights = ctx;
    timing = List.copyOf(tsl);
    incomingSamples = List.copyOf(inl);
    aimSamples = List.copyOf(aml);
    shieldEvents = List.copyOf(shl);
    localCombos = List.copyOf(lcs);
    opponentCombos = List.copyOf(ocs);
    reachValues = rv.stream().mapToDouble(Double::doubleValue).toArray();
    reachWeapons = rw.toArray(String[]::new);
    reachEligible = re;
    reachWithin = rwi;
    hitsAfterDisable = had;
    bypassedShields = byp;
    reachTarget = target;
    reachTolerance = tolerance;
    mixedReachPolicy = mixed;
    dominanceLocal = dl;
    dominanceOpponent = dop;
    dominanceNeutral = dn;
    dominanceIncomplete = di;
    dominanceInsufficient = dins;
    timelines = List.copyOf(tl);
    hitSamples = List.copyOf(hs);
    missSamples = List.copyOf(ms);
    jumps = List.copyOf(js);
    intervals = List.copyOf(iv);
    comboLengths = List.copyOf(cl);
    jumpsWithoutDamage = nd;
    motionSamples = mo;
    center = ce;
    edge = ed;
    high = hi;
    low = lo;
  }

  private static void add(int[] into, int[] from) {
    for (int i = 0; i < into.length; i++) into[i] += from[i];
  }

  public static double ratio(double numerator, double denominator) {
    return denominator == 0 ? Double.NaN : numerator / denominator;
  }

  /**
   * Conservative accuracy percentage: correlated hits / (all attempts minus confirmed shield
   * blocks). Uncertain attacks stay in the denominator.
   */
  public double accuracy() {
    return 100 * ratio(hits, attempts - shieldBlocks);
  }

  public int accuracyDenominator() {
    return attempts - shieldBlocks;
  }

  public double reachWithinPercent() {
    return 100 * ratio(reachWithin, reachEligible);
  }

  /** Share of pooled active (sufficient, complete) dominance time favoring a side. */
  public double dominanceShare(Dominance.State s) {
    double active = dominanceLocal + dominanceOpponent + dominanceNeutral;
    double v =
        s == Dominance.State.LOCAL
            ? dominanceLocal
            : s == Dominance.State.OPPONENT ? dominanceOpponent : dominanceNeutral;
    return 100 * ratio(v, active);
  }

  public double averageLocalCombo() {
    return localCombos.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
  }

  public double averageReach() {
    return ratio(reachSum, reachCount);
  }

  public double maxReachOrNaN() {
    return reachCount == 0 ? Double.NaN : maxReach;
  }

  public double criticalRate() {
    return 100 * ratio(criticalConditions, hits);
  }

  public double sprintRate() {
    return 100 * ratio(sprintHits, hits);
  }

  public double averageInterval() {
    return ratio(intervalSum, intervalCount);
  }

  /** Pooled population standard deviation of all hit intervals. */
  public double intervalDeviation() {
    if (intervalCount == 0) return Double.NaN;
    double mean = intervalSum / intervalCount;
    return Math.sqrt(Math.max(0, intervalSquared / intervalCount - mean * mean));
  }

  /** Mean length of local combos (two or more consecutive hits), pooled over timeline fights. */
  public double averageCombo() {
    return averageLocalCombo();
  }

  public int readiness(Grade g) {
    return readiness[g.ordinal()];
  }

  /** Trend value for one fight; NaN where the fight has no samples for this metric. */
  public static double trendValue(FightRecord f, TrendMetric metric) {
    var s = f.statistics();
    return switch (metric) {
      case ACCURACY -> 100 * ratio(s.hits, s.accuracyDenominator());
      case REACH -> ratio(s.reachSum, s.reachCount);
      case CRITICAL -> 100 * ratio(s.criticalConditions, s.hits);
      case TIMING -> ratio(s.intervalSum, s.intervalCount);
    };
  }

  public double[] trend(TrendMetric metric) {
    double[] out = new double[chronological.size()];
    for (int i = 0; i < out.length; i++) out[i] = trendValue(chronological.get(i), metric);
    return out;
  }

  /** Per-fight rate of a sub-count over correlated hits, chronological; NaN without hits. */
  public double[] perFightHitRate(java.util.function.ToIntFunction<Statistics> count) {
    double[] out = new double[chronological.size()];
    for (int i = 0; i < out.length; i++) {
      var s = chronological.get(i).statistics();
      out[i] = 100 * ratio(count.applyAsInt(s), s.hits);
    }
    return out;
  }
}
