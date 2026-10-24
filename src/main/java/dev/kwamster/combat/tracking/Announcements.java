package dev.kwamster.combat.tracking;

import dev.kwamster.combat.fights.Resolution.Scope;
import java.util.*;
import java.util.regex.*;

/**
 * Pure server adapters. Rules see the actual packet channel, never the rendered chat log. Each rule
 * is matched against the whole normalized message, so relayed player chat that merely contains a
 * phrase cannot match. Formats were taken from the owner's server logs (see MEASUREMENT_AUDIT.md).
 */
public final class Announcements {
  public enum Origin {
    SYSTEM,
    PLAYER_CHAT,
    DISGUISED_CHAT,
    OVERLAY,
    TITLE,
    SUBTITLE,
    UNCERTAIN
  }

  public enum Kind {
    /** A round or match start naming the opponent. */
    START,
    /** A new round inside the current match (best-of formats). */
    ROUND_START,
    /** Countdown finished / match banner. Informational; never splits a round. */
    BEGIN,
    RESULT,
    KILL,
    END,
    /** Non-authoritative text kept as local evidence only (titles). */
    NOTE
  }

  /**
   * One interpreted announcement. {@code winner}/{@code loser} may be "local" for second-person
   * forms. {@code scope} says whether a result describes a whole match or one round. {@code
   * trusted} is true only for exact host-scoped adapter rules on the system channel.
   */
  public record Message(
      Kind kind,
      String winner,
      String loser,
      String opponent,
      String rule,
      String original,
      Origin origin,
      boolean trusted,
      Scope scope,
      boolean draw,
      Integer roundNumber,
      int[] score) {
    public Message(
        Kind kind,
        String winner,
        String loser,
        String opponent,
        String rule,
        String original,
        Origin origin,
        boolean trusted) {
      this(kind, winner, loser, opponent, rule, original, origin, trusted, null, false, null, null);
    }

    public boolean paired() {
      return winner != null && loser != null;
    }
  }

  public interface Rules {
    default ServerCompatibility.Adapter metadata() {
      return ServerCompatibility.unknown();
    }

    boolean supports(String host);

    Optional<Message> parse(String original, String normalized, Origin origin);
  }

  private static final String NAME = "([A-Za-z0-9_]{1,16})";
  private static final String SYM = "[^A-Za-z0-9]*";
  // ---- Minemen (minemen.net, minemen.club)
  private static final Pattern MM_PAIR =
      Pattern.compile(
          "^Match Results \\(Click to view\\) "
              + SYM
              + "Winner: "
              + NAME
              + "[^A-Za-z0-9]+Loser: "
              + NAME
              // Spectated matches append "Spectators (n): names".
              + "(?: Spectators \\(\\d+\\): [A-Za-z0-9_, ]{0,400})?$");

  /** {@code Casual Match}, {@code Sword Duel}, {@code Diamond Potion Duel}, ... blocks. */
  private static final Pattern MM_START =
      Pattern.compile(
          "^[A-Za-z][A-Za-z0-9 ]{0,40}? (?:Match|Duel) "
              + SYM
              + "Map: .+? "
              + SYM
              + "Opponent: "
              + NAME
              + " "
              + SYM
              + "Ping: \\d+ ms$");

  private static final Pattern MM_KILL =
      Pattern.compile("^" + NAME + " was killed by " + NAME + "!$");

  /** {@code PlayerA won! 1 - 0}, optionally with remaining health: {@code PlayerA (8.5❤) won!}. */
  private static final Pattern MM_ROUND_WON =
      Pattern.compile(
          "^" + NAME + "(?: \\(\\d+(?:\\.\\d+)?[^A-Za-z0-9()]*\\))? won! (\\d{1,2}) - (\\d{1,2})$");

  private static final Pattern MM_BEGIN = Pattern.compile("^Match started!$");
  // ---- PVPHQ
  private static final Pattern HQ_OPPONENT = Pattern.compile("^" + SYM + "Opponent: " + NAME + "$");
  private static final Pattern HQ_BANNER =
      Pattern.compile("^" + SYM + "(?:UNRANKED|RANKED) MATCH" + SYM + "$");
  private static final Pattern HQ_KILL =
      Pattern.compile("^" + SYM + NAME + " has been killed by " + NAME + "\\.$");
  private static final Pattern HQ_REPORT = Pattern.compile("^" + SYM + "MATCH REPORT" + SYM + "$");

  /** {@code ► Winner: PlayerA (7.9 ❤) [Click]}; best-of modes add {@code , 10/18 ⚗}. */
  private static final Pattern HQ_WINNER =
      Pattern.compile("^" + SYM + "Winner: " + NAME + " \\(\\d[^()]*\\) \\[Click\\]$");

  private static final Pattern HQ_LOSER =
      Pattern.compile("^" + SYM + "Loser: " + NAME + "(?: \\(\\d[^()]*\\))? \\[Click\\]$");
  // ---- Generic, untrusted fallbacks requested by the owner
  private static final Pattern ROUND = Pattern.compile("^Round (\\d{1,3}) started!$");
  private static final Pattern SIMPLE_WIN =
      Pattern.compile("^(?:Winner: " + NAME + "|" + NAME + " Won|You Won)[!.]?$");
  private static final Pattern SIMPLE_LOSS =
      Pattern.compile("^(?:Loser: " + NAME + "|" + NAME + " Lost|You Lost)[!.]?$");
  private static final Pattern SIMPLE_DRAW =
      Pattern.compile("^(?:Draw|Tie|It's a draw|The match ended in a draw)[!.]?$");
  private static final List<Rules> RULES = new ArrayList<>();

  static {
    register(new Minemen());
    register(new Pvphq());
  }

  private Announcements() {}

  /**
   * Extension point for server rules without modifying the combat tracker. Register during
   * initialization.
   */
  public static void register(Rules rules) {
    RULES.add(rules);
  }

  public static List<ServerCompatibility.Adapter> adapters() {
    return RULES.stream().map(Rules::metadata).toList();
  }

  public static Optional<Message> interpret(String host, String original, Origin origin) {
    if (original == null || original.length() > 4096) return Optional.empty();
    if (origin == Origin.TITLE || origin == Origin.SUBTITLE) {
      String text = normalize(original);
      return text.isEmpty() || text.length() > 160
          ? Optional.empty()
          : Optional.of(
              message(Kind.NOTE, null, null, null, "title-text", original, origin, false));
    }
    if (origin == Origin.PLAYER_CHAT || origin == Origin.DISGUISED_CHAT || origin == Origin.OVERLAY)
      return Optional.empty();
    String text = normalize(original);
    for (var rules : RULES)
      if (rules.supports(host)) {
        var found = rules.parse(original, text, origin);
        if (found.isPresent()) return found;
      }
    Matcher win = SIMPLE_WIN.matcher(text), loss = SIMPLE_LOSS.matcher(text);
    if (win.matches())
      return Optional.of(
          result(
              text.matches("You Won[!.]?") ? "local" : first(win),
              null,
              "generic-exact",
              original,
              origin,
              false,
              Scope.MATCH,
              null));
    if (loss.matches())
      return Optional.of(
          result(
              null,
              text.matches("You Lost[!.]?") ? "local" : first(loss),
              "generic-exact",
              original,
              origin,
              false,
              Scope.MATCH,
              null));
    if (SIMPLE_DRAW.matcher(text).matches())
      return Optional.of(
          new Message(
              Kind.RESULT,
              null,
              null,
              null,
              "generic-draw",
              original,
              origin,
              false,
              Scope.MATCH,
              true,
              null,
              null));
    Matcher round = ROUND.matcher(text);
    if (round.matches())
      return Optional.of(roundStart(round, "observed-round", original, origin, false));
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

  static boolean hostIs(String host, String... domains) {
    if (host == null) return false;
    String h =
        host.strip().toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "").replaceFirst("\\.$", "");
    for (String d : domains) if (h.equals(d) || h.endsWith("." + d)) return true;
    return false;
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

  private static Message result(
      String w,
      String l,
      String rule,
      String original,
      Origin origin,
      boolean trusted,
      Scope scope,
      int[] score) {
    return new Message(
        Kind.RESULT,
        w,
        l,
        null,
        rule,
        original,
        origin,
        trusted && origin == Origin.SYSTEM,
        scope,
        false,
        null,
        score);
  }

  private static Message roundStart(
      Matcher m, String rule, String original, Origin origin, boolean trusted) {
    Integer n;
    try {
      n = Integer.parseInt(m.group(1));
    } catch (RuntimeException ex) {
      n = null;
    }
    return new Message(
        Kind.ROUND_START,
        null,
        null,
        null,
        rule,
        original,
        origin,
        trusted && origin == Origin.SYSTEM,
        Scope.ROUND,
        false,
        n,
        null);
  }

  private record Minemen() implements Rules {
    public ServerCompatibility.Adapter metadata() {
      return ServerCompatibility.MINEMEN;
    }

    public boolean supports(String host) {
      return hostIs(host, "minemen.net", "minemen.club");
    }

    public Optional<Message> parse(String original, String text, Origin origin) {
      Matcher m = MM_PAIR.matcher(text);
      if (m.matches())
        return Optional.of(
            result(
                m.group(1),
                m.group(2),
                "minemen-result",
                original,
                origin,
                true,
                Scope.MATCH,
                null));
      m = MM_START.matcher(text);
      if (m.matches())
        return Optional.of(
            message(Kind.START, null, null, m.group(1), "minemen-start", original, origin, true));
      m = MM_KILL.matcher(text);
      if (m.matches())
        return Optional.of(
            message(
                Kind.KILL, m.group(2), m.group(1), null, "minemen-kill", original, origin, true));
      m = MM_ROUND_WON.matcher(text);
      if (m.matches())
        return Optional.of(
            result(
                m.group(1),
                null,
                "minemen-round-result",
                original,
                origin,
                true,
                Scope.ROUND,
                new int[] {Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))}));
      if (MM_BEGIN.matcher(text).matches())
        return Optional.of(
            message(Kind.BEGIN, null, null, null, "minemen-begin", original, origin, true));
      m = ROUND.matcher(text);
      if (m.matches()) return Optional.of(roundStart(m, "minemen-round", original, origin, true));
      return Optional.empty();
    }
  }

  private record Pvphq() implements Rules {
    public ServerCompatibility.Adapter metadata() {
      return ServerCompatibility.PVPHQ;
    }

    public boolean supports(String host) {
      return hostIs(host, "pvphq.com");
    }

    public Optional<Message> parse(String original, String text, Origin origin) {
      Matcher m = HQ_WINNER.matcher(text);
      if (m.matches())
        return Optional.of(
            result(m.group(1), null, "pvphq-result", original, origin, true, Scope.ROUND, null));
      m = HQ_LOSER.matcher(text);
      if (m.matches())
        return Optional.of(
            result(null, m.group(1), "pvphq-loser", original, origin, true, Scope.ROUND, null));
      m = HQ_OPPONENT.matcher(text);
      if (m.matches())
        return Optional.of(
            message(Kind.START, null, null, m.group(1), "pvphq-start", original, origin, true));
      m = HQ_KILL.matcher(text);
      if (m.matches())
        return Optional.of(
            message(Kind.KILL, m.group(2), m.group(1), null, "pvphq-kill", original, origin, true));
      if (HQ_REPORT.matcher(text).matches())
        return Optional.of(
            message(Kind.END, null, null, null, "pvphq-report", original, origin, true));
      if (HQ_BANNER.matcher(text).matches())
        return Optional.of(
            message(Kind.BEGIN, null, null, null, "pvphq-banner", original, origin, true));
      return Optional.empty();
    }
  }
}
