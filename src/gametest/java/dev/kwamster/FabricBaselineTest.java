package dev.kwamster;

import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricBaselineTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext ctx) {
    if (FabricLoader.getInstance().isModLoaded("kwamster"))
      throw new AssertionError("Baseline must exclude KWAMSTER");
    PerformanceScenario.run(ctx, new String[] {"Fabric baseline"}, m -> {}, "baseline.json");
  }
}
