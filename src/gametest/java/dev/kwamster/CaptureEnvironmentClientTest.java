package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.Kind;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.*;
import dev.kwamster.recording.storage.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Production policy/lifecycle/storage exercised inside the muted Minecraft client. Announcements
 * are supplied locally; this is not an external-server packet acceptance test.
 */
final class CaptureEnvironmentClientTest {
  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  static void exercise(ClientGameTestContext ctx) {
    ctx.runOnClient(
        mc -> {
          for (String host :
              List.of(
                  "mcpvp.club",
                  "east.catpvp.net",
                  "minemen.club",
                  "pvphq.com",
                  "fishffa.gg",
                  "pvplegacy.net")) {
            KwamsterClient.engine.context(host, mc.player.getName().getString());
            check(
                KwamsterClient.engine.capturePolicy().mode()
                    == CapturePolicy.Mode.CONFIGURED_SERVER,
                "configured selection " + host);
          }
          KwamsterClient.engine.context(
              ClientCapture.currentHost(), mc.player.getName().getString());
          check(
              KwamsterClient.engine.capturePolicy().mode() == CapturePolicy.Mode.GENERIC_COMBAT,
              "singleplayer generic capture");
        });
    Path root = KwamsterClient.dataDir.resolve("capture-acceptance");
    List<FightRecord> records = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    try (var store = new FightStore(root.resolve("fights"), errors::add)) {
      var reports = new AutoReports(store, root, s -> {});
      var config = new Config();
      RecordingController[] ref = {null};
      var engine =
          new CombatEngine(config, f -> ref[0].automaticFinished(f), (e, g) -> {}, id -> "PlayerB");
      var recording =
          new RecordingController(
              config,
              engine,
              f -> {
                records.add(f);
                reports.finalized(f, ReportExporter.Options.DEFAULT);
              },
              f -> {},
              s -> {},
              id -> "PlayerB");
      ref[0] = recording;
      long time = 1_000_000_000L, sequence = 0;
      String[] hosts = {
        "mcpvp.club", "east.catpvp.net", "minemen.club", "pvphq.com", "fishffa.gg", "pvplegacy.net"
      };
      String[] starts = {
        "⚔ FIGHT! ⚔",
        "Opponent: PlayerB ℹ",
        "Casual Match ● Map: Village ● Opponent: PlayerB ● Ping: 40 ms",
        "► Opponent: PlayerB",
        "☠ Opponent: PlayerB",
        "Round 1 has started."
      };
      String[] ends = {
        "——————————————————————\n"
            + "⚔ Match Complete ⚔\n\n"
            + "🗡 PlayerA won the round! (1 - 0)\n\n"
            + "❤ 11/20 ʜᴘ\n"
            + "——————————————————————",
        "PlayerA won the game.",
        "Match Results (Click to view) 🏆 Winner: PlayerA ▏ ☠ Loser: PlayerB",
        "► Winner: PlayerA (0.9 ❤) [Click]",
        "⭐ Winner: PlayerA",
        "PlayerA has won the game!"
      };
      for (int i = 0; i < hosts.length; i++) {
        String host = hosts[i];
        recording.context(host, "PlayerA", "world", time, 0);
        recording.accept(event(++sequence, time += 1_000_000, Kind.ATTACK));
        check(!engine.engaged(), "ordinary configured attack started " + host);
        recording.announcement(
            host,
            "PlayerA",
            Announcements.interpret(
                    host,
                    starts[i],
                    i == 0 ? Announcements.Origin.TITLE : Announcements.Origin.SYSTEM)
                .orElseThrow(),
            time += 1_000_000);
        check(engine.engaged(), "configured start missing " + host);
        recording.tick(time += 600_000_000_000L, 12000);
        check(engine.engaged(), "configured inactivity ended match " + host);
        if (i == 1) {
          recording.announcement(
              host,
              "PlayerA",
              Announcements.interpret(host, "PlayerB won the round.", Announcements.Origin.SYSTEM)
                  .orElseThrow(),
              ++time);
          check(engine.engaged(), "CatPvP round ended whole match");
        }
        if (i == 3)
          recording.announcement(
              host,
              "PlayerA",
              Announcements.interpret(host, "🏆 MATCH REPORT 🏆", Announcements.Origin.SYSTEM)
                  .orElseThrow(),
              ++time);
        recording.announcement(
            host,
            "PlayerA",
            Announcements.interpret(host, ends[i], Announcements.Origin.SYSTEM).orElseThrow(),
            ++time);
        check(
            !engine.engaged() && records.size() == i + 1,
            "configured result duplicated or missing " + host);
        check(
            records.getLast().outcome() == FightRecord.Outcome.WIN,
            "configured result wrong " + host);
        time += 10_000_000_000L;
      }
      config.tracking = false;
      recording.context("minemen.club", "PlayerA", "world", ++time, 0);
      recording.announcement(
          "minemen.club",
          "PlayerA",
          Announcements.interpret("minemen.club", starts[2], Announcements.Origin.SYSTEM)
              .orElseThrow(),
          ++time);
      check(!engine.engaged(), "disabled automatic announcement captured");
      check(
          recording.start("minemen.club", "PlayerA", "world", ++time, 0),
          "disabled manual unavailable");
      recording.tick(time += 600_000_000_000L, 12000);
      check(recording.manual(), "manual ended by inactivity");
      check(recording.end(++time, 12001), "manual end unavailable");
      check(
          engine.capturePolicy().mode() == CapturePolicy.Mode.DISABLED, "manual restore disabled");
      config.tracking = true;
      recording.context("unconfigured.test", "PlayerA", "world", ++time, 0);
      recording.accept(event(++sequence, ++time, Kind.ATTACK));
      check(engine.engaged(), "generic attack missing");
      recording.tick(time += 60_000_000_000L, 1200);
      check(!engine.engaged(), "generic inactivity did not end");
      recording.context("minemen.club", "PlayerA", "world", ++time, 0);
      recording.announcement(
          "minemen.club",
          "PlayerA",
          Announcements.interpret("minemen.club", starts[2], Announcements.Origin.SYSTEM)
              .orElseThrow(),
          ++time);
      recording.announcement(
          "minemen.club",
          "PlayerA",
          Announcements.interpret(
                  "minemen.club",
                  starts[2].replace("PlayerB", "PlayerC"),
                  Announcements.Origin.SYSTEM)
              .orElseThrow(),
          ++time);
      recording.accept(event(++sequence, ++time, Kind.DISCONNECT));
      check(!engine.engaged(), "disconnect cannot leave another automatic match active");
      check(
          records.getLast().outcome() == FightRecord.Outcome.INTERRUPTED,
          "configured disconnect not preserved");
      check(store.await(20000), "capture exports did not drain");
      check(store.readAll().size() == records.size(), "capture recording count");
      try (var files = Files.walk(root.resolve("exports"))) {
        check(
            files.filter(p -> p.getFileName().toString().equals("index.html")).count()
                == records.size(),
            "capture HTML count");
      }
      check(errors.isEmpty(), "capture storage errors " + errors);
      System.out.println(
          "KWAMSTER 0.1.5 capture acceptance: six environments; generic/disabled/manual/disconnect;"
              + " "
              + records.size()
              + " recordings and HTML reports PASS");
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private static CombatEvent event(long seq, long time, Kind kind) {
    return new CombatEvent(
        seq,
        time,
        kind,
        "local",
        kind == Kind.ATTACK ? "B" : null,
        Confidence.OBSERVED,
        kind == Kind.ATTACK ? new CombatEvent.Attack(1, 3, .5, .5, 0, false, false, true) : null,
        "acceptance",
        seq);
  }
}
