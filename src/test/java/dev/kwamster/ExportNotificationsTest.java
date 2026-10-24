package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.recording.ExportNotifications;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExportNotificationsTest {
  final AtomicLong clock = new AtomicLong();
  final ExportNotifications queue = new ExportNotifications(clock::get);
  final List<String> chat = new ArrayList<>();

  ExportNotifications.Completion completion(String id) {
    return new ExportNotifications.Completion(id, "revision");
  }

  @Test
  void startupAndLobbyWithoutCompletionAreSilent() {
    queue.tick(false, chat::add);
    for (int i = 0; i < 100; i++) queue.tick(true, chat::add);
    assertTrue(chat.isEmpty());
    assertEquals(0, queue.size());
  }

  @Test
  void duplicateCallbackAndManyTicksConsumeOneCompletion() {
    var event = completion("recording-a");
    queue.offer(event);
    queue.offer(event);
    queue.tick(true, chat::add);
    queue.offer(event);
    for (int i = 0; i < 100; i++) queue.tick(true, chat::add);
    assertEquals(List.of(ExportNotifications.MESSAGE), chat);
    assertEquals(0, queue.size());
  }

  @Test
  void absentWorldDefersUntilPlayerThenEveryTransitionRemainsSilent() {
    var event = completion("recording-a");
    queue.offer(event);
    queue.tick(false, chat::add);
    assertTrue(chat.isEmpty());
    assertEquals(1, queue.size());
    clock.set(ExportNotifications.MAX_AGE_NANOS - 1);
    queue.tick(true, chat::add); // lobby
    for (String transition : List.of("arena", "lobby", "dimension", "reconnect", "server", "gui")) {
      queue.tick(false, chat::add);
      queue.offer(event); // stale callback after a transition
      queue.tick(true, chat::add);
      assertEquals(1, chat.size(), transition);
    }
  }

  @Test
  void offlineMessagesExpireAtExactDeadlineAndCannotBeRequeued() {
    var event = completion("recording-a");
    queue.offer(event);
    clock.set(ExportNotifications.MAX_AGE_NANOS);
    queue.tick(false, chat::add);
    assertEquals(0, queue.size());
    queue.offer(event);
    queue.tick(true, chat::add);
    assertTrue(chat.isEmpty());
    queue.offer(completion("new-recording"));
    queue.tick(true, chat::add);
    assertEquals(1, chat.size());
  }

  @Test
  void rapidDistinctCompletionsAndRevisionsShareTextButStayIndependent() {
    for (int i = 0; i < 20; i++) queue.offer(completion("recording-" + i));
    queue.offer(new ExportNotifications.Completion("recording-0", "new-revision"));
    queue.tick(true, chat::add);
    assertEquals(21, chat.size());
    assertEquals(0, queue.size());
  }

  @Test
  void overflowDisposesOldestWithoutRetainingAnUnboundedSeenCache() {
    var first = completion("oldest");
    queue.offer(first);
    for (int i = 0; i < ExportNotifications.CAPACITY; i++)
      queue.offer(completion("recording-" + i));
    assertEquals(ExportNotifications.CAPACITY, queue.size());
    queue.tick(true, chat::add);
    assertEquals(ExportNotifications.CAPACITY, chat.size());
    queue.offer(first);
    assertEquals(0, queue.size());
  }

  @Test
  void concurrentDuplicateCallbacksClaimTheSameTokenOnce() throws Exception {
    var event = completion("recording-a");
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 100; i++)
        tasks.add(
            () -> {
              queue.offer(event);
              return null;
            });
      for (var result : workers.invokeAll(tasks)) result.get();
    }
    queue.tick(true, chat::add);
    assertEquals(1, chat.size());
  }

  @Test
  void reentrantDrainCannotRedispatchConsumedToken() {
    queue.offer(completion("recording-a"));
    queue.tick(
        true,
        message -> {
          chat.add(message);
          queue.tick(true, chat::add);
        });
    assertEquals(1, chat.size());
  }
}
