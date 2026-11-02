package dev.phenomena.gui.ui;

import dev.phenomena.analytics.HistoryQuery;
import dev.phenomena.analytics.TrendMetric;
import java.util.*;

/**
 * Navigation state that survives tab switches and closing/reopening the dashboard during a
 * session. Pure data, independent of Minecraft, so tab behaviour is unit tested.
 *
 * <p>Information architecture (0.1.3): Overview, Offense, Defense, Fight Flow, History, Settings.
 * Each analysis tab has one level of contextual sections; there is no deeper navigation.
 */
public final class UiState {
  public enum Tab {
    OVERVIEW("Overview"),
    OFFENSE("Offense"),
    DEFENSE("Defense"),
    FLOW("Fight Flow"),
    HISTORY("History"),
    SETTINGS("Settings");

    public final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  public enum OffenseSection {
    ACCURACY("Accuracy"),
    REACH("Reach & spacing"),
    AIM("Aim"),
    TIMING("Weapon timing"),
    CRITICAL("Critical hits"),
    SPRINT("Sprint hits");

    public final String label;

    OffenseSection(String label) {
      this.label = label;
    }
  }

  public enum DefenseSection {
    INCOMING("Incoming hits"),
    SHIELDS("Shields"),
    JUMP("Jump resets"),
    DAMAGE("Damage & knockback");

    public final String label;

    DefenseSection(String label) {
      this.label = label;
    }
  }

  public enum FlowSection {
    COMBOS("Combos"),
    DOMINANCE("Dominance"),
    EXCHANGES("Exchanges");

    public final String label;

    FlowSection(String label) {
      this.label = label;
    }
  }

  public enum SettingsSection {
    GENERAL("General"),
    CONTROLS("Controls"),
    FEEDBACK("Sounds"),
    MEASUREMENT("Measurement"),
    STORAGE("Recording & storage"),
    INTERFACE("Interface");

    public final String label;

    SettingsSection(String label) {
      this.label = label;
    }
  }

  /** Fights included in the analysis tabs. */
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

  /** Attack outcome filter for the weapon timing chart. */
  public enum AttemptFilter {
    ALL("All attempts"),
    HITS("Hits"),
    NOT_HIT("No hit");

    public final String label;

    AttemptFilter(String label) {
      this.label = label;
    }
  }

  public static final UiState SESSION = new UiState();

  public Tab tab = Tab.OVERVIEW;
  public OffenseSection offense = OffenseSection.ACCURACY;
  public DefenseSection defense = DefenseSection.INCOMING;
  public FlowSection flow = FlowSection.COMBOS;
  public SettingsSection settings = SettingsSection.GENERAL;
  public Scope scope = Scope.LAST_25;
  public TrendMetric trend = TrendMetric.ACCURACY;
  public HistoryQuery.OutcomeFilter outcome = HistoryQuery.OutcomeFilter.ALL;
  public HistoryQuery.Sort sort = HistoryQuery.Sort.NEWEST;
  public boolean withHitsOnly;
  public boolean aimHeatmap;
  public int feedbackGrade;

  /** Weapon filter (registry id) for reach, timing and aim views; null shows every weapon. */
  public String weapon;

  public AttemptFilter attempts = AttemptFilter.ALL;

  /** Fight Flow focus: 0 is the newest fight with a loaded timeline in scope. */
  public int focus;

  /** Selected combo index in the focused fight, or -1. */
  public int combo = -1;

  /** Selected fight in History; detail view when {@link #detail} is true. */
  public String selectedFight;

  public boolean detail;

  private final Map<String, Integer> scroll = new HashMap<>();

  /** Scroll key for the visible page: tab plus section or detail view. */
  public String pageKey() {
    return switch (tab) {
      case OVERVIEW -> "overview";
      case OFFENSE -> "offense/" + offense.name();
      case DEFENSE -> "defense/" + defense.name();
      case FLOW -> "flow/" + flow.name();
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

  /** Moves the Fight Flow focus; clamps to the available timelines and clears the combo. */
  public void focus(int index, int available) {
    focus = Math.max(0, Math.min(Math.max(0, available - 1), index));
    combo = -1;
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
