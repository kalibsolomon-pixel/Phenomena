package dev.kwamster.combat.tracking;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.fights.FightRecord.Outcome;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.*;
import dev.kwamster.config.Config;
import java.util.*;
import java.util.function.*;

/** Client-thread confined. Event IDs must increase, times must be monotonic. */
public final class CombatEngine {
  public enum State {
    IDLE,
    ENGAGED,
    ENDING,
    FINALIZED
  }

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
  private Long lastConfirmedTick;
  private long start, lastActivity, lastSequence = -1, lastTime = -1, startEpoch, lastDamage = -1;
  private State state = State.IDLE;
  private final List<MatchEvidence.Entry> matchEntries = new ArrayList<>();
  private String server, localPlayer, roundId, announcedOpponent;
  private long roundStart = -1, endDeadline = -1;
  private Outcome pendingOutcome;
  private String pendingReason;
  private boolean ambiguousRematch;
  private Confidence outcomeConfidence = Confidence.OBSERVED;
  private final LinkedHashMap<String, Long> announcements = new LinkedHashMap<>();

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
    boolean startMessage =
        message.kind() == Announcements.Kind.START
            || message.kind() == Announcements.Kind.ROUND_START;
    if (startMessage && system) {
      boolean repeatedParticipants =
          roundId != null
              && Objects.equals(server, host)
              && message.opponent() != null
              && Objects.equals(message.opponent(), opponentLabel);
      if (engaged())
        finish(
            now, pendingOutcome == null ? Outcome.UNKNOWN : pendingOutcome, "New round boundary");
      matchEntries.clear();
      prelude.clear();
      server = host;
      localPlayer = local;
      announcedOpponent = message.opponent();
      roundId = UUID.randomUUID().toString();
      roundStart = now;
      ambiguousRematch = repeatedParticipants;
      outcomeConfidence = Confidence.OBSERVED;
      addEvidence(message, now, true);
      if (announcedOpponent != null) begin("announced:" + announcedOpponent, now);
      return;
    }
    if (!engaged()) return;

    boolean context =
        system
            && Objects.equals(server, host)
            && Objects.equals(localPlayer, local)
            && (roundStart < 0 || now - roundStart >= 2_000_000_000L);
    String other = opponentLabel;
    String winner = "local".equals(message.winner()) ? local : message.winner();
    String loser = "local".equals(message.loser()) ? local : message.loser();
    boolean pair = winner != null && loser != null;
    boolean participants =
        pair
            ? (Objects.equals(winner, local) && Objects.equals(loser, other))
                || (Objects.equals(loser, local) && Objects.equals(winner, other))
            : roundStart >= 0
                && (Objects.equals(winner, local)
                    || Objects.equals(winner, other)
                    || Objects.equals(loser, local)
                    || Objects.equals(loser, other));
    boolean fresh =
        endDeadline >= 0
            ? now <= endDeadline
            : now - lastActivity
                <= (pair && roundStart >= 0
                        ? config.serverInactivitySeconds
                        : config.resultGraceSeconds)
                    * 1e9;
    // A one-name result cannot identify the opponent. Require independent endpoint evidence.
    boolean scoped = pair || endDeadline >= 0;
    boolean accepted =
        context && fresh && participants && scoped && (!ambiguousRematch || endDeadline >= 0);
    if (message.kind() == Announcements.Kind.END) {
      addEvidence(message, now, context && fresh);
      if (context && fresh)
        requestFinish(now, Outcome.UNKNOWN, "Server match end; awaiting result");
      return;
    }
    addEvidence(message, now, accepted);
    if (!accepted) return;
    Outcome result =
        Objects.equals(winner, local) || Objects.equals(loser, other) ? Outcome.WIN : Outcome.LOSS;
    outcomeConfidence =
        message.trusted() && pair && !ambiguousRematch ? Confidence.CONFIRMED : Confidence.INFERRED;
    finish(
        now,
        result,
        "Server " + message.kind().name().toLowerCase(Locale.ROOT) + " / " + message.rule());
    roundStart = -1;
    announcedOpponent = null;
  }

  private void addEvidence(Announcements.Message m, long now, boolean accepted) {
    if (matchEntries.size() < 64)
      matchEntries.add(
          new MatchEvidence.Entry(
              now, m.kind().name(), m.rule(), m.origin().name(), m.original(), accepted));
  }

  private void requestFinish(long now, Outcome outcome, String reason) {
    if (!engaged() || endDeadline >= 0) return;
    lastActivity = Math.max(lastActivity, now);
    endDeadline = now + (long) (config.resultGraceSeconds * 1e9);
    pendingOutcome = outcome;
    pendingReason = reason;
    state = State.ENDING;
  }

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

  public void context(String host, String local) {
    if (!engaged()) {
      if (!Objects.equals(server, host) || !Objects.equals(localPlayer, local)) {
        roundStart = -1;
        announcedOpponent = null;
        ambiguousRematch = false;
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
            && Objects.equals(name, announcedOpponent));
  }

  public void accept(CombatEvent e) {
    if (!config.tracking || e.sequence() <= lastSequence || e.nanoTime() < lastTime) return;
    lastSequence = e.sequence();
    lastTime = e.nanoTime();
    if (e.kind() == Kind.DISCONNECT) {
      if (engaged())
        requestFinish(e.nanoTime(), Outcome.INTERRUPTED, "Disconnected: " + e.detail());
      return;
    }
    if (engaged() && endDeadline >= 0 && e.kind() == Kind.ATTACK) {
      finish(e.nanoTime(), pendingOutcome, pendingReason);
      roundStart = -1;
      announcedOpponent = null;
    }
    if (!engaged()) {
      if (e.kind() == Kind.JUMP || e.kind() == Kind.JUMP_INPUT) {
        if (prelude.size() == 32) prelude.removeFirst();
        prelude.addLast(e);
        return;
      }
      if (e.kind() == Kind.ATTACK && e.target() != null) begin(e.target(), e.nanoTime());
      else if (e.kind() == Kind.DAMAGE_IN && e.actor() != null) begin(e.actor(), e.nanoTime());
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
      finish(e.nanoTime(), Outcome.INTERRUPTED, "Target changed");
      begin(e.target(), e.nanoTime());
    }
    if (e.kind() == Kind.HURT && !Objects.equals(e.target(), opponent)) return;
    if (e.kind() == Kind.DAMAGE_IN && e.actor() != null && !Objects.equals(e.actor(), opponent))
      return;
    if (events.size() >= 9000
        && (e.kind() == Kind.MOTION || e.kind() == Kind.JUMP_INPUT || e.kind() == Kind.VELOCITY))
      return;
    if (events.size() >= 10000) {
      finish(e.nanoTime(), Outcome.INTERRUPTED, "Event limit");
      return;
    }
    events.add(e);
    if (e.kind() == Kind.JUMP || e.kind() == Kind.DAMAGE_IN) {
      if (jumpWindow.size() == 128) jumpWindow.removeFirst();
      jumpWindow.addLast(e);
      while (!jumpWindow.isEmpty()
          && e.clientTick() != null
          && jumpWindow.peekFirst().clientTick() != null
          && e.clientTick() - jumpWindow.peekFirst().clientTick() > 20) jumpWindow.removeFirst();
    }
    while (!pending.isEmpty()
        && e.nanoTime() - pending.peekFirst().nanoTime() > policy.correlationMillis() * 1e6)
      pending.removeFirst();
    switch (e.kind()) {
      case ATTACK -> {
        boolean empty =
            e.attack() == null || (e.detail() != null && e.detail().startsWith("empty-swing"));
        stats.attempt(e.attack(), empty);
        if (e.attack() != null && !empty) {
          if (pending.size() == 64) pending.removeFirst();
          pending.addLast(e);
        }
        lastActivity = e.nanoTime();
        state = State.ENGAGED;
      }
      case HURT -> {
        if (e.clientTick() != null && Objects.equals(lastConfirmedTick, e.clientTick())) break;
        if (e.confidence() == Confidence.CONFIRMED
            && "local".equals(e.actor())
            && "local-melee-source".equals(e.detail())) {
          CombatEvent match = null;
          for (var p : pending) {
            if (Objects.equals(p.target(), e.target())) {
              match = p;
              break;
            }
          }
          if (match != null) {
            pending.remove(match);
            lastConfirmedTick = e.clientTick();
            stats.hit(match.attack(), match.nanoTime(), e.nanoTime(), policy.comboSeconds());
            if (PerfectReach.eligible(
                match.attack(), policy.perfectReachTarget(), policy.perfectReachTolerance()))
              stats.perfectReachHits++;
            lastActivity = e.nanoTime();
            state = State.ENGAGED;
            var grade =
                match.attack().measurement() == null
                    ? HitQuality.classify(
                        match.attack(), policy.perfectCooldown(), policy.greatCooldown())
                    : null;
            evaluations.add(
                new FightRecord.Evaluation(
                    match.sequence(),
                    e.sequence(),
                    grade,
                    Confidence.INFERRED,
                    stats.currentCombo,
                    stats.momentum));
            feedback.accept(match, grade);
          }
        }
      }
      case DAMAGE_IN -> {
        stats.incoming(e.nanoTime());
        updateJumpCandidates();
        lastDamage = e.nanoTime();
        lastActivity = e.nanoTime();
        state = State.ENGAGED;
      }
      case JUMP -> updateJumpCandidates();
      case DEATH -> {
        if ("local".equals(e.target()))
          requestFinish(e.nanoTime(), Outcome.LOSS, "Local death observed; attribution unknown");
        else if (Objects.equals(e.target(), opponent))
          requestFinish(
              e.nanoTime(), Outcome.UNKNOWN, "Opponent death observed; kill attribution unknown");
      }
      default -> {}
    }
  }

  private void updateJumpCandidates() {
    for (var j : JumpResets.analyze(List.copyOf(jumpWindow), policy.jumpWindowTicks()))
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
      matchEntries.clear();
      roundId = null;
    }
    endDeadline = -1;
    pendingOutcome = null;
    pendingReason = null;
    if (roundStart < 0) {
      matchEntries.clear();
      roundId = null;
      outcomeConfidence = Confidence.OBSERVED;
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
    lastDamage = -1;
    lastConfirmedTick = null;
    state = State.ENGAGED;
  }

  public void tick(long now) {
    if (!engaged()) return;
    if (stats.lastHit > 0 && now - stats.lastHit > policy.comboSeconds() * 1e9)
      stats.currentCombo = 0;
    if (endDeadline >= 0) {
      if (now >= endDeadline) {
        finish(now, pendingOutcome, pendingReason);
        roundStart = -1;
        announcedOpponent = null;
      }
      return;
    }
    double inactivity = roundStart >= 0 ? config.serverInactivitySeconds : config.inactivitySeconds;
    if (now - lastActivity > inactivity * 1e9) finish(now, Outcome.UNKNOWN, "Inactivity");
    else if (now - lastActivity > inactivity * .75e9) state = State.ENDING;
  }

  public void finish(long now, Outcome outcome, String reason) {
    if (!engaged()) return;
    state = State.FINALIZED;
    stats.currentCombo = 0;
    stats.jumpCandidates =
        (int)
            JumpResets.analyze(events, policy.jumpWindowTicks()).stream()
                .filter(
                    j ->
                        j.grade() == JumpResets.Grade.IDEAL_CANDIDATE
                            || j.grade() == JumpResets.Grade.ATTEMPT_CANDIDATE)
                .count();
    recorder.accept(
        new FightRecord(
            2,
            UUID.randomUUID().toString(),
            startEpoch,
            System.currentTimeMillis(),
            Math.max(0, now - start),
            opponent,
            outcome,
            reason,
            stats.copy(),
            List.copyOf(events),
            policy,
            List.copyOf(evaluations),
            opponentLabel,
            new MatchEvidence(
                server,
                localPlayer,
                roundId,
                outcomeConfidence,
                List.copyOf(matchEntries),
                start)));
    endDeadline = -1;
    pending.clear();
    roundStart = -1;
    announcedOpponent = null;
  }
}
