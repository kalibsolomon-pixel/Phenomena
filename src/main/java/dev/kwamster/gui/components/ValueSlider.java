package dev.kwamster.gui.components;

import dev.kwamster.gui.theme.Theme;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public final class ValueSlider extends AbstractSliderButton {
  private final String label;
  private final double min, max;
  private final DoubleConsumer setter;

  public ValueSlider(
      int x,
      int y,
      int w,
      String label,
      double current,
      double min,
      double max,
      DoubleConsumer setter) {
    super(x, y, w, 20, Component.empty(), (current - min) / (max - min));
    this.label = label;
    this.min = min;
    this.max = max;
    this.setter = setter;
    updateMessage();
  }

  @Override
  protected void updateMessage() {
    setMessage(
        Component.literal(
            String.format(Locale.ROOT, "%s: %.2f", label, min + value * (max - min))));
  }

  @Override
  protected void applyValue() {
    setter.accept(min + value * (max - min));
  }

  @Override
  public void renderWidget(GuiGraphics g, int mx, int my, float d) {
    g.fill(getX(), getY(), getX() + width, getY() + height, Theme.SURFACE);
    g.fill(getX(), getY() + 18, getX() + (int) (width * value), getY() + 20, 0xFF88C8AA);
    g.renderOutline(getX(), getY(), width, height, isFocused() ? 0xFF88C8AA : Theme.BORDER);
    g.drawCenteredString(
        Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + 6, Theme.TEXT);
  }
}
