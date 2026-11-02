package dev.phenomena.gui.components;

import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Vanilla-backed button (focus, keyboard activation and narration come from Button) painted
 * with the per-pixel renderer: rounded faces, short hover/press transitions, and a focus ring
 * drawn one gap outside the control so it stays visible on selected peers. Labels may be
 * dynamic.
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
  private long hoverSince;

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
    boolean hovered = active && (isHovered() || ring);
    boolean pressed = active && isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed();
    if (hovered && hoverSince == 0L) hoverSince = System.nanoTime();
    if (!hovered) hoverSince = 0L;
    float hover = Theme.ease(hoverSince);
    float x = getX(), y = getY(), w = width, h = height;
    int accent = Theme.accent();
    int text0 = active ? Theme.TEXT : Theme.DIM;
    switch (style) {
      case TAB -> {
        // Flat at rest; a quiet face appears on hover. The screen draws the active-tab accent
        // edge so it can animate between tabs — no static underline here.
        if (sel)
          Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, Theme.alpha(Theme.SURFACE_HOVER, 0x70));
        else if (hover > 0)
          Pixel.rounded(
              g, x, y, w, h, Theme.RADIUS_SMALL, Theme.alpha(Theme.SURFACE_HOVER, (int) (0x50 * hover)));
        text0 = sel ? Theme.TEXT : hover > 0 ? Theme.MUTED : Theme.DIM;
      }
      case CHIP -> {
        int face =
            sel ? Theme.mix(Theme.SURFACE_2, accent, 0.12)
                : Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover);
        Pixel.rounded(g, x, y, w, h, h / 2f, face);
        Pixel.outline(
            g, x, y, w, h, h / 2f, 1,
            sel ? Theme.alpha(accent, 0xB0) : Theme.alpha(Theme.BORDER_SOFT, 0xB0));
        text0 = sel ? Theme.TEXT : active ? Theme.MUTED : Theme.DIM;
      }
      case SUBTLE -> {
        if (hover > 0)
          Pixel.rounded(
              g, x, y, w, h, Theme.RADIUS_SMALL, Theme.alpha(Theme.SURFACE_2, (int) (255 * hover)));
        text0 = active ? (hover > 0 ? Theme.TEXT : Theme.MUTED) : Theme.DIM;
      }
      case PRIMARY -> {
        if (!active) {
          Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE);
          Pixel.outline(g, x, y, w, h, Theme.RADIUS_SMALL, 1, Theme.BORDER_SOFT);
          text0 = Theme.DIM;
        } else {
          int face =
              pressed ? Theme.accentPressed()
                  : hover > 0 ? Theme.mix(accent, Theme.accentHover(), hover) : accent;
          Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, face);
          text0 = onAccent(face);
        }
      }
      case DANGER -> {
        // Destructive: neutral face with a red label; commits to solid red on press.
        int face = pressed ? Theme.SURFACE_PRESSED : Theme.mix(Theme.SURFACE_2, Theme.SURFACE_HOVER, hover);
        Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, face);
        Pixel.outline(g, x, y, w, h, Theme.RADIUS_SMALL, 1, Theme.BORDER_SOFT);
        if (active && pressed) {
          Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, Theme.ERROR);
          text0 = 0xFF1A100E;
        } else text0 = active ? Theme.ERROR : Theme.DIM;
      }
      default -> {
        int face =
            pressed ? Theme.SURFACE_PRESSED : Theme.mix(Theme.SURFACE_2, Theme.SURFACE_HOVER, hover);
        Pixel.rounded(g, x, y, w, h, Theme.RADIUS_SMALL, face);
        Pixel.outline(g, x, y, w, h, Theme.RADIUS_SMALL, 1, Theme.BORDER_SOFT);
        if (!pressed) Pixel.fillLogical(g, x + 2, y + 1.5f, x + w - 2, y + 2, Theme.BEVEL_LIGHT);
      }
    }
    if (ring) focusRing(g, x, y, w, h);
    String fitted = fit(font, text, (int) (w - 8));
    int fx = Math.round(x + (w - font.width(fitted)) / 2f);
    int fy = Math.round(y + (h - 8) / 2f + (pressed ? 1 : 0));
    g.drawString(font, fitted, fx, fy, text0, false);
  }

  /** Keyboard focus ring, one gap outside the face so it reads on selected/hovered peers. */
  public static void focusRing(GuiGraphics g, float x, float y, float w, float h) {
    Pixel.outline(
        g,
        x - Theme.FOCUS_GAP,
        y - Theme.FOCUS_GAP,
        w + 2 * Theme.FOCUS_GAP,
        h + 2 * Theme.FOCUS_GAP,
        Theme.RADIUS_SMALL + Theme.FOCUS_GAP,
        1,
        Theme.alpha(Theme.accent(), 0xD0));
  }

  /** Readable label color on an accent-colored fill. */
  public static int onAccent(int argb) {
    int r = (argb >>> 16) & 0xFF, gg = (argb >>> 8) & 0xFF, b = argb & 0xFF;
    double luminance = 0.2126 * r + 0.7152 * gg + 0.0722 * b;
    return luminance > 140 ? 0xFF101418 : 0xFFF2F6F4;
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
