package dev.kwamster.combat.fights;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.config.Config;
import java.util.List;

public record FightRecord(
    int schemaVersion,
    String id,
    long startEpochMillis,
    long endEpochMillis,
    long durationNanos,
    String opponent,
    Outcome outcome,
    String reason,
    Statistics statistics,
    List<CombatEvent> events,
    Policy policy,
    List<Evaluation> evaluations,
    String opponentLabel) {
  public enum Outcome {
    WIN,
    LOSS,
    DRAW,
    INTERRUPTED,
    UNKNOWN
  }

  public record Policy(
      double correlationMillis,
      double comboSeconds,
      double jumpWindowMillis,
      double perfectCooldown,
      double greatCooldown) {
    public static Policy of(Config c) {
      return new Policy(
          c.correlationMillis,
          c.comboSeconds,
          c.jumpWindowMillis,
          c.perfectCooldown,
          c.greatCooldown);
    }
  }

  public record Evaluation(
      long attackSequence,
      long evidenceSequence,
      Grade readiness,
      Confidence confidence,
      int combo,
      double momentum) {}

  public FightRecord(
      int schemaVersion,
      String id,
      long startEpochMillis,
      long endEpochMillis,
      long durationNanos,
      String opponent,
      Outcome outcome,
      String reason,
      Statistics statistics,
      List<CombatEvent> events) {
    this(
        schemaVersion,
        id,
        startEpochMillis,
        endEpochMillis,
        durationNanos,
        opponent,
        outcome,
        reason,
        statistics,
        events,
        Policy.of(new Config()),
        List.of(),
        null);
  }

  public FightRecord(
      int schemaVersion,
      String id,
      long startEpochMillis,
      long endEpochMillis,
      long durationNanos,
      String opponent,
      Outcome outcome,
      String reason,
      Statistics statistics,
      List<CombatEvent> events,
      Policy policy,
      List<Evaluation> evaluations) {
    this(
        schemaVersion,
        id,
        startEpochMillis,
        endEpochMillis,
        durationNanos,
        opponent,
        outcome,
        reason,
        statistics,
        events,
        policy,
        evaluations,
        null);
  }

  /** Timeline-free copy kept in bounded memory; the full record loads on demand. */
  public boolean hasTimeline() {
    return !events.isEmpty();
  }

  public FightRecord summaryOnly() {
    return new FightRecord(
        schemaVersion,
        id,
        startEpochMillis,
        endEpochMillis,
        durationNanos,
        opponent,
        outcome,
        reason,
        statistics,
        List.of(),
        policy,
        List.of(),
        opponentLabel);
  }

  public FightRecord {
    events = List.copyOf(events);
    if (policy == null) policy = Policy.of(new Config());
    evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
  }
}
