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
      c.text("Offense: accuracy, reach and spacing, aim, weapon timing and critical hits.");
      c.text("Defense: incoming hits and who caused them, shields, jump-reset timing, damage and knockback.");
      c.text("Fight Flow: combos, who held the advantage over time, and exchange-by-exchange breakdowns.");
      c.text("History: every recorded fight, with an offline browser report you can open with one click.");
      c.row(
          new KButton("Hit sound settings", () -> {
                s.state.settings = dev.kwamster.gui.ui.UiState.SettingsSection.FEEDBACK;
                s.selectTab(UiState.Tab.SETTINGS);
              }).content(),
          new KButton("Open Recordings Folder", () -> FightActions.openFolder(dev.kwamster.platform.SafePaths.Managed.FIGHTS)).content());
      return;
    }
    var latest = KwamsterClient.history.getFirst();
    var r = latest.resolution();
    c.heading("Latest fight", dateTime(latest.startEpochMillis()) + " vs " + opponent(latest.opponentLabel(), latest.opponent()));
    var full = DataCache.full(latest.id());
    var fa = full == null ? null : DataCache.analytics(full);
    var ls = latest.statistics();
    c.stats(
        List.of(
            new Stat(
                "Result",
                r.classification().label,
                r.source().label + (r.derived() ? " (pre-0.1.3)" : ""),
                "Result, evidence source and confidence are stored separately. Confidence: "
                    + r.confidence().name().toLowerCase(Locale.ROOT)
                    + "."),
            new Stat("Duration", duration(latest.durationNanos()), ls.attempts + " attempts", "Recorded engagement length."),
            stat("accuracy", percent(Aggregate.trendValue(latest, TrendMetric.ACCURACY)), ls.hits + " / " + ls.accuracyDenominator()),
            stat(
                "reachWithin",
                fa == null || fa.reach.eligible() == 0 ? NONE : fa.reach.within() + " / " + fa.reach.eligible(),
                fa == null ? "timeline not loaded" : "eligible hits"),
            stat("longestCombo", Integer.toString(ls.longestCombo), ls.comboCount + " combos"),
            stat(
                "dominanceShare",
                fa == null ? NONE : percent(fa.dominance.share(dev.kwamster.analytics.Dominance.State.LOCAL)),
                fa == null ? "timeline not loaded" : fa.dominance.availability.label.toLowerCase(Locale.ROOT))));
    if (fa != null) {
      var lines = dev.kwamster.analytics.Interpretation.of(fa);
      for (int i = 1; i < Math.min(5, lines.size()); i++) c.text(lines.get(i));
    }
    c.heading("Recent performance", a.fights + " fights");
    c.stats(
        List.of(
            stat("accuracy", percent(a.accuracy()), a.hits + " / " + a.accuracyDenominator() + " attempts"),
            stat("hits", Integer.toString(a.hits), "server-confirmed source"),
            stat("averageReach", meters(a.averageReach()), a.reachCount + " hit samples"),
            stat("reachWithin", percent(a.reachWithinPercent()), a.reachWithin + " of " + a.reachEligible),
            stat("criticalRate", percent(a.criticalRate()), a.criticalConditions + " with crit conditions"),
            stat("averageCombo", number(a.averageLocalCombo(), 2), "longest " + a.longestCombo),
            stat(
                "dominanceShare",
                percent(a.dominanceShare(dev.kwamster.analytics.Dominance.State.LOCAL)),
                "opponent " + percent(a.dominanceShare(dev.kwamster.analytics.Dominance.State.OPPONENT))),
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
        new KButton("Offense", () -> s.selectTab(UiState.Tab.OFFENSE)).content(),
        new KButton("Fight Flow", () -> s.selectTab(UiState.Tab.FLOW)).content());
    c.note(
        "Hits are server-reported melee damage matched to your attacks; reach and aim are client"
            + " geometry. Results are confirmed only by an exact server announcement naming both"
            + " players; a kill message or a death alone is inferred, and an opponent's death with no"
            + " announcement stays Unknown.");
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
