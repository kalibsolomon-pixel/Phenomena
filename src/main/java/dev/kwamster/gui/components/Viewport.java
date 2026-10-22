package dev.kwamster.gui.components;

import dev.kwamster.gui.ui.Layout.Rect;

/**
 * The visible scroll area of the open dashboard. Content widgets reject pointer input outside it,
 * so controls scrolled under the header or footer are never clickable invisibly.
 */
public final class Viewport {
  private static Rect current;

  private Viewport() {}

  public static void set(Rect r) {
    current = r;
  }

  public static boolean contains(double x, double y) {
    return current == null || current.contains(x, y);
  }

  public static Rect get() {
    return current;
  }
}
