package dev.phenomena;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Diagnostic main: prints fixtures whose replayed outcome disagrees with the label. */
final class OutcomeFixtureMisses {
  public static void main(String[] args) throws Exception {
    var root =
        JsonParser.parseReader(
                new InputStreamReader(
                    OutcomeFixtureMisses.class.getResourceAsStream("/fixtures/outcomes.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (var element : root.getAsJsonArray("fixtures")) {
      var f = element.getAsJsonObject();
      String label = f.get("label").getAsString();
      if (!label.equals("WIN") && !label.equals("LOSS")) continue;
      List<double[]> times = new ArrayList<>();
      List<String> texts = new ArrayList<>();
      for (var m : f.getAsJsonArray("messages")) {
        times.add(new double[] {m.getAsJsonObject().get("t").getAsDouble()});
        texts.add(m.getAsJsonObject().get("text").getAsString());
      }
      var r = OutcomeFixtureTest.replay(f.get("server").getAsString(), times, texts);
      String p = r == null ? "none" : r.outcome().name();
      boolean inferred =
          args.length > 0
              && r != null
              && r.resolution().confidence() != dev.phenomena.combat.events.Confidence.CONFIRMED;
      if (!p.equals(label) || inferred) {
        System.out.println(
            "---- "
                + f.get("server").getAsString()
                + " label="
                + label
                + " got="
                + p
                + (r == null ? "" : " reason=" + r.reason()));
        for (int i = 0; i < texts.size(); i++)
          System.out.println("   " + (int) times.get(i)[0] + " " + texts.get(i).replace("\n", "|"));
      }
    }
  }
}
