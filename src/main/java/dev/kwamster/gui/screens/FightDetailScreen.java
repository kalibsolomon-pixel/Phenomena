package dev.kwamster.gui.screens;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.sharing.ReportExporter;
import java.util.Locale;

public final class FightDetailScreen extends PanelScreen {
  private final FightRecord fight;

  public FightDetailScreen(FightRecord fight) {
    super("Fight details", fight.outcome() + " / " + fight.reason());
    this.fight = fight;
  }

  @Override
  protected void content() {
    var s = fight.statistics();
    label("Fight " + fight.id());
    label(
        String.format(
            Locale.ROOT,
            "Duration %.1fs / %d hits / %d attacks / %.1f%%",
            fight.durationNanos() / 1e9,
            s.hits,
            s.attempts,
            s.accuracy()));
    label("Empty misses " + s.emptyMisses + " / Uncertain " + s.uncertain);
    label(
        String.format(
            Locale.ROOT, "Observed hit reach %.2f m / max %.2f m", s.averageReach(), s.maxReach));
    label("Critical conditions " + s.criticalConditions + " / Sprint hits " + s.sprintHits);
    label("Jump candidates " + s.jumpCandidates + " / Success unavailable");
    label("Longest combo " + s.longestCombo);
    label(
        String.format(
            Locale.ROOT,
            "Hit interval %.2fs / deviation %.2fs",
            s.averageInterval(),
            s.intervalDeviation()));
    action(
        "Export anonymized local report + replay",
        () -> {
          var dir = KwamsterClient.dataDir.resolve("exports").resolve(fight.id());
          KwamsterClient.store.submit(
              () -> {
                try {
                  ReportExporter.export(dir, fight, true);
                  minecraft.execute(
                      () -> KwamsterClient.status = "Exported to kwamster/exports/" + fight.id());
                } catch (java.io.IOException ex) {
                  minecraft.execute(
                      () -> KwamsterClient.status = "Export failed: " + ex.getMessage());
                }
              });
        });
    action(
        "Delete this recording",
        () ->
            minecraft.setScreen(
                new ConfirmScreen(
                    "Delete this local fight recording?",
                    () ->
                        KwamsterClient.store.delete(
                            fight.id(),
                            () ->
                                minecraft.execute(
                                    () -> {
                                      KwamsterClient.history.removeIf(
                                          f -> f.id().equals(fight.id()));
                                      minecraft.setScreen(new HistoryScreen());
                                    })),
                    () -> minecraft.setScreen(this))));
    label("Event timeline / monotonic time / confidence");
    long base = fight.events().isEmpty() ? 0 : fight.events().getFirst().nanoTime();
    for (var e : fight.events()) {
      label(
          String.format(
              Locale.ROOT,
              "%6.2fs  %s / %s",
              (e.nanoTime() - base) / 1e9,
              e.kind(),
              e.confidence()));
      if (e.attack() != null)
        label(
            String.format(
                Locale.ROOT,
                " cooldown %.2f / %.2fm / aim %.2f %.2f %.2f",
                e.attack().cooldown(),
                e.attack().reach(),
                e.attack().aimX(),
                e.attack().aimY(),
                e.attack().aimZ()));
    }
  }
}
