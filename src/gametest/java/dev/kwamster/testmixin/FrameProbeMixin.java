package dev.kwamster.testmixin;

import dev.kwamster.FrameProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FrameProbeMixin {
  @Inject(method = "runTick", at = @At("HEAD"))
  private void kwamster$frameStart(boolean render, CallbackInfo ci) {
    FrameProbe.begin(render);
  }

  @Inject(method = "runTick", at = @At("RETURN"))
  private void kwamster$frameEnd(boolean render, CallbackInfo ci) {
    FrameProbe.end(render);
  }
}
