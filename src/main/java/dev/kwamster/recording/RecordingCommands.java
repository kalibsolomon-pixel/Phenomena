package dev.kwamster.recording;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.tracking.ClientCapture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/** Fabric's client dispatcher consumes these locally, including on permissionless servers. */
public final class RecordingCommands {
  private RecordingCommands() {}

  public static void register() {
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, access) ->
            install(
                dispatcher,
                RecordingCommands::start,
                RecordingCommands::end,
                RecordingCommands::diagnostics,
                (names, servers) ->
                    KwamsterClient.diagnostics.export(names, servers, ClientCapture.knownNames())));
  }

  public static void install(
      com.mojang.brigadier.CommandDispatcher<
              net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
          dispatcher,
      Runnable start,
      Runnable end,
      java.util.function.Consumer<Boolean> diagnostics,
      java.util.function.BiConsumer<Boolean, Boolean> export) {
    dispatcher.register(
        literal("kwm")
            .then(
                literal("start")
                    .executes(
                        ctx -> {
                          start.run();
                          return 1;
                        }))
            .then(
                literal("end")
                    .executes(
                        ctx -> {
                          end.run();
                          return 1;
                        }))
            .then(
                literal("diagnostics")
                    .then(
                        literal("on")
                            .executes(
                                ctx -> {
                                  diagnostics.accept(true);
                                  return 1;
                                }))
                    .then(
                        literal("off")
                            .executes(
                                ctx -> {
                                  diagnostics.accept(false);
                                  return 1;
                                }))
                    .then(
                        literal("export")
                            .executes(
                                ctx -> {
                                  export.accept(true, true);
                                  return 1;
                                }))
                    .then(
                        literal("export-names")
                            .executes(
                                ctx -> {
                                  export.accept(true, false);
                                  return 1;
                                }))
                    .then(
                        literal("export-servers")
                            .executes(
                                ctx -> {
                                  export.accept(false, true);
                                  return 1;
                                }))));
  }

  public static void start() {
    var mc = net.minecraft.client.Minecraft.getInstance();
    KwamsterClient.recording.start(
        mc.player == null ? null : ClientCapture.currentHost(),
        mc.player == null ? null : mc.player.getName().getString(),
        ClientCapture.currentWorld(),
        ClientCapture.now(),
        ClientCapture.clientTick());
  }

  public static void end() {
    KwamsterClient.recording.end(ClientCapture.now(), ClientCapture.clientTick());
  }

  public static void diagnostics(boolean enabled) {
    KwamsterClient.config.diagnosticCapture = enabled;
    KwamsterClient.diagnostics.enabled(enabled);
    KwamsterClient.saveConfig();
    KwamsterClient.notice(
        "Diagnostic capture "
            + (enabled ? "enabled; relevant announcements stay local." : "disabled."));
  }
}
