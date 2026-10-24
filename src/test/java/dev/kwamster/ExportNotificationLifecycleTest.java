package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.recording.*;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportNotificationLifecycleTest {
  @TempDir Path data;
  final ExportNotifications queue = new ExportNotifications(() -> 0);
  final List<String> chat = new ArrayList<>(), errors = new ArrayList<>();
  final AtomicInteger writes = new AtomicInteger();

  AutoReports reports(FightStore store) {
    return new AutoReports(
        store,
        data,
        errors::add,
        (p, f, o) -> {
          writes.incrementAndGet();
          ReportExporter.export(p, f, o);
        },
        queue::offer);
  }

  void scan(FightStore store, AutoReports reports) throws Exception {
    // Production recovery runs on the same storage worker as finalization, not the UI thread.
    assertTrue(
        store.submit(
            () -> {
              try {
                reports.recover(store.readAll(), ReportExporter.Options.DEFAULT);
              } catch (IOException ex) {
                throw new RuntimeException(ex);
              }
            }));
    assertTrue(store.await(10000));
  }

  @Test
  void severalSuccessfulReceiptsAndRestartNeverReplayDeliveredCompletions() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var reports = reports(store);
      for (int i = 0; i < 4; i++)
        reports.finalized(Fixtures.sample(), ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertEquals(4, chat.size());
      var before = new HashMap<Path, String>();
      try (var paths = Files.walk(data)) {
        for (var p : paths.filter(Files::isRegularFile).toList())
          before.put(p, Files.readString(p));
      }
      for (int i = 0; i < 3; i++) scan(store, reports);
      scan(store, reports(store)); // restarted pipeline reads existing successful receipts
      queue.tick(true, chat::add);
      assertEquals(4, writes.get());
      assertEquals(4, chat.size());
      assertEquals(4, store.readAll().size());
      for (var entry : before.entrySet())
        assertEquals(entry.getValue(), Files.readString(entry.getKey()));
    }
  }

  @Test
  void historicalRevisionRebuildsDoNotMasqueradeAsFreshFights() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var reports = reports(store);
      for (int i = 0; i < 9; i++)
        reports.finalized(Fixtures.sample(), ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      chat.clear();
      // Reproduce the old startup burst by making nine valid receipts refer to an older revision.
      try (var receipts = Files.list(data.resolve("report-state"))) {
        for (var receipt : receipts.toList()) {
          var json =
              com.google.gson.JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
          json.addProperty("digest", "previous-format-revision");
          AtomicJson.write(receipt, json);
        }
      }
      scan(store, reports);
      queue.tick(true, chat::add);
      assertTrue(chat.isEmpty());
      assertEquals(18, writes.get()); // necessary repairs still run
      scan(store, reports);
      assertEquals(18, writes.get());
      assertEquals(9, store.readAll().size());
    }
  }

  @Test
  void incompleteStartupExportRecoversSilentlyThenNewCompletionNotifies() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      store.persistFinalized(Fixtures.sample());
      var reports = reports(store);
      scan(store, reports);
      queue.tick(true, chat::add);
      assertTrue(chat.isEmpty());
      reports.finalized(Fixtures.sample(), ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      queue.tick(false, chat::add);
      assertTrue(chat.isEmpty());
      queue.tick(true, chat::add);
      for (int i = 0; i < 100; i++) queue.tick(true, chat::add);
      assertEquals(1, chat.size());
      assertEquals(2, writes.get());
      assertEquals(2, store.readAll().size());
    }
  }

  @Test
  void failureAndLaterSuccessRemainDistinctWithoutDuplicatingUnrelatedWork() throws Exception {
    var fail = new AtomicBoolean(true);
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var reports =
          new AutoReports(
              store,
              data,
              errors::add,
              (p, f, o) -> {
                writes.incrementAndGet();
                if (fail.get()) throw new IOException("injected failure");
                ReportExporter.export(p, f, o);
              },
              queue::offer);
      var fight = Fixtures.sample();
      reports.finalized(fight, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      assertEquals(3, writes.get());
      assertTrue(errors.stream().anyMatch(s -> s.contains("failed after 3 attempts")));
      queue.tick(true, chat::add);
      assertTrue(chat.isEmpty());
      fail.set(false);
      reports.tick(30_000_000_000L);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      reports.finalized(fight, ReportExporter.Options.DEFAULT); // duplicate finalization callback
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertEquals(1, chat.size());
      assertEquals(4, writes.get());
      assertEquals(1, store.readAll().size());
      reports.finalized(Fixtures.sample(), ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertEquals(2, chat.size());
      assertEquals(5, writes.get());
    }
  }

  @Test
  void recoveryRetryKeepsItsSilentOrigin() throws Exception {
    var fail = new AtomicBoolean(true);
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      store.persistFinalized(Fixtures.sample());
      var reports =
          new AutoReports(
              store,
              data,
              errors::add,
              (p, f, o) -> {
                if (fail.get()) throw new IOException("injected startup failure");
                ReportExporter.export(p, f, o);
              },
              queue::offer);
      scan(store, reports);
      assertTrue(errors.stream().anyMatch(s -> s.contains("recovery deferred")));
      fail.set(false);
      reports.tick(30_000_000_000L);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertTrue(chat.isEmpty());
      assertTrue(reports.availability(store.readAll().getFirst().id()).startsWith("Report ready"));
    }
  }

  @Test
  void recoveredManualCheckpointIsExportedWithoutReplayingOrdinaryCompletion() throws Exception {
    var manual = new ManualRecordingTest();
    manual.start();
    manual.hit(1, "B");
    var checkpoint = manual.end(2);
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var recovery = new SessionRecovery(store, data, errors::add);
      recovery.checkpoint(checkpoint);
      assertTrue(store.await(10000));
      var reports = reports(store);
      assertTrue(
          store.submit(
              () -> reports.recover(recovery.recover(List.of()), ReportExporter.Options.DEFAULT)));
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertTrue(chat.isEmpty());
      assertEquals(1, writes.get());
      assertEquals(1, store.readAll().size());
      assertTrue(store.readOne(checkpoint.id()).manual());
      scan(store, reports);
      assertEquals(1, writes.get());
    }
  }

  @Test
  void manualAndConfiguredMatchStillProduceOneRecordingExportAndNoticeEach() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var reports = reports(store);
      var manual = new ManualRecordingTest();
      manual.start();
      manual.hit(1, "B");
      FightRecord parent = manual.end(2);
      reports.finalized(parent, ReportExporter.Options.DEFAULT);
      var automatic = new Sim("pvphq.com");
      automatic
          .chat(0, "► Opponent: PlayerB")
          .hit(1)
          .chat(2, "🏆 MATCH REPORT 🏆")
          .chat(2.1, "► Winner: PlayerA (2 ❤) [Click]");
      assertEquals(1, automatic.out.size());
      reports.finalized(automatic.last(), ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      queue.tick(true, chat::add);
      assertEquals(2, chat.size());
      assertEquals(2, writes.get());
      assertEquals(2, store.readAll().size());
      automatic.disconnect(3);
      automatic.host = "mcpvp.club";
      automatic.tick(4);
      queue.tick(false, chat::add);
      queue.tick(true, chat::add);
      assertEquals(1, automatic.out.size());
      assertEquals(2, chat.size());
    }
  }
}
