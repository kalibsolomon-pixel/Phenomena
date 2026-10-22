package dev.kwamster;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.kwamster.config.*;
import dev.kwamster.gui.screens.DashboardScreen;
public final class KwamsterClient implements ClientModInitializer {
 public static final Logger LOG=LoggerFactory.getLogger("kwamster");
 public static Config config=new Config();
 public static ConfigStore configStore;
 public static dev.kwamster.combat.tracking.CombatEngine engine;
 public static final java.util.List<dev.kwamster.combat.fights.FightRecord> history=new java.util.ArrayList<>();
 public void onInitializeClient() {
  configStore=new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve("kwamster.json"));
  try {config=configStore.load();}catch(java.io.IOException ex){LOG.warn("Config load failed",ex);}
  engine=new dev.kwamster.combat.tracking.CombatEngine(config,fight->{history.addFirst(fight);if(history.size()>config.retention)history.removeLast();},(event,grade)->LOG.debug("Hit readiness {}",grade));
  var key=KeyBindingHelper.registerKeyBinding(new KeyMapping("key.kwamster.open",GLFW.GLFW_KEY_K,KeyMapping.Category.register(Identifier.fromNamespaceAndPath("kwamster","controls"))));
  ClientTickEvents.END_CLIENT_TICK.register(client->{dev.kwamster.combat.tracking.ClientCapture.tick();while(key.consumeClick())client.setScreen(new DashboardScreen());});
  LOG.info("KWAMSTER initialized; press K for analytics");
 }
}
