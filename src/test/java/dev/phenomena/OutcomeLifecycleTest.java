package dev.phenomena;

import static dev.phenomena.Sim.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.combat.events.Confidence;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.fights.Resolution.Classification;
import dev.phenomena.combat.fights.Resolution.Scope;
import dev.phenomena.combat.fights.Resolution.Source;
import dev.phenomena.combat.tracking.Announcements;
import dev.phenomena.combat.tracking.Announcements.Origin;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Match lifecycle and result classification with the observed server formats. */
class OutcomeLifecycleTest {
  private static Sim duel() {
    var s = new Sim();
    s.chat(0, MM_START);
    s.hit(3).hit(4);
    return s;
  }

  @Test
  void minemenClubHostIsSupported() {
    assertTrue(
        Announcements.interpret("minemen.club", MM_WIN, Origin.SYSTEM).orElseThrow().trusted());
    assertTrue(
        Announcements.interpret("eu.minemen.club:25565", MM_START, Origin.SYSTEM).isPresent());
    assertTrue(
        Announcements.interpret("minemen.club.evil.example", MM_WIN, Origin.SYSTEM).isEmpty());
  }

  @Test
  void duelStartBlocksForEveryObservedMode() {
    for (String mode :
        new String[] {
          "Sword Duel", "Bow Duel", "Diamond Potion Duel", "Casual Match", "Stick Fight Duel"
        }) {
      var m =
          Announcements.interpret(
                  "minemen.club",
                  "\n" + mode + "\n ● Map: Rome\n ● Opponent: PlayerB\n ● Ping: 9 ms\n",
                  Origin.SYSTEM)
              .orElseThrow();
      assertEquals(Announcements.Kind.START, m.kind());
      assertEquals("PlayerB", m.opponent());
    }
    assertTrue(
        Announcements.interpret(
                "minemen.club",
                "\nDuel Sent\n ● To: PlayerB\n ● Mode: Diamond SMP\n",
                Origin.SYSTEM)
            .isEmpty(),
        "an outgoing duel request is not a start");
  }

  @Test
  void pairedServerResultIsConfirmedAndNamesItsEvidence() {
    var s = duel();
    s.chat(5, MM_WIN);
    var r = s.last().resolution();
    assertEquals(Classification.CONFIRMED_WIN, r.classification());
    assertEquals(Source.SERVER_MATCH_RESULT, r.source());
    assertEquals(Confidence.CONFIRMED, r.confidence());
    assertEquals(Scope.MATCH, r.scope());
    var entry = s.last().matchEvidence().entries().get(r.evidenceIndex());
    assertEquals("RESULT", entry.kind());
    assertTrue(entry.accepted());
  }

  @Test
  void pvphqOneNameWinnerInsideNamedRoundIsConfirmed() {
    var s = new Sim();
    s.host = "pvphq.com:25565";
    s.chat(0, "⚔ UNRANKED MATCH ⚔").chat(0, "► Opponent: PlayerB");
    s.hit(3).hit(4);
    s.chat(5, "🗡 PlayerB has been killed by PlayerA.");
    s.chat(5.01, "🏆 MATCH REPORT 🏆");
    s.chat(5.02, "► Winner: PlayerA (7.9 ❤) [Click]");
    s.chat(5.03, "► Loser: PlayerB [Click]");
    assertEquals(1, s.out.size(), "finalized exactly once");
    var r = s.last().resolution();
    assertEquals(Classification.CONFIRMED_WIN, r.classification());
    assertEquals(Scope.MATCH, r.scope());
  }

  @Test
  void pvphqBestOfRoundLinesAreRoundResultsUntilMatchReport() {
    var s = new Sim();
    s.host = "pvphq.com";
    s.chat(0, "► Opponent: PlayerB");
    s.hit(3);
    s.chat(5, "🗡 PlayerB has been killed by PlayerA.");
    s.chat(5, "► Winner: PlayerA (5.3 ❤, 10/18 ⚗) [Click]");
    s.chat(5, "► Loser: PlayerB (0 ❤, 11/18 ⚗) [Click]");
    s.tick(14);
    assertTrue(s.out.isEmpty());
    assertTrue(s.engine.engaged());
    s.hit(20).incoming(21);
    s.chat(24, "🗡 PlayerA has been killed by PlayerB.");
    s.chat(24.1, "🏆 MATCH REPORT 🏆");
    s.chat(24.2, "► Winner: PlayerB (6 ❤, 18/18 ⚗) [Click]");
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.WIN, s.last().matchEvidence().rounds().getFirst().outcome());
    assertEquals(Classification.CONFIRMED_LOSS, s.last().resolution().classification());
    assertEquals(Scope.MATCH, s.last().resolution().scope());
  }

  @Test
  void minemenBestOfRoundsThenMatchResultUpgradesFinalRound() {
    var s = new Sim();
    s.chat(0, MM_START);
    s.chat(1, "Round 1 started!");
    s.hit(3.5);
    s.chat(6, "PlayerB was killed by PlayerA!");
    s.chat(6, "\nPlayerA (8.5❤) won!\n 1 - 0\n");
    s.chat(12, "Round 2 started!");
    assertTrue(s.out.isEmpty());
    assertTrue(s.engine.engaged());
    s.hit(15);
    s.chat(18, "PlayerB was killed by PlayerA!");
    s.chat(18, "\nPlayerA won!\n 2 - 0\n");
    s.chat(18.5, MM_WIN);
    assertEquals(1, s.out.size());
    assertEquals(2, s.last().matchEvidence().rounds().size());
    assertEquals(Scope.MATCH, s.last().resolution().scope());
    assertEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
  }

  @Test
  void roundScoreMustMatchRoundNumber() {
    var s = new Sim();
    s.chat(0, MM_START);
    s.chat(1, "Round 1 started!");
    s.hit(3.5);
    s.chat(6, "\nPlayerA won!\n 2 - 0\n"); // score claims round 2
    s.tick(100);
    assertTrue(s.out.isEmpty());
    s.engine.finish(ns(101), FightRecord.Outcome.INTERRUPTED, "Client stopped");
    assertNotEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
    assertTrue(s.last().resolution().notes().stream().anyMatch(n -> n.contains("does not match")));
  }

  @Test
  void killMessageCannotEndConfiguredMatch() {
    var s = duel();
    s.chat(5, "PlayerB was killed by PlayerA!").tick(1000);
    assertTrue(s.out.isEmpty());
    s.chat(1001, MM_WIN);
    assertEquals(1, s.out.size());
    assertEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
  }

  @Test
  void thirdPartyKillNeverBecomesAWin() {
    var s = duel();
    s.chat(5, "PlayerB was killed by PlayerC!").tick(1000);
    assertTrue(s.out.isEmpty());
    s.engine.finish(ns(1001), FightRecord.Outcome.INTERRUPTED, "Client stopped");
    assertEquals(Classification.INTERRUPTED, s.last().resolution().classification());
  }

  @Test
  void thirdPartyDeathCannotClassifyConfiguredMatch() {
    var s = duel();
    s.chat(5, "PlayerA was killed by PlayerC!").tick(1000);
    assertTrue(s.out.isEmpty());
    s.chat(1001, MM_WIN);
    assertEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
  }

  @Test
  void contradictoryKillsCannotOverrideMatchResult() {
    var s = duel();
    s.chat(5, "PlayerB was killed by PlayerA!")
        .chat(5.5, "PlayerA was killed by PlayerB!")
        .tick(1000);
    assertTrue(s.out.isEmpty(), "kill observations cannot classify the match");
    s.chat(1001, MM_WIN);
    assertEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
  }

  @Test
  void delayedResultAfterNextStartBelongsToPreviousRound() {
    var s = duel();
    s.chat(10, MM_START.replace("PlayerB", "PlayerC")); // next match announced first
    s.chat(10.5, MM_WIN); // late result for the PlayerB round
    assertEquals(Classification.CONFIRMED_WIN, s.out.getFirst().resolution().classification());
    assertEquals("PlayerB", s.out.getFirst().opponentLabel());
    assertTrue(s.engine.engaged(), "the queued round started");
  }

  @Test
  void deferredRoundStartsAtItsAnnouncementWhenNoResultArrives() {
    var s = duel();
    s.chat(10, MM_START.replace("PlayerB", "PlayerC"));
    s.tick(12.5);
    assertEquals(1, s.out.size());
    assertEquals(Classification.INTERRUPTED, s.out.getFirst().resolution().classification());
    assertEquals(Source.ROUND_BOUNDARY, s.out.getFirst().resolution().source());
    assertTrue(s.engine.acceptsTarget("C", "PlayerC"));
  }

  @Test
  void disconnectPreservesInterruptedAndLateResultCannotRewrite() {
    var s = duel();
    s.disconnect(5).chat(6, MM_WIN).tick(9);
    assertEquals(1, s.out.size());
    assertEquals(Classification.INTERRUPTED, s.last().resolution().classification());
    assertFalse(s.engine.engaged());
  }

  @Test
  void forfeitDuringCountdownIsAccepted() {
    var s = new Sim();
    s.chat(0, MM_START);
    s.chat(1.5, MM_WIN);
    assertEquals(Classification.CONFIRMED_WIN, s.last().resolution().classification());
    assertTrue(s.last().events().isEmpty(), "no combat statistics invented");
  }

  @Test
  void spectatorSuffixDoesNotBreakResult() {
    var m =
        Announcements.interpret(
                "minemen.club",
                "\n"
                    + "Match Results (Click to view)\n"
                    + " 🏆 Winner: PlayerB  ▏  ☠ Loser: PlayerA\n\n"
                    + " Spectators (1): Someone",
                Origin.SYSTEM)
            .orElseThrow();
    assertEquals("PlayerB", m.winner());
  }

  @Test
  void teleportsAndTitlesDoNotEndOrResolve() {
    var s = duel();
    s.teleport(5);
    s.title(5.5, "VICTORY!");
    assertTrue(s.engine.engaged());
    s.chat(8, MM_LOSS);
    var f = s.last();
    assertEquals(Classification.CONFIRMED_LOSS, f.resolution().classification());
    assertTrue(
        f.matchEvidence().entries().stream()
            .anyMatch(e -> e.kind().equals("NOTE") && !e.accepted()));
  }

  @Test
  void configuredDeathWaitsWhileGenericDeathRemainsConservative() {
    var s = duel();
    s.death(5, "local").tick(1000);
    assertTrue(s.out.isEmpty());
    var t = new Sim("unconfigured.test");
    t.hit(1).hit(2).death(3, B).tick(7);
    assertEquals(Classification.UNKNOWN, t.last().resolution().classification());
    assertEquals(Source.OPPONENT_DEATH, t.last().resolution().source());
  }

  @Test
  void finalizationHappensOnce() {
    var s = duel();
    s.chat(5, MM_WIN);
    s.engine.finish(ns(6), FightRecord.Outcome.INTERRUPTED, "again");
    s.chat(6.5, MM_WIN.replace("\n", " "));
    s.tick(100);
    assertEquals(1, s.out.size());
  }

  @Test
  void shutdownDoesNotPromoteKillToMatchVictory() {
    var s = duel();
    s.chat(5, "PlayerB was killed by PlayerA!");
    s.engine.finish(ns(5.5), FightRecord.Outcome.INTERRUPTED, "Client stopped");
    assertEquals(Classification.INTERRUPTED, s.last().resolution().classification());
  }

  @Test
  void opponentGoneThenNewTargetIsUnknownNotInterrupted() {
    var s = new Sim("unconfigured.test");
    s.hit(1);
    s.emit(
        2,
        dev.phenomena.combat.events.CombatEvent.Kind.REMOVED,
        null,
        B,
        Confidence.OBSERVED,
        null,
        "removed",
        null);
    s.attack(3, C, 3, false, false);
    assertEquals(Source.OPPONENT_REMOVED, s.out.getFirst().resolution().source());
    var t = new Sim("unconfigured.test");
    t.hit(1).attack(2, C, 3, false, false);
    assertEquals(Classification.INTERRUPTED, t.out.getFirst().resolution().classification());
  }

  @Test
  void legacyRecordingsDeriveResolutionReadOnly() {
    var legacy =
        new FightRecord(
            2,
            UUID.randomUUID().toString(),
            0,
            1,
            1,
            "x",
            FightRecord.Outcome.LOSS,
            "Local death",
            new dev.phenomena.combat.metrics.Statistics(),
            List.of());
    assertTrue(legacy.resolution().derived());
    assertEquals(Classification.INFERRED_LOSS, legacy.resolution().classification());
    var confirmed =
        new FightRecord(
            2,
            UUID.randomUUID().toString(),
            0,
            1,
            1,
            "x",
            FightRecord.Outcome.WIN,
            "Server",
            new dev.phenomena.combat.metrics.Statistics(),
            List.of(),
            null,
            List.of(),
            null,
            new MatchEvidence(
                null,
                null,
                "r",
                Confidence.CONFIRMED,
                List.of(
                    new MatchEvidence.Entry(0, "RESULT", "minemen-result", "SYSTEM", "t", true))));
    assertEquals(Classification.CONFIRMED_WIN, confirmed.resolution().classification());
    assertEquals(0, confirmed.resolution().evidenceIndex());
  }

  @Test
  void drawTextNeedsEndpointAndIsSeparateClass() {
    var s = new Sim();
    s.host = "unknown.example";
    s.hit(1);
    s.chat(2, "Draw!");
    assertTrue(s.engine.engaged(), "generic text alone cannot resolve");
    s.death(3, B);
    s.chat(4, "Draw!");
    assertEquals(Classification.DRAW, s.last().resolution().classification());
    assertEquals(FightRecord.Outcome.DRAW, s.last().outcome());
  }
}
