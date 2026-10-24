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

## 0.1.1 dashboard data and report generation

`./gradlew.bat benchmarkAnalytics` on the same machine:

| Measure | Result |
| --- | --- |
| Aggregate over 1,000 fights with 50,000 timeline events, the in-memory caps (best of 7) | 5.3 ms |
| Export of one 1,000-event fight (report.json plus index.html) | 59 ms, 432 KB HTML |
| Engine event processing | 204 ns/event, 103 allocated bytes/event (0.1.0: 191 ns, 103 B) |
| Atomic replacement of a small recording | 2.9 ms mean |

The aggregate runs only when history changes or the scope changes. Results are cached per scope, and per-fight analytics are cached in an LRU. Charts map precomputed arrays to pixels without re-deriving data per frame. Report generation and folder or browser opening run on the storage worker and a short-lived launcher thread, never on the render thread. The frame-time comparison above was not repeated for 0.1.1. Its HUD and recording modes now exercise the dormant overlay through `hudOverlay`. Raw output is in build/performance/analytics.json.


## 0.1.3 — 2026-10-24

Same machine, Java and Minecraft stack as above. Raw files are in [performance/0.1.3/](performance/0.1.3/); the earlier files are kept unchanged.

### Frame time (same gametest loop as the 0.1.0 table)

| Mode | Samples | Mean ms | P95 ms | P99 ms | 0.1.0 mean / P95 |
| --- | ---: | ---: | ---: | ---: | --- |
| Fabric baseline (KWAMSTER disabled) | 5690 | 0.262 | 0.441 | 0.702 | 0.274 / 0.472 |
| Inactive | 6386 | 0.229 | 0.390 | 0.606 | 0.255 / 0.469 |
| Tracking | 6360 | 0.235 | 0.401 | 0.642 | 0.255 / 0.483 |
| HUD (dormant overlay forced on) | 5966 | 0.249 | 0.439 | 0.784 | 0.276 / 0.493 |
| Recording | 5773 | 0.258 | 0.479 | 0.796 | 0.279 / 0.520 |

The tracking and recording modes now include the new capture work: sound and animate observations, per-tick health, teleport and removal checks, and richer finalization. They stayed within the baseline's run-to-run variation. As before, this is a synthetic high-frame-rate loop with one pass per mode, not interactive FPS, and no causal overhead percentage follows from it.

### Analytics and storage (`./scripts/dev.ps1 benchmarkAnalytics`)

| Measure | 0.1.3 | 0.1.1/0.1.2 |
| --- | ---: | ---: |
| Engine, attack/hit pairs, finalizing every 1,000 pairs | 318–400 ns/event, 170 B/event | 204 ns, 103 B |
| Engine, 0.1.3 mixed events (attempts, hits, attributed incoming, velocity, health, jumps, shield, motion) after warm-up | 938–1,266 ns/event, 475 B/event | — |
| Per-fight analytics of a 10,000-event fight (the recording cap), best of 5 | 48–55 ms | — |
| Dashboard aggregate, 1,000 fights / 50,000 timeline events (best of 7) | 31–38 ms | 5.3 ms |
| Dashboard aggregate, 1,000 fights with 20 context-rich timelines | 15–28 ms | — |
| Report export, 1,000-event fight | 111–124 ms, 621 KB | 59 ms, 432 KB |
| Report export, 10,000-event fight | 188–232 ms, 3.9 MB | — |
| Atomic replacement of a small recording | 3.2–4.1 ms | 2.9 ms |

Per-event engine cost rose because every finalization now runs the shared combo analysis, jump analysis and health summary. Profiling with Java Flight Recorder found two hot spots, both fixed before these numbers were taken: a quadratic largest-swing search (now a linear sliding window, verified against brute force) and a per-event hash map in the combo analysis (now binary search). The remaining cost per 1,000-event fight is spread across the dominance sampling, combo, incoming and aim summaries.

Analytics run only when history or scope changes and are cached per fight; they never run per frame. On the client thread, the worst case at the in-memory caps is one page build of a few tens of milliseconds after a fight ends. Exports run on the storage worker. Reports are larger because they embed dominance samples, combo structure and per-attempt data. A 10,000-event fight produces a report of about 4 MB.

Ranges span three back-to-back runs after the optimizations. This machine shows about ±20 % variation between runs, and the 0.1.1/0.1.2 values come from earlier sessions. Treat the comparison as indicative.


## 0.1.4 manual recording and diagnostic capture

Fresh muted runs, isolated outputs: `build/performance/v014-perf-baseline/baseline.json` and `v014-perf-mod/mod.json`. Summary files, without the large raw frame arrays, are committed in [performance/0.1.4](performance/0.1.4). Same stride-eight high-frame-rate synthetic workload and hardware as 0.1.3. Optional compatibility mods are excluded from these measurements. Every mode reported throttle reason NONE.

| Mode | Samples | Mean ms | P95 ms | P99 ms |
| --- | ---: | ---: | ---: | ---: |
| Fabric baseline, KWAMSTER disabled | 5727 | 0.2582 | 0.4190 | 0.6259 |
| Inactive | 5809 | 0.2545 | 0.4429 | 0.6891 |
| Tracking | 6069 | 0.2429 | 0.4142 | 0.6386 |
| Dormant HUD forced on | 5646 | 0.2651 | 0.4540 | 0.7792 |
| Automatic recording | 5740 | 0.2601 | 0.4503 | 0.7621 |
| Manual recording | 5689 | 0.2641 | 0.4538 | 0.7308 |
| Manual with diagnostic capture | 6134 | 0.2453 | 0.4298 | 0.7415 |

No clear frame regression appears in this short synthetic scene. These are one run per mode, with run order/JIT/GC variation; the lower diagnostic mean is not evidence that diagnostics improve performance. They are not interactive FPS or long-multiplayer-session measurements.

`benchmarkRecording` separately replayed 10,000 events per run (one discarded warmup, three reported runs). Observed ranges were 668–1091 ns/event automatic, 3056–4582 ns/event manual, and 1594–2364 ns/event manual plus diagnostics. Timed work includes event processing, exchange finalization, participant collection and five-second snapshot copies. Disk writes and HTML generation are outside that event timing; async diagnostic chunk queuing is included. Warmup and mode order prevent interpreting the ranges as causal overhead percentages. Raw values are in `performance/0.1.4/recording.json`.

Diagnostics are off by default and return after a boolean check. Enabled captures rotate at 2,048 relevant entries. Serialization, fixture reduction, atomic writes, report analytics and exports use the storage worker. Snapshot copying and participant/summary aggregation run on the client thread every five seconds, and grow with session length; parent data remains memory-resident. This release does not claim bounded constant-time snapshots, unlimited sessions, or hours-long memory/performance acceptance. No render-frame file IO or diagnostic upload was introduced.
