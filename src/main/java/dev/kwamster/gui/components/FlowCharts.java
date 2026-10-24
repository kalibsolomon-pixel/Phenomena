package dev.kwamster.gui.components;

import static dev.kwamster.gui.components.Blocks.*;

import dev.kwamster.analytics.Format;
import dev.kwamster.gui.theme.Theme;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 0.1.3 analysis charts. Data is prepared at build time; rendering maps numbers to pixels. Color
 * semantics are shared across charts and the HTML report: you = blue (above / top lane), opponent =
 * orange (below / bottom lane), neutral = grey, incomplete attribution = dotted grey, no evidence =
 * empty. Position and shape always carry the same meaning as color.
 */
public final class FlowCharts {
  private FlowCharts() {}

  /** Filled intervals are measured combat; the empty background is recording time on breaks. */
  public static final class ExchangeTimeline implements Blocks.Block {
    private final double duration;
    private final List<dev.kwamster.analytics.FightAnalytics.ActiveExchange> exchanges;

    public ExchangeTimeline(
        double duration, List<dev.kwamster.analytics.FightAnalytics.ActiveExchange> exchanges) {
      this.duration = Math.max(.001, duration);
      this.exchanges = exchanges;
    }

    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      g.fill(x, y + 5, x + w, y + 25, Theme.SURFACE_2);
      for (var ex : exchanges) {
        int from = x + (int) (w * Math.max(0, ex.start()) / duration);
        int to = x + (int) (w * Math.min(duration, ex.end()) / duration);
        g.fill(from, y + 5, Math.max(from + 1, to), y + 25, LOCAL);
      }
      timeAxis(g, x, y + 30, w, duration);
    }
  }

  public static final int LOCAL = Theme.SERIES_1, OPPONENT = Theme.SERIES_2, GOOD = Theme.SERIES_3;
  public static final int NEUTRAL = 0xFF8A949A, INCOMPLETE = 0xFF5E676D;

  static String secs(double v) {
    return Format.number(v, 1) + " s";
  }

  /** Time axis with start, middle and end labels in seconds. */
  static void timeAxis(GuiGraphics g, int px, int y, int pw, double duration) {
    var f = font();
    g.fill(px, y, px + pw, y + 1, Theme.BORDER);
    String a = "0 s", m = secs(duration / 2), b = secs(duration);
    g.drawString(f, a, px, y + 3, Theme.DIM, false);
    g.drawString(f, m, px + (pw - f.width(m)) / 2, y + 3, Theme.DIM, false);
    g.drawString(f, b, px + pw - f.width(b), y + 3, Theme.DIM, false);
  }

  static void legend(GuiGraphics g, int x, int y, String[] names, int[] colors, boolean[] hollow) {
    var f = font();
    int lx = x;
    for (int i = 0; i < names.length; i++) {
      if (hollow != null && hollow[i]) g.renderOutline(lx, y + 1, 6, 6, colors[i]);
      else g.fill(lx, y + 1, lx + 6, y + 7, colors[i]);
      g.drawString(f, names[i], lx + 9, y, Theme.MUTED, false);
      lx += f.width(names[i]) + 20;
    }
  }

  static void emptyState(GuiGraphics g, int px, int py, int pw, int ph, String text) {
    var f = font();
    String e = KButton.fit(f, text, pw - 8);
    g.drawString(f, e, px + (pw - f.width(e)) / 2, py + ph / 2 - 4, Theme.MUTED, false);
  }

  // ---- combo timeline --------------------------------------------------------------------

  /** One confirmed hit mark. side: true = you. */
  public record Mark(double time, boolean local, boolean traded, String label) {}

  /** A combo span (two or more consecutive hits). */
  public record Span(double start, double end, boolean local, int length, String label) {}

  /**
   * Two lanes over fight time: your confirmed hits on top, attributed opponent hits below. Combos
   * are bands behind their hits with the length printed on the band; the selected combo is
   * outlined. Same-tick trades are hollow squares. Exchange boundaries are faint separators.
   */
  public static final class ComboTimeline implements Blocks.Block {
    private final double duration;
    private final List<Mark> marks;
    private final List<Span> spans;
    private final List<Double> boundaries;
    private final int selected;
    private final String opponentNote;

    public ComboTimeline(
        double duration,
        List<Mark> marks,
        List<Span> spans,
        List<Double> boundaries,
        int selected,
        String opponentNote) {
      this.duration = Math.max(duration, .001);
      this.marks = marks;
      this.spans = spans;
      this.boundaries = boundaries;
      this.selected = selected;
      this.opponentNote = opponentNote;
    }

    public static int height() {
      return 86;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      int label = Math.max(f.width("Opponent"), f.width("You")) + 8;
      int px = x + label, pw = Math.max(20, w - label - 4);
      int laneH = 22, top = y + 4, bottom = top + laneH + 6;
      g.fill(px, top, px + pw, top + laneH, Theme.alpha(Theme.SURFACE_2, 0x90));
      g.fill(px, bottom, px + pw, bottom + laneH, Theme.alpha(Theme.SURFACE_2, 0x90));
      g.drawString(f, "You", x, top + 7, LOCAL, false);
      g.drawString(f, "Opponent", x, bottom + 7, OPPONENT, false);
      for (double b : boundaries) {
        int bx = sx(b, px, pw);
        g.fill(bx, top, bx + 1, bottom + laneH, Theme.GRID);
      }
      String hover = null;
      for (int i = 0; i < spans.size(); i++) {
        var s = spans.get(i);
        int x0 = sx(s.start(), px, pw) - 2, x1 = Math.max(x0 + 5, sx(s.end(), px, pw) + 3);
        int ly = s.local() ? top : bottom;
        int color = s.local() ? LOCAL : OPPONENT;
        g.fill(x0, ly + 3, x1, ly + laneH - 3, Theme.alpha(color, 0x55));
        if (i == selected) g.renderOutline(x0 - 1, ly + 2, x1 - x0 + 2, laneH - 4, Theme.TEXT);
        String n = "x" + s.length();
        int tx = Math.min(px + pw - f.width(n), Math.max(px, (x0 + x1 - f.width(n)) / 2));
        g.drawString(f, n, tx, s.local() ? ly - 1 : ly + laneH - 7, Theme.TEXT, false);
        if (hover(mx, my, x0, ly, x1 - x0, laneH)) hover = s.label();
      }
      for (var m : marks) {
        int cx = sx(m.time(), px, pw);
        int ly = m.local() ? top : bottom;
        int color = m.local() ? LOCAL : OPPONENT;
        if (m.traded()) g.renderOutline(cx - 2, ly + laneH / 2 - 2, 5, 5, color);
        else g.fill(cx - 1, ly + 6, cx + 1, ly + laneH - 6, color);
        if (Math.abs(mx - cx) <= 2 && my >= ly && my < ly + laneH && Viewport.contains(mx, my))
          hover = m.label();
      }
      if (marks.isEmpty())
        emptyState(g, px, top, pw, laneH * 2 + 6, "No confirmed hits in this fight");
      timeAxis(g, px, bottom + laneH + 2, pw, duration);
      legend(
          g,
          px,
          bottom + laneH + 14,
          new String[] {"Your hit", "Opponent hit (attributed)", "Same-tick trade", "Combo band"},
          new int[] {LOCAL, OPPONENT, Theme.MUTED, Theme.alpha(LOCAL, 0x90)},
          new boolean[] {false, false, true, false});
      if (opponentNote != null)
        g.drawString(
            f, KButton.fit(f, opponentNote, pw), px, bottom + laneH + 26, Theme.WARN, false);
      if (hover != null) tooltip(g, hover, mx, my);
    }

    private int sx(double t, int px, int pw) {
      return px + (int) Math.round((pw - 1) * Math.max(0, Math.min(1, t / duration)));
    }
  }

  // ---- dominance -------------------------------------------------------------------------

  /**
   * Encounter dominance over time: area above the centerline favors you, below favors the opponent.
   * Columns without enough evidence are left empty; incomplete attribution is dotted grey. A state
   * strip under the plot repeats the classification without relying on color alone (letters in the
   * tooltip). Hit ticks run along the top (you) and bottom (opponent) edges.
   */
  public static final class DominanceChart implements Blocks.Block {
    private final double duration, step;
    private final double[] values;
    private final char[] states;
    private final double[] localHits, opponentHits;
    private final List<double[]> combos;
    private final List<Double> boundaries;
    private final String empty;

    public DominanceChart(
        double duration,
        double step,
        double[] values,
        char[] states,
        double[] localHits,
        double[] opponentHits,
        List<double[]> combos,
        List<Double> boundaries,
        String empty) {
      this.duration = Math.max(duration, step);
      this.step = step;
      this.values = values;
      this.states = states;
      this.localHits = localHits;
      this.opponentHits = opponentHits;
      this.combos = combos;
      this.boundaries = boundaries;
      this.empty = empty;
    }

    public static int height(boolean compact) {
      return compact ? 118 : 138;
    }

    static int stateColor(char s) {
      return switch (s) {
        case 'L' -> LOCAL;
        case 'O' -> OPPONENT;
        case 'N' -> NEUTRAL;
        case 'C' -> INCOMPLETE;
        default -> 0;
      };
    }

    static String stateName(char s) {
      return switch (s) {
        case 'L' -> "advantage: you";
        case 'O' -> "advantage: opponent";
        case 'N' -> "neutral";
        case 'C' -> "incomplete attribution";
        default -> "no evidence";
      };
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      String up = "+1 you", mid = "0", down = "-1 opp";
      int gutter = Math.max(f.width(up), f.width(down)) + 6;
      int px = x + gutter, pw = Math.max(20, w - gutter - 4);
      int legendH = 24, py = y + 4, ph = Math.max(30, h - 26 - legendH);
      g.fill(px, py, px + pw, py + ph, Theme.alpha(Theme.SURFACE_2, 0x90));
      int zero = py + ph / 2;
      g.drawString(f, up, px - 4 - f.width(up), py, LOCAL, false);
      g.drawString(f, mid, px - 4 - f.width(mid), zero - 4, Theme.DIM, false);
      g.drawString(f, down, px - 4 - f.width(down), py + ph - 8, OPPONENT, false);
      for (double b : boundaries) {
        int bx = sx(b, px, pw);
        for (int yy = py; yy < py + ph; yy += 4) g.fill(bx, yy, bx + 1, yy + 2, Theme.BORDER);
      }
      boolean any = false;
      int prevY = 0;
      boolean pen = false;
      for (int col = 0; col < pw; col++) {
        double t = duration * col / Math.max(1, pw - 1);
        int k = Math.min(values.length - 1, Math.max(0, (int) Math.round(t / step)));
        double v = values[k];
        char s = states[k];
        if (!Double.isFinite(v) || s == 'I') {
          pen = false;
          continue;
        }
        any = true;
        int vy = zero - (int) Math.round(v * (ph / 2.0 - 2));
        int color = stateColor(s);
        if (s == 'C') {
          for (int yy = Math.min(vy, zero); yy <= Math.max(vy, zero); yy += 2)
            if ((col + yy) % 4 == 0) g.fill(px + col, yy, px + col + 1, yy + 1, color);
        } else
          g.fill(
              px + col,
              Math.min(vy, zero),
              px + col + 1,
              Math.max(vy, zero) + 1,
              Theme.alpha(color, 0x70));
        if (pen) line(g, px + col - 1, prevY, px + col, vy, Theme.alpha(Theme.TEXT, 0xC0));
        prevY = vy;
        pen = true;
      }
      g.fill(px, zero, px + pw, zero + 1, Theme.alpha(Theme.TEXT, 0x80));
      for (var c : combos) {
        int x0 = sx(c[0], px, pw), x1 = Math.max(x0 + 2, sx(c[1], px, pw));
        g.fill(x0, py + 4, x1, py + 6, Theme.alpha(LOCAL, 0xC0));
      }
      for (double t : localHits) {
        int cx = sx(t, px, pw);
        g.fill(cx, py, cx + 1, py + 3, LOCAL);
      }
      for (double t : opponentHits) {
        int cx = sx(t, px, pw);
        g.fill(cx, py + ph - 3, cx + 1, py + ph, OPPONENT);
      }
      // State strip
      int sy = py + ph + 2;
      for (int col = 0; col < pw; col++) {
        double t = duration * col / Math.max(1, pw - 1);
        int k = Math.min(states.length - 1, Math.max(0, (int) Math.round(t / step)));
        int color = stateColor(states[k]);
        if (color != 0) g.fill(px + col, sy, px + col + 1, sy + 3, color);
      }
      timeAxis(g, px, sy + 5, pw, duration);
      legend(
          g,
          px,
          sy + 17,
          new String[] {"You", "Opponent", "Neutral", "Incomplete", "No evidence: empty"},
          new int[] {LOCAL, OPPONENT, NEUTRAL, INCOMPLETE, Theme.SURFACE_2},
          new boolean[] {false, false, false, true, true});
      if (!any) emptyState(g, px, py, pw, ph, empty);
      if (hover(mx, my, px, py, pw, ph + 6)) {
        double t = duration * (mx - px) / Math.max(1, pw - 1);
        int k = Math.min(values.length - 1, Math.max(0, (int) Math.round(t / step)));
        int cx = sx(t, px, pw);
        g.fill(cx, py, cx + 1, py + ph, Theme.alpha(Theme.TEXT, 0x50));
        tooltip(
            g,
            secs(t)
                + ": "
                + (Double.isFinite(values[k]) ? Format.number(values[k], 2) + ", " : "")
                + stateName(states[k]),
            mx,
            my);
      }
    }

    private int sx(double t, int px, int pw) {
      return px + (int) Math.round((pw - 1) * Math.max(0, Math.min(1, t / duration)));
    }
  }

  // ---- reach histogram --------------------------------------------------------------------

  /**
   * Eligible confirmed-hit reach in 0.05-block bins between 2.0 and 4.0 blocks, plus open-ended
   * edge bins. The configured target window is shaded and the target is a vertical line; bars in
   * the window are green and every bar shows its count on hover.
   */
  public static final class ReachHistogram implements Blocks.Block {
    public static final double LO = 2, HI = 4, BIN = .05;
    private final int[] bins;
    private final double target, tolerance;
    private final int max, total;

    public ReachHistogram(double[] values, double target, double tolerance) {
      int n = (int) Math.round((HI - LO) / BIN) + 2;
      bins = new int[n];
      for (double v : values) bins[bin(v)]++;
      int m = 0;
      for (int b : bins) m = Math.max(m, b);
      max = m;
      total = values.length;
      this.target = target;
      this.tolerance = tolerance;
    }

    static int bin(double v) {
      if (v < LO) return 0;
      if (v >= HI) return (int) Math.round((HI - LO) / BIN) + 1;
      return 1 + (int) Math.floor((v - LO) / BIN + 1e-9);
    }

    static String range(int i, int n) {
      if (i == 0) return "< " + Format.number(LO, 2) + " blocks";
      if (i == n - 1) return ">= " + Format.number(HI, 2) + " blocks";
      double a = LO + (i - 1) * BIN;
      return Format.number(a, 2) + "-" + Format.number(a + BIN, 2) + " blocks";
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      String top = Integer.toString(Math.max(1, max));
      int gutter = f.width(top) + 6;
      int px = x + gutter, py = y + 4, pw = w - gutter - 2, ph = h - 32;
      int n = bins.length;
      double slot = pw / (double) n;
      // Shaded window
      double a = target - tolerance, b = target + tolerance;
      int wx0 = px + (int) Math.round(xOf(a) * slot), wx1 = px + (int) Math.round(xOf(b) * slot);
      g.fill(wx0, py, Math.max(wx0 + 1, wx1), py + ph, Theme.alpha(GOOD, 0x30));
      g.fill(px, py + ph, px + pw, py + ph + 1, Theme.BORDER);
      g.drawString(f, top, px - 4 - f.width(top), py - 3, Theme.DIM, false);
      g.drawString(f, "0", px - 4 - f.width("0"), py + ph - 4, Theme.DIM, false);
      String hover = null;
      for (int i = 0; i < n; i++) {
        int bx = px + (int) Math.round(i * slot), bw = Math.max(1, (int) Math.round(slot) - 1);
        int bh = max == 0 ? 0 : (int) Math.round((ph - 2) * bins[i] / (double) max);
        double mid = i == 0 ? LO - BIN : i == n - 1 ? HI + BIN : LO + (i - .5) * BIN;
        boolean in = Math.abs(mid - target) <= tolerance + 1e-9;
        if (bins[i] > 0) g.fill(bx, py + ph - bh, bx + bw, py + ph, in ? GOOD : LOCAL);
        if (hover(mx, my, bx, py, bw + 1, ph + 1))
          hover = range(i, n) + ": " + bins[i] + (in ? " (in target window)" : "");
      }
      int tx = px + (int) Math.round(xOf(target) * slot);
      g.fill(tx, py, tx + 1, py + ph, Theme.TEXT);
      // Axis labels every 0.5 blocks, in blocks.
      for (double v = LO; v <= HI + 1e-9; v += .5) {
        String l = Format.number(v, 1);
        int lx = px + (int) Math.round(xOf(v) * slot);
        g.drawString(f, l, lx - f.width(l) / 2, py + ph + 3, Theme.DIM, false);
      }
      String unit = "reach (blocks, eye to hitbox)";
      g.drawString(f, unit, px + pw - f.width(unit), py + ph + 13, Theme.DIM, false);
      legend(
          g,
          px,
          py + ph + 13,
          new String[] {"In window", "Outside"},
          new int[] {GOOD, LOCAL},
          null);
      if (total == 0) emptyState(g, px, py, pw, ph, "No eligible confirmed hits (ray contact)");
      if (hover != null) tooltip(g, hover, mx, my);
    }

    /** Slot coordinate (in bins) of a reach value. */
    private static double xOf(double v) {
      if (v < LO) return .5;
      if (v > HI) return (HI - LO) / BIN + 1.5;
      return 1 + (v - LO) / BIN;
    }
  }

  // ---- weapon timing ------------------------------------------------------------------------

  /** One graded interval for the scatter. */
  public record TimingPoint(
      double delta, String grade, String outcome, boolean reset, String label) {}

  /**
   * Interval minus expected cooldown (ticks) for each attempt, in order. Zero is "one cooldown
   * later"; the tolerance band is shaded. Color is the timing grade; shape is the result (filled
   * square hit, diamond shield, hollow no hit). Resets (openers) are marks on the axis.
   */
  public static final class TimingScatter implements Blocks.Block {
    private final List<TimingPoint> points;
    private final double tolerance, range;

    public TimingScatter(List<TimingPoint> points, double tolerance) {
      this.points = points;
      this.tolerance = tolerance;
      double m = 4;
      for (var p : points) if (Double.isFinite(p.delta())) m = Math.max(m, Math.abs(p.delta()));
      this.range = Math.min(40, Math.ceil(m + 1));
    }

    static int gradeColor(String g) {
      return switch (g) {
        case "PERFECT" -> GOOD;
        case "TOO_FAST" -> OPPONENT;
        case "TOO_SLOW" -> LOCAL;
        default -> NEUTRAL;
      };
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      String top = "+" + Format.number(range, 0) + " t",
          bottom = "-" + Format.number(range, 0) + " t";
      int gutter = Math.max(f.width(top), f.width(bottom)) + 6;
      int px = x + gutter, py = y + 4, pw = w - gutter - 4, ph = h - 32;
      g.fill(px, py, px + pw, py + ph, Theme.alpha(Theme.SURFACE_2, 0x90));
      int zero = py + ph / 2;
      int band = (int) Math.round(tolerance / range * (ph / 2.0));
      g.fill(px, zero - band, px + pw, zero + band + 1, Theme.alpha(GOOD, 0x30));
      g.fill(px, zero, px + pw, zero + 1, Theme.alpha(Theme.TEXT, 0x80));
      g.drawString(f, top, px - 4 - f.width(top), py, Theme.DIM, false);
      g.drawString(f, "0", px - 4 - f.width("0"), zero - 4, Theme.DIM, false);
      g.drawString(f, bottom, px - 4 - f.width(bottom), py + ph - 8, Theme.DIM, false);
      String hover = null;
      int n = Math.max(1, points.size());
      for (int i = 0; i < points.size(); i++) {
        var p = points.get(i);
        int cx = px + 3 + (int) Math.round((pw - 7) * (n == 1 ? .5 : i / (double) (n - 1)));
        if (p.reset() || !Double.isFinite(p.delta())) {
          g.fill(cx, py + ph - 6, cx + 1, py + ph, Theme.MUTED);
          if (Math.abs(mx - cx) <= 1 && hover(mx, my, px, py, pw, ph)) hover = p.label();
          continue;
        }
        double v = Math.max(-range, Math.min(range, p.delta()));
        int cy = zero - (int) Math.round(v / range * (ph / 2.0 - 3));
        int color = gradeColor(p.grade());
        switch (p.outcome()) {
          case "HIT" -> g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
          case "SHIELD_BLOCK", "SHIELD_DISABLE" -> {
            g.fill(cx, cy - 2, cx + 1, cy + 3, color);
            g.fill(cx - 2, cy, cx + 3, cy + 1, color);
          }
          default -> g.renderOutline(cx - 2, cy - 2, 5, 5, color);
        }
        if (Math.abs(mx - cx) <= 2 && Math.abs(my - cy) <= 3 && Viewport.contains(mx, my))
          hover = p.label();
      }
      String axis = "attempt order; y = interval minus expected cooldown (ticks)";
      g.drawString(f, KButton.fit(f, axis, pw), px, py + ph + 3, Theme.DIM, false);
      legend(
          g,
          px,
          py + ph + 14,
          new String[] {"Perfect", "Too fast", "Too slow", "Hit", "No hit", "Reset"},
          new int[] {GOOD, OPPONENT, LOCAL, Theme.TEXT, Theme.TEXT, Theme.MUTED},
          new boolean[] {false, false, false, false, true, false});
      if (points.isEmpty())
        emptyState(g, px, py, pw, ph, "No attack timing samples for this filter");
      if (hover != null) tooltip(g, hover, mx, my);
    }
  }

  // ---- jump offset strip --------------------------------------------------------------------

  /**
   * Signed tick offsets (jump minus attributed incoming hit) from -10 to +10. Each incoming hit
   * with a nearby actual jump is one dot stacked in its tick column; the candidate window is shaded
   * and the same-tick column outlined. Offsets beyond the range sit on the edge columns.
   */
  public static final class OffsetStrip implements Blocks.Block {
    public static final int RANGE = 10;
    private final int[] counts = new int[2 * RANGE + 1];
    private final int window, max;

    public OffsetStrip(List<Long> offsets, int window) {
      this.window = window;
      int m = 0;
      for (long o : offsets) {
        int k = (int) Math.max(-RANGE, Math.min(RANGE, o)) + RANGE;
        m = Math.max(m, ++counts[k]);
      }
      max = m;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      int px = x + 4, py = y + 4, pw = w - 8, ph = h - 32;
      double slot = pw / (double) counts.length;
      int w0 = px + (int) Math.round((RANGE - window) * slot);
      int w1 = px + (int) Math.round((RANGE + window + 1) * slot);
      g.fill(w0, py, w1, py + ph, Theme.alpha(GOOD, 0x28));
      int c0 = px + (int) Math.round(RANGE * slot);
      g.renderOutline(c0, py, Math.max(2, (int) Math.round(slot)), ph, GOOD);
      g.fill(px, py + ph, px + pw, py + ph + 1, Theme.BORDER);
      String hover = null;
      int dot = Math.max(2, Math.min(5, (int) (slot / 2)));
      for (int i = 0; i < counts.length; i++) {
        int cx = px + (int) Math.round((i + .5) * slot);
        int per = Math.max(1, (ph - 2) / (dot + 1));
        for (int k = 0; k < counts[i] && k < per; k++) {
          int cy = py + ph - 2 - k * (dot + 1);
          int offset = i - RANGE;
          int color = offset == 0 ? GOOD : Math.abs(offset) <= window ? LOCAL : NEUTRAL;
          g.fill(cx - dot / 2, cy - dot, cx - dot / 2 + dot, cy, color);
        }
        if (hover(mx, my, px + (int) Math.round(i * slot), py, (int) Math.ceil(slot), ph + 1)) {
          int offset = i - RANGE;
          hover =
              (offset == -RANGE ? "<= " : offset == RANGE ? ">= " : "")
                  + (offset > 0 ? "+" : "")
                  + offset
                  + " ticks: "
                  + counts[i]
                  + (offset == 0
                      ? " (same tick: ideal-timing candidate)"
                      : Math.abs(offset) <= window ? " (attempt candidate)" : " (outside window)");
        }
      }
      for (int o : new int[] {-RANGE, -window, 0, window, RANGE}) {
        String l = (o > 0 ? "+" : "") + o;
        int lx = px + (int) Math.round((o + RANGE + .5) * slot);
        g.drawString(f, l, lx - f.width(l) / 2, py + ph + 3, Theme.DIM, false);
      }
      String unit = "jump tick minus incoming-hit tick (early is negative)";
      g.drawString(f, KButton.fit(f, unit, pw), px, py + ph + 14, Theme.DIM, false);
      if (max == 0) emptyState(g, px, py, pw, ph, "No actual jumps near attributed incoming hits");
      if (hover != null) tooltip(g, hover, mx, my);
    }
  }
}
