package dev.kwamster.combat.metrics;

import dev.kwamster.combat.events.CombatEvent.Attack;

public final class Statistics {
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
    hits++;
    uncertain--;
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

  public double accuracy() {
    return attempts == 0 ? 0 : 100.0 * hits / attempts;
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
    return dev.kwamster.recording.storage.AtomicJson.GSON.fromJson(
        dev.kwamster.recording.storage.AtomicJson.GSON.toJson(this), Statistics.class);
  }
}
