package dev.phenomena.gui.screens;

import dev.phenomena.gui.components.*;
import dev.phenomena.gui.components.Blocks.Block;
import dev.phenomena.gui.theme.Theme;
import dev.phenomena.gui.ui.Layout;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Builds a page as a vertical flow in content coordinates (x from 0, y from 0 at the top of the
 * scroll area). The screen later offsets every item by the viewport origin and scroll position.
 */
public final class Content {
  /** A widget or block with its content-space rectangle; widgets may have an inset. */
  public record Item(AbstractWidget widget, Block block, int x, int y, int w, int h, int insetX, int insetY) {}

  final List<Item> items = new ArrayList<>();
  final PhenomenaScreen screen;
  public final int width;
  public final boolean compact;
  public final int gap, rowH;
  int y;

  Content(PhenomenaScreen screen, int width, boolean compact) {
    this.screen = screen;
    this.width = width;
    this.compact = compact;
    this.gap = compact ? 4 : 6;
    this.rowH = compact ? 14 : 16;
  }

  public int y() {
    return y;
  }

  public void space(int h) {
    y += h;
  }

  public void block(int h, Block block) {
    items.add(new Item(null, block, 0, y, width, h, 0, 0));
    y += h + gap;
  }

  public void blockAt(int x, int w, int h, Block block) {
    items.add(new Item(null, block, x, y, w, h, 0, 0));
  }

  public void heading(String text) {
    heading(text, null);
  }

  public void heading(String text, String right) {
    if (y > 0) y += compact ? 2 : 4;
    block(11, new Blocks.Heading(text, right));
  }

  public void text(String text) {
    var t = new Blocks.Text(text, width, Theme.MUTED);
    block(t.height(), t);
  }

  public void note(String text) {
    note(text, Theme.BORDER);
  }

  public void note(String text, int rule) {
    var n = new Blocks.Note(text, width, rule);
    block(n.height(), n);
  }

  public void stats(List<Blocks.Stat> stats) {
    int max = Layout.columns(width, compact ? 96 : 104, Blocks.StatGrid.GAP, 5);
    // Prefer the widest column count whose last row holds more than one card; fall back
    // to fewer columns rather than leaving a lone orphan on its own row.
    int cols = 1;
    for (int c = max; c >= 2; c--) {
      int rem = stats.size() % c;
      if (rem == 0 || rem >= 2 || stats.size() <= c) {
        cols = c;
        break;
      }
    }
    block(
        Blocks.StatGrid.height(stats.size(), Math.max(1, cols), compact),
        new Blocks.StatGrid(stats, Math.max(1, cols), compact));
  }

  public int chartHeight() {
    return compact ? 92 : 112;
  }

  /** Places a widget at full width. */
  public <W extends AbstractWidget> W widget(W w, int h) {
    add(w, 0, y, width, h);
    y += h + gap;
    return w;
  }

  /** Equal-width row of widgets. */
  public void row(AbstractWidget... ws) {
    var segs = Layout.segments(0, y, width, rowH, ws.length, 4);
    for (int i = 0; i < ws.length; i++) add(ws[i], segs.get(i).x(), y, segs.get(i).w(), rowH);
    y += rowH + gap;
  }

  /**
   * Label (and optional help text) on the left, control on the right. Narrow widths stack the
   * control below the label so neither is truncated.
   */
  public void setting(String label, String help, AbstractWidget control) {
    var font = net.minecraft.client.Minecraft.getInstance().font;
    boolean stacked = width < 300;
    int controlW = stacked ? width : Math.min(190, width / 2);
    int labelW = stacked ? width : width - controlW - 10;
    var lines = help == null ? List.<net.minecraft.util.FormattedCharSequence>of()
        : font.split(net.minecraft.network.chat.Component.literal(help), labelW);
    int textH = 10 + lines.size() * 10;
    int top = y;
    blockAt(
        0,
        labelW,
        textH,
        (g, x, yy, w, h, mx, my) -> {
          g.drawString(font, KButton.fit(font, label, w), x, yy + (stacked ? 0 : 4), Theme.TEXT, false);
          for (int i = 0; i < lines.size(); i++)
            g.drawString(font, lines.get(i), x, yy + (stacked ? 10 : 14) + i * 10, Theme.DIM, false);
        });
    if (stacked) {
      y += textH + 2;
      add(control, 0, y, controlW, rowH);
      y += rowH + gap + 2;
    } else {
      add(control, width - controlW, top, controlW, rowH);
      y = top + Math.max(rowH, textH + 4) + gap + 2;
    }
  }

  /** Hex field plus preset swatches, kept in sync. */
  public void color(String label, String help, IntSupplier get, IntConsumer set) {
    var field = new KTextField(label, String.format(Locale.ROOT, "%06X", get.getAsInt() & 0xFFFFFF), 6);
    field.setFilter(s -> s.matches("[0-9a-fA-F]{0,6}"));
    field.setResponder(
        v -> {
          if (v.length() == 6) set.accept(0xFF000000 | Integer.parseInt(v, 16));
        });
    var swatches =
        new ColorSwatches(
            label + " presets",
            get,
            c -> {
              set.accept(c);
              field.setValue(String.format(Locale.ROOT, "%06X", c & 0xFFFFFF));
            });
    int top = y;
    setting(label, help, field);
    boolean stacked = width < 300;
    int x = stacked ? 0 : width - Math.min(190, width / 2);
    int sy = stacked ? y - gap : top + rowH + 3;
    add(swatches, x, sy, Math.min(ColorSwatches.width(), width), ColorSwatches.CELL + 4);
    y = Math.max(y, sy + ColorSwatches.CELL + 4 + gap + 2);
  }

  void add(AbstractWidget w, int x, int y, int width, int height) {
    if (w instanceof KToggle) {
      // Switches keep their intrinsic size, vertically centered on the row.
      w.setWidth(KToggle.W);
      w.setHeight(KToggle.H);
      items.add(
          new Item(w, null, x + width - KToggle.W, y + (height - KToggle.H) / 2, KToggle.W, KToggle.H, 0, 0));
    } else if (w instanceof KTextField) {
      int pad = KTextField.PAD;
      w.setWidth(Math.max(10, width - 2 * pad));
      w.setHeight(8);
      items.add(new Item(w, null, x, y, width, height, pad, (height - 8) / 2));
    } else {
      w.setWidth(width);
      w.setHeight(height);
      items.add(new Item(w, null, x, y, width, height, 0, 0));
    }
  }

  public int height() {
    return Math.max(0, y - gap);
  }
}
