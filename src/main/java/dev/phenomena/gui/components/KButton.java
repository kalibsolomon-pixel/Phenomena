package dev.phenomena.gui.components;

import dev.phenomena.gui.theme.Theme;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Compact vanilla-backed button (focus, keyboard activation and narration come from Button) with
 * a flat charcoal face and a one-pixel Minecraft-style bevel. Labels may be dynamic.
 */
public class KButton extends Button {
  public enum Style {
    NORMAL,
    PRIMARY,
    SUBTLE,
    TAB,
    CHIP,
    DANGER
  }

  public final String id;
  private final Style style;
  private Supplier<String> label;
  private BooleanSupplier selected = () -> false;
  private boolean content;
  private String shown = "";

  public KButton(String id, Style style, Supplier<String> label, Runnable action) {
    super(0, 0, 60, 20, Component.literal(label.get()), b -> action.run(), DEFAULT_NARRATION);
    this.id = id;
    this.style = style;
    this.label = label;
  }

  public KButton(String label, Runnable action) {
    this(label, Style.NORMAL, () -> label, action);
  }

  public KButton(String label, Style style, Runnable action) {
    this(label, style, () -> label, action);
  }

  public KButton selected(BooleanSupplier s) {
    selected = s;
    return this;
  }

  public KButton tooltip(String text) {
    setTooltip(Tooltip.create(Component.literal(text)));
    return this;
  }

  public KButton enabled(boolean on) {
    active = on;
    return this;
  }

  /** Marks the button as scroll content: pointer input is limited to the viewport. */
  public KButton content() {
    content = true;
    return this;
  }

  public boolean isContent() {
    return content;
  }

  public void setLabel(Supplier<String> label) {
    this.label = label;
  }

  public KButton size(int w, int h) {
    setWidth(w);
    setHeight(h);
    return this;
  }

  public boolean isSelected() {
    return selected.getAsBoolean();
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (content && !Viewport.contains(e.x(), e.y())) return false;
    return super.mouseClicked(e, doubleClick);
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return (!content || Viewport.contains(x, y)) && super.isMouseOver(x, y);
  }

  @Override
  protected void renderContents(GuiGraphics g, int mx, int my, float delta) {
    String text = label.get();
    if (!text.equals(shown)) {
      shown = text;
      setMessage(Component.literal(text));
    }
    var font = Minecraft.getInstance().font;
    boolean sel = isSelected();
    boolean ring = keyboardFocus(this);
    boolean hover = active && (isHovered() || ring);
    boolean pressed =
        active && isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed();
    int x = getX(), y = getY(), w = width, h = height;
    int accent = Theme.accent();
    int text0 = active ? Theme.TEXT : Theme.DIM;
    switch (style) {
      case TAB -> {
        if (sel) g.fill(x, y, x + w, y + h, Theme.SURFACE);
        else if (hover) g.fill(x, y, x + w, y + h, Theme.SURFACE_2);
        if (sel) g.fill(x, y + h - 2, x + w, y + h, accent);
        text0 = sel ? Theme.TEXT : hover ? Theme.TEXT : Theme.MUTED;
      }
      case CHIP -> {
        int face = sel ? Theme.SURFACE_HOVER : hover ? Theme.SURFACE_2 : Theme.SURFACE;
        g.fill(x, y, x + w, y + h, face);
        g.renderOutline(x, y, w, h, sel ? Theme.alpha(accent, 0xB0) : Theme.BORDER_SOFT);
        text0 = sel ? Theme.TEXT : active ? Theme.MUTED : Theme.DIM;
        if (sel) g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, accent);
      }
      case SUBTLE -> {
        if (hover) g.fill(x, y, x + w, y + h, Theme.SURFACE_2);
        if (hover) g.renderOutline(x, y, w, h, Theme.BORDER_SOFT);
        text0 = active ? (hover ? Theme.TEXT : Theme.MUTED) : Theme.DIM;
      }
      default -> {
        int face =
            pressed
                ? Theme.SURFACE_PRESSED
                : hover
                    ? Theme.SURFACE_HOVER
                    : style == Style.PRIMARY ? Theme.mix(Theme.SURFACE_2, accent, .16) : Theme.SURFACE_2;
        g.fill(x, y, x + w, y + h, face);
        if (!pressed) {
          g.fill(x + 1, y + 1, x + w - 1, y + 2, Theme.BEVEL_LIGHT);
          g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, Theme.BEVEL_DARK);
        }
        int border =
            style == Style.DANGER && active
                ? Theme.alpha(Theme.ERROR, 0x90)
                : style == Style.PRIMARY && active ? Theme.alpha(accent, 0x90) : Theme.BORDER;
        g.renderOutline(x, y, w, h, ring ? accent : border);
        if (style == Style.DANGER && active) text0 = Theme.ERROR;
      }
    }
    String fitted = fit(font, text, w - 8);
    g.drawString(
        font,
        fitted,
        x + (w - font.width(fitted)) / 2,
        y + (h - 8) / 2 + (pressed ? 1 : 0),
        text0,
        false);
    if (ring && (style == Style.TAB || style == Style.CHIP || style == Style.SUBTLE))
      g.renderOutline(x, y, w, h, Theme.alpha(accent, 0xC0));
  }

  /** Focus rings follow keyboard navigation only, like vanilla; mouse clicks do not leave one. */
  public static boolean keyboardFocus(net.minecraft.client.gui.components.AbstractWidget w) {
    return w.isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
  }

  public static String fit(net.minecraft.client.gui.Font font, String s, int width) {
    if (font.width(s) <= width) return s;
    String dots = "...";
    return font.plainSubstrByWidth(s, Math.max(0, width - font.width(dots))) + dots;
  }
}
