package dev.kwamster;

import com.google.gson.GsonBuilder;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class PerformanceScenario {
  public static void run(
      ClientGameTestContext ctx, String[] modes, Consumer<String> configure, String file) {
    List<Map<String, Object>> results = new ArrayList<>();
    try (var world = ctx.worldBuilder().create()) {
      world.getServer().runCommand("gamemode survival @a");
      world.getServer().runCommand("tp @a 0.5 -60 0.5 0 0");
      world
          .getServer()
          .runCommand(
              "summon minecraft:husk 0.5 -60 2.8 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
      world
          .getServer()
          .runCommand(
              "attribute @e[type=minecraft:husk,limit=1] minecraft:max_health base set 100000");
      world
          .getServer()
          .runCommand("data merge entity @e[type=minecraft:husk,limit=1] {Health:100000.0f}");
      ctx.runOnClient(
          mc -> {
            mc.setScreen(null);
            mc.options
                .getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER)
                .set(0.0);
            mc.options.enableVsync().set(false);
            mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
            mc.getFramerateLimitTracker().onInputReceived();
            mc.options.framerateLimit().set(260);
          });
      ctx.waitTicks(100);
      for (String mode : modes) {
        ctx.runOnClient(
            mc -> {
              mc.getFramerateLimitTracker().onInputReceived();
              configure.accept(mode);
            });
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> FrameProbe.reset());
        long gc = gcMillis(), collections = gcCount(), cpu = cpuNanos(), start = System.nanoTime();
        for (int i = 0; i < 240; i++) {
          if (i > 0 && i % 60 == 0) ctx.runOnClient(mc -> configure.accept("flush"));
          if (i % 20 == 0) {
            world.getServer().runCommand("tp @e[type=minecraft:husk,limit=1] 0.5 -60 2.8");
            ctx.runOnClient(
                mc -> {
                  for (var e : mc.level.entitiesForRendering())
                    if (e instanceof LivingEntity && e != mc.player) {
                      mc.hitResult = new EntityHitResult(e);
                      mc.gameMode.attack(mc.player, e);
                      break;
                    }
                });
          }
          ctx.waitTick();
        }
        double wall = (System.nanoTime() - start) / 1e9;
        double cpuMillis = (cpuNanos() - cpu) / 1e6;
        double[] frames = ctx.computeOnClient(mc -> FrameProbe.sample());
        double[] sorted = frames.clone();
        Arrays.sort(sorted);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", mode);
        r.put(
            "throttleReason",
            ctx.computeOnClient(mc -> mc.getFramerateLimitTracker().getThrottleReason().name()));
        r.put("samples", frames.length);
        r.put("totalFrames", ctx.computeOnClient(mc -> FrameProbe.totalFrames()));
        r.put("sampleStride", 8);
        r.put("meanFrameMs", Arrays.stream(frames).average().orElse(0));
        r.put("p95FrameMs", sorted.length == 0 ? 0 : sorted[(int) (sorted.length * .95)]);
        r.put("p99FrameMs", sorted.length == 0 ? 0 : sorted[(int) (sorted.length * .99)]);
        r.put("wallSeconds", wall);
        r.put("processCpuMs", cpuMillis);
        r.put(
            "usedHeapBytes",
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        r.put("gcMillis", gcMillis() - gc);
        r.put("gcCollections", gcCount() - collections);
        r.put("frameMs", frames);
        results.add(r);
      }
    }
    try {
      Path out = Path.of(System.getProperty("kwamster.perfOutput"));
      Files.createDirectories(out);
      Files.writeString(
          out.resolve(file), new GsonBuilder().setPrettyPrinting().create().toJson(results));
    } catch (java.io.IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionTime)
        .filter(n -> n >= 0)
        .sum();
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionCount)
        .filter(n -> n >= 0)
        .sum();
  }

  private static long cpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }
}
