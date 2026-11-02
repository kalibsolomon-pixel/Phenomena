package dev.phenomena.analytics;

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
              "Combat accuracy",
              "%",
              "Inferred",
              "Correlated hits / (attempts minus confirmed shield blocks). Uncertain attempts stay"
                  + " in the denominator."),
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
              "Unique eligible combat attempts. Noncombat inputs and duplicates are excluded in"
                  + " 0.1.6; historical measurements keep their original policy."),
          new Def(
              "emptyMisses",
              "Eligible misses",
              "",
              "Observed",
              "Aimed empty swings with opponent geometry and no confirmation within the correlation"
                  + " window. Inferred misses, not proof of server rejection. Historical"
                  + " empty-swing counts retain their older policy."),
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
              "Actual jumps within the signed client-tick window around incoming damage. Same tick"
                  + " is an ideal candidate; success is unavailable."),
          new Def(
              "longestCombo",
              "Longest combo",
              "",
              "Inferred",
              "Most consecutive confirmed hits before an attributed return hit, a same-tick trade"
                  + " or a disengagement gap (combo timeout)."),
          new Def(
              "averageCombo",
              "Average combo",
              "",
              "Inferred",
              "Mean length of combos: runs of two or more consecutive confirmed hits."),
          new Def(
              "momentum",
              "Legacy momentum",
              "",
              "Inferred",
              "0.1.0-0.1.2 per-hit value: +0.20 per hit, -0.25 per attributed incoming hit, 3 s"
                  + " decay. Superseded by encounter dominance."),
          new Def(
              "incoming",
              "Attributed incoming hits",
              "",
              "Confirmed source",
              "Server damage events whose cause entity is the tracked opponent. Only these break"
                  + " your combos or move dominance."),
          new Def(
              "unattributedIncoming",
              "Unattributed damage",
              "",
              "Observed",
              "Damage with no cause entity (fall, fire, environment) or from a third party. Never"
                  + " counted as opponent retaliation."),
          new Def(
              "attacksIntoShield",
              "Attacks into a raised shield",
              "",
              "Observed",
              "Attacks made while the target's client-visible state showed an active shield."),
          new Def(
              "shieldBlocks",
              "Confirmed shield blocks",
              "",
              "Confirmed source / inferred match",
              "Attack into a blocking target followed by the server's shield sound at the target"
                  + " and no damage event. Removed from the accuracy denominator."),
          new Def(
              "estimatedShieldBlocks",
              "Estimated shield blocks",
              "",
              "Estimated",
              "Attack into a raised, facing shield with no damage event and no shield sound. Stays"
                  + " uncertain and in the accuracy denominator."),
          new Def(
              "shieldDisables",
              "Shield disables",
              "",
              "Confirmed source / inferred match",
              "Your attack followed by the shield-disable sound at the opponent."),
          new Def(
              "hitsAfterDisable",
              "Hits after a disable",
              "",
              "Confirmed source",
              "Confirmed hits within 5 s of a shield disable you caused."),
          new Def(
              "localShieldBlocks",
              "Your shield blocks",
              "",
              "Observed",
              "Shield-block sounds at your position. The blocked attacker is not reported."),
          new Def(
              "serverCrits",
              "Server critical hits",
              "",
              "Confirmed source",
              "Your confirmed hits with the server's critical-hit animation on the opponent within"
                  + " one tick. Damage is not reported."),
          new Def(
              "comboCount",
              "Combos",
              "",
              "Inferred",
              "Runs of two or more consecutive confirmed hits."),
          new Def(
              "comboHits",
              "Hits in combos",
              "",
              "Inferred",
              "Confirmed hits that were part of a combo of two or more."),
          new Def(
              "longestOpponentCombo",
              "Longest opp. combo",
              "",
              "Inferred",
              "Same rule using attributed incoming hits. Estimated or unavailable when some"
                  + " incoming damage is unattributed."),
          new Def(
              "longestUnanswered",
              "Longest streak",
              "",
              "Inferred",
              "Most consecutive confirmed hits before an attributed return hit, ignoring"
                  + " disengagement gaps."),
          new Def(
              "dominance",
              "Encounter dominance",
              "",
              "Inferred",
              "Decayed share of confirmed evidence: (P - N) / (P + N + 1), 3 s time constant."
                  + " Neutral or not drawn when evidence is thin or incoming damage is"
                  + " unattributed."),
          new Def(
              "dominanceShare",
              "Advantage share",
              "%",
              "Inferred",
              "Share of observed active combat time with dominance above +0.2 (you) or below -0.2"
                  + " (opponent). Inactive and incomplete periods are excluded."),
          new Def(
              "reachWithin",
              "Reach in target window",
              "%",
              "Observed",
              "Eligible confirmed hits (ray contact) within the configured target and tolerance."),
          new Def(
              "reachDeviation",
              "Reach consistency",
              "m",
              "Observed",
              "Population standard deviation of eligible confirmed-hit reach; lower is more"
                  + " consistent."),
          new Def(
              "reachError",
              "Average deviation from target",
              "m",
              "Observed",
              "Mean absolute difference between eligible hit reach and the configured target."),
          new Def(
              "aimCentral",
              "Central aim",
              "%",
              "Observed geometry",
              "Hits graded ideal or good by the client hitbox heuristic. Not a damage measure."),
          new Def(
              "aimDistance",
              "Distance from ideal point",
              "m",
              "Observed geometry",
              "Mean blocks between the recorded contact and the region's ideal point; SD is aim"
                  + " consistency."),
          new Def(
              "timingPerfect",
              "Cooldown-matched intervals",
              "%",
              "Observed",
              "Graded attack intervals within the tick tolerance of the weapon's cooldown."
                  + " Mechanical readiness, not tactical quality."),
          new Def(
              "damageTaken",
              "Health lost",
              "HP",
              "Observed",
              "Sum of observed decreases of your health plus absorption during the recording."),
          new Def(
              "knockback",
              "Knockback observed",
              "",
              "Observed",
              "Incoming hits with a server velocity update for you within one tick."),
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
