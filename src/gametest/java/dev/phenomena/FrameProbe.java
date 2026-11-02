package dev.phenomena;

import java.util.*;

/** Test-only rendered-frame probe, excluded from the production jar. */
public final class FrameProbe {
  public static boolean active;
  private static boolean measuring;
  private static long start;
  private static final long[] nanos = new long[32768];
  private static int count, total;

  public static void begin(boolean render) {
    measuring = active && render;
    if (measuring) start = System.nanoTime();
  }

  public static void end(boolean render) {
    if (measuring && active && render) {
      long duration = System.nanoTime() - start;
      if (++total % 8 == 0 && count < nanos.length) nanos[count++] = duration;
    }
    measuring = false;
  }

  public static void reset() {
    count = 0;
    total = 0;
    measuring = false;
    active = true;
  }

  public static int totalFrames() {
    return total;
  }

  public static double[] sample() {
    active = false;
    measuring = false;
    return java.util.stream.LongStream.of(Arrays.copyOf(nanos, count))
        .mapToDouble(n -> n / 1e6)
        .toArray();
  }
}
