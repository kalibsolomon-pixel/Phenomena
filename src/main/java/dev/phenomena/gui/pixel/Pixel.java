package dev.phenomena.gui.pixel;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Phenomena' per-pixel renderer. Shapes are rasterized analytically in <b>physical device
 * pixels</b> — logical GUI coordinates are scaled by the window's GUI scale first — and each
 * edge pixel receives its exact sub-pixel coverage folded into the alpha channel. The
 * resulting integer rectangles are submitted as ordinary batched {@link GuiGraphics#fill}
 * calls under a {@code 1/guiScale} pose, so they land on exact device pixels through
 * Minecraft's normal GUI pipeline: no shaders, no textures, no OpenGL state changes, and full
 * compatibility with Sodium/ImmediatelyFast and every GUI scale, fractional ones included.
 *
 * <p>Adapted from Aurora Client's proven RenderUtil coverage rasterizer (design reference),
 * re-implemented for Phenomena' smaller primitive set.
 */
public final class Pixel {
  /** One destination row/rect in physical pixel space. */
  @FunctionalInterface
  public interface Sink {
    void rect(int x1, int y1, int x2, int y2, int argb);
  }

  private static final AtomicLong FILLS = new AtomicLong();

  private Pixel() {}

  /** Current GUI scale as float (fractional scales stay sharp: coverage is computed in device px). */
  public static float scale() {
    var window = Minecraft.getInstance().getWindow();
    return (float) window.getGuiScale();
  }

  /** Number of fill submissions issued through this class (diagnostics/perf instrumentation). */
  public static long fillsSubmitted() {
    return FILLS.get();
  }

  /** Logical-space fill for hard integer grids (heat cells, chart bars); device-exact. */
  public static void fillLogical(GuiGraphics g, float x1, float y1, float x2, float y2, int argb) {
    float s = scale();
    g.fill(
        Math.round(x1 * s),
        Math.round(y1 * s),
        Math.round(x2 * s),
        Math.round(y2 * s),
        argb);
    FILLS.incrementAndGet();
  }

  /** Antialiased filled rounded rectangle in logical coordinates. Canonical surface shape. */
  public static void rounded(
      GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
    if (w <= 0 || h <= 0) return;
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    roundedRows(
        sink(g),
        x * s,
        y * s,
        w * s,
        h * s,
        Math.min(radius * s, Math.min(w * s, h * s) / 2f),
        color);
    g.pose().popMatrix();
  }

  /** Antialiased rounded outline; {@code thickness} in logical pixels, min one device pixel. */
  public static void outline(
      GuiGraphics g, float x, float y, float w, float h, float radius, float thickness, int color) {
    if (w <= 0 || h <= 0) return;
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    float px = x * s, py = y * s, pw = w * s, ph = h * s;
    float pr = Math.min(radius * s, Math.min(pw, ph) / 2f);
    float t = Math.max(1f, thickness * s);
    Sink sink = sink(g);
    if (pr <= 0) {
      rect(sink, px, py, px + pw, py + t, color);
      rect(sink, px, py + ph - t, px + pw, py + ph, color);
      rect(sink, px, py + t, px + t, py + ph - t, color);
      rect(sink, px + pw - t, py + t, px + pw, py + ph - t, color);
      g.pose().popMatrix();
      return;
    }
    float yTop = py + pr, yBot = py + ph - pr;
    float innerR = pr - t;
    int rows = (int) Math.ceil(ph);
    // Top corners: rows inside the stroke cap span the full width; deeper rows are bands.
    for (int i = 0; i < (int) Math.ceil(pr); i++) {
      float rowY = py + i;
      float dy = yTop - (rowY + 0.5f);
      float dxO = (dy > 0 && dy < pr) ? (float) Math.sqrt(pr * pr - dy * dy) : pr;
      float dxI = (dy >= 0 && dy < innerR) ? (float) Math.sqrt(innerR * innerR - dy * dy) : 0;
      if (rowY < py + t) row(sink, px + pr - dxO, rowY, px + pw - pr + dxO, color);
      else {
        row(sink, px + pr - dxO, rowY, px + pr - dxI, color);
        row(sink, px + pw - pr + dxI, rowY, px + pw - pr + dxO, color);
      }
    }
    if (yBot > yTop) {
      rect(sink, px, yTop, px + t, yBot, color);
      rect(sink, px + pw - t, yTop, px + pw, yBot, color);
    }
    int bottomStart = Math.max((int) Math.ceil(pr), (int) Math.floor(ph - pr));
    for (int i = bottomStart; i < rows; i++) {
      float rowY = py + i;
      float dy = (rowY + 0.5f) - yBot;
      float dxO = (dy > 0 && dy < pr) ? (float) Math.sqrt(pr * pr - dy * dy) : pr;
      float dxI = (dy >= 0 && dy < innerR) ? (float) Math.sqrt(innerR * innerR - dy * dy) : 0;
      if (rowY >= py + ph - t) row(sink, px + pr - dxO, rowY, px + pw - pr + dxO, color);
      else {
        row(sink, px + pr - dxO, rowY, px + pr - dxI, color);
        row(sink, px + pw - pr + dxI, rowY, px + pw - pr + dxO, color);
      }
    }
    g.pose().popMatrix();
  }

  /** Antialiased filled circle — toggle knobs, chart points. Stays perfectly round. */
  public static void circle(GuiGraphics g, float cx, float cy, float radius, int color) {
    if (radius <= 0) return;
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    Sink sink = sink(g);
    float pcx = cx * s, pcy = cy * s, pr = radius * s;
    int start = (int) Math.floor(pcy - pr), end = (int) Math.ceil(pcy + pr);
    for (int y = start; y < end; y++) {
      float dy = (y + 0.5f) - pcy;
      if (Math.abs(dy) >= pr) continue;
      float dx = (float) Math.sqrt(pr * pr - dy * dy);
      row(sink, pcx - dx, y, pcx + dx, color);
    }
    g.pose().popMatrix();
  }

  /** Antialiased line of logical thickness; horizontal, vertical or diagonal. */
  public static void line(
      GuiGraphics g, float x1, float y1, float x2, float y2, float thickness, int color) {
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    Sink sink = sink(g);
    float t = Math.max(1f, thickness * s);
    if (y1 == y2) {
      float y = y1 * s - t / 2;
      row(sink, Math.min(x1, x2) * s, y, Math.max(x1, x2) * s, color);
    } else if (x1 == x2) {
      rect(sink, x1 * s - t / 2, Math.min(y1, y2) * s, x1 * s + t / 2, Math.max(y1, y2) * s, color);
    } else {
      diagonal(sink, x1 * s, y1 * s, x2 * s, y2 * s, t, color);
    }
    g.pose().popMatrix();
  }

  /** Arbitrary-angle AA line for chart polylines: a stroke quad rasterized row by row. */
  private static void diagonal(Sink sink, float ax, float ay, float bx, float by, float t, int color) {
    float dx = bx - ax, dy = by - ay;
    float len = (float) Math.sqrt(dx * dx + dy * dy);
    if (len < 1e-3f) return;
    float nx = -dy / len * t / 2, ny = dx / len * t / 2;
    float[] xs = {ax - nx, ax + nx, bx + nx, bx - nx};
    float[] ys = {ay - ny, ay + ny, by + ny, by - ny};
    float minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
    float maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
    float minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
    float maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
    for (int y = (int) Math.floor(minY); y < (int) Math.ceil(maxY); y++) {
      float top = Math.max(y, minY), bottom = Math.min(y + 1, maxY);
      if (bottom <= top) continue;
      float mid = (top + bottom) * 0.5f;
      float l = Float.POSITIVE_INFINITY, r = Float.NEGATIVE_INFINITY;
      for (int i = 0; i < 4; i++) {
        int j = (i + 1) % 4;
        float ex = edgeX(xs[i], ys[i], xs[j], ys[j], mid);
        if (Float.isFinite(ex)) {
          l = Math.min(l, ex);
          r = Math.max(r, ex);
        }
      }
      if (r > l) row(sink, l, y, r, color);
    }
  }

  private static float edgeX(float ax, float ay, float bx, float by, float y) {
    return ay != by && y >= Math.min(ay, by) && y < Math.max(ay, by)
        ? ax + (bx - ax) * (y - ay) / (by - ay)
        : Float.NaN;
  }

  /** Smooth vertical gradient between two colors, one blended fill per device row. */
  public static void gradientV(
      GuiGraphics g, float x, float y, float w, float h, int top, int bottom) {
    if (w <= 0 || h <= 0) return;
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    Sink sink = sink(g);
    float px = x * s, py = y * s, pw = w * s, ph = h * s;
    int rows = Math.max(1, (int) Math.ceil(ph));
    for (int i = 0; i < rows; i++) {
      float t = ph <= 1 ? 0.5f : (i + 0.5f) / ph;
      row(sink, px, py + i, px + pw, mix(top, bottom, t));
    }
    g.pose().popMatrix();
  }

  /** Smooth horizontal gradient between two colors. */
  public static void gradientH(
      GuiGraphics g, float x, float y, float w, float h, int left, int right) {
    if (w <= 0 || h <= 0) return;
    float s = scale();
    g.pose().pushMatrix();
    g.pose().scale(1 / s, 1 / s);
    Sink sink = sink(g);
    float px = x * s, py = y * s, pw = w * s, ph = h * s;
    int cols = Math.max(1, (int) Math.ceil(pw));
    for (int i = 0; i < cols; i++) {
      float t = pw <= 1 ? 0.5f : (i + 0.5f) / pw;
      rect(sink, px + i, py, px + i + 1, py + ph, mix(left, right, t));
    }
    g.pose().popMatrix();
  }

  // ---- rasterizer core -----------------------------------------------------

  private static Sink sink(GuiGraphics g) {
    return (x1, y1, x2, y2, argb) -> {
      FILLS.incrementAndGet();
      g.fill(x1, y1, x2, y2, argb);
    };
  }

  private static void roundedRows(
      Sink sink, float px, float py, float pw, float ph, float pr, int color) {
    if (pr <= 0) {
      rect(sink, px, py, px + pw, py + ph, color);
      return;
    }
    float yTop = py + pr, yBot = py + ph - pr;
    int rows = (int) Math.ceil(ph);
    for (int i = 0; i < (int) Math.ceil(pr); i++) {
      float rowY = py + i;
      float dy = yTop - (rowY + 0.5f);
      float dx = (dy > 0 && dy < pr) ? (float) Math.sqrt(pr * pr - dy * dy) : pr;
      row(sink, px + pr - dx, rowY, px + pw - pr + dx, color);
    }
    if (yBot > yTop) rect(sink, px, yTop, px + pw, yBot, color);
    int bottomStart = Math.max((int) Math.ceil(pr), (int) Math.floor(ph - pr));
    for (int i = bottomStart; i < rows; i++) {
      float rowY = py + i;
      float dy = (rowY + 0.5f) - yBot;
      float dx = (dy > 0 && dy < pr) ? (float) Math.sqrt(pr * pr - dy * dy) : pr;
      row(sink, px + pr - dx, rowY, px + pw - pr + dx, color);
    }
  }

  /** Emits one device-pixel row span with sub-pixel coverage folded into both edge alphas. */
  private static void row(Sink sink, float leftEdge, float rowY, float rightEdge, int color) {
    int y0 = Math.round(rowY);
    int y1 = y0 + 1;
    int leftInt = (int) Math.floor(leftEdge);
    float leftFrac = leftEdge - leftInt;
    int rightInt = (int) Math.floor(rightEdge);
    float rightFrac = rightEdge - rightInt;
    if (leftInt == rightInt) {
      float cov = Math.max(0f, Math.min(1f, rightEdge - leftEdge));
      int a = Math.round(cov * ((color >>> 24) & 0xFF));
      if (a > 0) sink.rect(leftInt, y0, leftInt + 1, y1, (a << 24) | (color & 0x00FFFFFF));
      return;
    }
    if (leftFrac > 0.001f)
      sink.rect(leftInt, y0, leftInt + 1, y1, alphaScaled(color, 1f - leftFrac));
    int fullStart = leftFrac > 0.001f ? leftInt + 1 : leftInt;
    if (rightInt > fullStart) sink.rect(fullStart, y0, rightInt, y1, color);
    if (rightFrac > 0.001f)
      sink.rect(rightInt, y0, rightInt + 1, y1, alphaScaled(color, rightFrac));
  }

  private static void rect(Sink sink, float x1, float y1, float x2, float y2, int color) {
    sink.rect(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), color);
  }

  private static int alphaScaled(int color, float f) {
    int a = Math.round(Math.max(0f, Math.min(1f, f)) * ((color >>> 24) & 0xFF));
    return (a << 24) | (color & 0x00FFFFFF);
  }

  /** Linear blend of two ARGB colors. */
  public static int mix(int a, int b, float t) {
    t = Math.max(0f, Math.min(1f, t));
    int out = 0;
    for (int shift = 0; shift <= 24; shift += 8) {
      int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
      out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
    }
    return out;
  }
}
