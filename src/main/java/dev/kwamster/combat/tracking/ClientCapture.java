package dev.kwamster.combat.tracking;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.metrics.Geometry;
import dev.kwamster.combat.quality.*;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;

/**
 * Reads Minecraft state on the client thread and turns it into immutable observations. All hooks
 * are read-only. Packet observations are low frequency (damage, sound, animation, death, title);
 * per-tick work is a few field reads during an engagement.
 */
public final class ClientCapture {
  private static Entity trackedTarget;
  private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
  private static long sequence, clientTick;
  private static final AttackTiming TIMING = new AttackTiming();
  private static boolean jumpHeld, jumpConsumed;
  private static boolean connected, wasGround, sprinting, localDeathReported;
  private static double lastHealth = -1, lastAbsorption = -1;
  private static Vec3 lastPosition;
  private static String deadTarget, removedTarget;
  private static final long ORIGIN = System.nanoTime();

  /** Bounded entity UUID to display label (type or player name); local display metadata only. */
  private static final java.util.Map<String, String> LABELS =
      new java.util.LinkedHashMap<>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
          return size() > 32;
        }
      };

  private ClientCapture() {}

  public static void startTick() {
    clientTick++;
    var mc = Minecraft.getInstance();
    if (mc.player == null) {
      TIMING.reset();
      jumpHeld = false;
      jumpConsumed = false;
      return;
    }
    TIMING.held(weaponKey(mc.player));
    if (KwamsterClient.engine != null) KwamsterClient.engine.heldWeapon(weaponKey(mc.player));
    boolean down = mc.options.keyJump.isDown();
    if (down && !jumpHeld) {
      jumpConsumed = false;
      emit(
          Kind.JUMP_INPUT,
          "local",
          null,
          Confidence.OBSERVED,
          null,
          "jump pressed; ground=" + mc.player.onGround());
    }
    if (!down) jumpConsumed = false;
    jumpHeld = down;
  }

  private static String weaponKey(Player p) {
    var stack = p.getMainHandItem();
    return (stack.isEmpty() ? "fist" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
        + ";slot="
        + p.getInventory().getSelectedSlot()
        + ";attributes="
        + Objects.hashCode(stack.get(DataComponents.ATTRIBUTE_MODIFIERS));
  }

  /**
   * Actual vanilla jump method, not just an input press or a knockback-induced ground transition.
   */
  public static void jumped(LivingEntity entity) {
    var mc = Minecraft.getInstance();
    if (entity != mc.player
        || !mc.options.keyJump.isDown()
        || jumpConsumed
        || entity.isInWater()
        || entity.onClimbable()
        || entity.isPassenger()
        || !entity.onGround()
        || entity.getDeltaMovement().y <= 0) return;
    jumpConsumed = true;
    emit(
        Kind.JUMP,
        "local",
        null,
        Confidence.OBSERVED,
        null,
        "actual jumpFromGround; vy=" + entity.getDeltaMovement().y,
        Context.jump(entity.isSprinting()));
  }

  private static String host(Minecraft mc) {
    return mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip;
  }

  public static String currentHost() {
    return host(Minecraft.getInstance());
  }

  public static String currentWorld() {
    var mc = Minecraft.getInstance();
    return mc.level == null ? null : mc.level.dimension().identifier().toString();
  }

  public static long clientTick() {
    return clientTick;
  }

  public static java.util.List<String> knownNames() {
    var names = new java.util.ArrayList<>(LABELS.values());
    var mc = Minecraft.getInstance();
    if (mc.player != null) names.add(mc.player.getName().getString());
    if (mc.getConnection() != null)
      mc.getConnection().getOnlinePlayers().forEach(p -> names.add(p.getProfile().name()));
    return names;
  }

  public static void systemMessage(String text, boolean overlay) {
    announcement(text, overlay ? Announcements.Origin.OVERLAY : Announcements.Origin.SYSTEM);
  }

  public static void title(String text) {
    announcement(text, Announcements.Origin.TITLE);
  }

  public static void subtitle(String text) {
    announcement(text, Announcements.Origin.SUBTITLE);
  }

  private static void announcement(String text, Announcements.Origin origin) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || KwamsterClient.recording == null) return;
    String host = host(mc);
    long now = now();
    // Packets can announce the match before the first END_CLIENT_TICK in its new level.
    KwamsterClient.recording.context(
        host, mc.player.getName().getString(), currentWorld(), now, clientTick);
    var m = Announcements.interpret(host, text, origin).orElse(null);
    if (m != null)
      KwamsterClient.recording.announcement(host, mc.player.getName().getString(), m, now);
    KwamsterClient.diagnostics.announcement(
        host,
        text,
        origin,
        m,
        now,
        m == null ? "No observed rule" : KwamsterClient.engine.evidenceDecision(now));
  }

  public static String label(String uuid) {
    return uuid == null ? null : LABELS.get(uuid);
  }

  private static void remember(Entity entity) {
    String label =
        entity instanceof Player p
            ? p.getName().getString()
            : entity.getType().getDescription().getString();
    LABELS.put(entity.getUUID().toString(), label);
  }

  public static long now() {
    return System.nanoTime() - ORIGIN;
  }

  private static void emit(
      Kind kind, String actor, String target, Confidence c, Attack a, String detail) {
    emit(kind, actor, target, c, a, detail, null);
  }

  private static void emit(
      Kind kind,
      String actor,
      String target,
      Confidence c,
      Attack a,
      String detail,
      Context context) {
    if (KwamsterClient.recording != null) {
      var event =
          new CombatEvent(
              ++sequence, now(), kind, actor, target, c, a, detail, clientTick, context);
      KwamsterClient.diagnostics.event(currentHost(), event);
      KwamsterClient.recording.accept(event);
    }
  }

  public static void emptySwing() {
    var mc = Minecraft.getInstance();
    if (mc.player != null
        && mc.level != null
        && mc.screen == null
        && mc.hitResult != null
        && mc.hitResult.getType() == HitResult.Type.MISS) {
      LivingEntity aimed = null;
      // Attack-time query only, never a per-frame scan. The expanded box admits opening misses
      // before a successful hit. Facing unrelated empty space supplies no combat evidence.
      var eye = mc.player.getEyePosition(1);
      var end = eye.add(mc.player.getViewVector(1).scale(6));
      double nearest = Double.POSITIVE_INFINITY;
      for (var candidate :
          mc.level.getEntitiesOfClass(
              LivingEntity.class,
              mc.player.getBoundingBox().inflate(6),
              entity ->
                  entity != mc.player
                      && entity.isAlive()
                      && !entity.isSpectator()
                      && mc.player.hasLineOfSight(entity))) {
        if (!KwamsterClient.engine.acceptsTarget(
            candidate.getUUID().toString(),
            candidate instanceof Player p
                ? p.getName().getString()
                : candidate.getType().getDescription().getString())) continue;
        var contact =
            candidate.getBoundingBox().inflate(candidate.getPickRadius() + .35).clip(eye, end);
        if (contact.isPresent() && eye.distanceToSqr(contact.get()) < nearest) {
          aimed = candidate;
          nearest = eye.distanceToSqr(contact.get());
        }
      }
      if (aimed != null) capture(mc.player, aimed, true);
      else
        emit(
            Kind.ATTACK,
            "local",
            null,
            Confidence.OBSERVED,
            null,
            "empty-swing; no aimed opponent");
    }
  }

  public static void attack(Player player, Entity target) {
    capture(player, target, false);
  }

  private static void capture(Player player, Entity target, boolean empty) {
    var mc = Minecraft.getInstance();
    if (player != mc.player
        || !(target instanceof LivingEntity)
        || player.isSpectator()
        || (!KwamsterClient.config.tracking && !KwamsterClient.recording.manual())) return;
    remember(target);
    if (KwamsterClient.engine.acceptsTarget(
        target.getUUID().toString(), label(target.getUUID().toString()))) trackedTarget = target;
    float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    var eye = player.getEyePosition(partial);
    var view = player.getViewVector(partial);
    var renderPos = target.getPosition(partial);
    var pos = target.position();
    var collision = target.getBoundingBox().move(renderPos.subtract(pos));
    var b = collision.inflate(target.getPickRadius());
    var box = new Geometry.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    var origin = new Geometry.Vec(eye.x, eye.y, eye.z);
    var ray = new Geometry.Vec(view.x, view.y, view.z);
    var contact =
        Geometry.intersect(origin, ray, box, player.entityAttackRange().effectiveMaxRange(player));
    var normalized = contact.map(box::normalize).orElse(new Geometry.Vec(-1, -1, -1));
    double cooldown = player.getAttackStrengthScale(.5f);
    boolean crit =
        CriticalConditions.eligible(
            cooldown,
            player.fallDistance,
            player.onGround(),
            player.onClimbable(),
            player.isInWater(),
            player.isMobilityRestricted(),
            player.isPassenger(),
            player.isSprinting());
    double reach = contact.map(origin::distance).orElse(box.distance(origin));
    var region = MeleeAim.region(origin, box, player.entityAttackRange().effectiveMaxRange(player));
    boolean visibleContact = contact.isPresent();
    if (visibleContact && mc.level != null) {
      var point = contact.get();
      var block =
          mc.level.clip(
              new net.minecraft.world.level.ClipContext(
                  eye,
                  new Vec3(point.x(), point.y(), point.z()),
                  net.minecraft.world.level.ClipContext.Block.COLLIDER,
                  net.minecraft.world.level.ClipContext.Fluid.NONE,
                  player));
      visibleContact =
          block.getType() == HitResult.Type.MISS
              || eye.distanceTo(block.getLocation()) + .001 >= reach;
    }
    String weapon = weaponKey(player);
    double speed = player.getAttributeValue(Attributes.ATTACK_SPEED);
    double duration = player.getCurrentItemAttackStrengthDelay();
    if (!KwamsterClient.engine.engaged()
        || KwamsterClient.engine.state() == CombatEngine.State.ENDING
        || !Objects.equals(target.getUUID().toString(), KwamsterClient.engine.opponent()))
      TIMING.reset();
    var timing =
        TIMING.attack(weapon, clientTick, duration, KwamsterClient.config.timingToleranceTicks);
    boolean blocking = target instanceof LivingEntity le && le.isBlocking();
    boolean facing = false;
    if (blocking) {
      // Vanilla blocks melee arriving in front of the blocker's horizontal view direction.
      var look = target.getViewVector(1);
      var toAttacker = player.position().subtract(target.position());
      facing = look.x * toAttacker.x + look.z * toAttacker.z > 0;
    }
    emit(
        Kind.ATTACK,
        "local",
        target.getUUID().toString(),
        Confidence.OBSERVED,
        new Attack(
            cooldown,
            reach,
            normalized.x(),
            normalized.y(),
            normalized.z(),
            player.isSprinting(),
            crit,
            visibleContact,
            new Measurement(
                weapon,
                speed,
                duration,
                timing,
                origin,
                box,
                region,
                MeleeAim.classify(normalized, region, box, visibleContact),
                "MELEE_ATTEMPT")),
        (empty ? "empty-swing; plausible recent opponent; " : "")
            + "interpolated eye-to-client-target-box; partial="
            + partial
            + "; margin="
            + target.getPickRadius()
            + "; critical conditions observed",
        Context.target(blocking, facing));
  }

  /** Called at TAIL, after vanilla's main-thread packet dispatch guard. Reads only. */
  public static void damage(ClientboundDamageEventPacket packet) {
    var mc = Minecraft.getInstance();
    if (mc.player == null
        || mc.level == null
        || (!KwamsterClient.config.tracking && !KwamsterClient.recording.manual())) return;
    var target = mc.level.getEntity(packet.entityId());
    var cause = mc.level.getEntity(packet.sourceCauseId());
    if (target == null) return;
    if (target == mc.player) {
      if (cause != null) remember(cause);
      var engine = KwamsterClient.engine;
      String opponent = engine.engaged() ? engine.opponent() : null;
      boolean living = cause instanceof LivingEntity && cause != mc.player;
      String causeId = living ? cause.getUUID().toString() : null;
      boolean direct = living && packet.sourceDirectId() == cause.getId();
      // Before an engagement the first living cause becomes the opponent.
      boolean isOpponent =
          living
              && (opponent == null
                  || causeId.equals(opponent)
                  || (opponent.startsWith("announced:")
                      && Objects.equals(label(causeId), opponent.substring(10))));
      Attribution attribution =
          !living
              ? Attribution.UNATTRIBUTED
              : !isOpponent
                  ? Attribution.OTHER_ENTITY
                  : direct ? Attribution.OPPONENT_DIRECT : Attribution.OPPONENT_INDIRECT;
      if (!engine.engaged() || isOpponent) trackedTarget = cause;
      var p = mc.player;
      boolean valid = !p.isInWater() && !p.onClimbable() && !p.isPassenger();
      emit(
          Kind.DAMAGE_IN,
          causeId,
          "local",
          Confidence.CONFIRMED,
          null,
          "server damage event; amount and knockback cancellation unavailable",
          Context.incoming(
              attribution,
              packet.sourceType().unwrapKey().map(k -> k.identifier().toString()).orElse(null),
              living ? direct : null,
              p.onGround(),
              p.isSprinting(),
              valid));
    } else if (cause == mc.player
        && packet.sourceDirectId() == mc.player.getId()
        && (packet.sourceType().is(DamageTypes.PLAYER_ATTACK)
            || packet.sourceType().is(DamageTypes.MACE_SMASH)))
      emit(
          Kind.HURT,
          "local",
          target.getUUID().toString(),
          Confidence.CONFIRMED,
          null,
          "local-melee-source");
  }

  /**
   * Shield block or disable sounds within 1.5 blocks of the local player or the opponent. Only two
   * sound identities are compared; every other sound returns immediately.
   */
  public static void sound(ClientboundSoundPacket packet) {
    var mc = Minecraft.getInstance();
    var engine = KwamsterClient.engine;
    if (mc.player == null || engine == null || !engine.engaged()) return;
    var sound = packet.getSound();
    boolean block = sound.is(SoundEvents.SHIELD_BLOCK);
    boolean disable = !block && sound.is(SoundEvents.SHIELD_BREAK);
    if (!block && !disable) return;
    var at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
    double local = mc.player.position().distanceTo(at);
    double opp =
        trackedTarget != null
                && Objects.equals(trackedTarget.getUUID().toString(), engine.opponent())
            ? trackedTarget.position().distanceTo(at)
            : Double.POSITIVE_INFINITY;
    double nearest = Math.min(local, opp);
    // The sound plays at the blocker's position; anything farther is someone else's shield.
    if (nearest > 1.5) return;
    emit(
        Kind.SHIELD,
        null,
        null,
        Confidence.OBSERVED,
        null,
        (block ? "shield block sound" : "shield disable sound") + "; nearest participant",
        Context.shield(block ? "BLOCK" : "DISABLE", opp < local ? "opponent" : "local", nearest));
  }

  /** Server critical-hit animation (action 4) on the opponent or the local player. */
  public static void animate(ClientboundAnimatePacket packet) {
    var mc = Minecraft.getInstance();
    var engine = KwamsterClient.engine;
    if (mc.player == null
        || mc.level == null
        || engine == null
        || !engine.engaged()
        || packet.getAction() != ClientboundAnimatePacket.CRITICAL_HIT) return;
    var entity = mc.level.getEntity(packet.getId());
    if (entity == null) return;
    boolean local = entity == mc.player;
    String id = local ? "local" : entity.getUUID().toString();
    if (!local && !id.equals(engine.opponent())) return;
    emit(
        Kind.CRIT,
        null,
        id,
        Confidence.CONFIRMED,
        null,
        "server critical-hit animation",
        Context.subject(local ? "local" : "opponent"));
  }

  /** Death-screen packet the server sends to the dying local player. */
  public static void combatKill(ClientboundPlayerCombatKillPacket packet) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || packet.playerId() != mc.player.getId() || localDeathReported) return;
    localDeathReported = true;
    emit(Kind.DEATH, null, "local", Confidence.CONFIRMED, null, "server combat-kill packet");
  }

  public static void velocity(ClientboundSetEntityMotionPacket packet) {
    var mc = Minecraft.getInstance();
    if (mc.player != null
        && packet.getId() == mc.player.getId()
        && KwamsterClient.engine.engaged()) {
      var v = packet.getMovement();
      emit(
          Kind.VELOCITY,
          "local",
          null,
          Confidence.OBSERVED,
          null,
          "server velocity update; cause unverified",
          Context.velocity(v.x, v.y, v.z, mc.player.onGround()));
    }
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      // Level/player availability is not connection identity (respawn and arena loading can
      // temporarily clear both). Actual connection teardown is observed by Fabric below.
      connected = false;
      deadTarget = null;
      removedTarget = null;
      trackedTarget = null;
      lastLevel = null;
      lastPosition = null;
      KwamsterClient.recording.tick(now(), clientTick);
      return;
    }
    if (connected && lastLevel != mc.level) {
      emit(
          Kind.WORLD_CHANGE,
          "local",
          null,
          Confidence.OBSERVED,
          null,
          "world or dimension changed; connection retained");
      connected = false;
      trackedTarget = null;
      deadTarget = null;
      removedTarget = null;
      lastPosition = null;
    }
    KwamsterClient.recording.context(
        host(mc), mc.player.getName().getString(), currentWorld(), now(), clientTick);
    lastLevel = mc.level;
    if (!connected) {
      wasGround = mc.player.onGround();
      sprinting = mc.player.isSprinting();
      connected = true;
    }
    if (KwamsterClient.config.tracking || KwamsterClient.recording.manual()) {
      var engine = KwamsterClient.engine;
      if (sprinting != mc.player.isSprinting()) {
        sprinting = mc.player.isSprinting();
        emit(Kind.SPRINT, "local", null, Confidence.OBSERVED, null, Boolean.toString(sprinting));
      }
      boolean ground = mc.player.onGround();
      wasGround = ground;
      if (mc.player.isDeadOrDying()) {
        if (!localDeathReported)
          emit(Kind.DEATH, null, "local", Confidence.OBSERVED, null, "local death");
        localDeathReported = true;
      } else localDeathReported = false;
      var position = mc.player.position();
      if (engine.engaged()) {
        double health = mc.player.getHealth(), absorption = mc.player.getAbsorptionAmount();
        if (health != lastHealth || absorption != lastAbsorption)
          emit(
              Kind.HEALTH,
              "local",
              null,
              Confidence.OBSERVED,
              null,
              "client health",
              Context.health(health, absorption));
        lastHealth = health;
        lastAbsorption = absorption;
        if (lastPosition != null && lastPosition.distanceTo(position) > 8)
          emit(
              Kind.TELEPORT,
              "local",
              null,
              Confidence.OBSERVED,
              null,
              "single-tick position change",
              Context.distance(lastPosition.distanceTo(position)));
        if (mc.player.tickCount % 2 == 0) {
          var motion = mc.player.getDeltaMovement();
          emit(
              Kind.MOTION,
              "local",
              null,
              Confidence.OBSERVED,
              null,
              "vx=" + motion.x + ";vy=" + motion.y + ";vz=" + motion.z + ";ground=" + ground);
        }
        String opponent = engine.opponent();
        boolean isOpponent =
            trackedTarget != null
                && opponent != null
                && opponent.equals(trackedTarget.getUUID().toString());
        if (isOpponent
            && !opponent.equals(deadTarget)
            && trackedTarget instanceof LivingEntity living
            && living.isDeadOrDying()) {
          deadTarget = opponent;
          emit(
              Kind.DEATH,
              null,
              opponent,
              Confidence.OBSERVED,
              null,
              "opponent death; attribution unavailable");
        }
        if (isOpponent && trackedTarget.isRemoved() && !opponent.equals(removedTarget)) {
          removedTarget = opponent;
          emit(
              Kind.REMOVED,
              null,
              opponent,
              Confidence.OBSERVED,
              null,
              "opponent entity removed from client world: " + trackedTarget.getRemovalReason());
        }
      } else {
        lastHealth = -1;
        lastAbsorption = -1;
      }
      lastPosition = position;
    }
    KwamsterClient.recording.tick(now(), clientTick);
  }

  public static void disconnected() {
    emit(Kind.DISCONNECT, "local", null, Confidence.OBSERVED, null, "play connection closed");
    connected = false;
    lastLevel = null;
    trackedTarget = null;
    lastPosition = null;
    TIMING.reset();
  }
}
