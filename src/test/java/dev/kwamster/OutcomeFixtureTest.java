package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Replays the anonymized announcement fixtures extracted from the owner's logs
 * (scripts/extract_outcome_fixtures.py) through the real adapters and engine, with synthetic melee
 * activity before each terminal announcement. Labels come from the script's own consensus rule, so
 * this measures parsing and lifecycle against the server's own statements on two servers only.
 */
class OutcomeFixtureTest {
  record Score(int tp, int fp, int fn) {
    double precision() {
      return tp + fp == 0 ? Double.NaN : (double) tp / (tp + fp);
    }

    double recall() {
      return tp + fn == 0 ? Double.NaN : (double) tp / (tp + fn);
    }
  }

  static FightRecord replay(String server, List<double[]> times, List<String> texts) {
    List<FightRecord> out = new ArrayList<>();
    var engine =
        new CombatEngine(
            new Config(), out::add, (e, g) -> {}, id -> "B".equals(id) ? "PlayerB" : "PlayerC");
    long[] seq = {0};
    long ms = 0;
    double combatFrom = 3, lastTerminal = -100;
    boolean[] terminals = new boolean[texts.size()];
    for (int i = 0; i < texts.size(); i++) {
      String flat = Announcements.normalize(texts.get(i));
      terminals[i] =
          (flat.contains("PlayerA") || flat.contains("PlayerB"))
              && (flat.contains("Winner:")
                  || flat.contains("Loser:")
                  || flat.contains("killed by")
                  || flat.contains("won!"));
    }
    for (int i = 0; i < texts.size(); i++) {
      double t = times.get(i)[0];
      String text = texts.get(i);
      String flat = Announcements.normalize(text);
      // A cluster is the messages sharing one log second; fighting precedes its first message.
      boolean first = i == 0 || times.get(i - 1)[0] != t;
      boolean terminal = false;
      for (int k = i; first && k < texts.size() && times.get(k)[0] == t; k++)
        terminal |= terminals[k];
      // Simulated fighting before the first terminal message of a fighting phase, as in the real
      // matches. Messages that follow a result directly, or name other players, get no combat.
      if (terminal && t - lastTerminal > 6)
        for (double c = combatFrom; c < t - .5; c += 1.5) {
          long at = (long) (c * 1000);
          if (at <= ms) continue;
          ms = at;
          engine.tick(ms * 1_000_000);
          engine.accept(event(++seq[0], ms, Kind.ATTACK, "local", "B"));
          ms += 40;
          engine.accept(event(++seq[0], ms, Kind.HURT, "local", "B"));
        }
      if (flat.matches("Round \\d+ started!") || flat.contains("Opponent:")) combatFrom = t + 3;
      if (terminal) {
        combatFrom = t + 3;
        lastTerminal = t;
      }
      ms = Math.max(ms + 10, (long) (t * 1000));
      engine.tick(ms * 1_000_000);
      var msg = Announcements.interpret(server, text, Announcements.Origin.SYSTEM);
      if (msg.isPresent()) engine.announcement(server, "PlayerA", msg.get(), ms * 1_000_000);
    }
    engine.tick((ms + 70_000) * 1_000_000);
    // The match is classified by its last decisive recording (best-of rounds produce several).
    for (int i = out.size() - 1; i >= 0; i--)
      if (out.get(i).outcome() == FightRecord.Outcome.WIN
          || out.get(i).outcome() == FightRecord.Outcome.LOSS) return out.get(i);
    return out.isEmpty() ? null : out.getLast();
  }

  static CombatEvent event(long seq, long ms, Kind kind, String actor, String target) {
    return new CombatEvent(
        seq,
        ms * 1_000_000,
        kind,
        actor,
        target,
        kind == Kind.HURT ? Confidence.CONFIRMED : Confidence.OBSERVED,
        kind == Kind.ATTACK ? new Attack(1, 3, .5, .5, 0, false, false, true) : null,
        kind == Kind.HURT ? "local-melee-source" : "",
        ms / 50);
  }

  @Test
  void ownerLogFixturesPrecisionAndRecall() throws Exception {
    var stream = getClass().getResourceAsStream("/fixtures/outcomes.json");
    assertNotNull(stream, "fixture resource");
    var root =
        JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
            .getAsJsonObject();
    Map<String, int[]> confusion = new TreeMap<>();
    int confirmed = 0, decided = 0, total = 0, skipped = 0, roundOnly = 0;
    Map<String, Score> scores = new LinkedHashMap<>();
    int[] winC = new int[3], lossC = new int[3];
    for (var element : root.getAsJsonArray("fixtures")) {
      var f = element.getAsJsonObject();
      String label = f.get("label").getAsString();
      if (label.equals("AMBIGUOUS")) {
        skipped++;
        continue;
      }
      List<double[]> times = new ArrayList<>();
      List<String> texts = new ArrayList<>();
      for (var m : f.getAsJsonArray("messages")) {
        times.add(new double[] {m.getAsJsonObject().get("t").getAsDouble()});
        texts.add(m.getAsJsonObject().get("text").getAsString());
      }
      var record = replay(f.get("server").getAsString(), times, texts);
      // These legacy labels fall back to the last round/kill vote. They cannot authorize a
      // configured match end in 0.1.5. Keep the original corpus and assert explicit abstention.
      if (f.get("server").getAsString().equals("pvphq.com")
          && !label.equals("NONE")
          && texts.stream().noneMatch(t -> t.contains("MATCH REPORT"))) {
        assertNull(record, "round-only evidence must not produce a completed match");
        roundOnly++;
        continue;
      }
      total++;
      String predicted =
          record == null
              ? "NONE"
              : switch (record.outcome()) {
                case WIN -> "WIN";
                case LOSS -> "LOSS";
                default -> "NONE";
              };
      if (record != null
          && (record.resolution().classification() == Resolution.Classification.CONFIRMED_WIN
              || record.resolution().classification() == Resolution.Classification.CONFIRMED_LOSS))
        confirmed++;
      if (!predicted.equals("NONE")) decided++;
      confusion.computeIfAbsent(label + "->" + predicted, k -> new int[1])[0]++;
      for (var cls : List.of("WIN", "LOSS")) {
        int[] c = cls.equals("WIN") ? winC : lossC;
        if (predicted.equals(cls) && label.equals(cls)) c[0]++;
        else if (predicted.equals(cls)) c[1]++;
        else if (label.equals(cls)) c[2]++;
      }
    }
    scores.put("WIN", new Score(winC[0], winC[1], winC[2]));
    scores.put("LOSS", new Score(lossC[0], lossC[1], lossC[2]));
    StringBuilder report = new StringBuilder();
    report.append(
        String.format(
            Locale.ROOT,
            "eligible fixtures=%d (ambiguous skipped=%d; round-only policy abstentions=%d)"
                + " decided=%d confirmed=%d%n",
            total,
            skipped,
            roundOnly,
            decided,
            confirmed));
    for (var e : scores.entrySet())
      report.append(
          String.format(
              Locale.ROOT,
              "%s tp=%d fp=%d fn=%d precision=%.4f recall=%.4f%n",
              e.getKey(),
              e.getValue().tp(),
              e.getValue().fp(),
              e.getValue().fn(),
              e.getValue().precision(),
              e.getValue().recall()));
    confusion.forEach((k, v) -> report.append(k).append(' ').append(v[0]).append('\n'));
    Path out = Path.of("build", "reports", "outcome-fixtures.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report.toString());
    System.out.println(report);
    // Regression guards on this fixture set only; see VALIDATION.md for the measured values.
    assertTrue(scores.get("WIN").precision() >= .99, report.toString());
    assertTrue(scores.get("LOSS").precision() >= .99, report.toString());
    assertTrue(scores.get("WIN").recall() >= .95, report.toString());
    assertTrue(scores.get("LOSS").recall() >= .95, report.toString());
  }
}
