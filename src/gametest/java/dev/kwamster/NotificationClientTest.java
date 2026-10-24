package dev.kwamster;

import dev.kwamster.recording.ExportNotifications;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Real client thread, tick delivery and local chat; no external server acceptance is claimed. */
public final class NotificationClientTest {
  public static final AtomicInteger DELIVERED = new AtomicInteger();
  private static final ExportNotifications.Completion OFFLINE =
      new ExportNotifications.Completion("acceptance-offline", "test-revision");

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  static void startup(ClientGameTestContext ctx) {
    ctx.runOnClient(
        mc -> {
          var store = KwamsterClient.store;
          var engine = KwamsterClient.engine;
          new KwamsterClient().onInitializeClient();
          new KwamsterClient().onInitializeClient();
          check(
              store == KwamsterClient.store && engine == KwamsterClient.engine,
              "reinitialization replaced listeners or storage");
          check(DELIVERED.get() == 0, "startup emitted ordinary completion");
          check(mc.player == null, "offline acceptance requires absent player");
          KwamsterClient.exportNotifications.offer(OFFLINE);
          KwamsterClient.exportNotifications.offer(OFFLINE);
        });
    ctx.waitTicks(3);
    check(DELIVERED.get() == 0, "completion delivered without player");
    check(KwamsterClient.exportNotifications.size() == 1, "offline completion lost or duplicated");
  }

  static void joined(ClientGameTestContext ctx) {
    ctx.waitTicks(3);
    check(DELIVERED.get() == 1, "offline completion did not deliver once on entry");
    ctx.runOnClient(mc -> KwamsterClient.exportNotifications.offer(OFFLINE));
    ctx.waitTicks(5);
    check(DELIVERED.get() == 1, "duplicate callback replayed on later tick");
  }

  static void reconnect(ClientGameTestContext ctx) {
    try {
      check(KwamsterClient.store.await(20000), "disconnect export did not finish");
    } catch (InterruptedException ex) {
      throw new RuntimeException(ex);
    }
    ctx.waitTicks(3);
    int before = DELIVERED.get();
    check(
        KwamsterClient.exportNotifications.size() == 1,
        "new disconnect export should await next world");
    try (var world = ctx.worldBuilder().create()) {
      ctx.waitTicks(10);
      check(DELIVERED.get() == before + 1, "reconnect lost or replayed completion");
      ctx.runOnClient(mc -> KwamsterClient.exportNotifications.offer(OFFLINE));
      ctx.waitTicks(10);
      check(DELIVERED.get() == before + 1, "reconnect replayed acknowledged event");
      check(KwamsterClient.exportNotifications.size() == 0, "delivered event retained");
    }
    KwamsterClient.LOG.info(
        "Notification client acceptance passed: actual chat, offline delivery, duplicate"
            + " initialization and reconnect");
  }
}
