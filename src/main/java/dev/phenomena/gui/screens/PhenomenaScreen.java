package dev.phenomena.gui.screens;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.gui.Notices;
import dev.phenomena.gui.components.*;
import dev.phenomena.gui.theme.Theme;
import dev.phenomena.gui.ui.Layout;
import dev.phenomena.gui.ui.Layout.Rect;
import dev.phenomena.gui.ui.UiState;
import dev.phenomena.gui.ui.UiState.Tab;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Phenomena dashboard: header, persistent primary tabs, a contextual heading with optional
 * section chips, and one clipped scroll area. Navigation state lives in {@link UiState} so tabs,
 * sections, filters and scroll positions survive switching and reopening. Combat tracking never
 * depends on this screen being open.
 */
public final class PhenomenaScreen extends Screen {
  /** One primary tab. */
  public interface Page {
    String title();

    /** Right-aligned context text beside the title, or null. */
    default String context() {
      return null;
    }

    /** Section chips under the title, or an empty list. */
    default List<String> sections() {
      return List.of();
    }

    default int section() {
      return 0;
    }

    default void section(int index) {}

    /** Fixed controls aligned right on the title row (dropdowns, actions). */
    default List<AbstractWidget> toolbar(PhenomenaScreen s) {
      return List.of();
    }

    void build(Content c);
  }

  public final UiState state = UiState.SESSION;
  private final Map<Tab, Page> pages = new EnumMap<>(Tab.class);
  private final List<Content.Item> items = new ArrayList<>();
  private final List<KButton> tabButtons = new ArrayList<>();
  private Rect frame, viewport, panel;
  private int contentHeight, scroll, headerH, tabsY, titleY;
  private Popup popup;
  private boolean draggingBar;
  private long builtVersion = -1;
  private float indicatorX = -1;
  private Consumer<KeyEvent> keyCapture;

  public PhenomenaScreen() {
    super(Component.literal("Phenomena"));
    pages.put(Tab.OVERVIEW, new OverviewPage(this));
    pages.put(Tab.OFFENSE, new OffensePage(this));
    pages.put(Tab.DEFENSE, new DefensePage(this));
    pages.put(Tab.FLOW, new FlowPage(this));
    pages.put(Tab.HISTORY, new HistoryPage(this));
    pages.put(Tab.SETTINGS, new SettingsPage(this));
  }

  public Page page() {
    return pages.get(state.tab);
  }

  // ---- layout --------------------------------------------------------------

  @Override
  protected void init() {
    popup = null;
    items.clear();
    tabButtons.clear();
    boolean compact = tight();
    frame = Layout.frame(width, height, compact);
    headerH = compact ? 18 : 22;
    tabsY = headerH;
    int tabH = compact ? 16 : 18;
    var tabRects = Layout.segments(frame.x(), tabsY, frame.w(), tabH, Tab.values().length, 2);
    for (var t : Tab.values()) {
      var r = tabRects.get(t.ordinal());
      var b =
          new KButton(t.label, KButton.Style.TAB, () -> t.label, () -> selectTab(t))
              .selected(() -> state.tab == t);
      b.setX(r.x());
      b.setY(r.y());
      b.size(r.w(), r.h());
      tabButtons.add(addRenderableWidget(b));
    }
    titleY = tabsY + tabH + (compact ? 6 : 9);
    var page = page();

    // Toolbar widgets right-aligned on the title row.
    int tx = frame.right() - 6;
    int toolH = compact ? 13 : 14;
    var tools = page.toolbar(this);
    for (int i = tools.size() - 1; i >= 0; i--) {
      var w = tools.get(i);
      tx -= w.getWidth();
      w.setX(tx);
      w.setY(titleY - 3);
      w.setHeight(toolH);
      addRenderableWidget(w);
      tx -= 4;
    }

    int y = titleY + (compact ? 12 : 14);
    var sections = page.sections();
    if (!sections.isEmpty()) {
      var rects =
          Layout.wrap(
              sections, s -> font.width(s), frame.x() + 6, y, frame.w() - 12, compact ? 13 : 14, 6, 3);
      for (int i = 0; i < sections.size(); i++) {
        int idx = i;
        var r = rects.get(i);
        var chip =
            new KButton(
                    "section:" + sections.get(i),
                    KButton.Style.CHIP,
                    () -> sections.get(idx),
                    () -> {
                      page.section(idx);
                      rebuild();
                    })
                .selected(() -> page.section() == idx);
        chip.setX(r.x());
        chip.setY(r.y());
        chip.size(r.w(), r.h());
        addRenderableWidget(chip);
      }
      y += Layout.rowsHeight(rects, y) + (compact ? 5 : 7);
    }
    int footerH = compact ? 16 : 20;
    panel = new Rect(frame.x(), titleY - (compact ? 6 : 8), frame.w(), height - footerH - (titleY - (compact ? 6 : 8)) - 2);
    viewport = new Rect(frame.x() + 6, y, frame.w() - 16, Math.max(20, panel.bottom() - y - 4));
    Viewport.set(viewport);

    var content = new Content(this, viewport.w(), compact);
    page.build(content);
    contentHeight = content.height();
    for (var item : content.items) {
      items.add(item);
      if (item.widget() != null) addWidget(item.widget());
    }
    scroll = state.clampScroll(contentHeight, viewport.h());
    placeWidgets();

    var close =
        new KButton("Close", KButton.Style.SUBTLE, this::onClose).tooltip("Escape or the menu key");
    close.size(Math.max(44, font.width("Close") + 16), footerH - 4);
    close.setX(frame.right() - close.getWidth());
    close.setY(height - footerH + 1);
    addRenderableWidget(close);
    builtVersion = PhenomenaClient.historyVersion;
  }

  private void placeWidgets() {
    for (var item : items) {
      if (item.widget() == null) continue;
      item.widget().setX(viewport.x() + item.x() + item.insetX());
      item.widget().setY(viewport.y() + item.y() + item.insetY() - scroll);
    }
  }

  /** Rebuild the current page, keeping scroll and restoring keyboard focus by widget id. */
  public void rebuild() {
    String focusId = idOf(getFocused());
    rebuildWidgets();
    if (focusId != null)
      for (var child : children())
        if (focusId.equals(idOf(child))) {
          setFocused(child);
          break;
        }
  }

  private static String idOf(GuiEventListener l) {
    if (l instanceof KButton b) return "b:" + b.id;
    if (l instanceof KSlider s) return "s:" + s.id;
    if (l instanceof KTextField t) return "t:" + t.id;
    if (l instanceof ColorSwatches c) return "c:" + c.id;
    if (l instanceof FightList) return "list";
    return null;
  }

  public void selectTab(Tab t) {
    if (state.tab == t) return;
    state.select(t);
    rebuild();
  }

  public void setScroll(int value) {
    scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport.h()), value));
    state.setScroll(scroll);
    placeWidgets();
  }

  /** Scrolls so an absolute screen-space band becomes visible. */
  public void reveal(int top, int bottom) {
    int contentTop = top - viewport.y() + scroll, contentBottom = bottom - viewport.y() + scroll;
    setScroll(Layout.reveal(scroll, viewport.h(), contentTop - 4, contentBottom + 4));
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    super.setFocused(listener);
    if (listener instanceof AbstractWidget w && viewport != null)
      for (var item : items)
        if (item.widget() == w) {
          int top = viewport.y() + item.y() - scroll;
          reveal(top, top + item.h());
          break;
        }
  }

  // ---- popups & helpers for pages -----------------------------------------

  public KButton dropdown(
      String id, Supplier<String> label, List<String> options, IntSupplier current, IntConsumer pick) {
    KButton[] self = new KButton[1];
    self[0] =
        new KButton(
            id,
            KButton.Style.NORMAL,
            () -> label.get() + " v",
            () -> {
              var b = self[0];
              popup =
                  new Popup.Dropdown(
                      new Rect(b.getX(), b.getY(), b.getWidth(), b.getHeight()),
                      options,
                      current.getAsInt(),
                      i -> {
                        pick.accept(i);
                        rebuild();
                      },
                      width,
                      height);
            });
    self[0].setWidth(font.width(label.get() + " v") + 14);
    return self[0];
  }

  public void confirm(String title, String message, String action, Runnable run) {
    popup = new Popup.Confirm(title, message, action, run);
  }

  public boolean popupOpen() {
    return popup != null;
  }

  /** Routes the next key press to the capture (key rebinding). */
  public void captureKey(Consumer<KeyEvent> capture) {
    keyCapture = capture;
  }

  public boolean capturing() {
    return keyCapture != null;
  }

  /** True for widgets inside the scroll area (used by layout checks). */
  public boolean isContentWidget(GuiEventListener l) {
    for (var item : items) if (item.widget() == l) return true;
    return false;
  }

  public int contentWidth() {
    return viewport == null ? 200 : viewport.w();
  }

  // ---- input ---------------------------------------------------------------

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
    if (popup != null) {
      if (!popup.click(e.x(), e.y())) popup = null;
      return true;
    }
    var bar = scrollTrack();
    if (bar != null && e.button() == 0 && e.x() >= bar.x() - 2 && e.x() < bar.right() + 2 && bar.contains(bar.x(), e.y())) {
      draggingBar = true;
      dragBar(e.y());
      return true;
    }
    return super.mouseClicked(e, doubleClick);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (draggingBar) {
      dragBar(e.y());
      return true;
    }
    return super.mouseDragged(e, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    draggingBar = false;
    return super.mouseReleased(e);
  }

  private void dragBar(double y) {
    var track = scrollTrack();
    if (track == null) return;
    double t = (y - track.y()) / Math.max(1, track.h());
    setScroll((int) Math.round(t * (contentHeight - viewport.h())));
  }

  private Rect scrollTrack() {
    if (viewport == null || contentHeight <= viewport.h()) return null;
    return new Rect(viewport.right() + 4, viewport.y(), 3, viewport.h());
  }

  @Override
  public boolean mouseScrolled(double x, double y, double h, double v) {
    if (popup != null) {
      popup.scroll(v);
      return true;
    }
    if (viewport != null && contentHeight > viewport.h()) {
      setScroll(scroll - (int) Math.round(v * Layout.wheelStep(viewport.h())));
      return true;
    }
    return super.mouseScrolled(x, y, h, v);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (popup != null) {
      if (!popup.key(e.key())) popup = null;
      return true;
    }
    if (keyCapture != null) {
      var capture = keyCapture;
      keyCapture = null;
      capture.accept(e);
      return true;
    }
    boolean typing = getFocused() instanceof EditBox;
    if (!typing) {
      switch (e.key()) {
        case GLFW.GLFW_KEY_PAGE_DOWN -> {
          setScroll(scroll + viewport.h() - 20);
          return true;
        }
        case GLFW.GLFW_KEY_PAGE_UP -> {
          setScroll(scroll - viewport.h() + 20);
          return true;
        }
        case GLFW.GLFW_KEY_HOME -> {
          setScroll(0);
          return true;
        }
        case GLFW.GLFW_KEY_END -> {
          setScroll(contentHeight);
          return true;
        }
        case GLFW.GLFW_KEY_1,
            GLFW.GLFW_KEY_2,
            GLFW.GLFW_KEY_3,
            GLFW.GLFW_KEY_4,
            GLFW.GLFW_KEY_5,
            GLFW.GLFW_KEY_6 -> {
          selectTab(Tab.values()[e.key() - GLFW.GLFW_KEY_1]);
          return true;
        }
        default -> {}
      }
      if (PhenomenaClient.openKey != null && PhenomenaClient.openKey.matches(e)) {
        onClose();
        return true;
      }
    }
    if (super.keyPressed(e)) return true;
    // Arrow keys on a focused tab switch tabs, matching common tab-strip behavior.
    if (getFocused() instanceof KButton b && tabButtons.contains(b)
        && (e.key() == GLFW.GLFW_KEY_LEFT || e.key() == GLFW.GLFW_KEY_RIGHT)) {
      int count = Tab.values().length;
      int i = (state.tab.ordinal() + (e.key() == GLFW.GLFW_KEY_LEFT ? count - 1 : 1)) % count;
      selectTab(Tab.values()[i]);
      setFocused(tabButtons.get(i));
      return true;
    }
    return false;
  }

  // ---- lifecycle -------------------------------------------------------------

  @Override
  public void tick() {
    if (builtVersion != PhenomenaClient.historyVersion
        && popup == null
        && !(getFocused() instanceof EditBox)
        && !draggingBar) rebuild();
  }

  @Override
  public void onClose() {
    PhenomenaClient.saveConfig();
    Viewport.set(null);
    minecraft.setScreen(null);
  }

  @Override
  public void removed() {
    PhenomenaClient.saveConfig();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  // ---- rendering -------------------------------------------------------------

  @Override
  public void renderBackground(GuiGraphics g, int mx, int my, float d) {
    g.fill(0, 0, width, height, Theme.BACKGROUND);
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float d) {
    Viewport.set(viewport);
    boolean compact = tight();
    int accent = Theme.accent();
    // Header band
    g.fill(0, 0, width, headerH, Theme.HEADER);
    g.fill(0, headerH - 1, width, headerH, Theme.BORDER_SOFT);
    int hy = (headerH - 8) / 2;
    g.drawString(font, Component.literal("Phenomena").withStyle(net.minecraft.ChatFormatting.BOLD), frame.x(), hy, accent, false);
    int nameW = font.width(Component.literal("Phenomena").withStyle(net.minecraft.ChatFormatting.BOLD));
    if (frame.w() > 360) g.drawString(font, "Combat analytics", frame.x() + nameW + 8, hy, Theme.DIM, false);
    String status = liveStatus();
    int sw = font.width(status);
    int dot = PhenomenaClient.config.tracking ? (PhenomenaClient.engine.engaged() ? Theme.WARN : Theme.OK) : Theme.DIM;
    g.fill(frame.right() - sw - 8, hy + 2, frame.right() - sw - 4, hy + 6, dot);
    g.drawString(font, status, frame.right() - sw, hy, Theme.MUTED, false);

    // Tab strip baseline and sliding indicator
    var active = tabButtons.get(state.tab.ordinal());
    g.fill(frame.x(), active.getBottom(), frame.right(), active.getBottom() + 1, Theme.BORDER_SOFT);
    float target = active.getX();
    if (indicatorX < 0 || Theme.reducedMotion()) indicatorX = target;
    else indicatorX += (target - indicatorX) * Math.min(1, d * .6f);

    // Content panel
    g.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), Theme.SURFACE);
    g.renderOutline(panel.x(), panel.y(), panel.w(), panel.h(), Theme.BORDER_SOFT);
    var page = page();
    int toolsLeft = frame.right();
    for (var child : children())
      if (child instanceof AbstractWidget w && w.getY() == titleY - 3 && !(w instanceof KButton kb && tabButtons.contains(kb)))
        toolsLeft = Math.min(toolsLeft, w.getX());
    String title = KButton.fit(font, page.title(), Math.max(20, toolsLeft - frame.x() - 18));
    g.drawString(font, title, frame.x() + 6, titleY, Theme.TEXT, false);
    String ctx = page.context();
    if (ctx != null) {
      int avail = toolsLeft - frame.x() - 18 - font.width(title) - 10;
      if (avail > 30) g.drawString(font, KButton.fit(font, ctx, avail), frame.x() + 12 + font.width(title), titleY, Theme.DIM, false);
    }

    super.render(g, mx, my, d);
    // Animated underline under the active tab (drawn after tab faces).
    if (!Theme.reducedMotion() && Math.abs(indicatorX - target) > .5f) {
      int ix = Math.round(indicatorX);
      g.fill(ix, active.getBottom() - 2, ix + active.getWidth(), active.getBottom(), Theme.alpha(accent, 0x90));
    }

    // Scrolled content, clipped to the viewport
    g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
    int top = viewport.y() - scroll;
    for (var item : items) {
      int iy = top + item.y();
      if (iy + item.h() < viewport.y() || iy > viewport.bottom()) continue;
      if (item.block() != null)
        item.block().render(g, viewport.x() + item.x(), iy, item.w(), item.h(), mx, my);
      else item.widget().render(g, mx, my, d);
    }
    g.disableScissor();
    var track = scrollTrack();
    if (track != null) {
      g.fill(track.x(), track.y(), track.right(), track.bottom(), Theme.alpha(Theme.BORDER_SOFT, 0x80));
      var thumb = Layout.thumb(track, contentHeight, scroll);
      if (thumb != null) g.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), draggingBar ? Theme.MUTED : Theme.BORDER);
      // Fade hints that more content exists above / below.
      if (scroll > 0) g.fillGradient(viewport.x(), viewport.y(), viewport.right(), viewport.y() + 6, 0x60000000, 0);
      if (scroll < contentHeight - viewport.h())
        g.fillGradient(viewport.x(), viewport.bottom() - 6, viewport.right(), viewport.bottom(), 0, 0x60000000);
    }

    // Footer status
    int fy = height - (compact ? 16 : 20) + (compact ? 4 : 6);
    String text;
    int color;
    if (keyCapture != null) {
      text = "Press a key to bind. Escape cancels.";
      color = Theme.WARN;
    } else if (Notices.recent()) {
      text = Notices.text;
      color = Notices.level == Notices.Level.ERROR ? Theme.ERROR : Notices.level == Notices.Level.OK ? Theme.OK : Theme.MUTED;
    } else {
      text = "Local only. 1-6 switch tabs, Tab moves focus, " + keyName() + " or Esc closes.";
      color = Theme.DIM;
    }
    g.drawString(font, KButton.fit(font, text, frame.w() - 60), frame.x(), fy, color, false);

    if (popup != null) {
      g.nextStratum();
      popup.render(g, mx, my, width, height);
    }
  }

  private String liveStatus() {
    var e = PhenomenaClient.engine;
    if (!PhenomenaClient.config.tracking) return "Tracking paused";
    String rec = PhenomenaClient.config.recording ? "" : " / not recording";
    return switch (e.state()) {
      case ENGAGED -> "In fight / " + e.stats().hits + " hits" + rec;
      case ENDING -> "Fight ending" + rec;
      default -> "Tracking" + rec;
    };
  }

  /** Compact spacing when chosen, or automatically when the scaled window is short. */
  public boolean tight() {
    return Theme.compact() || height < 300;
  }

  public static String keyName() {
    return PhenomenaClient.openKey == null ? "K" : PhenomenaClient.openKey.getTranslatedKeyMessage().getString();
  }
}
