package dev.phenomena.gui.components;

import dev.phenomena.gui.theme.Theme;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Current-color preview plus a row of restrained presets. Click a preset, or focus the control
 * and use Left/Right then Enter. Exact values are typed in the paired hexadecimal field.
 */
public final class ColorSwatches extends AbstractWidget {
  public static final int[] PRESETS = {
    0xFF88C8AA, 0xFF8FB4E0, 0xFFA4CBF0, 0xFFE4D28A, 0xFFE6A278, 0xFFE49790, 0xFFC9A6E4, 0xFF9AA6AC
  };
  public static final int CELL = 12, GAP = 3;

  public final String id;
  private final IntSupplier current;
  private final IntConsumer choose;
  private int cursor = -1;

  public ColorSwatches(String id, IntSupplier current, IntConsumer choose) {
    super(0, 0, width(), CELL + 4, Component.literal(id));
    this.id = id;
    this.current = current;
    this.choose = choose;
  }

  public static int width() {
    return (CELL + GAP) * (PRESETS.length + 1) + 6;
  }

  private int cellX(int i) {
    return getX() + (CELL + GAP) * (i + 1) + 6;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (!Viewport.contains(e.x(), e.y()) || !isActive() || e.button() != 0) return false;
    for (int i = 0; i < PRESETS.length; i++) {
      int x = cellX(i);
      if (e.x() >= x && e.x() < x + CELL && e.y() >= getY() + 2 && e.y() < getY() + 2 + CELL) {
        cursor = i;
        choose.accept(PRESETS[i]);
        playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return Viewport.contains(x, y) && super.isMouseOver(x, y);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (!isFocused()) return false;
    switch (e.key()) {
      case GLFW.GLFW_KEY_LEFT -> cursor = Math.max(0, (cursor < 0 ? 0 : cursor) - 1);
      case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(PRESETS.length - 1, cursor + 1);
      case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_KP_ENTER -> {
        if (cursor >= 0) choose.accept(PRESETS[cursor]);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mx, int my, float d) {
    int y = getY() + 2;
    int now = current.getAsInt() | 0xFF000000;
    g.fill(getX(), y, getX() + CELL, y + CELL, now);
    g.renderOutline(getX() - 1, y - 1, CELL + 2, CELL + 2, Theme.BORDER);
    for (int i = 0; i < PRESETS.length; i++) {
      int x = cellX(i);
      boolean hover = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
      g.fill(x, y, x + CELL, y + CELL, PRESETS[i]);
      boolean chosen = (PRESETS[i] & 0xFFFFFF) == (now & 0xFFFFFF);
      if (chosen || hover || (isFocused() && i == cursor))
        g.renderOutline(x - 1, y - 1, CELL + 2, CELL + 2, chosen ? Theme.TEXT : Theme.MUTED);
    }
    if (KButton.keyboardFocus(this)) g.renderOutline(getX() - 3, getY(), width + 2, height, Theme.alpha(Theme.accent(), 0x80));
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput out) {
    out.add(
        NarratedElementType.TITLE,
        Component.literal(id + String.format(" #%06X", current.getAsInt() & 0xFFFFFF)));
  }
}
