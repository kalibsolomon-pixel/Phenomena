package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.platform.SafePaths;
import dev.phenomena.recording.ExportNotifications;
import dev.phenomena.recording.storage.*;
import dev.phenomena.sharing.ReportExporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * 0.2.1 HTML report contracts: structure, offline operation, embedded data for every scenario the
 * redesigned viewer navigates (rounds, exchanges, manual scopes, historical schemas, missing
 * metrics), determinism, and that a template change never re-exports existing reports.
 */
class ReportViewerTest {
  @TempDir Path dir;

  static String template() throws IOException {
    try (var in = ReportExporter.class.getResourceAsStream("/report-viewer.html")) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static String script(String html) {
    int a = html.indexOf("<script>") + "<script>".length();
    return html.substring(a, html.indexOf("</script>", a));
  }

  static JsonObject payload(Path index) throws IOException {
    String html = Files.readString(index);
    int start = html.indexOf("type=\"application/json\">") + "type=\"application/json\">".length();
    return JsonParser.parseString(html.substring(start, html.indexOf("</script>", start)))
        .getAsJsonObject();
  }

  Path export(String name, FightRecord f) throws IOException {
    return ReportExporter.export(dir.resolve(name), f, ReportExporter.Options.DEFAULT);
  }

  @Test
  void templateHasOneDataSlotAndAccessibleStructure() throws Exception {
    String t = template();
    assertEquals(1, t.split(Pattern.quote(ReportExporter.PLACEHOLDER), -1).length - 1);
    for (String id :
        List.of(
            "id=\"result-line\"",
            "id=\"title\"",
            "id=\"meta\"",
            "id=\"coverage\"",
            "id=\"session-controls\"",
            "role=\"tablist\"",
            "id=\"main\"",
            "class=\"skip\"",
            "<html lang=\"en\">")) assertTrue(t.contains(id), id);
    for (String tab : List.of("overview", "offense", "defense", "flow", "timeline"))
      assertTrue(
          t.contains("id=\"p-" + tab + "\" aria-labelledby=\"t-" + tab + "\""), "panel " + tab);
    String index = Files.readString(export("s", Fixtures.sample()));
    assertFalse(index.contains(ReportExporter.PLACEHOLDER));
    assertEquals(
        "phenomena-report", payload(dir.resolve("s/index.html")).get("format").getAsString());
  }

  @Test
  void viewerIsSelfContainedAndWritesTextOnly() throws Exception {
    String t = template();
    String code = script(t);
    for (String bad :
        List.of(
            "innerHTML",
            "outerHTML",
            "insertAdjacentHTML",
            "document.write",
            "new Function",
            "eval(",
            "import(",
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "EventSource",
            "sendBeacon",
            "localStorage",
            "indexedDB")) assertFalse(code.contains(bad), "viewer script must not use " + bad);
    for (String bad : List.of("@import", "@font-face", "<link", "<img", "<iframe", " src="))
      assertFalse(t.contains(bad), "template must not reference " + bad);
    // Only same-document fragment references (an SVG pattern) are allowed.
    assertFalse(Pattern.compile("url\\((?!#)").matcher(t).find(), "no external url() references");
    String withoutSvgNs = t.replace("http://www.w3.org/2000/svg", "");
    assertFalse(withoutSvgNs.contains("http://") || withoutSvgNs.contains("https://"));
    assertTrue(t.contains("default-src 'none'") && t.contains("connect-src 'none'"));
    assertTrue(t.contains("form-action 'none'") && t.contains("base-uri 'none'"));
  }

  @Test
  void layoutIsResponsiveAndMotionIsOptional() throws Exception {
    String t = template();
    assertTrue(t.contains("name=\"viewport\" content=\"width=device-width, initial-scale=1\""));
    assertTrue(t.contains("@media (max-width:820px)"));
    assertTrue(t.contains("@media (max-width:520px)"));
    assertTrue(t.contains("@media (prefers-reduced-motion:reduce)"));
    assertTrue(t.contains("overflow-x:hidden"), "page never scrolls sideways");
    assertTrue(t.contains(".scroll{max-height:380px;overflow:auto"), "tables scroll in a frame");
    // Charts are laid out at the panel's real pixel width so text keeps its stated size.
    String code = script(t);
    assertTrue(code.contains("function cw()"));
    assertFalse(code.contains("const W=900"), "no fixed-width chart coordinate systems remain");
    assertTrue(code.contains("requestAnimationFrame(frame)") && code.contains("if(!playing"));
    assertFalse(code.contains("setInterval"), "no unbounded timers");
  }

  @Test
  void exportIsDeterministic() throws Exception {
    var f = Fixtures.sample();
    var a = export("a", f);
    var b = export("b", f);
    assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
    assertArrayEquals(
        Files.readAllBytes(a.resolveSibling("report.json")),
        Files.readAllBytes(b.resolveSibling("report.json")));
  }

  @Test
  void roundsAndExchangesReachTheViewer() throws Exception {
    var p = payload(export("rounds", ReportSamples.fixed(ReportSamples.multiRound(), 2)));
    var rounds =
        p.getAsJsonObject("fight").getAsJsonObject("matchEvidence").getAsJsonArray("rounds");
    assertEquals(3, rounds.size());
    for (var r : rounds) assertTrue(r.getAsJsonObject().has("startNanos"));
    assertEquals(3, p.getAsJsonObject("derived").getAsJsonArray("combatExchanges").size());
    var ex = payload(export("ex", ReportSamples.fixed(ReportSamples.exchanges(), 3)));
    assertTrue(ex.getAsJsonObject("derived").getAsJsonArray("combatExchanges").size() >= 3);
    String code = script(template());
    assertTrue(code.contains("'Jump to round or exchange'"));
    assertTrue(code.contains("'Match rounds'") && code.contains("'Not announced'"));
  }

  @Test
  void manualSessionScopesFilterByParticipant() throws Exception {
    var f = ManualReportExample.build();
    var p = payload(export("manual", f));
    var scopes = p.getAsJsonArray("scopes");
    var participants =
        p.getAsJsonObject("fight").getAsJsonObject("session").getAsJsonArray("participants");
    assertEquals(f.session().exchanges().size(), scopes.size());
    Set<String> ids = new HashSet<>();
    for (var x : participants) ids.add(x.getAsJsonObject().get("id").getAsString());
    for (var s : scopes) {
      var scope = s.getAsJsonObject();
      assertEquals("phenomena-report", scope.get("format").getAsString());
      // The participant filter matches each exchange by its (anonymized) opponent id.
      assertTrue(ids.contains(scope.getAsJsonObject("fight").get("opponent").getAsString()));
      assertTrue(scope.getAsJsonObject("derived").has("summary"));
    }
    String code = script(template());
    assertTrue(code.contains("'Participant filter'") && code.contains("'Exchange filter'"));
  }

  @Test
  void historicalSchemaShowsUnavailableInsteadOfZero() throws Exception {
    var f = ReportSamples.historical();
    assertEquals(1, f.schemaVersion());
    var s = payload(export("legacy", f)).getAsJsonObject("derived").getAsJsonObject("summary");
    for (String key :
        List.of(
            "perfectReachHits",
            "serverCrits",
            "damageTaken",
            "totalInputs",
            "uniqueAttacks",
            "combatActiveSeconds"))
      assertTrue(s.get(key) == null || s.get(key).isJsonNull(), key + " must be unavailable");
    assertFalse(s.get("contextAvailable").getAsBoolean());
    String code = script(template());
    assertTrue(code.contains("Recorded before 0.1.2"));
    assertTrue(code.contains("Recorded before 0.1.3"));
    assertTrue(code.contains("Historical measurement policy"));
  }

  @Test
  void incompleteRecordingKeepsMissingMeasurementsNull() throws Exception {
    var d = payload(export("incomplete", ReportSamples.incomplete())).getAsJsonObject("derived");
    var s = d.getAsJsonObject("summary");
    for (String key : List.of("averageReach", "maxReach", "averageInterval", "intervalDeviation"))
      assertTrue(s.get(key) == null || s.get(key).isJsonNull(), key);
    var reach = d.getAsJsonObject("reach");
    assertEquals(0, reach.get("eligible").getAsInt());
    assertTrue(reach.get("mean") == null || reach.get("mean").isJsonNull());
  }

  @Test
  void hostileLabelsInManualSessionsStayInsideTheDataBlock() throws Exception {
    var f = ManualReportExample.build();
    var html =
        Files.readString(
            ReportExporter.export(
                dir.resolve("evil"),
                evilLabels(f),
                new ReportExporter.Options(false, true, false)));
    assertEquals(2, html.split("</script>", -1).length - 1, "exactly two script elements close");
    assertFalse(html.contains("<svg onload"));
    assertTrue(html.contains("\\u003csvg onload=alert(1)\\u003e"));
  }

  private static FightRecord evilLabels(FightRecord f) {
    var s = f.session();
    var participants =
        s.participants().stream()
            .map(
                p ->
                    new dev.phenomena.combat.fights.SessionInfo.Participant(
                        p.id(), "<svg onload=alert(1)></script>"))
            .toList();
    return new FightRecord(
        f.schemaVersion(),
        f.id(),
        f.startEpochMillis(),
        f.endEpochMillis(),
        f.durationNanos(),
        f.opponent(),
        f.outcome(),
        f.reason(),
        f.statistics(),
        f.events(),
        f.policy(),
        f.evaluations(),
        f.opponentLabel(),
        f.matchEvidence(),
        f.resolution(),
        new dev.phenomena.combat.fights.SessionInfo(
            s.startTick(),
            s.endTick(),
            s.world(),
            s.termination(),
            participants,
            s.exchanges(),
            s.announcements(),
            s.manualStartEpochMillis(),
            s.manualStartTick()));
  }

  /**
   * The format version is part of every receipt digest. Bumping it re-exports every historical
   * recording on the next start, so visual-only template changes must leave it alone.
   */
  @Test
  void formatVersionIsUnchangedByTheRedesign() {
    assertEquals(6, ReportExporter.FORMAT_VERSION);
  }

  @Test
  void reportsWrittenByAnEarlierTemplateAreNotReexportedOrAnnounced() throws Exception {
    var f = Fixtures.sample();
    String legacy = "<!doctype html><title>KWAMSTER fight report</title>earlier viewer";
    try (var store = new FightStore(dir.resolve("fights"), s -> {})) {
      var old =
          new AutoReports(
              store,
              dir,
              s -> {},
              (p, fight, o) -> {
                ReportExporter.export(p, fight, o);
                Files.writeString(p.resolve("index.html"), legacy);
              });
      old.finalized(f, ReportExporter.Options.DEFAULT);
      assertTrue(store.await(10000));
      var exports = new AtomicInteger();
      var notices = new ArrayList<ExportNotifications.Completion>();
      var upgraded =
          new AutoReports(
              store,
              dir,
              s -> {},
              (p, fight, o) -> {
                exports.incrementAndGet();
                ReportExporter.export(p, fight, o);
              },
              notices::add);
      upgraded.recover(store.readAll(), ReportExporter.Options.DEFAULT);
      assertEquals(0, exports.get(), "no re-export after a template change");
      assertTrue(notices.isEmpty(), "no completion notice replayed");
      var folder = SafePaths.exportFolder(dir, f, true);
      assertEquals(legacy, Files.readString(folder.resolve("index.html")));
    }
  }
}
