package dev.kwamster.mixin;

import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PacketListenerMixin {
  @Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
  private void kwamster$velocity(
      net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket p, CallbackInfo ci) {
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
}
