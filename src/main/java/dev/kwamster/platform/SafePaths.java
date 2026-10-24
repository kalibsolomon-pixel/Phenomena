package dev.kwamster.platform;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * Resolves the only locations KWAMSTER opens or writes for the user: its managed fights and exports
 * directories under the Minecraft game directory, and per-fight export folders named by a validated
 * UUID. Nothing read from a recording ever becomes a path component.
 */
public final class SafePaths {
  public enum Managed {
    FIGHTS("fights"),
    EXPORTS("exports");

    public final String folder;

    Managed(String folder) {
      this.folder = folder;
    }
  }

  private SafePaths() {}

  /** {@code <gameDir>/kwamster/<folder>}; created if missing. Refuses symlinks and files. */
  public static Path managed(Path dataDir, Managed which) throws IOException {
    Path base = dataDir.toAbsolutePath().normalize();
    Path dir = base.resolve(which.folder).normalize();
    if (!dir.startsWith(base)) throw new IOException("Path escapes KWAMSTER data directory");
    if (Files.isSymbolicLink(dir)) throw new IOException("Refusing symbolic link: " + dir);
    if (Files.exists(dir) && !Files.isDirectory(dir))
      throw new IOException("Not a directory: " + dir);
    Files.createDirectories(dir);
    return dir;
  }

  private static final java.time.format.DateTimeFormatter STAMP =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT);

  /**
   * Readable folder name: "Fight vs NAME yyyy-MM-dd HH-mm-ss", or "Fight yyyy-MM-dd HH-mm-ss"
   * without a name or when anonymizing. The local start time keeps names unique per fight.
   */
  public static String exportName(dev.kwamster.combat.fights.FightRecord f, boolean anonymize) {
    String when =
        STAMP.format(
            java.time.Instant.ofEpochMilli(f.startEpochMillis())
                .atZone(java.time.ZoneId.systemDefault()));
    if (f.manual()) return "Manual Session " + when;
    String name = anonymize ? "" : sanitize(f.opponentLabel());
    return name.isEmpty() ? "Fight " + when : "Fight vs " + name + " " + when;
  }

  /**
   * Reduces recording-derived text to a safe single path segment: letters, digits, space, dash and
   * underscore only, no leading/trailing dots or spaces, at most 32 characters.
   */
  public static String sanitize(String text) {
    if (text == null) return "";
    StringBuilder out = new StringBuilder();
    for (char c : text.toCharArray())
      if (Character.isLetterOrDigit(c) && c < 128 || c == ' ' || c == '-' || c == '_')
        out.append(c);
    String s = out.toString().trim().replaceAll(" +", " ");
    return s.length() > 32 ? s.substring(0, 32).trim() : s;
  }

  /**
   * Export folder for a fight inside {@code <exports>}. Re-exporting the same fight reuses its
   * folder; if another fight already owns the name, the short fight id is appended.
   */
  public static Path exportFolder(
      Path dataDir, dev.kwamster.combat.fights.FightRecord f, boolean anonymize)
      throws IOException {
    String id = requireUuid(f.id());
    Path exports = managed(dataDir, Managed.EXPORTS);
    String base = exportName(f, anonymize);
    Path folder = checked(exports, base);
    if (Files.exists(folder) && !ownedBy(folder, id))
      folder = checked(exports, base + " [" + id + "]");
    if (Files.exists(folder) && !ownedBy(folder, id))
      throw new IOException("Export collision; existing folder preserved");
    return folder;
  }

  /** Local naming always uses the known opponent, independently of sharing privacy. */
  public static Path recordingFolder(Path dataDir, dev.kwamster.combat.fights.FightRecord f)
      throws IOException {
    String id = requireUuid(f.id());
    Path root = managed(dataDir, Managed.FIGHTS);
    Path folder = checked(root, exportName(f, false));
    if (Files.exists(folder)) {
      if (Files.isRegularFile(folder.resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS))
        return folder;
      try (var entries = Files.list(folder)) {
        if (entries.findAny().isPresent())
          folder = checked(root, exportName(f, false) + " [" + id + "]");
      }
    }
    if (Files.exists(folder)) {
      if (!Files.isDirectory(folder)) throw new IOException("Recording folder collision");
      try (var entries = Files.list(folder)) {
        if (entries.findAny().isPresent()
            && !Files.isRegularFile(folder.resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Recording collision; existing contents preserved");
      }
    }
    return folder;
  }

  private static Path checked(Path exports, String name) throws IOException {
    Path folder = exports.resolve(name).normalize();
    if (!exports.equals(folder.getParent())) throw new IOException("Invalid export folder");
    if (Files.isSymbolicLink(folder)) throw new IOException("Refusing symbolic link: " + folder);
    return folder;
  }

  /** True when the folder holds this fight's report (or nothing yet). */
  private static boolean ownedBy(Path folder, String id) {
    Path owner = folder.resolve(".kwamster-owner");
    try {
      if (Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) && Files.size(owner) < 100)
        return id.equals(Files.readString(owner));
    } catch (IOException ex) {
      return false;
    }
    Path report = folder.resolve("report.json");
    try {
      if (!Files.exists(report)) {
        try (var entries = Files.list(folder)) {
          return entries.findAny().isEmpty();
        }
      }
      if (Files.isSymbolicLink(report) || Files.size(report) > 64L * 1024 * 1024) return false;
      try (var reader = Files.newBufferedReader(report)) {
        var json = com.google.gson.JsonParser.parseReader(reader);
        return json.isJsonObject()
            && json.getAsJsonObject().has("id")
            && id.equals(json.getAsJsonObject().get("id").getAsString());
      }
    } catch (IOException | RuntimeException ex) {
      return false;
    }
  }

  /** The report file to open; must be a regular non-link file directly inside its folder. */
  public static Path reportFile(Path folder) throws IOException {
    Path file = folder.resolve("index.html");
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Report file missing or unsafe");
    return file;
  }

  public static String requireUuid(String id) throws IOException {
    try {
      var uuid = UUID.fromString(id);
      if (!uuid.toString().equals(id)) throw new IllegalArgumentException(id);
      return id;
    } catch (RuntimeException ex) {
      throw new IOException("Invalid fight identifier");
    }
  }
}
