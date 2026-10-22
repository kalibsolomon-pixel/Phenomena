package dev.kwamster.combat.tracking;
import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.events.*;
import dev.kwamster.combat.events.CombatEvent.*;
import dev.kwamster.combat.metrics.Geometry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.phys.*;
public final class ClientCapture {
 private static long sequence;private static boolean connected,wasGround,sprinting;private static String deadTarget;private static final long ORIGIN=System.nanoTime();
 private ClientCapture(){}
 public static long now(){return System.nanoTime()-ORIGIN;}
 private static void emit(Kind kind,String actor,String target,Confidence c,Attack a,String detail){if(KwamsterClient.engine!=null)KwamsterClient.engine.accept(new CombatEvent(++sequence,now(),kind,actor,target,c,a,detail));}
 public static void emptySwing(){var mc=Minecraft.getInstance();if(mc.player!=null&&mc.screen==null&&mc.hitResult!=null&&mc.hitResult.getType()==HitResult.Type.MISS)emit(Kind.ATTACK,"local",null,Confidence.OBSERVED,null,"empty-swing");}
 public static void attack(Player player,Entity target){
  var mc=Minecraft.getInstance();if(player!=mc.player||!(target instanceof LivingEntity)||player.isSpectator()||!KwamsterClient.config.tracking)return;
  var eye=player.getEyePosition();var view=player.getLookAngle();var b=target.getBoundingBox();var box=new Geometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);
  var origin=new Geometry.Vec(eye.x,eye.y,eye.z);var ray=new Geometry.Vec(view.x,view.y,view.z);var contact=Geometry.intersect(origin,ray,box,player.entityInteractionRange());
  var normalized=contact.map(box::normalize).orElse(new Geometry.Vec(-1,-1,-1));double cooldown=player.getAttackStrengthScale(.5f);
  boolean crit=cooldown>.9&&player.fallDistance>0&&!player.onGround()&&!player.onClimbable()&&!player.isInWater()&&!player.hasEffect(MobEffects.BLINDNESS)&&!player.isPassenger()&&!player.isSprinting();
  double reach=contact.map(origin::distance).orElse(box.distance(origin));
  emit(Kind.ATTACK,"local",target.getUUID().toString(),Confidence.OBSERVED,new Attack(cooldown,reach,normalized.x(),normalized.y(),normalized.z(),player.isSprinting(),crit,contact.isPresent()),"eye-to-client-box; critical conditions observed");
 }
 /** Called at TAIL, after vanilla's main-thread packet dispatch guard. Reads only. */
 public static void damage(ClientboundDamageEventPacket packet){
  var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null||!KwamsterClient.config.tracking)return;
  var target=mc.level.getEntity(packet.entityId());var cause=mc.level.getEntity(packet.sourceCauseId());if(target==null)return;
  if(target==mc.player){emit(Kind.DAMAGE_IN,cause instanceof Player?cause.getUUID().toString():null,"local",Confidence.CONFIRMED,null,"server damage event; amount unavailable");}
  else if(cause==mc.player&&packet.sourceDirectId()==mc.player.getId()&&packet.sourceType().is(DamageTypes.PLAYER_ATTACK))emit(Kind.HURT,"local",target.getUUID().toString(),Confidence.CONFIRMED,null,"local-melee-source");
 }
 public static void tick(){var mc=Minecraft.getInstance();if(mc.player==null||mc.level==null){if(connected)emit(Kind.DISCONNECT,"local",null,Confidence.OBSERVED,null,"world unavailable");connected=false;deadTarget=null;return;}
  if(!connected){wasGround=mc.player.onGround();sprinting=mc.player.isSprinting();connected=true;}
  if(KwamsterClient.config.tracking){
   if(sprinting!=mc.player.isSprinting()){sprinting=mc.player.isSprinting();emit(Kind.SPRINT,"local",null,Confidence.OBSERVED,null,Boolean.toString(sprinting));}
   boolean ground=mc.player.onGround();if(wasGround&&!ground&&mc.player.getDeltaMovement().y>.15&&mc.options.keyJump.isDown())emit(Kind.JUMP,"local",null,Confidence.OBSERVED,null,"ground-to-air; vy="+mc.player.getDeltaMovement().y);wasGround=ground;
   if(mc.player.isDeadOrDying())emit(Kind.DEATH,null,"local",Confidence.OBSERVED,null,"local death");
   String opponent=KwamsterClient.engine.opponent();if(opponent!=null&&!opponent.equals(deadTarget)){for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(opponent)&&entity instanceof LivingEntity living&&living.isDeadOrDying()){deadTarget=opponent;emit(Kind.DEATH,null,opponent,Confidence.OBSERVED,null,"opponent death; attribution unavailable");break;}}
  }
  KwamsterClient.engine.tick(now());
 }
}
