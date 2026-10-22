package dev.kwamster.mixin;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.kwamster.combat.tracking.ClientCapture;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
 @Inject(method="startAttack",at=@At("HEAD"))private void kwamster$empty(CallbackInfoReturnable<Boolean> ci){ClientCapture.emptySwing();}
}
