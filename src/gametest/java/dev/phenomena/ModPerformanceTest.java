package dev.phenomena;

import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.tracking.ClientCapture;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

public final class ModPerformanceTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(mc -> PhenomenaClient.resetConfig());
    PerformanceScenario.run(
        ctx,
        new String[] {"Inactive", "Tracking", "HUD", "Recording", "Manual", "Manual diagnostics"},
        mode -> {
          if (mode.equals("flush")) {
            PhenomenaClient.engine.finish(
                ClientCapture.now(), FightRecord.Outcome.UNKNOWN, "Performance checkpoint");
            return;
          }
          PhenomenaClient.recording.shutdown(
              ClientCapture.now(), ClientCapture.clientTick(), "Performance segment");
          PhenomenaClient.config.tracking = !mode.equals("Inactive");
          // The retired overlay is still measurable when enabled explicitly.
          PhenomenaClient.config.hudOverlay = mode.equals("HUD") || mode.equals("Recording");
          PhenomenaClient.config.recording = mode.equals("Recording");
          PhenomenaClient.config.sound = false;
          PhenomenaClient.config.summary = false;
          PhenomenaClient.diagnostics.enabled(mode.equals("Manual diagnostics"));
          if (mode.startsWith("Manual"))
            PhenomenaClient.recording.start(
                ClientCapture.currentHost(),
                net.minecraft.client.Minecraft.getInstance().player.getName().getString(),
                ClientCapture.currentWorld(),
                ClientCapture.now(),
                ClientCapture.clientTick());
        },
        "mod.json");
  }
}
