package dev.phenomena.analytics;

import dev.phenomena.combat.fights.FightRecord;
import java.util.*;

/** Pure history filtering and sorting; operates on summaries only, never loads timelines. */
public final class HistoryQuery {
  public enum OutcomeFilter {
    ALL("All outcomes"),
    UNKNOWN("Unknown"),
    LOSS("Loss"),
    INTERRUPTED("Interrupted"),
    WIN("Win"),
    DRAW("Draw");

    public final String label;

    OutcomeFilter(String label) {
      this.label = label;
    }

    public boolean accepts(FightRecord f) {
      return this == ALL || f.outcome().name().equals(name());
    }
  }

  public enum Sort {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    HITS("Most hits"),
    ACCURACY("Accuracy"),
    REACH("Average reach"),
    DURATION("Duration");

    public final String label;

    Sort(String label) {
      this.label = label;
    }
  }

  private HistoryQuery() {}

  /** Returns a new list; ties fall back to newest first for a stable presentation. */
  public static List<FightRecord> apply(
      List<FightRecord> fights, OutcomeFilter filter, Sort sort, boolean withHitsOnly) {
    Comparator<FightRecord> newest =
        Comparator.comparingLong(FightRecord::startEpochMillis).reversed();
    Comparator<FightRecord> order =
        switch (sort) {
          case NEWEST -> newest;
          case OLDEST -> Comparator.comparingLong(FightRecord::startEpochMillis);
          case HITS -> Comparator.comparingInt((FightRecord f) -> f.statistics().hits).reversed();
          case ACCURACY -> descendingNaNLast(f -> Aggregate.trendValue(f, TrendMetric.ACCURACY));
          case REACH -> descendingNaNLast(f -> Aggregate.trendValue(f, TrendMetric.REACH));
          case DURATION -> Comparator.comparingLong(FightRecord::durationNanos).reversed();
        };
    List<FightRecord> out = new ArrayList<>();
    for (var f : fights)
      if (filter.accepts(f) && (!withHitsOnly || f.statistics().hits > 0)) out.add(f);
    out.sort(order.thenComparing(newest));
    return out;
  }

  private static Comparator<FightRecord> descendingNaNLast(
      java.util.function.ToDoubleFunction<FightRecord> key) {
    return (a, b) -> {
      double x = key.applyAsDouble(a), y = key.applyAsDouble(b);
      if (Double.isNaN(x) || Double.isNaN(y))
        return Boolean.compare(Double.isNaN(x), Double.isNaN(y));
      return Double.compare(y, x);
    };
  }
}
