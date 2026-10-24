package dev.kwamster.recording;

import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.Kind;
import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.Config;
import java.util.*;
import java.util.function.*;

/** Client-thread owner of the recording lifecycle. Commands and GUI call this same controller. */
public final class RecordingController {
  public enum State {
    IDLE,
    AUTOMATIC_ACTIVE,
    MANUAL_ACTIVE,
    FINALIZING,
    PENDING_EXPORT
  }

  private final CombatEngine engine;
  private final Consumer<FightRecord> completed, checkpoint;
  private final Consumer<String> notice;
  private final Function<String, String> labels;
  private final Config config;
  private final List<CombatEvent> events = new ArrayList<>();
  private final List<FightRecord> exchanges = new ArrayList<>();
  private final List<MatchEvidence.Entry> announcements = new ArrayList<>();
  private final LinkedHashMap<String, SessionInfo.Participant> participants = new LinkedHashMap<>();
  private String id, server, local, world, lastSaved;
  private long start, epoch, startTick, endTick, nextCheckpoint, lastSeq = -1;
  private long manualStartEpoch, manualStartTick;
  private boolean finalizing;
  private Function<String, String> exportStatus = id -> "Report pending";

  public void exportStatus(Function<String, String> status) {
    exportStatus = status;
  }

  public RecordingController(
      Config config,
      CombatEngine engine,
      Consumer<FightRecord> completed,
      Consumer<FightRecord> checkpoint,
      Consumer<String> notice,
      Function<String, String> labels) {
    this.config = config;
    this.engine = engine;
    this.completed = completed;
    this.checkpoint = checkpoint;
    this.notice = notice;
    this.labels = labels;
  }

  public boolean manual() {
    return id != null;
  }

  public String lastSaved() {
    return lastSaved;
  }

  public String server() {
    return server;
  }

  public State state() {
    if (finalizing) return State.FINALIZING;
    if (manual()) return State.MANUAL_ACTIVE;
    if (engine.engaged()) return State.AUTOMATIC_ACTIVE;
    return lastSaved != null && exportStatus.apply(lastSaved).startsWith("Report pending")
        ? State.PENDING_EXPORT
        : State.IDLE;
  }

  public long duration(long now) {
    return manual()
        ? Math.max(0, now - start)
        : engine.engaged() ? Math.max(0, now - engine.startNanos()) : 0;
  }

  public String opponent() {
    return engine.engaged() ? labels.apply(engine.opponent()) : null;
  }

  public boolean start(String host, String local, String world, long now, long tick) {
    if (manual()) {
      notice.accept("Manual recording is already active.");
      return false;
    }
    if (host == null || local == null) {
      notice.accept("Join a world before starting a recording.");
      return false;
    }
    this.server = host;
    this.local = local;
    this.world = world;
    id = UUID.randomUUID().toString();
    start = now;
    epoch = System.currentTimeMillis();
    manualStartEpoch = epoch;
    manualStartTick = tick;
    startTick = endTick = tick;
    events.clear();
    exchanges.clear();
    announcements.clear();
    participants.clear();
    lastSeq = -1;
    if (engine.engaged()) {
      start = engine.startNanos();
      epoch = engine.startEpochMillis();
      for (var e : engine.observations()) {
        events.add(e);
        remember(e);
        lastSeq = e.sequence();
        if (e.clientTick() != null) startTick = Math.min(startTick, e.clientTick());
      }
    } else engine.context(host, local);
    engine.manualExchanges = true;
    nextCheckpoint = now;
    tick(now, tick);
    notice.accept("Manual recording started. Use /kwm end to save the recording and HTML report.");
    return true;
  }

  public boolean end(long now, long tick) {
    if (!manual()) {
      notice.accept("No manual recording is active. Use /kwm start first.");
      return false;
    }
    terminate(now, tick, "Manual end", false);
    return true;
  }

  public void automaticFinished(FightRecord fight) {
    if (manual()) exchanges.add(fight);
    else {
      lastSaved = fight.id();
      completed.accept(fight);
    }
  }

  public void context(String host, String player, String dimension, long now, long tick) {
    boolean changed =
        server != null
            && (!Objects.equals(host, server)
                || !Objects.equals(player, local)
                || !Objects.equals(dimension, world));
    if (!manual() && changed && engine.engaged()) {
      engine.finish(now, FightRecord.Outcome.INTERRUPTED, "Connection context changed");
      engine.clearMatchContext();
    }
    if (manual()
        && (!Objects.equals(host, server)
            || !Objects.equals(player, local)
            || !Objects.equals(dimension, world)))
      terminate(now, tick, "Server or world changed", true);
    if (!manual()) {
      server = host;
      local = player;
      world = dimension;
    }
    engine.context(host, player);
  }

  public void accept(CombatEvent e) {
    if (manual()
        && e.sequence() > lastSeq
        && (events.isEmpty() || e.nanoTime() >= events.getLast().nanoTime())) {
      events.add(e);
      remember(e);
      lastSeq = e.sequence();
      if (e.clientTick() != null) endTick = e.clientTick();
    }
    engine.accept(e);
    if (manual() && e.kind() == Kind.DISCONNECT)
      terminate(e.nanoTime(), endTick, "Disconnected: " + e.detail(), true);
  }

  public void announcement(String host, String player, Announcements.Message message, long now) {
    if (manual()) {
      if (!Objects.equals(host, server) || !Objects.equals(player, local)) return;
      announcements.add(
          new MatchEvidence.Entry(
              now,
              message.kind().name(),
              message.rule(),
              message.origin().name(),
              message.original(),
              false));
    }
    engine.announcement(host, player, message, now);
  }

  public void tick(long now, long tick) {
    engine.tick(now);
    if (manual() && now >= nextCheckpoint) {
      endTick = tick;
      nextCheckpoint = now + 5_000_000_000L;
      checkpoint.accept(
          snapshot(now, tick, "Recovered after interrupted client; checkpoint", true));
    }
  }

  public void shutdown(long now, long tick, String reason) {
    if (manual()) terminate(now, tick, reason, true);
    else engine.finish(now, FightRecord.Outcome.INTERRUPTED, reason);
  }

  private void terminate(long now, long tick, String reason, boolean interrupted) {
    if (!manual() || finalizing) return;
    finalizing = true;
    // The current exchange is captured inside the parent; no duplicate top-level duel is emitted.
    engine.finish(now, FightRecord.Outcome.UNKNOWN, reason);
    var record = snapshot(now, tick, reason, interrupted);
    checkpoint.accept(record); // durable recovery always precedes the export job
    lastSaved = id;
    id = null;
    engine.manualExchanges = false;
    engine.clearMatchContext();
    finalizing = false;
    completed.accept(record);
    events.clear();
    exchanges.clear();
    announcements.clear();
    notice.accept("Manual session finalized; saving recording and offline HTML report.");
  }

  private FightRecord snapshot(long now, long tick, String reason, boolean interrupted) {
    List<FightRecord> scopes = new ArrayList<>(exchanges);
    if (engine.engaged()) scopes.add(engine.checkpoint(now));
    var stats = new Statistics();
    List<FightRecord.Evaluation> eval = new ArrayList<>();
    Set<Long> counted = new HashSet<>();
    for (var f : scopes) {
      stats.add(f.statistics());
      eval.addAll(f.evaluations());
      f.events().forEach(e -> counted.add(e.sequence()));
    }
    for (var e : events)
      if (!counted.contains(e.sequence())) {
        if (e.kind() == Kind.ATTACK)
          stats.attempt(
              e.attack(),
              e.attack() == null || (e.detail() != null && e.detail().startsWith("empty-swing")));
        else if (e.kind() == Kind.DAMAGE_IN) {
          if (e.actor() == null) stats.unattributedIncoming++;
          else stats.thirdPartyIncoming++;
        }
      }
    var outcome = interrupted ? FightRecord.Outcome.INTERRUPTED : FightRecord.Outcome.UNKNOWN;
    return new FightRecord(
        FightRecord.SCHEMA,
        id,
        epoch,
        System.currentTimeMillis(),
        Math.max(0, now - start),
        null,
        outcome,
        reason,
        stats,
        List.copyOf(events),
        FightRecord.Policy.of(config),
        eval,
        null,
        new MatchEvidence(server, local, null, Confidence.OBSERVED, List.of(), start),
        new Resolution(
            Resolution.Classification.of(outcome, false),
            Resolution.Source.EXTERNAL,
            Confidence.OBSERVED,
            Resolution.Scope.ENGAGEMENT,
            now,
            null,
            null,
            List.of("Session outcome is independent of individual exchange results"),
            false),
        new SessionInfo(
            startTick,
            tick,
            world,
            reason,
            List.copyOf(participants.values()),
            scopes,
            List.copyOf(announcements),
            manualStartEpoch,
            manualStartTick));
  }

  private void remember(CombatEvent event) {
    for (var who : new String[] {event.actor(), event.target()})
      if (who != null && !"local".equals(who)) {
        var previous = participants.get(who);
        String label = labels.apply(who);
        if (previous == null || previous.label() == null)
          participants.put(who, new SessionInfo.Participant(who, label));
      }
  }
}
