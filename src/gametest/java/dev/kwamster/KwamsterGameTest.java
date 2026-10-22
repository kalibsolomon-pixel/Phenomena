package dev.kwamster;

import dev.kwamster.combat.tracking.ClientCapture;
import dev.kwamster.gui.screens.*;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class KwamsterGameTest implements FabricClientGameTest {
  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @Override
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(
        mc -> {
          mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
          KwamsterClient.resetConfig();
          KwamsterClient.history.clear();
        });
    ctx.setScreen(DashboardScreen::new);
    ctx.waitTicks(2);
    ctx.takeScreenshot("kwamster-overview");
    ctx.clickScreenButton("Settings");
    ctx.waitForScreen(SettingsScreen.class);
    ctx.takeScreenshot("kwamster-settings");
    boolean before = ctx.computeOnClient(mc -> KwamsterClient.config.tracking);
    ctx.clickScreenButton("Tracking: " + (before ? "On" : "Off"));
    check(
        ctx.computeOnClient(mc -> KwamsterClient.config.tracking) != before,
        "Toggle did not change");
    ctx.clickScreenButton("Tracking: " + (!before ? "On" : "Off"));
    ctx.getInput().resizeWindow(854, 480);
    ctx.waitTicks(2);
    ctx.takeScreenshot("kwamster-small-settings");
    ctx.clickScreenButton("HUD editor");
    ctx.waitForScreen(HudEditorScreen.class);
    ctx.takeScreenshot("kwamster-hud-editor");
    ctx.clickScreenButton("Scale +");
    ctx.runOnClient(mc -> check(KwamsterClient.config.analytics.scale > 1, "Scale control failed"));
    ctx.clickScreenButton("Reset selected");
    ctx.runOnClient(
        mc -> {
          double beforeX = KwamsterClient.config.analytics.x;
          mc.screen.keyPressed(new net.minecraft.client.input.KeyEvent(262, 0, 0));
          check(KwamsterClient.config.analytics.x > beforeX, "Keyboard HUD positioning failed");
        });
    ctx.clickScreenButton("Reset selected");
    ctx.clickScreenButton("Done");
    ctx.setScreen(() -> null);
    try (var world = ctx.worldBuilder().create()) {
      world.getServer().runCommand("gamemode survival @a");
      world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
      world
          .getServer()
          .runCommand(
              "summon minecraft:husk 0.5 -60 2.8 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
      ctx.waitTicks(30);
      ctx.runOnClient(
          mc -> {
            mc.setScreen(null);
            KwamsterClient.config.tracking = true;
            check(mc.player != null, "No player");
            for (var e : mc.level.entitiesForRendering())
              if (e instanceof LivingEntity && e != mc.player && e.getZ() > 1) {
                mc.hitResult = new EntityHitResult(e);
                mc.gameMode.attack(mc.player, e);
                break;
              }
          });
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc -> {
            check(KwamsterClient.engine.stats().attempts == 1, "Capture did not record one attack");
            check(
                KwamsterClient.engine.stats().hits == 1, "Server melee damage was not correlated");
            check(KwamsterClient.engine.stats().reachCount == 1, "Reach was not recorded");
          });
      ctx.takeScreenshot("kwamster-live-hud");
      ctx.runOnClient(
          mc -> {
            KwamsterClient.engine.finish(
                ClientCapture.now(),
                dev.kwamster.combat.fights.FightRecord.Outcome.UNKNOWN,
                "Integration test");
            mc.setScreen(new FightDetailScreen(KwamsterClient.history.getFirst()));
          });
      ctx.waitTicks(2);
      ctx.takeScreenshot("kwamster-fight-details");
      boolean exported = false;
      for (int i = 0; i < 20; i++) {
        if (ctx.tryClickScreenButton("Export anonymized local report + replay")) {
          exported = true;
          break;
        }
        ctx.getInput().scroll(-1);
      }
      check(exported, "Export control not reachable by scrolling");
      try {
        check(KwamsterClient.store.await(5000), "Export queue did not drain");
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }
      check(
          java.nio.file.Files.isRegularFile(
              KwamsterClient.dataDir
                  .resolve("exports")
                  .resolve(KwamsterClient.history.getFirst().id())
                  .resolve("index.html")),
          "Exported viewer missing");
      ctx.runOnClient(mc -> mc.setScreen(null));
    }
  }
}
