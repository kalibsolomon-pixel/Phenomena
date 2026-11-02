package dev.phenomena;

import dev.phenomena.combat.fights.FightRecord;
import dev.phenomena.combat.tracking.ClientCapture;
import dev.phenomena.gui.components.Viewport;
import dev.phenomena.gui.screens.PhenomenaScreen;
import dev.phenomena.gui.ui.UiState;
import dev.phenomena.platform.SystemOpener;
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
 * presses. Process launches are recorded instead of executed unless -Dphenomena.realOpen=true.
 */
public final class PhenomenaGameTest implements FabricClientGameTest {
  private static final List<List<String>> LAUNCHED = new CopyOnWriteArrayList<>();

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  /** Clicks a visible dashboard widget by its label through the real mouse path. */
  static void click(ClientGameTestContext ctx, String label) {
    double[] at =
        ctx.computeOnClient(
            mc -> {
              for (var child : mc.screen.children())
                if (child instanceof AbstractWidget w
                    && w.visible
                    && w.getMessage().getString().equals(label)) {
                  if (mc.screen instanceof PhenomenaScreen ks) ks.setFocused(w); // scrolls into view
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
          check(mc.screen instanceof PhenomenaScreen, "dashboard not open at " + where);
          var screen = (PhenomenaScreen) mc.screen;
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
      check(PhenomenaClient.store.await(10000), "storage queue did not drain");
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
              attackNow(mc); // two actual vanilla observations in one client tick
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
        ctx.runOnClient(PhenomenaGameTest::attackNow);
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
            PhenomenaClient.engine.finish(
                ClientCapture.now(),
                FightRecord.Outcome.UNKNOWN,
                "Integration test fight " + (n + 1)));
  }

  @Override
  public void runTest(ClientGameTestContext ctx) {
    NotificationClientTest.startup(ctx);
    boolean realOpen = Boolean.getBoolean("phenomena.realOpen");
    if (!realOpen)
      SystemOpener.setLauncher(
          cmd -> {
            LAUNCHED.add(cmd);
            return 0;
          });
    ctx.runOnClient(
        mc -> {
          mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
          PhenomenaClient.resetConfig();
          PhenomenaClient.history.clear();
          PhenomenaClient.historyChanged();
          check(
              PhenomenaClient.openKey.getDefaultKey().getValue() == GLFW.GLFW_KEY_K,
              "default key is K");
          check(!PhenomenaClient.config.hudOverlay, "HUD retired by default");
        });
    ctx.getInput().resizeWindow(1280, 720);
    ctx.setScreen(PhenomenaScreen::new);
    ctx.waitTicks(2);
    checkLayout(ctx, "empty overview");
    shot(ctx, "phenomena-empty-overview");
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
      NotificationClientTest.joined(ctx);

      // Keybind opens and closes the dashboard; gameplay keys stay out of the world while open.
      ctx.getInput().pressKey(GLFW.GLFW_KEY_K);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> check(mc.screen instanceof PhenomenaScreen, "K did not open the dashboard"));
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
                PhenomenaClient.history.size() == 4,
                "expected 4 fights, got " + PhenomenaClient.history.size());
            int hits = PhenomenaClient.history.stream().mapToInt(f -> f.statistics().hits).sum();
            int empty =
                PhenomenaClient.history.stream().mapToInt(f -> f.statistics().excludedSwings).sum();
            check(hits >= 12, "too few correlated hits: " + hits);
            check(
                PhenomenaClient.history.stream()
                        .mapToInt(f -> f.statistics().deduplicatedAttacks)
                        .sum()
                    >= 1,
                "real same-tick attack calls were not deduplicated");
            check(empty >= 4, "unrelated swings were not excluded: " + empty);
            var all = PhenomenaClient.history;
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
                all.stream().mapToInt(f -> f.statistics().serverCrits).sum() >= 1,
                "server critical-hit animation correlated with a confirmed hit");
            check(
                all.stream().allMatch(f -> f.schemaVersion() == FightRecord.SCHEMA),
                "new recordings use current schema");
            check(
                PhenomenaClient.history.stream().allMatch(f -> f.statistics().reachCount > 0),
                "reach recorded");
            check(
                "Husk".equals(PhenomenaClient.history.getFirst().opponentLabel()), "opponent label");
            for (var f : PhenomenaClient.history) {
              try {
                var report =
                    dev.phenomena.platform.SafePaths.exportFolder(PhenomenaClient.dataDir, f, true)
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
                  Files.isRegularFile(PhenomenaClient.store.recordingFile(f.id())),
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
      int beforeSmash = ctx.computeOnClient(mc -> PhenomenaClient.engine.stats().hits);
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
                  PhenomenaClient.engine.stats().hits > beforeSmash,
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
      ctx.setScreen(PhenomenaScreen::new);
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
            check(PhenomenaClient.engine.engaged(), "tracking while GUI open");
            PhenomenaClient.engine.finish(
                ClientCapture.now(),
                FightRecord.Outcome.INTERRUPTED,
                "Integration test: disposable");
            var measurements =
                PhenomenaClient.history.getFirst().events().stream()
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

      // Defense through vanilla packets: attributed incoming damage, knockback and health, a jump
      // right after it, then a real off-hand shield block (no damage event, block sound only).
      world.getServer().runCommand("item replace entity @a weapon.offhand with minecraft:shield");
      ctx.waitTicks(5);
      ctx.runOnClient(
          mc -> {
            aimAt(mc, husk(mc), 0, 0);
            attackNow(mc);
          });
      ctx.waitTicks(4);
      for (int i = 0; i < 3; i++) {
        // A jump-reset attempt: the jump leaves the ground just before the hit arrives.
        for (int t = 0; t < 2; t++) {
          ctx.runOnClient(mc -> mc.options.keyJump.setDown(true));
          ctx.waitTick();
        }
        ctx.runOnClient(mc -> mc.options.keyJump.setDown(false));
        world
            .getServer()
            .runCommand("damage @p 1 minecraft:mob_attack by @e[type=minecraft:husk,limit=1]");
        ctx.waitTicks(16);
        world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
        ctx.waitTicks(4);
      }
      world.getServer().runCommand("damage @p 1 minecraft:fall");
      ctx.waitTicks(4);
      world.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:air");
      ctx.waitTicks(3);
      // Look slightly over the husk: the use key then raises the shield instead of interacting.
      ctx.runOnClient(mc -> mc.player.setXRot(-25));
      // The test input's mouse hold does not reach keyUse while the window is ungrabbed, so the
      // key mapping is held directly; vanilla then raises the off-hand shield itself.
      ctx.runOnClient(
          mc -> {
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.OFF_HAND);
          });
      for (int t = 0; t < 12; t++) {
        ctx.runOnClient(mc -> mc.options.keyUse.setDown(true));
        ctx.waitTick();
      }
      String blockingState =
          ctx.computeOnClient(
              mc ->
                  "useDown="
                      + mc.options.keyUse.isDown()
                      + " hit="
                      + (mc.hitResult == null ? null : mc.hitResult.getType())
                      + " blocking="
                      + mc.player.isBlocking()
                      + " using="
                      + mc.player.isUsingItem()
                      + " offhand="
                      + mc.player.getOffhandItem()
                      + " incomingBefore="
                      + PhenomenaClient.engine.stats().incoming);
      world
          .getServer()
          .runCommand("damage @p 1 minecraft:mob_attack by @e[type=minecraft:husk,limit=1]");
      for (int t = 0; t < 4; t++) {
        ctx.runOnClient(mc -> mc.options.keyUse.setDown(true));
        ctx.waitTick();
      }
      ctx.runOnClient(mc -> mc.options.keyUse.setDown(false));
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc -> {
            var st = PhenomenaClient.engine.stats();
            PhenomenaClient.LOG.info(
                "Shield diagnostic: {} incomingAfter={} localBlocks={} engaged={}",
                blockingState,
                st.incoming,
                st.localShieldBlocks,
                PhenomenaClient.engine.engaged());
            check(st.incoming >= 3, "attributed incoming hits: " + st.incoming);
            check(st.unattributedIncoming >= 1, "fall damage stays unattributed");
            check(st.localShieldBlocks >= 1, "real shield block observed from the block sound");
            PhenomenaClient.engine.finish(
                ClientCapture.now(), FightRecord.Outcome.UNKNOWN, "Integration test: defense");
            var f = PhenomenaClient.history.getFirst();
            var a = dev.phenomena.analytics.FightAnalytics.of(f);
            check(a.attributedIncoming >= 3, "analytics agree on attribution");
            check(
                a.incoming.stream().anyMatch(i -> i.knockback() != null),
                "knockback velocity linked to an incoming hit");
            check(
                a.incoming.stream().anyMatch(i -> i.damage() != null),
                "health drop linked to an incoming hit");
            check(
                a.incoming.stream()
                    .filter(i -> i.attributed())
                    .anyMatch(i -> i.jumpOffsetTicks() != null),
                "jump paired with an attributed hit on the tick axis: jumps="
                    + f.events().stream()
                        .filter(e -> e.kind() == dev.phenomena.combat.events.CombatEvent.Kind.JUMP)
                        .count()
                    + " inputs="
                    + f.events().stream()
                        .filter(
                            e -> e.kind() == dev.phenomena.combat.events.CombatEvent.Kind.JUMP_INPUT)
                        .count()
                    + " classes="
                    + a.incoming.stream().map(i -> i.jumpClass() + "@" + i.tick()).toList());
          });
      world.getServer().runCommand("item replace entity @a weapon.offhand with minecraft:air");
      world.getServer().runCommand("effect give @a minecraft:instant_health 1 4 true");
      ctx.waitTicks(10);
      await(ctx);

      // ---- Overview (screenshots at 1600x900, GUI scale 3)
      ctx.getInput().resizeWindow(1600, 900);
      ctx.runOnClient(
          mc -> {
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            UiState.SESSION.tab = UiState.Tab.OVERVIEW;
          });
      ctx.setScreen(PhenomenaScreen::new);
      checkLayout(ctx, "overview");
      shot(ctx, "phenomena-overview");
      click(ctx, "Reach");
      ctx.runOnClient(
          mc ->
              check(
                  UiState.SESSION.trend == dev.phenomena.analytics.TrendMetric.REACH,
                  "trend switch"));
      shot(ctx, "phenomena-overview-reach-trend");
      click(ctx, "Accuracy");

      // ---- Offense, Defense and Fight Flow, each section
      click(ctx, "Offense");
      ctx.runOnClient(mc -> check(UiState.SESSION.tab == UiState.Tab.OFFENSE, "offense tab"));
      for (var section : UiState.OffenseSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "offense " + section);
        shot(ctx, "phenomena-offense-" + section.name().toLowerCase(Locale.ROOT));
      }
      click(ctx, "Aim");
      click(ctx, "Aggregate density");
      shot(ctx, "phenomena-offense-aim-density");
      // Scroll position is preserved per section across tab switches.
      ctx.runOnClient(mc -> ((PhenomenaScreen) mc.screen).setScroll(60));
      click(ctx, "Settings");
      click(ctx, "Offense");
      ctx.runOnClient(mc -> check(UiState.SESSION.scroll() > 0, "scroll restored"));
      click(ctx, "Defense");
      for (var section : UiState.DefenseSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "defense " + section);
        shot(ctx, "phenomena-defense-" + section.name().toLowerCase(Locale.ROOT));
      }
      click(ctx, "Fight Flow");
      ctx.runOnClient(mc -> check(UiState.SESSION.tab == UiState.Tab.FLOW, "flow tab"));
      for (var section : UiState.FlowSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "flow " + section);
        shot(ctx, "phenomena-flow-" + section.name().toLowerCase(Locale.ROOT));
      }
      click(ctx, "Combos");
      click(ctx, "Older fight");
      click(ctx, "Next combo");
      ctx.runOnClient(
          mc ->
              check(UiState.SESSION.focus == 1 && UiState.SESSION.combo >= -1, "flow focus moved"));
      shot(ctx, "phenomena-flow-combo-selected");
      click(ctx, "Newer fight");

      // ---- History
      click(ctx, "History");
      checkLayout(ctx, "history");
      String disposable = ctx.computeOnClient(mc -> PhenomenaClient.history.getFirst().id());
      String keep = ctx.computeOnClient(mc -> PhenomenaClient.history.get(1).id());
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.selectedFight = keep;
            ((PhenomenaScreen) mc.screen).rebuild();
          });
      shot(ctx, "phenomena-history");
      // Filter through the dropdown with the keyboard.
      click(ctx, "All outcomes v");
      ctx.runOnClient(mc -> check(((PhenomenaScreen) mc.screen).popupOpen(), "dropdown opened"));
      ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
      ctx.getInput().pressKey(GLFW.GLFW_KEY_DOWN);
      shot(ctx, "phenomena-history-filter");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  UiState.SESSION.outcome == dev.phenomena.analytics.HistoryQuery.OutcomeFilter.LOSS,
                  "filter set to Loss: " + UiState.SESSION.outcome));
      click(ctx, "Loss v");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  mc.screen instanceof PhenomenaScreen && !((PhenomenaScreen) mc.screen).popupOpen(),
                  "Escape closes the dropdown only"));
      ctx.runOnClient(
          mc -> {
            check(UiState.SESSION.selectedFight == null, "filtering out the selection clears it");
            UiState.SESSION.outcome = dev.phenomena.analytics.HistoryQuery.OutcomeFilter.ALL;
            UiState.SESSION.selectedFight = keep;
            ((PhenomenaScreen) mc.screen).rebuild();
          });

      // Folder buttons resolve the managed directories.
      LAUNCHED.clear();
      click(ctx, "Open Recordings Folder");
      click(ctx, "Open Exports Folder");
      ctx.waitTicks(10);
      await(ctx);
      if (!realOpen) {
        check(LAUNCHED.size() == 2, "folder launches " + LAUNCHED);
        Path fights = PhenomenaClient.store.directory().toAbsolutePath().normalize();
        check(
            LAUNCHED.get(0).getLast().equals(fights.toString()),
            "fights folder " + LAUNCHED.get(0));
        check(
            LAUNCHED
                .get(1)
                .getLast()
                .equals(
                    PhenomenaClient.dataDir
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
      shot(ctx, "phenomena-fight-detail");
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
                      PhenomenaClient.history.stream()
                          .filter(f -> f.id().equals(keep))
                          .findFirst()
                          .orElseThrow();
                  var folder =
                      dev.phenomena.platform.SafePaths.exportFolder(
                          PhenomenaClient.dataDir, record, PhenomenaClient.config.anonymizeExports);
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
            !html.contains(PhenomenaClient.history.get(1).opponent()),
            "browser report anonymized by default");
        check(html.contains("\"format\":\"phenomena-report\""), "payload embedded");
      } catch (java.io.IOException ex) {
        throw new RuntimeException(ex);
      }
      click(ctx, "Back to list");

      // Delete the disposable fight with confirmation (keyboard: move to Delete, Enter).
      ctx.runOnClient(
          mc -> {
            UiState.SESSION.selectedFight = disposable;
            ((PhenomenaScreen) mc.screen).rebuild();
          });
      click(ctx, "Delete");
      shot(ctx, "phenomena-delete-confirm");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
      ctx.waitTicks(2);
      ctx.runOnClient(
          mc ->
              check(
                  PhenomenaClient.history.stream().anyMatch(f -> f.id().equals(disposable)),
                  "Escape cancels delete"));
      click(ctx, "Delete");
      ctx.getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
      ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
      ctx.waitTicks(3);
      await(ctx);
      ctx.runOnClient(
          mc -> {
            check(
                PhenomenaClient.history.stream().noneMatch(f -> f.id().equals(disposable)),
                "fight removed from history");
            check(
                !Files.exists(PhenomenaClient.store.recordingFile(disposable)), "recording deleted");
            check(PhenomenaClient.history.size() == 5, "other fights kept");
          });

      // ---- Settings
      click(ctx, "Settings");
      checkLayout(ctx, "settings general");
      shot(ctx, "phenomena-settings");
      ctx.runOnClient(
          mc -> {
            var b =
                mc.screen.children().stream()
                    .filter(
                        c ->
                            c instanceof dev.phenomena.gui.components.KButton k
                                && k.id.equals("Fight summary in chat"))
                    .map(c -> (dev.phenomena.gui.components.KButton) c)
                    .findFirst()
                    .orElseThrow();
            b.onPress(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            check(PhenomenaClient.config.chatSummary, "toggle applied");
          });
      for (var section : UiState.SettingsSection.values()) {
        click(ctx, section.label);
        checkLayout(ctx, "settings " + section);
        shot(ctx, "phenomena-settings-" + section.name().toLowerCase(Locale.ROOT));
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
                  mc.screen instanceof PhenomenaScreen
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
                    .resolve("phenomena.json"));
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
          ctx.setScreen(PhenomenaScreen::new);
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
        ctx.setScreen(PhenomenaScreen::new);
        checkLayout(ctx, "small " + tab);
        shot(ctx, "phenomena-small-" + tab.name().toLowerCase(Locale.ROOT));
      }
      ctx.runOnClient(
          mc -> PhenomenaClient.config.density = dev.phenomena.config.Config.Density.COMPACT);
      for (var tab : new UiState.Tab[] {UiState.Tab.OFFENSE, UiState.Tab.FLOW}) {
        ctx.runOnClient(mc -> UiState.SESSION.tab = tab);
        ctx.setScreen(PhenomenaScreen::new);
        checkLayout(ctx, "small compact " + tab);
        shot(ctx, "phenomena-small-compact-" + tab.name().toLowerCase(Locale.ROOT));
      }
      ctx.runOnClient(
          mc -> PhenomenaClient.config.density = dev.phenomena.config.Config.Density.COMFORTABLE);
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
            var result = PhenomenaClient.history.getFirst();
            check(
                result.outcome() == FightRecord.Outcome.WIN,
                "system announcement resolves endpoint");
            check(
                result.matchEvidence().confidence()
                    == dev.phenomena.combat.events.Confidence.INFERRED,
                "one-name result remains inferred");
          });
      await(ctx);
      CaptureEnvironmentClientTest.exercise(ctx);
      ManualClientTest.exercise(ctx, world);
    }
    ManualClientTest.afterDisconnect(ctx);
    NotificationClientTest.reconnect(ctx);
    SystemOpener.setLauncher(null);
  }
}
