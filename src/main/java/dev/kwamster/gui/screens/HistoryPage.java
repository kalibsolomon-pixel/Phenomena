package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;
import static dev.kwamster.gui.screens.OverviewPage.stat;

import dev.kwamster.KwamsterClient;
import dev.kwamster.analytics.*;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.gui.DataCache;
import dev.kwamster.gui.FightActions;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.platform.SafePaths;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Local recording management. The list works on in-memory summaries; a fight's full timeline is
 * loaded from disk only when its details are opened.
 */
final class HistoryPage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  HistoryPage(KwamsterScreen s) {
    this.s = s;
  }

  private FightRecord selected() {
    return DataCache.summary(s.state.selectedFight);
  }

  @Override
  public String title() {
    var f = selected();
    return s.state.detail && f != null
        ? "Fight / " + dateTime(f.startEpochMillis())
        : "Fight History";
  }

  @Override
  public String context() {
    if (s.state.detail) return null;
    int n = KwamsterClient.history.size();
    return n + (n == 1 ? " recording" : " recordings");
  }

  @Override
  public List<AbstractWidget> toolbar(KwamsterScreen screen) {
    if (s.state.detail && selected() != null)
      return List.of(
          new KButton(
                  "Back to list",
                  KButton.Style.NORMAL,
                  () -> {
                    s.state.closeDetail();
                    s.rebuild();
                  })
              .size(screen.getFont().width("Back to list") + 16, 14));
    var filters = HistoryQuery.OutcomeFilter.values();
    var sorts = HistoryQuery.Sort.values();
    return List.of(
        screen.dropdown(
            "filter",
            () -> s.state.outcome.label,
            Arrays.stream(filters).map(f -> f.label).toList(),
            () -> s.state.outcome.ordinal(),
            i -> s.state.outcome = filters[i]),
        screen.dropdown(
            "sort",
            () -> "Sort: " + s.state.sort.label,
            Arrays.stream(sorts).map(v -> v.label).toList(),
            () -> s.state.sort.ordinal(),
            i -> s.state.sort = sorts[i]));
  }

  @Override
  public void build(Content c) {
    var f = selected();
    if (s.state.detail && f != null) detail(c, f);
    else {
      s.state.closeDetail();
      list(c);
    }
  }

  private void folders(Content c, KButton... extra) {
    var buttons = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
    buttons.add(
        new KButton(
                "Open Recordings Folder", () -> FightActions.openFolder(SafePaths.Managed.FIGHTS))
            .tooltip("kwamster/fights in the Minecraft game directory")
            .content());
    buttons.add(
        new KButton("Open Exports Folder", () -> FightActions.openFolder(SafePaths.Managed.EXPORTS))
            .tooltip("kwamster/exports in the Minecraft game directory")
            .content());
    buttons.addAll(Arrays.asList(extra));
    c.row(buttons.toArray(net.minecraft.client.gui.components.AbstractWidget[]::new));
  }

  private void list(Content c) {
    var fights =
        HistoryQuery.apply(
            KwamsterClient.history, s.state.outcome, s.state.sort, s.state.withHitsOnly);
    if (s.state.selectedFight != null
        && fights.stream().noneMatch(x -> x.id().equals(s.state.selectedFight)))
      s.state.selectedFight = null;
    var sel = selected();
    boolean has = sel != null;
    c.row(
        new KButton("Details", () -> open(s.state.selectedFight)).enabled(has).content(),
        new KButton(
                "View in Browser",
                KButton.Style.PRIMARY,
                () -> FightActions.viewInBrowser(selected()))
            .enabled(has)
            .content(),
        new KButton("Export", () -> FightActions.export(selected()))
            .enabled(has)
            .tooltip(
                KwamsterClient.config.anonymizeExports
                    ? "Anonymized copy in kwamster/exports"
                    : "Copy in kwamster/exports (not anonymized)")
            .content(),
        new KButton("Delete", KButton.Style.DANGER, () -> confirmDelete(selected()))
            .enabled(has)
            .content());
    folders(
        c,
        new KButton(
                "hits-only",
                KButton.Style.CHIP,
                () -> s.state.withHitsOnly ? "With hits only" : "All fights",
                () -> {
                  s.state.withHitsOnly = !s.state.withHitsOnly;
                  s.rebuild();
                })
            .selected(() -> s.state.withHitsOnly)
            .tooltip("Toggle: hide fights without correlated hits")
            .content());
    if (KwamsterClient.history.isEmpty()) {
      c.note("No local recordings yet. Finished fights are saved to kwamster/fights.");
      return;
    }
    int rowH = c.compact ? 12 : 14;
    c.widget(
        new FightList(
            fights,
            rowH,
            () -> s.state.selectedFight,
            id -> {
              boolean changed = !id.equals(s.state.selectedFight);
              s.state.selectedFight = id;
              if (changed) s.rebuild(); // enables the selection toolbar
            },
            this::open,
            id -> FightActions.viewInBrowser(DataCache.summary(id)),
            s::reveal),
        FightList.heightFor(fights.size(), rowH));
    c.text(
        fights.size()
            + " shown. Click to select, click again or press Enter for details. Up/Down"
            + " moves the selection when the list is focused.");
  }

  private void open(String id) {
    if (id == null) return;
    s.state.openFight(id);
    s.rebuild();
  }

  private void confirmDelete(FightRecord f) {
    if (f == null) return;
    s.confirm(
        "Delete recording?",
        "Delete the fight from "
            + dateTime(f.startEpochMillis())
            + " ("
            + f.statistics().hits
            + (f.statistics().hits == 1 ? " hit" : " hits")
            + ")? This removes kwamster/fights/"
            + f.id().substring(0, 8)
            + "... from disk. Exported reports are not affected.",
        "Delete",
        () ->
            FightActions.delete(
                f.id(),
                () -> {
                  s.state.forget(f.id());
                  s.rebuild();
                }));
  }

  private void detail(Content c, FightRecord f) {
    var st = f.statistics();
    c.row(
        new KButton("View in Browser", KButton.Style.PRIMARY, () -> FightActions.viewInBrowser(f))
            .content(),
        new KButton("Export", () -> FightActions.export(f)).content(),
        new KButton("Delete", KButton.Style.DANGER, () -> confirmDelete(f)).content());
    var r = f.resolution();
    c.heading(
        r.classification().label + " / " + duration(f.durationNanos()),
        f.manual() ? "Manual session" : opponent(f.opponentLabel(), f.opponent()));
    c.text(
        "Established by: "
            + r.source().label
            + "; confidence "
            + r.confidence().name().toLowerCase(Locale.ROOT)
            + "; scope "
            + r.scope().name().toLowerCase(Locale.ROOT)
            + (r.derived() ? "; derived from a recording made before 0.1.3" : "")
            + ".");
    if (r.evidenceIndex() != null && r.evidenceIndex() < f.matchEvidence().entries().size()) {
      var e = f.matchEvidence().entries().get(r.evidenceIndex());
      c.text("Establishing announcement: " + e.rule() + ": " + e.text());
    }
    for (var n : r.notes()) c.text("Note: " + n);
    if (KwamsterClient.autoReports != null) c.text(KwamsterClient.autoReports.availability(f.id()));
    for (var evidence : f.matchEvidence().entries())
      c.text(
          evidence.kind()
              + " / "
              + evidence.origin()
              + " / "
              + (evidence.accepted() ? "accepted" : "not authoritative")
              + ": "
              + evidence.text());
    c.text(f.reason() == null ? "" : "Ended: " + f.reason() + ".");
    c.stats(
        List.of(
            stat(
                "accuracy",
                percent(Aggregate.trendValue(f, TrendMetric.ACCURACY)),
                st.hits + " / " + st.attempts),
            stat("emptyMisses", Integer.toString(st.emptyMisses), st.uncertain + " uncertain"),
            stat(
                "averageReach",
                meters(Aggregate.trendValue(f, TrendMetric.REACH)),
                "max " + meters(st.reachCount == 0 ? Double.NaN : st.maxReach)),
            stat(
                "criticalRate",
                percent(Aggregate.trendValue(f, TrendMetric.CRITICAL)),
                st.criticalConditions + " hits"),
            stat(
                "sprintRate",
                percent(100 * Aggregate.ratio(st.sprintHits, st.hits)),
                st.sprintHits + " hits"),
            stat(
                "averageInterval",
                seconds(Aggregate.trendValue(f, TrendMetric.TIMING)),
                "SD " + seconds(st.intervalCount == 0 ? Double.NaN : st.intervalDeviation())),
            stat("longestCombo", Integer.toString(st.longestCombo), st.comboCount + " combos"),
            stat(
                "incoming",
                Integer.toString(st.incoming),
                st.unattributedIncoming + " unattributed"),
            stat(
                "shieldBlocks",
                Integer.toString(st.shieldBlocks),
                st.attacksIntoShield + " into shields"),
            stat("jumpCandidates", Integer.toString(st.jumpCandidates), "success not measured")));

    var full = DataCache.full(f.id());
    if (full == null) {
      if (DataCache.loading(f.id())) c.note("Loading timeline from disk...");
      else {
        DataCache.requestTimeline(
            f.id(),
            () -> {
              if (s.state.detail && f.id().equals(s.state.selectedFight)) s.rebuild();
            });
        c.note(
            DataCache.loading(f.id())
                ? "Loading timeline from disk..."
                : "Timeline unavailable for this fight.");
      }
      return;
    }
    var a = DataCache.analytics(full);
    if (full.manual()) {
      c.heading("Manual session");
      c.text(
          full.session().participants().size()
              + " participants / "
              + full.session().exchanges().size()
              + " exchanges / "
              + full.session().termination());
      c.note(
          "Open the browser report to filter by participant or exchange. Session-wide dominance is"
              + " unavailable.");
    }
    var cfg = KwamsterClient.config;
    c.heading("Reach per hit", "seconds into the fight");
    double[] hx = a.hits.stream().mapToDouble(FightAnalytics.Hit::time).toArray();
    double[] hy = a.hits.stream().mapToDouble(FightAnalytics.Hit::reach).toArray();
    var est = a.misses.stream().filter(m -> m.geometry() && m.estimatedReach() >= 0).toList();
    c.block(
        c.chartHeight(),
        new Chart(
            List.of(
                new Series("Hit distance", hx, hy, Theme.SERIES_1, Mark.POINTS),
                new Series(
                    "Estimated, no hit",
                    est.stream().mapToDouble(FightAnalytics.Miss::time).toArray(),
                    est.stream().mapToDouble(FightAnalytics.Miss::estimatedReach).toArray(),
                    Theme.SERIES_2,
                    Mark.HOLLOW)),
            0,
            Double.NaN,
            "m",
            "s",
            null,
            cfg.chartGrid,
            true,
            "No reach samples"));
    c.heading("Encounter dominance", a.dominance.availability.label);
    c.block(FlowCharts.DominanceChart.height(c.compact), FlowPage.chart(a));
    c.heading("Combo timeline", "x = combo length");
    c.block(FlowCharts.ComboTimeline.height(), FlowPage.timeline(a, -1));
    c.heading("Interpretation");
    for (var line : Interpretation.of(a)) c.text(line);

    c.heading("Event timeline", full.events().size() + " events / monotonic time");
    long base =
        full.matchEvidence().timelineStartNanos() != null
            ? full.matchEvidence().timelineStartNanos()
            : full.events().isEmpty() ? 0 : full.events().getFirst().nanoTime();
    Set<Long> hits = new HashSet<>();
    for (var h : a.hits) hits.add(h.attackSequence());
    List<String[]> rows = new ArrayList<>();
    for (var e : full.events()) {
      if (e.kind() == dev.kwamster.combat.events.CombatEvent.Kind.MOTION) continue;
      String what = e.kind().name();
      String detail;
      boolean empty = e.detail() != null && e.detail().startsWith("empty-swing");
      if (e.attack() != null)
        detail =
            (hits.contains(e.sequence()) ? "hit, " : empty ? "empty swing, " : "no report, ")
                + meters(e.attack().reach())
                + ", cooldown "
                + number(e.attack().cooldown(), 2)
                + (e.attack().sprinting() ? ", sprint" : "");
      else detail = e.detail() == null ? "" : e.detail();
      rows.add(
          new String[] {
            number((e.nanoTime() - base) / 1e9, 2) + " s",
            what,
            title(e.confidence().name()),
            detail
          });
    }
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Time", "Event", "Confidence", "Detail"},
            new float[] {1, 1.3f, 1.3f, 4},
            new boolean[] {true, false, false, false},
            rows));
    c.text(a.motionSamples + " motion samples are kept in the recording and the browser report.");
  }

  private static String title(String s) {
    return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
  }
}
