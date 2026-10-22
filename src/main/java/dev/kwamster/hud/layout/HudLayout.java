package dev.kwamster.hud.layout;

import dev.kwamster.config.Config.Hud;

public final class HudLayout {
  private HudLayout() {}

  public static int x(Hud h, int viewport, int width) {
    return (int) Math.round(h.x * Math.max(0, viewport - width * h.scale));
  }

  public static int y(Hud h, int viewport, int height) {
    return (int) Math.round(h.y * Math.max(0, viewport - height * h.scale));
  }

  public static void move(Hud h, double x, double y, int vw, int vh, int width, int height) {
    h.x = x / Math.max(1, vw - width * h.scale);
    h.y = y / Math.max(1, vh - height * h.scale);
    h.validate();
  }
}
