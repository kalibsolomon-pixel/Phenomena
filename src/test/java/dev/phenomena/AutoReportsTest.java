package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import dev.phenomena.combat.fights.*;
import dev.phenomena.platform.SafePaths;
import dev.phenomena.recording.storage.*;
import dev.phenomena.sharing.ReportExporter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AutoReportsTest {
  @TempDir Path data;

  @Test
  void finalizationAutomaticallyWritesWithoutGuiAndReusesId() throws Exception {
    var messages = new ArrayList<String>();
    try (var store = new FightStore(data.resolve("fights"), messages::add)) {
      var auto = new AutoReports(store, data, messages::add);
      var f = Fixtures.sample();
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      assertTrue(
          store
              .recordingFile(f.id())
              .getParent()
              .getFileName()
              .toString()
              .startsWith("Fight vs Husk "));
      var shared = SafePaths.exportFolder(data, f, true);
      assertFalse(shared.getFileName().toString().contains("Husk"));
      assertEquals(f.id(), FightStore.decode(Files.readString(shared.resolve("report.json"))).id());
      assertFalse(Files.readString(shared.resolve("index.html")).contains("Husk"));
      assertTrue(auto.availability(f.id()).startsWith("Report ready"));
      assertEquals(1, store.readAll().size());
    }
  }

  @Test
  void repeatedFinalizeAndStartupDoNotDuplicateOrRewriteCompletedExport() throws Exception {
    var calls = new AtomicInteger();
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var auto =
          new AutoReports(
              store,
              data,
              s -> {},
              (p, f, o) -> {
                calls.incrementAndGet();
                ReportExporter.export(p, f, o);
              });
      var f = Fixtures.sample();
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      auto.recover(store.readAll(), ReportExporter.Options.DEFAULT);
      assertEquals(1, calls.get());
      try (var dirs = Files.list(data.resolve("exports"))) {
        assertEquals(1, dirs.count());
      }
    }
  }

  @Test
  void transientFailureRetriesAndRepairsSamePartialFolder() throws Exception {
    var calls = new AtomicInteger();
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var auto =
          new AutoReports(
              store,
              data,
              s -> {},
              (p, f, o) -> {
                ReportExporter.export(p, f, o);
                if (calls.incrementAndGet() < 3) {
                  Files.writeString(p.resolve("index.html"), "partial");
                  throw new IOException("Injected disk failure");
                }
              });
      var f = Fixtures.sample();
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      assertEquals(3, calls.get());
      assertTrue(
          Files.readString(SafePaths.exportFolder(data, f, true).resolve("index.html"))
              .contains("Phenomena Fight Report"));
      try (var dirs = Files.list(data.resolve("exports"))) {
        assertEquals(1, dirs.count());
      }
    }
  }

  @Test
  void permanentFailurePreservesRecordingAndRecoversOnRestart() throws Exception {
    var errors = new ArrayList<String>();
    var f = Fixtures.sample();
    try (var store = new FightStore(data.resolve("fights"), errors::add)) {
      var broken =
          new AutoReports(
              store,
              data,
              errors::add,
              (p, x, o) -> {
                throw new IOException("Injected unavailable disk");
              });
      broken.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      assertEquals(f.id(), store.readOne(f.id()).id());
      assertFalse(errors.isEmpty());
      new AutoReports(store, data, errors::add)
          .recover(store.readAll(), ReportExporter.Options.DEFAULT);
      assertTrue(Files.isRegularFile(SafePaths.exportFolder(data, f, true).resolve("index.html")));
    }
  }

  @Test
  void missingHtmlAndCorruptedReceiptRecoverWithoutDeletingRecording() throws Exception {
    var f = Fixtures.sample();
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var auto = new AutoReports(store, data, s -> {});
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      var folder = SafePaths.exportFolder(data, f, true);
      Files.delete(folder.resolve("index.html"));
      Files.writeString(data.resolve("report-state").resolve(f.id() + ".json"), "{");
      auto.recover(store.readAll(), ReportExporter.Options.DEFAULT);
      assertTrue(Files.isRegularFile(folder.resolve("index.html")));
      assertEquals(f.id(), store.readOne(f.id()).id());
    }
  }

  @Test
  void historicalRootRecordLoadsAlongsideNewFoldersWithoutMigration() throws Exception {
    var old = Fixtures.sample();
    var newer =
        new Fixtures("PlayerB").hit(0, Fixtures.attack(1, 3, .5, .5, false, false, true)).finish(1);
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      store.save(old, 100, 10_000_000);
      assertTrue(store.await(10000));
      Path original = store.recordingFile(old.id());
      String content = Files.readString(original);
      store.persistFinalized(newer);
      assertEquals(2, store.readAll().size());
      assertEquals(content, Files.readString(original));
      assertEquals(newer.id(), store.readOne(newer.id()).id());
    }
  }

  @Test
  void completedButTruncatedHtmlIsRepairedUsingReceiptDigest() throws Exception {
    var f = Fixtures.sample();
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var auto = new AutoReports(store, data, s -> {});
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      var file = SafePaths.exportFolder(data, f, true).resolve("index.html");
      Files.writeString(file, "truncated");
      auto.recover(store.readAll(), ReportExporter.Options.DEFAULT);
      assertTrue(Files.readString(file).contains("Phenomena Fight Report"));
    }
  }

  @Test
  void fullWorkerQueueRetainsFightUntilRetry() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      store.submit(
          () -> {
            try {
              release.await();
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          });
      for (int i = 0; i < 128; i++) store.submit(() -> {});
      var auto = new AutoReports(store, data, s -> {});
      var f = Fixtures.sample();
      auto.finalized(f, ReportExporter.Options.DEFAULT);
      release.countDown();
      // Drain already queued work before explicitly pumping the retained retry snapshot.
      for (int i = 0; i < 100 && !store.await(100); i++) Thread.yield();
      auto.pump();
      assertTrue(store.await(10000));
      assertEquals(f.id(), store.readOne(f.id()).id());
    } finally {
      release.countDown();
    }
  }

  @Test
  void allAnnouncementNamesAndServerAreRedactedInSharedReport() throws Exception {
    var f = Fixtures.sample();
    var evidence =
        new MatchEvidence(
            "private.example",
            "PlayerA",
            "round",
            dev.phenomena.combat.events.Confidence.CONFIRMED,
            List.of(
                new MatchEvidence.Entry(
                    1,
                    "RESULT",
                    "rule",
                    "SYSTEM",
                    "PlayerA defeated PlayerB, spectator PlayerC",
                    true)));
    var named =
        new FightRecord(
            2,
            f.id(),
            f.startEpochMillis(),
            f.endEpochMillis(),
            f.durationNanos(),
            f.opponent(),
            f.outcome(),
            "PlayerA vs PlayerB",
            f.statistics(),
            f.events(),
            f.policy(),
            f.evaluations(),
            "PlayerB",
            evidence);
    ReportExporter.export(data, named, true);
    String html = Files.readString(data.resolve("index.html"));
    for (String identity :
        List.of("PlayerA", "PlayerB", "PlayerC", "private.example", f.opponent()))
      assertFalse(html.contains(identity), identity);
  }
}
