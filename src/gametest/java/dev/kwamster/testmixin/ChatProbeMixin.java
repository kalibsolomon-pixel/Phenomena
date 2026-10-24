package dev.kwamster.testmixin;

import dev.kwamster.NotificationClientTest;
import dev.kwamster.recording.ExportNotifications;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only count of actual local chat insertion, not status logging. */
@Mixin(ChatComponent.class)
public abstract class ChatProbeMixin {
  @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
  private void kwamster$count(Component message, CallbackInfo ci) {
    if (message.getString().equals("[KWAMSTER] " + ExportNotifications.MESSAGE))
      NotificationClientTest.DELIVERED.incrementAndGet();
  }
}
