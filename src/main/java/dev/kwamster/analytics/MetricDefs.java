package dev.kwamster.analytics;

import java.util.*;

/**
 * One table of metric labels, units and confidence used by the in-game GUI and embedded in each
 * HTML report, so both surfaces describe the same quantity the same way.
 */
public final class MetricDefs {
  public record Def(String key, String label, String unit, String confidence, String help) {}

  public static final List<Def> ALL =
      List.of(
          new Def(
              "accuracy",
              "Hit accuracy",
              "%",
              "Inferred",
              "Correlated hits / all attempts. Uncertain attempts stay in the denominator."),
          new Def(
              "hits",
              "Correlated hits",
              "",
              "Confirmed source",
              "Server melee damage events from the local player, matched to an attack."),
          new Def(
              "attempts",
              "Attack attempts",
              "",
              "Observed",
              "Entity attacks plus empty swings during an engagement."),
          new Def(
              "emptyMisses",
              "Empty swings",
              "",
              "Observed",
              "Swings with no entity selected. The only confirmed misses."),
          new Def(
              "uncertain",
              "Uncertain attacks",
              "",
              "Inferred",
              "Entity attacks with no matching server report. Not proven misses."),
          new Def(
              "averageReach",
              "Average reach",
              "m",
              "Observed",
              "Client eye-to-hitbox distance on correlated hits. Not a server verdict."),
          new Def(
              "maxReach",
              "Maximum reach",
              "m",
              "Observed",
              "Largest client-measured distance on a correlated hit."),
          new Def(
              "criticalRate",
              "Critical-hit rate",
              "%",
              "Observed",
              "Hits with critical conditions / correlated hits. Damage is not verified."),
          new Def(
              "sprintRate",
              "Sprint-hit rate",
              "%",
              "Observed",
              "Hits made while sprinting / correlated hits. Knockback is not verified."),
          new Def(
              "averageInterval",
              "Average hit interval",
              "s",
              "Observed",
              "Mean attack-time gap between consecutive correlated hits."),
          new Def(
              "intervalDeviation",
              "Interval deviation",
              "s",
              "Observed",
              "Population standard deviation of hit intervals; lower is more consistent."),
          new Def(
              "jumpCandidates",
              "Jump candidates",
              "",
              "Inferred",
              "Possible jump resets: jumps inside the window after incoming damage. Success unavailable."),
          new Def(
              "longestCombo",
              "Longest combo",
              "",
              "Inferred",
              "Consecutive correlated hits without retaliation or timeout."),
          new Def(
              "averageCombo",
              "Average combo",
              "",
              "Inferred",
              "Mean length of hit chains, single hits included."),
          new Def(
              "momentum",
              "Momentum (estimate)",
              "",
              "Inferred",
              "+0.20 per hit, -0.25 per incoming hit, 3 s decay. Not a win probability."),
          new Def("fights", "Recorded fights", "", "Observed", "Fights in local history."));

  private static final Map<String, Def> BY_KEY = new HashMap<>();

  static {
    for (var d : ALL) BY_KEY.put(d.key(), d);
  }

  private MetricDefs() {}

  public static Def get(String key) {
    var d = BY_KEY.get(key);
    if (d == null) throw new IllegalArgumentException(key);
    return d;
  }
}
