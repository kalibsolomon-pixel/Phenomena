package dev.kwamster.combat.metrics;

import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord.Evaluation;
import dev.kwamster.combat.fights.MatchEvidence;
import java.util.*;

/**
 * Combo and exchange structure from confirmed observations only.
 *
 * <ul>
 *   <li>A local mark is a frozen hit evaluation, placed at its server evidence time. An opponent
 *       mark is an incoming damage event the server attributed to the tracked opponent; duplicate
 *       observations in one client tick count once.
 *   <li>A streak is a maximal run of one side's marks. It ends on a confirmed hit by the other side
 *       (retaliation), on a same-tick trade, on disengagement (no hit by anyone for {@code gap}
 *       seconds) or at the end of the recording. A combo is a streak of at least two hits.
 *   <li>Same-tick hits from both sides cannot be ordered reliably. They are marked as a trade, end
 *       both streaks and extend neither, so ordering noise cannot manufacture a combo.
 *   <li>Unattributed incoming damage, third-party damage, shield blocks, knockback and empty swings
 *       are not marks.
 *   <li>An exchange is a run of marks without a disengagement gap.
 * </ul>
 */
public final class Combos {
  public enum Side {
    LOCAL,
    OPPONENT
  }

  public enum End {
    RETALIATION,
    TRADE,
    DISENGAGE,
    RECORDING_END,
    ROUND_BOUNDARY
  }

  public record Mark(long sequence, long nanos, Long tick, Side side, boolean traded) {}

  public record Streak(
      Side side, int length, long startNanos, long endNanos, List<Long> sequences, End endedBy) {
    public boolean combo() {
      return length >= 2;
    }
  }

  public record Exchange(
      long startNanos,
      long endNanos,
      int localHits,
      int opponentHits,
      int longestLocal,
      int longestOpponent) {
    public int advantage() {
      return localHits - opponentHits;
    }
  }

  public record Result(
      List<Mark> marks,
      List<Streak> streaks,
      List<Exchange> exchanges,
      int longestUnansweredLocal,
      int longestUnansweredOpponent) {
    public List<Streak> combos(Side side) {
      return streaks.stream().filter(s -> s.side() == side && s.combo()).toList();
    }

    public int longest(Side side) {
      return streaks.stream()
          .filter(s -> s.side() == side)
          .mapToInt(Streak::length)
          .max()
          .orElse(0);
    }

    public int comboHits(Side side) {
      return combos(side).stream().mapToInt(Streak::length).sum();
    }

    public double averageCombo(Side side) {
      return combos(side).stream().mapToInt(Streak::length).average().orElse(Double.NaN);
    }
  }

  private Combos() {}

  public static Result analyze(
      List<CombatEvent> events, List<Evaluation> evaluations, String opponent, double gapSeconds) {
    return analyze(events, evaluations, opponent, gapSeconds, List.of());
  }

  public static Result analyze(
      List<CombatEvent> events,
      List<Evaluation> evaluations,
      String opponent,
      double gapSeconds,
      List<MatchEvidence.Round> rounds) {
    long[] boundaries =
        rounds.stream()
            .flatMapToLong(
                r ->
                    r.endNanos() == null
                        ? java.util.stream.LongStream.of(r.startNanos())
                        : java.util.stream.LongStream.of(r.startNanos(), r.endNanos()))
            .distinct()
            .sorted()
            .toArray();
    List<Mark> raw = new ArrayList<>(evaluations.size() + 16);
    for (var ev : evaluations) {
      if (!ev.hit()) continue;
      var evidence = find(events, ev.evidenceSequence());
      var attack = evidence != null ? null : find(events, ev.attackSequence());
      var at = evidence != null ? evidence : attack;
      if (at == null) continue;
      raw.add(new Mark(ev.attackSequence(), at.nanoTime(), at.clientTick(), Side.LOCAL, false));
    }
    Set<Long> opponentTicks = new HashSet<>();
    for (var e : events) {
      if (!e.attributedTo(opponent)) continue;
      if (e.clientTick() != null && !opponentTicks.add(e.clientTick())) continue;
      raw.add(new Mark(e.sequence(), e.nanoTime(), e.clientTick(), Side.OPPONENT, false));
    }
    raw.sort(Comparator.comparingLong(Mark::nanos).thenComparingLong(Mark::sequence));
    Set<Long> localTicks = new HashSet<>();
    for (var m : raw) if (m.side() == Side.LOCAL && m.tick() != null) localTicks.add(m.tick());
    List<Mark> marks = new ArrayList<>(raw.size());
    for (var m : raw) {
      boolean traded =
          m.tick() != null
              && (m.side() == Side.LOCAL
                  ? opponentTicks.contains(m.tick())
                  : localTicks.contains(m.tick()));
      marks.add(traded ? new Mark(m.sequence(), m.nanos(), m.tick(), m.side(), true) : m);
    }

    long gap = (long) (gapSeconds * 1e9);
    List<Streak> streaks = new ArrayList<>();
    List<Exchange> exchanges = new ArrayList<>();
    Side side = null;
    List<Long> current = new ArrayList<>();
    long streakStart = 0, streakEnd = 0;
    long exStart = -1, exEnd = 0, last = Long.MIN_VALUE;
    int exLocal = 0, exOpp = 0, exLongLocal = 0, exLongOpp = 0;
    int run = 0, longestLocal = 0, longestOpp = 0;
    Side runSide = null;
    int boundary = 0;
    for (var m : marks) {
      boolean roundBoundary = false;
      while (boundary < boundaries.length && boundaries[boundary] <= m.nanos()) {
        if (last != Long.MIN_VALUE && boundaries[boundary] > last) roundBoundary = true;
        boundary++;
      }
      if (roundBoundary) {
        run = 0;
        runSide = null;
      }
      if (last != Long.MIN_VALUE && (roundBoundary || m.nanos() - last > gap)) {
        if (side != null)
          streaks.add(
              streak(
                  side,
                  current,
                  streakStart,
                  streakEnd,
                  roundBoundary ? End.ROUND_BOUNDARY : End.DISENGAGE));
        exLongLocal = Math.max(exLongLocal, side == Side.LOCAL ? current.size() : 0);
        exLongOpp = Math.max(exLongOpp, side == Side.OPPONENT ? current.size() : 0);
        exchanges.add(new Exchange(exStart, exEnd, exLocal, exOpp, exLongLocal, exLongOpp));
        side = null;
        current = new ArrayList<>();
        exStart = -1;
        exLocal = exOpp = exLongLocal = exLongOpp = 0;
      }
      if (exStart < 0) exStart = m.nanos();
      exEnd = m.nanos();
      last = m.nanos();
      if (m.side() == Side.LOCAL) exLocal++;
      else exOpp++;
      // Unanswered runs ignore disengagement; only the other side's confirmed hit ends them.
      if (m.traded()) {
        run = 0;
        runSide = null;
      } else if (runSide == m.side()) run++;
      else {
        runSide = m.side();
        run = 1;
      }
      if (runSide == Side.LOCAL) longestLocal = Math.max(longestLocal, run);
      if (runSide == Side.OPPONENT) longestOpp = Math.max(longestOpp, run);

      if (m.traded()) {
        if (side != null) {
          streaks.add(streak(side, current, streakStart, streakEnd, End.TRADE));
          if (side == Side.LOCAL) exLongLocal = Math.max(exLongLocal, current.size());
          else exLongOpp = Math.max(exLongOpp, current.size());
        }
        side = null;
        current = new ArrayList<>();
        continue;
      }
      if (side != null && side != m.side()) {
        streaks.add(streak(side, current, streakStart, streakEnd, End.RETALIATION));
        if (side == Side.LOCAL) exLongLocal = Math.max(exLongLocal, current.size());
        else exLongOpp = Math.max(exLongOpp, current.size());
        current = new ArrayList<>();
        side = null;
      }
      if (side == null) {
        side = m.side();
        streakStart = m.nanos();
      }
      current.add(m.sequence());
      streakEnd = m.nanos();
    }
    if (side != null) {
      streaks.add(streak(side, current, streakStart, streakEnd, End.RECORDING_END));
      if (side == Side.LOCAL) exLongLocal = Math.max(exLongLocal, current.size());
      else exLongOpp = Math.max(exLongOpp, current.size());
    }
    if (exStart >= 0)
      exchanges.add(new Exchange(exStart, exEnd, exLocal, exOpp, exLongLocal, exLongOpp));
    return new Result(
        List.copyOf(marks), List.copyOf(streaks), List.copyOf(exchanges), longestLocal, longestOpp);
  }

  /** Binary search by sequence; recorded events are strictly increasing in sequence. */
  static CombatEvent find(List<CombatEvent> events, long sequence) {
    int lo = 0, hi = events.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      long s = events.get(mid).sequence();
      if (s < sequence) lo = mid + 1;
      else if (s > sequence) hi = mid - 1;
      else return events.get(mid);
    }
    return null;
  }

  private static Streak streak(Side side, List<Long> seqs, long start, long end, End why) {
    return new Streak(side, seqs.size(), start, end, List.copyOf(seqs), why);
  }
}
