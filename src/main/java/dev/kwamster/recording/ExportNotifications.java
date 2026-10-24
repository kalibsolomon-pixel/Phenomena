package dev.kwamster.recording;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Session-only, bounded completion delivery. World objects never own or reset this queue. */
public final class ExportNotifications {
  public static final String MESSAGE = "Fight saved; automatic HTML report ready";
  public static final int CAPACITY = 128;
  public static final long MAX_AGE_NANOS = 120_000_000_000L;

  /** One token per newly committed receipt revision; duplicate callbacks share its claim. */
  public static final class Completion {
    public final String recordingId;
    public final String revision;
    private final AtomicBoolean claimed = new AtomicBoolean();

    public Completion(String recordingId, String revision) {
      this.recordingId = recordingId;
      this.revision = revision;
    }
  }

  private record Pending(Completion completion, long queuedAt) {}

  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private final LongSupplier clock;

  public ExportNotifications(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * May be called by the storage worker. Expired/overflowed tokens are disposed, never replayed.
   */
  public synchronized void offer(Completion completion) {
    if (!completion.claimed.compareAndSet(false, true)) return;
    long now = clock.getAsLong();
    expire(now);
    if (pending.size() == CAPACITY) pending.removeFirst();
    pending.addLast(new Pending(completion, now));
  }

  /**
   * Call only on the client tick. A temporary missing player retains notices for up to 2 minutes.
   */
  public synchronized void tick(boolean playerAvailable, Consumer<String> display) {
    expire(clock.getAsLong());
    if (!playerAvailable) return;
    while (!pending.isEmpty()) {
      // Consume before invoking the UI: reentrant processing cannot redispatch the same token.
      pending.removeFirst();
      display.accept(MESSAGE);
    }
  }

  private void expire(long now) {
    while (!pending.isEmpty() && now - pending.getFirst().queuedAt >= MAX_AGE_NANOS)
      pending.removeFirst();
  }

  public synchronized int size() {
    return pending.size();
  }
}
