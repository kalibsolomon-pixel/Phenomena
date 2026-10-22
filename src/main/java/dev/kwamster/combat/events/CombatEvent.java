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
    String detail,
    Long clientTick) {
  public CombatEvent(
      long sequence,
      long nanoTime,
      Kind kind,
      String actor,
      String target,
      Confidence confidence,
      Attack attack,
      String detail) {
    this(sequence, nanoTime, kind, actor, target, confidence, attack, detail, null);
  }

  public enum Kind {
    ATTACK,
    HURT,
    DAMAGE_IN,
    SPRINT,
    JUMP,
    JUMP_INPUT,
    MOTION,
    VELOCITY,
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
      boolean rayContact,
      Measurement measurement) {
    public Attack(
        double cooldown,
        double reach,
        double aimX,
        double aimY,
        double aimZ,
        boolean sprinting,
        boolean criticalConditions,
        boolean rayContact) {
      this(cooldown, reach, aimX, aimY, aimZ, sprinting, criticalConditions, rayContact, null);
    }
  }

  public record Measurement(
      String weapon,
      double attackSpeed,
      double cooldownTicks,
      dev.kwamster.combat.quality.AttackTiming.Result timing,
      dev.kwamster.combat.metrics.Geometry.Vec eye,
      dev.kwamster.combat.metrics.Geometry.Box box,
      dev.kwamster.combat.quality.MeleeAim.Region idealRegion,
      dev.kwamster.combat.quality.MeleeAim.Grade aimQuality,
      String mode) {
    public Measurement {
      // A zero/unavailable speed can occur through server attributes; never serialize Infinity.
      if (!Double.isFinite(attackSpeed) || attackSpeed <= 0) attackSpeed = 0;
      if (!Double.isFinite(cooldownTicks) || cooldownTicks <= 0) cooldownTicks = 0;
    }
  }
}
