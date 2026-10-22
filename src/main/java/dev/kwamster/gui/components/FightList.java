package dev.kwamster.gui.components;

import dev.kwamster.analytics.*;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.gui.theme.Theme;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * One focusable widget for a list of fight summaries. Rows outside the viewport are not drawn,
 * row text is formatted once, and no timeline is touched. Click selects, double-click or Enter
 * opens details; the optional right-hand column opens the browser report.
 */
public final class FightList extends AbstractWidget {
  private record Row(FightRecord fight, String[] cells) {}

  private record Column(String title, int width, boolean right) {}

  public static final int HEADER = 13;
  private final List<Row> rows = new ArrayList<>();
  private final Supplier<String> selected;
  private final Consumer<String> onSelect, onOpen, onBrowser;
  private final BiConsumer<Integer, Integer> reveal;
  private final int rowHeight;
  private List<Column> columns = List.of();
  private boolean[] shown = new boolean[0];

  public FightList(
      List<FightRecord> fights,
      int rowHeight,
      Supplier<String> selected,
      Consumer<String> onSelect,
      Consumer<String> onOpen,
      Consumer<String> onBrowser,
      BiConsumer<Integer, Integer> reveal) {
    super(0, 0, 100, HEADER + Math.max(1, fights.size()) * rowHeight, Component.literal("Fights"));
    this.rowHeight = rowHeight;
    this.selected = selected;
    this.onSelect = onSelect;
    this.onOpen = onOpen;
    this.onBrowser = onBrowser;
    this.reveal = reveal;
    for (var f : fights) {
      var s = f.statistics();
      rows.add(
          new Row(
              f,
              new String[] {
                Format.dateTime(f.startEpochMillis()),
                Format.duration(f.durationNanos()),
                Format.opponent(f.opponentLabel(), f.opponent()),
                Format.outcome(f.outcome()),
                Integer.toString(s.hits),
                Format.percent(Aggregate.trendValue(f, TrendMetric.ACCURACY)),
                Format.meters(Aggregate.trendValue(f, TrendMetric.REACH)),
                "Browser"
              }));
    }
  }

  public static int heightFor(int count, int rowHeight) {
    return HEADER + Math.max(1, count) * rowHeight;
  }

  /** Chooses visible columns for the current width: least important columns drop first. */
  private void layoutColumns(Font font) {
    String[] titles = {"Date", "Duration", "Opponent", "Outcome", "Hits", "Accuracy", "Reach", ""};
    String[] samples = {"0000-00-00 00:00", "00m 00s", "Opponent", "Interrupted", "000", "100.0%", "0.00 m", "Browser"};
    int[] widths = new int[titles.length];
    for (int i = 0; i < titles.length; i++)
      widths[i] = Math.max(font.width(titles[i]), font.width(samples[i])) + (i >= 4 ? 12 : 8);
    widths[2] = Math.max(widths[2], 78);
    if (onBrowser == null) widths[7] = 0;
    boolean[] right = {false, true, false, false, true, true, true, true};
    int[] dropOrder = {2, 1, 3, 6};
    shown = new boolean[titles.length];
    Arrays.fill(shown, true);
    if (onBrowser == null) shown[7] = false;
    int total = 0;
    for (int i = 0; i < titles.length; i++) if (shown[i]) total += widths[i];
    for (int d : dropOrder) {
      if (total <= width - 4) break;
      shown[d] = false;
      total -= widths[d];
    }
    if (!shown[0] || total > width - 4) {
      widths[0] = font.width("00-00 00:00") + 8;
    }
    // Spread spare width over the opponent (or date) column.
    int spare = Math.max(0, width - 4 - total);
    if (shown[2]) widths[2] += spare;
    else widths[0] += spare;
    List<Column> cols = new ArrayList<>();
    for (int i = 0; i < titles.length; i++)
      cols.add(new Column(titles[i], shown[i] ? widths[i] : 0, right[i]));
    columns = cols;
  }

  private int indexOf(String id) {
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).fight.id().equals(id)) return i;
    return -1;
  }

  private int rowAt(double y) {
    int i = (int) Math.floor((y - getY() - HEADER) / rowHeight);
    return i >= 0 && i < rows.size() ? i : -1;
  }

  private boolean inBrowserColumn(double x) {
    return onBrowser != null && shown.length > 7 && shown[7] && x >= getX() + width - columns.get(7).width - 2;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (!Viewport.contains(e.x(), e.y()) || !isMouseOver(e.x(), e.y()) || e.button() != 0)
      return false;
    int i = rowAt(e.y());
    if (i < 0) return true;
    String id = rows.get(i).fight.id();
    setFocused(true);
    if (inBrowserColumn(e.x())) onBrowser.accept(id);
    else if (doubleClick || Objects.equals(selected.get(), id)) onOpen.accept(id);
    else onSelect.accept(id);
    playDownSound(Minecraft.getInstance().getSoundManager());
    return true;
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return Viewport.contains(x, y) && super.isMouseOver(x, y);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (!isFocused() || rows.isEmpty()) return false;
    int i = indexOf(selected.get());
    switch (e.key()) {
      case GLFW.GLFW_KEY_DOWN -> i = Math.min(rows.size() - 1, i + 1);
      case GLFW.GLFW_KEY_UP -> i = Math.max(0, i < 0 ? 0 : i - 1);
      case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
        if (i >= 0) onOpen.accept(rows.get(i).fight.id());
        return true;
      }
      default -> {
        return false;
      }
    }
    if (i < 0) i = 0;
    onSelect.accept(rows.get(i).fight.id());
    int top = getY() + HEADER + i * rowHeight;
    reveal.accept(top, top + rowHeight);
    return true;
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mx, int my, float d) {
    var font = Minecraft.getInstance().font;
    layoutColumns(font);
    int x = getX(), y = getY();
    g.fill(x, y, x + width, y + HEADER - 1, Theme.SURFACE_2);
    drawCells(g, font, columns.stream().map(Column::title).toArray(String[]::new), y + 2, Theme.DIM);
    g.fill(x, y + HEADER - 1, x + width, y + HEADER, Theme.BORDER_SOFT);
    if (rows.isEmpty()) {
      g.drawString(font, "No fights match.", x + 4, y + HEADER + (rowHeight - 8) / 2, Theme.MUTED, false);
      return;
    }
    var view = Viewport.get();
    int first = 0, last = rows.size() - 1;
    if (view != null) {
      first = Math.max(0, (view.y() - y - HEADER) / rowHeight);
      last = Math.min(rows.size() - 1, (view.bottom() - y - HEADER) / rowHeight + 1);
    }
    String sel = selected.get();
    int hovered = isHovered() ? rowAt(my) : -1;
    for (int i = first; i <= last; i++) {
      var row = rows.get(i);
      int ry = y + HEADER + i * rowHeight;
      boolean isSel = row.fight.id().equals(sel);
      if (isSel) {
        g.fill(x, ry, x + width, ry + rowHeight, Theme.SURFACE_HOVER);
        g.fill(x, ry, x + 2, ry + rowHeight, Theme.accent());
      } else if (i == hovered) g.fill(x, ry, x + width, ry + rowHeight, Theme.SURFACE_2);
      else if (i % 2 == 1) g.fill(x, ry, x + width, ry + rowHeight, 0x0CFFFFFF);
      drawCells(g, font, row.cells, ry + (rowHeight - 8) / 2, -1);
    }
    if (KButton.keyboardFocus(this)) g.renderOutline(x, y, width, height, Theme.alpha(Theme.accent(), 0x90));
  }

  private void drawCells(GuiGraphics g, Font font, String[] cells, int ty, int color) {
    int cx = getX() + 4;
    for (int c = 0; c < columns.size(); c++) {
      var col = columns.get(c);
      if (col.width == 0) continue;
      String text = KButton.fit(font, cells[c], col.width - 6);
      int ink = color;
      if (ink == -1)
        ink =
            c == 7
                ? Theme.accent()
                : c == 0 || c >= 4 ? Theme.TEXT : Theme.MUTED;
      int tx = col.right ? cx + col.width - 6 - font.width(text) : cx;
      g.drawString(font, text, tx, ty, ink, false);
      cx += col.width;
    }
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput out) {
    int i = indexOf(selected.get());
    String text =
        i < 0
            ? rows.size() + " fights. Use arrow keys to select."
            : String.join(", ", Arrays.copyOf(rows.get(i).cells, 7));
    out.add(NarratedElementType.TITLE, Component.literal(text));
  }
}
