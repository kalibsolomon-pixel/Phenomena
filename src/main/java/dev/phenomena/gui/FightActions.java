package dev.phenomena.gui;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.platform.SafePaths;
import dev.phenomena.platform.SystemOpener;
import dev.phenomena.sharing.ReportExporter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * User-initiated file actions. All filesystem work runs on the storage worker; process launches run
 * on their own daemon thread; results come back as notices. Paths are built only from the game
 * directory and validated fight UUIDs.
 */
public final class FightActions {
  private FightActions() {}

  public static void openFolder(SafePaths.Managed which) {
    Path data = PhenomenaClient.dataDir;
    submit(
        () -> {
          Path dir = SafePaths.managed(data, which);
          SystemOpener.open(
              dir,
              true,
              error ->
                  Notices.post(
                      error == null ? "Opened " + display(dir) : error,
                      error == null ? Notices.Level.OK : Notices.Level.ERROR));
        },
        "Could not open folder");
  }

  /** Writes (or refreshes) the fight's report in exports/<id>/ and opens it in the browser. */
  public static void viewInBrowser(FightRecord fight) {
    report(fight, true, "Opening report in browser...");
  }

  public static void export(FightRecord fight) {
    report(fight, PhenomenaClient.config.openAfterExport, "Exporting report...");
  }

  private static void report(FightRecord fight, boolean open, String progress) {
    if (fight == null) return;
    var c = PhenomenaClient.config;
    var options = new ReportExporter.Options(c.anonymizeExports, c.exportMotion, c.reducedMotion);
    FightRecord inMemory = DataCache.full(fight.id());
    Path data = PhenomenaClient.dataDir;
    Notices.post(progress, Notices.Level.INFO);
    submit(
        () -> {
          FightRecord full = inMemory != null ? inMemory : PhenomenaClient.store.readOne(fight.id());
          PhenomenaClient.store.persistFinalized(full);
          Path folder = SafePaths.exportFolder(data, full, options.anonymize());
          ReportExporter.export(folder, full, options);
          Path file = SafePaths.reportFile(folder);
          if (!open) {
            Notices.post("Exported to " + display(file), Notices.Level.OK);
            return;
          }
          SystemOpener.open(
              file,
              false,
              error ->
                  Notices.post(
                      error == null ? "Opened " + display(file) : error,
                      error == null ? Notices.Level.OK : Notices.Level.ERROR));
        },
        "Report failed");
  }

  /** Deletes only the managed recording; exports are independent copies. */
  public static void delete(String id, Runnable after) {
    if (PhenomenaClient.store == null) return;
    PhenomenaClient.store.delete(
        id,
        () ->
            net.minecraft.client.Minecraft.getInstance()
                .execute(
                    () -> {
                      PhenomenaClient.history.removeIf(f -> f.id().equals(id));
                      PhenomenaClient.historyChanged();
                      DataCache.forget(id);
                      Notices.post("Recording deleted", Notices.Level.OK);
                      after.run();
                    }));
  }

  private interface IoTask {
    void run() throws IOException;
  }

  private static void submit(IoTask task, String failure) {
    if (PhenomenaClient.store == null) return;
    boolean queued =
        PhenomenaClient.store.submit(
            () -> {
              try {
                task.run();
              } catch (IOException | RuntimeException ex) {
                Notices.post(failure + ": " + ex.getMessage(), Notices.Level.ERROR);
              }
            });
    if (!queued) Notices.post(failure + ": storage busy", Notices.Level.ERROR);
  }

  /** Game-directory-relative text for notices, avoiding absolute user paths on screen. */
  static String display(Path p) {
    try {
      Path game = PhenomenaClient.dataDir.toAbsolutePath().getParent();
      return game.relativize(p.toAbsolutePath()).toString().replace('\\', '/');
    } catch (RuntimeException ex) {
      return p.getFileName().toString();
    }
  }
}
