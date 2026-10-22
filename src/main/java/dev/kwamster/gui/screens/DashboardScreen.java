package dev.kwamster.gui.screens;

import dev.kwamster.KwamsterClient;
import dev.kwamster.hud.CombatHud;
import java.util.Locale;

public final class DashboardScreen extends PanelScreen {
  public DashboardScreen() {
    super("Overview", "Modern combat / confidence-aware / local by default");
  }

  @Override
  protected void content() {
    label(
        KwamsterClient.engine.state()
            + " / "
            + (KwamsterClient.config.tracking ? "Tracking active" : "Tracking paused"));
    for (String s : CombatHud.lines) label(s);
    label("Critical conditions: observed; reset success: unavailable");
    label("Hits: correlated server damage events; reach: client geometry");
    if (!KwamsterClient.history.isEmpty()) {
      label("Recent fight / " + KwamsterClient.history.getFirst().outcome());
      double avg =
          KwamsterClient.history.stream()
              .limit(10)
              .mapToDouble(f -> f.statistics().accuracy())
              .average()
              .orElse(0);
      label(String.format(Locale.ROOT, "Recent 10 fights / average accuracy %.1f%%", avg));
    }
    action("Open fight history", () -> minecraft.setScreen(new HistoryScreen()));
  }

  @Override
  public void tick() {
    if (KwamsterClient.engine != null
        && minecraft.player != null
        && minecraft.player.tickCount % 10 == 0) init();
  }
}
