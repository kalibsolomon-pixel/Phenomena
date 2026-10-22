package dev.kwamster.gui.screens;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kwamster.KwamsterClient;
import dev.kwamster.combat.quality.HitQuality;
import dev.kwamster.config.Config;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

public final class SettingsScreen extends PanelScreen {
  private boolean binding;
  private int grade;

  public SettingsScreen() {
    super("Settings", "Changes save locally when leaving this screen");
  }

  @Override
  protected void content() {
    var c = KwamsterClient.config;
    toggle(
        "Tracking",
        () -> c.tracking,
        v -> {
          if (!v)
            KwamsterClient.engine.finish(
                dev.kwamster.combat.tracking.ClientCapture.now(),
                dev.kwamster.combat.fights.FightRecord.Outcome.INTERRUPTED,
                "Tracking paused");
          c.tracking = v;
        });
    action(
        "Display: " + c.display,
        () ->
            minecraft.setScreen(
                new ChoiceScreen(
                    "Display",
                    java.util.Arrays.stream(Config.Display.values()).map(Enum::name).toList(),
                    i -> {
                      c.display = Config.Display.values()[i];
                      minecraft.setScreen(this);
                    })));
    toggle("Record finalized fights", () -> c.recording, v -> c.recording = v);
    toggle("Per-hit local chat (rate limited)", () -> c.perHitChat, v -> c.perHitChat = v);
    toggle("Fight summary alert", () -> c.summary, v -> c.summary = v);
    slider("Alert duration (seconds)", c.alertSeconds, 1, 30, v -> c.alertSeconds = v);
    toggle("Subtle alert fade", () -> c.animate, v -> c.animate = v);
    slider("Fight inactivity (seconds)", c.inactivitySeconds, 2, 30, v -> c.inactivitySeconds = v);
    slider(
        "Damage correlation window (ms)",
        c.correlationMillis,
        50,
        500,
        v -> c.correlationMillis = v);
    slider("Combo timeout (seconds)", c.comboSeconds, .5, 10, v -> c.comboSeconds = v);
    slider("Jump candidate window (ms)", c.jumpWindowMillis, 50, 300, v -> c.jumpWindowMillis = v);
    label("Readiness feedback / experimental, not damage quality");
    slider(
        "Perfect cooldown",
        c.perfectCooldown,
        .5,
        1,
        v -> {
          c.perfectCooldown = v;
          c.greatCooldown = Math.min(c.greatCooldown, v);
        });
    slider("Great cooldown", c.greatCooldown, .1, c.perfectCooldown, v -> c.greatCooldown = v);
    toggle("Feedback audio", () -> c.sound, v -> c.sound = v);
    toggle("Floating readiness text", () -> c.floatingText, v -> c.floatingText = v);
    action(
        "Category: " + HitQuality.Grade.values()[grade],
        () -> {
          grade = (grade + 1) % 4;
          init();
        });
    var style = c.feedback[grade];
    toggle("Category visual", () -> style.visual, v -> style.visual = v);
    toggle("Category audio", () -> style.audio, v -> style.audio = v);
    label("Category sound resource identifier");
    textField("Sound ID", style.sound, v -> style.sound = v);
    slider("Category volume", style.volume, 0, 1, v -> style.volume = (float) v);
    slider("Category pitch", style.pitch, .5, 2, v -> style.pitch = (float) v);
    color("Category RGB", style.color, v -> style.color = v);
    color("Accent RGB", c.accent, v -> c.accent = v);
    color("Forward RGB", c.forwardColor, v -> c.forwardColor = v);
    color("Backward RGB", c.backwardColor, v -> c.backwardColor = v);
    color("Neutral RGB", c.neutralColor, v -> c.neutralColor = v);
    slider("Retained fights", c.retention, 1, 1000, v -> c.retention = (int) v);
    slider("Storage budget (MiB)", c.maxStorageMb, 1, 1024, v -> c.maxStorageMb = v);
    action(
        binding
            ? "Press a key / Escape cancels"
            : "Menu key: " + KwamsterClient.openKey.getTranslatedKeyMessage().getString(),
        () -> {
          binding = true;
          init();
        });
    action(
        "Reset configuration",
        () ->
            minecraft.setScreen(
                new ConfirmScreen(
                    "Reset KWAMSTER settings?",
                    () -> {
                      KwamsterClient.resetConfig();
                      minecraft.setScreen(new SettingsScreen());
                    },
                    () -> minecraft.setScreen(this))));
  }

  private void color(String label, int value, java.util.function.IntConsumer set) {
    label(label + " / hexadecimal");
    textField(
        label,
        String.format(Locale.ROOT, "%06X", value & 0xFFFFFF),
        v -> {
          if (v.matches("[0-9a-fA-F]{6}")) set.accept(0xFF000000 | Integer.parseInt(v, 16));
        });
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (binding) {
      binding = false;
      if (e.key() != 256) {
        KwamsterClient.openKey.setKey(InputConstants.getKey(e));
        KeyMapping.resetMapping();
        minecraft.options.save();
      }
      init();
      return true;
    }
    return super.keyPressed(e);
  }
}
