package dev.kwamster.combat.tracking;

import java.util.*;
import java.util.regex.*;

/** Pure server adapters. Rules see the actual packet channel, never the rendered chat log. */
public final class Announcements {
  public enum Origin {
    SYSTEM,
    PLAYER_CHAT,
    DISGUISED_CHAT,
    OVERLAY,
    UNCERTAIN
  }

  public enum Kind {
    START,
    ROUND_START,
    RESULT,
    KILL,
    END
  }

  public record Message(
      Kind kind,
      String winner,
      String loser,
      String opponent,
      String rule,
      String original,
      Origin origin,
      boolean trusted) {}

  public interface Rules {
    boolean supports(String host);

    Optional<Message> parse(String original, String normalized, Origin origin);
  }

  private static final String NAME = "([A-Za-z0-9_]{1,16})";
  private static final Pattern PAIR =
      Pattern.compile(
          "^Match Results \\(Click to view\\) [^A-Za-z0-9]*Winner: "
              + NAME
              + "[^A-Za-z0-9]+Loser: "
              + NAME
              + "$");
  private static final Pattern CASUAL =
      Pattern.compile(
          "^Casual Match [^A-Za-z0-9]*Map: .+? [^A-Za-z0-9]*Opponent: "
              + NAME
              + " [^A-Za-z0-9]*Ping: \\d+ ms$");
  private static final Pattern OPPONENT = Pattern.compile("^[^A-Za-z0-9]*Opponent: " + NAME + "$");
  private static final Pattern HEALTH_WINNER =
      Pattern.compile(
          "^[^A-Za-z0-9]*Winner: " + NAME + " \\(\\d+(?:\\.\\d+)? [^A-Za-z0-9]+\\) \\[Click\\]$");
  private static final Pattern KILL = Pattern.compile("^" + NAME + " was killed by " + NAME + "!$");
  private static final Pattern ROUND = Pattern.compile("^Round \\d+ started!$");
  private static final Pattern SIMPLE_WIN =
      Pattern.compile("^(?:Winner: " + NAME + "|" + NAME + " Won|You Won)[!.]?$");
  private static final Pattern SIMPLE_LOSS =
      Pattern.compile("^(?:Loser: " + NAME + "|" + NAME + " Lost|You Lost)[!.]?$");
  private static final List<Rules> RULES = new ArrayList<>();

  static {
    register(new Observed("minemen.net", "minemen", true));
    register(new Observed("pvphq.com", "pvphq", false));
  }

  private Announcements() {}

  /**
   * Extension point for server rules without modifying the combat tracker. Register during
   * initialization.
   */
  public static void register(Rules rules) {
    RULES.add(rules);
  }

  public static Optional<Message> interpret(String host, String original, Origin origin) {
    if (original == null
        || original.length() > 4096
        || origin == Origin.PLAYER_CHAT
        || origin == Origin.DISGUISED_CHAT
        || origin == Origin.OVERLAY) return Optional.empty();
    String text = normalize(original);
    for (var rules : RULES)
      if (rules.supports(host)) {
        var found = rules.parse(original, text, origin);
        if (found.isPresent()) return found;
      }
    Matcher win = SIMPLE_WIN.matcher(text), loss = SIMPLE_LOSS.matcher(text);
    if (win.matches())
      return Optional.of(
          message(
              Kind.RESULT,
              text.matches("You Won[!.]?") ? "local" : first(win),
              null,
              null,
              "generic-exact",
              original,
              origin,
              false));
    if (loss.matches())
      return Optional.of(
          message(
              Kind.RESULT,
              null,
              text.matches("You Lost[!.]?") ? "local" : first(loss),
              null,
              "generic-exact",
              original,
              origin,
              false));
    if (ROUND.matcher(text).matches())
      return Optional.of(
          message(Kind.ROUND_START, null, null, null, "observed-round", original, origin, false));
    if (text.equals("Round ended!") || text.equals("Match ended!"))
      return Optional.of(
          message(Kind.END, null, null, null, "generic-end", original, origin, false));
    return Optional.empty();
  }

  private static String first(Matcher m) {
    for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return m.group(i);
    return "local";
  }

  public static String normalize(String text) {
    return text.replaceAll("§.", "").replace("\\n", "\n").strip().replaceAll("\\s+", " ");
  }

  private static Message message(
      Kind k,
      String w,
      String l,
      String o,
      String rule,
      String original,
      Origin origin,
      boolean trusted) {
    return new Message(k, w, l, o, rule, original, origin, trusted && origin == Origin.SYSTEM);
  }

  private record Observed(String domain, String id, boolean paired) implements Rules {
    public boolean supports(String host) {
      if (host == null) return false;
      String h = host.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
      return h.equals(domain) || h.endsWith("." + domain);
    }

    public Optional<Message> parse(String original, String text, Origin origin) {
      Matcher m = (paired ? PAIR : HEALTH_WINNER).matcher(text);
      if (m.matches())
        return Optional.of(
            message(
                Kind.RESULT,
                m.group(1),
                paired ? m.group(2) : null,
                null,
                id + "-result",
                original,
                origin,
                true));
      m = (paired ? CASUAL : OPPONENT).matcher(text);
      if (m.matches())
        return Optional.of(
            message(Kind.START, null, null, m.group(1), id + "-start", original, origin, true));
      m = KILL.matcher(text);
      if (paired && m.matches())
        return Optional.of(
            message(Kind.KILL, m.group(2), m.group(1), null, id + "-kill", original, origin, true));
      return Optional.empty();
    }
  }
}
