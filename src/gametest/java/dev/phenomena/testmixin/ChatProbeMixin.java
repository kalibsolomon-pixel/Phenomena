package dev.phenomena.testmixin;

import dev.phenomena.NotificationClientTest;
import dev.phenomena.recording.ExportNotifications;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only count of actual local chat insertion, not status logging. */
@Mixin(ChatComponent.class)
public abstract class ChatProbeMixin {
  @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
  private void phenomena$count(Component message, CallbackInfo ci) {
    if (message.getString().equals("[Phenomena] " + ExportNotifications.MESSAGE))
      NotificationClientTest.DELIVERED.incrementAndGet();
  }
}
