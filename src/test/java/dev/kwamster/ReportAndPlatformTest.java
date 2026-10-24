package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.platform.SafePaths;
import dev.kwamster.platform.SystemOpener;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Browser report generation, escaping, privacy, safe path resolution and file opening. */
class ReportAndPlatformTest {
  @TempDir Path dir;

  @AfterEach
  void restoreLauncher() {
    SystemOpener.setLauncher(null);
  }

  private static String payloadJson(String html) {
    int start = html.indexOf("type=\"application/json\">") + "type=\"application/json\">".length();
    int end = html.indexOf("</script>", start);
    return html.substring(start, end);
  }

  @Test
  void reportEmbedsSharedDerivedValues() throws Exception {
    var fight = Fixtures.sample();
    var index = ReportExporter.export(dir, fight, ReportExporter.Options.DEFAULT);
    String html = Files.readString(index);
    var payload = JsonParser.parseString(payloadJson(html)).getAsJsonObject();
    assertEquals("kwamster-report", payload.get("format").getAsString());
    var derived = payload.getAsJsonObject("derived");
    var summary = derived.getAsJsonObject("summary");
    assertEquals(fight.statistics().hits, summary.get("hits").getAsInt());
    assertEquals(fight.statistics().accuracy(), summary.get("accuracy").getAsDouble(), 1e-9);
    assertEquals(fight.statistics().hits, derived.getAsJsonArray("hits").size());
    assertEquals(3, derived.getAsJsonObject("readiness").get("PERFECT").getAsInt());
    assertTrue(derived.getAsJsonArray("momentum").size() > 10);
    assertFalse(payload.getAsJsonArray("metrics").isEmpty());
    // A fight without reach samples has null, not 0, average reach.
    var none =
        new Fixtures()
            .empty(0)
            .attackAt(.1, Fixtures.attack(1, 3, .5, .5, false, false, true))
            .finish(1);
    var other = dir.resolve("none");
    String h2 =
        Files.readString(ReportExporter.export(other, none, ReportExporter.Options.DEFAULT));
    var s2 =
        JsonParser.parseString(payloadJson(h2))
            .getAsJsonObject()
            .getAsJsonObject("derived")
            .getAsJsonObject("summary");
    assertTrue(s2.get("averageReach") == null || s2.get("averageReach").isJsonNull());
  }

  @Test
  void viewerIsOfflineAndHasAllTabs() throws Exception {
    String html =
        Files.readString(
            ReportExporter.export(dir, Fixtures.sample(), ReportExporter.Options.DEFAULT));
    assertTrue(html.contains("default-src 'none'"));
    assertTrue(html.contains("connect-src 'none'"));
    for (String tab : List.of("'overview'", "'offense'", "'defense'", "'flow'", "'timeline'"))
      assertTrue(html.contains(tab), tab);
    String code = html.replace("http://www.w3.org/2000/svg", "");
    for (String bad :
        List.of(
            "http://",
            "https://",
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "innerHTML",
            "<link",
            "src=\"",
            "eval(")) assertFalse(code.contains(bad), "viewer must not contain " + bad);
  }

  @Test
  void untrustedTextCannotBreakOutOfScript() throws Exception {
    var f = Fixtures.sample();
    var evil =
        new FightRecord(
            1,
            f.id(),
            1,
            2,
            f.durationNanos(),
            "</script><script>alert(1)</script>",
            f.outcome(),
            "</SCRIPT><img src=x onerror=alert(2)>\u2028&amp;",
            f.statistics(),
            f.events(),
            f.policy(),
            f.evaluations(),
            "<b>name</b>");
    String html =
        Files.readString(
            ReportExporter.export(dir, evil, new ReportExporter.Options(false, true, false)));
    String payload = payloadJson(html);
    assertFalse(payload.contains("<"), "no raw angle brackets in payload");
    assertFalse(payload.contains("\u2028"));
    assertFalse(html.contains("<script>alert(1)"));
    assertFalse(html.contains("<img src=x"));
    // Escapes decode to the original text, so the viewer still shows it (via textContent).
    var fight = JsonParser.parseString(payload).getAsJsonObject().getAsJsonObject("fight");
    assertEquals(
        "</SCRIPT><img src=x onerror=alert(2)>\u2028&amp;", fight.get("reason").getAsString());
    assertEquals("\\u003c/script\\u003e", ReportExporter.scriptSafe("</script>"));
  }

  @Test
  void anonymizationRemovesOpponentIdentity() throws Exception {
    var f = Fixtures.sample();
    ReportExporter.export(dir, f, ReportExporter.Options.DEFAULT);
    String html = Files.readString(dir.resolve("index.html")),
        json = Files.readString(dir.resolve("report.json"));
    for (String s : List.of(html, json)) {
      assertFalse(s.contains(Fixtures.TARGET));
      assertFalse(s.contains("Husk"));
    }
    var parsed = FightStore.decode(json);
    assertEquals("Opponent", parsed.opponent());
    assertEquals(f.events().size(), parsed.events().size());
    var named = dir.resolve("named");
    ReportExporter.export(named, f, new ReportExporter.Options(false, true, false));
    assertTrue(
        Files.readString(named.resolve("index.html")).contains("Husk"), "opt-out keeps the label");
  }

  @Test
  void motionTracesCanBeOmitted() throws Exception {
    var f = Fixtures.sample();
    ReportExporter.export(dir, f, new ReportExporter.Options(true, false, false));
    var shared = FightStore.decode(Files.readString(dir.resolve("report.json")));
    assertTrue(
        shared.events().stream()
            .noneMatch(e -> e.kind() == dev.kwamster.combat.events.CombatEvent.Kind.MOTION));
    assertEquals(f.events().size() - 1, shared.events().size());
    assertEquals(f.statistics().hits, shared.statistics().hits, "statistics unchanged");
  }

  @Test
  void exportOverwritesAtomicallyWithoutLeftovers() throws Exception {
    var f = Fixtures.sample();
    ReportExporter.export(dir, f, ReportExporter.Options.DEFAULT);
    ReportExporter.export(dir, f, ReportExporter.Options.DEFAULT);
    try (var files = Files.list(dir)) {
      assertEquals(
          Set.of("index.html", "report.json", ".kwamster-owner"),
          new HashSet<>(files.map(p -> p.getFileName().toString()).toList()));
    }
  }

  @Test
  void managedFoldersResolveUnderGameDirectory() throws Exception {
    Path data = dir.resolve("game").resolve("kwamster");
    Path fights = SafePaths.managed(data, SafePaths.Managed.FIGHTS);
    Path exports = SafePaths.managed(data, SafePaths.Managed.EXPORTS);
    assertEquals(data.toAbsolutePath().normalize().resolve("fights"), fights);
    assertTrue(
        Files.isDirectory(fights) && Files.isDirectory(exports),
        "missing managed directories are created");
    var fight = Fixtures.sample();
    Path folder = SafePaths.exportFolder(data, fight, false);
    assertEquals(exports, folder.getParent());
    for (String bad :
        List.of("../../etc", "..", "a/b", "", "not-a-uuid", fight.id().toUpperCase(Locale.ROOT)))
      assertThrows(
          java.io.IOException.class,
          () -> SafePaths.exportFolder(data, withId(fight, bad), false),
          bad);
    Files.createDirectories(folder);
    assertThrows(java.io.IOException.class, () -> SafePaths.reportFile(folder), "missing report");
    Files.writeString(folder.resolve("index.html"), "x");
    assertEquals(folder.resolve("index.html"), SafePaths.reportFile(folder));
  }

  @Test
  void exportFoldersAreNamedByOpponentAndTime() throws Exception {
    Path data = dir.resolve("kwamster");
    var fight = Fixtures.sample();
    String stamp = "\\d{4}-\\d\\d-\\d\\d \\d\\d-\\d\\d-\\d\\d";
    assertTrue(SafePaths.exportName(fight, false).matches("Fight vs Husk " + stamp));
    assertTrue(
        SafePaths.exportName(fight, true).matches("Fight " + stamp),
        "anonymized names omit the opponent");
    var unnamed = withLabel(fight, null);
    assertTrue(SafePaths.exportName(unnamed, false).matches("Fight " + stamp));
    // Recording-derived labels are reduced to a safe single segment.
    assertEquals("etcpasswd", SafePaths.sanitize("../../etc/passwd"));
    assertEquals("Steve_99", SafePaths.sanitize("Steve_99"));
    assertEquals("ab c", SafePaths.sanitize(" a<>:\"|?*b  c. "));
    assertEquals("", SafePaths.sanitize(".."));
    assertTrue(SafePaths.exportName(withLabel(fight, ".."), false).matches("Fight " + stamp));
    assertEquals(32, SafePaths.sanitize("x".repeat(80)).length());
    // Re-exporting the same fight reuses its folder; a different fight with the same name gets the
    // id.
    Path first = SafePaths.exportFolder(data, fight, false);
    ReportExporter.export(first, fight, new ReportExporter.Options(false, true, false));
    assertEquals(first, SafePaths.exportFolder(data, fight, false));
    var twin = withId(fight, UUID.randomUUID().toString());
    Path second = SafePaths.exportFolder(data, twin, false);
    assertNotEquals(first, second);
    assertTrue(second.getFileName().toString().endsWith("[" + twin.id() + "]"));
    assertEquals(first.getParent(), second.getParent());
  }

  private static FightRecord withId(FightRecord f, String id) {
    return new FightRecord(
        f.schemaVersion(),
        id,
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
        f.opponentLabel());
  }

  private static FightRecord withLabel(FightRecord f, String label) {
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
        label);
  }

  @Test
  void fileWhereFolderExpectedIsRejected() throws Exception {
    Path data = dir.resolve("kwamster");
    Files.createDirectories(data);
    Files.writeString(data.resolve("exports"), "not a folder");
    assertThrows(
        java.io.IOException.class, () -> SafePaths.managed(data, SafePaths.Managed.EXPORTS));
  }

  @Test
  void symlinkedFolderIsRejectedWhereSupported() throws Exception {
    Path data = dir.resolve("kwamster");
    Files.createDirectories(data);
    Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
    try {
      Files.createSymbolicLink(data.resolve("fights"), elsewhere);
    } catch (Exception ex) {
      Assumptions.abort("Symbolic links unavailable: " + ex.getMessage());
    }
    assertThrows(
        java.io.IOException.class, () -> SafePaths.managed(data, SafePaths.Managed.FIGHTS));
  }

  @Test
  void openerCommandsPerPlatform() {
    Path folder = dir.resolve("with space");
    Path file = folder.resolve("index.html");
    assertEquals(
        List.of("explorer.exe", folder.toAbsolutePath().normalize().toString()),
        SystemOpener.command(SystemOpener.Os.WINDOWS, folder, true));
    var win = SystemOpener.command(SystemOpener.Os.WINDOWS, file, false);
    assertEquals("rundll32", win.get(0));
    assertTrue(
        win.get(2).startsWith("file:") && !win.get(2).contains(" "), "spaces percent-encoded");
    assertEquals(
        List.of("xdg-open", folder.toAbsolutePath().normalize().toString()),
        SystemOpener.command(SystemOpener.Os.LINUX, folder, true));
    assertEquals("xdg-open", SystemOpener.command(SystemOpener.Os.LINUX, file, false).getFirst());
    assertEquals("open", SystemOpener.command(SystemOpener.Os.MAC, file, false).getFirst());
  }

  @Test
  void openerValidatesAndReportsFailures() throws Exception {
    List<List<String>> launched = new CopyOnWriteArrayList<>();
    SystemOpener.setLauncher(
        cmd -> {
          launched.add(cmd);
          return 0;
        });
    var result = new LinkedBlockingQueue<Optional<String>>();
    SystemOpener.open(dir.resolve("missing"), true, e -> result.add(Optional.ofNullable(e)));
    assertTrue(result.poll(5, TimeUnit.SECONDS).orElseThrow().contains("does not exist"));
    assertTrue(launched.isEmpty(), "nothing launched for a missing path");
    SystemOpener.open(dir, true, e -> result.add(Optional.ofNullable(e)));
    assertTrue(result.poll(5, TimeUnit.SECONDS).isEmpty(), "success reports no error");
    assertEquals(1, launched.size());
    var file = Files.writeString(dir.resolve("r.html"), "x");
    SystemOpener.open(file, true, e -> result.add(Optional.ofNullable(e)));
    assertTrue(result.poll(5, TimeUnit.SECONDS).isPresent(), "a file is not a folder");
    SystemOpener.setLauncher(
        cmd -> {
          throw new java.io.IOException("no such program");
        });
    SystemOpener.open(file, false, e -> result.add(Optional.ofNullable(e)));
    assertTrue(result.poll(5, TimeUnit.SECONDS).orElseThrow().contains("unavailable"));
  }

  @Test
  void storeReadsOneAndRejectsUnsafeIds() throws Exception {
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(dir, errors::add)) {
      var f = Fixtures.sample();
      store.save(f, 10, 10_000_000);
      assertTrue(store.await(5000));
      assertEquals(f.events().size(), store.readOne(f.id()).events().size());
      assertThrows(java.io.IOException.class, () -> store.readOne("../x"));
      assertThrows(java.io.IOException.class, () -> store.readOne(UUID.randomUUID().toString()));
      var latch = new CountDownLatch(1);
      store.delete(f.id(), latch::countDown);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertFalse(Files.exists(dir.resolve(f.id() + ".json")));
      assertTrue(errors.isEmpty(), errors.toString());
    }
  }

  @Test
  void earlierRecordingsWithoutNewFieldsStillLoad() throws Exception {
    // Shape written by 0.1.0 before evaluations, policy and opponentLabel existed.
    String id = UUID.randomUUID().toString();
    String json =
        """
{"schemaVersion":1,"id":"%s","startEpochMillis":5,"endEpochMillis":6,"durationNanos":10,
 "opponent":"x","outcome":"UNKNOWN","reason":"old","statistics":{"attempts":1,"hits":0,"uncertain":1},
 "events":[{"sequence":1,"nanoTime":1,"kind":"ATTACK","actor":"local","target":"x","confidence":"OBSERVED","detail":""}]}
"""
            .formatted(id);
    var f = FightStore.decode(json);
    assertNull(f.opponentLabel());
    assertTrue(f.evaluations().isEmpty());
    assertNotNull(f.policy());
    var a = dev.kwamster.analytics.FightAnalytics.of(f);
    assertEquals(1, a.misses.size());
    assertTrue(a.misses.getFirst().empty(), "attack with no geometry is an empty swing");
    ReportExporter.export(dir, f, ReportExporter.Options.DEFAULT);
    assertTrue(Files.exists(dir.resolve("index.html")));
  }

  @Test
  void corruptedAndTruncatedRecordingsAreSkipped() throws Exception {
    String id = UUID.randomUUID().toString();
    Files.writeString(
        dir.resolve(id + ".json"), "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"events\":[");
    Files.writeString(dir.resolve("random.json"), "[]");
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(dir, errors::add)) {
      assertTrue(store.readAll().isEmpty());
      assertEquals(2, errors.size());
      assertThrows(java.io.IOException.class, () -> store.readOne(id));
    }
    assertTrue(Files.exists(dir.resolve(id + ".json")), "corrupt file preserved");
  }
}
