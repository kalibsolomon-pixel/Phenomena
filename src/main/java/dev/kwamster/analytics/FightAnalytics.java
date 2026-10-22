package dev.kwamster.analytics;

import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.metrics.Geometry;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.HitQuality.Grade;
import java.util.*;

/**
 * Timeline-derived values for one recorded fight. Built only from stored raw events and the
 * frozen evaluations, so every number can be traced back to a recorded observation. Correlation is
 * not repeated here: an attack is a hit exactly when the engine recorded an evaluation for it.
 *
 * <p>Instances are immutable and independent of Minecraft classes; the in-game GUI and the HTML
 * report both consume them.
 */
public final class FightAnalytics {
  public static final double REACH_BIN = .5, REACH_MAX = 6;
  public static final int REACH_BINS = (int) (REACH_MAX / REACH_BIN);
  public static final int COOLDOWN_BINS = 10;
  public static final int JUMP_OFFSET_LIMIT_MS = 1000;

  /** A correlated hit. Times are seconds since the first recorded event. */
  public record Hit(
      long attackSequence,
      double time,
      double evidenceTime,
      double reach,
      boolean rayContact,
      double aimHorizontal,
      double aimVertical,
      double cooldown,
      Grade readiness,
      boolean sprinting,
      boolean criticalConditions,
      int combo,
      double momentum) {}

  /**
   * An attempt without a correlated server report. Empty swings carry geometry only when a
   * plausible recent opponent was in range; uncertain attempts were aimed at an entity.
   */
  public record Miss(
      long sequence,
      double time,
      boolean empty,
      boolean geometry,
      double estimatedReach,
      boolean rayContact,
      double aimHorizontal,
      double aimVertical) {}

  /** Jump observed after incoming opponent damage; offset is jump time minus damage time. */
  public record Jump(double time, double offsetMillis, boolean candidate) {}

  public record Point(double time, double value) {}

  public final FightRecord fight;
  public final double duration;
  public final List<Hit> hits;
  public final List<Miss> misses;
  public final List<Double> intervals;
  public final List<Integer> comboLengths;
  public final List<Point> momentumChanges;
  public final List<Double> incomingTimes;
  public final List<Jump> jumps;
  public final int jumpsWithoutDamage, motionSamples, sprintTransitions;
  public final int[] readiness = new int[4];
  public final int[] hitReachBins = new int[REACH_BINS + 1];
  public final int[] missReachBins = new int[REACH_BINS + 1];
  public final int[] cooldownBins = new int[COOLDOWN_BINS];
  public final int center, edge, high, low;

  private FightAnalytics(FightRecord f) {
    this.fight = f;
    var events = f.events();
    long base = events.isEmpty() ? 0 : events.getFirst().nanoTime();
    duration = Math.max(f.durationNanos() / 1e9, 0);
    Map<Long, CombatEvent> bySequence = new HashMap<>();
    for (var e : events) bySequence.put(e.sequence(), e);

    List<Hit> hitList = new ArrayList<>();
    Set<Long> hitAttacks = new HashSet<>();
    int c = 0, ed = 0, hi = 0, lo = 0;
    for (var ev : f.evaluations()) {
      var attackEvent = bySequence.get(ev.attackSequence());
      var evidence = bySequence.get(ev.evidenceSequence());
      if (attackEvent == null || attackEvent.attack() == null) continue;
      var a = attackEvent.attack();
      hitAttacks.add(ev.attackSequence());
      double h = a.rayContact() ? Geometry.faceHorizontal(a.aimX(), a.aimZ()) : -1;
      hitList.add(
          new Hit(
              ev.attackSequence(),
              seconds(attackEvent.nanoTime(), base),
              evidence == null
                  ? seconds(attackEvent.nanoTime(), base)
                  : seconds(evidence.nanoTime(), base),
              a.reach(),
              a.rayContact(),
              h,
              a.rayContact() ? a.aimY() : -1,
              a.cooldown(),
              ev.readiness(),
              a.sprinting(),
              a.criticalConditions(),
              ev.combo(),
              ev.momentum()));
      if (ev.readiness() != null) readiness[ev.readiness().ordinal()]++;
      if (a.reach() >= 0) hitReachBins[reachBin(a.reach())]++;
      cooldownBins[Math.min(COOLDOWN_BINS - 1, Math.max(0, (int) (a.cooldown() * COOLDOWN_BINS)))]++;
      if (a.rayContact()) {
        switch (Geometry.placement(a.aimX(), a.aimY(), a.aimZ())) {
          case "Center" -> c++;
          case "Edge" -> ed++;
          case "High" -> hi++;
          default -> lo++;
        }
      }
    }
    center = c;
    edge = ed;
    high = hi;
    low = lo;
    hits = List.copyOf(hitList);

    List<Double> intervalList = new ArrayList<>();
    for (int i = 1; i < hits.size(); i++)
      intervalList.add(hits.get(i).time() - hits.get(i - 1).time());
    intervals = List.copyOf(intervalList);

    List<Integer> combos = new ArrayList<>();
    for (int i = 0; i < hits.size(); i++) {
      boolean last = i == hits.size() - 1 || hits.get(i + 1).combo() <= hits.get(i).combo();
      if (last) combos.add(hits.get(i).combo());
    }
    comboLengths = List.copyOf(combos);

    List<Miss> missList = new ArrayList<>();
    List<Double> incoming = new ArrayList<>();
    List<Jump> jumpList = new ArrayList<>();
    int noDamage = 0, motion = 0, sprint = 0;
    double lastDamage = Double.NaN;
    double window = f.policy().jumpWindowMillis();
    for (var e : events) {
      double t = seconds(e.nanoTime(), base);
      switch (e.kind()) {
        case ATTACK -> {
          if (hitAttacks.contains(e.sequence())) break;
          var a = e.attack();
          boolean empty = a == null || (e.detail() != null && e.detail().startsWith("empty-swing"));
          missList.add(
              new Miss(
                  e.sequence(),
                  t,
                  empty,
                  a != null,
                  a == null ? -1 : a.reach(),
                  a != null && a.rayContact(),
                  a != null && a.rayContact() ? Geometry.faceHorizontal(a.aimX(), a.aimZ()) : -1,
                  a != null && a.rayContact() ? a.aimY() : -1));
          if (a != null && a.reach() >= 0) missReachBins[reachBin(a.reach())]++;
        }
        case DAMAGE_IN -> {
          incoming.add(t);
          lastDamage = t;
        }
        case JUMP -> {
          if (Double.isNaN(lastDamage) || (t - lastDamage) * 1000 > JUMP_OFFSET_LIMIT_MS)
            noDamage++;
          else {
            double offset = (t - lastDamage) * 1000;
            jumpList.add(new Jump(t, offset, Math.abs(offset) <= window));
          }
        }
        case MOTION -> motion++;
        case SPRINT -> sprint++;
        default -> {}
      }
    }
    misses = List.copyOf(missList);
    incomingTimes = List.copyOf(incoming);
    jumps = List.copyOf(jumpList);
    jumpsWithoutDamage = noDamage;
    motionSamples = motion;
    sprintTransitions = sprint;
    momentumChanges = List.copyOf(momentum(hits, incomingTimes));
  }

  public static FightAnalytics of(FightRecord fight) {
    return new FightAnalytics(fight);
  }

  private static double seconds(long nanos, long base) {
    return (nanos - base) / 1e9;
  }

  public static int reachBin(double reach) {
    return Math.min(REACH_BINS, Math.max(0, (int) (reach / REACH_BIN)));
  }

  /**
   * Momentum change points, applying exactly the Statistics model: +hit on server evidence time,
   * -incoming on damage time, clipped to [-1, 1], exponential decay between changes. The value at
   * each hit equals the recorded evaluation momentum.
   */
  private static List<Point> momentum(List<Hit> hits, List<Double> incoming) {
    record Change(double time, boolean hit) {}
    List<Change> changes = new ArrayList<>();
    for (var h : hits) changes.add(new Change(h.evidenceTime(), true));
    for (var t : incoming) changes.add(new Change(t, false));
    changes.sort(Comparator.comparingDouble(Change::time));
    List<Point> points = new ArrayList<>();
    double value = 0, at = 0;
    for (var ch : changes) {
      double decayed = decay(value, ch.time() - at);
      value =
          ch.hit()
              ? Math.min(1, decayed + Statistics.MOMENTUM_HIT)
              : Math.max(-1, decayed - Statistics.MOMENTUM_INCOMING);
      at = ch.time();
      points.add(new Point(at, value));
    }
    return points;
  }

  private static double decay(double value, double seconds) {
    return value * Math.exp(-Math.max(0, seconds) * 1e9 / Statistics.MOMENTUM_DECAY_NANOS);
  }

  /** Estimated momentum at a time in seconds since fight start. */
  public double momentumAt(double time) {
    double value = 0, at = 0;
    for (var p : momentumChanges) {
      if (p.time() > time) break;
      value = p.value();
      at = p.time();
    }
    return decay(value, time - at);
  }

  /** Evenly sampled momentum curve for charts; computed once per call, cache the result. */
  public double[] momentumSamples(int count) {
    double[] out = new double[Math.max(2, count)];
    for (int i = 0; i < out.length; i++) out[i] = momentumAt(duration * i / (out.length - 1));
    return out;
  }

  public int longestCombo() {
    return comboLengths.stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  public double averageCombo() {
    return comboLengths.isEmpty()
        ? Double.NaN
        : comboLengths.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
  }
}
