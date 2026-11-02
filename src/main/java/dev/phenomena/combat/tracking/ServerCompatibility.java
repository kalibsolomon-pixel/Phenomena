package dev.phenomena.combat.tracking;

import java.util.*;

/** Versioned, evidence-labeled adapter inventory. Capabilities never imply live validation. */
public final class ServerCompatibility {
  public record Adapter(
      String id,
      int version,
      String name,
      List<String> hosts,
      String evidenceLevel,
      String lastValidated,
      String evidenceSource,
      List<String> modes,
      boolean matchStart,
      boolean matchEnd,
      boolean winLoss,
      boolean rounds,
      String limitations) {}

  public static final Adapter MINEMEN =
      new Adapter(
          "minemen",
          2,
          "Minemen",
          List.of("minemen.club", "minemen.net", "eu.minemen.club", "eu.minemen.net"),
          "REAL_LOG_FIXTURES",
          "2026-10-24",
          "src/test/resources/fixtures/outcomes.json; docs/MEASUREMENT_AUDIT.md",
          List.of("Observed Duel and Casual Match banners"),
          true,
          true,
          true,
          true,
          "Exact verified aliases only. Match results complete the match; round results remain"
              + " inside it. No new live multiplayer validation.");
  public static final Adapter PVPHQ =
      new Adapter(
          "pvphq",
          2,
          "PVPHQ",
          List.of(
              "pvphq.com",
              "play.chi-us.pvphq.com",
              "play.bhs-ca.pvphq.com",
              "play.dal-us.pvphq.com",
              "play.hil-us.pvphq.com",
              "play.lax-us.pvphq.com",
              "play.mim-us.pvphq.com",
              "play.vin-us.pvphq.com"),
          "REAL_LOG_FIXTURES",
          "2026-10-24",
          "src/test/resources/fixtures/outcomes.json; docs/MEASUREMENT_AUDIT.md",
          List.of("Ranked", "Unranked", "Best-of rounds"),
          true,
          true,
          true,
          true,
          "Named context plus MATCH REPORT and winner/loser; no death required. Round lines without"
              + " report remain rounds. No new live multiplayer validation.");

  public static final Adapter MCPVP =
      observed(
          "mcpvp",
          "MCPvP",
          List.of("mcpvp.club"),
          List.of("Observed single-round and first-to-two duels", "Observed XP/armor result block"),
          true,
          "Start requires observed FIGHT title; opponent may remain unknown until combat. Match"
              + " Complete supports observed 1-0/0-1/2-0 and health or XP/armor blocks. Resignation"
              + " is an explicit end with unavailable result; never guessed as a win.");
  public static final Adapter CATPVP =
      observed(
          "catpvp",
          "CatPvP East",
          List.of("east.catpvp.net"),
          List.of("Casual Beast", "Ranked Diamond SMP"),
          true,
          "Round winner is separate from game winner. Owner first-match round count disagrees with"
              + " captured 2-1; no labels rewritten.");
  public static final Adapter FISHFFA =
      observed(
          "fishffa",
          "FishFFA",
          List.of("fishffa.gg", "na.fishffa.gg"),
          List.of("UHC", "Sword"),
          true,
          "Health-bearing winner is round-only; plain winner is duel result. Loss in match 2 is"
              + " visible only in observed defeat subtitles. Last round outcome is not invented"
              + " from duel result.");
  public static final Adapter LEGACY =
      observed(
          "pvplegacy",
          "PvPLegacy",
          List.of("pvplegacy.net"),
          List.of("Observed custom kit duels"),
          true,
          "Round 1 starts an unnamed match; kit owner is not assumed to be opponent. Combat can"
              + " bind participant. Game winner differs from round winner.");

  private static Adapter observed(
      String id,
      String name,
      List<String> hosts,
      List<String> modes,
      boolean rounds,
      String limitations) {
    return new Adapter(
        id,
        id.equals("mcpvp") ? 2 : 1,
        name,
        hosts,
        "REAL_LOG_AND_PACKET_FIXTURES",
        "2026-10-24",
        "src/test/resources/fixtures/six-servers.json; docs/0.1.5.md",
        modes,
        true,
        true,
        true,
        rounds,
        limitations + " No new live external-server acceptance.");
  }

  public static Adapter unknown() {
    return new Adapter(
        "unknown",
        1,
        "Unknown server",
        List.of(),
        "UNKNOWN_UNSUPPORTED",
        null,
        "No observed fixtures",
        List.of(),
        false,
        false,
        false,
        false,
        "Use manual recording and opt-in diagnostics to collect evidence.");
  }

  private ServerCompatibility() {}
}
