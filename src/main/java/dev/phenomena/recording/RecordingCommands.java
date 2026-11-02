package dev.phenomena.recording;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.combat.tracking.ClientCapture;
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
                    PhenomenaClient.diagnostics.export(names, servers, ClientCapture.knownNames())));
  }

  public static void install(
      com.mojang.brigadier.CommandDispatcher<
              net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
          dispatcher,
      Runnable start,
      Runnable end,
      java.util.function.Consumer<Boolean> diagnostics,
      java.util.function.BiConsumer<Boolean, Boolean> export) {
    var tree = buildTree(start, end, diagnostics, export);
    dispatcher.register(tree);
    // /kwm stays as a deprecated local alias of the documented /phenomena command.
    var phenomena = dispatcher.getRoot().getChild("phenomena");
    if (phenomena != null) dispatcher.register(literal("kwm").redirect(phenomena));
  }

  private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
          net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
      buildTree(
          Runnable start,
          Runnable end,
          java.util.function.Consumer<Boolean> diagnostics,
          java.util.function.BiConsumer<Boolean, Boolean> export) {
    return literal("phenomena")
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
                                })));
  }

  public static void start() {
    var mc = net.minecraft.client.Minecraft.getInstance();
    PhenomenaClient.recording.start(
        mc.player == null ? null : ClientCapture.currentHost(),
        mc.player == null ? null : mc.player.getName().getString(),
        ClientCapture.currentWorld(),
        ClientCapture.now(),
        ClientCapture.clientTick());
  }

  public static void end() {
    PhenomenaClient.recording.end(ClientCapture.now(), ClientCapture.clientTick());
  }

  public static void diagnostics(boolean enabled) {
    PhenomenaClient.config.diagnosticCapture = enabled;
    PhenomenaClient.diagnostics.enabled(enabled);
    PhenomenaClient.saveConfig();
    PhenomenaClient.notice(
        "Diagnostic capture "
            + (enabled ? "enabled; relevant announcements stay local." : "disabled."));
  }
}
