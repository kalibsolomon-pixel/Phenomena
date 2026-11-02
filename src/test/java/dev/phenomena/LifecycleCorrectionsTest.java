package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.Config;
import dev.phenomena.recording.RecordingController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleCorrectionsTest {
  /**
   * Observed lifecycle times, redacted names. No combat data survived the original tiny files. This
   * is a lifecycle replay with the corrected WORLD_CHANGE mapping, not lost-hit recovery. Private
   * evidence: 4d79f1e0 entries 580/581/723/724, 881/882/1081/1082, 1271/1272/1483/1484 (zero
   * based); latest.log references in docs/0.1.6.md.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void observedPvphqEntryOrderKeepsCurrentMatch(int caseIndex) {
    long[] begins = {3874795322400L, 4316459041500L, 4578993034500L};
    long[] changes = {3874848359700L, 4316459679800L, 4578994416200L};
    long[] ends = {3969235466900L, 4408550267900L, 4693729573400L};
    var cfg = new Config();
    var records = new ArrayList<FightRecord>();
    var engine = new CombatEngine(cfg, records::add, (a, g) -> {});
    var controller =
        new RecordingController(cfg, engine, records::add, f -> {}, s -> {}, id -> null);
    controller.context("pvphq.com", "PlayerA", "lobby", begins[caseIndex] - 1, 1);
    controller.announcement(
        "pvphq.com",
        "PlayerA",
        Announcements.interpret("pvphq.com", "► Opponent: PlayerB", Announcements.Origin.SYSTEM)
            .orElseThrow(),
        begins[caseIndex]);
    controller.accept(
        new CombatEvent(
            1,
            changes[caseIndex],
            Kind.WORLD_CHANGE,
            "local",
            null,
            Confidence.OBSERVED,
            null,
            "world or dimension changed",
            2L));
    controller.context("pvphq.com", "PlayerA", "arena", changes[caseIndex] + 1, 2);
    assertTrue(engine.engaged());
    assertTrue(records.isEmpty());
    controller.announcement(
        "pvphq.com",
        "PlayerA",
        Announcements.interpret("pvphq.com", "🏆 MATCH REPORT 🏆", Announcements.Origin.SYSTEM)
            .orElseThrow(),
        ends[caseIndex] - 1);
    controller.announcement(
        "pvphq.com",
        "PlayerA",
        Announcements.interpret(
                "pvphq.com",
                caseIndex == 2
                    ? "► Winner: PlayerB (10.9 ❤) [Click]"
                    : "► Winner: PlayerA (18.3 ❤) [Click]",
                Announcements.Origin.SYSTEM)
            .orElseThrow(),
        ends[caseIndex]);
    assertEquals(1, records.size());
    assertTrue(records.getFirst().durationNanos() > 90_000_000_000L);
    assertEquals(
        caseIndex == 2 ? FightRecord.Outcome.LOSS : FightRecord.Outcome.WIN,
        records.getFirst().outcome());
    assertEquals(begins[caseIndex], records.getFirst().matchEvidence().timelineStartNanos());
  }

  @Test
  void oldDisconnectClassificationReproducesTinyRecording() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB");
    s.disconnect(.0530373);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.INTERRUPTED, s.last().outcome());
    assertEquals(53_037_300L, s.last().durationNanos());
  }

  @Test
  void actualConnectionTerminationStillInterrupts() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB").hit(1).disconnect(2);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.INTERRUPTED, s.last().outcome());
    assertEquals(Resolution.Source.DISCONNECT, s.last().resolution().source());
    assertTrue(s.last().events().stream().anyMatch(e -> e.kind() == Kind.DISCONNECT));
  }

  @Test
  void entrySignalIsCurrentMatchNotStalePreviousRecording() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB")
        .hit(1)
        .chat(2, "🏆 MATCH REPORT 🏆")
        .chat(2.1, "► Winner: PlayerA (2 ❤) [Click]");
    var previous = s.last();
    s.chat(2.2, "► Opponent: PlayerC");
    s.emit(2.201, Kind.WORLD_CHANGE, "local", null, Confidence.OBSERVED, null, "arena load", null);
    s.chat(3, "► Opponent: PlayerC").chat(4, "► Opponent: PlayerC");
    assertEquals(1, s.out.size());
    assertTrue(s.engine.engaged());
    s.chat(5, "🏆 MATCH REPORT 🏆").chat(5.1, "► Winner: PlayerA (2 ❤) [Click]");
    assertEquals(2, s.out.size());
    assertNotEquals(previous.matchEvidence().matchId(), s.last().matchEvidence().matchId());
    assertEquals(
        previous.matchEvidence().serverSessionId(), s.last().matchEvidence().serverSessionId());
  }

  @Test
  void repeatedQueuedStartDoesNotPostponeStaleMatchPreservation() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB").hit(1);
    s.chat(2, "► Opponent: PlayerC").chat(3, "► Opponent: PlayerC").tick(4.01);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.INTERRUPTED, s.last().outcome());
    assertTrue(s.engine.engaged());
    assertEquals("announced:PlayerC", s.engine.opponent());
  }

  @Test
  void mcpvpObservedFirstToTwoCompletionAndRoundReset() {
    var s = new Sim("mcpvp.club");
    s.title(0, "⚔ FIGHT! ⚔").hit(1);
    s.chat(
        2,
        "——————————————————————\n"
            + "🗡 PlayerA won the round! (1 - 0)\n\n"
            + "❤ 4/20 ʜᴘ\n"
            + "——————————————————————");
    assertTrue(s.engine.engaged());
    s.title(3, "⚔ FIGHT! ⚔").hit(4);
    s.chat(
        5,
        "——————————————————————\n"
            + "⚔ Match Complete ⚔\n\n"
            + "🗡 PlayerA won the round! (2 - 0)\n\n"
            + "❤ 2.0/20 ʜᴘ\n"
            + "——————————————————————");
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.WIN, s.last().outcome());
    assertEquals(2, s.last().matchEvidence().rounds().size());
  }

  @Test
  void mcpvpObservedXpArmorResults() {
    var s = new Sim("mcpvp.club");
    s.title(0, "⚔ FIGHT! ⚔").hit(1);
    s.chat(
        2,
        "——————————————————————\n"
            + "⚔ Match Complete ⚔\n\n"
            + "🗡 PlayerA won the round! (1 - 0)\n\n"
            + "⚗ 64 xᴘ | 🛡 470 ᴀʀᴍᴏʀ ᴀᴠɢ.\n"
            + "——————————————————————");
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.WIN, s.last().outcome());
  }

  @Test
  void resignationNamesCurrentParticipantAndNeverFabricatesWin() {
    var s = new Sim("mcpvp.club");
    s.title(0, "⚔ FIGHT! ⚔").hit(1).chat(2, "PlayerC resigned");
    s.tick(6);
    assertTrue(s.engine.engaged());
    s.chat(7, "PlayerB resigned").tick(10.1);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.UNKNOWN, s.last().outcome());
  }

  @Test
  void underscoredNamesSurviveSymbolPrefixParsing() {
    var m =
        Announcements.interpret(
                "pvphq.com", "🗡 _PlayerB has been killed by PlayerA.", Announcements.Origin.SYSTEM)
            .orElseThrow();
    assertEquals("_PlayerB", m.loser());
  }
}
