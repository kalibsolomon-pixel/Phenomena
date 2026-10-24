package dev.kwamster;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.ClientCapture;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

public final class ModPerformanceTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(mc -> KwamsterClient.resetConfig());
    PerformanceScenario.run(
        ctx,
        new String[] {"Inactive", "Tracking", "HUD", "Recording", "Manual", "Manual diagnostics"},
        mode -> {
          if (mode.equals("flush")) {
            KwamsterClient.engine.finish(
                ClientCapture.now(), FightRecord.Outcome.UNKNOWN, "Performance checkpoint");
            return;
          }
          KwamsterClient.recording.shutdown(
              ClientCapture.now(), ClientCapture.clientTick(), "Performance segment");
          KwamsterClient.config.tracking = !mode.equals("Inactive");
          // The retired overlay is still measurable when enabled explicitly.
          KwamsterClient.config.hudOverlay = mode.equals("HUD") || mode.equals("Recording");
          KwamsterClient.config.recording = mode.equals("Recording");
          KwamsterClient.config.sound = false;
          KwamsterClient.config.summary = false;
          KwamsterClient.diagnostics.enabled(mode.equals("Manual diagnostics"));
          if (mode.startsWith("Manual"))
            KwamsterClient.recording.start(
                ClientCapture.currentHost(),
                net.minecraft.client.Minecraft.getInstance().player.getName().getString(),
                ClientCapture.currentWorld(),
                ClientCapture.now(),
                ClientCapture.clientTick());
        },
        "mod.json");
  }
}
