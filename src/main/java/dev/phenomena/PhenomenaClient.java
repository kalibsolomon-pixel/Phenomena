package dev.phenomena;

import dev.phenomena.combat.fights.*;
import dev.phenomena.combat.tracking.*;
import dev.phenomena.config.*;
import dev.phenomena.feedback.FeedbackController;
import dev.phenomena.gui.screens.PhenomenaScreen;
import dev.phenomena.hud.CombatHud;
import dev.phenomena.recording.*;
import dev.phenomena.recording.storage.*;
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

public final class PhenomenaClient implements ClientModInitializer {
  public static final Logger LOG = LoggerFactory.getLogger("phenomena");
  public static Config config = new Config();
  public static ConfigStore configStore;
  public static CombatEngine engine;
  public static KeyMapping openKey;
  public static FightStore store;
  public static AutoReports autoReports;
  public static RecordingController recording;
  public static SessionRecovery recovery;
  public static Diagnostics diagnostics;
  public static Path dataDir;
  public static String status = "Local by default / K opens Phenomena";
  public static final List<FightRecord> history = new ArrayList<>();
  public static final ExportNotifications exportNotifications =
      new ExportNotifications(System::nanoTime);
  private static boolean initialized;

  /** Incremented on every history change so derived GUI data is recomputed only when needed. */
  public static long historyVersion;

  public static void historyChanged() {
    historyVersion++;
  }

  @Override
  public void onInitializeClient() {
    synchronized (PhenomenaClient.class) {
      if (initialized) return;
      initialized = true;
    }
    var loader = FabricLoader.getInstance();
    // Reads the legacy kwamster.json once when no phenomena.json exists; saves always target
    // the new name, so the legacy file is preserved untouched as provenance.
    configStore =
        new ConfigStore(
            loader.getConfigDir().resolve("phenomena.json"),
            loader.getConfigDir().resolve("kwamster.json"));
    try {
      config = configStore.load();
    } catch (java.io.IOException ex) {
      LOG.warn("Config load failed", ex);
      status = "Invalid configuration preserved; defaults loaded";
    }
    dataDir = loader.getGameDir().resolve("phenomena");
    store =
        new FightStore(
            dataDir.resolve("fights"),
            error -> {
              LOG.warn("{}", error);
              Minecraft.getInstance().execute(() -> status = error);
            });
    autoReports =
        new AutoReports(
            store,
            dataDir,
            error -> {
              LOG.info("{}", error);
              Minecraft.getInstance()
                  .execute(
                      () -> {
                        status = error;
                        if (error.contains("failed") || error.contains("exhausted")) notice(error);
                      });
            },
            (folder, fight, options) ->
                dev.phenomena.sharing.ReportExporter.export(folder, fight, options),
            exportNotifications::offer);
    recovery = new SessionRecovery(store, dataDir, PhenomenaClient::notice);
    diagnostics = new Diagnostics(store, dataDir, PhenomenaClient::notice);
    diagnostics.policy(() -> engine.capturePolicy(), () -> engine.captureLimitation());
    diagnostics.enabled(config.diagnosticCapture);
    // First task on the single storage worker: copy legacy kwamster data forward once,
    // before history load and report recovery can observe the directory.
    store.submit(
        () -> LegacyDataMigration.migrate(loader.getGameDir(), msg -> LOG.info("{}", msg)));
    store.submit(
        () -> {
          try {
            AtomicJson.write(
                dataDir.resolve("server-compatibility.json"), Announcements.adapters());
          } catch (java.io.IOException ex) {
            notice("Compatibility inventory could not be saved: " + ex.getMessage());
          }
        });
    installEngine();
    RecordingCommands.register();
    var recoveryOptions = reportOptions();
    store.load(
        loaded -> {
          var fights = recovery.recover(loaded);
          autoReports.recover(fights, recoveryOptions);
          Minecraft.getInstance()
              .execute(
                  () -> {
                    for (var f : fights)
                      if (history.stream().noneMatch(h -> h.id().equals(f.id()))) history.add(f);
                    history.sort(
                        Comparator.comparingLong(FightRecord::startEpochMillis).reversed());
                    trimHistory();
                    historyChanged();
                  });
        });
    openKey =
        KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.phenomena.open",
                GLFW.GLFW_KEY_K,
                KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("phenomena", "controls"))));
    ClientTickEvents.START_CLIENT_TICK.register(client -> ClientCapture.startTick());
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> ClientCapture.disconnected());
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          ClientCapture.tick();
          autoReports.tick(ClientCapture.now());
          exportNotifications.tick(client.player != null, PhenomenaClient::notice);
          diagnostics.tick(ClientCapture.now());
          // The persistent HUD is dormant: tracking never depends on it.
          if (config.hudOverlay) CombatHud.tick();
          while (openKey.consumeClick())
            if (client.screen == null) client.setScreen(new PhenomenaScreen());
        });
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath("phenomena", "analytics"),
        (g, d) -> {
          if (config.hudOverlay) CombatHud.render(g);
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          recording.shutdown(ClientCapture.now(), ClientCapture.clientTick(), "Client stopped");
          diagnostics.flush();
          autoReports.pump();
          saveConfig();
          store.close();
        });
    LOG.info("Phenomena initialized; Minecraft 1.21.11 / Java 21 / local storage {}", dataDir);
  }

  private static void installEngine() {
    engine =
        new CombatEngine(
            config,
            fight -> recording.automaticFinished(fight),
            FeedbackController::hit,
            ClientCapture::label);
    recording =
        new RecordingController(
            config,
            engine,
            PhenomenaClient::completed,
            recovery::checkpoint,
            PhenomenaClient::notice,
            ClientCapture::label);
    recording.exportStatus(autoReports::availability);
  }

  public static void notice(String message) {
    LOG.info("{}", message);
    Minecraft.getInstance()
        .execute(
            () -> {
              status = message;
              if (Minecraft.getInstance().player != null)
                Minecraft.getInstance()
                    .gui
                    .getChat()
                    .addMessage(Component.literal("[Phenomena] " + message));
            });
  }

  private static void completed(FightRecord fight) {
    history.addFirst(fight);
    trimHistory();
    historyChanged();
    CombatHud.summaryAt = ClientCapture.now();
    diagnostics.outcome(fight);
    if (fight.manual() || config.recording) autoReports.finalized(fight, reportOptions());
    if (config.chatSummary && !fight.manual())
      notice(
          fight.resolution().classification().label
              + " / "
              + fight.statistics().hits
              + " hits / "
              + fight.statistics().attempts
              + " attacks");
  }

  public static dev.phenomena.sharing.ReportExporter.Options reportOptions() {
    return new dev.phenomena.sharing.ReportExporter.Options(
        config.anonymizeExports, config.exportMotion, config.reducedMotion);
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
    if (recording != null)
      recording.shutdown(ClientCapture.now(), ClientCapture.clientTick(), "Configuration reset");
    config = new Config();
    if (diagnostics != null) diagnostics.enabled(false);
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
