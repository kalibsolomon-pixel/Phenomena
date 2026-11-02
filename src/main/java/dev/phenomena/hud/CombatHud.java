package dev.phenomena.hud;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.combat.metrics.*;
import dev.phenomena.combat.tracking.ClientCapture;
import dev.phenomena.config.Config.Hud;
import dev.phenomena.feedback.FeedbackController;
import dev.phenomena.hud.layout.HudLayout;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class CombatHud {
  public static String[] lines = {"Phenomena", "Awaiting engagement"};
  public static String direction = "Neutral";
  public static long summaryAt;
  private static long refreshed;

  private CombatHud() {}

  public static void tick() {
    long now = ClientCapture.now();
    var mc = Minecraft.getInstance();
    if (mc.player != null) {
      var v = mc.player.getDeltaMovement();
      var facing = mc.player.getLookAngle();
      double len = Math.hypot(facing.x, facing.z);
      direction =
          len < 1e-6 ? "Neutral" : Geometry.direction(v.x, v.z, facing.x / len, facing.z / len);
    }
    if (now - refreshed < 200_000_000L) return;
    refreshed = now;
    var e = PhenomenaClient.engine;
    var s = e.stats();
    lines =
        new String[] {
          "Phenomena / " + e.state(),
          String.format(Locale.ROOT, "Hits %d / %d  %.0f%%", s.hits, s.attempts, s.accuracy()),
          "Uncertain " + s.uncertain + " / Empty " + s.emptyMisses,
          String.format(Locale.ROOT, "Reach %.2f / %.2f m", s.averageReach(), s.maxReach),
          String.format(
              Locale.ROOT,
              "Crit cond. %d (%.0f%%) / Sprint %d",
              s.criticalConditions,
              s.hits == 0 ? 0 : 100.0 * s.criticalConditions / s.hits,
              s.sprintHits),
          "Jump candidates " + s.jumpCandidates + " / success N/A",
          "Combo " + s.currentCombo + " / Best " + s.longestCombo,
          String.format(
              Locale.ROOT, "Interval %.2fs / SD %.2f", s.averageInterval(), s.intervalDeviation()),
          String.format(Locale.ROOT, "Momentum est. %+.0f%%", 100 * s.momentumAt(now))
        };
  }

  public static String[] summary() {
    if (PhenomenaClient.history.isEmpty())
      return new String[] {"Fight summary", "No completed fights"};
    var f = PhenomenaClient.history.getFirst();
    var s = f.statistics();
    return new String[] {
      "FIGHT / " + f.outcome(),
      String.format(
          Locale.ROOT,
          "%.1fs / %d hits / %d attempts",
          f.durationNanos() / 1e9,
          s.hits,
          s.attempts),
      String.format(
          Locale.ROOT,
          "%.0f%% / empty %d / uncertain %d",
          s.accuracy(),
          s.emptyMisses,
          s.uncertain),
      String.format(Locale.ROOT, "Reach %.2f / %.2f m", s.averageReach(), s.maxReach),
      "Crit cond. " + s.criticalConditions + " / Sprint " + s.sprintHits,
      "Jump candidates " + s.jumpCandidates + " / Best combo " + s.longestCombo,
      String.format(Locale.ROOT, "Hit interval %.2fs", s.averageInterval())
    };
  }

  public static void render(GuiGraphics g) {
    var mc = Minecraft.getInstance();
    var c = PhenomenaClient.config;
    if (mc.player == null
        || mc.options.hideGui
        || mc.screen instanceof dev.phenomena.gui.screens.HudEditorScreen) return;
    // Dormant since 0.1.1: reached only when hudOverlay is enabled in the config file.
    if (c.hudOverlay) {
      panel(g, c.analytics, lines, c.accent, false);
      movement(g, c.direction, false);
    }
    long now = ClientCapture.now();
    if (c.hudOverlay && c.summary && now - summaryAt < c.alertSeconds * 1e9 && summaryAt > 0) {
      float fade =
          c.animate ? (float) Math.min(1, (c.alertSeconds - (now - summaryAt) / 1e9) / .3) : 1;
      panel(g, c.alert, summary(), c.accent, false, fade);
    }
    var grade = FeedbackController.recent;
    if (c.hudOverlay && grade != null && now - FeedbackController.recentAt < 700_000_000L) {
      int color = c.feedback[grade.ordinal()].color;
      int y = g.guiHeight() / 2 + 24;
      if (c.floatingText) y -= (int) ((now - FeedbackController.recentAt) / 40_000_000L);
      g.drawCenteredString(mc.font, grade + " readiness", g.guiWidth() / 2, y, color);
    }
  }

  public static void panel(GuiGraphics g, Hud h, String[] text, int accent, boolean preview) {
    panel(g, h, text, accent, preview, 1);
  }

  public static void panel(
      GuiGraphics g, Hud h, String[] text, int accent, boolean preview, float fade) {
    if (!h.visible && !preview) return;
    int w = panelWidth(text), height = text.length * 12 + 14;
    int x = HudLayout.x(h, g.guiWidth(), w), y = HudLayout.y(h, g.guiHeight(), height);
    g.pose().pushMatrix();
    g.pose().translate(x, y);
    g.pose().scale((float) h.scale, (float) h.scale);
    int alpha = (int) (h.opacity * fade * 255) << 24;
    if (h.background) g.fill(0, 0, w, height, alpha | 0x171C20);
    g.fill(0, 0, 2, height, alpha | (accent & 0xFFFFFF));
    for (int i = 0; i < text.length; i++) {
      int tx = h.rightAligned ? w - 7 - Minecraft.getInstance().font.width(text[i]) : 8;
      g.drawString(
          Minecraft.getInstance().font,
          text[i],
          tx,
          7 + i * 12,
          alpha | ((i == 0 ? accent : 0xFFE6ECEA) & 0xFFFFFF),
          false);
    }
    if (preview) g.renderOutline(0, 0, w, height, 0xFF88C8AA);
    g.pose().popMatrix();
  }

  public static int panelWidth(String[] text) {
    int w = 160;
    for (String l : text) w = Math.max(w, Minecraft.getInstance().font.width(l) + 16);
    return w;
  }

  public static void movement(GuiGraphics g, Hud h, boolean preview) {
    var c = PhenomenaClient.config;
    int color =
        direction.equals("Forward")
            ? c.forwardColor
            : direction.equals("Backward") ? c.backwardColor : c.neutralColor;
    panel(g, h, new String[] {"Movement / " + direction}, color, preview);
  }
}
