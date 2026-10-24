package dev.kwamster.recording.storage;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.platform.SafePaths;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Atomic checkpoints survive client crashes; recovered sessions never resume on a connection. */
public final class SessionRecovery {
  private final FightStore store;
  private final Path folder;
  private final Consumer<String> status;

  public SessionRecovery(FightStore store, Path data, Consumer<String> status) {
    this.store = store;
    folder = data.resolve("pending-sessions");
    this.status = status;
  }

  public void checkpoint(FightRecord record) {
    store.submit(
        () -> {
          try {
            AtomicJson.write(folder.resolve(SafePaths.requireUuid(record.id()) + ".json"), record);
          } catch (IOException ex) {
            status.accept("Manual checkpoint failed: " + ex.getMessage());
          }
        });
  }

  /**
   * On the storage worker, before history is delivered. Originals and damaged evidence are kept.
   */
  public List<FightRecord> recover(List<FightRecord> history) {
    var records = new ArrayList<>(history);
    if (!Files.isDirectory(folder) || Files.isSymbolicLink(folder)) return records;
    try (var paths = Files.list(folder)) {
      for (var p : paths.filter(p -> p.toString().endsWith(".json")).toList())
        try {
          if (Files.isSymbolicLink(p) || Files.size(p) > FightStore.MAX_RECORDING_BYTES) continue;
          var f = FightStore.decode(Files.readString(p));
          if (!f.manual() || !p.getFileName().toString().equals(f.id() + ".json")) continue;
          if (records.stream().anyMatch(r -> r.id().equals(f.id()))) continue;
          store.persistFinalized(f);
          records.add(f);
          status.accept("Recovered manual session: " + f.session().termination());
        } catch (IOException | RuntimeException ex) {
          status.accept("Checkpoint preserved; recovery failed: " + ex.getMessage());
        }
    } catch (IOException ex) {
      status.accept("Session recovery failed: " + ex.getMessage());
    }
    return records;
  }
}
