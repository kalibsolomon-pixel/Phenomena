package dev.kwamster.gui.components;

import dev.kwamster.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class ActionButton extends Button {
  public ActionButton(int x, int y, int w, String label, Runnable action) {
    super(x, y, w, 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION);
  }

  @Override
  protected void renderContents(GuiGraphics g, int mx, int my, float delta) {
    g.fill(
        getX(),
        getY(),
        getX() + width,
        getY() + height,
        isHoveredOrFocused() ? 0xFF303C40 : Theme.SURFACE);
    g.renderOutline(getX(), getY(), width, height, isFocused() ? 0xFF88C8AA : Theme.BORDER);
    g.drawCenteredString(
        Minecraft.getInstance().font,
        getMessage(),
        getX() + width / 2,
        getY() + 6,
        active ? Theme.TEXT : Theme.MUTED);
  }
}
