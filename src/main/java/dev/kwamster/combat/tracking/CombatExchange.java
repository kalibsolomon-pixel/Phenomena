package dev.kwamster.combat.tracking;

import java.util.*;

/** Measurement time only. This class has no recording/finalization callback. */
public final class CombatExchange {
  public record Interval(long startNanos, long endNanos, String endedBy) {}

  private final List<Interval> closed = new ArrayList<>();
  private final List<Interval> view = Collections.unmodifiableList(closed);
  private long start = -1, last = -1;
  private double seconds;

  public CombatExchange(double seconds) {
    this.seconds = seconds;
  }

  public boolean active() {
    return start >= 0;
  }

  public List<Interval> closedIntervals() {
    return view;
  }

  public void timeout(double seconds) {
    this.seconds = seconds;
  }

  public long deadline() {
    return last + (long) (seconds * 1e9);
  }

  public boolean advance(long now) {
    if (active() && now >= deadline()) {
      close(deadline(), "INACTIVITY");
      return true;
    }
    return false;
  }

  public boolean interact(long now) {
    advance(now);
    boolean opened = !active();
    if (opened) start = now;
    last = now;
    return opened;
  }

  public void close(long now, String reason) {
    if (!active()) return;
    closed.add(new Interval(start, Math.max(start, Math.min(now, deadline())), reason));
    start = -1;
  }

  public List<Interval> snapshot(long now) {
    var copy = new ArrayList<>(closed);
    if (active())
      copy.add(new Interval(start, Math.max(start, Math.min(now, deadline())), "ACTIVE"));
    return List.copyOf(copy);
  }
}
