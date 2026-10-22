package dev.kwamster.gui.screens;

import dev.kwamster.KwamsterClient;
import dev.kwamster.gui.components.*;
import dev.kwamster.gui.theme.Theme;
import java.util.function.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

public abstract class PanelScreen extends Screen {
  protected int scroll, row, total;
  protected String subtitle;

  protected PanelScreen(String title, String subtitle) {
    super(Component.literal(title));
    this.subtitle = subtitle;
  }

  @Override
  protected final void init() {
    clearWidgets();
    int w = (width - 48) / 4;
    String[] names = {"Overview", "History", "Settings", "HUD editor"};
    Runnable[] actions = {
      () -> minecraft.setScreen(new DashboardScreen()),
      () -> minecraft.setScreen(new HistoryScreen()),
      () -> minecraft.setScreen(new SettingsScreen()),
      () -> minecraft.setScreen(new HudEditorScreen())
    };
    for (int i = 0; i < 4; i++)
      addRenderableWidget(new ActionButton(24 + i * w, 52, w - 3, names[i], actions[i]));
    row = 0;
    content();
    total = row;
    addRenderableWidget(new ActionButton(width - 104, height - 30, 80, "Done", this::onClose));
  }

  protected abstract void content();

  protected int nextY() {
    return 84 + 24 * (row++ - scroll);
  }

  protected boolean visible(int y) {
    return y >= 84 && y + 20 <= height - 42;
  }

  protected void action(String label, Runnable r) {
    int y = nextY();
    if (visible(y)) addRenderableWidget(new ActionButton(24, y, width - 48, label, r));
  }

  protected void toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
    action(
        label + ": " + (get.getAsBoolean() ? "On" : "Off"),
        () -> {
          set.accept(!get.getAsBoolean());
          init();
        });
  }

  protected void slider(String label, double value, double min, double max, DoubleConsumer set) {
    int y = nextY();
    if (visible(y))
      addRenderableWidget(new ValueSlider(24, y, width - 48, label, value, min, max, set));
  }

  protected void textField(String label, String value, Consumer<String> set) {
    int y = nextY();
    if (visible(y)) {
      var edit = new EditBox(font, 24, y, width - 48, 20, Component.literal(label));
      edit.setMaxLength(128);
      edit.setValue(value);
      edit.setTooltip(Tooltip.create(Component.literal(label)));
      edit.setResponder(set);
      addRenderableWidget(edit);
    }
  }

  protected void label(String text) {
    int y = nextY();
    if (visible(y))
      addRenderableOnly(
          (g, mx, my, d) ->
              g.drawString(
                  font, font.plainSubstrByWidth(text, width - 52), 26, y + 6, Theme.MUTED));
  }

  protected void refresh() {
    init();
  }

  @Override
  public boolean mouseScrolled(double x, double y, double h, double v) {
    scroll =
        Math.max(
            0,
            Math.min(
                Math.max(0, total - Math.max(1, (height - 126) / 24)),
                scroll - (int) Math.signum(v) * 2));
    init();
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (e.key() == 267 || e.key() == 266) {
      mouseScrolled(0, 0, 0, e.key() == 267 ? -3 : 3);
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float d) {
    g.fill(0, 0, width, height, Theme.BACKGROUND);
    g.drawString(font, "KWAMSTER / " + title.getString(), 24, 17, Theme.TEXT);
    g.drawString(font, font.plainSubstrByWidth(subtitle, width - 48), 24, 33, Theme.MUTED);
    g.enableScissor(0, 0, width, height);
    super.render(g, mx, my, d);
    g.disableScissor();
    g.drawString(
        font,
        font.plainSubstrByWidth(KwamsterClient.status, width - 140),
        24,
        height - 24,
        Theme.MUTED);
    if (total > Math.max(1, (height - 126) / 24))
      g.drawString(font, "Scroll / PgUp PgDn", width - 145, 76, Theme.MUTED);
  }

  @Override
  public void onClose() {
    KwamsterClient.saveConfig();
    minecraft.setScreen(null);
  }

  @Override
  public void removed() {
    KwamsterClient.saveConfig();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
