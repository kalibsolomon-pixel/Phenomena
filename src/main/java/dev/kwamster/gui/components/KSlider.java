package dev.kwamster.gui.components;

import dev.kwamster.gui.theme.Theme;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Vanilla slider (arrow keys, drag, narration) with stepping, formatting and viewport clipping. */
public final class KSlider extends AbstractSliderButton {
  public final String id;
  private final double min, max, step;
  private final DoubleFunction<String> format;
  private final DoubleConsumer setter;

  public KSlider(
      String id,
      double current,
      double min,
      double max,
      double step,
      DoubleFunction<String> format,
      DoubleConsumer setter) {
    super(0, 0, 100, 16, Component.empty(), max > min ? (clamp(current, min, max) - min) / (max - min) : 0);
    this.id = id;
    this.min = min;
    this.max = max;
    this.step = step;
    this.format = format;
    this.setter = setter;
    updateMessage();
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public double current() {
    double v = min + value * (max - min);
    if (step > 0) v = Math.round((v - min) / step) * step + min;
    return clamp(v, min, max);
  }

  @Override
  protected void updateMessage() {
    setMessage(Component.literal(id + ": " + format.apply(current())));
  }

  @Override
  protected void applyValue() {
    setter.accept(current());
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (!Viewport.contains(e.x(), e.y())) return false;
    return super.mouseClicked(e, doubleClick);
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return Viewport.contains(x, y) && super.isMouseOver(x, y);
  }

  @Override
  public void renderWidget(GuiGraphics g, int mx, int my, float d) {
    var font = Minecraft.getInstance().font;
    int x = getX(), y = getY(), w = width, h = height;
    int accent = Theme.accent();
    boolean hover = isHovered() || KButton.keyboardFocus(this);
    g.fill(x, y, x + w, y + h, hover ? Theme.SURFACE_HOVER : Theme.SURFACE_2);
    int track = y + h / 2;
    g.fill(x + 4, track, x + w - 4, track + 1, Theme.BORDER);
    int knob = x + 4 + (int) Math.round((w - 8) * value);
    g.fill(x + 4, track, knob, track + 1, accent);
    g.fill(knob - 2, y + 3, knob + 2, y + h - 3, hover ? Theme.TEXT : Theme.MUTED);
    g.renderOutline(x, y, w, h, KButton.keyboardFocus(this) ? accent : Theme.BORDER);
    String text = format.apply(current());
    int tw = font.width(text);
    // Value sits on a small plate so it stays readable over the track.
    int tx = x + w - tw - 6;
    g.fill(tx - 2, y + (h - 10) / 2, tx + tw + 2, y + (h - 10) / 2 + 10, Theme.alpha(Theme.SURFACE, 0xE0));
    g.drawString(font, text, tx, y + (h - 8) / 2, Theme.TEXT, false);
  }
}
