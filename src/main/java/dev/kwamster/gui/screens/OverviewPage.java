package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;

import dev.kwamster.KwamsterClient;
import dev.kwamster.analytics.*;
import dev.kwamster.gui.DataCache;
import dev.kwamster.gui.FightActions;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Recent-performance summary: headline statistics, a switchable trend chart, recent fights. */
final class OverviewPage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  OverviewPage(KwamsterScreen s) {
    this.s = s;
  }

  private int window() {
    return KwamsterClient.config.trendWindow;
  }

  @Override
  public String title() {
    return "Overview";
  }

  @Override
  public String context() {
    int total = KwamsterClient.history.size();
    return total == 0 ? null : "Last " + Math.min(window(), total) + " of " + total + " fights";
  }

  @Override
  public void build(Content c) {
    var a = DataCache.aggregate(window());
    if (a.fights == 0) {
      c.heading("No fights recorded yet");
      c.text(
          "Start a fight with any living entity. KWAMSTER tracks combat in the background while this"
              + " window is closed; statistics appear here once a fight ends.");
      c.note(
          "Press " + KwamsterScreen.keyName() + " to open this dashboard. Recordings stay on this computer.",
          Theme.accent());
      c.heading("What you will see");
      c.text("Overview: accuracy, reach, critical and sprint hits, and hit timing across recent fights, with a trend chart.");
      c.text("Combat Analytics: reach distribution, a hitbox map of where your attacks landed, readiness, combos and momentum.");
      c.text("Fight History: every recorded fight, with an offline browser replay you can open with one click.");
      c.row(
          new KButton("Hit sound settings", () -> {
                s.state.settings = dev.kwamster.gui.ui.UiState.SettingsSection.FEEDBACK;
                s.selectTab(UiState.Tab.SETTINGS);
              }).content(),
          new KButton("Open Recordings Folder", () -> FightActions.openFolder(dev.kwamster.platform.SafePaths.Managed.FIGHTS)).content());
      return;
    }
    c.stats(
        List.of(
            stat("accuracy", percent(a.accuracy()), a.hits + " / " + a.attempts + " attempts"),
            stat("hits", Integer.toString(a.hits), "server-confirmed source"),
            stat("attempts", Integer.toString(a.attempts), a.uncertain + " uncertain"),
            stat("averageReach", meters(a.averageReach()), a.reachCount + " hit samples"),
            stat("maxReach", meters(a.maxReachOrNaN()), "client geometry"),
            stat("criticalRate", percent(a.criticalRate()), a.criticalConditions + " with crit conditions"),
            stat("sprintRate", percent(a.sprintRate()), a.sprintHits + " sprint hits"),
            stat("averageInterval", seconds(a.averageInterval()), "SD " + seconds(a.intervalDeviation())),
            stat("fights", Integer.toString(KwamsterClient.history.size()), a.fights + " in this summary")));

    c.heading("Performance trend", "per fight, oldest to newest");
    var metrics = TrendMetric.values();
    var chips = new AbstractWidget[metrics.length];
    for (int i = 0; i < metrics.length; i++) {
      var m = metrics[i];
      chips[i] =
          new KButton("trend:" + m, KButton.Style.CHIP, m::shortLabel, () -> {
                s.state.trend = m;
                s.rebuild();
              })
              .selected(() -> s.state.trend == m)
              .content();
    }
    c.row(chips);
    var m = s.state.trend;
    double[] values = a.trend(m);
    String[] labels = new String[values.length];
    for (int i = 0; i < labels.length; i++) labels[i] = shortDate(a.chronological.get(i).startEpochMillis());
    int missing = 0;
    for (double v : values) if (!Double.isFinite(v)) missing++;
    var cfg = KwamsterClient.config;
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(new Series(m.label, null, values, Theme.SERIES_1, Mark.LINE)),
            m.min,
            m.max,
            m.unit,
            "",
            labels,
            cfg.chartGrid,
            cfg.chartPoints,
            "No " + m.label.toLowerCase(Locale.ROOT) + " samples yet"));
    String explain =
        switch (m) {
          case ACCURACY -> "Accuracy counts uncertain attacks as attempts, so it is a conservative lower bound.";
          case REACH -> "Average client-measured distance on correlated hits.";
          case CRITICAL -> "Share of correlated hits made with critical conditions. Critical damage is not verified.";
          case TIMING -> "Average gap between consecutive correlated hits; shorter is not automatically better.";
        };
    c.text(explain + (missing > 0 ? " " + missing + " fight(s) without samples are shown as gaps." : ""));

    c.heading("Recent fights", "select to inspect");
    var recent = KwamsterClient.history.subList(0, Math.min(5, KwamsterClient.history.size()));
    int rowH = c.compact ? 12 : 14;
    c.widget(
        new FightList(
            List.copyOf(recent),
            rowH,
            () -> null,
            id -> openDetail(id),
            id -> openDetail(id),
            id -> FightActions.viewInBrowser(DataCache.summary(id)),
            s::reveal),
        FightList.heightFor(recent.size(), rowH));
    c.row(
        new KButton("All fights", () -> s.selectTab(UiState.Tab.HISTORY)).content(),
        new KButton("Combat analytics", () -> s.selectTab(UiState.Tab.ANALYTICS)).content());
    c.note(
        "Hits are server-reported melee damage matched to your attacks; reach and aim are client"
            + " geometry; outcomes are conservative (opponent death is Unknown, not a win).");
  }

  private void openDetail(String id) {
    s.state.openFight(id);
    s.rebuild();
  }

  static Stat stat(String key, String value, String caption) {
    var d = MetricDefs.get(key);
    return new Stat(d.label(), value, caption, d.confidence() + ": " + d.help());
  }
}
