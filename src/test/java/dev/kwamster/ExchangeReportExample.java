package dev.kwamster;

import dev.kwamster.combat.events.*;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.Path;

/** Synthetic review artifact with opening miss, duplicates, breaks, shield and uncertainty. */
public final class ExchangeReportExample {
  public static void main(String[] args) throws Exception {
    var s = new Sim("minemen.club");
    s.chat(0, Sim.MM_START);
    s.emit(
        1,
        CombatEvent.Kind.ATTACK,
        "local",
        Sim.B,
        Confidence.OBSERVED,
        Sim.attack(3.3, "minecraft:diamond_sword", null),
        "empty-swing; aimed near opponent",
        null);
    s.hit(1.5).attack(1.55).hit(2).tick(5.1);
    s.emit(
        5.3,
        CombatEvent.Kind.ATTACK,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "empty-swing; no aimed opponent",
        null);
    s.hit(7).incoming(7.5).attack(8, Sim.B, 3, true, true).shield(8.05, "BLOCK", "opponent");
    s.attack(9).chat(10, Sim.MM_WIN);
    Path output = Path.of(args.length > 0 ? args[0] : "build/example-report-v016");
    ReportExporter.export(output, s.last(), ReportExporter.Options.DEFAULT);
    System.out.println(output.toAbsolutePath().resolve("index.html"));
  }
}
