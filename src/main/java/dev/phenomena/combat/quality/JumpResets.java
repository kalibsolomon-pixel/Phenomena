package dev.phenomena.combat.quality;

import dev.phenomena.combat.events.CombatEvent;
import java.util.*;

/** One nearest incoming observation per actual jump, independent of packet/input ordering. */
public final class JumpResets {
  public enum Grade {
    IDEAL_CANDIDATE,
    ATTEMPT_CANDIDATE,
    OUTSIDE_WINDOW,
    UNAVAILABLE
  }

  public record Sample(
      long jumpSequence, Long damageSequence, Long offsetTicks, Double offsetMillis, Grade grade) {}

  /** Legacy form: every incoming damage observation is a candidate reference. */
  public static List<Sample> analyze(List<CombatEvent> events, int window) {
    return analyze(events, window, null);
  }

  /**
   * Pairs each actual jump with the nearest incoming hit attributed to {@code opponent}. A null
   * opponent keeps the pre-0.1.3 behaviour of using every incoming damage observation.
   */
  public static List<Sample> analyze(List<CombatEvent> events, int window, String opponent) {
    var damage =
        events.stream()
            .filter(
                e ->
                    e.kind() == CombatEvent.Kind.DAMAGE_IN
                        && (opponent == null || e.attributedTo(opponent)))
            .toList();
    List<Sample> out = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (var jump : events) {
      if (jump.kind() != CombatEvent.Kind.JUMP || !seen.add(jump.sequence())) continue;
      CombatEvent nearest = null;
      long best = Long.MAX_VALUE, bestTicks = Long.MAX_VALUE;
      for (var hit : damage) {
        long delta = Math.abs(jump.nanoTime() - hit.nanoTime());
        long ticks =
            jump.clientTick() == null || hit.clientTick() == null
                ? Long.MAX_VALUE
                : Math.abs(jump.clientTick() - hit.clientTick());
        if (ticks < bestTicks || (ticks == bestTicks && delta < best)) {
          best = delta;
          bestTicks = ticks;
          nearest = hit;
        }
      }
      if (nearest == null
          || jump.clientTick() == null
          || nearest.clientTick() == null
          || bestTicks > 20) {
        out.add(new Sample(jump.sequence(), null, null, null, Grade.UNAVAILABLE));
        continue;
      }
      long offset = jump.clientTick() - nearest.clientTick();
      out.add(
          new Sample(
              jump.sequence(),
              nearest.sequence(),
              offset,
              (jump.nanoTime() - nearest.nanoTime()) / 1e6,
              offset == 0
                  ? Grade.IDEAL_CANDIDATE
                  : Math.abs(offset) <= window ? Grade.ATTEMPT_CANDIDATE : Grade.OUTSIDE_WINDOW));
    }
    return List.copyOf(out);
  }
}
