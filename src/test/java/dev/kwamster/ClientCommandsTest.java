package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import dev.kwamster.recording.RecordingCommands;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

class ClientCommandsTest {
  @Test
  void registersAndExecutesLocalCommandsWithoutServerSourceOrPermission() throws Exception {
    var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
    var calls = new ArrayList<String>();
    RecordingCommands.install(
        dispatcher,
        () -> calls.add("start"),
        () -> calls.add("end"),
        v -> calls.add("diagnostics:" + v),
        (n, s) -> calls.add("export:" + n + ":" + s));
    assertNotNull(dispatcher.getRoot().getChild("kwm"));
    assertTrue(dispatcher.getRoot().getChild("kwm").canUse(null));
    assertEquals(1, dispatcher.execute("kwm start", null));
    assertEquals(1, dispatcher.execute("kwm end", null));
    assertEquals(List.of("start", "end"), calls);
    // The only effects are local callbacks. Runtime test also probes the server packet handler.
  }

  @Test
  void diagnosticsCommandsExposeSeparateRedactionOptions() throws Exception {
    var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
    var calls = new ArrayList<String>();
    RecordingCommands.install(
        dispatcher,
        () -> {},
        () -> {},
        v -> calls.add("on:" + v),
        (n, s) -> calls.add(n + ":" + s));
    for (var c : List.of("on", "off", "export", "export-names", "export-servers"))
      dispatcher.execute("kwm diagnostics " + c, null);
    assertEquals(List.of("on:true", "on:false", "true:true", "true:false", "false:true"), calls);
    assertThrows(
        com.mojang.brigadier.exceptions.CommandSyntaxException.class,
        () -> dispatcher.execute("kwm invalid", null));
  }
}
