package dev.phenomena.gui.components;

import dev.phenomena.gui.pixel.Pixel;
import dev.phenomena.gui.theme.Theme;
import dev.phenomena.gui.ui.Layout.Rect;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * An overlay that receives all input while open: dropdown option lists and modal confirmation
 * dialogs. Rendered on its own stratum above the page. Escape always dismisses without acting.
 */
public abstract class Popup {
  protected Rect rect;

  public abstract void render(GuiGraphics g, int mx, int my, int screenW, int screenH);

  /** Returns false when the popup should close. */
  public abstract boolean click(double x, double y);

  /** Returns false when the popup should close. */
  public abstract boolean key(int key);

  public void scroll(double amount) {}

  public String narration() {
    return "";
  }

  // ---- dropdown ------------------------------------------------------------

  public static final class Dropdown extends Popup {
    public static final int ROW = 13;
    private final List<String> options;
    private final IntConsumer pick;
    private int highlighted, offset;
    private final int visible;

    public Dropdown(Rect anchor, List<String> options, int current, IntConsumer pick, int screenW, int screenH) {
      this.options = options;
      this.pick = pick;
      var font = Minecraft.getInstance().font;
      int w = anchor.w();
      for (var o : options) w = Math.max(w, font.width(o) + 16);
      w = Math.min(w, screenW - 8);
      int maxRows = Math.max(3, (screenH - 16) / ROW);
      int below = (screenH - anchor.bottom() - 6) / ROW, above = (anchor.y() - 6) / ROW;
      boolean down = below >= Math.min(options.size(), 4) || below >= above;
      visible = Math.min(options.size(), Math.min(maxRows, Math.max(3, down ? below : above)));
      int h = visible * ROW + 2;
      int x = Math.max(4, Math.min(anchor.x(), screenW - w - 4));
      int y = down ? anchor.bottom() + 1 : anchor.y() - h - 1;
      y = Math.max(4, Math.min(y, screenH - h - 4));
      rect = new Rect(x, y, w, h);
      highlighted = Math.max(0, current);
      offset = Math.max(0, Math.min(options.size() - visible, highlighted - visible / 2));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, int sw, int sh) {
      var font = Minecraft.getInstance().font;
      Pixel.fillLogical(g, rect.x(), rect.y(), rect.right(), rect.bottom(), Theme.SURFACE);
      Pixel.outline(g, rect.x(), rect.y(), rect.w(), rect.h(), 2, 1, Theme.BORDER);
      for (int i = 0; i < visible; i++) {
        int idx = offset + i;
        int ry = rect.y() + 1 + i * ROW;
        boolean hover = mx >= rect.x() && mx < rect.right() && my >= ry && my < ry + ROW;
        if (hover) highlighted = idx;
        if (idx == highlighted) {
          Pixel.fillLogical(g, rect.x() + 1, ry, rect.right() - 1, ry + ROW, Theme.SURFACE_HOVER);
          Pixel.fillLogical(g, rect.x() + 1, ry, rect.x() + 3, ry + ROW, Theme.accent());
        }
        g.drawString(
            font,
            KButton.fit(font, options.get(idx), rect.w() - 12),
            rect.x() + 7,
            ry + 3,
            idx == highlighted ? Theme.TEXT : Theme.MUTED,
            false);
      }
      if (options.size() > visible) {
        int th = Math.max(6, rect.h() * visible / options.size());
        int ty = rect.y() + (rect.h() - th) * offset / Math.max(1, options.size() - visible);
        Pixel.fillLogical(g, rect.right() - 3, ty, rect.right() - 1, ty + th, Theme.BORDER);
      }
    }

    @Override
    public boolean click(double x, double y) {
      if (!rect.contains(x, y)) return false;
      int i = offset + (int) ((y - rect.y() - 1) / ROW);
      if (i >= 0 && i < options.size()) {
        pick.accept(i);
        return false;
      }
      return true;
    }

    @Override
    public boolean key(int key) {
      switch (key) {
        case GLFW.GLFW_KEY_DOWN -> highlighted = Math.min(options.size() - 1, highlighted + 1);
        case GLFW.GLFW_KEY_UP -> highlighted = Math.max(0, highlighted - 1);
        case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
          pick.accept(highlighted);
          return false;
        }
        case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_TAB -> {
          return false;
        }
        default -> {}
      }
      if (highlighted < offset) offset = highlighted;
      if (highlighted >= offset + visible) offset = highlighted - visible + 1;
      return true;
    }

    @Override
    public void scroll(double amount) {
      offset = Math.max(0, Math.min(options.size() - visible, offset - (int) Math.signum(amount)));
    }

    @Override
    public String narration() {
      return options.get(highlighted);
    }
  }

  // ---- confirmation --------------------------------------------------------

  public static final class Confirm extends Popup {
    private final String title, message, action;
    private final Runnable confirm;
    private int focus; // 0 cancel (default), 1 confirm
    private Rect cancelRect, confirmRect;

    public Confirm(String title, String message, String action, Runnable confirm) {
      this.title = title;
      this.message = message;
      this.action = action;
      this.confirm = confirm;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, int sw, int sh) {
      var font = Minecraft.getInstance().font;
      Pixel.fillLogical(g, 0, 0, sw, sh, 0xA0000000);
      int w = Math.min(280, sw - 24);
      var lines = font.split(Component.literal(message), w - 20);
      int h = 26 + lines.size() * 10 + 30;
      rect = new Rect((sw - w) / 2, (sh - h) / 2, w, h);
      Pixel.fillLogical(g, rect.x(), rect.y(), rect.right(), rect.bottom(), Theme.SURFACE);
      Pixel.fillLogical(g, rect.x(), rect.y(), rect.right(), rect.y() + 1, Theme.BEVEL_LIGHT);
      Pixel.outline(g, rect.x(), rect.y(), rect.w(), rect.h(), 2, 1, Theme.BORDER);
      g.drawString(font, title, rect.x() + 10, rect.y() + 9, Theme.TEXT, false);
      for (int i = 0; i < lines.size(); i++)
        g.drawString(font, lines.get(i), rect.x() + 10, rect.y() + 24 + i * 10, Theme.MUTED, false);
      int bw = Math.min(90, (w - 30) / 2), by = rect.bottom() - 24;
      cancelRect = new Rect(rect.right() - 10 - 2 * bw - 6, by, bw, 16);
      confirmRect = new Rect(rect.right() - 10 - bw, by, bw, 16);
      button(g, cancelRect, "Cancel", focus == 0, false, mx, my);
      button(g, confirmRect, action, focus == 1, true, mx, my);
    }

    private void button(GuiGraphics g, Rect r, String text, boolean focused, boolean danger, int mx, int my) {
      var font = Minecraft.getInstance().font;
      boolean hover = r.contains(mx, my);
      Pixel.fillLogical(g, r.x(), r.y(), r.right(), r.bottom(), hover ? Theme.SURFACE_HOVER : Theme.SURFACE_2);
      Pixel.fillLogical(g, r.x() + 1, r.y() + 1, r.right() - 1, r.y() + 2, Theme.BEVEL_LIGHT);
      g.renderOutline(
          r.x(), r.y(), r.w(), r.h(), focused ? Theme.accent() : danger ? Theme.alpha(Theme.ERROR, 0x90) : Theme.BORDER);
      String t = KButton.fit(font, text, r.w() - 6);
      g.drawString(font, t, r.x() + (r.w() - font.width(t)) / 2, r.y() + 4, danger ? Theme.ERROR : Theme.TEXT, false);
    }

    @Override
    public boolean click(double x, double y) {
      if (confirmRect != null && confirmRect.contains(x, y)) {
        confirm.run();
        return false;
      }
      if (cancelRect != null && cancelRect.contains(x, y)) return false;
      return true; // modal: clicks elsewhere are ignored
    }

    @Override
    public boolean key(int key) {
      switch (key) {
        case GLFW.GLFW_KEY_ESCAPE -> {
          return false;
        }
        case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_TAB -> focus = 1 - focus;
        case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
          if (focus == 1) confirm.run();
          return false;
        }
        default -> {}
      }
      return true;
    }

    @Override
    public String narration() {
      return title + ". " + message + (focus == 1 ? ". " + action : ". Cancel");
    }
  }
}
