package dev.phenomena.recording.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One-time, non-destructive copy of the legacy {@code kwamster} data directory into
 * {@code phenomena}. Runs on the storage worker before history load.
 *
 * <ul>
 *   <li>The legacy directory is never modified or deleted; migration copies forward only.
 *   <li>Files copy to a temporary sibling and move atomically, so a crash never leaves a
 *       partial file that a later pass could mistake for a conflict.
 *   <li>Existing identical files are skipped, so an interrupted migration safely re-runs.
 *   <li>Conflicting existing files are never overwritten; they are recorded and left alone.
 *   <li>A committed {@code migration.json} marker prevents repeated migration on startup.
 *   <li>Symbolic links inside the legacy tree are ignored, never followed.
 * </ul>
 */
public final class LegacyDataMigration {
  public static final String LEGACY_DIR = "kwamster";
  public static final String MARKER = "migration.json";
  public static final int SCHEMA = 1;
  /** Bounded conflict list so a pathological tree cannot grow the marker without limit. */
  private static final int MAX_LOGGED_CONFLICTS = 64;

  /** Summary of one migration pass; {@code ran} is false when the marker already existed. */
  public record Result(
      boolean ran,
      boolean legacyPresent,
      int copied,
      int identical,
      int conflicts,
      List<String> conflictNames) {
    public String summary() {
      if (!ran) return "Legacy data already migrated";
      if (!legacyPresent) return "Fresh data directory";
      String text = "Legacy data migrated: " + copied + " copied, " + identical + " already present";
      return conflicts == 0 ? text : text + ", " + conflicts + " conflicts preserved";
    }
  }

  private LegacyDataMigration() {}

  public static Result migrate(Path gameDir, Consumer<String> log) {
    Path data = gameDir.resolve("phenomena");
    Path legacy = gameDir.resolve(LEGACY_DIR);
    try {
      if (Files.isRegularFile(data.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) {
        return new Result(false, false, 0, 0, 0, List.of());
      }
      boolean legacyPresent = Files.isDirectory(legacy) && !Files.isSymbolicLink(legacy);
      int copied = 0, identical = 0, conflicts = 0;
      List<String> conflictNames = new ArrayList<>();
      if (legacyPresent) {
        try (Stream<Path> files = Files.walk(legacy)) {
          for (Path src : (Iterable<Path>) files.sorted()::iterator) {
            if (Thread.interrupted()) throw new InterruptedException();
            if (Files.isSymbolicLink(src) || !Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS))
              continue;
            Path rel = legacy.relativize(src);
            Path dst = data.resolve(rel.toString()).normalize();
            if (!dst.startsWith(data.normalize())) continue; // defensive; walk() cannot produce it
            if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) {
              if (sameContent(src, dst)) identical++;
              else {
                conflicts++;
                if (conflictNames.size() < MAX_LOGGED_CONFLICTS) conflictNames.add(rel.toString());
              }
              continue;
            }
            Files.createDirectories(dst.getParent());
            Path tmp = dst.resolveSibling(dst.getFileName() + ".migrating");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
              Files.move(
                  tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
              copied++;
            } catch (AtomicMoveNotSupportedException ex) {
              Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
              copied++;
            }
          }
        }
      }
      Files.createDirectories(data);
      Map<String, Object> marker = new LinkedHashMap<>();
      marker.put("schema", SCHEMA);
      marker.put("source", LEGACY_DIR);
      marker.put("epochMillis", System.currentTimeMillis());
      marker.put("copied", copied);
      marker.put("identical", identical);
      marker.put("conflicts", conflicts);
      marker.put("conflictNames", conflictNames);
      AtomicJson.write(data.resolve(MARKER), marker);
      var result = new Result(true, legacyPresent, copied, identical, conflicts, conflictNames);
      if (log != null) log.accept(result.summary());
      return result;
    } catch (IOException | RuntimeException | InterruptedException ex) {
      // No marker was written, so the next startup retries idempotently.
      if (log != null) log.accept("Legacy data migration deferred: " + ex.getMessage());
      return new Result(true, legacyPresentSafe(legacy), 0, 0, 0, List.of());
    }
  }

  private static boolean sameContent(Path a, Path b) throws IOException {
    if (Files.size(a) != Files.size(b)) return false;
    return Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
  }

  private static boolean legacyPresentSafe(Path legacy) {
    try {
      return Files.isDirectory(legacy);
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
