package dev.kwamster;

import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.ClientCapture;
import dev.kwamster.gui.components.Viewport;
import dev.kwamster.gui.screens.KwamsterScreen;
import dev.kwamster.gui.ui.UiState;
import dev.kwamster.platform.SystemOpener;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/**
 * Real Fabric 1.21.11 client sequence: generates several genuine fights against a stationary husk
 * through vanilla input and packet paths, then drives the dashboard with real cursor clicks and key
 * presses. Process launches are recorded instead of executed unless -Dkwamster.realOpen=true.
 */
public final class KwamsterGameTest implements FabricClientGameTest {
  private static final List<List<String>> LAUNCHED = new CopyOnWriteArrayList<>();

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  /** Clicks a visible dashboard widget by its label through the real mouse path. */
  private static void click(ClientGameTestContext ctx, String label) {
    double[] at =
        ctx.computeOnClient(
            mc -> {
              for (var child : mc.screen.children())
                if (child instanceof AbstractWidget w
                    && w.visible
                    && w.getMessage().getString().equals(label)) {
                  if (mc.screen instanceof KwamsterScreen ks) ks.setFocused(w); // scrolls into view
                  double s = mc.getWindow().getGuiScale();
                  return new double[] {
                    (w.getX() + w.getWidth() / 2.0) * s, (w.getY() + w.getHeight() / 2.0) * s
                  };
                }
              return null;
            });
    check(at != null, "No widget labelled '" + label + "'");
    ctx.getInput().setCursorPos(at[0], at[1]);
    ctx.waitTick();
    ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    ctx.waitTicks(2);
  }

  /** Every widget must sit inside the window; content widgets inside the viewport frame. */
  private static void checkLayout(ClientGameTestContext ctx, String where) {
    ctx.runOnClient(
        mc -> {
          check(mc.screen instanceof KwamsterScreen, "dashboard not open at " + where);
          var screen = (KwamsterScreen) mc.screen;
          var vp = Viewport.get();
          check(vp.w() > 100 && vp.h() >= 20, where + ": viewport too small " + vp);
          for (var child : screen.children())
            if (child instanceof AbstractWidget w) {
              String name = "'" + w.getMessage().getString() + "'";
              check(
                  w.getX() >= 0 && w.getX() + w.getWidth() <= screen.width,
                  where + ": " + name + " exceeds width");
              if (screen.isContentWidget(w))
                check(
                    w.getX() >= vp.x() - 4 && w.getX() + w.getWidth() <= vp.right() + 4,
                    where + ": " + name + " outside viewport");
              else
                check(
                    w.getY() >= 0 && w.getY() + w.getHeight() <= screen.height,
                    where + ": fixed " + name + " off screen");
            }
        });
  }

  private static void shot(ClientGameTestContext ctx, String name) {
    ctx.getInput().setCursorPos(2, 2); // park the cursor so no hover tooltip covers content
    ctx.waitTicks(3);
    ctx.takeScreenshot(name);
  }

  private static void await(ClientGameTestContext ctx) {
    try {
      check(KwamsterClient.store.await(10000), "storage queue did not drain");
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
    ctx.waitTicks(2);
  }

  private static void aimAt(
      net.minecraft.client.Minecraft mc, LivingEntity target, double dx, double dy) {
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

  private static LivingEntity husk(net.minecraft.client.Minecraft mc) {
    for (var e : mc.level.entitiesForRendering())
      if (e instanceof LivingEntity l && e != mc.player && e.getZ() > 1) return l;
    throw new AssertionError("No husk");
  }

  /** Direct vanilla attack call at the current aim, as the click path would make it. */
  private static void attackNow(net.minecraft.client.Minecraft mc) {
    var h = husk(mc);
    mc.gameMode.attack(mc.player, h);
    mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
  }

  /**
   * One real engagement through vanilla paths: left clicks at varied aim and cooldown, empty swings
   * at the air, a quick follow-up during invulnerability (no server report), sprinting attacks and
   * attacks while falling after a jump.
   */
  private static void fight(
      ClientGameTestContext ctx, TestSingleplayerContext world, int n, double distance) {
    world.getServer().runCommand("tp @a 0.5 -60 " + (2.8 - distance) + " 0 0");
    ctx.waitTicks(10);
    double[][] offsets = {{0, 0}, {.18, .5}, {-.2, -.45}, {.25, .1}, {0, .7}, {-.1, .2}};
    int[] waits = {22, 14, 25, 11, 20, 18};
    for (int i = 0; i < offsets.length; i++) {
      int k = (i + n) % offsets.length;
      if (n == 2 && i % 2 == 0) {
        // Jump, wait for the descent, then attack: critical conditions are observed client-side.
        ctx.getInput().holdKeyFor(opts -> opts.keyJump, 2);
        for (int t = 0; t < 14; t++) {
          ctx.waitTick();
          if (ctx.computeOnClient(mc -> mc.player.fallDistance > 0 && !mc.player.onGround())) break;
        }
        ctx.runOnClient(
            mc -> {
              aimAt(mc, husk(mc), offsets[k][0] * .3, offsets[k][1]);
              attackNow(mc);
            });
      } else if (n == 1 && k % 2 == 0) {
        ctx.runOnClient(
            mc -> {
              aimAt(mc, husk(mc), offsets[k][0] * .3, offsets[k][1]);
              mc.player.setSprinting(true);
              attackNow(mc);
              mc.player.setSprinting(false);
            });
      } else {
        ctx.runOnClient(mc -> aimAt(mc, husk(mc), offsets[k][0] * .3, offsets[k][1]));
        ctx.waitTick();
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      }
      if (n == 0 && i == 1) {
        // Follow-up inside the target's invulnerability window: the server sends no damage report.
        ctx.waitTicks(3);
        ctx.runOnClient(KwamsterGameTest::attackNow);
      }
      ctx.waitTicks(waits[(i + n) % waits.length]);
      if (i == 2 || (n == 3 && i == 4)) {
        // Look away and swing at the air: an observed empty swing during the engagement.
        ctx.runOnClient(mc -> mc.player.setXRot(-80));
        ctx.waitTick();
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(12);
      }
    }
    ctx.waitTicks(10);
    ctx.runOnClient(
        mc ->
            KwamsterClient.engine.finish(
                ClientCapture.now(),
                FightRecord.Outcome.UNKNOWN,
                "Integration test fight " + (n + 1)));
  }

  @Override
  public void runTest(ClientGameTestContext ctx) {
    boolean realOpen = Boolean.getBoolean("kwamster.realOpen");
    if (!realOpen)
      SystemOpener.setLauncher(
          cmd -> {
            LAUNCHED.add(cmd);
            return 0;
          });
    ctx.runOnClient(
        mc -> {
          mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
          KwamsterClient.resetConfig();
          KwamsterClient.history.clear();
          KwamsterClient.historyChanged();
          check(
              KwamsterClient.openKey.getDefaultKey().getValue() == GLFW.GLFW_KEY_K,
              "default key is K");
          check(!KwamsterClient.config.hudOverlay, "HUD retired by default");
        });
    ctx.getInput().resizeWindow(1280, 720);
    ctx.setScreen(KwamsterScreen::new);
    ctx.waitTicks(2);
    checkLayout(ctx, "empty overview");
    shot(ctx, "kwamster-empty-overview");
    ctx.setScreen(() -> null);

    try (var world = ctx.worldBuilder().create()) {
      world.getServer().runCommand("gamemode survival @a");
      world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
      world
          .getServer()
          .runCommand(
              "summon minecraft:husk 0.5 -60 2.8"
                  + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:1000f,attributes:[{id:\"minecraft:max_health\",base:1000}]}");
      ctx.waitTicks(30);

      // Keybind opens and closes the dashboard; gameplay keys stay out of the world while open.
      ctx.getInput().pressKey(GLFW.GLFW_KEY_K);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> check(mc.screen instanceof KwamsterScreen, "K did not open the dashboard"));
      ctx.getInput().pressKey(GLFW.GLFW_KEY_K);
      ctx.waitTicks(2);
      ctx.runOnClient(mc -> check(mc.screen == null, "K did not close the dashboard"));

      // Genuine fights; tracking runs with the dashboard closed.
      double[] distances = {2.2, 2.6, 2.0, 2.85};
      for (int n = 0; n < distances.length; n++) fight(ctx, world, n, distances[n]);
      await(ctx);
      ctx.runOnClient(
          mc -> {
            check(
                KwamsterClient.history.size() == 4,
                "expected 4 fights, got " + KwamsterClient.history.size());
            int hits = KwamsterClient.history.stream().mapToInt(f -> f.statistics().hits).sum();
            int empty =
                KwamsterClient.history.stream().mapToInt(f -> f.statistics().emptyMisses).sum();
            check(hits >= 12, "too few correlated hits: " + hits);
            check(empty >= 4, "empty swings not captured: " + empty);
            var all = KwamsterClient.history;
            check(
                all.stream().mapToInt(f -> f.statistics().uncertain).sum() >= 1,
                "unreported follow-up stays uncertain");
            check(
                all.stream().mapToInt(f -> f.statistics().sprintHits).sum() >= 1,
                "sprint hits observed");
            check(
                all.stream().mapToInt(f -> f.statistics().criticalConditions).sum() >= 1,
                "critical conditions observed");
            check(
                KwamsterClient.history.stream().allMatch(f -> f.statistics().reachCount > 0),
                "reach recorded");
            check(
                "Husk".equals(KwamsterClient.history.getFirst().opponentLabel()), "opponent label");
            for (var f : KwamsterClient.history) {
              try {
                var report =
                    dev.kwamster.platform.SafePaths.exportFolder(KwamsterClient.dataDir, f, true)
                        .resolve("index.html");
                check(Files.isRegularFile(report), "automatic report before opening GUI");
                check(
                    f.events().stream()
                        .filter(e -> e.attack() != null)
                        .allMatch(e -> e.attack().measurement() != null),
                    "weapon and aim metadata captured");
              } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
              }
              check(
                  Files.isRegularFile(KwamsterClient.store.recordingFile(f.id())),
                  "recording written");
            }
          });

      // Exercise actual held-item attributes through vanilla capture, including a modified sword.
      for (String item :
          new String[] {"diamond_sword", "diamond_axe", "trident", "stick", "mace", "air"}) {
        world
            .getServer()
            .runCommand("item replace entity @a weapon.mainhand with minecraft:" + item);
        ctx.waitTicks(5);
        ctx.runOnClient(
            mc -> {
              aimAt(mc, husk(mc), 0, 0);
              attackNow(mc);
            });
        ctx.waitTicks(2);
      }
      world.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:mace");
      ctx.waitTicks(5);
      int beforeSmash = ctx.computeOnClient(mc -> KwamsterClient.engine.stats().hits);
      world.getServer().runCommand("tp @a 0.5 -56 0.5 0 0");
      for (int tick = 0; tick < 35; tick++) {
        ctx.waitTick();
        if (ctx.computeOnClient(
            mc ->
                mc.player.fallDistance > 1.5
                    && mc.player.getEyePosition().y - husk(mc).getBoundingBox().maxY < 2)) break;
      }
      ctx.runOnClient(
          mc -> {
            aimAt(mc, husk(mc), 0, .6);
            attackNow(mc);
          });
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc ->
              check(
                  KwamsterClient.engine.stats().hits > beforeSmash,
                  "mace smash source confirms melee"));
      world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
      ctx.waitTicks(10);
      world.getServer().runCommand("attribute @p minecraft:attack_speed base set 6");
      world
          .getServer()
          .runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc -> {
            aimAt(mc, husk(mc), 0, 0);
            attackNow(mc);
          });
      world.getServer().runCommand("attribute @p minecraft:attack_speed base set 4");
      ctx.waitTicks(5);

      // Engagement while the dashboard is open still tracks (GUI independence).
      ctx.setScreen(KwamsterScreen::new);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> {
            var h = husk(mc);
            aimAt(mc, h, 0, 0);
            mc.gameMode.attack(mc.player, h);
          });
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc -> {
            check(KwamsterClient.engine.engaged(), "tracking while GUI open");
            KwamsterClient.engine.finish(
                ClientCapture.now(),
                FightRecord.Outcome.INTERRUPTED,
                "Integration test: disposable");
            var measurements =
                KwamsterClient.history.getFirst().events().stream()
                    .filter(e -> e.attack() != null)
                    .map(e -> e.attack().measurement())
                    .toList();
            for (String weapon :
                new String[] {"diamond_sword", "diamond_axe", "trident", "stick", "mace", "fist"})
              check(
                  measurements.stream().anyMatch(m -> m.weapon().contains(weapon)),
                  "captured weapon " + weapon);
            check(
                measurements.stream()
                    .anyMatch(m -> m.weapon().contains("diamond_sword") && m.attackSpeed() > 3),
                "attribute modifier captured");
            check(
                measurements.stream()
                    .allMatch(m -> Math.abs(20 / m.attackSpeed() - m.cooldownTicks()) < .001),
                "effective cooldown matches attributes");
          });
      await(ctx);

      // ---- Overview (screenshots at 1600x900, GUI scale 3)
      ctx.getInput().resizeWindow(1600, 900);
      ctx.runOnClient(
          mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            UiState.SESSION.tab = UiState.Tab.OVERVIEW;
          });
      ctx.setScreen(KwamsterScreen::new);
      checkLayout(ctx, "overview");
      shot(ctx, "kwamster-overview");
      click(ctx, "Reach");
      ctx.runOnClient(
          mc ->
              check(
                  UiState.SESSION.trend == dev.kwamster.analytics.TrendMetric.REACH,
                  "trend switch"));
      shot(ctx, "kwamster-overview-reach-trend");
      click(ctx, "Accuracy");

      // ---- Combat Analytics, each section
      click(ctx, "Combat Analytics");
      ctx.runOnClient(mc -> check(UiState.SESSION.tab == UiState.Tab.ANALYTICS, "analytics tab"));
      for (var section : UiState.AnalyticsSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "analytics " + section);
        shot(ctx, "kwamster-analytics-" + section.name().toLowerCase(Locale.ROOT));
      }
      click(ctx, "Aim");
      click(ctx, "Aggregate density");
      shot(ctx, "kwamster-analytics-aim-density");
      // Scroll position is preserved per section across tab switches.
      ctx.runOnClient(mc -> ((KwamsterScreen) mc.screen).setScroll(60));
      click(ctx, "Settings");
      click(ctx, "Combat Analytics");
      ctx.runOnClient(mc -> check(UiState.SESSION.scroll() > 0, "scroll restored"));

      // ---- Fight History
      click(ctx, "Fight History");
      checkLayout(ctx, "history");
      String disposable = ctx.computeOnClient(mc -> KwamsterClient.history.getFirst().id());
      String keep = ctx.computeOnClient(mc -> KwamsterClient.history.get(1).id());
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.selectedFight = keep;
            ((KwamsterScreen) mc.screen).rebuild();
          });
      shot(ctx, "kwamster-history");
      // Filter through the dropdown with the keyboard.
      click(ctx, "All outcomes v");
      ctx.runOnClient(mc -> check(((KwamsterScreen) mc.screen).popupOpen(), "dropdown opened"));
      ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
      ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
      shot(ctx, "kwamster-history-filter");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  UiState.SESSION.outcome == dev.kwamster.analytics.HistoryQuery.OutcomeFilter.LOSS,
                  "filter set to Loss: " + UiState.SESSION.outcome));
      click(ctx, "Loss v");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  mc.screen instanceof KwamsterScreen && !((KwamsterScreen) mc.screen).popupOpen(),
                  "Escape closes the dropdown only"));
      ctx.runOnClient(
          mc -> {
            check(UiState.SESSION.selectedFight == null, "filtering out the selection clears it");
            UiState.SESSION.outcome = dev.kwamster.analytics.HistoryQuery.OutcomeFilter.ALL;
            UiState.SESSION.selectedFight = keep;
            ((KwamsterScreen) mc.screen).rebuild();
          });

      // Folder buttons resolve the managed directories.
      LAUNCHED.clear();
      click(ctx, "Open Recordings Folder");
      click(ctx, "Open Exports Folder");
      ctx.waitTicks(10);
      await(ctx);
      if (!realOpen) {
        check(LAUNCHED.size() == 2, "folder launches " + LAUNCHED);
        Path fights = KwamsterClient.store.directory().toAbsolutePath().normalize();
        check(
            LAUNCHED.get(0).getLast().equals(fights.toString()),
            "fights folder " + LAUNCHED.get(0));
        check(
            LAUNCHED
                .get(1)
                .getLast()
                .equals(
                    KwamsterClient.dataDir
                        .resolve("exports")
                        .toAbsolutePath()
                        .normalize()
                        .toString()),
            "exports folder");
      }

      // Details, then View in Browser generates and opens the report.
      click(ctx, "Details");
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc ->
              check(
                  UiState.SESSION.detail && keep.equals(UiState.SESSION.selectedFight),
                  "detail view"));
      checkLayout(ctx, "detail");
      shot(ctx, "kwamster-fight-detail");
      LAUNCHED.clear();
      click(ctx, "View in Browser");
      ctx.waitTicks(5);
      await(ctx);
      ctx.waitTicks(10);
      Path report =
          ctx.computeOnClient(
              mc -> {
                try {
                  var record =
                      KwamsterClient.history.stream()
                          .filter(f -> f.id().equals(keep))
                          .findFirst()
                          .orElseThrow();
                  var folder =
                      dev.kwamster.platform.SafePaths.exportFolder(
                          KwamsterClient.dataDir, record, KwamsterClient.config.anonymizeExports);
                  check(
                      folder.getFileName().toString().startsWith("Fight "),
                      "readable export folder " + folder);
                  return folder.resolve("index.html");
                } catch (java.io.IOException ex) {
                  throw new RuntimeException(ex);
                }
              });
      check(Files.isRegularFile(report), "browser report generated");
      if (!realOpen) {
        check(LAUNCHED.size() == 1, "browser launch " + LAUNCHED);
        String target = LAUNCHED.getFirst().getLast();
        check(
            target.equals(report.toAbsolutePath().normalize().toUri().toString())
                || target.equals(report.toAbsolutePath().normalize().toString()),
            "opened " + target);
      }
      try {
        String html = Files.readString(report);
        check(
            !html.contains(KwamsterClient.history.get(1).opponent()),
            "browser report anonymized by default");
        check(html.contains("\"format\":\"kwamster-report\""), "payload embedded");
      } catch (java.io.IOException ex) {
        throw new RuntimeException(ex);
      }
      click(ctx, "Back to list");

      // Delete the disposable fight with confirmation (keyboard: move to Delete, Enter).
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.selectedFight = disposable;
            ((KwamsterScreen) mc.screen).rebuild();
          });
      click(ctx, "Delete");
      shot(ctx, "kwamster-delete-confirm");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  KwamsterClient.history.stream().anyMatch(f -> f.id().equals(disposable)),
                  "Escape cancels delete"));
      click(ctx, "Delete");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
      ctx.waitTicks(3);
      await(ctx);
      ctx.runOnClient(
          mc -> {
            check(
                KwamsterClient.history.stream().noneMatch(f -> f.id().equals(disposable)),
                "fight removed from history");
            check(
                !Files.exists(KwamsterClient.store.recordingFile(disposable)), "recording deleted");
            check(KwamsterClient.history.size() == 4, "other fights kept");
          });

      // ---- Settings
      click(ctx, "Settings");
      checkLayout(ctx, "settings general");
      shot(ctx, "kwamster-settings");
      ctx.runOnClient(
          mc -> {
            var b =
                mc.screen.children().stream()
                    .filter(
                        c ->
                            c instanceof dev.kwamster.gui.components.KButton k
                                && k.id.equals("Fight summary in chat"))
                    .map(c -> (dev.kwamster.gui.components.KButton) c)
                    .findFirst()
                    .orElseThrow();
            b.onPress(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            check(KwamsterClient.config.chatSummary, "toggle applied");
          });
      for (var section : UiState.SettingsSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "settings " + section);
        shot(ctx, "kwamster-settings-" + section.name().toLowerCase(Locale.ROOT));
      }
      click(ctx, "Controls");
      click(ctx, "Open Minecraft Controls");
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  mc.screen
                      instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsScreen,
                  "Controls opened"));
      ctx.runOnClient(mc -> mc.screen.onClose());
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  mc.screen instanceof KwamsterScreen
                      && UiState.SESSION.settings == UiState.SettingsSection.CONTROLS,
                  "returned to dashboard"));
      // Closing saves configuration.
      ctx.runOnClient(mc -> mc.screen.onClose());
      await(ctx);
      try {
        String saved =
            Files.readString(
                net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("kwamster.json"));
        check(
            saved.contains("\"chatSummary\": true") && saved.contains("\"schemaVersion\": 2"),
            "config persisted");
      } catch (java.io.IOException ex) {
        throw new RuntimeException(ex);
      }

      // ---- GUI scale and small windows
      for (int scale : new int[] {1, 2, 3, 4}) {
        ctx.runOnClient(
            mc -> {
              mc.options.guiScale().set(scale);
              mc.resizeDisplay();
            });
        for (var tab : UiState.Tab.values()) {
          ctx.runOnClient(mc -> UiState.SESSION.tab = tab);
          ctx.setScreen(KwamsterScreen::new);
          ctx.waitTicks(1);
          checkLayout(ctx, "scale " + scale + " " + tab);
        }
      }
      ctx.runOnClient(
          mc -> {
            mc.options.guiScale().set(0);
            mc.resizeDisplay();
          });
      ctx.getInput().resizeWindow(854, 480);
      for (var tab : UiState.Tab.values()) {
        ctx.runOnClient(mc -> UiState.SESSION.tab = tab);
        ctx.setScreen(KwamsterScreen::new);
        checkLayout(ctx, "small " + tab);
        shot(ctx, "kwamster-small-" + tab.name().toLowerCase(Locale.ROOT));
      }
      ctx.runOnClient(
          mc -> KwamsterClient.config.density = dev.kwamster.config.Config.Density.COMPACT);
      ctx.runOnClient(mc -> UiState.SESSION.tab = UiState.Tab.ANALYTICS);
      ctx.setScreen(KwamsterScreen::new);
      checkLayout(ctx, "small compact analytics");
      shot(ctx, "kwamster-small-compact-analytics");
      ctx.runOnClient(
          mc -> KwamsterClient.config.density = dev.kwamster.config.Config.Density.COMFORTABLE);
      ctx.getInput().resizeWindow(1280, 720);
      ctx.setScreen(() -> null);
      ctx.runOnClient(mc -> mc.setScreen(null));
      // Real integrated-server system-chat packet path (not a manual multiplayer acceptance).
      world.getServer().runCommand("tellraw @a {\"text\":\"Round 5 started!\"}");
      ctx.waitTicks(50);
      ctx.runOnClient(
          mc -> {
            aimAt(mc, husk(mc), 0, 0);
            attackNow(mc);
          });
      ctx.waitTicks(5);
      world.getServer().runCommand("kill @e[type=minecraft:husk]");
      ctx.waitTicks(3);
      world.getServer().runCommand("tellraw @a {\"text\":\"You Won!\"}");
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc -> {
            var result = KwamsterClient.history.getFirst();
            check(
                result.outcome() == FightRecord.Outcome.WIN,
                "system announcement resolves endpoint");
            check(
                result.matchEvidence().confidence()
                    == dev.kwamster.combat.events.Confidence.INFERRED,
                "one-name result remains inferred");
          });
      await(ctx);
    }
    SystemOpener.setLauncher(null);
  }
}
