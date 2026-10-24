package dev.kwamster.combat.fights;

import java.util.List;

/** A user-controlled recording, with independent single-opponent measurement scopes. */
public record SessionInfo(
    long startTick,
    long endTick,
    String world,
    String termination,
    List<Participant> participants,
    List<FightRecord> exchanges,
    List<MatchEvidence.Entry> announcements,
    Long manualStartEpochMillis,
    Long manualStartTick) {
  public SessionInfo(
      long startTick,
      long endTick,
      String world,
      String termination,
      List<Participant> participants,
      List<FightRecord> exchanges,
      List<MatchEvidence.Entry> announcements) {
    this(
        startTick, endTick, world, termination, participants, exchanges, announcements, null, null);
  }

  public record Participant(String id, String label) {}

  public SessionInfo {
    participants = List.copyOf(participants);
    exchanges = List.copyOf(exchanges);
    announcements = List.copyOf(announcements);
  }
}
