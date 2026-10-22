package dev.kwamster.feedback;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.combat.tracking.ClientCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class FeedbackController {
  public static Grade recent;
  public static long recentAt;
  private static long lastSound, lastChat;

  private FeedbackController() {}

  public static void hit(CombatEvent event, Grade grade) {
    var c = KwamsterClient.config;
    var mc = Minecraft.getInstance();
    long now = ClientCapture.now();
    var style = c.feedback[grade.ordinal()];
    if (style.visual) {
      recent = grade;
      recentAt = now;
    }
    if (c.sound && style.audio && now - lastSound > 120_000_000L) {
      var id = Identifier.tryParse(style.sound);
      if (id != null && BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
        mc.getSoundManager()
            .play(
                SimpleSoundInstance.forUI(
                    BuiltInRegistries.SOUND_EVENT.getValue(id), style.pitch, style.volume));
        lastSound = now;
      }
    }
    if (c.display.chat() && c.perHitChat && now - lastChat > 500_000_000L) {
      mc.gui
          .getChat()
          .addMessage(
              Component.literal(
                  "[KWAMSTER] " + grade + " readiness / server-reported melee contact"));
      lastChat = now;
    }
  }
}
