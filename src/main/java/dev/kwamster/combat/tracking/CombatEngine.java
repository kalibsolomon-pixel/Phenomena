package dev.kwamster.combat.tracking;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.fights.FightRecord.Outcome;
import dev.kwamster.combat.fights.Resolution.Classification;
import dev.kwamster.combat.fights.Resolution.Scope;
import dev.kwamster.combat.fights.Resolution.Source;
import dev.kwamster.combat.metrics.Combos;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.*;
import dev.kwamster.config.Config;
import java.util.*;
import java.util.function.*;

/**
 * Client-thread confined. Event IDs must increase, times must be monotonic.
 *
 * <p>Outcome lifecycle: evidence is offered as ranked candidates. A match-scope server result
 * finalizes the round at once. Every other endpoint (round result, kill message, death, world
 * change, match-end marker) opens a bounded grace period in which a stronger announcement can
 * upgrade the result. Contradictory evidence of equal strength becomes Unknown. A recording is
 * finalized exactly once and is never changed afterwards.
 */
public final class CombatEngine {
  public enum State {
    IDLE,
    ENGAGED,
    ENDING,
    FINALIZED
  }

  /** Evidence strength; a later candidate replaces the pending one only when stronger. */
  static final int RANK_MATCH = 5,
      RANK_ROUND = 4,
      RANK_KILL = 3,
      RANK_WEAK_RESULT = 2,
      RANK_THIRD_PARTY = 1,
      RANK_ENDPOINT = 0;

  /** Grace after a round result; PVPHQ's match report followed the last round by up to 4 s. */
  static final long ROUND_RESULT_GRACE_NANOS = 8_000_000_000L;

  private record Candidate(
      Classification classification,
      Source source,
      Confidence confidence,
      Scope scope,
      long at,
      Integer evidenceIndex,
      Long eventSequence,
      int rank,
      String reason) {}

  private final Config config;
  private final Consumer<FightRecord> recorder;
  private final BiConsumer<CombatEvent, HitQuality.Grade> feedback;
  private final Function<String, String> labeler;
  private final ArrayList<CombatEvent> events = new ArrayList<>();
  private final ArrayDeque<CombatEvent> jumpWindow = new ArrayDeque<>();
  private final Set<Long> liveJumpCandidates = new HashSet<>();
  private final ArrayDeque<CombatEvent> prelude = new ArrayDeque<>();
  private final ArrayDeque<CombatEvent> pending = new ArrayDeque<>();
  private final ArrayList<FightRecord.Evaluation> evaluations = new ArrayList<>();
  private FightRecord.Policy policy;
  private Statistics stats = new Statistics();
  private String opponent, opponentLabel;
  private Long lastConfirmedTick, lastIncomingTick;
  private long start, lastActivity, lastSequence = -1, lastTime = -1, startEpoch, lastHitNanos = -1;
  private State state = State.IDLE;
  private boolean opponentGone;
  private final List<MatchEvidence.Entry> matchEntries = new ArrayList<>();
  private String server, localPlayer, matchId, roundId, announcedOpponent;
  private Integer roundNumber;
  private long roundStart = -1, endDeadline = -1, endpointAt = -1;
  private Candidate candidate;
  private final List<String> notes = new ArrayList<>();
  private boolean ambiguousRematch;
  private String lastRoundOpponent;
  private boolean lastRoundResolved = true;
  private Announcements.Message queuedStart;
  private long matchEndMarker = -1;
  private long queuedAt;
  private final LinkedHashMap<String, Long> announcements = new LinkedHashMap<>();

  public CombatEngine(
      Config c,
      Consumer<FightRecord> recorder,
      BiConsumer<CombatEvent, HitQuality.Grade> feedback) {
    this(c, recorder, feedback, id -> null);
  }

  /** The labeler resolves a display label (entity type or player name) for a new opponent. */
  public CombatEngine(
      Config c,
      Consumer<FightRecord> recorder,
      BiConsumer<CombatEvent, HitQuality.Grade> feedback,
      Function<String, String> labeler) {
    this.config = c;
    this.recorder = recorder;
    this.feedback = feedback;
    this.labeler = labeler;
  }

  // ---- announcements -----------------------------------------------------------------------

  /** Announcement interpretation lives in adapters; the tracker only applies scoped evidence. */
  public void announcement(String host, String local, Announcements.Message message, long now) {
    if (!config.tracking || now < lastTime) return;
    boolean system = message.origin() == Announcements.Origin.SYSTEM;
    String fingerprint =
        host + ":" + message.rule() + ":" + Announcements.normalize(message.original());
    Long seen = announcements.get(fingerprint);
    if (seen != null && now - seen < 2_000_000_000L) return;
    announcements.put(fingerprint, now);
    if (announcements.size() > 128) announcements.remove(announcements.keySet().iterator().next());

    if (message.kind() == Announcements.Kind.NOTE) {
      if (engaged()) addEvidence(message, now, false);
      return;
    }
    boolean startMessage =
        message.kind() == Announcements.Kind.START
            || message.kind() == Announcements.Kind.ROUND_START;
    if (startMessage && system) {
      startRound(host, local, message, now);
      return;
    }
    boolean scopedHere =
        system
            && message.trusted()
            && namedRound()
            && Objects.equals(server, host)
            && Objects.equals(localPlayer, local);
    if (!engaged() && scopedHere && message.kind() == Announcements.Kind.END)
      matchEndMarker = now; // Remembered for the result lines that follow it.
    if (!engaged()
        && scopedHere
        && (message.kind() == Announcements.Kind.RESULT
            || message.kind() == Announcements.Kind.KILL)
        && namesParticipant(message, local)) {
      // A best-of match continues between rounds without a fight in progress (for example a
      // forfeit after a round result). Open the scoped round so its result is not lost.
      long at = roundStart;
      begin("announced:" + announcedOpponent, Math.max(at, lastTime));
    }
    if (!engaged()) return;
    if (message.kind() == Announcements.Kind.BEGIN) {
      addEvidence(message, now, Objects.equals(server, host) && roundStart >= 0);
      return;
    }
    // Early results (forfeits during the countdown) are accepted unless an earlier round's result
    // is still outstanding and could be the message that just arrived.
    boolean pairNamesNewOpponent = message.paired() && !Objects.equals(lastRoundOpponent, other());
    boolean early = roundStart >= 0 && now - roundStart < 2_000_000_000L;
    boolean context =
        system
            && Objects.equals(server, host)
            && Objects.equals(localPlayer, local)
            && (!early || queuedStart != null || lastRoundResolved || pairNamesNewOpponent);
    if (message.kind() == Announcements.Kind.END) {
      boolean fresh = endDeadline >= 0 ? now <= endDeadline : now - lastActivity <= graceNanos();
      int index = addEvidence(message, now, context && fresh);
      if (context && fresh && message.trusted()) matchEndMarker = now;
      if (context && fresh)
        endpoint(
            now,
            new Candidate(
                Classification.UNKNOWN,
                Source.ROUND_BOUNDARY,
                Confidence.OBSERVED,
                Scope.MATCH,
                now,
                index,
                null,
                RANK_ENDPOINT,
                "Server match end; awaiting result"));
      return;
    }
    if (message.kind() == Announcements.Kind.KILL) {
      kill(message, local, now, context);
      return;
    }
    result(message, local, now, context);
  }

  private boolean namesParticipant(Announcements.Message m, String local) {
    for (String n : new String[] {m.winner(), m.loser()})
      if (n != null && ("local".equals(n) || n.equals(local) || n.equals(announcedOpponent)))
        return true;
    return false;
  }

  private long graceNanos() {
    return (long) (config.resultGraceSeconds * 1e9);
  }

  private boolean namedRound() {
    return roundStart >= 0 && announcedOpponent != null;
  }

  private String other() {
    return opponentLabel != null ? opponentLabel : announcedOpponent;
  }

  private void startRound(String host, String local, Announcements.Message message, long now) {
    boolean round = message.kind() == Announcements.Kind.ROUND_START;
    String named = round ? null : message.opponent();
    // A new round of the same match keeps its announced opponent.
    boolean sameMatch =
        round && matchId != null && Objects.equals(server, host) && announcedOpponent != null;
    if (round && !sameMatch && !engaged()) {
      // A bare round marker without a named match cannot identify an opponent; keep it as context.
      server = host;
      localPlayer = local;
    }
    if (engaged() && sameMatch && candidate == null && endDeadline < 0 && !hasCombat()) {
      // "Round 1 started!" after the match block: the countdown recording simply continues.
      roundNumber = message.roundNumber();
      roundStart = now;
      addEvidence(message, now, true);
      return;
    }
    if (engaged()) {
      if (candidate == null && endDeadline < 0 && queuedStart == null && hasCombat()) {
        // No result yet: a late result for the old round may still arrive shortly. Defer the
        // boundary instead of guessing which round a delayed announcement belongs to.
        queuedStart = message;
        queuedAt = now;
        server = host;
        localPlayer = local;
        addEvidence(message, now, true);
        endpoint(
            now,
            new Candidate(
                Classification.UNKNOWN,
                Source.ROUND_BOUNDARY,
                Confidence.OBSERVED,
                Scope.ROUND,
                now,
                null,
                null,
                RANK_ENDPOINT,
                "New round boundary"),
            Math.min(graceNanos(), 2_000_000_000L));
        return;
      }
      flush(now, "New round boundary");
    }
    applyStart(host, local, message, now, sameMatch, named);
  }

  private void applyStart(
      String host,
      String local,
      Announcements.Message message,
      long now,
      boolean sameMatch,
      String named) {
    boolean round = message.kind() == Announcements.Kind.ROUND_START;
    String nextOpponent = sameMatch ? announcedOpponent : named;
    boolean rematch =
        !round
            && nextOpponent != null
            && Objects.equals(server, host)
            && Objects.equals(nextOpponent, lastRoundOpponent);
    matchEntries.clear();
    prelude.clear();
    notes.clear();
    server = host;
    localPlayer = local;
    if (!sameMatch) {
      matchId = UUID.randomUUID().toString();
      announcedOpponent = named;
    }
    roundNumber = message.roundNumber();
    roundId = UUID.randomUUID().toString();
    roundStart = now;
    matchEndMarker = -1;
    // A rematch is ambiguous only when the previous round against this opponent was never
    // resolved: an outstanding late result could then belong to either round.
    ambiguousRematch = rematch && !lastRoundResolved;
    if (rematch && !ambiguousRematch)
      notes.add("Rematch against the same opponent; the previous round was resolved first");
    addEvidence(message, now, true);
    if (announcedOpponent != null) begin("announced:" + announcedOpponent, now);
  }

  private void kill(Announcements.Message m, String local, long now, boolean context) {
    String killer = "local".equals(m.winner()) ? local : m.winner();
    String victim = "local".equals(m.loser()) ? local : m.loser();
    String other = other();
    boolean fresh = endDeadline >= 0 ? now <= endDeadline : now - lastActivity <= graceNanos() * 4;
    boolean localWins = Objects.equals(killer, local) && Objects.equals(victim, other);
    boolean localLoses = Objects.equals(victim, local) && Objects.equals(killer, other);
    boolean opponentByThird =
        Objects.equals(victim, other) && killer != null && !Objects.equals(killer, local);
    boolean localByThird =
        Objects.equals(victim, local) && killer != null && !Objects.equals(killer, other);
    boolean accepted =
        context
            && fresh
            && m.trusted()
            && (localWins || localLoses || opponentByThird || localByThird);
    int index = addEvidence(m, now, accepted);
    if (!accepted) return;
    if (localWins || localLoses)
      endpoint(
          now,
          new Candidate(
              localWins ? Classification.INFERRED_WIN : Classification.INFERRED_LOSS,
              Source.SERVER_KILL_MESSAGE,
              Confidence.INFERRED,
              namedRound() ? Scope.ROUND : Scope.ENGAGEMENT,
              now,
              index,
              null,
              RANK_KILL,
              "Server kill message / " + m.rule()));
    else if (opponentByThird)
      endpoint(
          now,
          new Candidate(
              Classification.UNKNOWN,
              Source.THIRD_PARTY_KILL,
              Confidence.OBSERVED,
              Scope.ENGAGEMENT,
              now,
              index,
              null,
              RANK_THIRD_PARTY,
              "Opponent killed by a third party / " + m.rule()));
    else
      endpoint(
          now,
          new Candidate(
              Classification.INFERRED_LOSS,
              Source.SERVER_KILL_MESSAGE,
              Confidence.INFERRED,
              Scope.ENGAGEMENT,
              now,
              index,
              null,
              RANK_WEAK_RESULT,
              "Killed by a third party / " + m.rule()));
  }

  private void result(Announcements.Message m, String local, long now, boolean context) {
    String other = other();
    String winner = "local".equals(m.winner()) ? local : m.winner();
    String loser = "local".equals(m.loser()) ? local : m.loser();
    boolean pair = winner != null && loser != null;
    boolean participants =
        m.draw()
            || (pair
                ? (Objects.equals(winner, local) && Objects.equals(loser, other))
                    || (Objects.equals(loser, local) && Objects.equals(winner, other))
                : Objects.equals(winner, local)
                    || Objects.equals(winner, other)
                    || Objects.equals(loser, local)
                    || Objects.equals(loser, other));
    boolean named = namedRound();
    boolean fresh =
        endDeadline >= 0
            ? now <= endDeadline
            : now - lastActivity
                <= ((pair || (m.trusted() && named)) && roundStart >= 0
                    ? config.serverInactivitySeconds * 1e9
                    : graceNanos());
    // A one-name trusted result identifies both participants only inside a round whose start named
    // the opponent. Generic text always needs independent endpoint evidence.
    boolean scoped = m.trusted() ? pair || named || endDeadline >= 0 : endDeadline >= 0;
    boolean rematchOk = !ambiguousRematch || endDeadline >= 0;
    String scoreNote = null;
    boolean scoreOk = true;
    if (m.scope() == Scope.ROUND && m.score() != null && roundNumber != null) {
      int total = m.score()[0] + m.score()[1];
      scoreOk = total == roundNumber;
      if (!scoreOk) scoreNote = "Round score " + total + " does not match round " + roundNumber;
    }
    boolean accepted = context && fresh && participants && scoped && rematchOk && scoreOk;
    int index = addEvidence(m, now, accepted);
    if (scoreNote != null) notes.add(scoreNote);
    if (!accepted) return;
    Classification c;
    if (m.draw()) c = Classification.DRAW;
    else {
      boolean win = Objects.equals(winner, local) || Objects.equals(loser, other);
      boolean confirmed = m.trusted() && (pair || named) && !ambiguousRematch;
      c =
          win
              ? confirmed ? Classification.CONFIRMED_WIN : Classification.INFERRED_WIN
              : confirmed ? Classification.CONFIRMED_LOSS : Classification.INFERRED_LOSS;
    }
    boolean confirmed = c == Classification.CONFIRMED_WIN || c == Classification.CONFIRMED_LOSS;
    // A trusted round-scoped line right after the server's match-end marker is the match result.
    boolean round =
        m.scope() == Scope.ROUND
            && !(m.trusted() && matchEndMarker >= 0 && now - matchEndMarker <= 2_000_000_000L);
    var cand =
        new Candidate(
            c,
            !m.trusted()
                ? Source.GENERIC_RESULT
                : round ? Source.SERVER_ROUND_RESULT : Source.SERVER_MATCH_RESULT,
            confirmed ? Confidence.CONFIRMED : Confidence.INFERRED,
            round ? Scope.ROUND : named || pair ? Scope.MATCH : Scope.ENGAGEMENT,
            now,
            index,
            null,
            !m.trusted() ? RANK_WEAK_RESULT : round ? RANK_ROUND : RANK_MATCH,
            "Server " + m.kind().name().toLowerCase(Locale.ROOT) + " / " + m.rule());
    // A round result waits longer: the server's match result often follows within seconds and
    // should upgrade this recording rather than start an empty one. The next attack or round
    // start ends the wait early.
    if (round) endpoint(now, cand, Math.max(graceNanos(), ROUND_RESULT_GRACE_NANOS));
    else {
      offer(cand);
      flush(now, cand.reason());
    }
  }

  private int addEvidence(Announcements.Message m, long now, boolean accepted) {
    if (matchEntries.size() >= 64) return -1;
    matchEntries.add(
        new MatchEvidence.Entry(
            now, m.kind().name(), m.rule(), m.origin().name(), m.original(), accepted));
    return matchEntries.size() - 1;
  }

  // ---- result candidates -------------------------------------------------------------------

  private static boolean decisive(Classification c) {
    return c.outcome() == Outcome.WIN || c.outcome() == Outcome.LOSS || c == Classification.DRAW;
  }

  private void offer(Candidate c) {
    if (candidate == null) {
      candidate = c;
      return;
    }
    boolean contradicts =
        decisive(c.classification())
            && decisive(candidate.classification())
            && c.classification().outcome() != candidate.classification().outcome();
    if (c.rank() > candidate.rank()) {
      if (contradicts)
        notes.add(
            c.source().label
                + " ("
                + c.classification().label
                + ") overrode weaker "
                + candidate.source().label
                + " ("
                + candidate.classification().label
                + ")");
      candidate = c;
    } else if (c.rank() == candidate.rank() && contradicts) {
      notes.add(
          "Contradictory "
              + c.source().label
              + ": "
              + candidate.classification().label
              + " vs "
              + c.classification().label);
      candidate =
          new Candidate(
              Classification.UNKNOWN,
              Source.CONFLICT,
              Confidence.OBSERVED,
              candidate.scope(),
              c.at(),
              c.evidenceIndex(),
              c.eventSequence(),
              c.rank(),
              "Contradictory server evidence");
    } else if (contradicts)
      notes.add(
          "Weaker contradictory " + c.source().label + " ignored: " + c.classification().label);
  }

  private void endpoint(long now, Candidate c) {
    endpoint(now, c, graceNanos());
  }

  private void endpoint(long now, Candidate c, long grace) {
    if (!engaged()) return;
    var before = candidate;
    offer(c);
    if (endDeadline < 0) {
      lastActivity = Math.max(lastActivity, now);
      endDeadline = now + grace;
      endpointAt = now;
      state = State.ENDING;
    } else if (candidate != before && candidate == c) {
      // Only stronger evidence extends the wait; rank strictly increases, so this is bounded.
      endDeadline = Math.max(endDeadline, now + grace);
    }
  }

  /** Finalizes with the strongest pending candidate, then applies a deferred round start. */
  private void flush(long now, String fallbackReason) {
    if (!engaged()) return;
    var c =
        candidate != null
            ? candidate
            : new Candidate(
                Classification.UNKNOWN,
                Source.ROUND_BOUNDARY,
                Confidence.OBSERVED,
                Scope.ROUND,
                now,
                null,
                null,
                RANK_ENDPOINT,
                fallbackReason);
    finalizeWith(now, c);
    var q = queuedStart;
    if (q != null) {
      queuedStart = null;
      applyStart(
          server,
          localPlayer,
          q,
          queuedAt,
          q.kind() == Announcements.Kind.ROUND_START
              && matchId != null
              && announcedOpponent != null,
          q.kind() == Announcements.Kind.START ? q.opponent() : null);
    }
  }

  // ---- lifecycle helpers --------------------------------------------------------------------

  public void context(String host, String local) {
    if (!engaged()) {
      if (!Objects.equals(server, host) || !Objects.equals(localPlayer, local)) {
        roundStart = -1;
        announcedOpponent = null;
        matchId = null;
        ambiguousRematch = false;
        lastRoundOpponent = null;
        lastRoundResolved = true;
        matchEntries.clear();
      }
      server = host;
      localPlayer = local;
    }
  }

  public State state() {
    return state;
  }

  public boolean engaged() {
    return state == State.ENGAGED || state == State.ENDING;
  }

  public Statistics stats() {
    return stats;
  }

  public boolean perfectReachEligible(Attack attack) {
    return policy != null
        && PerfectReach.eligible(
            attack, policy.perfectReachTarget(), policy.perfectReachTolerance());
  }

  public String opponent() {
    return opponent;
  }

  public boolean acceptsTarget(String id, String name) {
    return !engaged()
        || roundStart < 0
        || Objects.equals(id, opponent)
        || (opponent != null
            && opponent.startsWith("announced:")
            && Objects.equals(name, announcedOpponent))
        || (queuedStart != null
            && queuedStart.opponent() != null
            && Objects.equals(name, queuedStart.opponent()));
  }

  // ---- combat events ------------------------------------------------------------------------

  public void accept(CombatEvent e) {
    if (!config.tracking || e.sequence() <= lastSequence || e.nanoTime() < lastTime) return;
    lastSequence = e.sequence();
    lastTime = e.nanoTime();
    if (e.kind() == Kind.DISCONNECT) {
      if (engaged())
        endpoint(
            e.nanoTime(),
            new Candidate(
                Classification.INTERRUPTED,
                Source.DISCONNECT,
                Confidence.OBSERVED,
                Scope.ENGAGEMENT,
                e.nanoTime(),
                null,
                e.sequence(),
                RANK_ENDPOINT,
                "Disconnected: " + e.detail()));
      return;
    }
    if (engaged() && endDeadline >= 0 && e.kind() == Kind.ATTACK) flush(e.nanoTime(), "Ended");
    if (!engaged()) {
      if (e.kind() == Kind.JUMP || e.kind() == Kind.JUMP_INPUT) {
        if (prelude.size() == 32) prelude.removeFirst();
        prelude.addLast(e);
        return;
      }
      if (e.kind() == Kind.ATTACK && e.target() != null) begin(e.target(), e.nanoTime());
      else if (e.kind() == Kind.DAMAGE_IN
          && e.actor() != null
          && (e.context() == null
              || e.context().attribution() == null
              || e.context().attribution() != Attribution.UNATTRIBUTED))
        begin(e.actor(), e.nanoTime());
      else return;
    }
    if (opponent != null && opponent.startsWith("announced:")) {
      String id =
          e.kind() == Kind.ATTACK ? e.target() : e.kind() == Kind.DAMAGE_IN ? e.actor() : null;
      if (id != null && Objects.equals(labeler.apply(id), announcedOpponent)) {
        opponent = id;
        opponentLabel = labeler.apply(id);
      }
    }
    if (e.kind() == Kind.ATTACK && e.target() != null && !e.target().equals(opponent)) {
      if (roundStart >= 0) return; // A server-scoped duel cannot silently change participants.
      if (opponentGone)
        finalizeWith(
            e.nanoTime(),
            new Candidate(
                Classification.UNKNOWN,
                Source.OPPONENT_REMOVED,
                Confidence.OBSERVED,
                Scope.ENGAGEMENT,
                e.nanoTime(),
                null,
                null,
                RANK_ENDPOINT,
                "Opponent died or left; new target"));
      else
        finalizeWith(
            e.nanoTime(),
            new Candidate(
                Classification.INTERRUPTED,
                Source.TARGET_CHANGE,
                Confidence.OBSERVED,
                Scope.ENGAGEMENT,
                e.nanoTime(),
                null,
                e.sequence(),
                RANK_ENDPOINT,
                "Target changed"));
      begin(e.target(), e.nanoTime());
    }
    if (e.kind() == Kind.HURT && !Objects.equals(e.target(), opponent)) return;
    if ((e.kind() == Kind.REMOVED || e.kind() == Kind.CRIT)
        && e.target() != null
        && !"local".equals(e.target())
        && !Objects.equals(e.target(), opponent)) return;
    // One server damage observation per actor and tick: duplicates cannot double count.
    if (e.kind() == Kind.DAMAGE_IN
        && e.attributedTo(opponent)
        && e.clientTick() != null
        && Objects.equals(lastIncomingTick, e.clientTick())) return;
    if (events.size() >= 9000
        && (e.kind() == Kind.MOTION
            || e.kind() == Kind.JUMP_INPUT
            || e.kind() == Kind.VELOCITY
            || e.kind() == Kind.HEALTH)) return;
    if (events.size() >= 10000) {
      finalizeWith(
          e.nanoTime(),
          new Candidate(
              Classification.INTERRUPTED,
              Source.EVENT_LIMIT,
              Confidence.OBSERVED,
              Scope.ENGAGEMENT,
              e.nanoTime(),
              null,
              e.sequence(),
              RANK_ENDPOINT,
              "Event limit"));
      return;
    }
    events.add(e);
    if (e.kind() == Kind.JUMP || e.attributedTo(opponent)) {
      if (jumpWindow.size() == 128) jumpWindow.removeFirst();
      jumpWindow.addLast(e);
      while (!jumpWindow.isEmpty()
          && e.clientTick() != null
          && jumpWindow.peekFirst().clientTick() != null
          && e.clientTick() - jumpWindow.peekFirst().clientTick() > 20) jumpWindow.removeFirst();
    }
    expirePending(e.nanoTime());
    switch (e.kind()) {
      case ATTACK -> {
        boolean empty =
            e.attack() == null || (e.detail() != null && e.detail().startsWith("empty-swing"));
        stats.attempt(e.attack(), empty);
        if (!empty && e.context() != null && Boolean.TRUE.equals(e.context().targetBlocking()))
          stats.attacksIntoShield++;
        if (e.attack() != null && !empty) {
          if (pending.size() == 64) pending.removeFirst();
          pending.addLast(e);
        }
        lastActivity = e.nanoTime();
        state = State.ENGAGED;
      }
      case HURT -> confirm(e);
      case DAMAGE_IN -> {
        if (e.attributedTo(opponent)) {
          lastIncomingTick = e.clientTick();
          stats.incoming(e.nanoTime());
          updateJumpCandidates();
          lastActivity = e.nanoTime();
          state = State.ENGAGED;
        } else if (e.actor() != null) stats.thirdPartyIncoming++;
        else stats.unattributedIncoming++;
      }
      case SHIELD -> shield(e);
      case CRIT -> {
        String subject = e.context() == null ? null : e.context().subject();
        if ("opponent".equals(subject)) {
          boolean recentHit =
              lastConfirmedTick != null
                  && e.clientTick() != null
                  && Math.abs(e.clientTick() - lastConfirmedTick) <= 1;
          if (recentHit || !pending.isEmpty()) stats.serverCrits++;
        } else if ("local".equals(subject)) stats.opponentCrits++;
      }
      case HEALTH -> {}
      case REMOVED -> {
        if (Objects.equals(e.target(), opponent)) opponentGone = true;
      }
      case JUMP -> updateJumpCandidates();
      case DEATH -> {
        if ("local".equals(e.target()))
          endpoint(
              e.nanoTime(),
              new Candidate(
                  Classification.INFERRED_LOSS,
                  Source.LOCAL_DEATH,
                  Confidence.INFERRED,
                  namedRound() ? Scope.ROUND : Scope.ENGAGEMENT,
                  e.nanoTime(),
                  null,
                  e.sequence(),
                  RANK_WEAK_RESULT,
                  "Local death observed; killer not confirmed"));
        else if (Objects.equals(e.target(), opponent)) {
          opponentGone = true;
          endpoint(
              e.nanoTime(),
              new Candidate(
                  Classification.UNKNOWN,
                  Source.OPPONENT_DEATH,
                  Confidence.OBSERVED,
                  Scope.ENGAGEMENT,
                  e.nanoTime(),
                  null,
                  e.sequence(),
                  RANK_ENDPOINT,
                  "Opponent death observed; kill attribution unknown"));
        }
      }
      default -> {}
    }
  }

  private void expirePending(long now) {
    while (!pending.isEmpty()
        && now - pending.peekFirst().nanoTime() > policy.correlationMillis() * 1e6) {
      var expired = pending.removeFirst();
      var ctx = expired.context();
      // Blocking, facing target and no damage report: possibly blocked, never confirmed.
      if (ctx != null
          && Boolean.TRUE.equals(ctx.targetBlocking())
          && Boolean.TRUE.equals(ctx.targetFacing())) stats.estimatedShieldBlocks++;
    }
  }

  private CombatEvent matchPending(String target) {
    for (var p : pending) if (Objects.equals(p.target(), target)) return p;
    return null;
  }

  private void confirm(CombatEvent e) {
    if (e.clientTick() != null && Objects.equals(lastConfirmedTick, e.clientTick())) return;
    if (e.confidence() != Confidence.CONFIRMED
        || !"local".equals(e.actor())
        || !"local-melee-source".equals(e.detail())) return;
    CombatEvent match = matchPending(e.target());
    if (match == null) return;
    pending.remove(match);
    lastConfirmedTick = e.clientTick();
    stats.hit(match.attack(), match.nanoTime(), e.nanoTime(), policy.comboSeconds());
    if (PerfectReach.eligible(
        match.attack(), policy.perfectReachTarget(), policy.perfectReachTolerance()))
      stats.perfectReachHits++;
    lastActivity = e.nanoTime();
    lastHitNanos = e.nanoTime();
    state = State.ENGAGED;
    var grade =
        match.attack().measurement() == null
            ? HitQuality.classify(match.attack(), policy.perfectCooldown(), policy.greatCooldown())
            : null;
    evaluations.add(
        new FightRecord.Evaluation(
            match.sequence(),
            e.sequence(),
            grade,
            Confidence.INFERRED,
            stats.currentCombo,
            stats.momentum,
            FightRecord.Evaluation.HIT));
    feedback.accept(match, grade);
  }

  /**
   * Shield sounds. An opponent BLOCK or DISABLE consumes the oldest pending local attack against
   * the opponent within the correlation window; vanilla 1.21.11 sends no damage event for a blocked
   * hit, so the attack is no longer uncertain. Local blocks are defensive observations whose
   * attacker is not reported.
   */
  private void shield(CombatEvent e) {
    var ctx = e.context();
    if (ctx == null) return;
    boolean disable = "DISABLE".equals(ctx.shield());
    if ("opponent".equals(ctx.subject())) {
      CombatEvent match = matchPending(opponent);
      if (match == null) return;
      pending.remove(match);
      stats.blocked();
      if (disable) stats.shieldDisables++;
      lastActivity = e.nanoTime();
      evaluations.add(
          new FightRecord.Evaluation(
              match.sequence(),
              e.sequence(),
              null,
              Confidence.INFERRED,
              0,
              stats.momentum,
              disable
                  ? FightRecord.Evaluation.SHIELD_DISABLE
                  : FightRecord.Evaluation.SHIELD_BLOCK));
    } else if ("local".equals(ctx.subject())) {
      if (disable) stats.localShieldDisabled++;
      else stats.localShieldBlocks++;
      lastActivity = e.nanoTime();
    }
  }

  private void updateJumpCandidates() {
    for (var j : JumpResets.analyze(List.copyOf(jumpWindow), policy.jumpWindowTicks(), opponent))
      if (j.grade() == JumpResets.Grade.IDEAL_CANDIDATE
          || j.grade() == JumpResets.Grade.ATTEMPT_CANDIDATE)
        liveJumpCandidates.add(j.jumpSequence());
    stats.jumpCandidates = liveJumpCandidates.size();
  }

  private void begin(String target, long time) {
    opponent = target;
    opponentLabel = target.startsWith("announced:") ? announcedOpponent : labeler.apply(target);
    // Announced names are checked against resolved entity names before attaching a round.
    if (announcedOpponent != null && !Objects.equals(announcedOpponent, opponentLabel)) {
      roundStart = -1;
      announcedOpponent = null;
      matchId = null;
      matchEntries.clear();
      roundId = null;
      notes.clear();
    }
    endDeadline = -1;
    endpointAt = -1;
    candidate = null;
    opponentGone = false;
    if (roundStart < 0) {
      matchEntries.clear();
      roundId = null;
      matchId = null;
      roundNumber = null;
      notes.clear();
      ambiguousRematch = false;
    }
    start = lastActivity = time;
    startEpoch = System.currentTimeMillis();
    stats = new Statistics();
    events.clear();
    for (var e : prelude) if (time - e.nanoTime() <= 250_000_000L) events.add(e);
    prelude.clear();
    jumpWindow.clear();
    jumpWindow.addAll(events);
    liveJumpCandidates.clear();
    if (!events.isEmpty()) {
      start = events.getFirst().nanoTime();
      startEpoch -= (time - start) / 1_000_000;
    }
    evaluations.clear();
    policy = FightRecord.Policy.of(config);
    pending.clear();
    lastHitNanos = -1;
    lastConfirmedTick = null;
    lastIncomingTick = null;
    state = State.ENGAGED;
  }

  public void tick(long now) {
    if (!engaged()) return;
    if (stats.lastHit > 0 && now - stats.lastHit > policy.comboSeconds() * 1e9)
      stats.currentCombo = 0;
    if (endDeadline >= 0) {
      if (now >= endDeadline) flush(now, "Ended");
      return;
    }
    double inactivity = roundStart >= 0 ? config.serverInactivitySeconds : config.inactivitySeconds;
    if (now - lastActivity > inactivity * 1e9)
      finalizeWith(
          now,
          new Candidate(
              Classification.UNKNOWN,
              Source.INACTIVITY,
              Confidence.OBSERVED,
              Scope.ENGAGEMENT,
              now,
              null,
              null,
              RANK_ENDPOINT,
              "Inactivity"));
    else if (now - lastActivity > inactivity * .75e9) state = State.ENDING;
  }

  /**
   * External finalization (client stop, configuration reset, tests). A pending result already
   * established by evidence is kept; the external reason is noted.
   */
  public void finish(long now, Outcome outcome, String reason) {
    if (!engaged()) return;
    if (candidate != null && candidate.rank() > RANK_ENDPOINT) {
      notes.add("Finalized early: " + reason);
      finalizeWith(now, candidate);
      return;
    }
    finalizeWith(
        now,
        new Candidate(
            Classification.of(outcome, false),
            reason != null && reason.startsWith("Client") ? Source.CLIENT : Source.EXTERNAL,
            outcome == Outcome.WIN || outcome == Outcome.LOSS
                ? Confidence.INFERRED
                : Confidence.OBSERVED,
            Scope.ENGAGEMENT,
            now,
            null,
            null,
            RANK_ENDPOINT,
            reason));
  }

  private boolean hasCombat() {
    for (var e : events)
      if (e.kind() == Kind.ATTACK || e.kind() == Kind.HURT || e.kind() == Kind.DAMAGE_IN)
        return true;
    return false;
  }

  private void finalizeWith(long now, Candidate c) {
    if (!engaged()) return;
    state = State.FINALIZED;
    if (!hasCombat() && !decisive(c.classification()) && roundStart >= 0) {
      // An announced round with no combat and no result measured nothing; do not record it.
      endDeadline = -1;
      endpointAt = -1;
      candidate = null;
      matchEndMarker = -1;
      pending.clear();
      if (queuedStart == null && c.scope() != Scope.ROUND) {
        roundStart = -1;
        announcedOpponent = null;
      }
      return;
    }
    stats.currentCombo = 0;
    expirePending(Long.MAX_VALUE);
    stats.jumpCandidates =
        (int)
            JumpResets.analyze(events, policy.jumpWindowTicks(), opponent).stream()
                .filter(
                    j ->
                        j.grade() == JumpResets.Grade.IDEAL_CANDIDATE
                            || j.grade() == JumpResets.Grade.ATTEMPT_CANDIDATE)
                .count();
    var combos = Combos.analyze(events, evaluations, opponent, policy.comboSeconds());
    stats.longestCombo = combos.longest(Combos.Side.LOCAL);
    stats.comboCount = combos.combos(Combos.Side.LOCAL).size();
    stats.comboHits = combos.comboHits(Combos.Side.LOCAL);
    stats.longestOpponentCombo = combos.longest(Combos.Side.OPPONENT);
    stats.longestUnanswered = combos.longestUnansweredLocal();
    stats.damageTaken = damageTaken();
    var resolution =
        new Resolution(
            c.classification(),
            c.source(),
            c.confidence(),
            c.scope(),
            c.at(),
            c.evidenceIndex() == null || c.evidenceIndex() < 0 ? null : c.evidenceIndex(),
            c.eventSequence(),
            List.copyOf(notes),
            false);
    recorder.accept(
        new FightRecord(
            FightRecord.SCHEMA,
            UUID.randomUUID().toString(),
            startEpoch,
            System.currentTimeMillis(),
            Math.max(0, now - start),
            opponent,
            c.classification().outcome(),
            c.reason(),
            stats.copy(),
            List.copyOf(events),
            policy,
            List.copyOf(evaluations),
            opponentLabel,
            new MatchEvidence(
                server,
                localPlayer,
                roundId,
                c.confidence(),
                List.copyOf(matchEntries),
                start,
                matchId,
                roundNumber),
            resolution));
    lastRoundOpponent = opponentLabel != null ? opponentLabel : announcedOpponent;
    lastRoundResolved = decisive(c.classification());
    endDeadline = -1;
    endpointAt = -1;
    candidate = null;
    matchEndMarker = -1;
    pending.clear();
    if (queuedStart == null) {
      if (c.scope() == Scope.ROUND && announcedOpponent != null && matchId != null) {
        // A best-of match continues: the next engagement belongs to the same scoped match even
        // when the server sends no round-start message (PVPHQ best-of formats).
        roundStart = now;
        roundId = UUID.randomUUID().toString();
        roundNumber = roundNumber == null ? null : roundNumber + 1;
        matchEntries.clear();
        notes.clear();
        ambiguousRematch = false;
      } else {
        roundStart = -1;
        announcedOpponent = null;
      }
    }
  }

  /** Observed decreases of local health plus absorption during the recording. */
  private double damageTaken() {
    double total = 0;
    Double previous = null;
    for (var e : events) {
      if (e.kind() != Kind.HEALTH || e.context() == null || e.context().health() == null) continue;
      double v =
          e.context().health() + (e.context().absorption() == null ? 0 : e.context().absorption());
      if (previous != null && v < previous) total += previous - v;
      previous = v;
    }
    return total;
  }
}
