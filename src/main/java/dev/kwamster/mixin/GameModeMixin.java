package dev.kwamster.mixin;

import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeMixin {
  @Inject(method = "attack", at = @At("HEAD"))
  private void kwamster$attack(Player player, Entity target, CallbackInfo ci) {
    ClientCapture.attack(player, target);
  }
}
