package dev.kwamster.recording.storage;

import dev.kwamster.combat.fights.FightRecord;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * All operations run on the single bounded writer. Existing reports are replaced only atomically.
 */
public final class FightStore implements AutoCloseable {
  public static final long MAX_RECORDING_BYTES = 512L * 1024 * 1024;
  private final Path directory;
  private final Consumer<String> errors;
  private final ThreadPoolExecutor writer;

  public FightStore(Path directory, Consumer<String> errors) {
    this.directory = directory;
    this.errors = errors;
    writer =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            r -> {
              var t = new Thread(r, "kwamster-storage");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public boolean submit(Runnable task) {
    try {
      writer.execute(
          () -> {
            try {
              task.run();
            } catch (RuntimeException ex) {
              errors.accept("Storage error: " + ex.getMessage());
            }
          });
      return true;
    } catch (RejectedExecutionException ex) {
      errors.accept("Storage queue full; operation was not saved");
      return false;
    }
  }

  public void load(Consumer<List<FightRecord>> result) {
    submit(
        () -> {
          try {
            result.accept(readAll());
          } catch (IOException ex) {
            errors.accept(ex.toString());
          }
        });
  }

  public Path directory() {
    return directory;
  }

  public void loadOne(String id, Consumer<FightRecord> result) {
    submit(
        () -> {
          try {
            result.accept(readOne(id));
          } catch (IOException | RuntimeException ex) {
            errors.accept("Could not load fight: " + ex.getMessage());
          }
        });
  }

  /** Reads one managed recording by UUID. Call from the storage worker, never render code. */
  public FightRecord readOne(String id) throws IOException {
    Path p;
    try {
      p = path(id);
    } catch (IllegalArgumentException ex) {
      throw new IOException("Invalid fight identifier");
    }
    if (!Files.exists(p)) throw new IOException("Recording not found");
    if (Files.isSymbolicLink(p) || Files.size(p) > MAX_RECORDING_BYTES)
      throw new IOException("Unsafe report");
    var f = decode(Files.readString(p));
    if (!f.id().equals(id)) throw new IOException("Report ID mismatch");
    return f;
  }

  public void save(FightRecord fight, int retention, long byteLimit) {
    submit(
        () -> {
          try {
            AtomicJson.write(path(fight.id()), fight);
            prune(retention, byteLimit);
          } catch (IOException ex) {
            errors.accept(ex.toString());
          }
        });
  }

  /**
   * Automatic pipeline entry; caller is already on the bounded storage worker. No destructive
   * pruning.
   */
  public Path persistFinalized(FightRecord fight) throws IOException {
    Path existing = path(fight.id());
    if (Files.exists(existing)) {
      readOne(fight.id());
      return existing;
    }
    Path file =
        Files.exists(existing)
            ? existing
            : dev.kwamster.platform.SafePaths.recordingFolder(directory.getParent(), fight)
                .resolve(fight.id() + ".json");
    AtomicJson.write(file, fight);
    return file;
  }

  public void delete(String id, Runnable done) {
    submit(
        () -> {
          try {
            Path p = path(id);
            if (Files.isSymbolicLink(p)) throw new IOException("Refusing symbolic link");
            Files.deleteIfExists(p);
            done.run();
          } catch (IOException ex) {
            errors.accept(ex.toString());
          }
        });
  }

  public List<FightRecord> readAll() throws IOException {
    Files.createDirectories(directory);
    List<FightRecord> result = new ArrayList<>();
    long loadedBytes = 0;
    int loadedEvents = 0;
    try (var files = Files.walk(directory, 2)) {
      for (var p :
          files
              .filter(Files::isRegularFile)
              .filter(f -> f.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparingLong(FightStore::modified).reversed())
              .limit(2000)
              .toList()) {
        try {
          if (Files.isSymbolicLink(p)
              || Files.isSymbolicLink(p.getParent())
              || Files.size(p) > MAX_RECORDING_BYTES) throw new IOException("Oversize or symlink");
          var f = decode(Files.readString(p));
          if (!p.getFileName().toString().equals(f.id() + ".json"))
            throw new IOException("ID mismatch");
          if (loadedBytes + Files.size(p) <= 64L * 1024 * 1024
              && loadedEvents + f.events().size() <= 50000
              && result.size() < 1000) {
            result.add(f);
            loadedBytes += Files.size(p);
            loadedEvents += f.events().size();
          } else if (result.size() < 1000) {
            result.add(f.summaryOnly());
          }
        } catch (IOException | RuntimeException ex) {
          errors.accept("Skipped report " + p.getFileName() + ": " + ex.getMessage());
        }
      }
    }
    result.sort(Comparator.comparingLong(FightRecord::startEpochMillis).reversed());
    return result;
  }

  public static FightRecord decode(String json) throws IOException {
    try {
      var f = AtomicJson.GSON.fromJson(json, FightRecord.class);
      if (f == null || (f.schemaVersion() < 1 || f.schemaVersion() > FightRecord.SCHEMA))
        throw new IOException("Unsupported report schema; preserved");
      UUID.fromString(f.id());
      if (f.statistics() == null
          || f.events() == null
          || f.events().size() > (f.manual() ? 1_000_000 : 10000)
          || f.outcome() == null
          || f.durationNanos() < 0) throw new IOException("Invalid report");
      long seq = -1, time = -1;
      for (var e : f.events()) {
        if (e == null
            || e.kind() == null
            || e.confidence() == null
            || e.sequence() <= seq
            || e.nanoTime() < time) throw new IOException("Invalid event order");
        seq = e.sequence();
        time = e.nanoTime();
      }
      return f;
    } catch (RuntimeException ex) {
      throw new IOException("Malformed report", ex);
    }
  }

  private record Stored(Path path, long start, long bytes) {}

  private void prune(int retention, long maxBytes) throws IOException {
    List<Stored> managed = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (var p :
          files.filter(f -> f.getFileName().toString().endsWith(".json")).limit(2000).toList()) {
        try {
          if (Files.isSymbolicLink(p) || Files.size(p) > MAX_RECORDING_BYTES) continue;
          var f = decode(Files.readString(p));
          if (p.getFileName().toString().equals(f.id() + ".json"))
            managed.add(new Stored(p, f.startEpochMillis(), Files.size(p)));
        } catch (IOException | RuntimeException ex) {
          /* Unknown and damaged files are preserved. */
        }
      }
    }
    managed.sort(Comparator.comparingLong(Stored::start).reversed());
    long total = 0;
    for (int i = 0; i < managed.size(); i++) {
      var f = managed.get(i);
      if (i >= retention || total + f.bytes() > maxBytes) Files.delete(f.path());
      else total += f.bytes();
    }
  }

  private static long modified(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (IOException ex) {
      return 0;
    }
  }

  private Path path(String id) {
    UUID.fromString(id);
    Path legacy = directory.resolve(id + ".json");
    if (Files.exists(legacy) || !Files.isDirectory(directory)) return legacy;
    try (var folders = Files.list(directory)) {
      for (var folder :
          folders
              .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
              .limit(2000)
              .toList()) {
        Path candidate = folder.resolve(id + ".json");
        if (Files.exists(candidate) && !Files.isSymbolicLink(candidate)) return candidate;
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot resolve recording", ex);
    }
    return legacy;
  }

  public Path recordingFile(String id) {
    return path(id);
  }

  public boolean await(long millis) throws InterruptedException {
    var barrier = new CompletableFuture<Void>();
    if (!submit(() -> barrier.complete(null))) return false;
    try {
      barrier.get(millis, TimeUnit.MILLISECONDS);
      return true;
    } catch (ExecutionException | TimeoutException ex) {
      return false;
    }
  }

  @Override
  public void close() {
    writer.shutdown();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS))
        errors.accept("Storage shutdown timed out");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
