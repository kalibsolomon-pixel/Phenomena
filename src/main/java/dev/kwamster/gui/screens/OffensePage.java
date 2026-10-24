package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;
import static dev.kwamster.gui.screens.AnalysisSupport.*;
import static dev.kwamster.gui.screens.OverviewPage.stat;

import dev.kwamster.analytics.*;
import dev.kwamster.combat.quality.AttackTiming;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState.AttemptFilter;
import dev.kwamster.gui.ui.UiState.OffenseSection;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Offense: accuracy, reach and spacing, aim, weapon timing, critical and sprint hits. */
final class OffensePage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  OffensePage(KwamsterScreen s) {
    this.s = s;
  }

  @Override
  public String title() {
    return "Offense";
  }

  @Override
  public List<String> sections() {
    return Arrays.stream(OffenseSection.values()).map(v -> v.label).toList();
  }

  @Override
  public int section() {
    return s.state.offense.ordinal();
  }

  @Override
  public void section(int index) {
    s.state.offense = OffenseSection.values()[index];
  }

  @Override
  public List<AbstractWidget> toolbar(KwamsterScreen screen) {
    return scopeToolbar(screen);
  }

  @Override
  public void build(Content c) {
    var a = aggregate(s);
    if (empty(c, a)) return;
    String coverage = coverage(a);
    switch (s.state.offense) {
      case ACCURACY -> accuracy(c, a, coverage);
      case REACH -> reach(c, a, coverage);
      case AIM -> aim(c, a, coverage);
      case TIMING -> timing(c, a, coverage);
      case CRITICAL -> rate(c, a, coverage, true);
      case SPRINT -> rate(c, a, coverage, false);
    }
  }

  private void accuracy(Content c, Aggregate a, String coverage) {
    c.heading("Attempts", coverage);
    if (a.measurementFights > 0) {
      c.text(
          "Input breakdown: "
              + a.measurementFights
              + " of "
              + a.fights
              + " fights have 0.1.6 measurements.");
      c.stats(
          List.of(
              new Stat(
                  "Total swings",
                  "" + a.totalInputs,
                  "raw inputs",
                  "All observed local outgoing attack inputs during recording."),
              new Stat(
                  "Unique attacks",
                  "" + a.uniqueAttacks,
                  a.deduplicatedAttacks + " duplicates",
                  "Inclusive two-client-tick window anchored to the first input."),
              new Stat(
                  "Excluded swings",
                  "" + a.excludedSwings,
                  "outside combat",
                  "No valid targeted opponent evidence; excluded from accuracy."),
              new Stat(
                  "Unresolved attacks",
                  "" + (a.uncertain - a.uncertainShield),
                  "included in denominator",
                  "Outcome evidence is insufficient. These attacks are never labelled as misses."),
              new Stat(
                  "Uncertain shields",
                  "" + a.uncertainShield,
                  "included in uncertainty",
                  "Blocking/facing state alone cannot prove a block; retained in accuracy"
                      + " denominator.")));
    }
    c.stats(
        List.of(
            stat("attempts", Integer.toString(a.attempts), "observed"),
            stat("hits", Integer.toString(a.hits), "confirmed source"),
            stat("emptyMisses", Integer.toString(a.emptyMisses), "eligible misses"),
            stat("uncertain", Integer.toString(a.uncertain), "not proven misses"),
            stat("shieldBlocks", Integer.toString(a.shieldBlocks), "not in denominator"),
            stat("accuracy", percent(a.accuracy()), a.hits + " / " + a.accuracyDenominator())));
    c.block(
        StackBar.height(),
        new StackBar(
            new String[] {"Hits", "Eligible misses", "Uncertain", "Shield blocks"},
            new int[] {a.hits, a.emptyMisses, a.uncertain, a.shieldBlocks},
            new int[] {Theme.SERIES_1, Theme.SERIES_2, Theme.DIM, Theme.SERIES_3}));
    c.note(
        "Accuracy = confirmed hits / (attempts minus confirmed shield blocks). A blocked attack met"
            + " the opponent's shield: it is neither a hit nor a miss. Estimated blocks and other"
            + " attacks without a server report stay uncertain and remain in the denominator. Empty"
            + " aimed misses need opponent geometry; unrelated swings are excluded in 0.1.6."
            + " Unresolved attempts remain in the denominator to avoid inflating accuracy."
            + " Historical fights keep their original measurement policy.");
    contextNote(c, a, "shield observations (their blocked attacks stay uncertain)");
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
  }

  private void reach(Content c, Aggregate a, String coverage) {
    c.heading("Reach and spacing", coverage);
    Set<String> weapons = new HashSet<>(Arrays.asList(a.reachWeapons));
    weaponFilter(c, s, weapons);
    List<Double> values = new ArrayList<>();
    for (int i = 0; i < a.reachValues.length; i++)
      if (weaponMatches(s, a.reachWeapons[i])) values.add(a.reachValues[i]);
    double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
    double target = Double.isNaN(a.reachTarget) ? 3 : a.reachTarget;
    double tolerance = Double.isNaN(a.reachTolerance) ? .06 : a.reachTolerance;
    int within = 0;
    double sum = 0, sq = 0, err = 0, max = Double.NaN;
    for (double x : v) {
      if (Math.abs(x - target) <= tolerance + 1e-9) within++;
      sum += x;
      sq += x * x;
      err += Math.abs(x - target);
      max = Double.isNaN(max) ? x : Math.max(max, x);
    }
    int n = v.length;
    double mean = n == 0 ? Double.NaN : sum / n;
    double sd = n == 0 ? Double.NaN : Math.sqrt(Math.max(0, sq / n - mean * mean));
    c.stats(
        List.of(
            stat(
                "reachWithin",
                n == 0 ? NONE : within + " / " + n,
                percent(n == 0 ? Double.NaN : 100.0 * within / n)),
            stat("averageReach", meters(mean), n + " eligible hits"),
            stat("reachDeviation", meters(sd), "standard deviation"),
            stat(
                "reachError",
                meters(n == 0 ? Double.NaN : err / n),
                "from " + number(target, 2) + " m"),
            stat("maxReach", meters(max), "eligible hits"),
            new Stat(
                "Target window",
                number(target, 2) + " ± " + number(tolerance, 2),
                a.mixedReachPolicy ? "differs between fights" : "configured",
                "Inclusive window frozen into each fight. Change it under Settings >"
                    + " Measurement.")));
    c.heading("Reach distribution", "eligible confirmed hits, 0.05-block bins");
    c.block(c.chartHeight() + 26, new FlowCharts.ReachHistogram(v, target, tolerance));
    c.note(
        "Eligible hits are server-confirmed melee hits whose attack ray touched the target's client"
            + " hitbox. Reach is the client distance from your eye to that contact point. It is not"
            + " a server reach verdict, and a value above the target is not evidence of a rule"
            + " violation: interpolation, latency and server rules differ. Weapons with custom"
            + " attack range components are listed separately.");
    c.heading("By weapon");
    List<String[]> rows = new ArrayList<>();
    Map<String, List<Double>> by = new TreeMap<>();
    for (int i = 0; i < a.reachValues.length; i++)
      by.computeIfAbsent(a.reachWeapons[i], k -> new ArrayList<>()).add(a.reachValues[i]);
    for (var e : by.entrySet()) {
      double[] w = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
      int in = 0;
      double ws = 0, wq = 0, wm = 0;
      for (double x : w) {
        if (Math.abs(x - target) <= tolerance + 1e-9) in++;
        ws += x;
        wq += x * x;
        wm = Math.max(wm, x);
      }
      double m = ws / w.length;
      rows.add(
          new String[] {
            Interpretation.shortItem(e.getKey()),
            Integer.toString(w.length),
            in + " (" + percent(100.0 * in / w.length) + ")",
            meters(m),
            meters(Math.sqrt(Math.max(0, wq / w.length - m * m))),
            meters(wm)
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Weapon", "Hits", "In window", "Mean", "SD", "Max"},
            new float[] {2, .8f, 1.6f, 1, 1, 1},
            new boolean[] {false, true, true, true, true, true},
            rows));
    long estimated =
        a.missSamples.stream().filter(m -> m.geometry() && m.estimatedReach() >= 0).count();
    c.text(
        estimated
            + " attempts without a confirmed hit have an estimated distance; they are excluded from"
            + " every reach statistic above.");
  }

  private void aim(Content c, Aggregate a, String coverage) {
    c.heading("Melee aim", coverage);
    Set<String> weapons = new HashSet<>();
    for (var sm : a.aimSamples) if (sm.hit()) weapons.add(sm.weapon());
    weaponFilter(c, s, weapons);
    int[] grades = new int[4];
    List<Double> distances = new ArrayList<>();
    for (var sm : a.aimSamples) {
      if (!sm.hit() || !weaponMatches(s, sm.weapon())) continue;
      int g =
          switch (sm.grade()) {
            case "IDEAL" -> 0;
            case "GOOD" -> 1;
            case "PERIPHERAL" -> 2;
            case "POOR" -> 3;
            default -> -1;
          };
      if (g < 0) continue;
      grades[g]++;
      if (Double.isFinite(sm.distance())) distances.add(sm.distance());
    }
    int graded = grades[0] + grades[1] + grades[2] + grades[3];
    double[] d = distances.stream().mapToDouble(Double::doubleValue).toArray();
    double mean = Arrays.stream(d).average().orElse(Double.NaN);
    double sd =
        d.length == 0
            ? Double.NaN
            : Math.sqrt(Arrays.stream(d).map(x -> (x - mean) * (x - mean)).sum() / d.length);
    c.stats(
        List.of(
            stat(
                "aimCentral",
                pct(grades[0] + grades[1], graded),
                (grades[0] + grades[1]) + " of " + graded),
            new Stat(
                "Peripheral or poor",
                Integer.toString(grades[2] + grades[3]),
                pct(grades[2] + grades[3], graded),
                "Hits graded peripheral or poor by the client hitbox heuristic."),
            stat("aimDistance", meters(mean), "SD " + meters(sd))));
    c.block(
        c.compact ? 80 : 96,
        new Bars(
            new String[] {"Ideal", "Good", "Peripheral", "Poor"},
            grades,
            "Confirmed hits",
            Theme.SERIES_1,
            null,
            null,
            0,
            new int[] {0xFF56B4E9, 0xFF009E73, 0xFFE69F00, 0xFFCC79A7},
            "Aim quality unavailable for historical recordings"));
    c.heading("By reach range", "ideal / good / peripheral / poor");
    List<String[]> rows = new ArrayList<>();
    Map<String, int[]> byReach = new TreeMap<>();
    for (var sm : a.aimSamples) {
      if (!sm.hit() || !weaponMatches(s, sm.weapon())) continue;
      int g = List.of("IDEAL", "GOOD", "PERIPHERAL", "POOR").indexOf(sm.grade());
      if (g >= 0) byReach.computeIfAbsent(sm.reachBand(), k -> new int[4])[g]++;
    }
    for (var e : byReach.entrySet()) {
      var v = e.getValue();
      int t = v[0] + v[1] + v[2] + v[3];
      rows.add(
          new String[] {
            e.getKey(),
            Integer.toString(t),
            v[0] + " / " + v[1] + " / " + v[2] + " / " + v[3],
            pct(v[0] + v[1], t)
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Reach", "Hits", "I / G / P / Poor", "Central"},
            new float[] {1.4f, .8f, 2, 1},
            new boolean[] {false, true, true, true},
            rows));
    c.heading("Hit placement", "measured coordinates; color = derived grade");
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
    for (var sm : a.aimSamples) {
      if (!weaponMatches(s, sm.weapon())) continue;
      int g = List.of("IDEAL", "GOOD", "PERIPHERAL", "POOR").indexOf(sm.grade());
      if (sm.hit()) hp.add(new double[] {sm.horizontal(), sm.vertical(), g});
      else op.add(new double[] {sm.horizontal(), sm.vertical()});
    }
    c.block(
        c.compact ? 130 : 160,
        new Hitbox(hp.toArray(double[][]::new), op.toArray(double[][]::new), s.state.aimHeatmap));
    c.note(
        "Positions are measured ray intersections normalized to the contacted face (0-1 across,"
            + " feet to head). Grades come from a separate heuristic: the ideal point follows the"
            + " nearest reachable surface with an edge inset, so it moves with elevation and pose;"
            + " opponent eye level is not a universal target. Blue/green/orange/purple mark ideal,"
            + " good, peripheral and poor. Attempts without ray contact are not plotted and no"
            + " swing path is reconstructed. Browser reports show the contacted face and region for"
            + " each hit.");
  }

  private void timing(Content c, Aggregate a, String coverage) {
    c.heading("Weapon timing", coverage + " / attempted melee attacks");
    Set<String> weapons = new HashSet<>();
    for (var t : a.timing) weapons.add(t.weapon());
    weaponFilter(c, s, weapons);
    var filters = AttemptFilter.values();
    var chips = new AbstractWidget[filters.length];
    for (int i = 0; i < filters.length; i++) {
      var f = filters[i];
      chips[i] =
          new KButton(
                  "attempts:" + f,
                  KButton.Style.CHIP,
                  () -> f.label,
                  () -> {
                    s.state.attempts = f;
                    s.rebuild();
                  })
              .selected(() -> s.state.attempts == f)
              .content();
    }
    c.row(chips);
    List<FightAnalytics.TimingSample> samples = new ArrayList<>();
    for (var t : a.timing) {
      if (!weaponMatches(s, t.weapon())) continue;
      boolean hit = t.outcome().equals("HIT");
      if (s.state.attempts == AttemptFilter.HITS && !hit) continue;
      if (s.state.attempts == AttemptFilter.NOT_HIT && hit) continue;
      samples.add(t);
    }
    var grades = AttackTiming.Grade.values();
    int[] counts = new int[grades.length];
    List<Double> deltas = new ArrayList<>();
    for (var t : samples) {
      counts[t.grade().ordinal()]++;
      if (t.graded()) deltas.add(t.deltaTicks());
    }
    int graded =
        counts[AttackTiming.Grade.PERFECT.ordinal()]
            + counts[AttackTiming.Grade.TOO_FAST.ordinal()]
            + counts[AttackTiming.Grade.TOO_SLOW.ordinal()];
    double[] d = deltas.stream().mapToDouble(Double::doubleValue).toArray();
    double meanAbs = Arrays.stream(d).map(Math::abs).average().orElse(Double.NaN);
    double m = Arrays.stream(d).average().orElse(Double.NaN);
    double sd =
        d.length == 0
            ? Double.NaN
            : Math.sqrt(Arrays.stream(d).map(x -> (x - m) * (x - m)).sum() / d.length);
    boolean mixed = s.state.weapon == null && weapons.size() > 1;
    c.stats(
        List.of(
            stat(
                "timingPerfect",
                pct(counts[AttackTiming.Grade.PERFECT.ordinal()], graded),
                counts[AttackTiming.Grade.PERFECT.ordinal()] + " of " + graded + " graded"),
            new Stat(
                "Too fast / too slow",
                counts[AttackTiming.Grade.TOO_FAST.ordinal()]
                    + " / "
                    + counts[AttackTiming.Grade.TOO_SLOW.ordinal()],
                "graded intervals",
                "Earlier than one cooldown / later, up to two cooldowns."),
            new Stat(
                "Mean |difference|",
                number(meanAbs, 1) + " t",
                mixed ? "relative to each weapon" : "ticks",
                "Average absolute difference between the interval and that weapon's expected"
                    + " cooldown."),
            new Stat(
                "Consistency (SD)",
                number(sd, 1) + " t",
                "of interval - expected",
                "Standard deviation of the difference; lower is more regular."),
            new Stat(
                "Resets / openers",
                Integer.toString(counts[AttackTiming.Grade.OPENER.ordinal()]),
                "ungraded",
                "First attack after a weapon switch or a gap over two cooldowns.")));
    c.heading("Interval vs expected cooldown", "each attempt; zero = one cooldown later");
    List<FlowCharts.TimingPoint> pts = new ArrayList<>();
    for (var t : samples)
      pts.add(
          new FlowCharts.TimingPoint(
              t.deltaTicks(),
              t.grade().name(),
              t.outcome(),
              t.grade() == AttackTiming.Grade.OPENER,
              Interpretation.shortItem(t.weapon())
                  + ": "
                  + (t.grade() == AttackTiming.Grade.OPENER
                      ? "reset / opener"
                      : number(t.intervalTicks(), 1)
                          + " t interval, expected "
                          + number(t.expectedTicks(), 1)
                          + " t ("
                          + (t.deltaTicks() >= 0 ? "+" : "")
                          + number(t.deltaTicks(), 1)
                          + ")")
                  + ", "
                  + title(t.grade().name())
                  + ", "
                  + title(t.outcome())));
    double tolerance =
        a.timelines.isEmpty() ? 1 : a.timelines.getLast().fight.policy().timingToleranceTicks();
    c.block(c.chartHeight() + 28, new FlowCharts.TimingScatter(pts, tolerance));
    List<String[]> rows = new ArrayList<>();
    for (int i = samples.size() - 1; i >= 0 && rows.size() < 50; i--) {
      var t = samples.get(i);
      rows.add(
          new String[] {
            Interpretation.shortItem(t.weapon()),
            number(t.attackSpeed(), 2),
            number(t.expectedTicks(), 2),
            number(t.intervalTicks(), 1),
            Double.isFinite(t.deltaTicks())
                ? (t.deltaTicks() >= 0 ? "+" : "") + number(t.deltaTicks(), 1)
                : NONE,
            title(t.grade().name()),
            title(t.outcome())
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Weapon", "Speed", "Expected", "Interval", "Diff", "Timing", "Result"},
            new float[] {1.6f, .8f, 1, 1, .8f, 1.3f, 1.4f},
            new boolean[] {false, true, true, true, true, false, false},
            rows));
    c.note(
        "The previous attack's effective cooldown (20 / attack speed ticks, from client attributes)"
            + " defines the next interval. Within the tick tolerance is perfect; earlier is too"
            + " fast; later, up to two cooldowns, is too slow. A switch or a longer gap resets the"
            + " sequence. Weapons are compared to their own cooldowns, never pooled into one raw"
            + " average. Cooldown readiness is mechanical; it does not say whether an attack was"
            + " tactically ideal.");
  }

  private void rate(Content c, Aggregate a, String coverage, boolean critical) {
    c.heading(critical ? "Critical hits" : "Sprint hits", coverage);
    int count = critical ? a.criticalConditions : a.sprintHits;
    double rate = critical ? a.criticalRate() : a.sprintRate();
    var list = new ArrayList<Stat>();
    list.add(
        new Stat(
            critical ? "With crit conditions" : "Sprint hits",
            Integer.toString(count),
            "correlated hits",
            critical
                ? "Observed: cooldown > 0.9, falling, airborne, not sprinting, climbing, swimming"
                    + " or riding."
                : "Observed: sprint state at the attack."));
    list.add(
        stat(critical ? "criticalRate" : "sprintRate", percent(rate), "of " + a.hits + " hits"));
    if (critical)
      list.add(
          stat(
              "serverCrits",
              a.contextFights == 0 ? NONE : Integer.toString(a.serverCrits),
              a.contextFights == 0 ? "needs 0.1.3 recordings" : "server animation"));
    c.stats(list);
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
            ? "Critical conditions mirror vanilla 1.21.11 eligibility observed on the client. Since"
                + " 0.1.3 the server's critical-hit animation on the opponent within one tick of a"
                + " confirmed hit is also counted. Neither reports the damage multiplier."
            : "Sprint state is observed at the attack. Extra knockback is not measured.");
    if (critical) contextNote(c, a, "server critical-hit observations");
  }

  static String title(String s) {
    String t = s.replace('_', ' ');
    return t.charAt(0) + t.substring(1).toLowerCase(Locale.ROOT);
  }
}
