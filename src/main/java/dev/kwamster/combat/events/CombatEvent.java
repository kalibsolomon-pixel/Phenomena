package dev.kwamster.combat.events;

/**
 * Immutable observations. nanoTime is session-relative monotonic time; never wall time. {@code
 * context} carries structured, optional fields added in schema 3; it is null on older recordings
 * and on events that do not need it.
 */
public record CombatEvent(
    long sequence,
    long nanoTime,
    Kind kind,
    String actor,
    String target,
    Confidence confidence,
    Attack attack,
    String detail,
    Long clientTick,
    Context context) {
  public CombatEvent(
      long sequence,
      long nanoTime,
      Kind kind,
      String actor,
      String target,
      Confidence confidence,
      Attack attack,
      String detail,
      Long clientTick) {
    this(sequence, nanoTime, kind, actor, target, confidence, attack, detail, clientTick, null);
  }

  public CombatEvent(
      long sequence,
      long nanoTime,
      Kind kind,
      String actor,
      String target,
      Confidence confidence,
      Attack attack,
      String detail) {
    this(sequence, nanoTime, kind, actor, target, confidence, attack, detail, null, null);
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
    JUMP_RESET,
    /** Shield block or shield disable sound attributed to the local player or the opponent. */
    SHIELD,
    /** Server critical-hit animation on the opponent (local crit) or the local player. */
    CRIT,
    /** Local health or absorption changed during an engagement. */
    HEALTH,
    /** Large single-tick position change of the local player (teleport, pearl, arena reset). */
    TELEPORT,
    /** The tracked opponent entity disappeared from the client world. */
    REMOVED
  }

  /** Who an incoming damage event can be attributed to, from the server-reported source. */
  public enum Attribution {
    /** Cause entity is the tracked opponent and also the direct source (melee). */
    OPPONENT_DIRECT,
    /** Cause entity is the tracked opponent through a projectile or other indirect source. */
    OPPONENT_INDIRECT,
    /** Cause entity is a different entity (third party). */
    OTHER_ENTITY,
    /** No cause entity was reported or it could not be resolved on the client. */
    UNATTRIBUTED
  }

  /**
   * Optional structured observation fields. Every field may be null; absence means "not observed",
   * never zero.
   */
  public record Context(
      Attribution attribution,
      String damageType,
      Boolean direct,
      Double health,
      Double absorption,
      Double vx,
      Double vy,
      Double vz,
      Boolean ground,
      Boolean sprinting,
      Boolean movementValid,
      String shield,
      String subject,
      Boolean targetBlocking,
      Boolean targetFacing,
      Double distance) {
    public static Context incoming(
        Attribution attribution,
        String damageType,
        Boolean direct,
        Boolean ground,
        Boolean sprinting,
        Boolean movementValid) {
      return new Context(
          attribution,
          damageType,
          direct,
          null,
          null,
          null,
          null,
          null,
          ground,
          sprinting,
          movementValid,
          null,
          null,
          null,
          null,
          null);
    }

    public static Context health(double health, double absorption) {
      return new Context(
          null,
          null,
          null,
          health,
          absorption,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public static Context velocity(double vx, double vy, double vz, Boolean ground) {
      return new Context(
          null, null, null, null, null, vx, vy, vz, ground, null, null, null, null, null, null,
          null);
    }

    /** {@code shield} is BLOCK or DISABLE; {@code subject} is local or opponent. */
    public static Context shield(String shield, String subject, double distance) {
      return new Context(
          null, null, null, null, null, null, null, null, null, null, null, shield, subject, null,
          null, distance);
    }

    public static Context subject(String subject) {
      return new Context(
          null, null, null, null, null, null, null, null, null, null, null, null, subject, null,
          null, null);
    }

    public static Context target(boolean blocking, boolean facing) {
      return new Context(
          null, null, null, null, null, null, null, null, null, null, null, null, null, blocking,
          facing, null);
    }

    public static Context jump(boolean sprinting) {
      return new Context(
          null, null, null, null, null, null, null, null, true, sprinting, true, null, null, null,
          null, null);
    }

    public static Context distance(double distance) {
      return new Context(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          distance);
    }
  }

  /** True when this incoming event is a server-attributed hit by the tracked opponent. */
  public boolean attributedTo(String opponent) {
    if (kind != Kind.DAMAGE_IN || actor == null || opponent == null) return false;
    if (context != null && context.attribution() != null)
      return (context.attribution() == Attribution.OPPONENT_DIRECT
              || context.attribution() == Attribution.OPPONENT_INDIRECT)
          && actor.equals(opponent);
    // Recordings before schema 3: the server cause entity matched the opponent.
    return actor.equals(opponent);
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

    /** Registry id without the slot/attribute identity suffix. */
    public String item() {
      return weapon == null ? "unknown" : weapon.split(";")[0];
    }
  }
}
