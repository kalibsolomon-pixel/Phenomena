package dev.kwamster.mixin;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.kwamster.combat.tracking.ClientCapture;
@Mixin(ClientPacketListener.class)
public abstract class PacketListenerMixin {
 @Inject(method="handleDamageEvent",at=@At("TAIL"))private void kwamster$damage(ClientboundDamageEventPacket p,CallbackInfo ci){ClientCapture.damage(p);}
}
