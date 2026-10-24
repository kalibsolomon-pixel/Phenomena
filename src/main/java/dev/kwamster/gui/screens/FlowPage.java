package dev.kwamster.gui.screens;

import static dev.kwamster.analytics.Format.*;
import static dev.kwamster.gui.screens.AnalysisSupport.*;
import static dev.kwamster.gui.screens.OverviewPage.stat;

import dev.kwamster.analytics.*;
import dev.kwamster.combat.metrics.Combos;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.components.Blocks.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState.FlowSection;
import java.util.*;
import net.minecraft.client.gui.components.AbstractWidget;

/** Fight Flow: combos, encounter dominance and exchanges for one focused fight plus the scope. */
final class FlowPage implements KwamsterScreen.Page {
  private final KwamsterScreen s;

  FlowPage(KwamsterScreen s) {
    this.s = s;
  }

  @Override
  public String title() {
    return "Fight Flow";
  }

  @Override
  public List<String> sections() {
    return Arrays.stream(FlowSection.values()).map(v -> v.label).toList();
  }

  @Override
  public int section() {
    return s.state.flow.ordinal();
  }

  @Override
  public void section(int index) {
    s.state.flow = FlowSection.values()[index];
  }

  @Override
  public List<AbstractWidget> toolbar(KwamsterScreen screen) {
    return scopeToolbar(screen);
  }

  @Override
  public void build(Content c) {
    var a = aggregate(s);
    if (empty(c, a)) return;
    if (a.timelines.isEmpty()) {
      c.note("Fight Flow needs a loaded fight timeline. Open a fight in History to load one.");
      return;
    }
    int n = a.timelines.size();
    s.state.focus(s.state.focus, n);
    var f = a.timelines.get(n - 1 - s.state.focus);
    focusRow(c, f, n);
    if (f.fight.manual()) {
      c.note(
          "Manual session: select a participant and exchange in the browser report for combos and"
              + " dominance. Session-wide dominance is unavailable.");
      for (var ex : f.fight.session().exchanges())
        c.text(
            opponent(ex.opponentLabel(), ex.opponent())
                + " / "
                + duration(ex.durationNanos())
                + " / "
                + ex.resolution().classification().label);
      c.row(
          new KButton(
                  "View session in browser",
                  () -> dev.kwamster.gui.FightActions.viewInBrowser(f.fight))
              .content());
      return;
    }
    switch (s.state.flow) {
      case COMBOS -> combos(c, a, f);
      case DOMINANCE -> dominance(c, a, f);
      case EXCHANGES -> exchanges(c, f);
    }
  }

  private void focusRow(Content c, FightAnalytics f, int n) {
    c.row(
        new KButton(
                "Older fight",
                () -> {
                  s.state.focus(s.state.focus + 1, n);
                  s.rebuild();
                })
            .enabled(s.state.focus < n - 1)
            .tooltip("Show the previous loaded fight in scope")
            .content(),
        new KButton(
                "Newer fight",
                () -> {
                  s.state.focus(s.state.focus - 1, n);
                  s.rebuild();
                })
            .enabled(s.state.focus > 0)
            .tooltip("Show the next loaded fight in scope")
            .content());
    c.text(
        "Fight "
            + (n - s.state.focus)
            + " of "
            + n
            + " loaded: "
            + dateTime(f.fight.startEpochMillis())
            + " vs "
            + opponent(f.fight.opponentLabel(), f.fight.opponent())
            + " / "
            + f.fight.resolution().classification().label
            + " / "
            + duration(f.fight.durationNanos()));
  }

  static FlowCharts.ComboTimeline timeline(FightAnalytics f, int selected) {
    List<FlowCharts.Mark> marks = new ArrayList<>();
    for (var m : f.combos.marks())
      marks.add(
          new FlowCharts.Mark(
              f.time(m.nanos()),
              m.side() == Combos.Side.LOCAL,
              m.traded(),
              (m.side() == Combos.Side.LOCAL ? "Your confirmed hit" : "Attributed opponent hit")
                  + " at "
                  + number(f.time(m.nanos()), 2)
                  + " s"
                  + (m.traded() ? " (same-tick trade)" : "")));
    List<FlowCharts.Span> spans = new ArrayList<>();
    for (var st : f.allCombos())
      spans.add(
          new FlowCharts.Span(
              f.time(st.startNanos()),
              f.time(st.endNanos()),
              st.side() == Combos.Side.LOCAL,
              st.length(),
              (st.side() == Combos.Side.LOCAL ? "Your combo: " : "Opponent combo: ")
                  + st.length()
                  + " hits in "
                  + number((st.endNanos() - st.startNanos()) / 1e9, 2)
                  + " s, ended by "
                  + endWord(st.endedBy())));
    List<Double> bounds = new ArrayList<>();
    for (var ex : f.combos.exchanges()) bounds.add(f.time(ex.startNanos()));
    String note =
        switch (f.opponentComboAvailability()) {
          case "Observed" -> null;
          case "Estimated" ->
              "Some incoming damage was unattributed: opponent combos are estimates.";
          default -> "Opponent hits could not be attributed: opponent combos are unavailable.";
        };
    return new FlowCharts.ComboTimeline(f.duration, marks, spans, bounds, selected, note);
  }

  static String endWord(Combos.End e) {
    return switch (e) {
      case RETALIATION -> "a return hit";
      case TRADE -> "a same-tick trade";
      case DISENGAGE -> "disengagement";
      case ROUND_BOUNDARY -> "a round boundary";
      case RECORDING_END -> "the end of the recording";
    };
  }

  private void combos(Content c, Aggregate a, FightAnalytics f) {
    c.heading("Combos in scope", coverage(a));
    c.stats(
        List.of(
            stat("longestCombo", Integer.toString(a.longestCombo), "your best"),
            stat(
                "averageCombo", number(a.averageLocalCombo(), 2), a.localCombos.size() + " combos"),
            stat("comboHits", Integer.toString(a.comboHits), "of " + a.hits + " hits"),
            stat("longestUnanswered", Integer.toString(a.longestUnanswered), "ignores gaps"),
            stat(
                "longestOpponentCombo",
                Integer.toString(a.longestOpponentCombo),
                a.unattributedIncoming > 0 ? "estimated" : "attributed hits")));
    var all = f.allCombos();
    if (s.state.combo >= all.size()) s.state.combo = -1;
    c.heading("Combo timeline", "focused fight; x = combo length");
    c.block(FlowCharts.ComboTimeline.height(), timeline(f, s.state.combo));
    c.row(
        new KButton(
                "Previous combo",
                () -> {
                  s.state.combo = s.state.combo <= 0 ? all.size() - 1 : s.state.combo - 1;
                  s.rebuild();
                })
            .enabled(!all.isEmpty())
            .tooltip("Select the previous combo")
            .content(),
        new KButton(
                "Next combo",
                () -> {
                  s.state.combo = s.state.combo >= all.size() - 1 ? 0 : s.state.combo + 1;
                  s.rebuild();
                })
            .enabled(!all.isEmpty())
            .tooltip("Select the next combo")
            .content());
    if (s.state.combo >= 0) {
      var st = all.get(s.state.combo);
      c.heading(
          (st.side() == Combos.Side.LOCAL ? "Your combo " : "Opponent combo ")
              + (s.state.combo + 1)
              + " of "
              + all.size(),
          st.length() + " hits / " + number((st.endNanos() - st.startNanos()) / 1e9, 2) + " s");
      List<String[]> rows = new ArrayList<>();
      Map<Long, FightAnalytics.Hit> hits = new HashMap<>();
      for (var h : f.hits) hits.put(h.attackSequence(), h);
      Map<Long, FightAnalytics.Incoming> incoming = new HashMap<>();
      for (var in : f.incoming) incoming.put(in.sequence(), in);
      int k = 1;
      for (long seq : st.sequences()) {
        var h = hits.get(seq);
        var in = incoming.get(seq);
        rows.add(
            h != null
                ? new String[] {
                  "#" + k++,
                  seconds(h.evidenceTime()),
                  "Your hit",
                  meters(h.reach()),
                  Interpretation.shortItem(h.weapon()),
                  Boolean.TRUE.equals(h.serverCrit())
                      ? "server crit"
                      : h.criticalConditions() ? "crit conditions" : NONE
                }
                : new String[] {
                  "#" + k++,
                  in == null ? NONE : seconds(in.time()),
                  "Opponent hit",
                  NONE,
                  in == null || in.damageType() == null
                      ? NONE
                      : Interpretation.shortItem(in.damageType()),
                  in != null && in.crit() ? "crit on you" : NONE
                });
      }
      c.block(
          Table.height(rows.size()),
          new Table(
              new String[] {"Hit", "Time", "Side", "Reach", "Weapon / type", "Critical"},
              new float[] {.6f, .9f, 1.2f, .9f, 1.6f, 1.3f},
              new boolean[] {false, true, false, true, false, false},
              rows));
      c.text("Ended by " + endWord(st.endedBy()) + ".");
    }
    c.note(
        "A combo is two or more consecutive confirmed hits by one side with no confirmed hit by the"
            + " other in between. It ends on an attributed return hit, a same-tick trade (order"
            + " unknown, so neither side's combo is extended), or a gap longer than the combo"
            + " timeout ("
            + number(f.fight.policy().comboSeconds(), 1)
            + " s). Empty swings, shield blocks, knockback and unattributed damage never extend or"
            + " end a combo.");
  }

  static FlowCharts.DominanceChart chart(FightAnalytics f) {
    var d = f.dominance;
    char[] states = new char[d.states.length];
    for (int i = 0; i < states.length; i++) states[i] = d.states[i].code;
    List<double[]> combos = new ArrayList<>();
    for (var st : f.allCombos())
      if (st.side() == Combos.Side.LOCAL)
        combos.add(new double[] {f.time(st.startNanos()), f.time(st.endNanos())});
    double[] local =
        f.combos.marks().stream()
            .filter(m -> m.side() == Combos.Side.LOCAL)
            .mapToDouble(m -> f.time(m.nanos()))
            .toArray();
    double[] opp =
        f.combos.marks().stream()
            .filter(m -> m.side() == Combos.Side.OPPONENT)
            .mapToDouble(m -> f.time(m.nanos()))
            .toArray();
    List<Double> bounds = new ArrayList<>();
    for (var b : f.boundaries)
      if (b.accepted()
          && (b.kind().equals("START")
              || b.kind().equals("ROUND_START")
              || b.kind().equals("RESULT"))) bounds.add(b.time());
    return new FlowCharts.DominanceChart(
        f.duration, d.step, d.values, states, local, opp, combos, bounds, "No confirmed exchanges");
  }

  private void dominance(Content c, Aggregate a, FightAnalytics f) {
    var d = f.dominance;
    c.heading(
        "Encounter dominance", "focused fight; " + d.availability.label.toLowerCase(Locale.ROOT));
    c.block(FlowCharts.DominanceChart.height(c.compact), chart(f));
    if (d.availability != Dominance.Availability.AVAILABLE)
      c.note(
          d.availability == Dominance.Availability.INCOMPLETE
              ? "Incoming-hit attribution was incomplete, so encounter dominance could not be"
                  + " established confidently. Dotted grey columns mark where unattributed damage"
                  + " was observed; they are not counted for either player."
              : "Too few confirmed exchanges were observed to estimate encounter dominance.",
          Theme.WARN);
    c.stats(
        List.of(
            stat(
                "dominanceShare",
                percent(d.share(Dominance.State.LOCAL)),
                "you, of " + secondsShort(d.activeSeconds) + " active"),
            new Stat(
                "Opponent advantage",
                percent(d.share(Dominance.State.OPPONENT)),
                "of active time",
                "Share of observed active combat time with dominance at or below -0.2."),
            new Stat(
                "Neutral",
                percent(d.share(Dominance.State.NEUTRAL)),
                "balanced",
                "Evidence present, between -0.2 and +0.2."),
            new Stat(
                "Longest advantage",
                d.longestAdvantage == null ? NONE : secondsShort(d.longestAdvantage.length()),
                d.longestAdvantage == null
                    ? "none"
                    : (d.longestAdvantage.side() == Dominance.State.LOCAL ? "you" : "opponent")
                        + " from "
                        + secondsShort(d.longestAdvantage.start()),
                "Longest continuous period on one side."),
            new Stat(
                "Largest swing",
                d.largestSwing == null
                    ? NONE
                    : number(d.largestSwing.toValue() - d.largestSwing.fromValue(), 2),
                d.largestSwing == null
                    ? "none"
                    : "within 3 s at " + secondsShort(d.largestSwing.from()),
                "Largest change of the dominance value within a 3-second window; negative moves"
                    + " toward the opponent."),
            new Stat(
                "Dominance changes",
                Integer.toString(d.changes),
                "you <-> opponent",
                "Passes from one side's advantage to the other's."),
            new Stat(
                "No evidence / incomplete",
                secondsShort(d.insufficientSeconds) + " / " + secondsShort(d.incompleteSeconds),
                "excluded from shares",
                "Inactive time and time with unattributed incoming damage.")));
    c.heading("In scope", coverage(a));
    c.text(
        "Pooled active time favoring you "
            + percent(a.dominanceShare(Dominance.State.LOCAL))
            + ", the opponent "
            + percent(a.dominanceShare(Dominance.State.OPPONENT))
            + ", neutral "
            + percent(a.dominanceShare(Dominance.State.NEUTRAL))
            + ". "
            + a.dominanceAvailability[0]
            + " fights available, "
            + a.dominanceAvailability[1]
            + " incomplete, "
            + a.dominanceAvailability[2]
            + " insufficient.");
    c.note(
        "Model: each confirmed hit weighs 1 (1.25 when it continues a combo), shield events"
            + " 0.25-0.5, all decaying with a 3 s time constant. D = (you - opponent) / (you +"
            + " opponent + 1), so thin evidence stays near zero and one event can reach at most"
            + " 0.5. Accuracy, aim and reach are not inputs. It describes combat advantage from"
            + " observed hits, not physical momentum and not a win probability.");
  }

  private void exchanges(Content c, FightAnalytics f) {
    if (f.fight.statistics().measurementVersion > 0) {
      c.heading(
          "Combat exchanges",
          f.activeExchanges.size()
              + " exchanges / "
              + number(f.combatActiveSeconds, 1)
              + " s combat-active / "
              + number(f.duration, 1)
              + " s recorded");
      c.block(48, new FlowCharts.ExchangeTimeline(f.duration, f.activeExchanges));
      c.note(
          "Filled intervals show combat; empty intervals show breaks. The independent exchange"
              + " timeout is "
              + number(f.fight.statistics().exchangeTimeoutSeconds, 1)
              + " s. Ending an exchange keeps the recording active.");
      List<String[]> measuredRows = new ArrayList<>();
      int i = 1;
      for (var e : f.activeExchanges)
        measuredRows.add(
            new String[] {
              "#" + i++,
              number(e.start(), 1) + "-" + number(e.end(), 1),
              number(e.end() - e.start(), 1),
              "" + e.hits(),
              "" + e.misses(),
              "" + e.combos(),
              "" + e.momentumChanges()
            });
      c.block(
          Table.height(measuredRows.size()),
          new Table(
              new String[] {
                "Exchange", "Time (s)", "Duration", "Hits", "Misses", "Combos", "Turns"
              },
              new float[] {1, 1.6f, 1, .7f, .7f, .8f, .7f},
              new boolean[] {false, true, true, true, true, true, true},
              measuredRows));
      c.block(FlowCharts.ComboTimeline.height(), timeline(f, -1));
      c.block(FlowCharts.DominanceChart.height(c.compact), chart(f));
      c.text("Finalization: " + f.fight.reason());
      return;
    }
    var ex = f.combos.exchanges();
    c.heading(
        "Exchanges",
        "focused fight; separated by gaps over "
            + number(f.fight.policy().comboSeconds(), 1)
            + " s");
    int won = 0, lost = 0;
    for (var e : ex) {
      if (e.advantage() > 0) won++;
      else if (e.advantage() < 0) lost++;
    }
    c.stats(
        List.of(
            new Stat(
                "Exchanges",
                Integer.toString(ex.size()),
                "observed",
                "Runs of hits without a disengagement gap."),
            new Stat(
                "Ahead / behind",
                won + " / " + lost,
                (ex.size() - won - lost) + " even",
                "Exchanges where you landed more / fewer confirmed hits than attributed opponent"
                    + " hits."),
            new Stat(
                "Turning points",
                Integer.toString(f.dominance.turningPoints().size()),
                "dominance changes",
                "Times the advantage passed from one side to the other.")));
    List<String[]> rows = new ArrayList<>();
    int k = 1;
    for (var e : ex)
      rows.add(
          new String[] {
            "#" + k++,
            number(f.time(e.startNanos()), 1) + "-" + number(f.time(e.endNanos()), 1) + " s",
            Integer.toString(e.localHits()),
            Integer.toString(e.opponentHits()),
            e.longestLocal() + " / " + e.longestOpponent(),
            (e.advantage() > 0 ? "+" : "") + e.advantage()
          });
    c.block(
        Table.height(rows.size()),
        new Table(
            new String[] {"Exchange", "Time", "Your hits", "Opp. hits", "Best combo", "Advantage"},
            new float[] {.9f, 1.6f, .9f, .9f, 1.1f, 1},
            new boolean[] {false, true, true, true, true, true},
            rows));
    var tp = f.dominance.turningPoints();
    if (!tp.isEmpty()) {
      c.heading("Key turning points");
      List<String[]> trows = new ArrayList<>();
      for (var p : tp)
        trows.add(
            new String[] {
              seconds(p[0]),
              p[1] > 0 ? "Advantage to you" : "Advantage to opponent",
              number(p[1], 2)
            });
      c.block(
          Table.height(trows.size()),
          new Table(
              new String[] {"Time", "Change", "Dominance"},
              new float[] {1, 2, 1},
              new boolean[] {true, false, true},
              trows));
    }
    c.heading("Interpretation");
    for (var line : Interpretation.of(f)) c.text(line);
  }

  static String secondsShort(double v) {
    return Double.isFinite(v) ? number(v, 1) + " s" : NONE;
  }
}
