package dev.phenomena.mixin;

import dev.phenomena.combat.tracking.ClientCapture;
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
  private void phenomena$empty(CallbackInfoReturnable<Boolean> ci) {
    ClientCapture.emptySwing();
  }
}
