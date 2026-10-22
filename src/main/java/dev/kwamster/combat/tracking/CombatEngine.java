package dev.kwamster.combat.tracking;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.fights.FightRecord.Outcome;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.HitQuality;
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
  private final ArrayList<CombatEvent> events = new ArrayList<>();
  private final ArrayDeque<CombatEvent> pending = new ArrayDeque<>();
  private final ArrayList<FightRecord.Evaluation> evaluations = new ArrayList<>();
  private FightRecord.Policy policy;
  private Statistics stats = new Statistics();
  private String opponent;
  private long start, lastActivity, lastSequence = -1, lastTime = -1, startEpoch, lastDamage = -1;
  private State state = State.IDLE;

  public CombatEngine(
      Config c,
      Consumer<FightRecord> recorder,
      BiConsumer<CombatEvent, HitQuality.Grade> feedback) {
    this.config = c;
    this.recorder = recorder;
    this.feedback = feedback;
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

  public String opponent() {
    return opponent;
  }

  public void accept(CombatEvent e) {
    if (!config.tracking || e.sequence() <= lastSequence || e.nanoTime() < lastTime) return;
    lastSequence = e.sequence();
    lastTime = e.nanoTime();
    if (e.kind() == Kind.DISCONNECT) {
      finish(e.nanoTime(), Outcome.INTERRUPTED, "Disconnected");
      return;
    }
    if (!engaged()) {
      if (e.kind() == Kind.ATTACK && e.target() != null) begin(e.target(), e.nanoTime());
      else if (e.kind() == Kind.DAMAGE_IN && e.actor() != null) begin(e.actor(), e.nanoTime());
      else return;
    }
    if (e.kind() == Kind.ATTACK && e.target() != null && !e.target().equals(opponent)) {
      finish(e.nanoTime(), Outcome.INTERRUPTED, "Target changed");
      begin(e.target(), e.nanoTime());
    }
    if (e.kind() == Kind.HURT && !Objects.equals(e.target(), opponent)) return;
    if (e.kind() == Kind.DAMAGE_IN && e.actor() != null && !Objects.equals(e.actor(), opponent))
      return;
    if (events.size() >= 10000) {
      finish(e.nanoTime(), Outcome.INTERRUPTED, "Event limit");
      return;
    }
    events.add(e);
    while (!pending.isEmpty()
        && e.nanoTime() - pending.peekFirst().nanoTime() > policy.correlationMillis() * 1e6)
      pending.removeFirst();
    switch (e.kind()) {
      case ATTACK -> {
        boolean empty = e.attack() == null || e.detail().startsWith("empty-swing");
        stats.attempt(e.attack(), empty);
        if (e.attack() != null && !empty) {
          if (pending.size() == 64) pending.removeFirst();
          pending.addLast(e);
        }
        lastActivity = e.nanoTime();
        state = State.ENGAGED;
      }
      case HURT -> {
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
            stats.hit(match.attack(), match.nanoTime(), e.nanoTime(), policy.comboSeconds());
            lastActivity = e.nanoTime();
            state = State.ENGAGED;
            var grade =
                HitQuality.classify(
                    match.attack(), policy.perfectCooldown(), policy.greatCooldown());
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
        lastDamage = e.nanoTime();
        lastActivity = e.nanoTime();
        state = State.ENGAGED;
      }
      case JUMP -> {
        if (lastDamage >= 0
            && Math.abs(e.nanoTime() - lastDamage) <= policy.jumpWindowMillis() * 1e6) {
          stats.jumpCandidates++;
        }
      }
      case DEATH -> {
        if ("local".equals(e.target()))
          finish(e.nanoTime(), Outcome.LOSS, "Local death observed; attribution unknown");
        else if (Objects.equals(e.target(), opponent))
          finish(
              e.nanoTime(), Outcome.UNKNOWN, "Opponent death observed; kill attribution unknown");
      }
      default -> {}
    }
  }

  private void begin(String target, long time) {
    opponent = target;
    start = lastActivity = time;
    startEpoch = System.currentTimeMillis();
    stats = new Statistics();
    events.clear();
    evaluations.clear();
    policy = FightRecord.Policy.of(config);
    pending.clear();
    lastDamage = -1;
    state = State.ENGAGED;
  }

  public void tick(long now) {
    if (!engaged()) return;
    if (stats.lastHit > 0 && now - stats.lastHit > policy.comboSeconds() * 1e9)
      stats.currentCombo = 0;
    if (now - lastActivity > config.inactivitySeconds * 1e9)
      finish(now, Outcome.UNKNOWN, "Inactivity");
    else if (now - lastActivity > config.inactivitySeconds * .75e9) state = State.ENDING;
  }

  public void finish(long now, Outcome outcome, String reason) {
    if (!engaged()) return;
    state = State.FINALIZED;
    stats.currentCombo = 0;
    recorder.accept(
        new FightRecord(
            1,
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
            List.copyOf(evaluations)));
    pending.clear();
  }
}
