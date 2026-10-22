package dev.kwamster.combat.quality;

import dev.kwamster.combat.events.CombatEvent.Attack;
import dev.kwamster.config.Config;

/** Readiness feedback, not a damage or skill rating. No reward for central aim or maximum reach. */
public final class HitQuality {
  public enum Grade {
    PERFECT,
    GREAT,
    NORMAL,
    POOR
  }

  private HitQuality() {}

  public static Grade classify(Attack a, Config c) {
    return classify(a, c.perfectCooldown, c.greatCooldown);
  }

  public static Grade classify(Attack a, double perfect, double great) {
    if (a.cooldown() >= perfect) return Grade.PERFECT;
    if (a.cooldown() >= great) return Grade.GREAT;
    return a.cooldown() >= .5 ? Grade.NORMAL : Grade.POOR;
  }
}
