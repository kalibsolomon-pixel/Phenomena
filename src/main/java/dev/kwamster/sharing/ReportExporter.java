package dev.kwamster.sharing;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.recording.storage.AtomicJson;
import java.io.*;
import java.nio.file.*;

/** Self-contained local viewer; no network requests or hosted links. */
public final class ReportExporter {
  private ReportExporter() {}

  public static void export(Path folder, FightRecord fight, boolean anonymize) throws IOException {
    Files.createDirectories(folder);
    String json = AtomicJson.GSON.toJson(fight);
    if (anonymize && fight.opponent() != null) json = json.replace(fight.opponent(), "Opponent");
    Files.writeString(folder.resolve("report.json"), json);
    String template;
    try (var in = ReportExporter.class.getResourceAsStream("/report-viewer.html")) {
      if (in == null) throw new IOException("Missing viewer template");
      template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String safe = json.replace("<", "\u003c").replace(">", "\u003e").replace("&", "\u0026");
    Files.writeString(folder.resolve("index.html"), template.replace("__FIGHT_JSON__", safe));
  }
}
