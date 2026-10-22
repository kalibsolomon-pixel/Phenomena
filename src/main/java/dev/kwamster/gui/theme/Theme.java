package dev.kwamster.gui.theme;

import dev.kwamster.KwamsterClient;

/** Shared design tokens. Charcoal surfaces, subtle borders, one restrained accent. */
public final class Theme {
  public static final int BACKGROUND = 0xF0111417,
      SURFACE = 0xFF1B2025,
      SURFACE_2 = 0xFF22282D,
      SURFACE_HOVER = 0xFF2C3338,
      SURFACE_PRESSED = 0xFF16191C,
      HEADER = 0xFF15191C,
      BORDER = 0xFF3B434B,
      BORDER_SOFT = 0xFF2C3238,
      BEVEL_LIGHT = 0x22FFFFFF,
      BEVEL_DARK = 0x40000000,
      TEXT = 0xFFE6ECEA,
      MUTED = 0xFF9AA6AC,
      DIM = 0xFF6C767C,
      GRID = 0xFF2A3036,
      WARN = 0xFFE4C46E,
      ERROR = 0xFFE49790,
      OK = 0xFF88C8AA;

  /** Validated dark-surface categorical slots (CVD-safe adjacent order). */
  public static final int SERIES_1 = 0xFF3987E5, SERIES_2 = 0xFFD95926, SERIES_3 = 0xFF199E70;

  /** Sequential ramp end for heat cells; low values blend toward the surface. */
  public static final int SEQ_HIGH = 0xFF86B6EF;

  private Theme() {}

  public static int accent() {
    return KwamsterClient.config == null ? OK : 0xFF000000 | KwamsterClient.config.accent;
  }

  public static boolean compact() {
    return KwamsterClient.config != null
        && KwamsterClient.config.density == dev.kwamster.config.Config.Density.COMPACT;
  }

  public static boolean reducedMotion() {
    return KwamsterClient.config != null && KwamsterClient.config.reducedMotion;
  }

  /** Linear blend between two ARGB colors. */
  public static int mix(int a, int b, double t) {
    t = Math.max(0, Math.min(1, t));
    int r = 0;
    for (int shift = 0; shift <= 24; shift += 8) {
      int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
      r |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
    }
    return r;
  }

  public static int alpha(int color, int alpha) {
    return (color & 0xFFFFFF) | (alpha << 24);
  }
}
