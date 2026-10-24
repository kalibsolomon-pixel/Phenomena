package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.tracking.*;
import dev.kwamster.recording.Diagnostics;
import dev.kwamster.recording.storage.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticCaptureTest {
  @TempDir Path data;

  @Test
  void diagnosticsExposePolicyAdapterAndMissingEvidenceWithoutCountdownNoise() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      d.policy(
          () -> CapturePolicy.resolve("mcpvp.club", false, false),
          () -> "Missing start; combat suppressed");
      d.enabled(true);
      d.announcement(
          "mcpvp.club",
          "3",
          Announcements.Origin.TITLE,
          Announcements.interpret("mcpvp.club", "3", Announcements.Origin.TITLE).orElseThrow(),
          1,
          "Rejected");
      d.announcement(
          "mcpvp.club",
          "⚔ FIGHT! ⚔",
          Announcements.Origin.TITLE,
          Announcements.interpret("mcpvp.club", "⚔ FIGHT! ⚔", Announcements.Origin.TITLE)
              .orElseThrow(),
          2,
          "Rejected: automatic capture disabled");
      d.flush();
      assertTrue(store.await(10000));
      try (var paths = Files.list(data.resolve("diagnostics"))) {
        var r =
            AtomicJson.GSON.fromJson(
                Files.readString(paths.findFirst().orElseThrow()), Diagnostics.Report.class);
        assertEquals(2, r.schema());
        assertEquals(1, r.entries().size());
        var e = r.entries().getFirst();
        assertEquals("DISABLED", e.capturePolicy());
        assertEquals("mcpvp", e.adapterId());
        assertEquals(1, e.adapterVersion());
        assertTrue(e.decision().contains("disabled"));
      }
    }
  }

  void capture(Diagnostics d, String text, Announcements.Origin origin) {
    d.announcement("private.example.net", text, origin, null, 100, "not accepted");
  }

  @Test
  void captureIsOffByDefaultAndCreatesNoEvidence() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      assertFalse(d.enabled());
      capture(d, "Winner: SecretPlayer", Announcements.Origin.SYSTEM);
      d.flush();
      assertTrue(store.await(10000));
      assertFalse(Files.exists(data.resolve("diagnostics")));
    }
  }

  @Test
  void excludesPrivateUnrelatedPlayerAndDisguisedMessages() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      d.enabled(true);
      for (var origin :
          List.of(
              Announcements.Origin.PLAYER_CHAT,
              Announcements.Origin.DISGUISED_CHAT,
              Announcements.Origin.UNCERTAIN)) capture(d, "Winner: SecretPlayer", origin);
      for (var text :
          List.of(
              "<SecretPlayer> I won the match",
              "SecretPlayer: I won",
              "[VIP] SecretPlayer » Winner: PlayerA",
              "From SecretPlayer whispers: I won",
              "hello private conversation")) capture(d, text, Announcements.Origin.SYSTEM);
      d.flush();
      assertTrue(store.await(10000));
      assertFalse(Files.exists(data.resolve("diagnostics")));
    }
  }

  @Test
  void unknownCandidatesAndObservableSurfacesKeepChannelAndRejection() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      d.enabled(true);
      for (var origin :
          List.of(
              Announcements.Origin.SYSTEM,
              Announcements.Origin.TITLE,
              Announcements.Origin.SUBTITLE,
              Announcements.Origin.OVERLAY))
        capture(d, "Duel finished against SecretPlayer", origin);
      d.flush();
      assertTrue(store.await(10000));
      try (var paths = Files.list(data.resolve("diagnostics"))) {
        var report =
            AtomicJson.GSON.fromJson(
                Files.readString(paths.findFirst().orElseThrow()), Diagnostics.Report.class);
        assertEquals(4, report.entries().size());
        assertEquals("SUBTITLE", report.entries().get(2).channel());
        assertTrue(report.entries().getFirst().decision().contains("No observed"));
        assertEquals("REAL_LOG_FIXTURES", report.compatibility().getFirst().evidenceLevel());
      }
    }
  }

  @Test
  void fixtureExportRedactsNamesAndServersPreservingLocalOriginals() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      d.enabled(true);
      capture(d, "Duel finished against SecretPlayer and OtherSecret", Announcements.Origin.SYSTEM);
      d.export(true, true, List.of("SecretPlayer"));
      assertTrue(store.await(10000));
      try (var paths = Files.list(data.resolve("diagnostics"))) {
        var files = paths.toList();
        assertEquals(2, files.size());
        String raw =
            Files.readString(
                files.stream()
                    .filter(p -> !p.getFileName().toString().startsWith("fixtures-"))
                    .findFirst()
                    .orElseThrow());
        String shared =
            Files.readString(
                files.stream()
                    .filter(p -> p.getFileName().toString().startsWith("fixtures-"))
                    .findFirst()
                    .orElseThrow());
        assertTrue(raw.contains("SecretPlayer"));
        assertFalse(shared.contains("SecretPlayer"));
        assertFalse(shared.contains("OtherSecret"));
        assertFalse(shared.contains("private.example.net"));
      }
    }
  }

  @Test
  void hostMatchingUsesDomainBoundariesAliasesAndVersionedEvidence() {
    assertTrue(
        Announcements.interpret("EU.MINEMEN.CLUB.:25565", Sim.MM_START, Announcements.Origin.SYSTEM)
            .isPresent());
    assertTrue(
        Announcements.interpret(
                "minemen.club.attacker.net", Sim.MM_START, Announcements.Origin.SYSTEM)
            .isEmpty());
    assertTrue(
        Announcements.interpret(
                "minemen.club", "<PlayerC> " + Sim.MM_WIN, Announcements.Origin.SYSTEM)
            .isEmpty());
    assertTrue(
        Announcements.interpret("minemen.club", Sim.MM_START, Announcements.Origin.PLAYER_CHAT)
            .isEmpty());
    assertEquals(2, Announcements.adapters().getFirst().version());
    assertFalse(ServerCompatibility.unknown().winLoss());
  }

  @Test
  void bareAnnouncementKeysAreNotMistakenForPlayerChat() throws Exception {
    try (var store = new FightStore(data.resolve("fights"), s -> {})) {
      var d = new Diagnostics(store, data, s -> {});
      d.enabled(true);
      capture(d, "Winner: PlayerA", Announcements.Origin.SYSTEM);
      capture(d, "Round: 2 beginning", Announcements.Origin.SYSTEM);
      capture(d, "PlayerC: I won the round", Announcements.Origin.SYSTEM);
      d.flush();
      assertTrue(store.await(10000));
      try (var files = Files.list(data.resolve("diagnostics"))) {
        var r =
            AtomicJson.GSON.fromJson(
                Files.readString(files.findFirst().orElseThrow()), Diagnostics.Report.class);
        assertEquals(2, r.entries().size());
      }
    }
  }
}
