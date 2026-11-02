package dev.phenomena;

import dev.phenomena.gui.screens.PhenomenaScreen;
import dev.phenomena.gui.ui.UiState;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.world.entity.LivingEntity;

/**
 * Real client dispatcher, network boundary, combat capture, persistence and shared GUI controller.
 */
public final class ManualClientTest {
  public static final AtomicInteger SERVER_COMMANDS = new AtomicInteger();

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  public static void exercise(ClientGameTestContext ctx, TestSingleplayerContext world) {
    ctx.setScreen(() -> null);
    int before = ctx.computeOnClient(mc -> PhenomenaClient.history.size());
    ctx.runOnClient(
        mc -> {
          PhenomenaClient.config.recording = false; // explicit manual request still saves
          PhenomenaClient.config.tracking = false; // explicit manual request still observes
          mc.player.connection.sendCommand("kwm start");
          check(PhenomenaClient.recording.manual(), "client /kwm start unavailable");
          mc.player.connection.sendCommand("kwm start");
        });
    world
        .getServer()
        .runCommand(
            "summon minecraft:husk 0.5 -60 2.8"
                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:100f}");
    ctx.waitTicks(15);
    ctx.runOnClient(
        mc -> {
          LivingEntity target = null;
          for (var e : mc.level.entitiesForRendering())
            if (e instanceof LivingEntity l && e != mc.player) {
              target = l;
              break;
            }
          check(target != null, "manual combat target missing");
          mc.gameMode.attack(mc.player, target);
        });
    ctx.waitTicks(15);
    ctx.runOnClient(mc -> mc.player.connection.sendCommand("kwm end"));
    ctx.waitTicks(5);
    try {
      check(PhenomenaClient.store.await(20000), "manual export worker did not drain");
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
    ctx.runOnClient(
        mc -> {
          check(!PhenomenaClient.recording.manual(), "/kwm end did not stop");
          check(PhenomenaClient.history.size() == before + 1, "manual recording duplicated");
          var f = PhenomenaClient.history.getFirst();
          check(f.manual(), "manual metadata missing");
          check(f.statistics().hits >= 1, "real manual combat event missing");
          check(
              PhenomenaClient.autoReports.availability(f.id()).startsWith("Report ready"),
              "automatic manual HTML missing");
          mc.player.connection.sendCommand("kwm end");
          check(PhenomenaClient.history.size() == before + 1, "duplicate /kwm end exported");
          PhenomenaClient.config.recording = true;
          PhenomenaClient.config.tracking = true;
          UiState.SESSION.tab = UiState.Tab.OVERVIEW;
        });
    ctx.setScreen(PhenomenaScreen::new);
    ctx.waitTicks(2);
    PhenomenaGameTest.click(ctx, "Start Manual Recording");
    ctx.runOnClient(
        mc -> check(PhenomenaClient.recording.manual(), "GUI start did not share controller"));
    ctx.takeScreenshot("phenomena-manual-recording");
    ctx.setScreen(() -> null);
    world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0.5 80 0.5");
    ctx.waitTicks(30);
    ctx.runOnClient(
        mc ->
            check(PhenomenaClient.recording.manual(), "dimension replacement ended manual parent"));
    world.getServer().runCommand("execute in minecraft:overworld run tp @a 0.5 -60 0.5");
    ctx.waitTicks(30);
    ctx.runOnClient(
        mc ->
            check(
                PhenomenaClient.recording.manual(),
                "return to original dimension ended manual parent"));
    ctx.setScreen(PhenomenaScreen::new);
    ctx.waitTicks(2);
    PhenomenaGameTest.click(ctx, "End Manual Recording");
    ctx.runOnClient(
        mc -> check(!PhenomenaClient.recording.manual(), "GUI end did not share controller"));
    ctx.setScreen(() -> null);
    ctx.runOnClient(mc -> mc.player.connection.sendCommand("kwm start"));
    ctx.waitTicks(3);
    check(SERVER_COMMANDS.get() == 0, "/kwm command was sent to server");
    // The enclosing world closes next, exercising the real disconnect callback.
  }

  public static void afterDisconnect(ClientGameTestContext ctx) {
    ctx.waitTicks(6);
    ctx.runOnClient(
        mc -> {
          check(!PhenomenaClient.recording.manual(), "manual session lost on disconnect");
          var f = PhenomenaClient.history.getFirst();
          check(f.manual(), "disconnect did not save session");
          check(
              f.outcome() == dev.phenomena.combat.fights.FightRecord.Outcome.INTERRUPTED,
              "disconnect classification wrong");
        });
  }
}
