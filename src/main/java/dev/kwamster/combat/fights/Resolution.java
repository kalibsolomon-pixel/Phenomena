package dev.kwamster.combat.fights;

import dev.kwamster.combat.events.Confidence;
import dev.kwamster.combat.fights.FightRecord.Outcome;
import java.util.List;

/**
 * How a recording's result was established. The classification is kept separate from the evidence
 * source and its confidence, and names the observation that established it (a match-evidence entry
 * index and/or a combat event sequence). Recordings made before 0.1.3 receive a resolution derived
 * read-only from their stored outcome, marked {@code derived}.
 */
public record Resolution(
    Classification classification,
    Source source,
    Confidence confidence,
    Scope scope,
    Long establishedNanos,
    Integer evidenceIndex,
    Long eventSequence,
    List<String> notes,
    boolean derived) {
  public enum Classification {
    CONFIRMED_WIN("Confirmed server-declared win"),
    CONFIRMED_LOSS("Confirmed server-declared loss"),
    INFERRED_WIN("Inferred win"),
    INFERRED_LOSS("Inferred loss"),
    DRAW("Draw"),
    UNKNOWN("Unknown"),
    INTERRUPTED("Interrupted");

    public final String label;

    Classification(String label) {
      this.label = label;
    }

    public Outcome outcome() {
      return switch (this) {
        case CONFIRMED_WIN, INFERRED_WIN -> Outcome.WIN;
        case CONFIRMED_LOSS, INFERRED_LOSS -> Outcome.LOSS;
        case DRAW -> Outcome.DRAW;
        case UNKNOWN -> Outcome.UNKNOWN;
        case INTERRUPTED -> Outcome.INTERRUPTED;
      };
    }

    public static Classification of(Outcome outcome, boolean confirmed) {
      return switch (outcome) {
        case WIN -> confirmed ? CONFIRMED_WIN : INFERRED_WIN;
        case LOSS -> confirmed ? CONFIRMED_LOSS : INFERRED_LOSS;
        case DRAW -> DRAW;
        case UNKNOWN -> UNKNOWN;
        case INTERRUPTED -> INTERRUPTED;
      };
    }
  }

  /** The kind of observation that established the result. */
  public enum Source {
    SERVER_MATCH_RESULT("Server match result"),
    SERVER_ROUND_RESULT("Server round result"),
    SERVER_KILL_MESSAGE("Server kill message"),
    GENERIC_RESULT("Generic result text with endpoint"),
    LOCAL_DEATH("Local death observed"),
    OPPONENT_DEATH("Opponent death observed"),
    OPPONENT_REMOVED("Opponent left the client world"),
    THIRD_PARTY_KILL("Opponent killed by a third party"),
    CONFLICT("Contradictory evidence"),
    INACTIVITY("Combat inactivity"),
    DISCONNECT("Disconnect or world change"),
    TARGET_CHANGE("New target"),
    ROUND_BOUNDARY("New round announced"),
    EVENT_LIMIT("Event limit"),
    CLIENT("Client stopped or reset"),
    EXTERNAL("External finalization"),
    LEGACY("Recorded before 0.1.3");

    public final String label;

    Source(String label) {
      this.label = label;
    }
  }

  /** What the result describes: a whole match, one round of it, or a client-side engagement. */
  public enum Scope {
    MATCH,
    ROUND,
    ENGAGEMENT
  }

  public Resolution {
    notes = notes == null ? List.of() : List.copyOf(notes);
    if (classification == null) classification = Classification.UNKNOWN;
    if (source == null) source = Source.EXTERNAL;
    if (confidence == null) confidence = Confidence.OBSERVED;
    if (scope == null) scope = Scope.ENGAGEMENT;
  }

  public Outcome outcome() {
    return classification.outcome();
  }

  /** Read-only interpretation of a recording made before resolutions were stored. */
  public static Resolution derive(Outcome outcome, MatchEvidence evidence, String reason) {
    boolean confirmed =
        evidence != null
            && evidence.confidence() == Confidence.CONFIRMED
            && (outcome == Outcome.WIN || outcome == Outcome.LOSS);
    var c = Classification.of(outcome == null ? Outcome.UNKNOWN : outcome, confirmed);
    Confidence confidence =
        confirmed
            ? Confidence.CONFIRMED
            : outcome == Outcome.WIN || outcome == Outcome.LOSS
                ? Confidence.INFERRED
                : Confidence.OBSERVED;
    Integer index = null;
    if (evidence != null)
      for (int i = evidence.entries().size() - 1; i >= 0; i--)
        if (evidence.entries().get(i).accepted()
            && !"START".equals(evidence.entries().get(i).kind())
            && !"ROUND_START".equals(evidence.entries().get(i).kind())) {
          index = i;
          break;
        }
    return new Resolution(
        c,
        Source.LEGACY,
        confidence,
        evidence != null && evidence.roundId() != null ? Scope.MATCH : Scope.ENGAGEMENT,
        null,
        index,
        null,
        reason == null ? List.of() : List.of("Stored reason: " + reason),
        true);
  }
}
