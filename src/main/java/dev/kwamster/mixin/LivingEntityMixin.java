package dev.kwamster.mixin;

import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
  @Inject(method = "jumpFromGround", at = @At("TAIL"))
  private void kwamster$jump(CallbackInfo ci) {
    ClientCapture.jumped((LivingEntity) (Object) this);
  }
}
