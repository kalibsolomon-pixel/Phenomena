package dev.phenomena.gui.screens;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.analytics.*;
import dev.phenomena.gui.DataCache;
import dev.phenomena.gui.components.KButton;
import dev.phenomena.gui.ui.UiState.Scope;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Shared pieces of the Offense, Defense and Fight Flow tabs. */
final class AnalysisSupport {
  private AnalysisSupport() {}

  static List<AbstractWidget> scopeToolbar(PhenomenaScreen screen) {
    var scopes = Scope.values();
    return List.of(
        screen.dropdown(
            "scope",
            () -> screen.state.scope.label,
            Arrays.stream(scopes).map(v -> v.label).toList(),
            () -> screen.state.scope.ordinal(),
            i -> screen.state.scope = scopes[i]));
  }

  static Aggregate aggregate(PhenomenaScreen screen) {
    return DataCache.aggregate(screen.state.scope.limit);
  }

  /** Writes the empty state and returns true when no fight is in scope. */
  static boolean empty(Content c, Aggregate a) {
    if (a.fights > 0) return false;
    c.heading("No data in scope");
    c.text("Analytics appear after the first recorded fight.");
    return true;
  }

  static String coverage(Aggregate a) {
    return a.timelineFights == a.fights
        ? a.fights + " fights"
        : a.fights + " fights; per-hit detail from " + a.timelineFights + " loaded timelines";
  }

  /** Weapon filter chips; "All weapons" plus each weapon seen in the samples. */
  static void weaponFilter(Content c, PhenomenaScreen s, Collection<String> weapons) {
    var list = new ArrayList<>(new TreeSet<>(weapons));
    if (list.size() < 2) {
      s.state.weapon = null;
      return;
    }
    if (s.state.weapon != null && !list.contains(s.state.weapon)) s.state.weapon = null;
    var chips = new ArrayList<AbstractWidget>();
    chips.add(
        new KButton(
                "weapon:all",
                KButton.Style.CHIP,
                () -> "All weapons",
                () -> {
                  s.state.weapon = null;
                  s.rebuild();
                })
            .selected(() -> s.state.weapon == null)
            .content());
    for (var w : list.subList(0, Math.min(5, list.size())))
      chips.add(
          new KButton(
                  "weapon:" + w,
                  KButton.Style.CHIP,
                  () -> Interpretation.shortItem(w),
                  () -> {
                    s.state.weapon = w;
                    s.rebuild();
                  })
              .selected(() -> w.equals(s.state.weapon))
              .tooltip(w)
              .content());
    c.row(chips.toArray(AbstractWidget[]::new));
  }

  static boolean weaponMatches(PhenomenaScreen s, String weapon) {
    return s.state.weapon == null || s.state.weapon.equals(weapon);
  }

  static boolean grid() {
    return PhenomenaClient.config.chartGrid;
  }

  static boolean points() {
    return PhenomenaClient.config.chartPoints;
  }

  static String[] fightLabels(Aggregate a) {
    String[] labels = new String[a.chronological.size()];
    for (int i = 0; i < labels.length; i++)
      labels[i] = Format.shortDate(a.chronological.get(i).startEpochMillis());
    return labels;
  }

  static String pct(int v, int total) {
    return total == 0 ? "no samples" : Format.percent(100.0 * v / total);
  }

  /** Context-dependent views note that recordings before 0.1.3 lack these observations. */
  static void contextNote(Content c, Aggregate a, String what) {
    if (a.contextFights < a.timelineFights)
      c.note(
          (a.timelineFights - a.contextFights)
              + " of "
              + a.timelineFights
              + " loaded fights were recorded before 0.1.3 and have no "
              + what
              + ". They are shown as unavailable, not as zero.");
  }
}
