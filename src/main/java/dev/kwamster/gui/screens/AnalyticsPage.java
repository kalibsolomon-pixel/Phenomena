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
    for (int i = 0; i < labels.length; i++)
      labels[i] = shortDate(a.chronological.get(i).startEpochMillis());
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
            List.of(
                new Series(
                    "Accuracy", null, a.trend(TrendMetric.ACCURACY), Theme.SERIES_1, Mark.LINE)),
            0,
            100,
            "%",
            "",
            fightLabels(a),
            grid(),
            points(),
            "No attempts recorded"));
    c.note(
        "Empty swings are the only confirmed misses. An entity attack without a matching server"
            + " damage report is uncertain: the server may have rejected it, or the report may have"
            + " arrived late. Uncertain attacks stay in the accuracy denominator.");
  }

  private void reach(Content c, Aggregate a, String coverage) {
    c.heading("Reach", coverage);
    double[] hitReach = a.hitSamples.stream().mapToDouble(FightAnalytics.Hit::reach).toArray();
    long estimated =
        a.missSamples.stream().filter(m -> m.geometry() && m.estimatedReach() >= 0).count();
    c.stats(
        List.of(
            stat("averageReach", meters(a.averageReach()), a.reachCount + " hit samples"),
            stat("maxReach", meters(a.maxReachOrNaN()), "correlated hits"),
            new Stat(
                "Estimated miss distance",
                Long.toString(estimated),
                "attempt samples",
                "Observed: distance on attempts with no correlated hit. Not hit distance.")));
    int perfect =
        a.chronological.stream()
            .filter(f -> f.schemaVersion() >= 2)
            .mapToInt(f -> f.statistics().perfectReachHits)
            .sum();
    c.text(
        "Perfect-reach hits: "
            + perfect
            + " (new recordings; source-correlated ray contacts inside the recorded reach"
            + " window).");
    c.heading("Distribution", "0.5 m bins");
    int n = FightAnalytics.REACH_BINS + 1;
    String[] labels = new String[n];
    for (int i = 0; i < n; i++)
      labels[i] =
          i == n - 1
              ? number(FightAnalytics.REACH_MAX, 0) + "+"
              : number(i * FightAnalytics.REACH_BIN, 1);
    c.block(
        c.chartHeight(),
        new Bars(
            labels,
            a.hitReachBins,
            "Hit distance",
            Theme.SERIES_1,
            a.missReachBins,
            "Estimated, no hit",
            Theme.SERIES_2,
            null,
            "No reach samples in loaded timelines"));
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
            0,
            Double.NaN,
            "m",
            "hit #",
            null,
            grid(),
            points(),
            "No reach samples"));
    c.heading("Per-hit values", "latest 50");
    List<String[]> rows = new ArrayList<>();
    for (int i = hits.size() - 1; i >= 0 && rows.size() < 50; i--) {
      var h = hits.get(i);
      rows.add(
          new String[] {
            "#" + (i + 1),
            meters(h.reach()),
            h.rayContact() ? "ray contact" : "nearest box",
            number(h.cooldown(), 2),
            h.measurement() == null || h.measurement().timing() == null
                ? NONE
                : h.measurement().timing().grade().name()
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Hit", "Reach", "Measured by", "Strength scale", "Attempt timing"},
            new float[] {1, 1.2f, 1.6f, 1.2f, 1.2f},
            new boolean[] {false, true, false, true, false},
            rows));
    c.note(
        "Reach is the client-side distance from your eye to the target's interpolated hitbox. It"
            + " cannot account for server position history or latency and is never a reach"
            + " verdict.");
  }

  private void aim(Content c, Aggregate a, String coverage) {
    c.heading("Melee aim", coverage);
    int[] quality = new int[4];
    for (var h : a.hitSamples)
      if (h.measurement() != null
          && h.measurement().aimQuality() != null
          && h.measurement().aimQuality().ordinal() < 4)
        quality[h.measurement().aimQuality().ordinal()]++;
    c.block(
        c.compact ? 80 : 96,
        new Bars(
            new String[] {"Ideal", "Good", "Peripheral", "Poor"},
            quality,
            "Client geometry",
            Theme.SERIES_1,
            null,
            null,
            0,
            new int[] {0xFF56B4E9, 0xFF009E73, 0xFFE69F00, 0xFFCC79A7},
            "Aim quality unavailable for historical recordings"));
    c.note(
        "Ideal alignment follows the nearest reachable surface with an edge inset. Elevation and"
            + " target pose change this region; opponent eye level is not a universal target."
            + " Colors indicate a geometry heuristic, not damage quality. Browser reports show the"
            + " surface regions for each hit.");
    c.heading("Raw placement", "normalized coordinates retained separately");
    int contacts = a.center + a.edge + a.high + a.low;
    c.stats(
        List.of(
            new Stat(
                "Center",
                Integer.toString(a.center),
                pct(a.center, contacts),
                "Middle of the face, between the quartiles."),
            new Stat(
                "Edge",
                Integer.toString(a.edge),
                pct(a.edge, contacts),
                "Outer 15% of the face width."),
            new Stat(
                "High",
                Integer.toString(a.high),
                pct(a.high, contacts),
                "Top quartile of the box."),
            new Stat(
                "Low",
                Integer.toString(a.low),
                pct(a.low, contacts),
                "Bottom quartile of the box.")));
    var modes =
        new AbstractWidget[] {
          new KButton(
                  "aim:markers",
                  KButton.Style.CHIP,
                  () -> "Individual impacts",
                  () -> {
                    s.state.aimHeatmap = false;
                    s.rebuild();
                  })
              .selected(() -> !s.state.aimHeatmap)
              .content(),
          new KButton(
                  "aim:heat",
                  KButton.Style.CHIP,
                  () -> "Aggregate density",
                  () -> {
                    s.state.aimHeatmap = true;
                    s.rebuild();
                  })
              .selected(() -> s.state.aimHeatmap)
              .content()
        };
    c.row(modes);
    List<double[]> hp = new ArrayList<>(), op = new ArrayList<>();
    for (var h : a.hitSamples)
      if (h.rayContact())
        hp.add(
            new double[] {
              h.aimHorizontal(),
              h.aimVertical(),
              h.measurement() == null || h.measurement().aimQuality() == null
                  ? -1
                  : h.measurement().aimQuality().ordinal()
            });
    for (var m : a.missSamples)
      if (m.rayContact()) op.add(new double[] {m.aimHorizontal(), m.aimVertical()});
    c.block(
        c.compact ? 130 : 160,
        new Hitbox(hp.toArray(double[][]::new), op.toArray(double[][]::new), s.state.aimHeatmap));
    c.note(
        "Positions are recorded ray intersections, normalized to the target's box (0 to 1 across"
            + " the contacted face and from feet to head). Attacks without ray contact are not"
            + " plotted. Placement is descriptive: a center hit is not automatically better.");
  }

  private static String pct(int v, int total) {
    return total == 0 ? "no contacts" : percent(100.0 * v / total);
  }

  private static String title(String grade) {
    return grade.charAt(0) + grade.substring(1).toLowerCase(Locale.ROOT);
  }

  private void timing(Content c, Aggregate a, String coverage) {
    c.heading("Weapon timing", coverage + " / attempted melee attacks");
    var samples = a.timingSamples;
    var grades = dev.kwamster.combat.quality.AttackTiming.Grade.values();
    int[] counts = new int[grades.length];
    for (var t : samples)
      if (t.measurement().timing() != null) counts[t.measurement().timing().grade().ordinal()]++;
    c.block(
        c.chartHeight(),
        new Bars(
            new String[] {"Opener", "Perfect", "Fast", "Slow", "N/A"},
            counts,
            "Attempts",
            Theme.SERIES_1,
            null,
            null,
            0,
            null,
            "Historical recordings have no weapon interval data"));
    List<String[]> rows = new ArrayList<>();
    for (int i = samples.size() - 1; i >= 0 && rows.size() < 50; i--) {
      var m = samples.get(i).measurement();
      var t = m.timing();
      if (t == null) continue;
      rows.add(
          new String[] {
            m.weapon().split(";")[0],
            number(m.attackSpeed(), 2),
            m.cooldownTicks() > 0 ? number(m.cooldownTicks(), 2) : NONE,
            t.grade() == dev.kwamster.combat.quality.AttackTiming.Grade.OPENER
                ? NONE
                : number(t.intervalTicks(), 1),
            t.grade().name()
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Weapon", "Speed", "Cooldown ticks", "Interval", "Timing"},
            new float[] {2, 1, 1.5f, 1, 2},
            new boolean[] {false, true, true, true, false},
            rows));
    c.note(
        "Intervals use attempted melee attacks, including empty swings during combat. The previous"
            + " weapon's effective cooldown defines the next interval. One cooldown is perfect"
            + " within the recorded tick tolerance; early is too fast, late up to two cooldowns is"
            + " too slow. A weapon switch or gap beyond two cooldowns resets the sequence. Openers"
            + " are ungraded. Server cooldown changes are unavailable unless observable in client"
            + " attributes.");
    c.heading("Hit intervals", "consecutive source-correlated hits; separate from attempt timing");
    c.text(
        "Average "
            + seconds(a.averageInterval())
            + "; deviation "
            + seconds(a.intervalDeviation()));
  }

  private static double cooldownMean(Aggregate a) {
    return a.hitSamples.stream()
        .mapToDouble(FightAnalytics.Hit::cooldown)
        .average()
        .orElse(Double.NaN);
  }

  private void rate(Content c, Aggregate a, String coverage, boolean critical) {
    c.heading(critical ? "Critical hits" : "Sprint hits", coverage);
    int count = critical ? a.criticalConditions : a.sprintHits;
    double rate = critical ? a.criticalRate() : a.sprintRate();
    c.stats(
        List.of(
            new Stat(
                critical ? "With crit conditions" : "Sprint hits",
                Integer.toString(count),
                "correlated hits",
                critical
                    ? "Observed: cooldown > 0.9, falling, airborne, not sprinting, climbing,"
                        + " swimming or riding."
                    : "Observed: sprint state at the attack."),
            stat(critical ? "criticalRate" : "sprintRate", percent(rate), "of " + a.hits + " hits"),
            stat("hits", Integer.toString(a.hits), "denominator")));
    c.heading("Rate per fight");
    double[] series = a.perFightHitRate(st -> critical ? st.criticalConditions : st.sprintHits);
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(
                new Series(
                    critical ? "Crit-condition rate" : "Sprint-hit rate",
                    null,
                    series,
                    Theme.SERIES_1,
                    Mark.LINE)),
            0,
            100,
            "%",
            "",
            fightLabels(a),
            grid(),
            points(),
            "No correlated hits"));
    c.note(
        critical
            ? "Critical conditions mirror vanilla 1.21.11 eligibility observed on the client. The"
                + " server's damage report does not include an amount, so the critical multiplier"
                + " is never confirmed."
            : "Sprint state is observed at the attack. Extra knockback is not measured.");
  }

  private void jumps(Content c, Aggregate a, String coverage) {
    c.heading("Jump resets", coverage);
    c.stats(
        List.of(
            stat("jumpCandidates", Integer.toString(a.jumpCandidates), "inside window"),
            new Stat(
                "Incoming hits",
                Integer.toString(a.incoming),
                "opponent damage",
                "Confirmed: server damage events against you from the tracked opponent."),
            new Stat(
                "Reset success",
                "Unavailable",
                "not estimated",
                "No knockback baseline exists, so success is not estimated."),
            new Stat(
                "Motion samples",
                Integer.toString(a.motionSamples),
                "10 Hz in fights",
                "Velocity traces recorded during engagements for future controlled analysis.")));
    c.heading("Jump minus incoming damage", "signed client ticks; early is negative");
    List<String[]> rows = new ArrayList<>();
    for (var j : a.jumps)
      rows.add(
          new String[] {
            seconds(j.time()),
            j.offsetTicks() == null ? NONE : Long.toString(j.offsetTicks()),
            number(j.offsetMillis(), 1),
            j.classification()
          });
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Time", "Tick offset", "ms diagnostic", "Candidate"},
            new float[] {1, 1, 1.5f, 3},
            new boolean[] {true, true, true, false},
            rows));
    c.note(
        "Same observed tick is an ideal-timing candidate; within the recorded window (default ±4"
            + " ticks) is an attempt candidate. Only actual jump actions count, once per key press."
            + " Prevented inputs are separate events. Older recordings without client ticks are"
            + " unavailable. Knockback cancellation remains unverified.",
        Theme.WARN);
  }

  private void combos(Content c, Aggregate a, String coverage) {
    c.heading("Combos", coverage);
    c.stats(
        List.of(
            stat("longestCombo", Integer.toString(a.longestCombo), "all fights in scope"),
            stat("averageCombo", number(a.averageCombo(), 2), a.comboLengths.size() + " chains"),
            new Stat(
                "Incoming hits",
                Integer.toString(a.incoming),
                "break combos",
                "Retaliation by the tracked opponent resets the combo.")));
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
        new Chart(
            List.of(new Series("Combo", null, combo, Theme.SERIES_1, Mark.LINE)),
            0,
            Double.NaN,
            "",
            "hit #",
            null,
            grid(),
            points(),
            "No correlated hits in this fight"));
    c.heading("Estimated momentum", "latest fight / seconds");
    double[] samples = latest.momentumSamples(160);
    double[] xs = new double[samples.length];
    for (int i = 0; i < xs.length; i++) xs[i] = latest.duration * i / (xs.length - 1);
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(new Series("Momentum (estimate)", xs, samples, Theme.SERIES_1, Mark.LINE)),
            -1,
            1,
            "",
            "s",
            null,
            grid(),
            false,
            "No exchanges"));
    c.note(
        MetricDefs.get("momentum").help() + " An illustrative exchange indicator only.",
        Theme.WARN);
  }

  static int gradeColor(Grade g) {
    return 0xFF000000 | KwamsterClient.config.feedback[g.ordinal()].color;
  }
}
