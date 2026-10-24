package dev.kwamster.mixin;

import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only observations at the TAIL of vanilla handlers, after the main-thread dispatch guard. No
 * packet is cancelled, delayed or modified. Every handler here is low frequency except sound, which
 * returns after two identity comparisons unless a shield sound arrives during an engagement.
 */
@Mixin(ClientPacketListener.class)
public abstract class PacketListenerMixin {
  @Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
  private void kwamster$velocity(ClientboundSetEntityMotionPacket p, CallbackInfo ci) {
    ClientCapture.velocity(p);
  }

  @Inject(method = "handleSystemChat", at = @At("TAIL"))
  private void kwamster$system(ClientboundSystemChatPacket p, CallbackInfo ci) {
    ClientCapture.systemMessage(p.content().getString(), p.overlay());
  }

  @Inject(method = "handleDamageEvent", at = @At("TAIL"))
  private void kwamster$damage(ClientboundDamageEventPacket p, CallbackInfo ci) {
    ClientCapture.damage(p);
  }

  @Inject(method = "handleSoundEvent", at = @At("TAIL"))
  private void kwamster$sound(ClientboundSoundPacket p, CallbackInfo ci) {
    ClientCapture.sound(p);
  }

  @Inject(method = "handleAnimate", at = @At("TAIL"))
  private void kwamster$animate(ClientboundAnimatePacket p, CallbackInfo ci) {
    ClientCapture.animate(p);
  }

  @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
  private void kwamster$combatKill(ClientboundPlayerCombatKillPacket p, CallbackInfo ci) {
    ClientCapture.combatKill(p);
  }

  @Inject(method = "setSubtitleText", at = @At("TAIL"))
  private void kwamster$subtitle(ClientboundSetSubtitleTextPacket p, CallbackInfo ci) {
    ClientCapture.subtitle(p.text().getString());
  }

  @Inject(method = "setTitleText", at = @At("TAIL"))
  private void kwamster$title(ClientboundSetTitleTextPacket p, CallbackInfo ci) {
    ClientCapture.title(p.text().getString());
  }
}
