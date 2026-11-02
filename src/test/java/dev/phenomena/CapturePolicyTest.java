package dev.phenomena;

import static dev.phenomena.Sim.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.recording.*;
import dev.phenomena.recording.storage.*;
import dev.phenomena.sharing.ReportExporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapturePolicyTest {
  @TempDir Path data;

  @Test
  void disconnectOrDisableCannotStartQueuedMatch() {
    for (boolean disconnect : List.of(true, false)) {
      var s = new Sim();
      s.chat(0, MM_START).hit(1).chat(2, MM_START.replace("PlayerB", "PlayerC"));
      if (disconnect) s.disconnect(2.5);
      else {
        s.config.tracking = false;
        s.tick(2.5);
      }
      assertFalse(s.engine.engaged());
      assertEquals(1, s.out.size());
      assertEquals(FightRecord.Outcome.INTERRUPTED, s.last().outcome());
    }
  }

  @Test
  void serverSessionContainsDistinctMatchesAndReconnectCreatesNewSession() {
    var s = new Sim();
    s.chat(0, MM_START).chat(3, MM_WIN);
    s.chat(6, MM_START).chat(9, MM_WIN);
    var first = s.out.getFirst().matchEvidence();
    var second = s.last().matchEvidence();
    assertNotNull(first.serverSessionId());
    assertEquals(first.serverSessionId(), second.serverSessionId());
    assertNotEquals(first.matchId(), second.matchId());
    s.chat(12, MM_START).disconnect(13).chat(16, MM_START).chat(19, MM_WIN);
    assertNotEquals(first.serverSessionId(), s.last().matchEvidence().serverSessionId());
  }

  @Test
  void duplicateResultCannotLeakAcrossImmediateRematch() {
    var s = new Sim();
    s.chat(0, MM_START).hit(1).chat(2, MM_WIN);
    s.chat(2.5, MM_START).chat(2.6, MM_WIN);
    assertEquals(1, s.out.size());
    assertTrue(s.engine.engaged());
    s.hit(5).chat(6, MM_WIN);
    assertEquals(2, s.out.size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mcpvp.club",
        "east.catpvp.net",
        "minemen.club",
        "pvphq.com",
        "fishffa.gg",
        "pvplegacy.net"
      })
  void configuredCombatCannotStartAndDisabledCannotStart(String host) {
    var s = new Sim(host);
    s.hit(1).tick(1000);
    assertFalse(s.engine.engaged());
    assertTrue(s.out.isEmpty());
    assertEquals(CapturePolicy.Mode.CONFIGURED_SERVER, s.engine.capturePolicy().mode());
    assertNotNull(s.engine.capturePolicy().environment());
    s.config.tracking = false;
    s.chat(1001, host.equals("minemen.club") ? MM_START : "Winner: PlayerA");
    s.hit(1002);
    assertTrue(s.out.isEmpty());
    assertFalse(s.engine.engaged());
    assertEquals(CapturePolicy.Mode.DISABLED, s.engine.capturePolicy().mode());
  }

  @Test
  void inactivityAndDeathCannotEndConfiguredMatch() {
    var s = new Sim();
    s.chat(0, MM_START).hit(3).death(4, "local").tick(100000);
    assertTrue(s.engine.engaged());
    assertTrue(s.out.isEmpty());
    assertTrue(s.engine.captureLimitation().contains("inactivity ignored"));
    s.chat(100001, MM_WIN);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.WIN, s.last().outcome());
    assertEquals(Resolution.Scope.MATCH, s.last().resolution().scope());
  }

  @Test
  void genericCaptureUsesOwnersInactivitySettingAndDisabledPreventsCombat() {
    var s = new Sim("unconfigured.test");
    s.config.inactivitySeconds = 11;
    s.hit(1).tick(11);
    assertTrue(s.engine.engaged());
    s.tick(12.1);
    assertFalse(s.engine.engaged());
    assertEquals(Resolution.Source.INACTIVITY, s.last().resolution().source());
    s.config.tracking = false;
    s.hit(15);
    assertFalse(s.engine.engaged());
    assertEquals(1, s.out.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"minemen.club", "unconfigured.test"})
  void manualOverrideAndRestorationWorkEvenWhenAutomaticDisabled(String host) {
    for (boolean automatic : List.of(true, false)) {
      var s = new Sim(host);
      s.config.tracking = automatic;
      List<FightRecord> saved = new ArrayList<>();
      RecordingController[] c = {null};
      var engine =
          new CombatEngine(s.config, f -> c[0].automaticFinished(f), (e, g) -> {}, id -> "PlayerB");
      c[0] =
          new RecordingController(s.config, engine, saved::add, f -> {}, m -> {}, id -> "PlayerB");
      c[0].context(host, "PlayerA", "world", ns(0), 20);
      assertTrue(c[0].start(host, "PlayerA", "world", ns(1), 40));
      assertEquals(CapturePolicy.Mode.MANUAL, engine.capturePolicy().mode());
      c[0].announcement(
          host,
          "PlayerA",
          Announcements.interpret("minemen.club", MM_START, Announcements.Origin.SYSTEM)
              .orElseThrow(),
          ns(2));
      c[0].announcement(
          host,
          "PlayerA",
          Announcements.interpret("minemen.club", MM_WIN, Announcements.Origin.SYSTEM)
              .orElseThrow(),
          ns(3));
      c[0].tick(ns(10000), 200020);
      assertTrue(c[0].manual());
      assertTrue(saved.isEmpty());
      assertTrue(c[0].end(ns(10001), 200040));
      assertEquals(1, saved.size());
      assertFalse(c[0].end(ns(10002), 200060));
      assertEquals(
          CapturePolicy.resolve(host, automatic, false).mode(), engine.capturePolicy().mode());
    }
  }

  @Test
  void unrelatedAndPostResultAttacksDoNotReplaceOrDuplicateMatch() {
    var s = new Sim();
    s.chat(0, MM_START).hit(3);
    s.attack(4, "C", 3, false, false).chat(5, MM_WIN).hit(5.1).tick(100);
    assertEquals(1, s.out.size());
    assertEquals(1, s.last().statistics().hits);
    assertFalse(s.engine.engaged());
  }

  @Test
  void roundResultsNeverResolveWholeMatchOrLeakIntoNext() {
    var s = new Sim("east.catpvp.net");
    s.chat(0, "Opponent: PlayerB ℹ").hit(3).chat(5, "PlayerB won the round.").tick(10000);
    assertTrue(s.out.isEmpty());
    s.hit(10001).chat(10005, "PlayerA won the game.");
    var f = s.last();
    assertEquals(FightRecord.Outcome.WIN, f.outcome());
    assertEquals(FightRecord.Outcome.LOSS, f.matchEvidence().rounds().getFirst().outcome());
    assertNull(
        f.matchEvidence().rounds().getLast().outcome(),
        "match victory does not invent round result");
    s.chat(10010, "Opponent: PlayerC ℹ").chat(10011, "PlayerB won the game.");
    assertEquals(1, s.out.size());
    assertTrue(s.engine.engaged());
  }

  @Test
  void disconnectPreservesEmptyAnnouncedMatchAsInterrupted() {
    var s = new Sim();
    s.chat(0, MM_START).disconnect(1).tick(100);
    assertEquals(1, s.out.size());
    assertEquals(FightRecord.Outcome.INTERRUPTED, s.last().outcome());
    s.chat(101, MM_WIN);
    assertEquals(1, s.out.size());
  }

  @Test
  void combosAndExchangesCannotBridgeRapidRounds() {
    var s = new Sim();
    s.chat(0, MM_START).hit(1).hit(1.6).chat(2, "PlayerA won! 1 - 0");
    s.chat(2.1, "Round 2 started!").hit(2.2).hit(2.8).chat(3.5, MM_WIN);
    var f = s.last();
    var analytics = dev.phenomena.analytics.FightAnalytics.of(f);
    assertEquals(2, f.statistics().longestCombo);
    assertEquals(2, analytics.combos.longest(dev.phenomena.combat.metrics.Combos.Side.LOCAL));
    assertEquals(2, analytics.combos.exchanges().size());
    assertEquals(2, analytics.combos.longestUnansweredLocal());
  }

  @Test
  void pvphqWinnerNeedsNoDeathAndRoundOnlyHasNoTimeout() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB")
        .chat(1, "► Winner: PlayerA (5.3 ❤, 10/18 ⚗) [Click]")
        .tick(10000);
    assertTrue(s.out.isEmpty());
    s.chat(10001, "🏆 MATCH REPORT 🏆").chat(10001.1, "► Winner: PlayerA (0.9 ❤) [Click]");
    assertEquals(FightRecord.Outcome.WIN, s.last().outcome());
    assertTrue(s.last().events().isEmpty());
  }

  @Test
  void explicitReportWithoutResultCompletesUnknownAndCannotReopenFromCombat() {
    var s = new Sim("pvphq.com");
    s.chat(0, "► Opponent: PlayerB").chat(10000, "🏆 MATCH REPORT 🏆").tick(10010);
    assertEquals(FightRecord.Outcome.UNKNOWN, s.last().outcome());
    assertEquals(Resolution.Source.SERVER_MATCH_RESULT, s.last().resolution().source());
    s.hit(10011);
    assertFalse(s.engine.engaged());
    assertEquals(1, s.out.size());
  }

  @Test
  void boundedRoundMetadataNeverAssignsLateOutcomeToEarlierRound() {
    var s = new Sim();
    s.chat(0, MM_START);
    for (int i = 1; i <= 257; i++) s.chat(i, "Round " + i + " started!");
    s.chat(258, "PlayerA won! 257 - 0"); // unsupported score length cannot fabricate a result
    s.chat(260, MM_WIN);
    assertEquals(1, s.out.size());
    assertEquals(256, s.last().matchEvidence().rounds().size());
    assertTrue(s.last().matchEvidence().rounds().stream().allMatch(r -> r.outcome() == null));
  }

  @Test
  void exactAliasesAndTitleTrustAreHostScoped() {
    for (String host : List.of("bad.minemen.club", "minemen.club.evil.test", "evilfishffa.gg"))
      assertEquals(
          CapturePolicy.Mode.GENERIC_COMBAT, CapturePolicy.resolve(host, true, false).mode());
    assertEquals(
        CapturePolicy.Mode.CONFIGURED_SERVER,
        CapturePolicy.resolve("NA.FISHFFA.GG.:25634", true, false).mode());
    assertFalse(
        Announcements.interpret("unknown.test", "⚔ FIGHT! ⚔", Announcements.Origin.TITLE)
            .orElseThrow()
            .trusted());
    assertTrue(
        Announcements.interpret("mcpvp.club", "⚔ FIGHT! ⚔", Announcements.Origin.TITLE)
            .orElseThrow()
            .trusted());
    assertTrue(
        Announcements.interpret("mcpvp.club", "PlayerC: ⚔ FIGHT! ⚔", Announcements.Origin.SYSTEM)
            .isEmpty());
  }

  static JsonArray fixtures() {
    return JsonParser.parseReader(
            new InputStreamReader(
                Objects.requireNonNull(
                    CapturePolicyTest.class.getResourceAsStream("/fixtures/six-servers.json")),
                StandardCharsets.UTF_8))
        .getAsJsonObject()
        .getAsJsonArray("fixtures");
  }

  /** Actual announcement timing with synthetic combat used only to exercise measurement binding. */
  static Sim replay(JsonObject f) {
    var s = new Sim(f.get("server").getAsString());
    double last = 0;
    s.engine.context(s.host, "PlayerA");
    for (var e : f.getAsJsonArray("messages")) {
      var m = e.getAsJsonObject();
      double t = m.get("t").getAsDouble();
      for (double at = last + 2; at < t - .1; at += 2) {
        s.tick(at);
        if (s.engine.engaged()) s.hit(at);
      }
      s.tick(t);
      Announcements.interpret(
              s.host,
              m.get("text").getAsString(),
              Announcements.Origin.valueOf(m.get("origin").getAsString()))
          .ifPresent(message -> s.engine.announcement(s.host, "PlayerA", message, ns(t)));
      last = t;
    }
    s.tick(last + 20);
    return s;
  }

  @TestFactory
  Stream<DynamicTest> eighteenOwnerOutcomesEachProduceOneRecordingAndOfflineReport() {
    List<DynamicTest> tests = new ArrayList<>();
    for (var e : fixtures()) {
      var f = e.getAsJsonObject();
      tests.add(
          DynamicTest.dynamicTest(
              f.get("server").getAsString() + " #" + f.get("match").getAsInt(),
              () -> {
                var s = replay(f);
                assertEquals(1, s.out.size(), "one recording per whole match");
                var record = s.last();
                assertEquals(f.get("ownerResult").getAsString(), record.outcome().name());
                assertEquals(Resolution.Scope.MATCH, record.resolution().scope());
                assertFalse(s.engine.engaged());
                assertEquals(ns(0), record.matchEvidence().timelineStartNanos());
                Path root = data.resolve(UUID.randomUUID().toString());
                try (var store = new FightStore(root.resolve("fights"), m -> fail(m))) {
                  var reports = new AutoReports(store, root, m -> {});
                  reports.finalized(record, ReportExporter.Options.DEFAULT);
                  reports.finalized(record, ReportExporter.Options.DEFAULT);
                  assertTrue(store.await(20000));
                  assertEquals(1, store.readAll().size());
                  try (var files = Files.walk(root.resolve("exports"))) {
                    var html =
                        files.filter(p -> p.getFileName().toString().equals("index.html")).toList();
                    assertEquals(1, html.size());
                    String text = Files.readString(html.getFirst());
                    assertTrue(text.contains("Match rounds"));
                    assertFalse(text.contains("PlayerA"));
                    assertFalse(text.contains("PlayerB"));
                    assertTrue(text.contains("connect-src 'none'"));
                  }
                  var decoded = store.readAll().getFirst();
                  assertEquals(record.matchEvidence().rounds(), decoded.matchEvidence().rounds());
                }
              }));
    }
    return tests.stream();
  }
}
