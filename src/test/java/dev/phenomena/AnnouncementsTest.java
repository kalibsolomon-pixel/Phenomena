package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.combat.events.*;
import dev.phenomena.combat.events.CombatEvent.*;
import dev.phenomena.combat.events.CombatEvent.Kind;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.combat.tracking.Announcements.*;
import dev.phenomena.config.Config;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnnouncementsTest {
  @Test
  void namedRoundCanFinalizeForfeitWithoutInventingCombat() {
    chat(0, START);
    assertTrue(engine.engaged());
    chat(2500, WIN);
    var f = records.getFirst();
    assertEquals(FightRecord.Outcome.WIN, f.outcome());
    assertTrue(f.events().isEmpty());
    assertEquals(0, f.statistics().attempts);
    assertEquals(0L, f.matchEvidence().timelineStartNanos());
  }

  List<FightRecord> records = new ArrayList<>();
  Config config = new Config();
  CombatEngine engine =
      new CombatEngine(
          config, records::add, (e, g) -> {}, id -> id.equals("B") ? "PlayerB" : "PlayerC");
  long seq;
  static final String HOST = "minemen.net";
  static final String START =
      "\nCasual Match\n ● Map: Village\n ● Opponent: PlayerB\n ● Ping: 47 ms\n";
  static final String WIN =
      "\nMatch Results (Click to view)\n 🏆 Winner: PlayerA  ▏  ☠ Loser: PlayerB\n";
  static final String LOSS =
      "\nMatch Results (Click to view)\n 🏆 Winner: PlayerB  ▏  ☠ Loser: PlayerA\n";

  void chat(long ms, String text) {
    chat(ms, text, Origin.SYSTEM);
  }

  void chat(long ms, String text, Origin origin) {
    Announcements.interpret(HOST, text, origin)
        .ifPresent(m -> engine.announcement(HOST, "PlayerA", m, ms * 1_000_000));
  }

  void event(long ms, Kind k, String target) {
    engine.accept(
        new CombatEvent(
            ++seq,
            ms * 1_000_000,
            k,
            "local",
            target,
            Confidence.OBSERVED,
            k == Kind.ATTACK ? new Attack(1, 3, .5, .5, 0, false, false, true) : null,
            "",
            ms / 50));
  }

  void begin() {
    chat(0, START);
    event(2100, Kind.ATTACK, "B");
  }

  @Test
  void observedPairWinsAndPreservesEvidence() {
    begin();
    chat(2500, WIN);
    assertEquals(FightRecord.Outcome.WIN, records.getFirst().outcome());
    assertEquals(Confidence.CONFIRMED, records.getFirst().matchEvidence().confidence());
    assertEquals(WIN, records.getFirst().matchEvidence().entries().getLast().text());
  }

  @Test
  void observedPairLoses() {
    begin();
    chat(2500, LOSS);
    assertEquals(FightRecord.Outcome.LOSS, records.getFirst().outcome());
  }

  @Test
  void duplicateResultsDoNotCreateFight() {
    begin();
    chat(2500, WIN);
    chat(2501, WIN);
    assertEquals(1, records.size());
  }

  @Test
  void duplicateStartsDoNotSplitFight() {
    chat(0, START);
    event(100, Kind.ATTACK, "B");
    chat(200, START);
    assertTrue(records.isEmpty());
  }

  @Test
  void wrongOpponentCannotResolve() {
    begin();
    chat(2500, WIN.replace("PlayerB", "PlayerC"));
    assertTrue(records.isEmpty());
  }

  @Test
  void unknownOriginCannotResolve() {
    begin();
    chat(2500, WIN, Origin.UNCERTAIN);
    assertTrue(records.isEmpty());
  }

  @Test
  void playerChatCannotResolve() {
    begin();
    chat(2500, WIN, Origin.PLAYER_CHAT);
    assertTrue(records.isEmpty());
  }

  @Test
  void disguisedChatCannotResolve() {
    begin();
    chat(2500, WIN, Origin.DISGUISED_CHAT);
    assertTrue(records.isEmpty());
  }

  @Test
  void shortDisengagementKeepsAnnouncedRound() {
    begin();
    engine.tick(12_000_000_000L);
    assertTrue(engine.engaged());
    chat(13000, WIN);
    assertEquals(1, records.size());
  }

  @Test
  void roundBoundarySplitsAndRejectsOldOpponent() {
    begin();
    chat(3000, START.replace("PlayerB", "PlayerC"));
    event(5100, Kind.ATTACK, "C");
    chat(5500, WIN);
    assertEquals(1, records.size());
    assertTrue(engine.engaged());
    chat(6000, WIN.replace("PlayerB", "PlayerC"));
    assertEquals(2, records.size());
  }

  @Test
  void delayedResultWithinGraceResolvesDeath() {
    begin();
    event(2200, Kind.DEATH, "B");
    chat(4000, WIN);
    assertEquals(FightRecord.Outcome.WIN, records.getFirst().outcome());
  }

  @Test
  void configuredResultHasNoCombatGraceExpiry() {
    begin();
    event(2200, Kind.DEATH, "B");
    engine.tick(600_000_000_000L);
    assertTrue(records.isEmpty());
    chat(610000, WIN);
    assertEquals(FightRecord.Outcome.WIN, records.getFirst().outcome());
  }

  @Test
  void deathCannotExpireConfiguredResult() {
    begin();
    event(2200, Kind.DEATH, "B");
    chat(6000, WIN);
    assertEquals(FightRecord.Outcome.WIN, records.getFirst().outcome());
  }

  @Test
  void unrelatedTargetDoesNotSplitAnnouncedDuel() {
    begin();
    event(2500, Kind.ATTACK, "C");
    assertTrue(records.isEmpty());
    assertEquals("B", engine.opponent());
  }

  @Test
  void genericEndCannotEndConfiguredMatch() {
    begin();
    chat(2500, "Round ended!");
    engine.tick(6_000_000_000L);
    assertTrue(records.isEmpty());
    assertTrue(engine.engaged());
  }

  @Test
  void genericYouWonCannotEndConfiguredMatch() {
    begin();
    chat(2400, "You Won");
    event(2500, Kind.DEATH, "B");
    chat(4500, "You Won!");
    engine.tick(600_000_000_000L);
    assertTrue(records.isEmpty());
    chat(610000, WIN);
    assertEquals(Confidence.CONFIRMED, records.getFirst().matchEvidence().confidence());
  }

  @Test
  void rematchAfterResolvedRoundIsConfirmed() {
    // Minemen sends the result, then the next Duel block one second later (owner logs).
    begin();
    chat(2500, WIN);
    chat(3500, START);
    event(5100, Kind.ATTACK, "B");
    chat(9000, LOSS);
    assertEquals(2, records.size());
    var second = records.getLast();
    assertEquals(FightRecord.Outcome.LOSS, second.outcome());
    assertEquals(Resolution.Classification.CONFIRMED_LOSS, second.resolution().classification());
    assertNotEquals(records.getFirst().matchEvidence().matchId(), second.matchEvidence().matchId());
  }

  @Test
  void duplicateStartCannotReplaceUnresolvedConfiguredMatch() {
    begin();
    engine.tick(70_000_000_000L);
    assertTrue(records.isEmpty());
    chat(71000, START);
    event(73100, Kind.ATTACK, "B");
    assertTrue(records.isEmpty(), "duplicate named start cannot replace unresolved match");
    chat(73500, WIN);
    assertEquals(1, records.size());
    assertEquals(Confidence.CONFIRMED, records.getFirst().matchEvidence().confidence());
  }

  @Test
  void serverProfileCannotMatchLookalikeDomain() {
    assertTrue(
        Announcements.interpret("minemen.net.attacker.invalid", WIN, Origin.SYSTEM).isEmpty());
  }

  @Test
  void pvphqObservedSingleWinnerAndStart() {
    assertEquals(
        "PlayerB",
        Announcements.interpret("play.chi-us.pvphq.com", "► Opponent: PlayerB", Origin.SYSTEM)
            .orElseThrow()
            .opponent());
    var m =
        Announcements.interpret("pvphq.com", "► Winner: PlayerA (7.9 ❤) [Click]", Origin.SYSTEM)
            .orElseThrow();
    assertEquals("PlayerA", m.winner());
    assertNull(m.loser());
  }

  @Test
  void observedKillNamesAreOrdered() {
    var m =
        Announcements.interpret(HOST, "PlayerB was killed by PlayerA!", Origin.SYSTEM)
            .orElseThrow();
    assertEquals("PlayerA", m.winner());
    assertEquals("PlayerB", m.loser());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PlayerA: You Won",
        "[VIP] PlayerA: Winner: PlayerA",
        "PlayerA: Round 1 started!",
        "PlayerA: server starts in two days",
        "Tournament Starting: 00:15",
        "Progress Win Streak: 10",
        "Winner: SomebodyElse [other text]"
      })
  void systemRelayedPlayerChatAndUnrelatedTextIgnored(String text) {
    assertTrue(Announcements.interpret(HOST, text, Origin.SYSTEM).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Winner: PlayerA",
        "PlayerA Won",
        "You Won",
        "Loser: PlayerA",
        "PlayerA Lost",
        "You Lost",
        "Round 2 started!"
      })
  void explicitGenericFormsSupportedWithoutInventingObservedEvidence(String text) {
    assertTrue(Announcements.interpret("unknown.example", text, Origin.SYSTEM).isPresent());
  }
}
