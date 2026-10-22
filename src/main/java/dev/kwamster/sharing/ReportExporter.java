package dev.kwamster.sharing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kwamster.analytics.*;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.recording.storage.AtomicJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Writes a portable, self-contained report folder: report.json (the raw, optionally anonymized
 * recording) and index.html (offline viewer with an embedded payload). The viewer has no network
 * code and a restrictive content security policy. Derived values are computed here by the same
 * {@link FightAnalytics} code the in-game GUI uses, so both surfaces show identical numbers.
 */
public final class ReportExporter {
  public static final String PLACEHOLDER = "__KWAMSTER_PAYLOAD__";
  public static final int FORMAT_VERSION = 2;
  private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

  public record Options(boolean anonymize, boolean includeMotion, boolean reducedMotion) {
    public static final Options DEFAULT = new Options(true, true, false);
  }

  private ReportExporter() {}

  /** Backward-compatible entry point used by earlier callers and tests. */
  public static void export(Path folder, FightRecord fight, boolean anonymize) throws IOException {
    export(folder, fight, new Options(anonymize, true, false));
  }

  public static Path export(Path folder, FightRecord fight, Options options) throws IOException {
    Files.createDirectories(folder);
    FightRecord shared = prepare(fight, options);
    AtomicJson.write(folder.resolve("report.json"), shared);
    String template;
    try (var in = ReportExporter.class.getResourceAsStream("/report-viewer.html")) {
      if (in == null) throw new IOException("Missing viewer template");
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    // Derived values use the complete timeline; motion traces may be omitted from the copy.
    String payload = scriptSafe(COMPACT.toJson(payload(fight, shared, options)));
    Path index = folder.resolve("index.html");
    AtomicJson.writeString(index, template.replace(PLACEHOLDER, payload));
    return index;
  }

  /** Applies privacy and size options without mutating the recording. */
  public static FightRecord prepare(FightRecord f, Options options) {
    String opponent = f.opponent();
    List<CombatEvent> events = new ArrayList<>();
    for (var e : f.events()) {
      if (!options.includeMotion() && e.kind() == CombatEvent.Kind.MOTION) continue;
      if (options.anonymize() && opponent != null)
        e =
            new CombatEvent(
                e.sequence(),
                e.nanoTime(),
                e.kind(),
                opponent.equals(e.actor()) ? "Opponent" : e.actor(),
                opponent.equals(e.target()) ? "Opponent" : e.target(),
                e.confidence(),
                e.attack(),
                e.detail());
      events.add(e);
    }
    return new FightRecord(
        f.schemaVersion(),
        f.id(),
        f.startEpochMillis(),
        f.endEpochMillis(),
        f.durationNanos(),
        options.anonymize() && opponent != null ? "Opponent" : opponent,
        f.outcome(),
        f.reason(),
        f.statistics(),
        events,
        f.policy(),
        f.evaluations(),
        options.anonymize() ? null : f.opponentLabel());
  }

  /**
   * Escapes characters that could end the embedding script element or break JavaScript parsing.
   * They only occur inside JSON strings, where the escapes decode to the same text.
   */
  public static String scriptSafe(String json) {
    StringBuilder out = new StringBuilder(json.length() + 64);
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      switch (c) {
        case '<' -> out.append("\\u003c");
        case '>' -> out.append("\\u003e");
        case '&' -> out.append("\\u0026");
        case '\u2028' -> out.append("\\u2028");
        case '\u2029' -> out.append("\\u2029");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static Double n(double v) {
    return Double.isFinite(v) ? v : null;
  }

  static Map<String, Object> payload(FightRecord full, FightRecord shared, Options options) {
    var a = FightAnalytics.of(full);
    var s = full.statistics();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("attempts", s.attempts);
    summary.put("hits", s.hits);
    summary.put("emptyMisses", s.emptyMisses);
    summary.put("uncertain", s.uncertain);
    summary.put("contacts", s.contacts);
    summary.put("accuracy", n(100 * Aggregate.ratio(s.hits, s.attempts)));
    summary.put("averageReach", n(Aggregate.ratio(s.reachSum, s.reachCount)));
    summary.put("maxReach", s.reachCount == 0 ? null : s.maxReach);
    summary.put("reachSamples", s.reachCount);
    summary.put("criticalConditions", s.criticalConditions);
    summary.put("criticalRate", n(100 * Aggregate.ratio(s.criticalConditions, s.hits)));
    summary.put("sprintHits", s.sprintHits);
    summary.put("sprintRate", n(100 * Aggregate.ratio(s.sprintHits, s.hits)));
    summary.put("averageInterval", n(Aggregate.ratio(s.intervalSum, s.intervalCount)));
    summary.put("intervalDeviation", s.intervalCount == 0 ? null : s.intervalDeviation());
    summary.put("jumpCandidates", s.jumpCandidates);
    summary.put("incoming", s.incoming);
    summary.put("longestCombo", s.longestCombo);
    summary.put("averageCombo", n(a.averageCombo()));
    summary.put("duration", a.duration);

    Map<String, Object> derived = new LinkedHashMap<>();
    derived.put("summary", summary);
    List<Map<String, Object>> hits = new ArrayList<>();
    for (var h : a.hits) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seq", h.attackSequence());
      m.put("t", h.time());
      m.put("evidenceT", h.evidenceTime());
      m.put("reach", h.reach() >= 0 ? h.reach() : null);
      m.put("contact", h.rayContact());
      m.put("aimH", h.rayContact() ? h.aimHorizontal() : null);
      m.put("aimV", h.rayContact() ? h.aimVertical() : null);
      m.put("cooldown", h.cooldown());
      m.put("readiness", h.readiness() == null ? null : h.readiness().name());
      m.put("sprinting", h.sprinting());
      m.put("critical", h.criticalConditions());
      m.put("combo", h.combo());
      m.put("momentum", h.momentum());
      hits.add(m);
    }
    derived.put("hits", hits);
    List<Map<String, Object>> misses = new ArrayList<>();
    for (var m0 : a.misses) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seq", m0.sequence());
      m.put("t", m0.time());
      m.put("empty", m0.empty());
      m.put("estimatedReach", m0.geometry() && m0.estimatedReach() >= 0 ? m0.estimatedReach() : null);
      m.put("contact", m0.rayContact());
      m.put("aimH", m0.rayContact() ? m0.aimHorizontal() : null);
      m.put("aimV", m0.rayContact() ? m0.aimVertical() : null);
      misses.add(m);
    }
    derived.put("misses", misses);
    Map<String, Integer> readiness = new LinkedHashMap<>();
    for (var g : Grade.values()) readiness.put(g.name(), a.readiness[g.ordinal()]);
    derived.put("readiness", readiness);
    derived.put("reachBinSize", FightAnalytics.REACH_BIN);
    derived.put("hitReachBins", a.hitReachBins);
    derived.put("missReachBins", a.missReachBins);
    derived.put("cooldownBins", a.cooldownBins);
    derived.put("intervals", a.intervals);
    derived.put("comboLengths", a.comboLengths);
    derived.put(
        "placement", Map.of("center", a.center, "edge", a.edge, "high", a.high, "low", a.low));
    List<double[]> momentum = new ArrayList<>();
    double[] samples = a.momentumSamples(200);
    for (int i = 0; i < samples.length; i++)
      momentum.add(new double[] {a.duration * i / (samples.length - 1), samples[i]});
    derived.put("momentum", momentum);
    derived.put("incoming", a.incomingTimes);
    List<Map<String, Object>> jumps = new ArrayList<>();
    for (var j : a.jumps)
      jumps.add(Map.of("t", j.time(), "offsetMs", j.offsetMillis(), "candidate", j.candidate()));
    derived.put("jumps", jumps);
    derived.put("jumpsWithoutDamage", a.jumpsWithoutDamage);
    derived.put("motionSamples", a.motionSamples);
    derived.put("sprintTransitions", a.sprintTransitions);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("format", "kwamster-report");
    payload.put("version", FORMAT_VERSION);
    payload.put("anonymized", options.anonymize());
    payload.put("motionIncluded", options.includeMotion());
    payload.put("reducedMotion", options.reducedMotion());
    payload.put("opponentLabel", shared.opponentLabel());
    payload.put("metrics", MetricDefs.ALL);
    payload.put("fight", shared);
    payload.put("derived", derived);
    return payload;
  }
}
