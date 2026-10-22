# Performance validation — 2026-10-21

Measured on Windows 11 Home 10.0.26300, Intel i7-14700F (20 cores / 28 logical processors), Radeon RX 9070 XT (driver 32.0.32015.7029), approximately 32 GiB RAM, Temurin Java 21.0.12.1. Minecraft 1.21.11, Loader 0.19.5, Fabric API 0.141.6+1.21.11. Optional optimization mods were excluded from these runs.

## Workload and method

A flat integrated-server world, stationary high-health husk, identical player position, one attempted melee attack every 20 ticks, 100 initial warmup ticks, 40 ticks between modes and 240 measured ticks per mode (approximately 12 seconds). Target position is reset for consistent geometry. Vsync is disabled; all tests are muted. A test-only probe times Minecraft.runTick and samples every eighth eligible rendered loop across the entire window; buffers did not fill. This is an artificial Fabric gametest loop that can run thousands of internal frames per second, **not normal interactive FPS**.

The mod comparison uses sequential inactive, tracking-only, HUD, then HUD+recording modes. Three explicit fight finalization checkpoints during each recording window exercise real background persistence. Sound and summary alerts are disabled for these measurements. Minecraft inactivity throttling is prevented and the final reason is recorded as NONE. Fabric's test packet synchronizer is disabled only for benchmark runs because repeated test commands tripped its lower-level packet assertion; the functional integration tests use normal synchronization.

| Mode | Samples | Mean ms | P95 ms | P99 ms | Process CPU seconds | End heap MiB | GC count / ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fabric baseline, first pass | 5789 | 0.255 | 0.452 | 0.955 | 32.58 | 510.6 | 25 / 57 |
| Fabric baseline, repeat | 5432 | 0.274 | 0.472 | 1.086 | 29.11 | 562.8 | 21 / 55 |
| Inactive | 5842 | 0.255 | 0.469 | 1.056 | 32.39 | 509.2 | 26 / 56 |
| Tracking | 5803 | 0.255 | 0.483 | 0.878 | 31.03 | 537.0 | 27 / 59 |
| HUD | 5445 | 0.276 | 0.493 | 1.091 | 36.33 | 329.3 | 56 / 71 |
| Recording | 5453 | 0.279 | 0.520 | 1.154 | 34.97 | 317.4 | 56 / 72 |

The first Fabric baseline averaged about .255 ms and the repeated baseline about .274 ms. HUD+recording averaged about .279 ms. Baseline variation is large relative to the apparent difference, so these runs do not establish a precise causal overhead percentage. HUD work increased collection frequency in this synthetic high-frame-rate loop, while total measured collection time remained small. End-heap values vary with GC timing and are not retained-memory overhead estimates. Process CPU includes the integrated server and every JVM thread, so CPU seconds can exceed wall seconds.

Background applications remained running, modes were not randomized, and there was only one mod-mode pass. No live multiplayer frame-time, long-session memory, GPU-time or broad hardware claim follows from this evidence. No zero-impact claim is made. Repeat representative live matches with identical settings and multiple randomized passes before choosing release performance budgets.

## Analytics and storage microbenchmark

After ten warmup fights, 200,000 synthetic events with a finalized snapshot every 1,000 attack/report pairs took **38.219 ms**, or **191 ns/event**, with **102.9 allocated bytes/event** on the benchmark thread. This includes event objects and periodic snapshot work; it excludes Minecraft geometry capture, render submission and network latency. It is a simple warmed JVM harness rather than JMH, and is not an end-to-end per-hit latency guarantee.

One hundred queued atomic replacements of a small one-attack fight report averaged **3.174 ms/write**, including worker drain and retention checks, with no errors. This is a small-report check, not an upper bound for 10,000-event fights or a full 1,000-report folder. Persistence runs off the render thread; queue-full and shutdown-timeout cases report failure rather than claiming a successful write.

Raw frame arrays, heap/CPU/GC counters and benchmark output are in [performance/](performance/). Reproduce commands are in [VALIDATION.md](VALIDATION.md). Initial invalid first-frame, buffer-saturation and AFK-throttled runs were rejected rather than included in these accepted results.
