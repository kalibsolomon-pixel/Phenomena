package dev.kwamster.analytics;

import dev.kwamster.combat.fights.FightRecord;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared display formatting. Missing values always render as a dash, never as zero. */
public final class Format {
  public static final String NONE = "-";
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
  private static final DateTimeFormatter SHORT =
      DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

  private Format() {}

  public static String percent(double v) {
    return Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f%%", v) : NONE;
  }

  public static String meters(double v) {
    return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f m", v) : NONE;
  }

  public static String seconds(double v) {
    return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f s", v) : NONE;
  }

  public static String number(double v, int decimals) {
    return Double.isFinite(v) ? String.format(Locale.ROOT, "%." + decimals + "f", v) : NONE;
  }

  public static String duration(long nanos) {
    double s = nanos / 1e9;
    if (s < 60) return String.format(Locale.ROOT, "%.1fs", s);
    long whole = Math.round(s);
    return String.format(Locale.ROOT, "%dm %02ds", whole / 60, whole % 60);
  }

  public static String dateTime(long epochMillis) {
    return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
  }

  public static String shortDate(long epochMillis) {
    return SHORT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
  }

  public static String value(double v, String unit) {
    return switch (unit) {
      case "%" -> percent(v);
      case "m" -> meters(v);
      case "s" -> seconds(v);
      default -> number(v, 2);
    };
  }

  /** Short opponent text for lists: label when known, otherwise a truncated entity UUID. */
  public static String opponent(String label, String uuid) {
    if (label != null && !label.isBlank()) return label;
    if (uuid == null || uuid.isBlank()) return "Unknown";
    return "Entity " + (uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
  }

  /** Short list label: the result with its evidence strength, e.g. "Win (confirmed)". */
  public static String result(dev.kwamster.combat.fights.Resolution r) {
    return switch (r.classification()) {
      case CONFIRMED_WIN -> "Win (confirmed)";
      case CONFIRMED_LOSS -> "Loss (confirmed)";
      case INFERRED_WIN -> "Win (inferred)";
      case INFERRED_LOSS -> "Loss (inferred)";
      case DRAW -> "Draw";
      case UNKNOWN -> "Unknown";
      case INTERRUPTED -> "Interrupted";
    };
  }

  public static String outcome(FightRecord.Outcome o) {
    return switch (o) {
      case WIN -> "Win";
      case LOSS -> "Loss";
      case DRAW -> "Draw";
      case INTERRUPTED -> "Interrupted";
      case UNKNOWN -> "Unknown";
    };
  }
}
