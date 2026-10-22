package dev.kwamster.gui.screens;

import dev.kwamster.KwamsterClient;
import dev.kwamster.config.Config.Hud;
import dev.kwamster.gui.components.ActionButton;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.hud.CombatHud;
import dev.kwamster.hud.layout.HudLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

public final class HudEditorScreen extends Screen {
  private int selected;
  private boolean dragging;
  private double offsetX, offsetY;

  public HudEditorScreen() {
    super(Component.literal("HUD editor"));
  }

  private Hud[] items() {
    var c = KwamsterClient.config;
    return new Hud[] {c.analytics, c.direction, c.alert};
  }

  private String[][] text() {
    return new String[][] {
      CombatHud.lines, new String[] {"Movement / " + CombatHud.direction}, CombatHud.summary()
    };
  }

  @Override
  protected void init() {
    clearWidgets();
    var h = items()[selected];
    int w = Math.max(60, (width - 32) / 4);
    String name = new String[] {"Analytics", "Direction", "Summary"}[selected];
    addRenderableWidget(
        new ActionButton(
            16,
            30,
            w - 3,
            name,
            () -> {
              selected = (selected + 1) % 3;
              init();
            }));
    addRenderableWidget(
        new ActionButton(
            16 + w,
            30,
            w - 3,
            h.visible ? "Visible" : "Hidden",
            () -> {
              h.visible = !h.visible;
              init();
            }));
    addRenderableWidget(
        new ActionButton(
            16 + 2 * w,
            30,
            w - 3,
            "Background: " + (h.background ? "On" : "Off"),
            () -> {
              h.background = !h.background;
              init();
            }));
    addRenderableWidget(
        new ActionButton(
            16 + 3 * w,
            30,
            w - 3,
            h.rightAligned ? "Align right" : "Align left",
            () -> {
              h.rightAligned = !h.rightAligned;
              init();
            }));
    addRenderableWidget(
        new ActionButton(
            16,
            height - 50,
            w - 3,
            "Scale -",
            () -> {
              h.scale -= .1;
              h.validate();
            }));
    addRenderableWidget(
        new ActionButton(
            16 + w,
            height - 50,
            w - 3,
            "Scale +",
            () -> {
              h.scale += .1;
              h.validate();
            }));
    addRenderableWidget(
        new ActionButton(
            16 + 2 * w,
            height - 50,
            w - 3,
            "Opacity",
            () -> {
              h.opacity = h.opacity <= .3f ? 1 : h.opacity - .1f;
            }));
    addRenderableWidget(
        new ActionButton(
            16 + 3 * w,
            height - 50,
            w - 3,
            "Reset selected",
            () -> {
              Hud def = new dev.kwamster.config.Config().analytics;
              if (selected == 1) def = new dev.kwamster.config.Config().direction;
              if (selected == 2) def = new dev.kwamster.config.Config().alert;
              h.x = def.x;
              h.y = def.y;
              h.scale = def.scale;
              h.opacity = def.opacity;
              h.visible = true;
              h.background = true;
              h.rightAligned = false;
              init();
            }));
    addRenderableWidget(new ActionButton(width - 96, height - 26, 80, "Done", this::onClose));
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float d) {
    g.fill(0, 0, width, height, 0xA0111417);
    var h = items();
    var t = text();
    for (int i = 0; i < 3; i++) CombatHud.panel(g, h[i], t[i], KwamsterClient.config.accent, true);
    g.fill(0, 0, width, 56, Theme.BACKGROUND);
    g.fill(0, height - 56, width, height, Theme.BACKGROUND);
    g.drawString(font, "HUD editor / drag panels; arrows move; wheel scales", 16, 12, Theme.TEXT);
    super.render(g, mx, my, d);
    g.drawString(
        font, "Normalized anchors persist across GUI scales", 16, height - 20, Theme.MUTED);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (super.mouseClicked(e, doubleClick)) return true;
    if (e.button() != 0) return true;
    var h = items();
    var t = text();
    for (int i = 2; i >= 0; i--) {
      int w = CombatHud.panelWidth(t[i]), hh = t[i].length * 12 + 14;
      int x = HudLayout.x(h[i], width, w), y = HudLayout.y(h[i], height, hh);
      if (e.x() >= x && e.x() <= x + w * h[i].scale && e.y() >= y && e.y() <= y + hh * h[i].scale) {
        selected = i;
        offsetX = e.x() - x;
        offsetY = e.y() - y;
        dragging = true;
        init();
        return true;
      }
    }
    return true;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (dragging) {
      var h = items()[selected];
      var t = text()[selected];
      HudLayout.move(
          h,
          e.x() - offsetX,
          e.y() - offsetY,
          width,
          height,
          CombatHud.panelWidth(t),
          t.length * 12 + 14);
      return true;
    }
    return super.mouseDragged(e, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    dragging = false;
    return super.mouseReleased(e);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double h, double v) {
    var item = items()[selected];
    item.scale += Math.signum(v) * .05;
    item.validate();
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    var h = items()[selected];
    double delta = .005;
    switch (e.key()) {
      case 263 -> h.x -= delta;
      case 262 -> h.x += delta;
      case 265 -> h.y -= delta;
      case 264 -> h.y += delta;
      default -> {
        return super.keyPressed(e);
      }
    }
    h.validate();
    return true;
  }

  @Override
  public void onClose() {
    KwamsterClient.saveConfig();
    minecraft.setScreen(new KwamsterScreen());
  }

  @Override
  public void removed() {
    KwamsterClient.saveConfig();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
