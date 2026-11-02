package dev.phenomena;

import dev.phenomena.analytics.FightAnalytics;
import dev.phenomena.recording.storage.FightStore;
import dev.phenomena.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;

/**
 * Validation helper, never part of the mod: reads every recording under a source directory
 * (read-only), decodes it with the production decoder, computes analytics and writes an anonymized
 * report into a separate output directory. Prints counts by schema and any failures. Usage:
 * ExportForValidation SOURCE_FIGHTS_DIR OUTPUT_DIR
 */
final class ExportForValidation {
  public static void main(String[] args) throws Exception {
    Path source = Path.of(args[0]), out = Path.of(args[1]);
    if (out.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize()))
      throw new IllegalArgumentException("Output must not be inside the source directory");
    Files.createDirectories(out);
    Map<Integer, Integer> schemas = new TreeMap<>();
    Map<String, Integer> classes = new TreeMap<>();
    List<String> failures = new ArrayList<>(), reports = new ArrayList<>();
    long analyticsNanos = 0, exportNanos = 0;
    int n = 0;
    try (var files = Files.walk(source, 2)) {
      for (var p : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
        try {
          var f = FightStore.decode(Files.readString(p));
          schemas.merge(f.schemaVersion(), 1, Integer::sum);
          classes.merge(f.resolution().classification().name(), 1, Integer::sum);
          long t0 = System.nanoTime();
          FightAnalytics.of(f);
          long t1 = System.nanoTime();
          String name = String.format("r%03d", n++);
          ReportExporter.export(out.resolve(name), f, ReportExporter.Options.DEFAULT);
          long t2 = System.nanoTime();
          analyticsNanos += t1 - t0;
          exportNanos += t2 - t1;
          reports.add(name + "/index.html");
        } catch (Exception ex) {
          failures.add(p.getFileName() + ": " + ex);
        }
      }
    }
    Files.writeString(out.resolve("reports.json"), new com.google.gson.Gson().toJson(reports));
    System.out.println(
        "exported=" + reports.size() + " schemas=" + schemas + " derived classes=" + classes);
    System.out.printf(
        Locale.ROOT,
        "analytics total %.1f ms, export total %.1f ms%n",
        analyticsNanos / 1e6,
        exportNanos / 1e6);
    System.out.println("failures=" + failures.size());
    failures.forEach(System.out::println);
  }
}
