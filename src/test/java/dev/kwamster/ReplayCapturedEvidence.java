package dev.kwamster;

import com.google.gson.*;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import dev.kwamster.recording.storage.AtomicJson;
import java.nio.file.*;
import java.util.*;

/** Private read-only evidence replay. Input is derived outside the original Aurora folders. */
public final class ReplayCapturedEvidence {
  record Input(long at, CombatEvent event, Announcements.Message message) {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length > 0 ? args[0] : ".local-diagnostics/0.1.5");
    Path output = args.length > 1 ? Path.of(args[1]) : root;
    var fixtures =
        JsonParser.parseString(
                Files.readString(root.resolve(args.length > 2 ? args[2] : "captured-replay.json")))
            .getAsJsonArray();
    List<Map<String, Object>> results = new ArrayList<>();
    int passed = 0, events = 0;
    for (var fixture : fixtures) {
      var f = fixture.getAsJsonObject();
      String host = f.get("server").getAsString(), local = f.get("local").getAsString();
      List<FightRecord> out = new ArrayList<>();
      var engine =
          new CombatEngine(
              new Config(),
              out::add,
              (e, g) -> {},
              id -> {
                var name = f.getAsJsonObject("labels").get(id);
                return name == null || name.isJsonNull() ? null : name.getAsString();
              });
      engine.context(host, local);
      List<Input> inputs = new ArrayList<>();
      for (var e : f.getAsJsonArray("events")) {
        var event = AtomicJson.GSON.fromJson(e, CombatEvent.class);
        inputs.add(new Input(event.nanoTime(), event, null));
        events++;
      }
      for (var e : f.getAsJsonArray("messages")) {
        var m = e.getAsJsonObject();
        long at = m.get("nanos").getAsLong();
        Announcements.interpret(
                host,
                m.get("text").getAsString(),
                Announcements.Origin.valueOf(m.get("origin").getAsString()))
            .ifPresent(message -> inputs.add(new Input(at, null, message)));
      }
      inputs.sort(Comparator.comparingLong(Input::at));
      for (var input : inputs) {
        engine.tick(input.at());
        if (input.event() != null) engine.accept(input.event());
        else engine.announcement(host, local, input.message(), input.at());
      }
      String expected =
          f.get(f.has("expectedResult") ? "expectedResult" : "ownerResult").getAsString();
      if (f.has("finalTick")) engine.tick(f.get("finalTick").getAsLong());
      boolean ok = out.size() == 1 && out.getFirst().outcome().name().equals(expected);
      if (ok) {
        passed++;
        var record = out.getFirst();
        var analytics = dev.kwamster.analytics.FightAnalytics.of(record);
        if (analytics.hits.size() != record.statistics().hits)
          throw new AssertionError("Canonical hit summary/graph disagreement");
        dev.kwamster.sharing.ReportExporter.export(
            output
                .resolve(args.length > 2 ? "latest-replay-reports" : "captured-replay-reports")
                .resolve(
                    String.format(
                        java.util.Locale.ROOT, "match-%02d-%s", f.get("match").getAsInt(), host)),
            record,
            dev.kwamster.sharing.ReportExporter.Options.DEFAULT);
      }
      results.add(
          Map.of(
              "server",
              host,
              "match",
              f.get("match").getAsInt(),
              "expectedResult",
              expected,
              "recordings",
              out.size(),
              "results",
              out.stream().map(r -> r.outcome().name()).toList(),
              "events",
              f.getAsJsonArray("events").size(),
              "pass",
              ok));
    }
    AtomicJson.write(
        output.resolve(
            args.length > 2 ? "latest-replay-result.json" : "captured-replay-result.json"),
        Map.of(
            "matches",
            fixtures.size(),
            "passed",
            passed,
            args.length > 2 ? "availableAndMappedEvents" : "actualEvents",
            events,
            "results",
            results,
            "limits",
            "Recorded combat events are real; missing original observations cannot be"
                + " reconstructed. Packet timestamps are exact where captured, other announcements"
                + " have log-second timing. No live external-server acceptance."));
    System.out.println(
        "Captured evidence replay: "
            + passed
            + "/"
            + fixtures.size()
            + " matches; "
            + events
            + (args.length > 2
                ? " available/mapped input events (see fixture limits)"
                : " original events"));
    if (passed != fixtures.size()) throw new AssertionError(AtomicJson.GSON.toJson(results));
  }
}
