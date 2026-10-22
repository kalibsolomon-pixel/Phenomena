package dev.kwamster;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.sounds.SoundSource;

/** Test-only audio preference, including after Fabric resets options between tests. */
public final class TestMuteInitializer implements ClientModInitializer {
  public void onInitializeClient() {
    ClientTickEvents.START_CLIENT_TICK.register(
        mc -> {
          var volume = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER);
          if (volume.get() != 0.0) volume.set(0.0);
        });
  }
}
