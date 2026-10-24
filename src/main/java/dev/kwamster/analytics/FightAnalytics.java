package dev.kwamster.analytics;

import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.events.CombatEvent.Kind;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.metrics.Combos;
import dev.kwamster.combat.metrics.Geometry;
import dev.kwamster.combat.metrics.Statistics;
import dev.kwamster.combat.quality.AttackTiming;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.combat.quality.JumpResets;
import dev.kwamster.combat.quality.MeleeAim;
import dev.kwamster.combat.quality.PerfectReach;
import java.util.*;

/**
 * Timeline-derived values for one recorded fight. Built only from stored raw events and the frozen
 * evaluations, so every number can be traced back to a recorded observation. Correlation is not
 * repeated here: an attack is a hit exactly when the engine recorded a hit evaluation for it, and a
 * confirmed shield block exactly when it recorded a shield evaluation.
 *
 * <p>Instances are immutable and independent of Minecraft classes; the in-game GUI and the HTML
 * report both consume them.
 */
public final class FightAnalytics {
  public static final double REACH_BIN = .5, REACH_MAX = 6, FINE_REACH_BIN = .05;
  public static final int REACH_BINS = (int) (REACH_MAX / REACH_BIN);
  public static final int COOLDOWN_BINS = 10;
  public static final int JUMP_OFFSET_LIMIT_MS = 1000;

  /** Vanilla axe disable lasts 5 s (100 ticks); hits inside it count as "after a disable". */
  public static final double DISABLE_FOLLOW_UP_SECONDS = 5;

  /** A correlated hit. Times are seconds since the first recorded event. */
  public record Hit(
      long attackSequence,
      double time,
      double evidenceTime,
      double reach,
      boolean rayContact,
      double aimHorizontal,
      double aimVertical,
      double cooldown,
      Grade readiness,
      boolean sprinting,
      boolean criticalConditions,
      int combo,
      double momentum,
      CombatEvent.Measurement measurement,
      boolean perfectReach,
      String weapon,
      Boolean serverCrit,
      String face,
      double aimDistance,
      Long tick) {}

  /**
   * An attempt without a correlated server report. Empty swings carry geometry only when a
   * plausible recent opponent was in range; uncertain attempts were aimed at an entity.
   */
  public record Miss(
      long sequence,
      double time,
      boolean empty,
      boolean geometry,
      double estimatedReach,
      boolean rayContact,
      double aimHorizontal,
      double aimVertical) {}

  /** Jump observed near incoming opponent damage; offset is jump time minus damage time. */
  public record Jump(
      double time,
      double offsetMillis,
      boolean candidate,
      Long offsetTicks,
      String classification) {}

  public record Timing(long sequence, double time, CombatEvent.Measurement measurement) {}

  /** One graded attack interval with its outcome; weapons are never pooled into one average. */
  public record TimingSample(
      long sequence,
      double time,
      String weapon,
      double attackSpeed,
      double expectedTicks,
      double intervalTicks,
      double deltaTicks,
      AttackTiming.Grade grade,
      String outcome) {
    public boolean graded() {
      return grade == AttackTiming.Grade.PERFECT
          || grade == AttackTiming.Grade.TOO_FAST
          || grade == AttackTiming.Grade.TOO_SLOW;
    }
  }

  /**
   * One incoming damage observation. {@code attribution} is the stored server-source attribution
   * (OPPONENT_DIRECT, OPPONENT_INDIRECT, OTHER_ENTITY, UNATTRIBUTED) or, for recordings before
   * 0.1.3, OPPONENT_LEGACY / UNATTRIBUTED. Damage is the observed local health+absorption drop
   * within two ticks; knockback is the server velocity update within one tick (blocks/tick,
   * horizontal).
   */
  public record Incoming(
      long sequence,
      double time,
      Long tick,
      String attribution,
      boolean attributed,
      String damageType,
      Double damage,
      Double knockback,
      Boolean ground,
      Boolean sprinting,
      Boolean movementValid,
      String jumpClass,
      Long jumpOffsetTicks,
      boolean crit,
      Double speedAfter) {}

  public record ShieldEvent(
      double time, String type, String subject, Long attackSequence, String weapon) {}

  public record Shields(
      boolean available,
      int attacksIntoShield,
      int confirmedBlocks,
      int estimatedBlocks,
      int disables,
      int hitsAfterDisable,
      int bypassed,
      int localBlocks,
      int localDisabled,
      List<ShieldEvent> events) {}

  public record WeaponReach(int eligible, int within, double mean, double deviation, double max) {}

  public record ReachSummary(
      double target,
      double tolerance,
      int eligible,
      int within,
      double mean,
      double deviation,
      double max,
      double meanAbsoluteError,
      double[] values,
      String[] weapons,
      Map<String, WeaponReach> byWeapon) {
    public double withinPercent() {
      return eligible == 0 ? Double.NaN : 100.0 * within / eligible;
    }
  }

  public record AimSample(
      long sequence,
      double time,
      String weapon,
      String face,
      double x,
      double y,
      double z,
      double horizontal,
      double vertical,
      String grade,
      double distance,
      String reachBand,
      boolean hit) {}

  public record AimSummary(
      List<AimSample> samples,
      int[] grades,
      double meanDistance,
      double distanceDeviation,
      Map<String, int[]> byWeapon,
      Map<String, int[]> byReach) {
    public int central() {
      return grades[0] + grades[1];
    }

    public int peripheral() {
      return grades[2] + grades[3];
    }
  }

  /** Experimental, descriptive only: mean horizontal speed 0.1-0.4 s after knockback. */
  public record JumpEffect(int candidates, double candidateSpeed, int others, double otherSpeed) {}

  public record Boundary(double time, String kind, boolean accepted) {}

  public final List<Timing> timings;
  public final List<JumpResets.Sample> jumpSamples;

  public record Point(double time, double value) {}

  public final FightRecord fight;
  public final double duration;
  public final List<Hit> hits;
  public final List<Miss> misses;
  public final List<Double> intervals;
  public final List<Integer> comboLengths;
  public final List<Point> momentumChanges;

  /** Attributed opponent hits only (seconds). */
  public final List<Double> incomingTimes;

  public final List<Jump> jumps;
  public final int jumpsWithoutDamage, motionSamples, sprintTransitions;
  public final int[] readiness = new int[4];
  public final int[] hitReachBins = new int[REACH_BINS + 1];
  public final int[] missReachBins = new int[REACH_BINS + 1];
  public final int[] cooldownBins = new int[COOLDOWN_BINS];
  public final int center, edge, high, low;

  // ---- 0.1.3 views
  public final boolean contextAvailable;
  public final Combos.Result combos;
  public final List<Incoming> incoming;
  public final int attributedIncoming, unattributedIncoming, thirdPartyIncoming;
  public final int unexplainedHealthDrops;
  public final Shields shields;
  public final Map<Long, String> attemptOutcomes;
  public final List<TimingSample> timingSamples;
  public final ReachSummary reach;
  public final AimSummary aim;
  public final JumpEffect jumpEffect;
  public final Dominance dominance;
  public final List<Boundary> boundaries;
  public final int serverCrits;

  /** Monotonic origin of every "seconds since fight start" value in this instance. */
  public final long baseNanos;

  private FightAnalytics(FightRecord f) {
    this.fight = f;
    var events = f.events();
    var policy = f.policy();
    String opponent = f.opponent();
    long base =
        f.matchEvidence().timelineStartNanos() != null
            ? f.matchEvidence().timelineStartNanos()
            : events.isEmpty() ? 0 : events.getFirst().nanoTime();
    duration = Math.max(f.durationNanos() / 1e9, 0);
    baseNanos = base;
    contextAvailable = f.schemaVersion() >= FightRecord.SCHEMA;
    Map<Long, CombatEvent> bySequence = new HashMap<>();
    for (var e : events) bySequence.put(e.sequence(), e);

    timings =
        events.stream()
            .filter(
                e ->
                    e.kind() == Kind.ATTACK
                        && e.attack() != null
                        && e.attack().measurement() != null)
            .map(
                e ->
                    new Timing(e.sequence(), seconds(e.nanoTime(), base), e.attack().measurement()))
            .toList();
    jumpSamples = JumpResets.analyze(events, policy.jumpWindowTicks(), opponent);
    Map<Long, JumpResets.Sample> byJump = new HashMap<>();
    jumpSamples.forEach(j -> byJump.put(j.jumpSequence(), j));

    // Server critical animations on the opponent, by tick.
    Set<Long> critTicks = new HashSet<>();
    int crits = 0;
    for (var e : events)
      if (e.kind() == Kind.CRIT
          && e.context() != null
          && "opponent".equals(e.context().subject())
          && e.clientTick() != null) critTicks.add(e.clientTick());

    List<Hit> hitList = new ArrayList<>();
    Set<Long> hitAttacks = new HashSet<>();
    Map<Long, String> outcomes = new HashMap<>();
    List<ShieldEvent> shieldEvents = new ArrayList<>();
    int blocks = 0, disables = 0;
    List<Double> disableTimes = new ArrayList<>();
    int c = 0, ed = 0, hi = 0, lo = 0;
    for (var ev : f.evaluations()) {
      var attackEvent = bySequence.get(ev.attackSequence());
      var evidence = bySequence.get(ev.evidenceSequence());
      if (attackEvent == null || attackEvent.attack() == null) continue;
      var a = attackEvent.attack();
      double evidenceTime =
          evidence == null
              ? seconds(attackEvent.nanoTime(), base)
              : seconds(evidence.nanoTime(), base);
      String weapon = a.measurement() == null ? "unknown" : a.measurement().item();
      if (ev.shield()) {
        boolean disable = FightRecord.Evaluation.SHIELD_DISABLE.equals(ev.result());
        blocks++;
        if (disable) {
          disables++;
          disableTimes.add(evidenceTime);
        }
        outcomes.put(ev.attackSequence(), ev.result());
        shieldEvents.add(
            new ShieldEvent(
                evidenceTime,
                disable ? "DISABLE" : "BLOCK",
                "opponent",
                ev.attackSequence(),
                weapon));
        continue;
      }
      hitAttacks.add(ev.attackSequence());
      outcomes.put(ev.attackSequence(), "HIT");
      double h = a.rayContact() ? Geometry.faceHorizontal(a.aimX(), a.aimZ()) : -1;
      Long tick = evidence == null ? attackEvent.clientTick() : evidence.clientTick();
      Boolean crit =
          !contextAvailable
              ? null
              : tick != null
                  && (critTicks.contains(tick)
                      || critTicks.contains(tick - 1)
                      || critTicks.contains(tick + 1));
      if (Boolean.TRUE.equals(crit)) crits++;
      var m = a.measurement();
      var normalized = new Geometry.Vec(a.aimX(), a.aimY(), a.aimZ());
      hitList.add(
          new Hit(
              ev.attackSequence(),
              seconds(attackEvent.nanoTime(), base),
              evidenceTime,
              a.reach(),
              a.rayContact(),
              h,
              a.rayContact() ? a.aimY() : -1,
              a.cooldown(),
              ev.readiness(),
              a.sprinting(),
              a.criticalConditions(),
              ev.combo(),
              ev.momentum(),
              m,
              PerfectReach.eligible(a, policy.perfectReachTarget(), policy.perfectReachTolerance()),
              weapon,
              crit,
              a.rayContact() ? MeleeAim.face(normalized) : null,
              m != null && a.rayContact()
                  ? MeleeAim.distance(normalized, m.idealRegion(), m.box())
                  : Double.NaN,
              tick));
      if (ev.readiness() != null) readiness[ev.readiness().ordinal()]++;
      if (a.reach() >= 0) hitReachBins[reachBin(a.reach())]++;
      cooldownBins[
          Math.min(COOLDOWN_BINS - 1, Math.max(0, (int) (a.cooldown() * COOLDOWN_BINS)))]++;
      if (a.rayContact()) {
        switch (Geometry.placement(a.aimX(), a.aimY(), a.aimZ())) {
          case "Center" -> c++;
          case "Edge" -> ed++;
          case "High" -> hi++;
          default -> lo++;
        }
      }
    }
    center = c;
    edge = ed;
    high = hi;
    low = lo;
    hits = List.copyOf(hitList);
    serverCrits = crits;

    List<Double> intervalList = new ArrayList<>();
    for (int i = 1; i < hits.size(); i++)
      intervalList.add(hits.get(i).time() - hits.get(i - 1).time());
    intervals = List.copyOf(intervalList);

    combos = Combos.analyze(events, f.evaluations(), opponent, policy.comboSeconds());
    comboLengths =
        combos.streaks().stream()
            .filter(s -> s.side() == Combos.Side.LOCAL)
            .map(Combos.Streak::length)
            .toList();

    // ---- attempts, shields, incoming, jumps, health and movement
    List<Miss> missList = new ArrayList<>();
    List<Jump> jumpList = new ArrayList<>();
    List<Double> attributedTimes = new ArrayList<>();
    int noDamage = 0, motion = 0, sprint = 0;
    int intoShield = 0, estimated = 0, bypassed = 0, localBlocks = 0, localDisabled = 0;
    var attributedDamage = events.stream().filter(e -> e.attributedTo(opponent)).toList();
    for (var e : events) {
      double t = seconds(e.nanoTime(), base);
      switch (e.kind()) {
        case ATTACK -> {
          var ctx = e.context();
          boolean empty =
              e.attack() == null || (e.detail() != null && e.detail().startsWith("empty-swing"));
          boolean intoBlock = !empty && ctx != null && Boolean.TRUE.equals(ctx.targetBlocking());
          if (intoBlock) intoShield++;
          if (hitAttacks.contains(e.sequence())) {
            if (intoBlock) bypassed++;
            break;
          }
          if (outcomes.containsKey(e.sequence())) break;
          var a = e.attack();
          boolean estimatedBlock = intoBlock && Boolean.TRUE.equals(ctx.targetFacing());
          if (estimatedBlock) {
            estimated++;
            shieldEvents.add(
                new ShieldEvent(
                    t,
                    "ESTIMATED_BLOCK",
                    "opponent",
                    e.sequence(),
                    a.measurement() == null ? "unknown" : a.measurement().item()));
          }
          outcomes.put(
              e.sequence(), empty ? "EMPTY" : estimatedBlock ? "ESTIMATED_BLOCK" : "UNCERTAIN");
          missList.add(
              new Miss(
                  e.sequence(),
                  t,
                  empty,
                  a != null,
                  a == null ? -1 : a.reach(),
                  a != null && a.rayContact(),
                  a != null && a.rayContact() ? Geometry.faceHorizontal(a.aimX(), a.aimZ()) : -1,
                  a != null && a.rayContact() ? a.aimY() : -1));
          if (a != null && a.reach() >= 0) missReachBins[reachBin(a.reach())]++;
        }
        case DAMAGE_IN -> {
          if (e.attributedTo(opponent)) attributedTimes.add(t);
        }
        case SHIELD -> {
          var ctx = e.context();
          if (ctx != null && "local".equals(ctx.subject())) {
            if ("DISABLE".equals(ctx.shield())) localDisabled++;
            else localBlocks++;
            shieldEvents.add(new ShieldEvent(t, ctx.shield(), "local", null, null));
          }
        }
        case JUMP -> {
          var sample = byJump.get(e.sequence());
          var nearest =
              attributedDamage.stream()
                  .min(Comparator.comparingLong(d -> Math.abs(e.nanoTime() - d.nanoTime())))
                  .orElse(null);
          if (nearest == null
              || (sample.offsetTicks() == null
                  && Math.abs(e.nanoTime() - nearest.nanoTime()) > 1_000_000_000L)) noDamage++;
          else {
            boolean candidate =
                sample.grade() == JumpResets.Grade.IDEAL_CANDIDATE
                    || sample.grade() == JumpResets.Grade.ATTEMPT_CANDIDATE;
            jumpList.add(
                new Jump(
                    t,
                    (e.nanoTime() - nearest.nanoTime()) / 1e6,
                    candidate,
                    sample.offsetTicks(),
                    sample.grade().name()));
          }
        }
        case MOTION -> motion++;
        case SPRINT -> sprint++;
        default -> {}
      }
    }
    shieldEvents.sort(Comparator.comparingDouble(ShieldEvent::time));
    int afterDisable = 0;
    for (var h : hits)
      for (double d : disableTimes)
        if (h.evidenceTime() > d && h.evidenceTime() - d <= DISABLE_FOLLOW_UP_SECONDS) {
          afterDisable++;
          break;
        }
    shields =
        new Shields(
            contextAvailable,
            intoShield,
            blocks,
            estimated,
            disables,
            afterDisable,
            bypassed,
            localBlocks,
            localDisabled,
            List.copyOf(shieldEvents));
    attemptOutcomes = Map.copyOf(outcomes);
    misses = List.copyOf(missList);
    incomingTimes = List.copyOf(attributedTimes);
    jumps = List.copyOf(jumpList);
    jumpsWithoutDamage = noDamage;
    motionSamples = motion;
    sprintTransitions = sprint;
    momentumChanges = List.copyOf(momentum(hits, incomingTimes));

    // ---- incoming detail
    var built = incoming(events, base, opponent, policy.jumpWindowTicks());
    incoming = built.list;
    unexplainedHealthDrops = built.unexplained.size();
    int att = 0, un = 0, third = 0;
    List<Double> unattributedTimes = new ArrayList<>(built.unexplained);
    for (var in : incoming) {
      if (in.attributed()) att++;
      else if ("OTHER_ENTITY".equals(in.attribution())) {
        third++;
        unattributedTimes.add(in.time());
      } else {
        un++;
        unattributedTimes.add(in.time());
      }
    }
    attributedIncoming = att;
    unattributedIncoming = un;
    thirdPartyIncoming = third;
    jumpEffect = jumpEffect(incoming);

    timingSamples = timingSamples(events, base, outcomes);
    reach = reach(hits, policy.perfectReachTarget(), policy.perfectReachTolerance());
    aim = aim(events, base, hitAttacks);

    // ---- dominance
    List<Dominance.Input> inputs = new ArrayList<>();
    Map<Long, Double> weights = new HashMap<>();
    for (var s : combos.streaks())
      for (int k = 0; k < s.sequences().size(); k++)
        weights.put(
            s.sequences().get(k),
            (s.side() == Combos.Side.LOCAL ? 1 : -1)
                * (Dominance.W_HIT + (k > 0 ? Dominance.W_STREAK_BONUS : 0)));
    for (var m : combos.marks()) {
      double w =
          weights.getOrDefault(
              m.sequence(), m.side() == Combos.Side.LOCAL ? Dominance.W_HIT : -Dominance.W_HIT);
      inputs.add(new Dominance.Input(seconds(m.nanos(), base), w, m.side().name()));
    }
    for (var s : shields.events()) {
      switch (s.type() + "/" + s.subject()) {
        case "BLOCK/opponent" ->
            inputs.add(
                new Dominance.Input(s.time(), -Dominance.W_SHIELD_BLOCKED, "OPPONENT_BLOCK"));
        case "DISABLE/opponent" ->
            inputs.add(
                new Dominance.Input(s.time(), Dominance.W_SHIELD_DISABLED, "OPPONENT_DISABLED"));
        case "BLOCK/local" ->
            inputs.add(new Dominance.Input(s.time(), Dominance.W_LOCAL_BLOCK, "LOCAL_BLOCK"));
        case "DISABLE/local" ->
            inputs.add(
                new Dominance.Input(s.time(), -Dominance.W_SHIELD_DISABLED, "LOCAL_DISABLED"));
        default -> {}
      }
    }
    inputs.sort(Comparator.comparingDouble(Dominance.Input::time));
    dominance = Dominance.of(duration, inputs, unattributedTimes, att);

    List<Boundary> bs = new ArrayList<>();
    for (var entry : f.matchEvidence().entries())
      bs.add(new Boundary(seconds(entry.nanoTime(), base), entry.kind(), entry.accepted()));
    boundaries = List.copyOf(bs);
  }

  private record IncomingBuild(List<Incoming> list, List<Double> unexplained) {}

  private static IncomingBuild incoming(
      List<CombatEvent> events, long base, String opponent, int window) {
    List<CombatEvent> damage = new ArrayList<>(), jumpsEv = new ArrayList<>();
    List<CombatEvent> velocity = new ArrayList<>(),
        health = new ArrayList<>(),
        motion = new ArrayList<>();
    Set<Long> localCrits = new HashSet<>();
    for (var e : events)
      switch (e.kind()) {
        case DAMAGE_IN -> damage.add(e);
        case JUMP -> jumpsEv.add(e);
        case VELOCITY -> velocity.add(e);
        case HEALTH -> health.add(e);
        case MOTION -> motion.add(e);
        case CRIT -> {
          if (e.context() != null
              && "local".equals(e.context().subject())
              && e.clientTick() != null) localCrits.add(e.clientTick());
        }
        default -> {}
      }
    List<Incoming> out = new ArrayList<>();
    Set<Long> damageTicks = new HashSet<>();
    for (var d : damage) if (d.clientTick() != null) damageTicks.add(d.clientTick());
    for (var d : damage) {
      var ctx = d.context();
      boolean attributed = d.attributedTo(opponent);
      String attribution =
          ctx != null && ctx.attribution() != null
              ? ctx.attribution().name()
              : attributed
                  ? "OPPONENT_LEGACY"
                  : d.actor() == null ? "UNATTRIBUTED" : "OTHER_ENTITY";
      Long tick = d.clientTick();
      Double dmg = null, kb = null, after = null;
      if (tick != null) {
        Double before = null, min = null;
        for (var h : health) {
          if (h.clientTick() == null || h.context() == null || h.context().health() == null)
            continue;
          double v = h.context().health() + nz(h.context().absorption());
          if (h.sequence() < d.sequence() && h.clientTick() <= tick) before = v;
          else if (h.clientTick() >= tick && h.clientTick() <= tick + 2)
            min = min == null ? v : Math.min(min, v);
        }
        if (before != null && min != null && before - min > 1e-6) dmg = before - min;
        for (var v : velocity)
          if (v.clientTick() != null
              && v.clientTick() >= tick
              && v.clientTick() <= tick + 1
              && v.context() != null
              && v.context().vx() != null) {
            kb = Math.hypot(v.context().vx(), v.context().vz());
            break;
          }
        double sum = 0;
        int n = 0;
        for (var m : motion)
          if (m.clientTick() != null && m.clientTick() >= tick + 2 && m.clientTick() <= tick + 8) {
            double[] vel = parseMotion(m.detail());
            if (vel != null) {
              sum += Math.hypot(vel[0], vel[1]);
              n++;
            }
          }
        if (n > 0) after = sum / n;
      }
      String jumpClass;
      Long offset = null;
      if (!attributed) jumpClass = "NOT_APPLICABLE";
      else if (tick == null) jumpClass = "UNAVAILABLE";
      else if (ctx != null && Boolean.FALSE.equals(ctx.movementValid())) jumpClass = "EXCLUDED";
      else {
        CombatEvent nearest = null;
        long best = Long.MAX_VALUE;
        for (var j : jumpsEv) {
          if (j.clientTick() == null) continue;
          long delta = Math.abs(j.clientTick() - tick);
          if (delta < best
              || (delta == best && nearest != null && j.sequence() < nearest.sequence())) {
            best = delta;
            nearest = j;
          }
        }
        if (nearest == null || best > 20) jumpClass = "NO_JUMP";
        else {
          offset = nearest.clientTick() - tick;
          jumpClass =
              offset == 0
                  ? "IDEAL_CANDIDATE"
                  : Math.abs(offset) <= window ? "ATTEMPT_CANDIDATE" : "OUTSIDE_WINDOW";
        }
      }
      boolean crit =
          tick != null
              && (localCrits.contains(tick)
                  || localCrits.contains(tick - 1)
                  || localCrits.contains(tick + 1));
      out.add(
          new Incoming(
              d.sequence(),
              seconds(d.nanoTime(), base),
              tick,
              attribution,
              attributed,
              ctx == null ? null : ctx.damageType(),
              dmg,
              kb,
              ctx == null ? null : ctx.ground(),
              ctx == null ? null : ctx.sprinting(),
              ctx == null ? null : ctx.movementValid(),
              jumpClass,
              offset,
              crit,
              after));
    }
    // Health decreases with no damage event within two ticks: damage the client could not
    // attribute.
    List<Double> unexplained = new ArrayList<>();
    Double previous = null;
    for (var h : health) {
      if (h.context() == null || h.context().health() == null) continue;
      double v = h.context().health() + nz(h.context().absorption());
      if (previous != null && v < previous - 1e-6 && h.clientTick() != null) {
        long t = h.clientTick();
        boolean explained = false;
        for (long k = t - 2; k <= t; k++) explained |= damageTicks.contains(k);
        if (!explained) unexplained.add(seconds(h.nanoTime(), base));
      }
      previous = v;
    }
    return new IncomingBuild(List.copyOf(out), List.copyOf(unexplained));
  }

  private static double nz(Double v) {
    return v == null ? 0 : v;
  }

  /** Horizontal velocity components from a MOTION detail string, or null. */
  static double[] parseMotion(String detail) {
    if (detail == null) return null;
    double vx = Double.NaN, vz = Double.NaN;
    for (String part : detail.split(";")) {
      try {
        if (part.startsWith("vx=")) vx = Double.parseDouble(part.substring(3));
        else if (part.startsWith("vz=")) vz = Double.parseDouble(part.substring(3));
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return Double.isFinite(vx) && Double.isFinite(vz) ? new double[] {vx, vz} : null;
  }

  private static JumpEffect jumpEffect(List<Incoming> incoming) {
    int nc = 0, no = 0;
    double sc = 0, so = 0;
    for (var in : incoming) {
      if (!in.attributed() || in.speedAfter() == null || in.knockback() == null) continue;
      switch (in.jumpClass()) {
        case "IDEAL_CANDIDATE", "ATTEMPT_CANDIDATE" -> {
          nc++;
          sc += in.speedAfter();
        }
        case "NO_JUMP", "OUTSIDE_WINDOW" -> {
          no++;
          so += in.speedAfter();
        }
        default -> {}
      }
    }
    if (nc < 3 || no < 3) return null;
    return new JumpEffect(nc, sc / nc, no, so / no);
  }

  private static List<TimingSample> timingSamples(
      List<CombatEvent> events, long base, Map<Long, String> outcomes) {
    List<TimingSample> out = new ArrayList<>();
    for (var e : events) {
      if (e.kind() != Kind.ATTACK || e.attack() == null || e.attack().measurement() == null)
        continue;
      var m = e.attack().measurement();
      var t = m.timing();
      if (t == null) continue;
      boolean opener = t.grade() == AttackTiming.Grade.OPENER;
      out.add(
          new TimingSample(
              e.sequence(),
              seconds(e.nanoTime(), base),
              m.item(),
              m.attackSpeed(),
              opener ? Double.NaN : t.expectedTicks(),
              opener ? Double.NaN : t.intervalTicks(),
              opener ? Double.NaN : t.intervalTicks() - t.expectedTicks(),
              t.grade(),
              outcomes.getOrDefault(e.sequence(), "UNCERTAIN")));
    }
    return List.copyOf(out);
  }

  private static ReachSummary reach(List<Hit> hits, double target, double tolerance) {
    List<Double> values = new ArrayList<>();
    List<String> weapons = new ArrayList<>();
    Map<String, List<Double>> by = new TreeMap<>();
    for (var h : hits) {
      if (!h.rayContact() || !(h.reach() >= 0) || !Double.isFinite(h.reach())) continue;
      values.add(h.reach());
      weapons.add(h.weapon());
      by.computeIfAbsent(h.weapon(), k -> new ArrayList<>()).add(h.reach());
    }
    double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
    Map<String, WeaponReach> byWeapon = new LinkedHashMap<>();
    for (var entry : by.entrySet()) {
      double[] w = entry.getValue().stream().mapToDouble(Double::doubleValue).toArray();
      byWeapon.put(
          entry.getKey(),
          new WeaponReach(w.length, within(w, target, tolerance), mean(w), sd(w), max(w)));
    }
    double mae = Double.NaN;
    if (v.length > 0) {
      mae = 0;
      for (double x : v) mae += Math.abs(x - target);
      mae /= v.length;
    }
    return new ReachSummary(
        target,
        tolerance,
        v.length,
        within(v, target, tolerance),
        mean(v),
        sd(v),
        max(v),
        mae,
        v,
        weapons.toArray(String[]::new),
        Map.copyOf(byWeapon));
  }

  private static int within(double[] v, double target, double tolerance) {
    int n = 0;
    for (double x : v) if (Math.abs(x - target) <= tolerance + 1e-9) n++;
    return n;
  }

  static double mean(double[] v) {
    if (v.length == 0) return Double.NaN;
    double s = 0;
    for (double x : v) s += x;
    return s / v.length;
  }

  /** Population standard deviation; NaN without samples. */
  static double sd(double[] v) {
    if (v.length == 0) return Double.NaN;
    double m = mean(v), s = 0;
    for (double x : v) s += (x - m) * (x - m);
    return Math.sqrt(s / v.length);
  }

  static double max(double[] v) {
    double m = Double.NaN;
    for (double x : v) m = Double.isNaN(m) ? x : Math.max(m, x);
    return m;
  }

  public static String reachBand(double reach) {
    return !(reach >= 0)
        ? "unknown"
        : reach < 2.5 ? "< 2.5 m" : reach < 3 ? "2.5-3.0 m" : ">= 3.0 m";
  }

  private static AimSummary aim(List<CombatEvent> events, long base, Set<Long> hitAttacks) {
    List<AimSample> samples = new ArrayList<>();
    int[] grades = new int[4];
    Map<String, int[]> byWeapon = new TreeMap<>(), byReach = new TreeMap<>();
    List<Double> distances = new ArrayList<>();
    for (var e : events) {
      if (e.kind() != Kind.ATTACK || e.attack() == null || !e.attack().rayContact()) continue;
      var a = e.attack();
      var m = a.measurement();
      boolean hit = hitAttacks.contains(e.sequence());
      var n = new Geometry.Vec(a.aimX(), a.aimY(), a.aimZ());
      String grade = m == null || m.aimQuality() == null ? "UNAVAILABLE" : m.aimQuality().name();
      double dist = m == null ? Double.NaN : MeleeAim.distance(n, m.idealRegion(), m.box());
      String weapon = m == null ? "unknown" : m.item();
      String band = reachBand(a.reach());
      samples.add(
          new AimSample(
              e.sequence(),
              seconds(e.nanoTime(), base),
              weapon,
              MeleeAim.face(n),
              a.aimX(),
              a.aimY(),
              a.aimZ(),
              Geometry.faceHorizontal(a.aimX(), a.aimZ()),
              a.aimY(),
              grade,
              dist,
              band,
              hit));
      if (!hit || m == null || m.aimQuality() == null || m.aimQuality().ordinal() >= 4) continue;
      int g = m.aimQuality().ordinal();
      grades[g]++;
      byWeapon.computeIfAbsent(weapon, k -> new int[4])[g]++;
      byReach.computeIfAbsent(band, k -> new int[4])[g]++;
      if (Double.isFinite(dist)) distances.add(dist);
    }
    double[] d = distances.stream().mapToDouble(Double::doubleValue).toArray();
    return new AimSummary(List.copyOf(samples), grades, mean(d), sd(d), byWeapon, byReach);
  }

  /** Seconds since fight start for a monotonic observation time. */
  public double time(long nanos) {
    return (nanos - baseNanos) / 1e9;
  }

  /** Every combo (two or more hits, either side) in time order. */
  public List<Combos.Streak> allCombos() {
    return combos.streaks().stream().filter(Combos.Streak::combo).toList();
  }

  public static FightAnalytics of(FightRecord fight) {
    return new FightAnalytics(fight);
  }

  private static double seconds(long nanos, long base) {
    return (nanos - base) / 1e9;
  }

  public static int reachBin(double reach) {
    return Math.min(REACH_BINS, Math.max(0, (int) (reach / REACH_BIN)));
  }

  /**
   * Legacy momentum change points (0.1.0-0.1.2 model, kept for the recorded per-hit values): +hit
   * on server evidence time, -incoming on attributed opponent damage, clipped to [-1, 1],
   * exponential decay between changes. Superseded for display by {@link Dominance}.
   */
  private static List<Point> momentum(List<Hit> hits, List<Double> incoming) {
    record Change(double time, boolean hit) {}
    List<Change> changes = new ArrayList<>();
    for (var h : hits) changes.add(new Change(h.evidenceTime(), true));
    for (var t : incoming) changes.add(new Change(t, false));
    changes.sort(Comparator.comparingDouble(Change::time));
    List<Point> points = new ArrayList<>();
    double value = 0, at = 0;
    for (var ch : changes) {
      double decayed = decay(value, ch.time() - at);
      value =
          ch.hit()
              ? Math.min(1, decayed + Statistics.MOMENTUM_HIT)
              : Math.max(-1, decayed - Statistics.MOMENTUM_INCOMING);
      at = ch.time();
      points.add(new Point(at, value));
    }
    return points;
  }

  private static double decay(double value, double seconds) {
    return value * Math.exp(-Math.max(0, seconds) * 1e9 / Statistics.MOMENTUM_DECAY_NANOS);
  }

  /** Legacy momentum estimate at a time in seconds since fight start. */
  public double momentumAt(double time) {
    double value = 0, at = 0;
    for (var p : momentumChanges) {
      if (p.time() > time) break;
      value = p.value();
      at = p.time();
    }
    return decay(value, time - at);
  }

  /** Evenly sampled legacy momentum curve. */
  public double[] momentumSamples(int count) {
    double[] out = new double[Math.max(2, count)];
    for (int i = 0; i < out.length; i++) out[i] = momentumAt(duration * i / (out.length - 1));
    return out;
  }

  public int longestCombo() {
    return combos.longest(Combos.Side.LOCAL);
  }

  /** Mean length of local combos (streaks of two or more hits). */
  public double averageCombo() {
    return combos.averageCombo(Combos.Side.LOCAL);
  }

  /** Accuracy with confirmed shield blocks removed from the denominator. */
  public double accuracy() {
    int attempts = hits.size() + misses.size() + shields.confirmedBlocks();
    int denominator = attempts - shields.confirmedBlocks();
    return denominator == 0 ? Double.NaN : 100.0 * hits.size() / denominator;
  }

  /** Opponent combo availability: attribution decides whether opponent streaks can be trusted. */
  public String opponentComboAvailability() {
    if (attributedIncoming == 0 && unattributedIncoming + unexplainedHealthDrops > 0)
      return "Unavailable";
    if (unattributedIncoming + unexplainedHealthDrops > 0) return "Estimated";
    return "Observed";
  }
}
