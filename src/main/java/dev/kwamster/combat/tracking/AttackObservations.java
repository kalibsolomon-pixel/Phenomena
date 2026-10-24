package dev.kwamster.combat.tracking;

import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.metrics.Statistics;
import java.util.*;

/**
 * Local outgoing observations only; inclusive two-tick window anchored to its FIRST input. Raw
 * inputs stay in the recording. Frozen ID/sample/class maps describe the canonical stream.
 * Different-target/weapon inputs inside the owner's window retain their distinct raw evidence, but
 * still share one reported attempt; confirmation must match an actually observed target.
 */
public final class AttackObservations {
  public enum Classification {
    HIT,
    MISS,
    SHIELD_BLOCK,
    UNCERTAIN_SHIELD,
    EXCLUDED,
    UNRESOLVED
  }

  public record Observation(CombatEvent event, boolean duplicate, boolean newlyEligible) {}

  private final Statistics stats;
  private final Map<Long, Set<String>> targets = new LinkedHashMap<>();
  private CombatEvent anchor, sample;
  private boolean sampleEligible;

  public AttackObservations(Statistics stats) {
    this.stats = stats;
    stats.measurementVersion = 1;
  }

  public static boolean outgoing(CombatEvent e) {
    return e.kind() == CombatEvent.Kind.ATTACK && "local".equals(e.actor());
  }

  public static boolean empty(CombatEvent e) {
    return e.attack() == null || e.detail() != null && e.detail().startsWith("empty-swing");
  }

  public static boolean eligible(CombatEvent e) {
    // Capture supplies geometry only for a valid selected target or an aimed near miss.
    return e.target() != null && e.attack() != null;
  }

  private static boolean within(CombatEvent first, CombatEvent next) {
    if (first.clientTick() == null || next.clientTick() == null) return false;
    long dt = next.clientTick() - first.clientTick();
    return dt >= 0 && dt <= 2;
  }

  public boolean duplicate(CombatEvent next) {
    return anchor != null && within(anchor, next);
  }

  public Observation observe(CombatEvent e) {
    return observe(e, true);
  }

  public Observation observe(CombatEvent e, boolean validOpponent) {
    if (!outgoing(e)) throw new IllegalArgumentException("Not a local outgoing input");
    stats.totalInputs++;
    boolean duplicate = anchor != null && within(anchor, e);
    if (!duplicate) {
      anchor = sample = e;
      sampleEligible = validOpponent && eligible(e);
      stats.uniqueAttacks++;
      targets.put(e.sequence(), new HashSet<>());
      // Correlation is bounded; frozen maps keep the complete diagnostic identity history.
      if (targets.size() > 64) targets.remove(targets.keySet().iterator().next());
    } else stats.deduplicatedAttacks++;
    long id = anchor.sequence();
    stats.attackCanonicalIds.put(e.sequence(), id);
    if (e.target() != null) targets.get(id).add(e.target());
    boolean wasEligible =
        stats.attackClassifications.containsKey(id)
            && !Classification.EXCLUDED.name().equals(stats.attackClassifications.get(id));
    if (!sampleEligible && validOpponent && eligible(e)) {
      sample = e;
      sampleEligible = true;
    }
    stats.attackSamples.put(id, sample.sequence());
    if (!stats.attackClassifications.containsKey(id) || !wasEligible) {
      var ctx = sample.context();
      classify(
          id,
          !sampleEligible
              ? Classification.EXCLUDED
              : ctx != null
                      && Boolean.TRUE.equals(ctx.targetBlocking())
                      && Boolean.TRUE.equals(ctx.targetFacing())
                  ? Classification.UNCERTAIN_SHIELD
                  : Classification.UNRESOLVED);
    }
    return new Observation(canonical(anchor, sample), duplicate, !wasEligible && sampleEligible);
  }

  public boolean targets(long id, String target) {
    return target != null && targets.getOrDefault(id, Set.of()).contains(target);
  }

  public boolean resolved(long id) {
    String c = stats.attackClassifications.get(id);
    return Classification.HIT.name().equals(c) || Classification.SHIELD_BLOCK.name().equals(c);
  }

  public void expire(CombatEvent e) {
    if (!resolved(e.sequence())
        && eligible(e)
        && empty(e)
        && !Classification.UNCERTAIN_SHIELD
            .name()
            .equals(stats.attackClassifications.get(e.sequence())))
      classify(e.sequence(), Classification.MISS);
  }

  public void classify(long id, Classification next) {
    String before = stats.attackClassifications.put(id, next.name());
    if (before != null) count(Classification.valueOf(before), -1);
    count(next, 1);
  }

  private void count(Classification c, int delta) {
    if (c != Classification.EXCLUDED) stats.attempts += delta;
    switch (c) {
      case HIT -> stats.hits += delta;
      case MISS -> {
        stats.eligibleMisses += delta;
        stats.emptyMisses += delta;
      }
      case SHIELD_BLOCK -> stats.shieldBlocks += delta;
      case UNCERTAIN_SHIELD -> {
        stats.uncertain += delta;
        stats.uncertainShield += delta;
        stats.estimatedShieldBlocks += delta;
      }
      case EXCLUDED -> stats.excludedSwings += delta;
      case UNRESOLVED -> stats.uncertain += delta;
    }
  }

  private static CombatEvent canonical(CombatEvent first, CombatEvent sample) {
    return new CombatEvent(
        first.sequence(),
        first.nanoTime(),
        first.kind(),
        first.actor(),
        sample.target(),
        first.confidence(),
        sample.attack(),
        sample.detail(),
        first.clientTick(),
        sample.context());
  }

  /** Historic records have no classification maps and retain their original interpretation. */
  public static List<CombatEvent> canonicalEvents(List<CombatEvent> events, Statistics s) {
    if (s.measurementVersion == 0) return events;
    var byId = new HashMap<Long, CombatEvent>();
    for (var e : events) byId.put(e.sequence(), e);
    var result = new ArrayList<CombatEvent>();
    for (var e : events) {
      if (!outgoing(e)) {
        result.add(e);
        continue;
      }
      Long id = s.attackCanonicalIds.get(e.sequence());
      if (id == null || id != e.sequence()) continue;
      var sample = byId.get(s.attackSamples.get(id));
      var canonical = sample == null ? e : canonical(e, sample);
      result.add(withTiming(canonical, s.attackTimings.get(id)));
    }
    return List.copyOf(result);
  }

  public static CombatEvent withTiming(
      CombatEvent e, dev.kwamster.combat.quality.AttackTiming.Result timing) {
    var a = e.attack();
    if (a == null || a.measurement() == null || timing == null) return e;
    var m = a.measurement();
    var measurement =
        new CombatEvent.Measurement(
            m.weapon(),
            m.attackSpeed(),
            m.cooldownTicks(),
            timing,
            m.eye(),
            m.box(),
            m.idealRegion(),
            m.aimQuality(),
            m.mode());
    var attack =
        new CombatEvent.Attack(
            a.cooldown(),
            a.reach(),
            a.aimX(),
            a.aimY(),
            a.aimZ(),
            a.sprinting(),
            a.criticalConditions(),
            a.rayContact(),
            measurement);
    return new CombatEvent(
        e.sequence(),
        e.nanoTime(),
        e.kind(),
        e.actor(),
        e.target(),
        e.confidence(),
        attack,
        e.detail(),
        e.clientTick(),
        e.context());
  }
}
