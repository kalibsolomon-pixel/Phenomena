package dev.phenomena.gui;

import dev.phenomena.PhenomenaClient;
import dev.phenomena.analytics.*;
import dev.phenomena.combat.fights.FightRecord;
import java.util.*;

/**
 * Client-thread cache of derived analytics. Aggregates are recomputed only when history changes
 * (tracked by {@link PhenomenaClient#historyVersion}) or the scope changes, never per frame.
 * Timelines loaded on demand for detail views live in a small LRU, not in history.
 */
public final class DataCache {
  private static final int FULL_CAPACITY = 4, ANALYTICS_CAPACITY = 256;

  private static long version = -1;
  private static final Map<Integer, Aggregate> AGGREGATES = new HashMap<>();
  private static final Map<String, FightAnalytics> ANALYTICS =
      new LinkedHashMap<>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FightAnalytics> e) {
          return size() > ANALYTICS_CAPACITY;
        }
      };
  private static final Map<String, FightRecord> FULL =
      new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FightRecord> e) {
          return size() > FULL_CAPACITY;
        }
      };
  private static final Set<String> LOADING = new HashSet<>();

  private DataCache() {}

  private static void sync() {
    if (version != PhenomenaClient.historyVersion) {
      version = PhenomenaClient.historyVersion;
      AGGREGATES.clear();
    }
  }

  /** Aggregate over the newest {@code limit} fights. */
  public static Aggregate aggregate(int limit) {
    sync();
    return AGGREGATES.computeIfAbsent(
        limit,
        l -> {
          var h = PhenomenaClient.history;
          return new Aggregate(
              List.copyOf(h.subList(0, Math.min(l, h.size()))), DataCache::analytics);
        });
  }

  public static FightAnalytics analytics(FightRecord f) {
    return ANALYTICS.computeIfAbsent(f.id(), id -> FightAnalytics.of(f));
  }

  public static FightRecord summary(String id) {
    if (id == null) return null;
    for (var f : PhenomenaClient.history) if (f.id().equals(id)) return f;
    return null;
  }

  /** The record with timeline if available now: in history or the on-demand LRU. */
  public static FightRecord full(String id) {
    var s = summary(id);
    if (s != null && s.hasTimeline()) return s;
    return FULL.get(id);
  }

  public static boolean loading(String id) {
    return LOADING.contains(id);
  }

  /**
   * Requests a timeline from disk on the storage worker. {@code done} runs on the client thread
   * after the record is cached (or the load failed).
   */
  public static void requestTimeline(String id, Runnable done) {
    if (id == null || full(id) != null || LOADING.contains(id) || PhenomenaClient.store == null) return;
    LOADING.add(id);
    var mc = net.minecraft.client.Minecraft.getInstance();
    boolean queued =
        PhenomenaClient.store.submit(
            () -> {
              FightRecord loaded = null;
              String error = null;
              try {
                loaded = PhenomenaClient.store.readOne(id);
              } catch (java.io.IOException ex) {
                error = ex.getMessage();
              }
              var result = loaded;
              var message = error;
              mc.execute(
                  () -> {
                    LOADING.remove(id);
                    if (result != null) FULL.put(id, result);
                    else Notices.post("Timeline unavailable: " + message, Notices.Level.ERROR);
                    done.run();
                  });
            });
    if (!queued) LOADING.remove(id);
  }

  public static void forget(String id) {
    FULL.remove(id);
    ANALYTICS.remove(id);
  }
}
