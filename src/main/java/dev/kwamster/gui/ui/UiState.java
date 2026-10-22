package dev.kwamster.gui.ui;

import dev.kwamster.analytics.HistoryQuery;
import dev.kwamster.analytics.TrendMetric;
import java.util.*;

/**
 * Navigation state that survives tab switches and closing/reopening the dashboard during a
 * session. Pure data, independent of Minecraft, so tab behaviour is unit tested.
 */
public final class UiState {
  public enum Tab {
    OVERVIEW("Overview"),
    ANALYTICS("Combat Analytics"),
    HISTORY("Fight History"),
    SETTINGS("Settings");

    public final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  public enum AnalyticsSection {
    ACCURACY("Accuracy"),
    REACH("Reach"),
    AIM("Aim"),
    TIMING("Timing"),
    CRITICAL("Critical hits"),
    SPRINT("Sprint hits"),
    JUMP("Jump resets"),
    COMBOS("Combos");

    public final String label;

    AnalyticsSection(String label) {
      this.label = label;
    }
  }

  public enum SettingsSection {
    GENERAL("General"),
    CONTROLS("Controls"),
    FEEDBACK("Combat feedback"),
    STORAGE("Recording & storage"),
    INTERFACE("Interface");

    public final String label;

    SettingsSection(String label) {
      this.label = label;
    }
  }

  /** Fights included in Combat Analytics. */
  public enum Scope {
    LAST_10("Last 10 fights", 10),
    LAST_25("Last 25 fights", 25),
    ALL("All fights", Integer.MAX_VALUE);

    public final String label;
    public final int limit;

    Scope(String label, int limit) {
      this.label = label;
      this.limit = limit;
    }
  }

  public static final UiState SESSION = new UiState();

  public Tab tab = Tab.OVERVIEW;
  public AnalyticsSection analytics = AnalyticsSection.ACCURACY;
  public SettingsSection settings = SettingsSection.GENERAL;
  public Scope scope = Scope.LAST_25;
  public TrendMetric trend = TrendMetric.ACCURACY;
  public HistoryQuery.OutcomeFilter outcome = HistoryQuery.OutcomeFilter.ALL;
  public HistoryQuery.Sort sort = HistoryQuery.Sort.NEWEST;
  public boolean withHitsOnly;
  public boolean aimHeatmap;
  public int feedbackGrade;

  /** Selected fight in Fight History; detail view when {@link #detail} is true. */
  public String selectedFight;

  public boolean detail;

  private final Map<String, Integer> scroll = new HashMap<>();

  /** Scroll key for the visible page: tab plus section or detail view. */
  public String pageKey() {
    return switch (tab) {
      case OVERVIEW -> "overview";
      case ANALYTICS -> "analytics/" + analytics.name();
      case HISTORY -> detail ? "history/detail/" + selectedFight : "history/list";
      case SETTINGS -> "settings/" + settings.name();
    };
  }

  public int scroll() {
    return scroll.getOrDefault(pageKey(), 0);
  }

  public void setScroll(int value) {
    scroll.put(pageKey(), Math.max(0, value));
  }

  /** Clamp after content height is known; keeps the stored value valid after resizes. */
  public int clampScroll(int contentHeight, int viewportHeight) {
    int max = Math.max(0, contentHeight - viewportHeight);
    int v = Math.max(0, Math.min(max, scroll()));
    setScroll(v);
    return v;
  }

  public void select(Tab t) {
    tab = t;
  }

  public void openFight(String id) {
    tab = Tab.HISTORY;
    selectedFight = id;
    detail = true;
    scroll.remove("history/detail/" + id);
  }

  public void closeDetail() {
    detail = false;
  }

  /** Forget a deleted fight everywhere it is referenced. */
  public void forget(String id) {
    if (Objects.equals(selectedFight, id)) {
      selectedFight = null;
      detail = false;
    }
    scroll.remove("history/detail/" + id);
  }
}
