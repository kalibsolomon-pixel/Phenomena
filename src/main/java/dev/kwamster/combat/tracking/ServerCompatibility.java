package dev.kwamster.combat.tracking;

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
          1,
          "Minemen",
          List.of("minemen.club", "minemen.net"),
          "REAL_LOG_FIXTURES",
          "2026-10-24",
          "src/test/resources/fixtures/outcomes.json; docs/MEASUREMENT_AUDIT.md",
          List.of("Observed Duel and Casual Match banners"),
          true,
          true,
          true,
          true,
          "Subdomains supported. No observed draw/explicit forfeit grammar; countdown results use"
              + " existing evidence rules. No live multiplayer validation in 0.1.4.");
  public static final Adapter PVPHQ =
      new Adapter(
          "pvphq",
          1,
          "PVPHQ",
          List.of("pvphq.com"),
          "REAL_LOG_FIXTURES",
          "2026-10-24",
          "src/test/resources/fixtures/outcomes.json; docs/MEASUREMENT_AUDIT.md",
          List.of("Ranked", "Unranked", "Best-of rounds"),
          true,
          true,
          true,
          true,
          "Winner/loser lines require named match context. No observed draw/explicit forfeit"
              + " grammar. No live multiplayer validation in 0.1.4.");

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
