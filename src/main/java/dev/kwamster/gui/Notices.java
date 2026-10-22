package dev.kwamster.gui;

import dev.kwamster.KwamsterClient;
import net.minecraft.client.Minecraft;

/** The dashboard's single status line. Call from any thread; updates land on the client thread. */
public final class Notices {
  public enum Level {
    INFO,
    OK,
    ERROR
  }

  public static volatile String text = "";
  public static volatile Level level = Level.INFO;
  public static volatile long at;

  private Notices() {}

  public static void post(String message, Level l) {
    Runnable apply =
        () -> {
          text = message;
          level = l;
          at = System.currentTimeMillis();
          KwamsterClient.status = message;
          if (l == Level.ERROR) KwamsterClient.LOG.warn("{}", message);
        };
    var mc = Minecraft.getInstance();
    if (mc == null || mc.isSameThread()) apply.run();
    else mc.execute(apply);
  }

  public static boolean recent() {
    return !text.isEmpty() && System.currentTimeMillis() - at < 8000;
  }
}
