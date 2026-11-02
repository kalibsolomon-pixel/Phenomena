package dev.phenomena.recording.storage;

import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.platform.SafePaths;
import dev.phenomena.recording.ExportNotifications;
import dev.phenomena.sharing.ReportExporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * One bounded worker, at most 128 retry snapshots. Durable recording precedes HTML publication.
 * Missing/mismatched receipts recover on startup; a receipt is published only after both files
 * exist.
 */
public final class AutoReports {
  @FunctionalInterface
  public interface Exporter {
    void export(Path folder, FightRecord fight, ReportExporter.Options options) throws IOException;
  }

  private record Pending(FightRecord fight, ReportExporter.Options options, boolean announce) {}

  private record Receipt(
      String id, String digest, String folder, String htmlDigest, String jsonDigest) {}

  private final FightStore store;
  private final Path data;
  private final Consumer<String> status;
  private final Exporter exporter;
  private final Consumer<ExportNotifications.Completion> completed;
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  private final Map<String, String> available =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
              return size() > 1000;
            }
          });
  private long nextRetry;

  public AutoReports(FightStore store, Path data, Consumer<String> status) {
    this(
        store,
        data,
        status,
        (folder, fight, options) -> ReportExporter.export(folder, fight, options));
  }

  public AutoReports(FightStore store, Path data, Consumer<String> status, Exporter exporter) {
    this(store, data, status, exporter, event -> status.accept(ExportNotifications.MESSAGE));
  }

  public AutoReports(
      FightStore store,
      Path data,
      Consumer<String> status,
      Exporter exporter,
      Consumer<ExportNotifications.Completion> completed) {
    this.store = store;
    this.data = data;
    this.status = status;
    this.exporter = exporter;
    this.completed = completed;
  }

  public String availability(String id) {
    return available.getOrDefault(
        id,
        pending.containsKey(id)
            ? "Report pending / retry available"
            : "Report generated automatically for new fights");
  }

  public synchronized boolean finalized(FightRecord fight, ReportExporter.Options options) {
    if (pending.size() >= 128 && !pending.containsKey(fight.id())) {
      status.accept(
          "Recording/report queue exhausted; fight remains in session history. Retry from"
              + " History.");
      return false;
    }
    pending.put(fight.id(), new Pending(fight, options, true));
    pump();
    return true;
  }

  public void tick(long now) {
    if (now >= nextRetry) {
      nextRetry = now + 30_000_000_000L;
      pump();
    }
  }

  public void pump() {
    for (var p : List.copyOf(pending.values())) {
      String id = p.fight.id();
      if (!running.add(id)) continue;
      if (!store.submit(
          () -> {
            try {
              for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                  process(p.fight, p.options, p.announce);
                  pending.remove(id, p);
                  return;
                } catch (IOException | RuntimeException ex) {
                  if (attempt == 3)
                    status.accept(
                        "Automatic report failed after 3 attempts; retry in 30s or on restart: "
                            + ex.getMessage());
                }
              }
            } finally {
              running.remove(id);
            }
          })) running.remove(id);
    }
  }

  /**
   * Called on the storage worker after history load. Never loads a partial timeline from memory.
   */
  public void recover(List<FightRecord> records, ReportExporter.Options options) {
    for (var summary : records) {
      try {
        // Startup repairs (including format upgrades) are background work, not new fights.
        process(store.readOne(summary.id()), options, false);
      } catch (IOException | RuntimeException ex) {
        status.accept("Report recovery deferred for " + summary.id() + ": " + ex.getMessage());
        if (pending.size() < 128)
          try {
            pending.putIfAbsent(
                summary.id(), new Pending(store.readOne(summary.id()), options, false));
          } catch (IOException ignored) {
            /* damaged originals are preserved */
          }
      }
    }
  }

  private void process(FightRecord fight, ReportExporter.Options options, boolean notify)
      throws IOException {
    store.persistFinalized(fight);
    Path receipts = data.resolve("report-state");
    Files.createDirectories(receipts);
    Path receipt = receipts.resolve(SafePaths.requireUuid(fight.id()) + ".json");
    String digest =
        digest(AtomicJson.GSON.toJson(fight) + options.toString() + ReportExporter.FORMAT_VERSION);
    Path folder = SafePaths.exportFolder(data, fight, options.anonymize());
    if (Files.isRegularFile(receipt) && !Files.isSymbolicLink(receipt)) {
      try {
        Receipt r = AtomicJson.GSON.fromJson(Files.readString(receipt), Receipt.class);
        if (r != null
            && fight.id().equals(r.id)
            && digest.equals(r.digest)
            && folder.getFileName().toString().equals(r.folder)
            && Files.isRegularFile(folder.resolve("report.json"))
            && Files.isRegularFile(folder.resolve("index.html"))
            && Objects.equals(r.htmlDigest, fileDigest(folder.resolve("index.html")))
            && Objects.equals(r.jsonDigest, fileDigest(folder.resolve("report.json")))) {
          available.put(
              fight.id(), "Report ready: " + data.relativize(folder.resolve("index.html")));
          return;
        }
      } catch (RuntimeException ex) {
        /* Incomplete receipts are repaired without deleting the report. */
      }
    }
    exporter.export(folder, fight, options);
    SafePaths.reportFile(folder);
    AtomicJson.write(
        receipt,
        new Receipt(
            fight.id(),
            digest,
            folder.getFileName().toString(),
            fileDigest(folder.resolve("index.html")),
            fileDigest(folder.resolve("report.json"))));
    available.put(fight.id(), "Report ready: " + data.relativize(folder.resolve("index.html")));
    if (notify) completed.accept(new ExportNotifications.Completion(fight.id(), digest));
  }

  private static String fileDigest(Path p) throws IOException {
    if (Files.isSymbolicLink(p) || Files.size(p) > 2L * 1024 * 1024 * 1024)
      throw new IOException("Unsafe or oversized export");
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(p)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String digest(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
