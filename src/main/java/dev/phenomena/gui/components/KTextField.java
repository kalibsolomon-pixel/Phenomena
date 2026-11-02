package dev.phenomena.gui.components;

import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Vanilla text field restricted to the viewport and drawn with the dashboard's flat style. The
 * widget rectangle is the text line; the drawn frame (and the click area) extends {@link #PAD}
 * pixels around it.
 */
public final class KTextField extends EditBox {
  public static final int PAD = 4;
  public final String id;

  public KTextField(String id, String value, int maxLength) {
    super(Minecraft.getInstance().font, 0, 0, 100, 8, Component.literal(id));
    this.id = id;
    setMaxLength(maxLength);
    setValue(value);
    setBordered(false);
    setTextColor(Theme.TEXT);
    setTextShadow(false);
  }

  private boolean inFrame(double x, double y) {
    return x >= getX() - PAD
        && x < getX() + width + PAD
        && y >= getY() - PAD
        && y < getY() + height + PAD;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (!Viewport.contains(e.x(), e.y()) || !isActive() || !inFrame(e.x(), e.y())) return false;
    double cx = Math.max(getX(), Math.min(getX() + width - 1, e.x()));
    double cy = Math.max(getY(), Math.min(getY() + height - 1, e.y()));
    return super.mouseClicked(new MouseButtonEvent(cx, cy, e.buttonInfo()), doubleClick);
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return Viewport.contains(x, y) && isActive() && inFrame(x, y);
  }

  @Override
  public void renderWidget(GuiGraphics g, int mx, int my, float d) {
    float x = getX() - PAD, y = getY() - PAD, w = width + 2 * PAD, h = height + 2 * PAD;
    Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, Theme.INSET);
    Pixel.outline(g, x, y, w, h, Theme.RADIUS_SMALL, 1,
        isFocused() ? Theme.accent() : Theme.BORDER_SOFT);
    super.renderWidget(g, mx, my, d);
  }
}
