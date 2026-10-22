package dev.kwamster.mixin;

import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
  @Inject(
      method = "startAttack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;)V"))
  private void kwamster$empty(CallbackInfoReturnable<Boolean> ci) {
    ClientCapture.emptySwing();
  }
}
