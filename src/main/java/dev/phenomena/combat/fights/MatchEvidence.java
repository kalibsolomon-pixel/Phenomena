package dev.phenomena.combat.fights;

import dev.phenomena.combat.events.Confidence;
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
    Integer roundNumber,
    List<Round> rounds,
    String serverSessionId) {
  public MatchEvidence(
      String server,
      String localPlayer,
      String roundId,
      Confidence confidence,
      List<Entry> entries,
      Long timelineStartNanos,
      String matchId,
      Integer roundNumber,
      List<Round> rounds) {
    this(
        server,
        localPlayer,
        roundId,
        confidence,
        entries,
        timelineStartNanos,
        matchId,
        roundNumber,
        rounds,
        null);
  }

  /** An observed round, with independent outcome; null outcome means it was not announced. */
  public record Round(
      int number,
      long startNanos,
      Long endNanos,
      FightRecord.Outcome outcome,
      String rule,
      Confidence confidence) {}

  public MatchEvidence(
      String server,
      String localPlayer,
      String roundId,
      Confidence confidence,
      List<Entry> entries,
      Long timelineStartNanos,
      String matchId,
      Integer roundNumber) {
    this(
        server,
        localPlayer,
        roundId,
        confidence,
        entries,
        timelineStartNanos,
        matchId,
        roundNumber,
        List.of());
  }

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
    rounds = rounds == null ? List.of() : List.copyOf(rounds);
  }

  public static MatchEvidence unavailable() {
    return new MatchEvidence(null, null, null, Confidence.OBSERVED, List.of());
  }
}
