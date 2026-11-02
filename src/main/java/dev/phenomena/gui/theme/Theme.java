package dev.phenomena.gui.theme;

import dev.phenomena.PhenomenaClient;

/**
 * Spxctra design tokens: one restrained near-neutral surface ladder, a single working accent,
 * semantic status colors and the validated CVD-safe chart series. Named by role, not value.
 * Geometry (radius, focus, motion) lives here too so every component shares one vocabulary.
 */
public final class Theme {
  // ---- surfaces (calm charcoal ladder, one step per hierarchy level) ----
  public static final int BACKGROUND = 0xF0101316,
      SURFACE = 0xFF171C21,
      SURFACE_2 = 0xFF1D2329,
      SURFACE_HOVER = 0xFF262D34,
      SURFACE_PRESSED = 0xFF12161A,
      INSET = 0xFF12161A,
      HEADER = 0xFF141920,
      BORDER = 0xFF39424B,
      BORDER_SOFT = 0xFF2A3138,
      BEVEL_LIGHT = 0x14FFFFFF,
      BEVEL_DARK = 0x26000000,
      TEXT = 0xFFE8EDEB,
      MUTED = 0xFF9BA7AD,
      DIM = 0xFF7A848B,
      GRID = 0xFF252C33,
      WARN = 0xFFE4C46E,
      ERROR = 0xFFE49790,
      OK = 0xFF88C8AA;

  /** Validated dark-surface categorical slots (CVD-safe adjacent order). */
  public static final int SERIES_1 = 0xFF3987E5, SERIES_2 = 0xFFD95926, SERIES_3 = 0xFF199E70;

  /** Sequential ramp end for heat cells; low values blend toward the surface. */
  public static final int SEQ_HIGH = 0xFF86B6EF;

  // ---- geometry vocabulary (logical px) ----
  public static final float RADIUS = 4f;
  public static final float RADIUS_SMALL = 3f;
  /** Focus ring sits one gap outside the control edge so it stays visible on selected peers. */
  public static final float FOCUS_GAP = 1f;

  private Theme() {}

  public static int accent() {
    return PhenomenaClient.config == null ? OK : 0xFF000000 | PhenomenaClient.config.accent;
  }

  /** Accent lifted toward white for hover feedback on accent-filled controls. */
  public static int accentHover() {
    return mix(accent(), 0xFFFFFFFF, 0.15);
  }

  /** Accent deepened for pressed feedback on accent-filled controls. */
  public static int accentPressed() {
    return mix(accent(), 0xFF000000, 0.25);
  }

  public static boolean compact() {
    return PhenomenaClient.config != null
        && PhenomenaClient.config.density == dev.phenomena.config.Config.Density.COMPACT;
  }

  public static boolean reducedMotion() {
    return PhenomenaClient.config != null && PhenomenaClient.config.reducedMotion;
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

  /**
   * Eased hover progress for interaction transitions: a short, quiet fade of the widget's own
   * start timestamp ({@code 0} = never started, negative = already complete). Reduced motion
   * snaps to the end state — behavior stays correct with animation disabled.
   */
  public static float ease(long startedNanos) {
    if (reducedMotion()) return 1f;
    if (startedNanos == 0L) return 0f;
    if (startedNanos < 0L) return 1f;
    float t = Math.min(1f, (System.nanoTime() - startedNanos) / 120_000_000f);
    return t * t * (3 - 2 * t);
  }
}
