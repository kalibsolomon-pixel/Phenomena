package dev.phenomena.combat.metrics;

import dev.phenomena.combat.events.CombatEvent.Attack;

public final class Statistics {
  private static final com.google.gson.Gson SUMMARY_JSON =
      new com.google.gson.GsonBuilder()
          .addSerializationExclusionStrategy(
              new com.google.gson.ExclusionStrategy() {
                public boolean shouldSkipClass(Class<?> c) {
                  return false;
                }

                public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
                  return f.getDeclaringClass() == Statistics.class
                      && java.util.Set.of(
                              "attackCanonicalIds",
                              "attackSamples",
                              "attackClassifications",
                              "attackTimings",
                              "combatExchanges")
                          .contains(f.getName());
                }
              })
          .create();

  /** Timeline metadata belongs in the lazy-loaded full recording, never every history summary. */
  public Statistics summaryCopy() {
    return dev.phenomena.recording.storage.AtomicJson.GSON.fromJson(
        SUMMARY_JSON.toJson(this), Statistics.class);
  }

  /** Additive frozen 0.1.6 measurement metadata; absent on historical recordings. */
  public int measurementVersion,
      totalInputs,
      uniqueAttacks,
      deduplicatedAttacks,
      excludedSwings,
      eligibleMisses,
      uncertainShield;

  public java.util.Map<Long, Long> attackCanonicalIds = new java.util.LinkedHashMap<>();
  public java.util.Map<Long, Long> attackSamples = new java.util.LinkedHashMap<>();
  public java.util.Map<Long, String> attackClassifications = new java.util.LinkedHashMap<>();
  public java.util.Map<Long, dev.phenomena.combat.quality.AttackTiming.Result> attackTimings =
      new java.util.LinkedHashMap<>();
  public double exchangeTimeoutSeconds = 3;
  public java.util.List<dev.phenomena.combat.tracking.CombatExchange.Interval> combatExchanges =
      java.util.List.of();

  /** Estimated momentum model, shared with derived timelines and the report viewer. */
  public static final double MOMENTUM_HIT = .2, MOMENTUM_INCOMING = .25, MOMENTUM_DECAY_NANOS = 3e9;

  public int attempts,
      hits,
      emptyMisses,
      uncertain,
      contacts,
      criticalConditions,
      sprintHits,
      jumpCandidates,
      currentCombo,
      longestCombo,
      incoming;
  public int perfectReachHits;

  /**
   * Schema 3 counters. {@code incoming} counts only server-attributed opponent hits; damage without
   * a cause entity and damage from third parties are counted separately and never treated as
   * retaliation.
   */
  public int unattributedIncoming, thirdPartyIncoming;

  public int attacksIntoShield, shieldBlocks, estimatedShieldBlocks, shieldDisables;
  public int localShieldBlocks, localShieldDisabled;
  public int serverCrits, opponentCrits;
  public int comboCount, comboHits, longestOpponentCombo, longestUnanswered;
  public double damageTaken;
  public int centerAim, edgeAim, highAim, lowAim;
  public int[] reachDistribution = new int[8];
  public double[] recentReach = new double[16];
  public int recentReachIndex, recentReachCount;
  public double recentInterval;
  public long momentumAt;
  public double reachSum, maxReach, cooldownSum, intervalSum, intervalSquared;
  public int reachCount, intervalCount;
  public long lastHit, comboStart;
  public double momentum;

  public void attempt(Attack a) {
    attempt(a, a == null);
  }

  public void attempt(Attack a, boolean empty) {
    attempts++;
    if (empty) emptyMisses++;
    else uncertain++;
    if (a != null && a.rayContact()) contacts++;
  }

  public void hit(Attack a, long time, long evidenceTime, double comboSeconds) {
    if (measurementVersion == 0) {
      hits++;
      uncertain--;
    }
    if (a.reach() >= 0) {
      reachSum += a.reach();
      reachCount++;
      maxReach = Math.max(maxReach, a.reach());
      reachDistribution[Math.min(7, (int) a.reach())]++;
      recentReach[recentReachIndex++ % 16] = a.reach();
      recentReachCount = Math.min(16, recentReachCount + 1);
    }
    if (a.rayContact()) {
      switch (Geometry.placement(a.aimX(), a.aimY(), a.aimZ())) {
        case "Center" -> centerAim++;
        case "Edge" -> edgeAim++;
        case "High" -> highAim++;
        case "Low" -> lowAim++;
      }
    }
    if (a.criticalConditions()) criticalConditions++;
    if (a.sprinting()) sprintHits++;
    cooldownSum += a.cooldown();
    if (lastHit > 0) {
      double interval = (time - lastHit) / 1e9;
      recentInterval = interval;
      intervalSum += interval;
      intervalSquared += interval * interval;
      intervalCount++;
      if (interval > comboSeconds) currentCombo = 0;
    }
    if (currentCombo == 0) comboStart = time;
    currentCombo++;
    longestCombo = Math.max(longestCombo, currentCombo);
    lastHit = time;
    momentum = Math.min(1, momentumAt(evidenceTime) + MOMENTUM_HIT);
    momentumAt = evidenceTime;
  }

  public void incoming(long time) {
    incoming++;
    currentCombo = 0;
    momentum = Math.max(-1, momentumAt(time) - MOMENTUM_INCOMING);
    momentumAt = time;
  }

  public double momentumAt(long time) {
    return momentum * Math.exp(-Math.max(0, time - momentumAt) / MOMENTUM_DECAY_NANOS);
  }

  public double recentReachDeviation() {
    if (recentReachCount == 0) return 0;
    double sum = 0, squares = 0;
    for (int i = 0; i < recentReachCount; i++) {
      sum += recentReach[i];
      squares += recentReach[i] * recentReach[i];
    }
    return Math.sqrt(Math.max(0, squares / recentReachCount - Math.pow(sum / recentReachCount, 2)));
  }

  /**
   * Hit accuracy denominator: every attempt except attacks confirmed as shield blocks. A blocked
   * attack connected with the opponent's shield; it is neither a hit nor a miss.
   */
  public int accuracyDenominator() {
    return Math.max(0, attempts - shieldBlocks);
  }

  public double accuracy() {
    int d = accuracyDenominator();
    return d == 0 ? 0 : 100.0 * hits / d;
  }

  /** A confirmed shield block consumes an attempt that would otherwise stay uncertain. */
  public void blocked() {
    if (measurementVersion == 0) {
      shieldBlocks++;
      uncertain--;
    }
  }

  public double averageReach() {
    return reachCount == 0 ? 0 : reachSum / reachCount;
  }

  public double averageInterval() {
    return intervalCount == 0 ? 0 : intervalSum / intervalCount;
  }

  public double intervalDeviation() {
    return intervalCount == 0
        ? 0
        : Math.sqrt(Math.max(0, intervalSquared / intervalCount - Math.pow(averageInterval(), 2)));
  }

  public Statistics copy() {
    return dev.phenomena.recording.storage.AtomicJson.GSON.fromJson(
        dev.phenomena.recording.storage.AtomicJson.GSON.toJson(this), Statistics.class);
  }

  /** Session totals sum observations; streaks use the maximum of independent exchanges. */
  public void add(Statistics s) {
    measurementVersion = Math.max(measurementVersion, s.measurementVersion);
    totalInputs += s.totalInputs;
    uniqueAttacks += s.uniqueAttacks;
    deduplicatedAttacks += s.deduplicatedAttacks;
    excludedSwings += s.excludedSwings;
    eligibleMisses += s.eligibleMisses;
    uncertainShield += s.uncertainShield;
    attackCanonicalIds.putAll(s.attackCanonicalIds);
    attackSamples.putAll(s.attackSamples);
    attackClassifications.putAll(s.attackClassifications);
    attackTimings.putAll(s.attackTimings);
    var intervals = new java.util.ArrayList<>(combatExchanges);
    intervals.addAll(s.combatExchanges);
    combatExchanges = java.util.List.copyOf(intervals);
    attempts += s.attempts;
    hits += s.hits;
    emptyMisses += s.emptyMisses;
    uncertain += s.uncertain;
    contacts += s.contacts;
    criticalConditions += s.criticalConditions;
    sprintHits += s.sprintHits;
    jumpCandidates += s.jumpCandidates;
    incoming += s.incoming;
    perfectReachHits += s.perfectReachHits;
    unattributedIncoming += s.unattributedIncoming;
    thirdPartyIncoming += s.thirdPartyIncoming;
    attacksIntoShield += s.attacksIntoShield;
    shieldBlocks += s.shieldBlocks;
    estimatedShieldBlocks += s.estimatedShieldBlocks;
    shieldDisables += s.shieldDisables;
    localShieldBlocks += s.localShieldBlocks;
    localShieldDisabled += s.localShieldDisabled;
    serverCrits += s.serverCrits;
    opponentCrits += s.opponentCrits;
    comboCount += s.comboCount;
    comboHits += s.comboHits;
    damageTaken += s.damageTaken;
    longestCombo = Math.max(longestCombo, s.longestCombo);
    longestOpponentCombo = Math.max(longestOpponentCombo, s.longestOpponentCombo);
    longestUnanswered = Math.max(longestUnanswered, s.longestUnanswered);
    reachSum += s.reachSum;
    reachCount += s.reachCount;
    maxReach = Math.max(maxReach, s.maxReach);
    cooldownSum += s.cooldownSum;
    intervalSum += s.intervalSum;
    intervalSquared += s.intervalSquared;
    intervalCount += s.intervalCount;
    centerAim += s.centerAim;
    edgeAim += s.edgeAim;
    highAim += s.highAim;
    lowAim += s.lowAim;
    for (int i = 0; i < reachDistribution.length; i++)
      reachDistribution[i] += s.reachDistribution[i];
  }
}
