package dev.kwamster.gui.screens;
import dev.kwamster.gui.components.ActionButton;
import dev.kwamster.gui.theme.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public class DashboardScreen extends Screen {
 public DashboardScreen() { super(Component.literal("KWAMSTER")); }
 @Override protected void init() { addRenderableWidget(new ActionButton(width/2-80,height-38,160,"Done",this::onClose)); }
 @Override public void render(GuiGraphics g,int mx,int my,float delta) {g.fill(0,0,width,height,Theme.BACKGROUND);g.drawString(font,"KWAMSTER",24,20,Theme.TEXT);g.drawString(font,"Combat intelligence / local by default",24,36,Theme.MUTED);super.render(g,mx,my,delta);}
 @Override public boolean isPauseScreen(){return false;}
}
