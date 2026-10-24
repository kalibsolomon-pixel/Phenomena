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
    Path root = Path.of(".local-diagnostics/0.1.5");
    var fixtures =
        JsonParser.parseString(Files.readString(root.resolve("captured-replay.json")))
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
      boolean ok =
          out.size() == 1
              && out.getFirst().outcome().name().equals(f.get("ownerResult").getAsString());
      if (ok) passed++;
      results.add(
          Map.of(
              "server",
              host,
              "match",
              f.get("match").getAsInt(),
              "ownerResult",
              f.get("ownerResult").getAsString(),
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
        root.resolve("captured-replay-result.json"),
        Map.of(
            "matches",
            fixtures.size(),
            "passed",
            passed,
            "actualEvents",
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
            + " original events");
    if (passed != fixtures.size()) throw new AssertionError(AtomicJson.GSON.toJson(results));
  }
}
