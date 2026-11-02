package dev.phenomena.gui.components;

import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Toggle switch for boolean settings. Press (mouse, Enter or Space) flips the backing value;
 * narration and keyboard activation come from Button. The knob slides through a short eased
 * transition, snapping instantly under reduced motion.
 */
public final class KToggle extends Button {
  public final String id;
  private final BooleanSupplier get;
  private final Runnable toggle;
  private long changedAt = Long.MIN_VALUE;
  private boolean lastOn;

  public KToggle(String id, BooleanSupplier get, Runnable toggle) {
    super(0, 0, 22, 12, Component.literal(id), b -> toggle.run(), DEFAULT_NARRATION);
    this.id = id;
    this.get = get;
    this.toggle = toggle;
    lastOn = get.getAsBoolean();
  }

  public static final int W = 22, H = 12;

  /** Marks the switch as scroll content: pointer input is limited to the viewport. */
  public KToggle content() {
    return this;
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
  protected void renderContents(GuiGraphics g, int mx, int my, float delta) {
    boolean on = get.getAsBoolean();
    if (on != lastOn) {
      lastOn = on;
      changedAt = System.nanoTime();
    }
    float t = changedAt == Long.MIN_VALUE ? (on ? 1f : 0f) : Theme.ease(changedAt);
    if (t >= 1f) t = 1f;
    float progress = on ? t : 1f - t;
    float x = getX(), y = getY(), w = width, h = height;
    boolean focus = KButton.keyboardFocus(this);
    boolean hover = active && (isHovered() || focus);
    int accent = Theme.accent();
    int trackOff = Theme.mix(Theme.INSET, Theme.SURFACE_HOVER, hover ? 0.6 : 0);
    int face = Pixel.mix(trackOff, accent, progress);
    Pixel.rounded(g, x, y, w, h, h / 2f, face);
    Pixel.outline(g, x, y, w, h, h / 2f, 1, on ? Theme.alpha(accent, 0xB0) : Theme.BORDER_SOFT);
    // Knob: from 0.27 to 0.73 of the track width, staying circular at every scale.
    float inset = 2f;
    float travel = w - h + inset; // knob diameter is h - 2*inset/... keep simple: d = h - inset
    float d = h - inset;
    float kx = x + inset / 2f + (w - d - inset) * progress + d / 2f;
    Pixel.circle(g, kx, y + h / 2f, d / 2f, 0xFFF2F6F4);
    if (focus) KButton.focusRing(g, x, y, w, h);
    if (Minecraft.getInstance().mouseHandler.isLeftPressed() && hover) {
      // Pressed feedback: the knob shrinks slightly.
      Pixel.circle(g, kx, y + h / 2f, d / 2f - 0.5f, Pixel.mix(0xFFF2F6F4, face, 0.25f));
    }
  }
}
