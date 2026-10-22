package dev.kwamster.combat.quality;

/** Mirrors 1.21.11 Player.canCriticalAttack plus attack-strength gate; client observation only. */
public final class CriticalConditions {
  private CriticalConditions() {}

  public static boolean eligible(
      double cooldown,
      double fallDistance,
      boolean ground,
      boolean climbing,
      boolean water,
      boolean mobilityRestricted,
      boolean passenger,
      boolean sprinting) {
    return cooldown > .9
        && fallDistance > 0
        && !ground
        && !climbing
        && !water
        && !mobilityRestricted
        && !passenger
        && !sprinting;
  }
}
