package dev.phenomena.gui.ui;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * Pure layout arithmetic for the dashboard frame, in scaled GUI pixels. Every result keeps
 * controls inside the frame for any GUI scale or window size down to {@link #MIN_WIDTH}.
 */
public final class Layout {
  public static final int MIN_WIDTH = 300, MAX_FRAME = 760;

  public record Rect(int x, int y, int w, int h) {
    public int right() {
      return x + w;
    }

    public int bottom() {
      return y + h;
    }

    public boolean contains(double px, double py) {
      return px >= x && px < x + w && py >= y && py < y + h;
    }
  }

  /** Horizontal frame: centered, capped width, at least a small side gutter. */
  public static Rect frame(int screenWidth, int screenHeight, boolean compact) {
    int gutter = screenWidth < 480 ? 6 : compact ? 10 : 14;
    int w = Math.min(MAX_FRAME, Math.max(0, screenWidth - 2 * gutter));
    return new Rect((screenWidth - w) / 2, 0, w, screenHeight);
  }

  /** Equal-width segments with a gap, the last absorbing rounding; never exceeds total. */
  public static List<Rect> segments(int x, int y, int total, int h, int count, int gap) {
    List<Rect> out = new ArrayList<>();
    if (count <= 0) return out;
    int w = Math.max(1, (total - gap * (count - 1)) / count);
    for (int i = 0; i < count; i++) {
      int sx = x + i * (w + gap);
      int sw = i == count - 1 ? Math.max(1, x + total - sx) : w;
      out.add(new Rect(sx, y, sw, h));
    }
    return out;
  }

  /**
   * Wraps labelled chips into rows within the available width. Each chip is its label width
   * plus padding, so short labels stay compact and long ones never clip.
   */
  public static <T> List<Rect> wrap(
      List<T> items, ToIntFunction<T> textWidth, int x, int y, int width, int h, int pad, int gap) {
    List<Rect> out = new ArrayList<>();
    int cx = x, cy = y;
    for (var item : items) {
      int w = Math.min(width, textWidth.applyAsInt(item) + 2 * pad);
      if (cx > x && cx + w > x + width) {
        cx = x;
        cy += h + gap;
      }
      out.add(new Rect(cx, cy, w, h));
      cx += w + gap;
    }
    return out;
  }

  public static int rowsHeight(List<Rect> rects, int y) {
    int bottom = y;
    for (var r : rects) bottom = Math.max(bottom, r.bottom());
    return bottom - y;
  }

  /** Columns for a card grid: as many as fit at the minimum card width, capped. */
  public static int columns(int width, int minCard, int gap, int max) {
    return Math.max(1, Math.min(max, (width + gap) / (minCard + gap)));
  }

  /** Scroll step keeps the wheel responsive at every density. */
  public static int wheelStep(int viewportHeight) {
    return Math.max(12, Math.min(48, viewportHeight / 6));
  }

  /** Scrollbar thumb for content in a viewport; null when nothing scrolls. */
  public static Rect thumb(Rect track, int content, int scroll) {
    if (content <= track.h()) return null;
    int h = Math.max(12, track.h() * track.h() / content);
    int travel = track.h() - h;
    int y = track.y() + (int) Math.round(travel * (scroll / (double) (content - track.h())));
    return new Rect(track.x(), y, track.w(), h);
  }

  /** Scroll that brings [top, bottom) into a viewport of given height, changing as little as possible. */
  public static int reveal(int scroll, int viewport, int top, int bottom) {
    if (top < scroll) return top;
    if (bottom > scroll + viewport) return Math.max(0, bottom - viewport);
    return scroll;
  }

  private Layout() {}
}
