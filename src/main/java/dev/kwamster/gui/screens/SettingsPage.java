package dev.kwamster.gui.screens;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kwamster.KwamsterClient;
import dev.kwamster.analytics.Format;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.tracking.ClientCapture;
import dev.kwamster.config.Config;
import dev.kwamster.feedback.FeedbackController;
import dev.kwamster.gui.FightActions;
import dev.kwamster.gui.Notices;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.theme.Theme;
import dev.kwamster.gui.ui.UiState.SettingsSection;
import dev.kwamster.platform.SafePaths;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.lwjgl.glfw.GLFW;

/** Configuration grouped into sections. Changes apply immediately and save on close. */
final class SettingsPage implements KwamsterScreen.Page {
  /** Curated feedback sounds; any resource identifier can still be typed. */
  static final String[][] SOUNDS = {
    {"XP orb pickup", "minecraft:entity.experience_orb.pickup"},
    {"Note block hat", "minecraft:block.note_block.hat"},
    {"Note block bass", "minecraft:block.note_block.bass"},
    {"Note block pling", "minecraft:block.note_block.pling"},
    {"Note block bell", "minecraft:block.note_block.bell"},
    {"Note block chime", "minecraft:block.note_block.chime"},
    {"Note block xylophone", "minecraft:block.note_block.xylophone"},
    {"Arrow hit player", "minecraft:entity.arrow.hit_player"},
    {"Button click", "minecraft:ui.button.click"}
  };

  private static final String[] GRADES = {"Perfect", "Great", "Normal", "Poor"};
  private final KwamsterScreen s;

  SettingsPage(KwamsterScreen s) {
    this.s = s;
  }

  private Config c() {
    return KwamsterClient.config;
  }

  @Override
  public String title() {
    return "Settings";
  }

  @Override
  public String context() {
    return "saved to config/kwamster.json";
  }

  @Override
  public List<String> sections() {
    return Arrays.stream(SettingsSection.values()).map(v -> v.label).toList();
  }

  @Override
  public int section() {
    return s.state.settings.ordinal();
  }

  @Override
  public void section(int index) {
    s.state.settings = SettingsSection.values()[index];
  }

  @Override
  public void build(Content c) {
    switch (s.state.settings) {
      case GENERAL -> general(c);
      case CONTROLS -> controls(c);
      case FEEDBACK -> feedback(c);
      case MEASUREMENT -> measurement(c);
      case STORAGE -> storage(c);
      case INTERFACE -> ui(c);
    }
  }

  static KButton toggle(String id, BooleanSupplier get, Consumer<Boolean> set) {
    return new KButton(
            id,
            KButton.Style.NORMAL,
            () -> get.getAsBoolean() ? "On" : "Off",
            () -> set.accept(!get.getAsBoolean()))
        .selected(get)
        .content();
  }

  private static KSlider slider(
      String id,
      double v,
      double min,
      double max,
      double step,
      DoubleFunction<String> fmt,
      DoubleConsumer set) {
    return new KSlider(id, v, min, max, step, fmt, set);
  }

  private void general(Content c) {
    var cfg = c();
    c.heading("Tracking");
    c.setting(
        "Automatic capture",
        "Uses configured server announcements or generic combat on other servers. Manual recording"
            + " remains available.",
        toggle(
            "Combat tracking",
            () -> cfg.tracking,
            v -> {
              if (!v && !KwamsterClient.recording.manual())
                KwamsterClient.engine.finish(
                    ClientCapture.now(), FightRecord.Outcome.INTERRUPTED, "Tracking paused");
              cfg.tracking = v;
            }));
    c.setting(
        "Record finished fights",
        "Saves JSON and a self-contained HTML report automatically in the background.",
        toggle("Record finished fights", () -> cfg.recording, v -> cfg.recording = v));
    c.setting(
        "Fight summary in chat",
        "One local chat line when a fight ends. Never sent to the server.",
        toggle("Fight summary in chat", () -> cfg.chatSummary, v -> cfg.chatSummary = v));
    c.heading("Data retention");
    c.setting(
        "Fights kept",
        "Maximum fights in session history; existing files are preserved.",
        slider(
            "Fights kept",
            cfg.retention,
            1,
            1000,
            1,
            v -> Integer.toString((int) v),
            v -> cfg.retention = (int) v));
    c.setting(
        "Storage budget",
        "Legacy manual-save budget; automatic recordings are preserved for recovery.",
        slider(
            "Storage budget",
            cfg.maxStorageMb,
            1,
            1024,
            1,
            v -> (int) v + " MiB",
            v -> cfg.maxStorageMb = v));
    c.heading("Reset");
    c.setting(
        "Reset all settings",
        "Restores defaults. Recordings are not affected.",
        new KButton(
                "Reset...",
                KButton.Style.DANGER,
                () ->
                    s.confirm(
                        "Reset settings?",
                        "Restore every KWAMSTER setting to its default? Recordings and exports are"
                            + " kept.",
                        "Reset",
                        () -> {
                          KwamsterClient.resetConfig();
                          Notices.post("Settings reset to defaults", Notices.Level.OK);
                          s.rebuild();
                        }))
            .content());
  }

  private void measurement(Content c) {
    var cfg = c();
    c.heading("Fight detection", "recording and combat measurement have separate timeouts");
    c.setting(
        "Inactivity timeout",
        "Applies only to generic combat capture on unconfigured servers.",
        slider(
            "Inactivity timeout",
            cfg.inactivitySeconds,
            2,
            30,
            .5,
            v -> Format.number(v, 1) + " s",
            v -> cfg.inactivitySeconds = v));
    c.setting(
        "Combat-exchange timeout",
        "Ends combat measurement after no qualifying interaction. The fight recording continues.",
        slider(
            "Combat-exchange timeout",
            cfg.exchangeInactivitySeconds,
            .5,
            30,
            .5,
            v -> Format.number(v, 1) + " s",
            v -> cfg.exchangeInactivitySeconds = v));
    c.setting(
        "Damage correlation window",
        "How long after an attack a server damage report may confirm it.",
        slider(
            "Correlation window",
            cfg.correlationMillis,
            50,
            500,
            10,
            v -> (int) v + " ms",
            v -> cfg.correlationMillis = v));
    c.setting(
        "Combo timeout",
        "Hits further apart start a new combo.",
        slider(
            "Combo timeout",
            cfg.comboSeconds,
            .5,
            10,
            .1,
            v -> Format.number(v, 1) + " s",
            v -> cfg.comboSeconds = v));
    c.setting(
        "Jump candidate window",
        "Signed client ticks around incoming damage. Candidates do not prove cancellation.",
        slider(
            "Jump window",
            cfg.jumpWindowTicks,
            0,
            10,
            1,
            v -> "±" + (int) v + " ticks",
            v -> cfg.jumpWindowTicks = (int) v));
    c.note("Configured matches and manual recordings never end because of inactivity.");
    c.heading("Reach and timing thresholds", "frozen into each new fight");
    c.setting(
        "Target reach",
        "Eye to interpolated target box ray contact, in blocks.",
        slider(
            "Target reach",
            cfg.perfectReachTarget,
            .1,
            6,
            .01,
            v -> Format.number(v, 2) + " blocks",
            v -> cfg.perfectReachTarget = v));
    c.setting(
        "Reach tolerance",
        "Inclusive window around target reach.",
        slider(
            "Reach tolerance",
            cfg.perfectReachTolerance,
            0,
            .5,
            .01,
            v -> "±" + Format.number(v, 2),
            v -> cfg.perfectReachTolerance = v));
    c.setting(
        "Timing tolerance",
        "Quantization allowance around one effective cooldown.",
        slider(
            "Timing tolerance",
            cfg.timingToleranceTicks,
            0,
            2,
            .25,
            v -> "±" + Format.number(v, 2) + " ticks",
            v -> cfg.timingToleranceTicks = v));
    c.note(
        "Thresholds are recorded with every fight, so changing them later never reinterprets an"
            + " earlier recording. Reach is client geometry; a hit beyond the target is not"
            + " evidence of a rule violation.");
  }

  private void controls(Content c) {
    var mc = net.minecraft.client.Minecraft.getInstance();
    var key = KwamsterClient.openKey;
    c.heading("Dashboard key");
    c.setting(
        "Open KWAMSTER",
        "Default K. This is the single KWAMSTER key mapping; it also appears in Minecraft Controls"
            + " under KWAMSTER.",
        new KButton(
                "rebind",
                KButton.Style.PRIMARY,
                () -> s.capturing() ? "Press a key..." : KwamsterScreen.keyName(),
                () ->
                    s.captureKey(
                        e -> {
                          if (e.key() != GLFW.GLFW_KEY_ESCAPE) {
                            key.setKey(InputConstants.getKey(e));
                            KeyMapping.resetMapping();
                            mc.options.save();
                            Notices.post(
                                "Dashboard key set to " + KwamsterScreen.keyName(),
                                Notices.Level.OK);
                          }
                          s.rebuild();
                        }))
            .tooltip("Click, then press the new key")
            .content());
    var conflicts = new ArrayList<String>();
    for (var other : mc.options.keyMappings)
      if (other != key && !key.isUnbound() && other.same(key))
        conflicts.add(
            net.minecraft.network.chat.Component.translatable(other.getName()).getString());
    if (!conflicts.isEmpty())
      c.note(
          "Also bound to: " + String.join(", ", conflicts) + ". Both actions will trigger.",
          Theme.WARN);
    c.row(
        new KButton(
                "Reset to K",
                () -> {
                  key.setKey(key.getDefaultKey());
                  KeyMapping.resetMapping();
                  mc.options.save();
                  s.rebuild();
                })
            .enabled(!key.isDefault())
            .content(),
        new KButton(
                "Open Minecraft Controls", () -> mc.setScreen(new KeyBindsScreen(s, mc.options)))
            .content());
    c.heading("In this window");
    c.text(
        "1-6 switch tabs. Tab and Shift+Tab move focus; Enter or Space activates. Arrow keys adjust"
            + " sliders and move list selection. Mouse wheel, Page Up/Down and Home/End scroll."
            + " Escape closes a dropdown or dialog first, then the window. The dashboard key also"
            + " closes it unless a text field is focused.");
  }

  private void feedback(Content c) {
    var cfg = c();
    c.heading("Hit sounds");
    c.setting(
        "Play hit sounds",
        "Perfect-reach sound after source-confirmed melee hits; at most one per 120 ms.",
        toggle("Play hit sounds", () -> cfg.sound, v -> cfg.sound = v));
    var channels = Config.SoundChannel.values();
    c.setting(
        "Sound category",
        "Minecraft volume slider that controls these sounds.",
        s.dropdown(
                "channel",
                () -> channel(cfg.soundChannel),
                Arrays.stream(channels).map(SettingsPage::channel).toList(),
                () -> cfg.soundChannel.ordinal(),
                i -> cfg.soundChannel = channels[i])
            .content());
    c.setting(
        "Perfect-reach sound",
        "Uses the Perfect sound below, independently of timing.",
        toggle("Perfect reach sound", () -> cfg.perfectReachSound, v -> cfg.perfectReachSound = v));
    c.heading(
        "Sound styles", "Perfect is used for perfect reach; other styles are retained for preview");
    var chips = new KButton[4];
    for (int i = 0; i < 4; i++) {
      int g = i;
      chips[i] =
          new KButton(
                  "grade:" + i,
                  KButton.Style.CHIP,
                  () -> GRADES[g],
                  () -> {
                    s.state.feedbackGrade = g;
                    s.rebuild();
                  })
              .selected(() -> s.state.feedbackGrade == g)
              .content();
    }
    c.row(chips);
    int g = s.state.feedbackGrade;
    var style = cfg.feedback[g];
    c.setting(
        GRADES[g] + " sound",
        "Plays for hits in this category.",
        toggle(GRADES[g] + " sound", () -> style.audio, v -> style.audio = v));
    List<String> names = new ArrayList<>();
    for (var sound : SOUNDS) names.add(sound[0]);
    names.add("Custom identifier");
    IntSupplier current =
        () -> {
          for (int i = 0; i < SOUNDS.length; i++) if (SOUNDS[i][1].equals(style.sound)) return i;
          return SOUNDS.length;
        };
    c.setting(
        "Sound",
        "Choose a preset, or type any sound resource identifier below.",
        s.dropdown(
                "sound",
                () -> names.get(current.getAsInt()),
                names,
                current,
                i -> {
                  if (i < SOUNDS.length) style.sound = SOUNDS[i][1];
                })
            .content());
    var id = new KTextField("Sound identifier", style.sound, 128);
    id.setResponder(v -> style.sound = v);
    c.setting("Sound identifier", "Unknown identifiers are silent.", id);
    c.setting(
        "Volume",
        null,
        slider(
            GRADES[g] + " volume",
            style.volume,
            0,
            1,
            .05,
            v -> Math.round(v * 100) + "%",
            v -> style.volume = (float) v));
    c.setting(
        "Pitch",
        null,
        slider(
            GRADES[g] + " pitch",
            style.pitch,
            .5,
            2,
            .05,
            v -> Format.number(v, 2),
            v -> style.pitch = (float) v));
    c.row(
        new KButton("Preview " + GRADES[g], () -> FeedbackController.preview(g))
            .tooltip("Plays at the configured category and volume")
            .content());
    c.color(
        GRADES[g] + " color",
        "Used for this category in charts.",
        () -> style.color,
        v -> style.color = v);
    c.setting(
        "Per-hit chat line",
        "Local, rate limited to two per second.",
        toggle("Per-hit chat line", () -> cfg.perHitChat, v -> cfg.perHitChat = v));
    c.note(
        "Perfect reach and perfect timing are separate. Openers have no measured interval. Legacy"
            + " readiness thresholds are retained only for historical records.");
  }

  private void storage(Content c) {
    var cfg = c();
    c.heading("Recording");
    c.setting(
        "Diagnostic capture",
        "Opt-in local server announcement evidence; private chat excluded by default.",
        toggle(
            "Diagnostic capture",
            () -> cfg.diagnosticCapture,
            dev.kwamster.recording.RecordingCommands::diagnostics));
    c.setting(
        "Record finished fights",
        "Automatic JSON and HTML after finalization. Local folders include the known opponent"
            + " name.",
        toggle("Record finished fights", () -> cfg.recording, v -> cfg.recording = v));
    c.setting(
        "Fights kept",
        null,
        slider(
            "Fights kept",
            cfg.retention,
            1,
            1000,
            1,
            v -> Integer.toString((int) v),
            v -> cfg.retention = (int) v));
    c.setting(
        "Storage budget",
        null,
        slider(
            "Storage budget",
            cfg.maxStorageMb,
            1,
            1024,
            1,
            v -> (int) v + " MiB",
            v -> cfg.maxStorageMb = v));
    c.heading("Export and browser reports");
    c.setting(
        "Anonymize reports",
        "Replaces the opponent's ID and name with \"Opponent\" in exports and browser views.",
        toggle("Anonymize reports", () -> cfg.anonymizeExports, v -> cfg.anonymizeExports = v));
    c.setting(
        "Include motion traces",
        "10 Hz velocity samples. Off makes reports smaller.",
        toggle("Include motion traces", () -> cfg.exportMotion, v -> cfg.exportMotion = v));
    c.setting(
        "Open after export",
        "Export also opens the report in your browser.",
        toggle("Open after export", () -> cfg.openAfterExport, v -> cfg.openAfterExport = v));
    c.heading("Folders", "inside the Minecraft game directory");
    c.row(
        new KButton(
                "Open Recordings Folder", () -> FightActions.openFolder(SafePaths.Managed.FIGHTS))
            .content(),
        new KButton("Open Exports Folder", () -> FightActions.openFolder(SafePaths.Managed.EXPORTS))
            .content());
    c.text(
        "Recordings: kwamster/fights. Reports: kwamster/exports/Fight vs <opponent> <date"
            + " time>/index.html (shared name omitted while anonymizing). Local recording folders"
            + " always retain a known opponent. Reports work offline, make no network requests, and"
            + " are never uploaded.");
  }

  private void ui(Content c) {
    var cfg = c();
    c.heading("Appearance");
    c.color(
        "Accent color",
        "Active tab, focus outlines and highlights.",
        () -> cfg.accent,
        v -> cfg.accent = v);
    var densities = Config.Density.values();
    c.setting(
        "Density",
        "Compact uses smaller spacing for small windows. GUI size follows Minecraft's GUI Scale"
            + " option.",
        s.dropdown(
                "density",
                () -> title(cfg.density.name()),
                Arrays.stream(densities).map(v -> title(v.name())).toList(),
                () -> cfg.density.ordinal(),
                i -> cfg.density = densities[i])
            .content());
    c.setting(
        "Reduced motion",
        "Disables the tab indicator animation here and transitions in reports.",
        toggle("Reduced motion", () -> cfg.reducedMotion, v -> cfg.reducedMotion = v));
    c.heading("Charts");
    c.setting(
        "Point markers",
        "Markers on line charts with up to 120 points.",
        toggle(
            "Point markers",
            () -> cfg.chartPoints,
            v -> {
              cfg.chartPoints = v;
              s.rebuild();
            }));
    c.setting(
        "Grid lines",
        null,
        toggle(
            "Grid lines",
            () -> cfg.chartGrid,
            v -> {
              cfg.chartGrid = v;
              s.rebuild();
            }));
    c.setting(
        "Overview window",
        "Fights included in the Overview summary and trend.",
        slider(
            "Overview window",
            cfg.trendWindow,
            5,
            100,
            1,
            v -> (int) v + " fights",
            v -> cfg.trendWindow = (int) v));
    c.note(
        "The persistent in-game HUD is retired in 0.1.1; this dashboard replaces it. Hit sounds"
            + " remain available.");
  }

  static String channel(Config.SoundChannel c) {
    return switch (c) {
      case UI -> "Interface (UI)";
      case PLAYERS -> "Players";
      case MASTER -> "Master";
    };
  }

  static String title(String s) {
    return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
  }
}
