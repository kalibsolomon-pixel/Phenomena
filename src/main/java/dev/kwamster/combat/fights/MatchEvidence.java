package dev.kwamster.combat.fights;

import dev.kwamster.combat.events.Confidence;
import java.util.List;

/** Raw local evidence; export redacts text and server/player identity independently. */
public record MatchEvidence(
    String server,
    String localPlayer,
    String roundId,
    Confidence confidence,
    List<Entry> entries,
    Long timelineStartNanos,
    String matchId,
    Integer roundNumber) {
  public MatchEvidence(
      String server,
      String localPlayer,
      String roundId,
      Confidence confidence,
      List<Entry> entries,
      Long timelineStartNanos) {
    this(server, localPlayer, roundId, confidence, entries, timelineStartNanos, null, null);
  }

  public MatchEvidence(
      String server,
      String localPlayer,
      String roundId,
      Confidence confidence,
      List<Entry> entries) {
    this(server, localPlayer, roundId, confidence, entries, null);
  }

  public record Entry(
      long nanoTime, String kind, String rule, String origin, String text, boolean accepted) {}

  public MatchEvidence {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  public static MatchEvidence unavailable() {
    return new MatchEvidence(null, null, null, Confidence.OBSERVED, List.of());
  }
}
