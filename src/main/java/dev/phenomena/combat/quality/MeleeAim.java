package dev.phenomena.combat.quality;

import dev.phenomena.combat.metrics.Geometry.*;

/**
 * Client geometry heuristic, separate from raw normalized impact coordinates. Not eye-level
 * scoring.
 */
public final class MeleeAim {
  public enum Grade {
    IDEAL,
    GOOD,
    PERIPHERAL,
    POOR,
    UNAVAILABLE
  }

  public record Region(
      Vec nearest,
      Vec ideal,
      double idealRadius,
      double goodRadius,
      double peripheralRadius,
      boolean reachable) {}

  public static Region region(Vec eye, Box box, double reach) {
    Vec nearest =
        new Vec(
            clamp(eye.x(), box.minX(), box.maxX()),
            clamp(eye.y(), box.minY(), box.maxY()),
            clamp(eye.z(), box.minZ(), box.maxZ()));
    // Inset the nearest visible face to avoid rewarding fragile edge-only ray contacts.
    double inset = .15;
    double x =
        clamp(eye.x(), mix(box.minX(), box.maxX(), inset), mix(box.minX(), box.maxX(), 1 - inset));
    double y =
        clamp(eye.y(), mix(box.minY(), box.maxY(), inset), mix(box.minY(), box.maxY(), 1 - inset));
    double z =
        clamp(eye.z(), mix(box.minZ(), box.maxZ(), inset), mix(box.minZ(), box.maxZ(), 1 - inset));
    if (eye.x() < box.minX() || eye.x() > box.maxX()) x = nearest.x();
    else if (eye.z() < box.minZ() || eye.z() > box.maxZ()) z = nearest.z();
    else if (eye.y() < box.minY() || eye.y() > box.maxY()) y = nearest.y();
    Vec ideal = new Vec(x, y, z);
    // Near the reach limit the nearest surface takes priority over the edge inset.
    if (eye.distance(ideal) > reach) ideal = nearest;
    double scale =
        Math.min(
            box.maxX() - box.minX(), Math.min(box.maxY() - box.minY(), box.maxZ() - box.minZ()));
    return new Region(
        box.normalize(nearest),
        box.normalize(ideal),
        scale * .18,
        scale * .4,
        scale * .7,
        eye.distance(nearest) <= reach);
  }

  /** Blocks between a recorded normalized contact and the ideal point of its region. */
  public static double distance(Vec normalized, Region region, Box box) {
    if (region == null || normalized == null || box == null) return Double.NaN;
    Vec i = region.ideal();
    return new Vec(
            (normalized.x() - i.x()) * (box.maxX() - box.minX()),
            (normalized.y() - i.y()) * (box.maxY() - box.minY()),
            (normalized.z() - i.z()) * (box.maxZ() - box.minZ()))
        .distance(new Vec(0, 0, 0));
  }

  /** Contacted face of a normalized point: the coordinate closest to a box boundary. */
  public static String face(Vec n) {
    double[] v = {n.x(), n.y(), n.z()};
    int axis = 0;
    double best = 2;
    for (int k = 0; k < 3; k++) {
      double edge = Math.min(v[k], 1 - v[k]);
      if (edge < best) {
        best = edge;
        axis = k;
      }
    }
    boolean high = v[axis] > .5;
    return switch (axis) {
      case 0 -> high ? "EAST" : "WEST";
      case 1 -> high ? "TOP" : "BOTTOM";
      default -> high ? "SOUTH" : "NORTH";
    };
  }

  public static Grade classify(Vec normalized, Region region, Box box, boolean visibleContact) {
    if (region == null) return Grade.UNAVAILABLE;
    if (!visibleContact || !region.reachable()) return Grade.POOR;
    double distance = distance(normalized, region, box);
    return distance <= region.idealRadius()
        ? Grade.IDEAL
        : distance <= region.goodRadius()
            ? Grade.GOOD
            : distance <= region.peripheralRadius() ? Grade.PERIPHERAL : Grade.POOR;
  }

  private static double mix(double a, double b, double t) {
    return a + (b - a) * t;
  }

  private static double clamp(double x, double a, double b) {
    return Math.max(a, Math.min(b, x));
  }
}
