package dev.phenomena.gui.components;

import dev.phenomena.analytics.Format;
import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Non-interactive content blocks and charts. Data is prepared once when a page is built; render
 * methods only map prepared numbers to pixels and allocate nothing per frame beyond tooltips.
 */
public final class Blocks {
  private Blocks() {}

  /** A positioned renderer inside the scroll content. */
  public interface Block {
    void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my);
  }

  static Font font() {
    return Minecraft.getInstance().font;
  }

  static boolean hover(int mx, int my, int x, int y, int w, int h) {
    return mx >= x && mx < x + w && my >= y && my < y + h && Viewport.contains(mx, my);
  }

  static void tooltip(GuiGraphics g, String text, int mx, int my) {
    g.setTooltipForNextFrame(font(), Component.literal(text), mx, my);
  }

  /** One-logical-pixel antialiased line at device resolution (chart segments). */
  static void line(GuiGraphics g, float x0, float y0, float x1, float y1, int color) {
    Pixel.line(g, x0, y0, x1, y1, 1, color);
  }

  // ---- text ----------------------------------------------------------------

  /** Wrapped paragraph; height is fixed at build time from the content width. */
  public static final class Text implements Block {
    private final List<FormattedCharSequence> lines;
    private final int color;

    public Text(String text, int width, int color) {
      this.lines = font().split(Component.literal(text), Math.max(20, width));
      this.color = color;
    }

    public int height() {
      return lines.size() * 10;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      for (int i = 0; i < lines.size(); i++)
        g.drawString(font(), lines.get(i), x, y + i * 10, color, false);
    }
  }

  /** Explanatory note with a subtle left rule. */
  public static final class Note implements Block {
    private final List<FormattedCharSequence> lines;
    private final int rule;

    public Note(String text, int width, int rule) {
      this.lines = font().split(Component.literal(text), Math.max(20, width - 12));
      this.rule = rule;
    }

    public int height() {
      return lines.size() * 10 + 8;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, Theme.alpha(Theme.SURFACE_2, 0xA0));
      Pixel.rounded(g, x, y, 2.5f, h, 1.25f, rule);
      for (int i = 0; i < lines.size(); i++)
        g.drawString(font(), lines.get(i), x + 8, y + 5 + i * 10, Theme.MUTED, false);
    }
  }

  /** Section heading inside the content area. */
  public static final class Heading implements Block {
    private final String text, right;

    public Heading(String text, String right) {
      this.text = text;
      this.right = right;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      g.drawString(f, text, x, y + 2, Theme.TEXT, false);
      int tw = f.width(text) + 6;
      if (right != null && !right.isEmpty()) {
        String r = KButton.fit(f, right, Math.max(0, w - tw - 10));
        int rw = f.width(r);
        g.drawString(f, r, x + w - rw, y + 2, Theme.DIM, false);
        Pixel.line(g, x + tw, y + 6.5f, x + w - rw - 6, y + 6.5f, 1, Theme.BORDER_SOFT);
      } else Pixel.line(g, x + tw, y + 6.5f, x + w, y + 6.5f, 1, Theme.BORDER_SOFT);
    }
  }

  // ---- stat cards ----------------------------------------------------------

  public record Stat(String label, String value, String caption, String help) {}

  /** Responsive grid of stat cards: label, prominent value, short qualifier. */
  public static final class StatGrid implements Block {
    public static final int GAP = 4;
    private final List<Stat> stats;
    private final int columns, cardH;

    public StatGrid(List<Stat> stats, int columns, boolean compact) {
      this.stats = stats;
      this.columns = Math.max(1, columns);
      this.cardH = cardHeight(compact);
    }

    public static int cardHeight(boolean compact) {
      return compact ? 31 : 36;
    }

    public static int height(int count, int columns, boolean compact) {
      int rows = (count + columns - 1) / Math.max(1, columns);
      return rows * cardHeight(compact) + Math.max(0, rows - 1) * GAP;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      int cw = (w - GAP * (columns - 1)) / columns;
      for (int i = 0; i < stats.size(); i++) {
        var s = stats.get(i);
        int ch = cardH, step = cardH == 36 ? 11 : 9;
        int cx = x + (i % columns) * (cw + GAP), cy = y + (i / columns) * (ch + GAP);
        boolean hov = hover(mx, my, cx, cy, cw, ch);
        Pixel.rounded(g, cx, cy, cw, ch, Theme.RADIUS, hov ? Theme.SURFACE_HOVER : Theme.SURFACE_2);
        Pixel.outline(g, cx, cy, cw, ch, Theme.RADIUS, 1, Theme.BORDER_SOFT);
        g.drawString(f, KButton.fit(f, s.label(), cw - 10), cx + 5, cy + 4, Theme.MUTED, false);
        g.drawString(
            f,
            Component.literal(KButton.fit(f, s.value(), cw - 10))
                .withStyle(net.minecraft.ChatFormatting.BOLD),
            cx + 5,
            cy + 4 + step,
            Format.NONE.equals(s.value()) ? Theme.DIM : Theme.TEXT,
            false);
        if (s.caption() != null)
          g.drawString(
              f, KButton.fit(f, s.caption(), cw - 10), cx + 5, cy + 4 + 2 * step, Theme.DIM, false);
        if (hov && s.help() != null) tooltip(g, s.help(), mx, my);
      }
    }
  }

  // ---- composition bar -----------------------------------------------------

  /** Horizontal part-to-whole bar with a legend; segments separated by a surface gap. */
  public static final class StackBar implements Block {
    private final String[] names;
    private final int[] values, colors;
    private final int total;

    public StackBar(String[] names, int[] values, int[] colors) {
      this.names = names;
      this.values = values;
      this.colors = colors;
      this.total = Arrays.stream(values).sum();
    }

    public static int height() {
      return 30;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      Pixel.rounded(g, x, y, w, 10, 5, Theme.SURFACE_2);
      int cx = x;
      for (int i = 0; i < values.length; i++) {
        if (total == 0 || values[i] == 0) continue;
        int sw = (int) Math.round(w * values[i] / (double) total);
        if (i == values.length - 1) sw = x + w - cx;
        g.fill(cx, y, cx + Math.max(1, sw - 2), y + 10, colors[i]);
        if (hover(mx, my, cx, y, sw, 10))
          tooltip(
              g,
              names[i] + ": " + values[i] + " (" + Format.percent(100.0 * values[i] / total) + ")",
              mx,
              my);
        cx += sw;
      }
      int lx = x;
      for (int i = 0; i < names.length; i++) {
        String label = names[i] + " " + values[i];
        Pixel.rounded(g, lx, y + 17, 6, 6, 2, colors[i]);
        g.drawString(f, label, lx + 9, y + 16, Theme.MUTED, false);
        lx += f.width(label) + 20;
      }
      if (total == 0) g.drawString(f, "No attempts recorded", x + 4, y + 1, Theme.DIM, false);
    }
  }

  // ---- XY / trend chart ----------------------------------------------------

  public enum Mark {
    LINE,
    POINTS,
    HOLLOW
  }

  /** One series. xs may be null (index positions); NaN y values are gaps, never zeros. */
  public record Series(String name, double[] xs, double[] ys, int color, Mark mark) {}

  /**
   * Single-axis chart with fixed or data-driven bounds, recessive grid, min/mid/max y labels and a
   * crosshair tooltip on the nearest sample.
   */
  public static final class Chart implements Block {
    private final List<Series> series;
    private final String unit, xUnit, empty;
    private final String[] xLabels;
    private final double yMin, yMax, xMin, xMax;
    private final boolean grid, points, zeroLine;
    private final boolean hasData;

    public Chart(
        List<Series> series,
        double yMin,
        double yMax,
        String unit,
        String xUnit,
        String[] xLabels,
        boolean grid,
        boolean points,
        String empty) {
      this.series = series;
      this.unit = unit;
      this.xUnit = xUnit;
      this.xLabels = xLabels;
      this.grid = grid;
      this.points = points;
      this.empty = empty;
      double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
      double xl = Double.POSITIVE_INFINITY, xh = Double.NEGATIVE_INFINITY;
      boolean any = false;
      for (var s : series)
        for (int i = 0; i < s.ys().length; i++) {
          double v = s.ys()[i];
          if (!Double.isFinite(v)) continue;
          any = true;
          lo = Math.min(lo, v);
          hi = Math.max(hi, v);
          double xv = s.xs() == null ? i : s.xs()[i];
          xl = Math.min(xl, xv);
          xh = Math.max(xh, xv);
        }
      hasData = any;
      this.yMin = Double.isFinite(yMin) ? yMin : any ? Math.min(0, lo) : 0;
      double top = Double.isFinite(yMax) ? yMax : any ? niceCeil(hi) : 1;
      this.yMax = top <= this.yMin ? this.yMin + 1 : top;
      this.zeroLine = this.yMin < 0 && this.yMax > 0;
      int maxIndex = 0;
      for (var s : series) if (s.xs() == null) maxIndex = Math.max(maxIndex, s.ys().length - 1);
      this.xMin = any && Double.isFinite(xl) ? Math.min(xl, 0) : 0;
      double xr = any ? Math.max(xh, maxIndex) : 1;
      this.xMax = xr <= xMin ? xMin + 1 : xr;
    }

    static double niceCeil(double v) {
      if (v <= 0) return 1;
      double mag = Math.pow(10, Math.floor(Math.log10(v)));
      for (double m : new double[] {1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10})
        if (m * mag >= v) return m * mag;
      return 10 * mag;
    }

    private String yLabel(double v) {
      return switch (unit) {
        case "%" -> Format.number(v, 0) + "%";
        case "m" -> Format.number(v, 1) + " m";
        case "s" -> Format.number(v, v < 10 ? 2 : 1) + " s";
        default -> Format.number(v, Math.abs(yMax - yMin) < 5 ? 1 : 0);
      };
    }

    private String value(double v) {
      return unit.isEmpty() ? Format.number(v, 2) : Format.value(v, unit);
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      String top = yLabel(yMax), mid = yLabel((yMin + yMax) / 2), bottom = yLabel(yMin);
      int gutter = Math.max(f.width(top), Math.max(f.width(mid), f.width(bottom))) + 6;
      int legendH = series.size() > 1 ? 12 : 0;
      int px = x + gutter,
          py = y + 4,
          pw = Math.max(10, w - gutter - 4),
          ph = Math.max(10, h - 18 - legendH);
      Pixel.rounded(g, px, py, pw, ph, Theme.RADIUS_SMALL, Theme.alpha(Theme.INSET, 0xB8));
      for (int i = 0; i <= 2; i++) {
        int gy = py + ph - i * ph / 2;
        if (grid || i == 0)
          Pixel.line(g, px + (i == 0 ? 0 : 2), gy - .5f, px + pw - (i == 0 ? 0 : 2), gy - .5f, 1,
              i == 0 ? Theme.BORDER : Theme.GRID);
      }
      g.drawString(f, top, px - 4 - f.width(top), py - 3, Theme.DIM, false);
      g.drawString(f, mid, px - 4 - f.width(mid), py + ph / 2 - 4, Theme.DIM, false);
      g.drawString(f, bottom, px - 4 - f.width(bottom), py + ph - 4, Theme.DIM, false);
      if (zeroLine) {
        int zy = sy(0, py, ph);
        Pixel.line(g, px, zy - .5f, px + pw, zy - .5f, 1, Theme.BORDER);
      }
      if (xLabels != null && xLabels.length > 0) {
        String a = xLabels[0], b = xLabels[xLabels.length - 1];
        g.drawString(f, a, px, py + ph + 4, Theme.DIM, false);
        if (xLabels.length > 1)
          g.drawString(f, b, px + pw - f.width(b), py + ph + 4, Theme.DIM, false);
      }
      if (xUnit != null && !xUnit.isEmpty()) {
        int uw = f.width(xUnit);
        g.drawString(f, xUnit, px + (pw - uw) / 2, py + ph + 4, Theme.DIM, false);
      }
      if (!hasData) {
        String e = KButton.fit(f, empty, pw - 8);
        g.drawString(f, e, px + (pw - f.width(e)) / 2, py + ph / 2 - 4, Theme.MUTED, false);
        return;
      }
      // Hover: nearest sample by x within the plot.
      boolean inPlot = hover(mx, my, px, py, pw, ph);
      Series hs = null;
      int hi = -1;
      double best = 7;
      for (var s : series) {
        int prevX = 0, prevY = 0;
        boolean prev = false;
        for (int i = 0; i < s.ys().length; i++) {
          double v = s.ys()[i];
          if (!Double.isFinite(v)) {
            prev = false;
            continue;
          }
          int cx = sx(s.xs() == null ? i : s.xs()[i], px, pw), cy = sy(v, py, ph);
          if (s.mark() == Mark.LINE) {
            if (prev) line(g, prevX, prevY, cx, cy, s.color());
            if (points && s.ys().length <= 120) Pixel.circle(g, cx + .5f, cy + .5f, 1.6f, s.color());
          } else if (s.mark() == Mark.POINTS) Pixel.circle(g, cx + .5f, cy + .5f, 1.6f, s.color());
          else Pixel.outline(g, cx - 2, cy - 2, 5, 5, 2, 1, s.color());
          if (inPlot) {
            double d = Math.abs(cx - mx) + (s.mark() == Mark.LINE ? 0 : Math.abs(cy - my) * .5);
            if (d < best) {
              best = d;
              hs = s;
              hi = i;
            }
          }
          prevX = cx;
          prevY = cy;
          prev = true;
        }
      }
      if (series.size() > 1) {
        int lx = px;
        for (var s : series) {
          if (s.mark() == Mark.HOLLOW) Pixel.outline(g, lx, py + ph + 15, 6, 6, 2, 1, s.color());
          else Pixel.rounded(g, lx, py + ph + 15, 6, 6, 2, s.color());
          g.drawString(f, s.name(), lx + 9, py + ph + 14, Theme.MUTED, false);
          lx += f.width(s.name()) + 20;
        }
      }
      if (hs != null) {
        double xv = hs.xs() == null ? hi : hs.xs()[hi];
        int cx = sx(xv, px, pw);
        Pixel.line(g, cx + .5f, py, cx + .5f, py + ph, 1, Theme.alpha(Theme.TEXT, 0x50));
        String where =
            hs.xs() == null && xLabels != null && hi < xLabels.length
                ? xLabels[hi]
                : (xUnit == null || xUnit.isEmpty()
                    ? "#" + (hi + 1)
                    : Format.number(xv, 2) + " " + xUnit);
        tooltip(g, hs.name() + " / " + where + ": " + value(hs.ys()[hi]), mx, my);
      }
    }

    private int sx(double v, int px, int pw) {
      return px + 2 + (int) Math.round((pw - 5) * (v - xMin) / (xMax - xMin));
    }

    private int sy(double v, int py, int ph) {
      double t = (Math.max(yMin, Math.min(yMax, v)) - yMin) / (yMax - yMin);
      return py + ph - 1 - (int) Math.round((ph - 3) * t);
    }
  }

  // ---- bar chart -----------------------------------------------------------

  /**
   * Vertical bars over ordered bins, optionally grouped with a second series. Colors may be set per
   * bar for semantic categories (readiness colors chosen by the player).
   */
  public static final class Bars implements Block {
    private final String[] labels;
    private final int[] a, b, barColors;
    private final String nameA, nameB;
    private final int colorA, colorB, max;
    private final String empty;

    public Bars(
        String[] labels,
        int[] a,
        String nameA,
        int colorA,
        int[] b,
        String nameB,
        int colorB,
        int[] barColors,
        String empty) {
      this.labels = labels;
      this.a = a;
      this.b = b;
      this.nameA = nameA;
      this.nameB = nameB;
      this.colorA = colorA;
      this.colorB = colorB;
      this.barColors = barColors;
      this.empty = empty;
      int m = 0;
      for (int v : a) m = Math.max(m, v);
      if (b != null) for (int v : b) m = Math.max(m, v);
      this.max = m;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      String top = Integer.toString(Math.max(1, max));
      int gutter = f.width(top) + 6;
      int legendH = b != null ? 12 : 0;
      int px = x + gutter, py = y + 4, pw = w - gutter - 2, ph = h - 18 - legendH;
      Pixel.line(g, px, py + ph + .5f, px + pw, py + ph + .5f, 1, Theme.BORDER);
      g.drawString(f, top, px - 4 - f.width(top), py - 3, Theme.DIM, false);
      g.drawString(f, "0", px - 4 - f.width("0"), py + ph - 4, Theme.DIM, false);
      if (grid()) Pixel.line(g, px, py - .5f, px + pw, py - .5f, 1, Theme.GRID);
      int n = labels.length;
      int slot = Math.max(1, pw / Math.max(1, n));
      int groups = b == null ? 1 : 2;
      int bw = Math.max(1, Math.min(18 * groups, slot - 2) / groups);
      if (max == 0) {
        String e = KButton.fit(f, empty, pw - 8);
        g.drawString(f, e, px + (pw - f.width(e)) / 2, py + ph / 2 - 4, Theme.MUTED, false);
      }
      int labelEvery = Math.max(1, (int) Math.ceil(n / (pw / 28.0)));
      for (int i = 0; i < n; i++) {
        int sx0 = px + i * slot + (slot - bw * groups) / 2;
        for (int k = 0; k < groups; k++) {
          int v = k == 0 ? a[i] : b[i];
          int bh = max == 0 ? 0 : (int) Math.round((ph - 2) * v / (double) max);
          int color = k == 1 ? colorB : barColors != null ? barColors[i] : colorA;
          int bx = sx0 + k * bw;
          if (v > 0)
            Pixel.rounded(
                g, bx, py + ph - bh, Math.max(1, bw - 1), Math.max(1, bh),
                Math.min(2, (bw - 1) / 2f), color);
          if (hover(mx, my, bx, py, bw, ph + 1))
            tooltip(g, labels[i] + " / " + (k == 0 ? nameA : nameB) + ": " + v, mx, my);
        }
        if (i % labelEvery == 0) {
          String l = KButton.fit(f, labels[i], slot * labelEvery - 2);
          g.drawString(
              f, l, px + i * slot + (slot - f.width(l)) / 2, py + ph + 4, Theme.DIM, false);
        }
      }
      if (b != null) {
        int lx = px;
        Pixel.rounded(g, lx, py + ph + 15, 6, 6, 2, colorA);
        g.drawString(f, nameA, lx + 9, py + ph + 14, Theme.MUTED, false);
        lx += f.width(nameA) + 20;
        Pixel.rounded(g, lx, py + ph + 15, 6, 6, 2, colorB);
        g.drawString(f, nameB, lx + 9, py + ph + 14, Theme.MUTED, false);
      }
    }

    private static boolean grid() {
      var c = dev.phenomena.PhenomenaClient.config;
      return c == null || c.chartGrid;
    }
  }

  // ---- hitbox --------------------------------------------------------------

  /**
   * Front view of the contacted face in normalized coordinates. Shows each recorded intersection or
   * an aggregate density grid, with the high/low quartiles and edge bands used by the placement
   * categories. Placement is descriptive only.
   */
  public static final class Hitbox implements Block {
    public static final int COLS = 6, ROWS = 12;
    private final double[][] hits, others;
    private final boolean heat;
    private final int[] cells = new int[COLS * ROWS];
    private final int maxCell;

    public Hitbox(double[][] hits, double[][] others, boolean heat) {
      this.hits = hits;
      this.others = others;
      this.heat = heat;
      int m = 0;
      for (var p : hits) {
        int c = Math.min(COLS - 1, (int) (p[0] * COLS)),
            r = Math.min(ROWS - 1, (int) ((1 - p[1]) * ROWS));
        m = Math.max(m, ++cells[r * COLS + c]);
      }
      maxCell = m;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      int bh = h - 8, bw = Math.max(24, bh / 3);
      int bx = x + Math.max(40, (w - bw) / 2 - 40), by = y + 4;
      g.fill(bx, by, bx + bw, by + bh, Theme.SURFACE_2);
      if (heat) {
        for (int r = 0; r < ROWS; r++)
          for (int c = 0; c < COLS; c++) {
            int v = cells[r * COLS + c];
            int x0 = bx + c * bw / COLS, x1 = bx + (c + 1) * bw / COLS;
            int y0 = by + r * bh / ROWS, y1 = by + (r + 1) * bh / ROWS;
            if (v > 0)
              g.fill(
                  x0,
                  y0,
                  x1,
                  y1,
                  Theme.mix(Theme.SURFACE_2, Theme.SEQ_HIGH, .2 + .8 * v / (double) maxCell));
            if (hover(mx, my, x0, y0, x1 - x0, y1 - y0))
              tooltip(g, v + " hits in this cell", mx, my);
          }
      }
      int q1 = by + bh / 4, q3 = by + bh * 3 / 4;
      Pixel.line(g, bx, q1 + .5f, bx + bw, q1 + .5f, 1, Theme.BORDER);
      Pixel.line(g, bx, q3 + .5f, bx + bw, q3 + .5f, 1, Theme.BORDER);
      int e1 = bx + (int) (bw * .15), e2 = bx + (int) Math.ceil(bw * .85);
      Pixel.line(g, e1 + .5f, q1, e1 + .5f, q3, 1, Theme.GRID);
      Pixel.line(g, e2 + .5f, q1, e2 + .5f, q3, 1, Theme.GRID);
      Pixel.outline(g, bx - 1, by - 1, bw + 2, bh + 2, Theme.RADIUS_SMALL, 1, Theme.BORDER);
      if (!heat) {
        for (var p : others) {
          float cx = bx + .5f + (float) (p[0] * (bw - 1)),
              cy = by + .5f + (float) ((1 - p[1]) * (bh - 1));
          Pixel.outline(g, cx - 2, cy - 2, 5, 5, 2, 1, Theme.SERIES_2);
        }
        for (var p : hits) {
          float cx = bx + .5f + (float) (p[0] * (bw - 1)),
              cy = by + .5f + (float) ((1 - p[1]) * (bh - 1));
          int[] colors = {0xFF56B4E9, 0xFF009E73, 0xFFE69F00, 0xFFCC79A7};
          int color = p.length > 2 && p[2] >= 0 && p[2] < 4 ? colors[(int) p[2]] : Theme.SERIES_1;
          Pixel.circle(g, cx, cy, 1.6f, color);
        }
      }
      int lx = bx + bw + 10;
      g.drawString(f, "High", lx, by + bh / 8 - 4, Theme.DIM, false);
      g.drawString(f, "Center / edge", lx, by + bh / 2 - 4, Theme.DIM, false);
      g.drawString(f, "Low", lx, by + bh * 7 / 8 - 4, Theme.DIM, false);
      String l1 = heat ? "Hit density" : "Correlated hit";
      int ly = by + bh - 20;
      int rx = x;
      Pixel.rounded(g, rx, ly, 6, 6, 2, heat ? Theme.SEQ_HIGH : Theme.SERIES_1);
      g.drawString(f, l1, rx + 9, ly - 1, Theme.MUTED, false);
      if (!heat) {
        Pixel.outline(g, rx, ly + 12, 6, 6, 2, 1, Theme.SERIES_2);
        g.drawString(f, "Contact, unconfirmed", rx + 9, ly + 11, Theme.MUTED, false);
      }
      if (hits.length == 0 && others.length == 0) {
        String e = "No ray contacts";
        g.drawString(f, e, bx + (bw - f.width(e)) / 2, by + bh / 2 - 4, Theme.MUTED, false);
      }
    }
  }

  // ---- table ---------------------------------------------------------------

  /** Virtualized text table; only rows inside the viewport are drawn. */
  public static final class Table implements Block {
    public static final int ROW = 11;
    private final String[] headers;
    private final List<String[]> rows;
    private final float[] weights;
    private final boolean[] right;

    public Table(String[] headers, float[] weights, boolean[] right, List<String[]> rows) {
      this.headers = headers;
      this.weights = weights;
      this.right = right;
      this.rows = rows;
    }

    public static int height(int rows) {
      return ROW + 2 + Math.max(1, rows) * ROW;
    }

    @Override
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
      var f = font();
      float total = 0;
      for (float wt : weights) total += wt;
      int[] cw = new int[weights.length];
      for (int i = 0; i < cw.length; i++) cw[i] = (int) (w * weights[i] / total);
      Pixel.rounded(g, x, y, w, ROW, Theme.RADIUS_SMALL, Theme.SURFACE_2);
      drawRow(g, f, headers, x, y + 2, cw, Theme.DIM);
      Pixel.line(g, x, y + ROW + .5f, x + w, y + ROW + .5f, 1, Theme.BORDER_SOFT);
      if (rows.isEmpty()) {
        g.drawString(f, "No rows", x + 4, y + ROW + 3, Theme.DIM, false);
        return;
      }
      var view = Viewport.get();
      int base = y + ROW + 2;
      int first = 0, last = rows.size() - 1;
      if (view != null) {
        first = Math.max(0, (view.y() - base) / ROW);
        last = Math.min(rows.size() - 1, (view.bottom() - base) / ROW + 1);
      }
      for (int i = first; i <= last; i++) {
        int ry = base + i * ROW;
        if (i % 2 == 1) g.fill(x, ry - 1, x + w, ry + ROW - 1, 0x0CFFFFFF);
        drawRow(g, f, rows.get(i), x, ry + 1, cw, Theme.TEXT);
      }
    }

    private void drawRow(GuiGraphics g, Font f, String[] cells, int x, int y, int[] cw, int color) {
      int cx = x + 3;
      for (int c = 0; c < cw.length && c < cells.length; c++) {
        String t = KButton.fit(f, cells[c] == null ? "" : cells[c], cw[c] - 6);
        int tx = right[c] ? cx + cw[c] - 6 - f.width(t) : cx;
        g.drawString(f, t, tx, y, c == 0 && color == Theme.TEXT ? Theme.MUTED : color, false);
        cx += cw[c];
      }
    }
  }
}
