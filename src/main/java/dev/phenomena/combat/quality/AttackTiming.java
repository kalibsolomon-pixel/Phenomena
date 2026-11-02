package dev.phenomena.combat.quality;

import java.util.Objects;

/** Attempt intervals in client ticks; first attempts never receive a perfect interval grade. */
public final class AttackTiming {
  public enum Grade {
    OPENER,
    PERFECT,
    TOO_FAST,
    TOO_SLOW,
    UNAVAILABLE,
    /**
     * Expected cooldown is not longer than the tick tolerance; fast and perfect are inseparable.
     */
    BELOW_RESOLUTION
  }

  public record Result(Grade grade, double intervalTicks, double expectedTicks) {}

  private String weapon;
  private long previousTick = -1;
  private double previousCooldown;

  public static double cooldownTicks(double effectiveAttackSpeed) {
    return Double.isFinite(effectiveAttackSpeed) && effectiveAttackSpeed > 0
        ? 20 / effectiveAttackSpeed
        : Double.NaN;
  }

  public void reset() {
    weapon = null;
    previousTick = -1;
  }

  public void held(String key) {
    if (!Objects.equals(weapon, key)) reset();
  }

  public Result attack(String key, long tick, double cooldown, double tolerance) {
    if (key == null || !Double.isFinite(cooldown) || cooldown <= 0) {
      reset();
      return new Result(Grade.UNAVAILABLE, 0, 0);
    }
    double interval = previousTick < 0 ? 0 : tick - previousTick;
    Grade grade = Grade.OPENER;
    double expected = previousCooldown;
    if (Objects.equals(weapon, key)
        && previousTick >= 0
        && interval >= 0
        && interval <= 2 * previousCooldown) {
      grade =
          previousCooldown <= Math.max(tolerance, 1)
              ? Grade.BELOW_RESOLUTION
              : Math.abs(interval - previousCooldown) <= tolerance
                  ? Grade.PERFECT
                  : interval < previousCooldown ? Grade.TOO_FAST : Grade.TOO_SLOW;
    }
    weapon = key;
    previousTick = tick;
    previousCooldown = cooldown;
    return new Result(grade, interval, grade == Grade.OPENER ? cooldown : expected);
  }
}
