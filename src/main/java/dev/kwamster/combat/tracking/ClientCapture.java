package dev.kwamster.combat.tracking;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.metrics.Geometry;
import dev.kwamster.combat.quality.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.*;

public final class ClientCapture {
  private static Entity trackedTarget;
  private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
  private static long sequence, clientTick;
  private static final AttackTiming TIMING = new AttackTiming();
  private static boolean jumpHeld, jumpConsumed;

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
        + java.util.Objects.hashCode(stack.get(DataComponents.ATTRIBUTE_MODIFIERS));
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
        "actual jumpFromGround; vy=" + entity.getDeltaMovement().y);
  }

  public static void systemMessage(String text, boolean overlay) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || KwamsterClient.engine == null) return;
    String host = mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip;
    Announcements.interpret(
            host, text, overlay ? Announcements.Origin.OVERLAY : Announcements.Origin.SYSTEM)
        .ifPresent(
            m ->
                KwamsterClient.engine.announcement(
                    host, mc.player.getName().getString(), m, now()));
  }

  private static boolean connected, wasGround, sprinting;
  private static String deadTarget;
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
    if (KwamsterClient.engine != null)
      KwamsterClient.engine.accept(
          new CombatEvent(++sequence, now(), kind, actor, target, c, a, detail, clientTick));
  }

  public static void emptySwing() {
    var mc = Minecraft.getInstance();
    if (mc.player != null
        && mc.screen == null
        && mc.hitResult != null
        && mc.hitResult.getType() == HitResult.Type.MISS) {
      if (KwamsterClient.engine.engaged()
          && trackedTarget instanceof LivingEntity
          && trackedTarget.distanceTo(mc.player) < 6) capture(mc.player, trackedTarget, true);
      else emit(Kind.ATTACK, "local", null, Confidence.OBSERVED, null, "empty-swing");
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
        || !KwamsterClient.config.tracking) return;
    remember(target);
    if (!KwamsterClient.engine.acceptsTarget(
        target.getUUID().toString(), label(target.getUUID().toString()))) return;
    trackedTarget = target;
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
        dev.kwamster.combat.quality.CriticalConditions.eligible(
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
        || !java.util.Objects.equals(target.getUUID().toString(), KwamsterClient.engine.opponent()))
      TIMING.reset();
    var timing =
        TIMING.attack(weapon, clientTick, duration, KwamsterClient.config.timingToleranceTicks);
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
            + "; critical conditions observed");
  }

  /** Called at TAIL, after vanilla's main-thread packet dispatch guard. Reads only. */
  public static void damage(ClientboundDamageEventPacket packet) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || !KwamsterClient.config.tracking) return;
    var target = mc.level.getEntity(packet.entityId());
    var cause = mc.level.getEntity(packet.sourceCauseId());
    if (target == null) return;
    if (target == mc.player) {
      if (cause != null) remember(cause);
      if (!KwamsterClient.engine.engaged()
          || (cause != null && cause.getUUID().toString().equals(KwamsterClient.engine.opponent())))
        trackedTarget = cause;
      emit(
          Kind.DAMAGE_IN,
          cause instanceof LivingEntity ? cause.getUUID().toString() : null,
          "local",
          Confidence.CONFIRMED,
          null,
          "server damage event; amount and knockback cancellation unavailable");
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

  public static void velocity(
      net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket packet) {
    var mc = Minecraft.getInstance();
    if (mc.player != null && packet.getId() == mc.player.getId() && KwamsterClient.engine.engaged())
      emit(
          Kind.VELOCITY,
          "local",
          null,
          Confidence.OBSERVED,
          null,
          "server velocity update; cause unverified; " + packet.getMovement());
  }

  public static void tick() {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      if (connected)
        emit(Kind.DISCONNECT, "local", null, Confidence.OBSERVED, null, "world unavailable");
      connected = false;
      deadTarget = null;
      trackedTarget = null;
      lastLevel = null;
      KwamsterClient.engine.tick(now());
      return;
    }
    if (connected && lastLevel != mc.level) {
      emit(Kind.DISCONNECT, "local", null, Confidence.OBSERVED, null, "world or dimension changed");
      connected = false;
      trackedTarget = null;
      deadTarget = null;
    }
    String host = mc.getCurrentServer() == null ? "singleplayer" : mc.getCurrentServer().ip;
    KwamsterClient.engine.context(host, mc.player.getName().getString());
    lastLevel = mc.level;
    if (!connected) {
      wasGround = mc.player.onGround();
      sprinting = mc.player.isSprinting();
      connected = true;
    }
    if (KwamsterClient.config.tracking) {
      if (sprinting != mc.player.isSprinting()) {
        sprinting = mc.player.isSprinting();
        emit(Kind.SPRINT, "local", null, Confidence.OBSERVED, null, Boolean.toString(sprinting));
      }
      boolean ground = mc.player.onGround();
      wasGround = ground;
      if (mc.player.isDeadOrDying())
        emit(Kind.DEATH, null, "local", Confidence.OBSERVED, null, "local death");
      if (KwamsterClient.engine.engaged()) {
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
        String opponent = KwamsterClient.engine.opponent();
        if (trackedTarget != null
            && opponent != null
            && opponent.equals(trackedTarget.getUUID().toString())
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
      }
    }
    KwamsterClient.engine.tick(now());
  }
}
