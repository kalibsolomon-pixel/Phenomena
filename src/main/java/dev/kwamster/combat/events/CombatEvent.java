package dev.kwamster.combat.events;

/** Immutable observations. nanoTime is session-relative monotonic time; never wall time. */
public record CombatEvent(
    long sequence,
    long nanoTime,
    Kind kind,
    String actor,
    String target,
    Confidence confidence,
    Attack attack,
    String detail) {
  public enum Kind {
    ATTACK,
    HURT,
    DAMAGE_IN,
    SPRINT,
    JUMP,
    MOTION,
    DEATH,
    DISCONNECT,
    QUALITY,
    JUMP_RESET
  }

  public record Attack(
      double cooldown,
      double reach,
      double aimX,
      double aimY,
      double aimZ,
      boolean sprinting,
      boolean criticalConditions,
      boolean rayContact) {}
}
