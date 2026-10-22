package dev.kwamster.combat.quality;

import dev.kwamster.combat.events.CombatEvent.Attack;

/** Only call after a unique source-confirmed melee correlation. */
public final class PerfectReach {
  private PerfectReach() {}

  public static boolean eligible(Attack attack, double target, double tolerance) {
    return attack != null
        && attack.rayContact()
        && Double.isFinite(attack.reach())
        && Math.abs(attack.reach() - target) <= tolerance + 1e-9;
  }
}
