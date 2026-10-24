package dev.kwamster.recording;

import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.recording.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Opt-in candidate evidence only. Never captures player/disguised chat or uploads data. */
public final class Diagnostics {
  public record Entry(
      long timestamp,
      long nanos,
      String server,
      String channel,
      String text,
      String kind,
      String rule,
      String winner,
      String loser,
      String opponent,
      String confidence,
      String decision) {}

  public record Report(
      int schema,
      List<ServerCompatibility.Adapter> compatibility,
      List<Entry> entries,
      String privacy,
      Map<String, Object> summary) {}

  private static final Pattern CANDIDATE =
      Pattern.compile(
          "(?i)\\b(match|duel|round|winner|loser|won|lost|killed|draw|forfeit|opponent|victory|defeat)\\b");
  private static final Pattern PRIVATE =
      Pattern.compile(
          "(?i)(?:^<|^\\[.*?]\\s*[A-Za-z0-9_]{1,16}\\s*[:»>]|^(?!(?:Winner|Loser|Opponent|Round|Match|Duel|Results):)[A-Za-z0-9_]{1,16}\\s*[:»>]|\\b(?:whisper|whispers|whispered|private"
              + " message|msg from|msg to)\\b|^\\((?:from|to) )");
  private final FightStore store;
  private final Path folder;
  private final Consumer<String> notice;
  private final List<Entry> entries = new ArrayList<>();
  private String capture = UUID.randomUUID().toString();
  private boolean enabled;
  private long nextSave;

  public Diagnostics(FightStore store, Path data, Consumer<String> notice) {
    this.store = store;
    folder = data.resolve("diagnostics");
    this.notice = notice;
  }

  public boolean enabled() {
    return enabled;
  }

  public void enabled(boolean value) {
    enabled = value;
    if (!value) flush();
  }

  public void announcement(
      String host,
      String text,
      Announcements.Origin origin,
      Announcements.Message message,
      long now,
      String decision) {
    if (!enabled
        || text == null
        || text.length() > 4096
        || origin == Announcements.Origin.PLAYER_CHAT
        || origin == Announcements.Origin.DISGUISED_CHAT
        || origin == Announcements.Origin.UNCERTAIN) return;
    String normalized = Announcements.normalize(text);
    if (PRIVATE.matcher(normalized).find()
        || (message == null && !CANDIDATE.matcher(normalized).find())) return;
    add(
        new Entry(
            System.currentTimeMillis(),
            now,
            host,
            origin.name(),
            text,
            message == null ? "UNRECOGNIZED_CANDIDATE" : message.kind().name(),
            message == null ? null : message.rule(),
            message == null ? null : message.winner(),
            message == null ? null : message.loser(),
            message == null ? null : message.opponent(),
            message != null && message.trusted()
                ? "HOST_RULE; lifecycle acceptance required"
                : "NON_AUTHORITATIVE",
            message == null ? "No observed adapter rule; not accepted" : decision));
  }

  public void event(String host, CombatEvent e) {
    if (!enabled
        || !(e.kind() == CombatEvent.Kind.DEATH
            || e.kind() == CombatEvent.Kind.DISCONNECT
            || e.kind() == CombatEvent.Kind.ATTACK
            || e.kind() == CombatEvent.Kind.DAMAGE_IN
            || e.kind() == CombatEvent.Kind.REMOVED
            || e.kind() == CombatEvent.Kind.TELEPORT)) return;
    add(
        new Entry(
            System.currentTimeMillis(),
            e.nanoTime(),
            host,
            "CLIENT_EVENT",
            e.detail(),
            e.kind().name(),
            null,
            null,
            null,
            e.kind() == CombatEvent.Kind.ATTACK ? e.target() : e.actor(),
            e.confidence().name(),
            "Observed; does not alone confirm a match result"));
  }

  public void outcome(FightRecord f) {
    if (!enabled) return;
    add(
        new Entry(
            System.currentTimeMillis(),
            f.matchEvidence().timelineStartNanos() == null
                ? 0
                : f.matchEvidence().timelineStartNanos(),
            f.matchEvidence().server(),
            "LIFECYCLE",
            f.reason(),
            f.resolution().classification().name(),
            null,
            null,
            null,
            f.opponentLabel(),
            f.resolution().confidence().name(),
            f.resolution().source().label));
  }

  private void add(Entry e) {
    if (entries.size() >= 2048) {
      flush();
      entries.clear();
      capture = UUID.randomUUID().toString();
    }
    entries.add(e);
  }

  public void tick(long now) {
    if (enabled && now >= nextSave) {
      nextSave = now + 5_000_000_000L;
      flush();
    }
  }

  private Report report(List<Entry> rows, String privacy) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("servers", rows.stream().map(Entry::server).distinct().toList());
    summary.put(
        "observedStartPatterns",
        rows.stream()
            .filter(e -> "START".equals(e.kind()) || "ROUND_START".equals(e.kind()))
            .map(Entry::rule)
            .distinct()
            .toList());
    summary.put(
        "observedEndPatterns",
        rows.stream()
            .filter(e -> Set.of("RESULT", "KILL", "END").contains(e.kind()))
            .map(Entry::rule)
            .distinct()
            .toList());
    summary.put(
        "recognizedResultPatterns",
        rows.stream().filter(e -> "RESULT".equals(e.kind())).map(Entry::rule).distinct().toList());
    summary.put(
        "unrecognizedCandidates",
        rows.stream().filter(e -> "UNRECOGNIZED_CANDIDATE".equals(e.kind())).count());
    summary.put(
        "unknownOrInterrupted",
        rows.stream().filter(e -> Set.of("UNKNOWN", "INTERRUPTED").contains(e.kind())).count());
    summary.put(
        "potentialFalsePositives",
        rows.stream()
            .filter(e -> e.rule() != null && !e.decision().startsWith("Accepted"))
            .count());
    summary.put(
        "correlationConfidence",
        rows.stream().filter(e -> "LIFECYCLE".equals(e.channel())).map(Entry::confidence).toList());
    return new Report(1, Announcements.adapters(), rows, privacy, summary);
  }

  public void flush() {
    if (entries.isEmpty()) return;
    var rows = List.copyOf(entries);
    Path file = folder.resolve(capture + ".json");
    store.submit(
        () -> {
          try {
            AtomicJson.write(
                file, report(rows, "Private local evidence; do not commit raw captures"));
          } catch (IOException ex) {
            notice.accept("Diagnostic save failed: " + ex.getMessage());
          }
        });
  }

  /** Redacted fixtures preserve structure. Original local captures are never modified. */
  public void export(boolean names, boolean servers, Collection<String> knownNames) {
    flush();
    var live = List.copyOf(entries);
    store.submit(
        () -> {
          try {
            List<Entry> all = new ArrayList<>();
            if (Files.isDirectory(folder))
              try (var files = Files.list(folder)) {
                for (var p :
                    files
                        .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                        .toList()) {
                  if (Files.isSymbolicLink(p) || Files.size(p) > 8 * 1024 * 1024) continue;
                  var r = AtomicJson.GSON.fromJson(Files.readString(p), Report.class);
                  if (r != null && r.entries() != null) all.addAll(r.entries());
                }
              }
            if (all.isEmpty()) all.addAll(live);
            var identities =
                new TreeSet<String>(
                    Comparator.comparingInt(String::length)
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()));
            identities.addAll(knownNames);
            for (var e : all)
              for (var n : new String[] {e.winner(), e.loser(), e.opponent()})
                if (n != null && !n.isBlank()) identities.add(n);
            Map<String, String> aliases = new LinkedHashMap<>();
            identities.forEach(n -> aliases.put(n, "Player" + (aliases.size() + 1)));
            List<Entry> shared = new ArrayList<>();
            long first = all.isEmpty() ? 0 : all.getFirst().timestamp();
            for (var e : all)
              shared.add(
                  new Entry(
                      e.timestamp() - first,
                      e.nanos(),
                      servers ? "server.invalid" : e.server(),
                      e.channel(),
                      servers
                          ? redactServer(names ? redact(e.text(), aliases) : e.text(), e.server())
                          : names ? redact(e.text(), aliases) : e.text(),
                      e.kind(),
                      e.rule(),
                      names ? redact(e.winner(), aliases) : e.winner(),
                      names ? redact(e.loser(), aliases) : e.loser(),
                      names ? redact(e.opponent(), aliases) : e.opponent(),
                      e.confidence(),
                      names ? redact(e.decision(), aliases) : e.decision()));
            // Unknown names in raw server text are conservatively removed; fixture author can
            // restore reviewed tokens.
            if (names)
              shared =
                  shared.stream()
                      .map(
                          e ->
                              new Entry(
                                  e.timestamp(),
                                  e.nanos(),
                                  e.server(),
                                  e.channel(),
                                  redactUnknown(e.text()),
                                  e.kind(),
                                  e.rule(),
                                  e.winner(),
                                  e.loser(),
                                  e.opponent(),
                                  e.confidence(),
                                  redactUnknown(e.decision())))
                      .toList();
            Path out = folder.resolve("fixtures-" + System.currentTimeMillis() + ".json");
            AtomicJson.write(
                out,
                report(
                    shared,
                    "Exported locally; names="
                        + names
                        + ", servers="
                        + servers
                        + "; review before sharing"));
            notice.accept("Diagnostic fixture bundle saved: " + out);
          } catch (IOException | RuntimeException ex) {
            notice.accept("Diagnostic export failed: " + ex.getMessage());
          }
        });
  }

  private static String redactServer(String text, String host) {
    return text == null
        ? null
        : host == null
            ? text
            : text.replace(host, "server.invalid")
                .replaceAll("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::[0-9]+)?\\b", "server.invalid");
  }

  private static String redact(String text, Map<String, String> aliases) {
    if (text == null) return null;
    for (var e : aliases.entrySet()) text = text.replace(e.getKey(), e.getValue());
    return text;
  }

  private static String redactUnknown(String text) {
    if (text == null) return null;
    // Whitelist protocol grammar rather than guessing whether an arbitrary word is a real name.
    var allowed =
        Set.of(
            "match",
            "results",
            "click",
            "to",
            "view",
            "winner",
            "loser",
            "opponent",
            "map",
            "ping",
            "ms",
            "duel",
            "sword",
            "casual",
            "round",
            "started",
            "ended",
            "won",
            "lost",
            "was",
            "has",
            "been",
            "killed",
            "by",
            "ranked",
            "unranked",
            "report",
            "you",
            "draw",
            "tie",
            "the",
            "in",
            "a",
            "forfeit",
            "local",
            "observed",
            "unknown",
            "interrupted");
    var m = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*").matcher(text);
    var out = new StringBuffer();
    while (m.find()) {
      String token = m.group();
      m.appendReplacement(
          out,
          java.util.regex.Matcher.quoteReplacement(
              allowed.contains(token.toLowerCase(Locale.ROOT)) || token.matches("Player\\d+")
                  ? token
                  : "Redacted"));
    }
    m.appendTail(out);
    return out.toString();
  }
}
