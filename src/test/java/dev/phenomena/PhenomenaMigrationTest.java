package dev.phenomena;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import dev.phenomena.config.Config;
import dev.phenomena.config.ConfigStore;
import dev.phenomena.recording.RecordingCommands;
import dev.phenomena.recording.storage.LegacyDataMigration;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identity-migration regressions: mod metadata, legacy config/data readers, command alias. */
class PhenomenaMigrationTest {

  @Test
  void fabricMetadataUsesPhenomenaIdentity() throws Exception {
    String json =
        Files.readString(Path.of("src/main/resources/fabric.mod.json"))
            .replace("${version}", "0.0.0");
    var parsed =
        com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    assertEquals("phenomena", parsed.get("id").getAsString());
    assertEquals(
        "dev.phenomena.PhenomenaClient",
        parsed.getAsJsonObject("entrypoints").getAsJsonArray("client").get(0).getAsString());
    String mixins = parsed.getAsJsonArray("mixins").get(0).getAsString();
    assertTrue(Files.isRegularFile(Path.of("src/main/resources").resolve(mixins)));
    String mixinConfig = Files.readString(Path.of("src/main/resources").resolve(mixins));
    assertEquals(
        "dev.phenomena.mixin",
        com.google.gson.JsonParser.parseString(mixinConfig).getAsJsonObject().get("package").getAsString());
  }

  @Test
  void langKeysAndAssetsUsePhenomenaNamespace() throws Exception {
    Path lang = Path.of("src/main/resources/assets/phenomena/lang/en_us.json");
    assertTrue(Files.isRegularFile(lang));
    assertFalse(Files.exists(Path.of("src/main/resources/assets/kwamster")));
    String json = Files.readString(lang);
    assertTrue(json.contains("key.phenomena.open"));
    assertTrue(json.contains("Phenomena"));
  }

  @Test
  void legacyConfigIsReadOnceAndNeverOverwritten(@TempDir Path dir) throws Exception {
    Path legacy = dir.resolve("kwamster.json");
    Path modern = dir.resolve("phenomena.json");
    Config original = new Config();
    original.accent = 0xFF123456;
    original.trendWindow = 33;
    dev.phenomena.recording.storage.AtomicJson.write(legacy, original);
    var store = new ConfigStore(modern, legacy);
    Config loaded = store.load();
    assertEquals(0xFF123456, loaded.accent);
    assertEquals(33, loaded.trendWindow);

    store.save(loaded);
    assertTrue(Files.isRegularFile(modern));
    // The legacy file remains byte-identical provenance.
    assertEquals(
        dev.phenomena.recording.storage.AtomicJson.GSON.toJson(original),
        Files.readString(legacy));
    // A later load prefers the modern file; the legacy path is no longer consulted.
    Config second = new ConfigStore(modern, legacy).load();
    assertEquals(0xFF123456, second.accent);
  }

  @Test
  void freshInstallationWritesMarkerWithoutLegacy(@TempDir Path dir) {
    var result = LegacyDataMigration.migrate(dir, null);
    assertTrue(result.ran());
    assertFalse(result.legacyPresent());
    assertTrue(Files.isRegularFile(dir.resolve("phenomena").resolve(LegacyDataMigration.MARKER)));
    assertEquals("Fresh data directory", result.summary());
    // Second startup: no repeat migration.
    assertEquals(false, LegacyDataMigration.migrate(dir, null).ran());
  }

  @Test
  void legacyDataIsCopiedForwardExactlyOnce(@TempDir Path dir) throws Exception {
    Path legacyFights = dir.resolve("kwamster/fights");
    Path legacyState = dir.resolve("kwamster/report-state");
    Files.createDirectories(legacyFights);
    Files.createDirectories(legacyState);
    byte[] fight = "{\"id\":\"a7ee1a04-1111-2222-3333-444455556666\"}".getBytes();
    Files.write(legacyFights.resolve("a7ee1a04-1111-2222-3333-444455556666.json"), fight);
    Files.write(legacyState.resolve("a7ee1a04-1111-2222-3333-444455556666.json"), "{}".getBytes());
    var before = Files.readAllBytes(legacyFights.resolve("a7ee1a04-1111-2222-3333-444455556666.json"));

    var result = LegacyDataMigration.migrate(dir, null);
    assertTrue(result.ran() && result.legacyPresent());
    assertEquals(2, result.copied());
    assertEquals(0, result.conflicts());
    Path migrated =
        dir.resolve("phenomena/fights/a7ee1a04-1111-2222-3333-444455556666.json");
    assertArrayEquals(fight, Files.readAllBytes(migrated));
    // Legacy tree untouched.
    assertArrayEquals(before, Files.readAllBytes(legacyFights.resolve("a7ee1a04-1111-2222-3333-444455556666.json")));

    // Re-running after marker: no further work.
    var second = LegacyDataMigration.migrate(dir, null);
    assertFalse(second.ran());

    // Marker records provenance.
    var marker =
        com.google.gson.JsonParser.parseString(
                Files.readString(dir.resolve("phenomena").resolve(LegacyDataMigration.MARKER)))
            .getAsJsonObject();
    assertEquals(LegacyDataMigration.SCHEMA, marker.get("schema").getAsInt());
    assertEquals("kwamster", marker.get("source").getAsString());
    assertEquals(2, marker.get("copied").getAsInt());
  }

  @Test
  void interruptedMigrationResumesAndNeverOverwritesConflicts(@TempDir Path dir) throws Exception {
    Path legacyFights = dir.resolve("kwamster/fights");
    Files.createDirectories(legacyFights);
    Files.write(legacyFights.resolve("a.json"), "AAAA".getBytes());
    Files.write(legacyFights.resolve("b.json"), "BBBB".getBytes());
    // Simulate a crash after one file: no marker, first file already present.
    Path partialFights = dir.resolve("phenomena/fights");
    Files.createDirectories(partialFights);
    Files.write(partialFights.resolve("a.json"), "AAAA".getBytes());
    // A conflicting file the owner (or a newer install) created: must survive untouched.
    Files.write(partialFights.resolve("b.json"), "DIFFERENT".getBytes());

    var result = LegacyDataMigration.migrate(dir, null);
    assertEquals(0, result.copied());
    assertEquals(1, result.identical());
    assertEquals(1, result.conflicts());
    assertEquals(1, result.conflictNames().size());
    assertTrue(result.conflictNames().get(0).endsWith("b.json"));
    assertEquals("DIFFERENT", Files.readString(partialFights.resolve("b.json")));
    assertEquals("BBBB", Files.readString(legacyFights.resolve("b.json")));
    assertTrue(Files.isRegularFile(dir.resolve("phenomena").resolve(LegacyDataMigration.MARKER)));
  }

  @Test
  void legacyDiagnosticsDirectoryMigratesToo(@TempDir Path dir) throws Exception {
    Path diag = dir.resolve("kwamster/diagnostics");
    Files.createDirectories(diag);
    Files.write(diag.resolve("capture.jsonl"), "{}".getBytes());
    var result = LegacyDataMigration.migrate(dir, null);
    assertEquals(1, result.copied());
    assertTrue(
        Files.isRegularFile(dir.resolve("phenomena/diagnostics/capture.jsonl")));
  }

  @Test
  void phenomenaCommandIsPrimaryAndKwmRemainsAlias() throws Exception {
    var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
    var calls = new ArrayList<String>();
    RecordingCommands.install(
        dispatcher,
        () -> calls.add("start"),
        () -> calls.add("end"),
        v -> calls.add("diagnostics:" + v),
        (n, s) -> calls.add("export:" + n + ":" + s));
    assertNotNull(dispatcher.getRoot().getChild("phenomena"));
    assertNotNull(dispatcher.getRoot().getChild("kwm"));
    assertEquals(1, dispatcher.execute("phenomena start", null));
    assertEquals(1, dispatcher.execute("kwm start", null));
    assertEquals(1, dispatcher.execute("phenomena diagnostics export-names", null));
    assertEquals(1, dispatcher.execute("kwm end", null));
    assertEquals(
        List.of("start", "start", "export:true:false", "end"), calls);
  }

  @Test
  void legacyExportFolderOwnershipMarkersAreBothAccepted() {
    // Both marker spellings must keep identifying legacy export folders as ours.
    var markers = dev.phenomena.platform.SafePaths.OWNER_MARKERS;
    assertTrue(markers.contains(".phenomena-owner"));
    assertTrue(markers.contains(".kwamster-owner"));
  }
}
