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
    String opponentLabel,
    MatchEvidence matchEvidence,
    Resolution resolution,
    SessionInfo session) {
  /** Schema 4 adds optional manual-session scopes; readers retain schemas 1�3. */
  public static final int SCHEMA = 4;

  /** Compatibility constructor for ordinary duels and historical callers. */
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
      List<Evaluation> evaluations,
      String opponentLabel,
      MatchEvidence matchEvidence,
      Resolution resolution) {
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
        opponentLabel,
        matchEvidence,
        resolution,
        null);
  }

  public boolean manual() {
    return session != null;
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
      List<Evaluation> evaluations,
      String opponentLabel,
      MatchEvidence matchEvidence) {
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
        opponentLabel,
        matchEvidence,
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
      List<Evaluation> evaluations,
      String opponentLabel) {
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
        opponentLabel,
        MatchEvidence.unavailable());
  }

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
      double greatCooldown,
      double perfectReachTarget,
      double perfectReachTolerance,
      double timingToleranceTicks,
      int jumpWindowTicks) {
    public Policy(
        double correlationMillis,
        double comboSeconds,
        double jumpWindowMillis,
        double perfectCooldown,
        double greatCooldown) {
      this(
          correlationMillis,
          comboSeconds,
          jumpWindowMillis,
          perfectCooldown,
          greatCooldown,
          3,
          .06,
          1,
          4);
    }

    public Policy {
      if (perfectReachTarget <= 0) {
        perfectReachTarget = 3;
        perfectReachTolerance = .06;
        timingToleranceTicks = 1;
        jumpWindowTicks = 4;
      }
    }

    public static Policy of(Config c) {
      return new Policy(
          c.correlationMillis,
          c.comboSeconds,
          c.jumpWindowMillis,
          c.perfectCooldown,
          c.greatCooldown,
          c.perfectReachTarget,
          c.perfectReachTolerance,
          c.timingToleranceTicks,
          c.jumpWindowTicks);
    }
  }

  /**
   * Frozen correlation of one attack with server evidence. {@code result} is null or HIT for a
   * confirmed hit (all recordings before 0.1.3), SHIELD_BLOCK when the opponent's shield block was
   * confirmed, and SHIELD_DISABLE when the block also disabled the shield.
   */
  public record Evaluation(
      long attackSequence,
      long evidenceSequence,
      Grade readiness,
      Confidence confidence,
      int combo,
      double momentum,
      String result) {
    public static final String HIT = "HIT",
        SHIELD_BLOCK = "SHIELD_BLOCK",
        SHIELD_DISABLE = "SHIELD_DISABLE";

    public Evaluation(
        long attackSequence,
        long evidenceSequence,
        Grade readiness,
        Confidence confidence,
        int combo,
        double momentum) {
      this(attackSequence, evidenceSequence, readiness, confidence, combo, momentum, null);
    }

    public boolean hit() {
      return result == null || HIT.equals(result);
    }

    public boolean shield() {
      return SHIELD_BLOCK.equals(result) || SHIELD_DISABLE.equals(result);
    }
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
        opponentLabel,
        matchEvidence,
        resolution,
        session == null
            ? null
            : new SessionInfo(
                session.startTick(),
                session.endTick(),
                session.world(),
                session.termination(),
                session.participants(),
                session.exchanges().stream().map(FightRecord::summaryOnly).toList(),
                session.announcements(),
                session.manualStartEpochMillis(),
                session.manualStartTick()));
  }

  public FightRecord {
    events = List.copyOf(events);
    if (policy == null) policy = Policy.of(new Config());
    evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    if (matchEvidence == null) matchEvidence = MatchEvidence.unavailable();
    if (outcome == null) outcome = Outcome.UNKNOWN;
    // Older recordings derive a read-only classification; stored files are never rewritten.
    if (resolution == null) resolution = Resolution.derive(outcome, matchEvidence, reason);
  }
}
