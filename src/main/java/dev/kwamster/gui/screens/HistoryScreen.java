package dev.kwamster.gui.screens;

import dev.kwamster.KwamsterClient;
import java.util.*;

public final class HistoryScreen extends PanelScreen {
  private boolean newest = true;
  private int filter;

  public HistoryScreen() {
    super("History", "Local recordings / select a fight for details");
  }

  @Override
  protected void content() {
    action(
        "Sort: " + (newest ? "Newest first" : "Oldest first"),
        () -> {
          newest = !newest;
          init();
        });
    action(
        "Filter: " + new String[] {"All", "Unknown", "Interrupted", "Loss", "Win"}[filter],
        () -> {
          filter = (filter + 1) % 5;
          init();
        });
    var fights = new ArrayList<>(KwamsterClient.history);
    if (!newest) Collections.reverse(fights);
    if (fights.isEmpty()) label("No recorded fights yet.");
    for (var f : fights) {
      if (filter > 0
          && !f.outcome()
              .name()
              .equals(new String[] {"", "UNKNOWN", "INTERRUPTED", "LOSS", "WIN"}[filter])) continue;
      String date =
          java.time.Instant.ofEpochMilli(f.startEpochMillis())
              .atZone(java.time.ZoneId.systemDefault())
              .toLocalDateTime()
              .toString();
      action(
          date + " / " + f.outcome() + " / " + f.statistics().hits + " hits",
          () -> {
            if (f.events().isEmpty() && f.statistics().attempts > 0) {
              KwamsterClient.status = "Loading fight timeline...";
              KwamsterClient.store.loadOne(
                  f.id(),
                  full ->
                      minecraft.execute(
                          () -> {
                            if (minecraft.screen == this) {
                              KwamsterClient.status = "Local recording loaded";
                              minecraft.setScreen(new FightDetailScreen(full));
                            }
                          }));
            } else minecraft.setScreen(new FightDetailScreen(f));
          });
    }
  }
}
