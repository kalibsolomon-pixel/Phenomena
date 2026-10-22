package dev.kwamster.combat.metrics;

import java.util.Optional;

public final class Geometry {
  public record Vec(double x, double y, double z) {
    public Vec add(Vec v) {
      return new Vec(x + v.x, y + v.y, z + v.z);
    }

    public Vec scale(double s) {
      return new Vec(x * s, y * s, z * s);
    }

    public double distance(Vec v) {
      return Math.sqrt((x - v.x) * (x - v.x) + (y - v.y) * (y - v.y) + (z - v.z) * (z - v.z));
    }
  }

  public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Vec normalize(Vec p) {
      return new Vec(norm(p.x, minX, maxX), norm(p.y, minY, maxY), norm(p.z, minZ, maxZ));
    }

    public double distance(Vec p) {
      return p.distance(
          new Vec(clamp(p.x, minX, maxX), clamp(p.y, minY, maxY), clamp(p.z, minZ, maxZ)));
    }
  }

  private Geometry() {}

  public static Optional<Vec> intersect(Vec eye, Vec unitDirection, Box box, double maxDistance) {
    double near = 0, far = maxDistance;
    double[] o = {eye.x, eye.y, eye.z},
        d = {unitDirection.x, unitDirection.y, unitDirection.z},
        lo = {box.minX, box.minY, box.minZ},
        hi = {box.maxX, box.maxY, box.maxZ};
    for (int i = 0; i < 3; i++) {
      if (Math.abs(d[i]) < 1e-10) {
        if (o[i] < lo[i] || o[i] > hi[i]) return Optional.empty();
      } else {
        double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
        near = Math.max(near, Math.min(a, b));
        far = Math.min(far, Math.max(a, b));
        if (near > far) return Optional.empty();
      }
    }
    return Optional.of(eye.add(unitDirection.scale(near)));
  }

  public static String placement(double x, double y, double z) {
    if (y > .75) return "High";
    if (y < .25) return "Low";
    return Math.abs(faceHorizontal(x, z) - .5) > .35 ? "Edge" : "Center";
  }

  /** Horizontal coordinate across the contacted vertical face of a normalized box. */
  public static double faceHorizontal(double x, double z) {
    return Math.min(x, 1 - x) < Math.min(z, 1 - z) ? z : x;
  }

  public static String direction(double vx, double vz, double facingX, double facingZ) {
    double dot = vx * facingX + vz * facingZ;
    return dot > .025 ? "Forward" : dot < -.025 ? "Backward" : "Neutral";
  }

  private static double norm(double v, double lo, double hi) {
    return hi > lo ? clamp((v - lo) / (hi - lo), 0, 1) : .5;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
