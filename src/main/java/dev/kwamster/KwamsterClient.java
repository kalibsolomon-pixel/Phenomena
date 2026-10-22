package dev.kwamster;

import dev.kwamster.combat.fights.*;
import dev.kwamster.combat.tracking.*;
import dev.kwamster.config.*;
import dev.kwamster.feedback.FeedbackController;
import dev.kwamster.gui.screens.KwamsterScreen;
import dev.kwamster.hud.CombatHud;
import dev.kwamster.recording.storage.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.*;

public final class KwamsterClient implements ClientModInitializer {
  public static final Logger LOG = LoggerFactory.getLogger("kwamster");
  public static Config config = new Config();
  public static ConfigStore configStore;
  public static CombatEngine engine;
  public static KeyMapping openKey;
  public static FightStore store;
  public static Path dataDir;
  public static String status = "Local by default / K opens KWAMSTER";
  public static final List<FightRecord> history = new ArrayList<>();

  /** Incremented on every history change so derived GUI data is recomputed only when needed. */
  public static long historyVersion;

  public static void historyChanged() {
    historyVersion++;
  }

  @Override
  public void onInitializeClient() {
    var loader = FabricLoader.getInstance();
    configStore = new ConfigStore(loader.getConfigDir().resolve("kwamster.json"));
    try {
      config = configStore.load();
    } catch (java.io.IOException ex) {
      LOG.warn("Config load failed", ex);
      status = "Invalid configuration preserved; defaults loaded";
    }
    dataDir = loader.getGameDir().resolve("kwamster");
    store =
        new FightStore(
            dataDir.resolve("fights"),
            error -> {
              LOG.warn("{}", error);
              Minecraft.getInstance().execute(() -> status = error);
            });
    installEngine();
    store.load(
        fights ->
            Minecraft.getInstance()
                .execute(
                    () -> {
                      for (var f : fights)
                        if (history.stream().noneMatch(h -> h.id().equals(f.id()))) history.add(f);
                      history.sort(
                          Comparator.comparingLong(FightRecord::startEpochMillis).reversed());
                      trimHistory();
                      historyChanged();
                    }));
    openKey =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.kwamster.open",
                GLFW.GLFW_KEY_K,
                KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("kwamster", "controls"))));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          ClientCapture.tick();
          // The persistent HUD is dormant: tracking never depends on it.
          if (config.hudOverlay) CombatHud.tick();
          while (openKey.consumeClick())
            if (client.screen == null) client.setScreen(new KwamsterScreen());
        });
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath("kwamster", "analytics"),
        (g, d) -> {
          if (config.hudOverlay) CombatHud.render(g);
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          engine.finish(ClientCapture.now(), FightRecord.Outcome.INTERRUPTED, "Client stopped");
          saveConfig();
          store.close();
        });
    LOG.info("KWAMSTER initialized; Minecraft 1.21.11 / Java 21 / local storage {}", dataDir);
  }

  private static void installEngine() {
    engine =
        new CombatEngine(
            config,
            fight -> {
              history.addFirst(fight);
              trimHistory();
              historyChanged();
              CombatHud.summaryAt = ClientCapture.now();
              if (config.recording)
                store.save(fight, config.retention, (long) (config.maxStorageMb * 1024 * 1024));
              if (config.chatSummary)
                Minecraft.getInstance()
                    .gui
                    .getChat()
                    .addMessage(
                        Component.literal(
                            String.format(
                                Locale.ROOT,
                                "[KWAMSTER] %s / %d hits / %d attacks / %.0f%% / %d uncertain",
                                fight.outcome(),
                                fight.statistics().hits,
                                fight.statistics().attempts,
                                fight.statistics().accuracy(),
                                fight.statistics().uncertain)));
            },
            FeedbackController::hit,
            ClientCapture::label);
  }

  private static void trimHistory() {
    while (history.size() > config.retention) history.removeLast();
    int eventCount = history.stream().mapToInt(f -> f.events().size()).sum();
    for (int i = history.size() - 1; i >= 0 && eventCount > 50000; i--) {
      var f = history.get(i);
      eventCount -= f.events().size();
      history.set(i, f.summaryOnly());
    }
  }

  public static void resetConfig() {
    engine.finish(ClientCapture.now(), FightRecord.Outcome.INTERRUPTED, "Configuration reset");
    config = new Config();
    installEngine();
    saveConfig();
  }

  public static void saveConfig() {
    if (store == null) return;
    config.validate();
    Config snapshot = AtomicJson.GSON.fromJson(AtomicJson.GSON.toJson(config), Config.class);
    store.submit(
        () -> {
          try {
            configStore.save(snapshot);
          } catch (java.io.IOException ex) {
            LOG.warn("Config save failed", ex);
            Minecraft.getInstance().execute(() -> status = "Config could not be saved");
          }
        });
  }
}
