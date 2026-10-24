package dev.kwamster.sharing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kwamster.analytics.*;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.fights.MatchEvidence;
import dev.kwamster.combat.metrics.Combos;
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
  public static final int FORMAT_VERSION = 5;
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
    AtomicJson.writeString(
        folder.resolve(".kwamster-owner"), dev.kwamster.platform.SafePaths.requireUuid(fight.id()));
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
    return prepare(f, options, aliases(f));
  }

  private static Map<String, String> aliases(FightRecord f) {
    Map<String, String> map = new LinkedHashMap<>();
    if (f.session() != null)
      for (var p : f.session().participants()) {
        String alias = "Participant " + (1 + map.values().stream().distinct().count());
        if (p.id() != null) map.put(p.id(), alias);
        if (p.label() != null) map.put(p.label(), alias);
      }
    if (f.opponent() != null) map.putIfAbsent(f.opponent(), "Opponent");
    if (f.opponentLabel() != null) map.putIfAbsent(f.opponentLabel(), "Opponent");
    return map;
  }

  private static String identity(String id, Map<String, String> names) {
    return id == null
        ? null
        : "local".equals(id) ? "local" : names.getOrDefault(id, "Other entity");
  }

  private static FightRecord prepare(FightRecord f, Options options, Map<String, String> names) {
    String opponent = f.opponent();
    List<CombatEvent> events = new ArrayList<>();
    for (var e : f.events()) {
      if (!options.includeMotion() && e.kind() == CombatEvent.Kind.MOTION) continue;
      if (options.anonymize())
        e =
            new CombatEvent(
                e.sequence(),
                e.nanoTime(),
                e.kind(),
                identity(e.actor(), names),
                identity(e.target(), names),
                e.confidence(),
                e.attack(),
                redact(e.detail(), f),
                e.clientTick(),
                e.context());
      events.add(e);
    }
    return new FightRecord(
        f.schemaVersion(),
        f.id(),
        f.startEpochMillis(),
        f.endEpochMillis(),
        f.durationNanos(),
        options.anonymize() ? identity(opponent, names) : opponent,
        f.outcome(),
        options.anonymize() ? redact(f.reason(), f) : f.reason(),
        f.statistics(),
        events,
        f.policy(),
        f.evaluations(),
        options.anonymize()
            ? (f.manual() ? null : identity(opponent != null ? opponent : f.opponentLabel(), names))
            : f.opponentLabel(),
        options.anonymize()
            ? new MatchEvidence(
                null,
                null,
                f.matchEvidence().roundId(),
                f.matchEvidence().confidence(),
                f.matchEvidence().entries().stream()
                    .map(
                        e ->
                            new MatchEvidence.Entry(
                                e.nanoTime(),
                                e.kind(),
                                e.rule(),
                                e.origin(),
                                "Announcement text redacted",
                                e.accepted()))
                    .toList(),
                f.matchEvidence().timelineStartNanos(),
                f.matchEvidence().matchId(),
                f.matchEvidence().roundNumber(),
                f.matchEvidence().rounds(),
                f.matchEvidence().serverSessionId())
            : f.matchEvidence(),
        options.anonymize() ? redactResolution(f) : f.resolution(),
        f.session() == null
            ? null
            : new dev.kwamster.combat.fights.SessionInfo(
                f.session().startTick(),
                f.session().endTick(),
                options.anonymize() ? null : f.session().world(),
                options.anonymize()
                    ? redact(f.session().termination(), f)
                    : f.session().termination(),
                f.session().participants().stream()
                    .map(
                        p ->
                            options.anonymize()
                                ? new dev.kwamster.combat.fights.SessionInfo.Participant(
                                    identity(p.id(), names), identity(p.id(), names))
                                : p)
                    .toList(),
                f.session().exchanges().stream().map(ex -> prepare(ex, options, names)).toList(),
                f.session().announcements().stream()
                    .map(
                        e ->
                            options.anonymize()
                                ? new MatchEvidence.Entry(
                                    e.nanoTime(),
                                    e.kind(),
                                    e.rule(),
                                    e.origin(),
                                    "Announcement text redacted",
                                    e.accepted())
                                : e)
                    .toList(),
                f.session().manualStartEpochMillis(),
                f.session().manualStartTick()));
  }

  private static dev.kwamster.combat.fights.Resolution redactResolution(FightRecord f) {
    var r = f.resolution();
    return new dev.kwamster.combat.fights.Resolution(
        r.classification(),
        r.source(),
        r.confidence(),
        r.scope(),
        r.establishedNanos(),
        r.evidenceIndex(),
        r.eventSequence(),
        r.notes().stream().map(n -> redact(n, f)).toList(),
        r.derived());
  }

  private static String redact(String text, FightRecord f) {
    if (text == null) return null;
    for (String identity :
        new String[] {
          f.opponent(),
          f.opponentLabel(),
          f.matchEvidence().localPlayer(),
          f.matchEvidence().server()
        }) if (identity != null && !identity.isEmpty()) text = text.replace(identity, "[redacted]");
    if (f.session() != null)
      for (var p : f.session().participants()) {
        if (p.id() != null && !p.id().isEmpty()) text = text.replace(p.id(), "[redacted]");
        if (p.label() != null && !p.label().isEmpty()) text = text.replace(p.label(), "[redacted]");
      }
    return text;
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

  private static Map<String, Object> resolution(FightRecord shared) {
    var r = shared.resolution();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("classification", r.classification().name());
    m.put("label", r.classification().label);
    m.put("source", r.source().label);
    m.put("confidence", r.confidence().name());
    m.put("scope", r.scope().name());
    m.put("evidenceIndex", r.evidenceIndex());
    m.put("eventSequence", r.eventSequence());
    m.put("notes", r.notes());
    m.put("derived", r.derived());
    return m;
  }

  private static Map<String, Object> combos(FightRecord full, FightAnalytics a) {
    long base =
        full.matchEvidence().timelineStartNanos() != null
            ? full.matchEvidence().timelineStartNanos()
            : full.events().isEmpty() ? 0 : full.events().getFirst().nanoTime();
    List<Map<String, Object>> marks = new ArrayList<>();
    for (var mk : a.combos.marks())
      marks.add(
          Map.of(
              "seq", mk.sequence(),
              "t", (mk.nanos() - base) / 1e9,
              "side", mk.side().name(),
              "traded", mk.traded()));
    List<Map<String, Object>> streaks = new ArrayList<>();
    for (var st : a.combos.streaks())
      streaks.add(
          Map.of(
              "side", st.side().name(),
              "length", st.length(),
              "start", (st.startNanos() - base) / 1e9,
              "end", (st.endNanos() - base) / 1e9,
              "seqs", st.sequences(),
              "endedBy", st.endedBy().name()));
    List<Map<String, Object>> exchanges = new ArrayList<>();
    for (var ex : a.combos.exchanges())
      exchanges.add(
          Map.of(
              "start", (ex.startNanos() - base) / 1e9,
              "end", (ex.endNanos() - base) / 1e9,
              "local", ex.localHits(),
              "opponent", ex.opponentHits(),
              "longestLocal", ex.longestLocal(),
              "longestOpponent", ex.longestOpponent()));
    return Map.of(
        "marks",
        marks,
        "streaks",
        streaks,
        "exchanges",
        exchanges,
        "gap",
        full.policy().comboSeconds());
  }

  private static Map<String, Object> dominance(Dominance d) {
    Map<String, Object> dom = new LinkedHashMap<>();
    dom.put("step", d.step);
    List<Double> values = new ArrayList<>(d.values.length);
    for (double v : d.values) values.add(Double.isFinite(v) ? Math.round(v * 1e4) / 1e4 : null);
    dom.put("values", values);
    StringBuilder states = new StringBuilder(d.states.length);
    for (var st : d.states) states.append(st.code);
    dom.put("states", states.toString());
    dom.put("availability", d.availability.name());
    dom.put("availabilityLabel", d.availability.label);
    dom.put("active", d.activeSeconds);
    dom.put("localShare", n(d.share(Dominance.State.LOCAL)));
    dom.put("opponentShare", n(d.share(Dominance.State.OPPONENT)));
    dom.put("neutralShare", n(d.share(Dominance.State.NEUTRAL)));
    dom.put("incomplete", d.incompleteSeconds);
    dom.put("insufficient", d.insufficientSeconds);
    dom.put("changes", d.changes);
    dom.put(
        "longest",
        d.longestAdvantage == null
            ? null
            : Map.of(
                "side", d.longestAdvantage.side().name(),
                "start", d.longestAdvantage.start(),
                "end", d.longestAdvantage.end()));
    dom.put(
        "swing",
        d.largestSwing == null
            ? null
            : Map.of(
                "from", d.largestSwing.from(),
                "to", d.largestSwing.to(),
                "fromValue", d.largestSwing.fromValue(),
                "toValue", d.largestSwing.toValue()));
    dom.put("turningPoints", d.turningPoints());
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("tau", Dominance.TAU_SECONDS);
    model.put("threshold", Dominance.THRESHOLD);
    model.put("minMass", Dominance.MIN_MASS);
    model.put("maxUnattributed", Dominance.MAX_UNATTRIBUTED);
    model.put("hit", Dominance.W_HIT);
    model.put("streakBonus", Dominance.W_STREAK_BONUS);
    model.put("shieldBlocked", Dominance.W_SHIELD_BLOCKED);
    model.put("shieldDisabled", Dominance.W_SHIELD_DISABLED);
    model.put("localBlock", Dominance.W_LOCAL_BLOCK);
    dom.put("model", model);
    return dom;
  }

  private static Map<String, Object> reach(FightAnalytics.ReachSummary r) {
    Map<String, Object> reach = new LinkedHashMap<>();
    reach.put("target", r.target());
    reach.put("tolerance", r.tolerance());
    reach.put("eligible", r.eligible());
    reach.put("within", r.within());
    reach.put("withinPercent", n(r.withinPercent()));
    reach.put("mean", n(r.mean()));
    reach.put("deviation", n(r.deviation()));
    reach.put("max", n(r.max()));
    reach.put("meanAbsoluteError", n(r.meanAbsoluteError()));
    reach.put("values", r.values());
    reach.put("weapons", r.weapons());
    Map<String, Object> byWeapon = new LinkedHashMap<>();
    r.byWeapon()
        .forEach(
            (k, v) -> {
              Map<String, Object> w = new LinkedHashMap<>();
              w.put("eligible", v.eligible());
              w.put("within", v.within());
              w.put("mean", n(v.mean()));
              w.put("deviation", n(v.deviation()));
              w.put("max", n(v.max()));
              byWeapon.put(k, w);
            });
    reach.put("byWeapon", byWeapon);
    return reach;
  }

  private static Map<String, Object> aim(FightAnalytics.AimSummary a) {
    Map<String, Object> aim = new LinkedHashMap<>();
    List<Map<String, Object>> samples = new ArrayList<>();
    for (var sm : a.samples()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seq", sm.sequence());
      m.put("t", sm.time());
      m.put("weapon", sm.weapon());
      m.put("face", sm.face());
      m.put("x", sm.x());
      m.put("y", sm.y());
      m.put("z", sm.z());
      m.put("grade", sm.grade());
      m.put("distance", n(sm.distance()));
      m.put("band", sm.reachBand());
      m.put("hit", sm.hit());
      samples.add(m);
    }
    aim.put("samples", samples);
    aim.put("grades", a.grades());
    aim.put("meanDistance", n(a.meanDistance()));
    aim.put("distanceDeviation", n(a.distanceDeviation()));
    aim.put("byWeapon", a.byWeapon());
    aim.put("byReach", a.byReach());
    return aim;
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
    summary.put("accuracy", n(100 * Aggregate.ratio(s.hits, s.accuracyDenominator())));
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
    summary.put("perfectReachHits", full.schemaVersion() >= 2 ? s.perfectReachHits : null);
    summary.put("incoming", s.incoming);
    summary.put("longestCombo", s.longestCombo);
    summary.put("averageCombo", n(a.averageCombo()));
    summary.put("duration", a.duration);
    summary.put("accuracyDenominator", s.accuracyDenominator());
    summary.put("attributedIncoming", a.attributedIncoming);
    summary.put("unattributedIncoming", a.unattributedIncoming);
    summary.put("thirdPartyIncoming", a.thirdPartyIncoming);
    summary.put("unexplainedHealthDrops", a.unexplainedHealthDrops);
    boolean v3 = full.schemaVersion() >= 3;
    summary.put("serverCrits", v3 ? a.serverCrits : null);
    summary.put("damageTaken", v3 ? s.damageTaken : null);
    summary.put("comboCount", a.combos.combos(Combos.Side.LOCAL).size());
    summary.put("comboHits", a.combos.comboHits(Combos.Side.LOCAL));
    summary.put("averageLocalCombo", n(a.averageCombo()));
    summary.put("longestOpponentCombo", a.combos.longest(Combos.Side.OPPONENT));
    summary.put("longestUnanswered", a.combos.longestUnansweredLocal());
    summary.put("opponentComboAvailability", a.opponentComboAvailability());
    summary.put("contextAvailable", a.contextAvailable);

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
      m.put("measurement", h.measurement());
      m.put("perfectReach", full.schemaVersion() >= 2 ? h.perfectReach() : null);
      m.put("weapon", h.weapon());
      m.put("serverCrit", h.serverCrit());
      m.put("face", h.face());
      m.put("aimDistance", n(h.aimDistance()));
      m.put("tick", h.tick());
      hits.add(m);
    }
    derived.put("hits", hits);
    List<Map<String, Object>> misses = new ArrayList<>();
    for (var m0 : a.misses) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seq", m0.sequence());
      m.put("t", m0.time());
      m.put("empty", m0.empty());
      m.put(
          "estimatedReach", m0.geometry() && m0.estimatedReach() >= 0 ? m0.estimatedReach() : null);
      m.put("contact", m0.rayContact());
      m.put("aimH", m0.rayContact() ? m0.aimHorizontal() : null);
      m.put("aimV", m0.rayContact() ? m0.aimVertical() : null);
      misses.add(m);
    }
    derived.put("misses", misses);
    Map<String, Integer> readiness = new LinkedHashMap<>();
    for (var g : Grade.values()) readiness.put(g.name(), a.readiness[g.ordinal()]);
    derived.put("readiness", readiness);
    derived.put("timings", a.timings);
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
    for (var j : a.jumps) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("t", j.time());
      m.put("offsetMs", j.offsetMillis());
      m.put("candidate", j.candidate());
      m.put("offsetTicks", j.offsetTicks());
      m.put("classification", j.classification());
      jumps.add(m);
    }
    derived.put("jumps", jumps);
    derived.put("jumpsWithoutDamage", a.jumpsWithoutDamage);
    derived.put("motionSamples", a.motionSamples);
    derived.put("sprintTransitions", a.sprintTransitions);
    derived.put("schema", full.schemaVersion());
    derived.put("resolution", resolution(shared));
    // Interpretations restate measured values; they contain no names.
    derived.put("interpretations", Interpretation.of(a));
    derived.put("combos", combos(full, a));
    derived.put("dominance", dominance(a.dominance));
    derived.put("shields", a.shields);
    derived.put("incomingDetail", a.incoming);
    List<Map<String, Object>> timing = new ArrayList<>();
    for (var t : a.timingSamples) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seq", t.sequence());
      m.put("t", t.time());
      m.put("weapon", t.weapon());
      m.put("attackSpeed", n(t.attackSpeed()));
      m.put("expected", n(t.expectedTicks()));
      m.put("interval", n(t.intervalTicks()));
      m.put("delta", n(t.deltaTicks()));
      m.put("grade", t.grade().name());
      m.put("outcome", t.outcome());
      timing.add(m);
    }
    derived.put("timingSamples", timing);
    derived.put("attemptOutcomes", a.attemptOutcomes);
    derived.put("reach", reach(a.reach));
    derived.put("aim", aim(a.aim));
    derived.put(
        "jumpEffect",
        a.jumpEffect == null
            ? null
            : Map.of(
                "candidates", a.jumpEffect.candidates(),
                "candidateSpeed", a.jumpEffect.candidateSpeed(),
                "others", a.jumpEffect.others(),
                "otherSpeed", a.jumpEffect.otherSpeed()));
    derived.put("jumpWindowTicks", full.policy().jumpWindowTicks());
    derived.put("boundaries", a.boundaries);

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
    if (full.manual()) {
      List<Map<String, Object>> scopes = new ArrayList<>();
      for (int i = 0; i < full.session().exchanges().size(); i++) {
        var ex = full.session().exchanges().get(i);
        var redacted = shared.session().exchanges().get(i);
        scopes.add(payload(ex, redacted, options));
      }
      payload.put("scopes", scopes);
      derived.put("boundaries", shared.session().announcements());
      summary.put("comboCount", s.comboCount);
      summary.put("comboHits", s.comboHits);
      summary.put("longestOpponentCombo", s.longestOpponentCombo);
      summary.put("longestUnanswered", s.longestUnanswered);
      summary.put("attributedIncoming", s.incoming);
      summary.put("unattributedIncoming", s.unattributedIncoming);
      summary.put("thirdPartyIncoming", s.thirdPartyIncoming);
      summary.put("opponentComboAvailability", "Unavailable");
    }
    return payload;
  }
}
