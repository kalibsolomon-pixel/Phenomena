package dev.phenomena.gui.components;

import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
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
    float x = getX(), y = getY(), w = width, h = height;
    int accent = Theme.accent();
    boolean focus = KButton.keyboardFocus(this);
    boolean hover = isHovered() || focus;
    if (hover && hoverSince == 0L) hoverSince = System.nanoTime();
    if (!hover) hoverSince = 0L;
    float hv = Theme.ease(hoverSince);
    Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL,
        Theme.mix(Theme.SURFACE_2, Theme.SURFACE_HOVER, hv));
    float trackY = y + h / 2f;
    float trackL = x + 5, trackR = x + w - 5;
    float knob = trackL + (trackR - trackL) * (float) value;
    Pixel.line(g, trackL, trackY, trackR, trackY, 2, Theme.BORDER);
    Pixel.line(g, trackL, trackY, knob, trackY, 2, accent);
    float kr = 3.2f;
    Pixel.circle(g, knob, trackY, kr, hover ? Theme.TEXT : Theme.MUTED);
    Pixel.outline(g, x, y, w, h, Theme.RADIUS_SMALL, 1, Theme.BORDER_SOFT);
    if (focus) KButton.focusRing(g, x, y, w, h);
    String text = format.apply(current());
    int tw = font.width(text);
    // Value sits on a small plate so it stays readable over the track.
    float tx = x + w - tw - 6;
    Pixel.rounded(g, tx - 3, y + (h - 11) / 2f, tw + 6, 11, Theme.RADIUS_SMALL,
        Theme.alpha(Theme.SURFACE, 0xE6));
    g.drawString(font, text, Math.round(tx), Math.round(y + (h - 8) / 2f), Theme.TEXT, false);
  }

  private long hoverSince;
}
