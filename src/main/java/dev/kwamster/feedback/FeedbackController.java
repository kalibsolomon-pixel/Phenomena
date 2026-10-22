package dev.kwamster.feedback;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.quality.HitQuality.Grade;
import dev.kwamster.combat.tracking.ClientCapture;
import dev.kwamster.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

public final class FeedbackController {
  /** Last visual grade; consumed only by the dormant HUD overlay. */
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
    if (c.sound && style.audio && now - lastSound > 120_000_000L && play(style, c.soundChannel))
      lastSound = now;
    if (c.perHitChat && now - lastChat > 500_000_000L) {
      mc.gui
          .getChat()
          .addMessage(
              Component.literal(
                  "[KWAMSTER] " + grade + " readiness / server-reported melee contact"));
      lastChat = now;
    }
  }

  /** Settings preview of one category's configured sound, ignoring the hit-sound toggle. */
  public static void preview(int grade) {
    var c = KwamsterClient.config;
    play(c.feedback[grade], c.soundChannel);
  }

  private static boolean play(Config.Feedback style, Config.SoundChannel channel) {
    var id = Identifier.tryParse(style.sound);
    if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) return false;
    var source =
        switch (channel) {
          case PLAYERS -> SoundSource.PLAYERS;
          case MASTER -> SoundSource.MASTER;
          default -> SoundSource.UI;
        };
    // Non-positional, relative, like vanilla UI sounds.
    Minecraft.getInstance()
        .getSoundManager()
        .play(
            new SimpleSoundInstance(
                id,
                source,
                style.volume,
                style.pitch,
                RandomSource.create(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0,
                0,
                0,
                true));
    return true;
  }
}
