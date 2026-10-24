package dev.kwamster.testmixin;

import dev.kwamster.ManualClientTest;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only proof that local commands never reach the server's command packet handler. */
@Mixin(ServerGamePacketListenerImpl.class)
public class CommandProbeMixin {
  @Inject(method = "handleChatCommand", at = @At("HEAD"))
  private void kwamster$command(ServerboundChatCommandPacket packet, CallbackInfo ci) {
    if (packet.command().startsWith("kwm")) ManualClientTest.SERVER_COMMANDS.incrementAndGet();
  }
}
