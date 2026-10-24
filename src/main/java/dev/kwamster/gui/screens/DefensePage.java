package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;
import static dev.kwamster.gui.screens.AnalysisSupport.*;
import static dev.kwamster.gui.screens.OverviewPage.stat;

import dev.kwamster.analytics.*;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState.DefenseSection;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Defense: incoming hits and their attribution, shields, jump resets, damage and knockback. */
final class DefensePage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  DefensePage(KwamsterScreen s) {
    this.s = s;
  }

  @Override
  public String title() {
    return "Defense";
  }

  @Override
  public List<String> sections() {
    return Arrays.stream(DefenseSection.values()).map(v -> v.label).toList();
  }

  @Override
  public int section() {
    return s.state.defense.ordinal();
  }

  @Override
  public void section(int index) {
    s.state.defense = DefenseSection.values()[index];
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
    switch (s.state.defense) {
      case INCOMING -> incoming(c, a, coverage);
      case SHIELDS -> shields(c, a, coverage);
      case JUMP -> jumps(c, a, coverage);
      case DAMAGE -> damage(c, a, coverage);
    }
  }

  private void incoming(Content c, Aggregate a, String coverage) {
    c.heading("Incoming damage", coverage);
    int total = a.incoming + a.unattributedIncoming + a.thirdPartyIncoming;
    c.stats(
        List.of(
            stat("incoming", Integer.toString(a.incoming), "from the tracked opponent"),
            stat(
                "unattributedIncoming",
                Integer.toString(a.unattributedIncoming),
                "no cause entity"),
            new Stat(
                "Third-party damage",
                Integer.toString(a.thirdPartyIncoming),
                "another entity",
                "Server reported a different cause entity. Kept, never counted as the opponent."),
            new Stat(
                "Opponent crits on you",
                a.contextFights == 0 ? NONE : Integer.toString(a.opponentCrits),
                "server animation",
                "Critical-hit animations the server showed on you. Damage is not reported.")));
    c.block(
        StackBar.height(),
        new StackBar(
            new String[] {"Attributed to opponent", "Unattributed", "Third party"},
            new int[] {a.incoming, a.unattributedIncoming, a.thirdPartyIncoming},
            new int[] {FlowCharts.OPPONENT, FlowCharts.INCOMPLETE, Theme.DIM}));
    c.note(
        "A client can see that it was damaged and which entity the server named as the cause. Only"
            + " damage whose cause is the tracked opponent counts as an opponent hit: it ends your"
            + " combos, moves dominance toward the opponent and pairs with jump resets. Fall, fire,"
            + " environmental and other unattributed damage is listed separately"
            + (total > 0 && a.unattributedIncoming > 0
                ? " (" + pct(a.unattributedIncoming, total) + " of incoming here)"
                : "")
            + ". Recordings before 0.1.3 counted it as opponent retaliation.");
    var latest = a.latestTimeline;
    if (latest == null) return;
    c.heading("Latest fight", dateTime(latest.fight.startEpochMillis()));
    List<String[]> rows = new ArrayList<>();
    for (var in : latest.incoming)
      rows.add(
          new String[] {
            seconds(in.time()),
            attribution(in.attribution()),
            in.damageType() == null ? NONE : Interpretation.shortItem(in.damageType()),
            in.damage() == null ? NONE : number(in.damage(), 1) + " HP",
            in.knockback() == null ? NONE : number(in.knockback(), 2),
            in.crit() ? "yes" : NONE
          });
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Time", "Attribution", "Type", "Health lost", "Knockback", "Crit"},
            new float[] {.8f, 1.8f, 1.4f, 1, 1, .6f},
            new boolean[] {true, false, false, true, true, false},
            rows));
  }

  static String attribution(String a) {
    return switch (a) {
      case "OPPONENT_DIRECT" -> "Opponent, melee";
      case "OPPONENT_INDIRECT" -> "Opponent, indirect";
      case "OPPONENT_LEGACY" -> "Opponent (pre-0.1.3)";
      case "OTHER_ENTITY" -> "Third party";
      default -> "Unattributed";
    };
  }

  private void shields(Content c, Aggregate a, String coverage) {
    c.heading("Your attacks against shields", coverage);
    c.stats(
        List.of(
            stat("attacksIntoShield", Integer.toString(a.attacksIntoShield), "raised shield"),
            stat("shieldBlocks", Integer.toString(a.shieldBlocks), "sound + no damage"),
            stat("estimatedShieldBlocks", Integer.toString(a.estimatedShieldBlocks), "no sound"),
            stat("shieldDisables", Integer.toString(a.shieldDisables), "disable sound"),
            stat("hitsAfterDisable", Integer.toString(a.hitsAfterDisable), "within 5 s"),
            new Stat(
                "Hits past a shield",
                Integer.toString(a.bypassedShields),
                "target blocking",
                "Confirmed hits while the target showed a raised shield (behind or after a"
                    + " disable).")));
    int otherUncertain = Math.max(0, a.uncertain - a.estimatedShieldBlocks);
    c.block(
        StackBar.height(),
        new StackBar(
            new String[] {"Hit", "Blocked", "Est. blocked", "Unknown", "Miss"},
            new int[] {
              a.hits, a.shieldBlocks, a.estimatedShieldBlocks, otherUncertain, a.emptyMisses
            },
            new int[] {Theme.SERIES_1, Theme.SERIES_3, 0xFF2F7F63, Theme.DIM, Theme.SERIES_2}));
    c.heading("Your defensive shield");
    c.stats(
        List.of(
            stat(
                "localShieldBlocks",
                Integer.toString(a.localShieldBlocks),
                "attacker not reported"),
            new Stat(
                "Your shield disabled",
                Integer.toString(a.localShieldDisabled),
                "disable sound at you",
                "Shield-disable sounds at your position.")));
    c.note(
        "Minecraft 1.21.11 sends no damage event for a blocked melee hit; it plays the shield-block"
            + " sound at the blocker. A confirmed block needs a target that was visibly blocking,"
            + " no damage report and that sound at the target. Blocking with no sound is only an"
            + " estimate and stays uncertain. Exact damage prevented is never inferred.");
    contextNote(c, a, "shield observations");
    var latest = a.latestTimeline;
    if (latest == null || latest.shields.events().isEmpty()) return;
    c.heading("Latest fight shield events", dateTime(latest.fight.startEpochMillis()));
    List<String[]> rows = new ArrayList<>();
    for (var e : latest.shields.events())
      rows.add(
          new String[] {
            seconds(e.time()),
            OffensePage.title(e.type()),
            e.subject().equals("local") ? "You" : "Opponent",
            e.weapon() == null ? NONE : Interpretation.shortItem(e.weapon())
          });
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Time", "Event", "Shield of", "Your weapon"},
            new float[] {1, 1.6f, 1.2f, 1.6f},
            new boolean[] {true, false, false, false},
            rows));
  }

  private void jumps(Content c, Aggregate a, String coverage) {
    c.heading("Jump resets", coverage);
    Map<String, Integer> classes = new LinkedHashMap<>();
    for (var k :
        List.of(
            "IDEAL_CANDIDATE",
            "ATTEMPT_CANDIDATE",
            "OUTSIDE_WINDOW",
            "NO_JUMP",
            "EXCLUDED",
            "UNAVAILABLE")) classes.put(k, 0);
    List<Long> offsets = new ArrayList<>();
    for (var in : a.incomingSamples) {
      if (!in.attributed()) continue;
      classes.merge(in.jumpClass(), 1, Integer::sum);
      if (in.jumpOffsetTicks() != null) offsets.add(in.jumpOffsetTicks());
    }
    int window = a.timelines.isEmpty() ? 4 : a.timelines.getLast().fight.policy().jumpWindowTicks();
    c.stats(
        List.of(
            new Stat(
                "Same tick",
                classes.get("IDEAL_CANDIDATE").toString(),
                "ideal-timing candidates",
                "Actual jump in the same client tick as the attributed incoming hit."),
            new Stat(
                "Within ±" + window,
                classes.get("ATTEMPT_CANDIDATE").toString(),
                "attempt candidates",
                "Actual jump within the window, excluding the same tick."),
            new Stat(
                "Outside window",
                classes.get("OUTSIDE_WINDOW").toString(),
                "not candidates",
                "Nearest jump further than the window but within 20 ticks."),
            new Stat(
                "No jump",
                classes.get("NO_JUMP").toString(),
                "within 20 ticks",
                "No actual jump near the hit."),
            new Stat(
                "Excluded",
                classes.get("EXCLUDED").toString(),
                "water, climbing, riding",
                "Movement state at the hit did not allow a normal jump."),
            new Stat(
                "Unavailable",
                classes.get("UNAVAILABLE").toString(),
                "no client ticks",
                "Older recordings without tick numbers.")));
    c.heading("Tick offsets", "jump minus attributed incoming hit");
    c.block(c.chartHeight() + 18, new FlowCharts.OffsetStrip(offsets, window));
    var e =
        a.timelines.stream()
            .map(t -> t.jumpEffect)
            .filter(Objects::nonNull)
            .reduce((x, y) -> y)
            .orElse(null);
    c.stats(
        List.of(
            new Stat(
                "Reset success",
                "Not measured",
                "no success rate",
                "Candidates describe timing only; no validated knockback baseline exists."),
            new Stat(
                "Speed after knockback (exp.)",
                e == null
                    ? "Unavailable"
                    : number(e.candidateSpeed(), 3) + " vs " + number(e.otherSpeed(), 3),
                e == null ? "needs 3+ samples per group" : "candidates vs no jump, latest",
                "Experimental and descriptive: mean horizontal speed (blocks/tick) 0.1-0.4 s after"
                    + " knockback. Confounded by attacker sprint, enchantments, blocks and"
                    + " input.")));
    var latest = a.latestTimeline;
    if (latest != null) {
      c.heading("Latest fight", dateTime(latest.fight.startEpochMillis()));
      List<String[]> rows = new ArrayList<>();
      for (var in : latest.incoming) {
        if (!in.attributed()) continue;
        rows.add(
            new String[] {
              seconds(in.time()),
              in.jumpOffsetTicks() == null
                  ? NONE
                  : (in.jumpOffsetTicks() > 0 ? "+" : "") + in.jumpOffsetTicks(),
              OffensePage.title(in.jumpClass()),
              in.ground() == null
                  ? NONE
                  : (in.ground() ? "ground" : "air")
                      + (Boolean.TRUE.equals(in.sprinting()) ? ", sprint" : ""),
              in.knockback() == null ? NONE : number(in.knockback(), 2),
              in.tick() == null ? "unavailable" : "observed ticks"
            });
      }
      c.block(
          Table.height(rows.size()),
          new Table(
              new String[] {"Hit", "Offset", "Category", "Movement", "Knockback", "Confidence"},
              new float[] {.8f, .8f, 1.7f, 1.2f, 1, 1.2f},
              new boolean[] {true, true, false, false, true, false},
              rows));
    }
    c.note(
        "Offsets compare client ticks of the actual jump action and the incoming-hit observation;"
            + " server timing may differ by network delay. Only attributed opponent hits are"
            + " considered. A candidate is not a successful reset: whether knockback was reduced is"
            + " not measured.",
        Theme.WARN);
  }

  private void damage(Content c, Aggregate a, String coverage) {
    c.heading("Damage and knockback", coverage);
    int withDamage = 0, withKnockback = 0, attributed = 0;
    double kbSum = 0;
    for (var in : a.incomingSamples) {
      if (!in.attributed()) continue;
      attributed++;
      if (in.damage() != null) withDamage++;
      if (in.knockback() != null) {
        withKnockback++;
        kbSum += in.knockback();
      }
    }
    c.stats(
        List.of(
            stat(
                "damageTaken",
                a.contextFights == 0 ? NONE : number(a.damageTaken, 1),
                "health + absorption"),
            new Stat(
                "Hits with health drop",
                withDamage + " / " + attributed,
                "attributed hits",
                "Attributed hits followed by a local health decrease within two ticks."),
            stat("knockback", withKnockback + " / " + attributed, "velocity within 1 tick"),
            new Stat(
                "Mean knockback",
                withKnockback == 0 ? NONE : number(kbSum / withKnockback, 2),
                "blocks/tick, horizontal",
                "Horizontal speed of the server velocity update after an attributed hit."),
            new Stat(
                "Damage dealt",
                "Unavailable",
                "not observable",
                "Servers commonly hide or alter opponent health; KWAMSTER does not estimate it.")));
    c.note(
        "Health is your own client value. Absorption, regeneration and armor are reflected only"
            + " through it; the server's damage amount is not reported. Velocity updates are"
            + " recorded as observed, without assuming what caused them.");
    contextNote(c, a, "health or knockback observations");
  }
}
