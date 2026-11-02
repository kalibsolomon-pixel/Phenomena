package dev.phenomena;

import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.tracking.ClientCapture;
import dev.phenomena.gui.screens.PhenomenaScreen;
import dev.phenomena.gui.ui.UiState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/**
 * Native visual-capture pass for the Spxctra UI: generates three genuine fights, then captures
 * every major screen and representative interaction states at the required window sizes and GUI
 * scales through the real client. Screenshots land in the run directory's screenshots folder.
 */
public final class UiScreenshotClientTest implements FabricClientGameTest {

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static void await(ClientGameTestContext ctx) {
    try {
      check(PhenomenaClient.store.await(10000), "storage queue did not drain");
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
    ctx.waitTicks(2);
  }

  private static LivingEntity husk(net.minecraft.client.Minecraft mc) {
    for (var e : mc.level.entitiesForRendering())
      if (e instanceof LivingEntity l && e != mc.player && e.getZ() > 1) return l;
    throw new AssertionError("No husk");
  }

  private static void aimAt(net.minecraft.client.Minecraft mc, LivingEntity target, double dx, double dy) {
    var p = mc.player;
    var eye = p.getEyePosition();
    var aim = target.getBoundingBox().getCenter().add(dx, dy, 0);
    var d = aim.subtract(eye);
    float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
    float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
    p.setYRot(yaw);
    p.setXRot(pitch);
    p.setYHeadRot(yaw);
  }

  private static void attack(net.minecraft.client.Minecraft mc) {
    var h = husk(mc);
    mc.gameMode.attack(mc.player, h);
    mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
  }

  private static void shot(ClientGameTestContext ctx, String name) {
    ctx.getInput().setCursorPos(2, 2);
    ctx.waitTicks(3);
    ctx.takeScreenshot(name);
  }

  /** Same as {@link #shot} but leaves the cursor where it is (hover-state captures). */
  private static void hoverShot(ClientGameTestContext ctx, String name, double x, double y) {
    ctx.getInput().setCursorPos(x, y);
    ctx.waitTicks(4);
    ctx.takeScreenshot(name);
  }

  private static double[] centerOf(String label) {
    var mc = net.minecraft.client.Minecraft.getInstance();
    for (var child : mc.screen.children())
      if (child instanceof AbstractWidget w
          && w.visible
          && w.getMessage().getString().startsWith(label)) {
        double s = mc.getWindow().getGuiScale();
        return new double[] {(w.getX() + w.getWidth() / 2.0) * s, (w.getY() + w.getHeight() / 2.0) * s};
      }
    return null;
  }

  private static void openDashboard(ClientGameTestContext ctx) {
    ctx.runOnClient(
        mc -> {
          UiState.SESSION.tab = UiState.Tab.OVERVIEW;
          mc.setScreen(new PhenomenaScreen());
        });
    ctx.waitTicks(3);
  }

  private static void tab(ClientGameTestContext ctx, UiState.Tab t, String pass, String name) {
    ctx.getInput().pressKey(GLFW.GLFW_KEY_1 + t.ordinal());
    ctx.waitTicks(3);
    shot(ctx, pass + "-" + name);
  }

  private static void sixTabs(ClientGameTestContext ctx, String pass) {
    tab(ctx, UiState.Tab.OVERVIEW, pass, "overview");
    tab(ctx, UiState.Tab.OFFENSE, pass, "offense");
    tab(ctx, UiState.Tab.DEFENSE, pass, "defense");
    tab(ctx, UiState.Tab.FLOW, pass, "flow");
    tab(ctx, UiState.Tab.HISTORY, pass, "history");
    tab(ctx, UiState.Tab.SETTINGS, pass, "settings");
  }

  private static void setScale(ClientGameTestContext ctx, int scale) {
    ctx.runOnClient(
        mc -> {
          mc.options.guiScale().set(scale);
          mc.resizeDisplay();
        });
    ctx.waitTicks(3);
  }

  @Override
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(
        mc -> {
          PhenomenaClient.resetConfig();
          PhenomenaClient.history.clear();
          PhenomenaClient.historyChanged();
        });

    try (var world = ctx.worldBuilder().create()) {
      world.getServer().runCommand("gamemode survival @a");
      world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
      world
          .getServer()
          .runCommand(
              "summon minecraft:husk 0.5 -60 2.8"
                  + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000}]}");
      ctx.waitTicks(30);

      // Three genuine fights: cooldown-spaced hits, sprint and jump variants, empty swings.
      for (int round = 0; round < 3; round++) {
        ctx.waitTicks(20);
        for (int i = 0; i < 10; i++) {
          int k = i;
          ctx.runOnClient(
              mc -> {
                aimAt(mc, husk(mc), (k % 3 - 1) * .2, (k % 2) * .4);
                if (k == 4) mc.player.setSprinting(true);
                if (k == 7) {
                  mc.player.setJumping(true);
                  mc.player.jumpFromGround();
                }
                attack(mc);
                if (k == 5) mc.player.setSprinting(false);
              });
          ctx.waitTicks(9);
        }
        ctx.runOnClient(
            mc -> {
              aimAt(mc, husk(mc), 2.5, 0); // deliberate miss away from the target
              attack(mc);
            });
        ctx.waitTicks(4);
        ctx.runOnClient(
            mc ->
                PhenomenaClient.engine.finish(
                    ClientCapture.now(), FightRecord.Outcome.INTERRUPTED, "Visual capture"));
        await(ctx);
      }
      ctx.runOnClient(
          mc -> check(PhenomenaClient.history.size() >= 3, "expected at least 3 fights"));

      // ---- Pass 1: 1920x1080, GUI scale 3 — full screen set + interaction states ----
      ctx.getInput().resizeWindow(1920, 1080);
      setScale(ctx, 3);
      openDashboard(ctx);
      sixTabs(ctx, "f1080s3");

      // Interaction states on History: selection, dropdown, delete confirmation.
      ctx.getInput().pressKey(GLFW.GLFW_KEY_5);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> {
            var list =
                (AbstractWidget)
                    mc.screen.children().stream()
                        .filter(c -> c instanceof dev.phenomena.gui.components.FightList)
                        .findFirst()
                        .orElse(null);
            check(list != null, "history list present");
            double s = mc.getWindow().getGuiScale();
            ctx_cursor[0] = (list.getX() + 60) * s;
            ctx_cursor[1] = (list.getY() + list.getHeight() / 2.0) * s;
          });
      hoverShot(ctx, "f1080s3-history-hover", ctx_cursor[0], ctx_cursor[1]);
      ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      ctx.waitTicks(3);
      shot(ctx, "f1080s3-history-selected");
      var dd = ctx.computeOnClient(mc -> centerOf("Sort: Newest"));
      check(dd != null, "sort dropdown present");
      hoverShot(ctx, "f1080s3-dropdown", dd[0], dd[1]);
      ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      ctx.waitTicks(2);
      shot(ctx, "f1080s3-dropdown-open");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);
      var del = ctx.computeOnClient(mc -> centerOf("Delete"));
      check(del != null, "delete present");
      hoverShot(ctx, "f1080s3-danger-hover", del[0], del[1]);
      ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      ctx.waitTicks(3);
      shot(ctx, "f1080s3-dialog");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);

      // Settings interactions: toggle switch hover, slider hover.
      ctx.getInput().pressKey(GLFW.GLFW_KEY_6);
      ctx.waitTicks(2);
      var toggle = ctx.computeOnClient(mc -> centerOf("Combat tracking"));
      if (toggle != null) hoverShot(ctx, "f1080s3-toggle-hover", toggle[0], toggle[1]);
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.settings = UiState.SettingsSection.EXCHANGES;
            ((PhenomenaScreen) mc.screen).rebuild();
          });
      ctx.waitTicks(2);
      shot(ctx, "f1080s3-settings-exchanges");
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.settings = UiState.SettingsSection.DATA;
            ((PhenomenaScreen) mc.screen).rebuild();
          });
      ctx.waitTicks(2);
      shot(ctx, "f1080s3-settings-data");

      // Overview stat-card tooltip.
      ctx.getInput().pressKey(GLFW.GLFW_KEY_1);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> {
            var vp = dev.phenomena.gui.components.Viewport.get();
            double s = mc.getWindow().getGuiScale();
            ctx_cursor[0] = (vp.x() + 40) * s;
            ctx_cursor[1] = (vp.y() + 46) * s;
          });
      hoverShot(ctx, "f1080s3-stat-tooltip", ctx_cursor[0], ctx_cursor[1]);

      // ---- Pass 2: 1920x1080, GUI scale 2 ----
      setScale(ctx, 2);
      openDashboard(ctx);
      tab(ctx, UiState.Tab.OVERVIEW, "f1080s2", "overview");
      tab(ctx, UiState.Tab.HISTORY, "f1080s2", "history");
      tab(ctx, UiState.Tab.FLOW, "f1080s2", "flow");

      // ---- Pass 3: 1024x768, GUI scale 2 — full set ----
      ctx.getInput().resizeWindow(1024, 768);
      setScale(ctx, 2);
      openDashboard(ctx);
      sixTabs(ctx, "f1024s2");

      // ---- Pass 4: 854x480, GUI scale 1 — tight layout ----
      ctx.getInput().resizeWindow(854, 480);
      setScale(ctx, 1);
      openDashboard(ctx);
      tab(ctx, UiState.Tab.OVERVIEW, "f854s1", "overview");
      tab(ctx, UiState.Tab.HISTORY, "f854s1", "history");
      tab(ctx, UiState.Tab.SETTINGS, "f854s1", "settings");

      // ---- Pass 5: 1024x768, GUI scale 4 — large-scale density ----
      ctx.getInput().resizeWindow(1024, 768);
      setScale(ctx, 4);
      openDashboard(ctx);
      tab(ctx, UiState.Tab.OVERVIEW, "f1024s4", "overview");
      tab(ctx, UiState.Tab.OFFENSE, "f1024s4", "offense");

      // Restore defaults for the run directory.
      setScale(ctx, 2);
      ctx.runOnClient(mc -> mc.options.save());
    }
  }

  private static final double[] ctx_cursor = new double[2];
}
