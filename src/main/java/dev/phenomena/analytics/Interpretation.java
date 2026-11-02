package dev.phenomena.analytics;

import dev.phenomena.combat.fights.Resolution;
import dev.phenomena.combat.metrics.Combos;
import java.util.*;

/**
 * Short plain-language statements generated deterministically from measured values. Every sentence
 * restates a recorded number; none gives coaching advice or claims a cause the evidence does not
 * show. No generative model or network service is involved.
 */
public final class Interpretation {
  private Interpretation() {}

  public static List<String> of(FightAnalytics a) {
    List<String> out = new ArrayList<>();
    var f = a.fight;
    Resolution r = f.resolution();
    out.add(
        "Outcome: "
            + r.classification().label
            + " ("
            + r.source().label
            + (r.derived() ? ", derived from a recording made before 0.1.3" : "")
            + ").");

    var local = a.combos.streaks().stream().filter(s -> s.side() == Combos.Side.LOCAL).toList();
    var best = local.stream().max(Comparator.comparingInt(Combos.Streak::length)).orElse(null);
    if (best != null && best.length() >= 2)
      out.add(
          "You landed "
              + words(best.length())
              + " consecutive hits "
              + switch (best.endedBy()) {
                case RETALIATION -> "before an attributed return hit.";
                case TRADE -> "before a same-tick trade.";
                case DISENGAGE -> "before both players disengaged.";
                case ROUND_BOUNDARY -> "before the round ended.";
                case RECORDING_END -> "and the recording ended without a return hit.";
              });
    else if (!a.hits.isEmpty()) out.add("No two of your confirmed hits landed consecutively.");

    String availability = a.opponentComboAvailability();
    if (!"Observed".equals(availability))
      out.add(
          "Some incoming damage could not be attributed to the opponent, so opponent combos are "
              + availability.toLowerCase(Locale.ROOT)
              + ".");

    var reach = a.reach;
    if (reach.eligible() > 0)
      out.add(
          String.format(
              Locale.ROOT,
              "Your reach was within the configured target window (%.2f ± %.2f m) on %d of %d"
                  + " eligible hits.",
              reach.target(),
              reach.tolerance(),
              reach.within(),
              reach.eligible()));

    var d = a.dominance;
    switch (d.availability) {
      case INCOMPLETE ->
          out.add(
              "Incoming-hit attribution was incomplete, so encounter dominance could not be"
                  + " established confidently.");
      case INSUFFICIENT ->
          out.add("Too few confirmed exchanges were observed to estimate encounter dominance.");
      case AVAILABLE ->
          out.add(
              String.format(
                  Locale.ROOT,
                  "During %.1f s of observed active combat you held the advantage %.0f%% of the"
                      + " time and the opponent %.0f%%.",
                  d.activeSeconds,
                  d.share(Dominance.State.LOCAL),
                  d.share(Dominance.State.OPPONENT)));
    }

    var s = a.shields;
    if (s.available() && s.attacksIntoShield() > 0)
      out.add(
          s.confirmedBlocks()
              + " of your "
              + s.attacksIntoShield()
              + " attacks into a raised shield were confirmed blocks"
              + (s.estimatedBlocks() > 0
                  ? " and " + s.estimatedBlocks() + " were estimated blocks"
                  : "")
              + ". Confirmed blocks are excluded from the accuracy denominator.");

    long jumpCandidates =
        a.incoming.stream()
            .filter(
                i ->
                    i.jumpClass().equals("IDEAL_CANDIDATE")
                        || i.jumpClass().equals("ATTEMPT_CANDIDATE"))
            .count();
    long same = a.incoming.stream().filter(i -> i.jumpClass().equals("IDEAL_CANDIDATE")).count();
    if (a.attributedIncoming > 0)
      out.add(
          jumpCandidates
              + " of "
              + a.attributedIncoming
              + " attributed incoming hits had an observed jump within ±"
              + f.policy().jumpWindowTicks()
              + " ticks ("
              + same
              + " on the same tick). Whether a jump reduced knockback is not measured.");

    Map<String, int[]> timing = new TreeMap<>();
    for (var t : a.timingSamples) {
      if (!t.graded()) continue;
      var c = timing.computeIfAbsent(t.weapon(), k -> new int[2]);
      c[1]++;
      if (t.grade() == dev.phenomena.combat.quality.AttackTiming.Grade.PERFECT) c[0]++;
    }
    for (var e : timing.entrySet())
      out.add(
          "With "
              + shortItem(e.getKey())
              + ", "
              + e.getValue()[0]
              + " of "
              + e.getValue()[1]
              + " graded attack intervals matched the weapon's cooldown. Cooldown readiness is not"
              + " a judgement of tactical timing.");
    return List.copyOf(out);
  }

  public static String shortItem(String item) {
    if (item == null) return "unknown";
    int i = item.indexOf(':');
    return (i >= 0 ? item.substring(i + 1) : item).replace('_', ' ');
  }

  private static String words(int n) {
    String[] w = {
      "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"
    };
    return n >= 0 && n < w.length ? w[n] : Integer.toString(n);
  }
}
