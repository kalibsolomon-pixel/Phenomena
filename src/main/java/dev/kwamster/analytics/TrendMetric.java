package dev.kwamster.analytics;

/** Per-fight trend metrics shared by the Overview chart and documentation. */
public enum TrendMetric {
  ACCURACY("Accuracy", "%", 0, 100),
  REACH("Average reach", "m", 0, 6),
  CRITICAL("Critical-hit rate", "%", 0, 100),
  TIMING("Hit interval", "s", 0, Double.NaN);

  public final String label, unit;

  /** Fixed axis bounds; a NaN maximum follows the data. */
  public final double min, max;

  TrendMetric(String label, String unit, double min, double max) {
    this.label = label;
    this.unit = unit;
    this.min = min;
    this.max = max;
  }

  public String shortLabel() {
    return switch (this) {
      case ACCURACY -> "Accuracy";
      case REACH -> "Reach";
      case CRITICAL -> "Crit rate";
      case TIMING -> "Timing";
    };
  }
}
