package dev.kwamster;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.ClientCapture;
import dev.kwamster.config.Config;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;

public final class ModPerformanceTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(mc -> KwamsterClient.resetConfig());
    PerformanceScenario.run(
        ctx,
        new String[] {"Inactive", "Tracking", "HUD", "Recording"},
        mode -> {
          if (mode.equals("flush")) {
            KwamsterClient.engine.finish(
                ClientCapture.now(), FightRecord.Outcome.UNKNOWN, "Performance checkpoint");
            return;
          }
          KwamsterClient.engine.finish(
              ClientCapture.now(), FightRecord.Outcome.UNKNOWN, "Performance segment");
          KwamsterClient.config.tracking = !mode.equals("Inactive");
          // The retired overlay is still measurable when enabled explicitly.
          KwamsterClient.config.hudOverlay = mode.equals("HUD") || mode.equals("Recording");
          KwamsterClient.config.recording = mode.equals("Recording");
          KwamsterClient.config.sound = false;
          KwamsterClient.config.summary = false;
        },
        "mod.json");
  }
}
