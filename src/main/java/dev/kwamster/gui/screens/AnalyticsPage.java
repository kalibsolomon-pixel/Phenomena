package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;
import static dev.kwamster.gui.screens.OverviewPage.stat;

import dev.kwamster.KwamsterClient;
import dev.kwamster.analytics.*;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.gui.DataCache;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState.AnalyticsSection;
import dev.kwamster.gui.ui.UiState.Scope;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Detailed statistics workspace, one section at a time. */
final class AnalyticsPage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  AnalyticsPage(KwamsterScreen s) {
    this.s = s;
  }

  @Override
  public String title() {
    return "Combat Analytics";
  }

  @Override
  public List<String> sections() {
    return Arrays.stream(AnalyticsSection.values()).map(v -> v.label).toList();
  }

  @Override
  public int section() {
    return s.state.analytics.ordinal();
  }

  @Override
  public void section(int index) {
    s.state.analytics = AnalyticsSection.values()[index];
  }

  @Override
  public List<AbstractWidget> toolbar(KwamsterScreen screen) {
    var scopes = Scope.values();
    return List.of(
        screen.dropdown(
            "scope",
            () -> s.state.scope.label,
            Arrays.stream(scopes).map(v -> v.label).toList(),
            () -> s.state.scope.ordinal(),
            i -> s.state.scope = scopes[i]));
  }

  private boolean grid() {
    return KwamsterClient.config.chartGrid;
  }

  private boolean points() {
    return KwamsterClient.config.chartPoints;
  }

  @Override
  public void build(Content c) {
    var a = DataCache.aggregate(s.state.scope.limit);
    if (a.fights == 0) {
      c.heading("No data in scope");
      c.text("Analytics appear after the first recorded fight.");
      return;
    }
    String coverage =
        a.timelineFights == a.fights
            ? a.fights + " fights"
            : a.fights + " fights; per-hit detail from " + a.timelineFights + " loaded timelines";
    switch (s.state.analytics) {
      case ACCURACY -> accuracy(c, a, coverage);
      case REACH -> reach(c, a, coverage);
      case AIM -> aim(c, a, coverage);
      case TIMING -> timing(c, a, coverage);
      case CRITICAL -> rate(c, a, coverage, true);
      case SPRINT -> rate(c, a, coverage, false);
      case JUMP -> jumps(c, a, coverage);
      case COMBOS -> combos(c, a, coverage);
    }
  }

  private String[] fightLabels(Aggregate a) {
    String[] labels = new String[a.chronological.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = shortDate(a.chronological.get(i).startEpochMillis());
    return labels;
  }

  private void accuracy(Content c, Aggregate a, String coverage) {
    c.heading("Attempts", coverage);
    c.stats(
        List.of(
            stat("attempts", Integer.toString(a.attempts), "observed"),
            stat("hits", Integer.toString(a.hits), "confirmed source"),
            stat("emptyMisses", Integer.toString(a.emptyMisses), "confirmed misses"),
            stat("uncertain", Integer.toString(a.uncertain), "not proven misses"),
            stat("accuracy", percent(a.accuracy()), "conservative")));
    c.block(
        StackBar.height(),
        new StackBar(
            new String[] {"Correlated hits", "Empty swings", "Uncertain"},
            new int[] {a.hits, a.emptyMisses, a.uncertain},
            new int[] {Theme.SERIES_1, Theme.SERIES_2, Theme.DIM}));
    c.heading("Accuracy per fight");
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(new Series("Accuracy", null, a.trend(TrendMetric.ACCURACY), Theme.SERIES_1, Mark.LINE)),
            0, 100, "%", "", fightLabels(a), grid(), points(), "No attempts recorded"));
    c.note(
        "Empty swings are the only confirmed misses. An entity attack without a matching server"
            + " damage report is uncertain: the server may have rejected it, or the report may have"
            + " arrived late. Uncertain attacks stay in the accuracy denominator.");
  }

  private void reach(Content c, Aggregate a, String coverage) {
    c.heading("Reach", coverage);
    double[] hitReach = a.hitSamples.stream().mapToDouble(FightAnalytics.Hit::reach).toArray();
    long estimated = a.missSamples.stream().filter(m -> m.geometry() && m.estimatedReach() >= 0).count();
    c.stats(
        List.of(
            stat("averageReach", meters(a.averageReach()), a.reachCount + " hit samples"),
            stat("maxReach", meters(a.maxReachOrNaN()), "correlated hits"),
            new Stat("Estimated miss distance", Long.toString(estimated), "attempt samples",
                "Observed: distance on attempts with no correlated hit. Not hit distance.")));
    c.heading("Distribution", "0.5 m bins");
    int n = FightAnalytics.REACH_BINS + 1;
    String[] labels = new String[n];
    for (int i = 0; i < n; i++)
      labels[i] = i == n - 1 ? number(FightAnalytics.REACH_MAX, 0) + "+" : number(i * FightAnalytics.REACH_BIN, 1);
    c.block(
        c.chartHeight(),
        new Bars(labels, a.hitReachBins, "Hit distance", Theme.SERIES_1, a.missReachBins,
            "Estimated, no hit", Theme.SERIES_2, null, "No reach samples in loaded timelines"));
    c.heading("Reach over time", "each hit, oldest to newest");
    double[] missIdx = new double[(int) estimated];
    double[] missReach = new double[(int) estimated];
    // Place estimates at the hit index they followed, so both series share one axis.
    int hi = 0, k = 0;
    var hits = a.hitSamples;
    for (var m : a.missSamples) {
      if (!m.geometry() || m.estimatedReach() < 0) continue;
      while (hi < hits.size() && hits.get(hi).time() <= m.time()) hi++;
      missIdx[k] = Math.max(0, hi - .5);
      missReach[k++] = m.estimatedReach();
    }
    // Misses are matched within a fight only approximately; keep them visually secondary.
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(
                new Series("Hit distance", null, hitReach, Theme.SERIES_1, Mark.LINE),
                new Series("Estimated, no hit", missIdx, missReach, Theme.SERIES_2, Mark.HOLLOW)),
            0, Double.NaN, "m", "hit #", null, grid(), points(), "No reach samples"));
    c.heading("Per-hit values", "latest 50");
    List<String[]> rows = new ArrayList<>();
    for (int i = hits.size() - 1; i >= 0 && rows.size() < 50; i--) {
      var h = hits.get(i);
      rows.add(new String[] {
        "#" + (i + 1), meters(h.reach()), h.rayContact() ? "ray contact" : "nearest box",
        number(h.cooldown(), 2), h.readiness() == null ? NONE : title(h.readiness().name())
      });
    }
    c.block(
        Table.height(rows.size()),
        new Table(new String[] {"Hit", "Reach", "Measured by", "Cooldown", "Readiness"},
            new float[] {1, 1.2f, 1.6f, 1.2f, 1.2f}, new boolean[] {false, true, false, true, false}, rows));
    c.note(
        "Reach is the client-side distance from your eye to the target's interpolated hitbox. It"
            + " cannot account for server position history or latency and is never a reach verdict.");
  }

  private void aim(Content c, Aggregate a, String coverage) {
    c.heading("Aim placement", coverage);
    int contacts = a.center + a.edge + a.high + a.low;
    c.stats(
        List.of(
            new Stat("Center", Integer.toString(a.center), pct(a.center, contacts), "Middle of the face, between the quartiles."),
            new Stat("Edge", Integer.toString(a.edge), pct(a.edge, contacts), "Outer 15% of the face width."),
            new Stat("High", Integer.toString(a.high), pct(a.high, contacts), "Top quartile of the box."),
            new Stat("Low", Integer.toString(a.low), pct(a.low, contacts), "Bottom quartile of the box.")));
    var modes =
        new AbstractWidget[] {
          new KButton("aim:markers", KButton.Style.CHIP, () -> "Individual impacts", () -> {
                s.state.aimHeatmap = false;
                s.rebuild();
              }).selected(() -> !s.state.aimHeatmap).content(),
          new KButton("aim:heat", KButton.Style.CHIP, () -> "Aggregate density", () -> {
                s.state.aimHeatmap = true;
                s.rebuild();
              }).selected(() -> s.state.aimHeatmap).content()
        };
    c.row(modes);
    List<double[]> hp = new ArrayList<>(), op = new ArrayList<>();
    for (var h : a.hitSamples) if (h.rayContact()) hp.add(new double[] {h.aimHorizontal(), h.aimVertical()});
    for (var m : a.missSamples) if (m.rayContact()) op.add(new double[] {m.aimHorizontal(), m.aimVertical()});
    c.block(
        c.compact ? 130 : 160,
        new Hitbox(hp.toArray(double[][]::new), op.toArray(double[][]::new), s.state.aimHeatmap));
    c.note(
        "Positions are recorded ray intersections, normalized to the target's box (0 to 1 across the"
            + " contacted face and from feet to head). Attacks without ray contact are not plotted."
            + " Placement is descriptive: a center hit is not automatically better.");
  }

  private static String pct(int v, int total) {
    return total == 0 ? "no contacts" : percent(100.0 * v / total);
  }

  private static String title(String grade) {
    return grade.charAt(0) + grade.substring(1).toLowerCase(Locale.ROOT);
  }

  private void timing(Content c, Aggregate a, String coverage) {
    c.heading("Hit timing", coverage);
    double mean = a.averageInterval(), sd = a.intervalDeviation();
    c.stats(
        List.of(
            stat("averageInterval", seconds(mean), a.intervalCount + " intervals"),
            stat("intervalDeviation", seconds(sd), "consistency"),
            new Stat("Variation", Double.isFinite(mean) && mean > 0 ? percent(100 * sd / mean) : NONE,
                "SD / mean", "Coefficient of variation; lower is more regular."),
            new Stat("Avg cooldown", a.hits == 0 ? NONE : number(cooldownMean(a), 2),
                "at correlated hits", "Mean attack-strength scale (0-1) when the hit was made.")));
    c.heading("Readiness categories", "correlated hits, recorded thresholds");
    var cfg = KwamsterClient.config;
    String[] gl = {"Perfect", "Great", "Normal", "Poor"};
    int[] colors = new int[4];
    for (int i = 0; i < 4; i++) colors[i] = 0xFF000000 | cfg.feedback[i].color;
    c.block(
        c.compact ? 80 : 96,
        new Bars(gl, a.readiness, "Hits", Theme.SERIES_1, null, null, 0, colors, "No correlated hits in loaded timelines"));
    c.heading("Cooldown readiness distribution", "attack strength at hit");
    String[] bins = new String[FightAnalytics.COOLDOWN_BINS];
    for (int i = 0; i < bins.length; i++) bins[i] = number(i / 10.0, 1);
    c.block(
        c.chartHeight(),
        new Bars(bins, a.cooldownBins, "Hits", Theme.SERIES_1, null, null, 0, null, "No correlated hits"));
    c.heading("Intervals", "consecutive correlated hits");
    double[] iv = a.intervals.stream().mapToDouble(Double::doubleValue).toArray();
    c.block(
        c.chartHeight(),
        new Chart(List.of(new Series("Interval", null, iv, Theme.SERIES_1, Mark.LINE)),
            0, Double.NaN, "s", "interval #", null, grid(), points(), "Needs two hits in one fight"));
    c.note(
        "Readiness is a transparent cooldown classification (Perfect >= "
            + number(cfg.perfectCooldown, 2) + ", Great >= " + number(cfg.greatCooldown, 2)
            + ", Normal >= 0.50 for new fights). It is not an objective skill or damage score.");
  }

  private static double cooldownMean(Aggregate a) {
    return a.hitSamples.stream().mapToDouble(FightAnalytics.Hit::cooldown).average().orElse(Double.NaN);
  }

  private void rate(Content c, Aggregate a, String coverage, boolean critical) {
    c.heading(critical ? "Critical hits" : "Sprint hits", coverage);
    int count = critical ? a.criticalConditions : a.sprintHits;
    double rate = critical ? a.criticalRate() : a.sprintRate();
    c.stats(
        List.of(
            new Stat(critical ? "With crit conditions" : "Sprint hits", Integer.toString(count), "correlated hits",
                critical ? "Observed: cooldown > 0.9, falling, airborne, not sprinting, climbing, swimming or riding."
                    : "Observed: sprint state at the attack."),
            stat(critical ? "criticalRate" : "sprintRate", percent(rate), "of " + a.hits + " hits"),
            stat("hits", Integer.toString(a.hits), "denominator")));
    c.heading("Rate per fight");
    double[] series =
        a.perFightHitRate(st -> critical ? st.criticalConditions : st.sprintHits);
    c.block(
        c.chartHeight(),
        new Chart(List.of(new Series(critical ? "Crit-condition rate" : "Sprint-hit rate", null, series, Theme.SERIES_1, Mark.LINE)),
            0, 100, "%", "", fightLabels(a), grid(), points(), "No correlated hits"));
    c.note(
        critical
            ? "Critical conditions mirror vanilla 1.21.11 eligibility observed on the client. The"
                + " server's damage report does not include an amount, so the critical multiplier is never confirmed."
            : "Sprint state is observed at the attack. Extra knockback is not measured.");
  }

  private void jumps(Content c, Aggregate a, String coverage) {
    c.heading("Jump resets", coverage);
    c.stats(
        List.of(
            stat("jumpCandidates", Integer.toString(a.jumpCandidates), "inside window"),
            new Stat("Incoming hits", Integer.toString(a.incoming), "opponent damage", "Confirmed: server damage events against you from the tracked opponent."),
            new Stat("Reset success", "Unavailable", "not estimated",
                "No knockback baseline exists, so success is not estimated."),
            new Stat("Motion samples", Integer.toString(a.motionSamples), "10 Hz in fights",
                "Velocity traces recorded during engagements for future controlled analysis.")));
    c.heading("Timing after incoming damage", "jump time minus damage time");
    int bins = 10;
    int[] counts = new int[bins];
    int[] candidates = new int[bins];
    String[] labels = new String[bins];
    for (int i = 0; i < bins; i++) labels[i] = (i * 100) + "";
    for (var j : a.jumps) {
      int b = Math.max(0, Math.min(bins - 1, (int) (j.offsetMillis() / 100)));
      if (j.candidate()) candidates[b]++;
      else counts[b]++;
    }
    c.block(
        c.chartHeight(),
        new Bars(labels, candidates, "Candidate", Theme.SERIES_1, counts, "Outside window", Theme.SERIES_2, null,
            "No jumps within 1 s after incoming damage"));
    c.text(
        "Bins are 100 ms after the most recent incoming hit. "
            + a.jumpsWithoutDamage + " other jump(s) had no incoming hit in the preceding second.");
    c.note(
        "Jump-reset success is unavailable. A candidate is a ground-to-air jump with the jump key"
            + " held, inside the configured window after incoming damage. Whether it reduced"
            + " knockback needs a validated baseline that does not exist yet.",
        Theme.WARN);
  }

  private void combos(Content c, Aggregate a, String coverage) {
    c.heading("Combos", coverage);
    c.stats(
        List.of(
            stat("longestCombo", Integer.toString(a.longestCombo), "all fights in scope"),
            stat("averageCombo", number(a.averageCombo(), 2), a.comboLengths.size() + " chains"),
            new Stat("Incoming hits", Integer.toString(a.incoming), "break combos", "Retaliation by the tracked opponent resets the combo.")));
    var latest = a.latestTimeline;
    if (latest == null) {
      c.note("Combo progression needs a loaded fight timeline.");
      return;
    }
    String when = dateTime(latest.fight.startEpochMillis());
    c.heading("Combo progression", "latest fight / " + when);
    double[] combo = latest.hits.stream().mapToDouble(FightAnalytics.Hit::combo).toArray();
    c.block(
        c.chartHeight(),
        new Chart(List.of(new Series("Combo", null, combo, Theme.SERIES_1, Mark.LINE)),
            0, Double.NaN, "", "hit #", null, grid(), points(), "No correlated hits in this fight"));
    c.heading("Estimated momentum", "latest fight / seconds");
    double[] samples = latest.momentumSamples(160);
    double[] xs = new double[samples.length];
    for (int i = 0; i < xs.length; i++) xs[i] = latest.duration * i / (xs.length - 1);
    c.block(
        c.chartHeight(),
        new Chart(List.of(new Series("Momentum (estimate)", xs, samples, Theme.SERIES_1, Mark.LINE)),
            -1, 1, "", "s", null, grid(), false, "No exchanges"));
    c.note(MetricDefs.get("momentum").help() + " An illustrative exchange indicator only.", Theme.WARN);
  }

  static int gradeColor(Grade g) {
    return 0xFF000000 | KwamsterClient.config.feedback[g.ordinal()].color;
  }
}
