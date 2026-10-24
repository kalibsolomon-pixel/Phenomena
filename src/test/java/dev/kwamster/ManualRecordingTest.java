package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.platform.SafePaths;
import dev.kwamster.recording.*;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ManualRecordingTest {
  @TempDir Path data;
  final Config config = new Config();
  final List<FightRecord> saved = new ArrayList<>(), checkpoints = new ArrayList<>();
  final List<String> notices = new ArrayList<>();
  RecordingController controller;
  final CombatEngine engine =
      new CombatEngine(
          config,
          f -> controller.automaticFinished(f),
          (e, g) -> {},
          id -> "B".equals(id) ? "PlayerB" : "C".equals(id) ? "PlayerC" : null);
  long seq;

  ManualRecordingTest() {
    controller =
        new RecordingController(
            config,
            engine,
            saved::add,
            checkpoints::add,
            notices::add,
            id -> "B".equals(id) ? "PlayerB" : "C".equals(id) ? "PlayerC" : null);
    engine.context("minemen.club", "PlayerA");
  }

  void start() {
    assertTrue(controller.start("minemen.club", "PlayerA", "world", Sim.ns(0), 20));
  }

  void emit(
      double t,
      Kind kind,
      String actor,
      String target,
      Attack attack,
      String detail,
      Context context) {
    controller.accept(
        new CombatEvent(
            ++seq,
            Sim.ns(t),
            kind,
            actor,
            target,
            Confidence.CONFIRMED,
            attack,
            detail,
            Sim.clientTick(t),
            context));
  }

  void hit(double t, String who) {
    emit(
        t, Kind.ATTACK, "local", who, Sim.attack(3, "minecraft:diamond_sword", null), "test", null);
    emit(t + .01, Kind.HURT, "local", who, null, "local-melee-source", null);
  }

  void chat(double t, String text) {
    controller.announcement(
        "minemen.club",
        "PlayerA",
        Announcements.interpret("minemen.club", text, Announcements.Origin.SYSTEM).orElseThrow(),
        Sim.ns(t));
  }

  FightRecord end(double t) {
    assertTrue(controller.end(Sim.ns(t), Sim.clientTick(t)));
    return saved.getLast();
  }

  @Test
  void startWithoutOpponentAndDuplicateStarts() {
    start();
    assertEquals(RecordingController.State.MANUAL_ACTIVE, controller.state());
    assertFalse(controller.start("minemen.club", "PlayerA", "world", Sim.ns(1), 40));
    assertEquals(1, checkpoints.size());
    assertTrue(checkpoints.getFirst().manual());
    assertTrue(checkpoints.getFirst().session().participants().isEmpty());
  }

  @Test
  void endWithoutRecordingDoesNotExport() {
    assertFalse(controller.end(Sim.ns(0), 20));
    assertTrue(saved.isEmpty());
  }

  @Test
  void finalizeExactlyOnceWithoutManufacturingWinner() {
    start();
    hit(1, "B");
    var f = end(4);
    assertEquals(FightRecord.Outcome.UNKNOWN, f.outcome());
    assertEquals(1, f.statistics().hits);
    assertEquals(20, f.session().startTick());
    assertEquals(100, f.session().endTick());
    assertFalse(controller.end(Sim.ns(5), 120));
    assertEquals(1, saved.size());
  }

  @Test
  void idleManualSessionRemainsActive() {
    start();
    controller.tick(Sim.ns(36000), 720020);
    assertTrue(controller.manual());
    assertTrue(saved.isEmpty());
    assertEquals(36000_000_000_000L, end(36000).durationNanos());
  }

  @Test
  void automaticMessagesRemainNestedAndDoNotEndParent() {
    start();
    chat(.1, Sim.MM_START);
    hit(2, "B");
    chat(4, Sim.MM_WIN);
    assertTrue(controller.manual());
    assertTrue(saved.isEmpty());
    var f = end(6);
    assertEquals(FightRecord.Outcome.UNKNOWN, f.outcome());
    assertEquals(2, f.session().announcements().size());
    assertEquals(
        Resolution.Classification.CONFIRMED_WIN,
        f.session().exchanges().getFirst().resolution().classification());
  }

  @Test
  void multipleOpponentsKeepIndependentScopes() {
    start();
    hit(1, "B");
    hit(2, "B");
    hit(2.7, "C");
    hit(3.5, "C");
    var f = end(5);
    assertNull(f.opponent());
    assertEquals(2, f.session().participants().size());
    assertEquals(2, f.session().exchanges().size());
    assertEquals(4, f.statistics().hits);
    assertEquals(2, f.statistics().longestCombo);
    assertEquals(8, f.events().size());
    assertEquals(4, f.evaluations().size());
    assertTrue(dev.kwamster.analytics.FightAnalytics.of(f).combos.marks().isEmpty());
    assertEquals(0, dev.kwamster.analytics.FightAnalytics.of(f).dominance.activeSeconds);
  }

  @Test
  void manualCanChangeTargetDuringNamedMatch() {
    start();
    chat(.1, Sim.MM_START);
    hit(2, "B");
    hit(3, "C");
    var f = end(5);
    assertEquals(2, f.statistics().hits);
    assertEquals(2, f.session().exchanges().size());
  }

  @Test
  void automaticInProgressPromotesWithoutLosingOrDuplicatingHits() {
    hit(1, "B");
    assertTrue(controller.start("minemen.club", "PlayerA", "world", Sim.ns(2), 60));
    hit(3, "B");
    var f = end(5);
    assertEquals(2, f.statistics().hits);
    assertEquals(4, f.events().size());
    assertEquals(1, saved.size());
    assertEquals(4_000_000_000L, f.durationNanos());
    assertEquals(60L, f.session().manualStartTick());
    assertEquals(
        60L, ReportExporter.prepare(f, ReportExporter.Options.DEFAULT).session().manualStartTick());
  }

  @Test
  void normalAutomaticTrackingResumes() {
    start();
    hit(1, "B");
    end(3);
    hit(4, "C");
    controller.tick(Sim.ns(100), 2020);
    assertEquals(2, saved.size());
    assertFalse(saved.getLast().manual());
    assertEquals("C", saved.getLast().opponent());
  }

  @Test
  void disconnectImmediatelyPreservesManualSession() {
    start();
    hit(1, "B");
    emit(2, Kind.DISCONNECT, "local", null, null, "connection lost", null);
    assertFalse(controller.manual());
    assertEquals(FightRecord.Outcome.INTERRUPTED, saved.getLast().outcome());
    assertEquals(1, saved.getLast().statistics().hits);
    assertEquals(3, saved.getLast().events().size());
  }

  @Test
  void dimensionChangeDoesNotResumeElsewhere() {
    start();
    hit(1, "B");
    controller.context("minemen.club", "PlayerA", "other", Sim.ns(2), 60);
    assertFalse(controller.manual());
    assertEquals(FightRecord.Outcome.INTERRUPTED, saved.getFirst().outcome());
  }

  @Test
  void shutdownPreservesManualSession() {
    start();
    hit(1, "B");
    controller.shutdown(Sim.ns(2), 60, "Client stopped");
    assertEquals("Client stopped", saved.getFirst().session().termination());
  }

  @Test
  void unAttributedAndThirdPartyDamageNeverBecomeOpponentHits() {
    start();
    hit(1, "B");
    emit(
        2,
        Kind.DAMAGE_IN,
        null,
        "local",
        null,
        "fall",
        Context.incoming(Attribution.UNATTRIBUTED, "minecraft:fall", null, true, false, true));
    emit(
        3,
        Kind.DAMAGE_IN,
        "C",
        "local",
        null,
        "other",
        Context.incoming(
            Attribution.OTHER_ENTITY, "minecraft:player_attack", true, true, false, true));
    var f = end(4);
    assertEquals(0, f.statistics().incoming);
    assertEquals(1, f.statistics().unattributedIncoming);
    assertEquals(1, f.statistics().thirdPartyIncoming);
  }

  @Test
  void checkpointsRecoverCurrentExchangeAndNeverDuplicateExports() throws Exception {
    start();
    hit(1, "B");
    controller.tick(Sim.ns(6), 140);
    var checkpoint = checkpoints.getLast();
    assertEquals(1, checkpoint.statistics().hits);
    try (var store = new FightStore(data.resolve("fights"), notices::add)) {
      var recovery = new SessionRecovery(store, data, notices::add);
      recovery.checkpoint(checkpoint);
      assertTrue(store.await(10000));
      var recovered = recovery.recover(store.readAll());
      assertEquals(1, recovered.size());
      assertEquals(FightRecord.Outcome.INTERRUPTED, recovered.getFirst().outcome());
      var reports = new AutoReports(store, data, notices::add);
      reports.recover(recovered, ReportExporter.Options.DEFAULT);
      assertTrue(
          Files.isRegularFile(
              SafePaths.exportFolder(data, checkpoint, true).resolve("index.html")));
      assertEquals(1, recovery.recover(store.readAll()).size());
      reports.recover(store.readAll(), ReportExporter.Options.DEFAULT);
      try (var dirs = Files.list(data.resolve("exports"))) {
        assertEquals(1, dirs.count());
      }
    }
  }

  @Test
  void finalCheckpointWinsOverEarlierRecoveryAndBadFilesPreserved() throws Exception {
    start();
    hit(1, "B");
    var f = end(3);
    try (var store = new FightStore(data.resolve("fights"), notices::add)) {
      var recovery = new SessionRecovery(store, data, notices::add);
      recovery.checkpoint(f);
      assertTrue(store.await(10000));
      var bad = data.resolve("pending-sessions/bad.json");
      Files.writeString(bad, "bad");
      assertEquals(FightRecord.Outcome.UNKNOWN, recovery.recover(List.of()).getFirst().outcome());
      assertEquals("bad", Files.readString(bad));
    }
  }

  @Test
  void manualFolderAndAnonymousParticipantScopes() throws Exception {
    start();
    hit(1, "B");
    hit(2, "C");
    var f = end(4);
    assertTrue(SafePaths.exportName(f, false).startsWith("Manual Session "));
    var shared = ReportExporter.prepare(f, ReportExporter.Options.DEFAULT);
    String json = AtomicJson.GSON.toJson(shared);
    assertFalse(json.contains("PlayerA"));
    assertFalse(json.contains("PlayerB"));
    assertFalse(json.contains("PlayerC"));
    assertFalse(json.contains("minemen.club"));
    assertNotEquals(
        shared.session().participants().get(0).id(), shared.session().participants().get(1).id());
    assertEquals(
        shared.session().participants().get(0).id(),
        shared.session().exchanges().getFirst().opponent());
    assertEquals(2, FightStore.decode(AtomicJson.GSON.toJson(f)).session().exchanges().size());
    var folder = data.resolve("manual");
    ReportExporter.export(folder, f, ReportExporter.Options.DEFAULT);
    var html = Files.readString(folder.resolve("index.html"));
    assertTrue(html.contains("Participant filter"));
    assertTrue(html.contains("Exchange filter"));
    assertFalse(html.contains("PlayerB"));
    assertTrue(html.contains("\"scopes\""));
  }

  @Test
  void roundAndDelayedResultsStayInChildScope() {
    start();
    chat(.1, Sim.MM_START);
    hit(2, "B");
    chat(4, "PlayerA won! 1 - 0");
    chat(5, Sim.MM_WIN);
    assertTrue(controller.manual());
    var f = end(8);
    assertEquals(1, f.session().exchanges().size());
    assertEquals(
        Resolution.Classification.CONFIRMED_WIN,
        f.session().exchanges().getFirst().resolution().classification());
  }

  @Test
  void resultFromDifferentServerCannotResolveCurrentFight() {
    hit(1, "B");
    engine.announcement(
        "pvphq.com",
        "PlayerA",
        Announcements.interpret("minemen.club", Sim.MM_WIN, Announcements.Origin.SYSTEM)
            .orElseThrow(),
        Sim.ns(2));
    assertTrue(saved.isEmpty());
    engine.finish(Sim.ns(3), FightRecord.Outcome.UNKNOWN, "test");
    assertEquals(FightRecord.Outcome.UNKNOWN, saved.getFirst().outcome());
  }

  @Test
  void historicalSchemaThreeRetainsIncomingAnalytics() throws Exception {
    var s = new Sim();
    s.hit(1).incoming(2);
    var f = s.finish(4);
    var json = AtomicJson.GSON.toJson(f).replace("\"schemaVersion\": 4", "\"schemaVersion\": 3");
    var old = FightStore.decode(json);
    assertFalse(old.manual());
    assertTrue(dev.kwamster.analytics.FightAnalytics.of(old).contextAvailable);
  }

  @Test
  void automaticContextChangeCannotCarryOldServerIntoNewCombat() {
    controller.context("minemen.club", "PlayerA", "world", Sim.ns(0), 20);
    hit(1, "B");
    controller.context("other.invalid", "PlayerA", "world", Sim.ns(2), 60);
    assertEquals(FightRecord.Outcome.INTERRUPTED, saved.getFirst().outcome());
    hit(3, "C");
    engine.finish(Sim.ns(4), FightRecord.Outcome.UNKNOWN, "test");
    assertEquals("other.invalid", saved.getLast().matchEvidence().server());
  }

  @Test
  void exportStateReflectsActualPendingWorkAndDisabledAutomaticSaving() {
    start();
    hit(1, "B");
    end(2);
    assertEquals(RecordingController.State.PENDING_EXPORT, controller.state());
    controller.exportStatus(id -> "Report ready: index.html");
    assertEquals(RecordingController.State.IDLE, controller.state());
    controller.exportStatus(id -> "Report generated automatically for new fights");
    hit(3, "C");
    engine.finish(Sim.ns(4), FightRecord.Outcome.UNKNOWN, "automatic saving disabled");
    assertEquals(RecordingController.State.IDLE, controller.state());
  }
}
