# Architecture

The Fabric entrypoint is the composition root. Minecraft state is read on the client thread; the storage worker receives finalized snapshots and never accesses world state. No rendering replacement, packet cancellation, packet mutation or input automation is installed by the production mod. Test-only input simulation and frame probes live in src/gametest and are excluded from the release jar.

## Capture and ordering

Three required, narrow mixins observe vanilla methods:

| Hook | Purpose | Invariant |
| --- | --- | --- |
| Minecraft.startAttack, immediately before LocalPlayer.swing | Actual empty swing | Vanilla guards have run; no blocked-click inflation |
| MultiPlayerGameMode.attack HEAD | Attack state before local cooldown reset | One capture path for entity attack attempts |
| ClientPacketListener.handleDamageEvent TAIL | Source-attributed server damage report | Vanilla main-thread dispatch has completed |

Monotonic session-relative nanoseconds and increasing sequence IDs order immutable CombatEvent records. Wall-clock timestamps are recording metadata only. The engine rejects duplicate IDs and backward time. It matches at most one pending attack to each eligible report, consumes that attack, and expires the 64-entry pending queue. Source, direct source, damage type, target identity and a bounded timing window are required by capture. A damage animation alone never confirms a hit. The server source is confirmed; its attribution to one particular recent attack remains a temporal inference.

## Engine and derived data

CombatEngine implements IDLE, ENGAGED, ENDING, FINALIZED. Target switches, disconnects, client shutdown and the 10,000-event safety cap interrupt fights. Inactivity ends with UNKNOWN; local death gives LOSS, opponent death stays UNKNOWN because kill attribution is unavailable. Every new fight resets accumulators. Retaliation by the tracked opponent breaks combos. Engagement with additional opponents is split, preserving the completed segment.

Geometry, eligibility, readiness and statistics are independent of Minecraft classes and tested with synthetic inputs. Raw events are stored separately from derived statistics and Evaluation records. Policy values are frozen at fight start so later settings changes do not change the recorded interpretation. Additive v1 fields have defaults for recordings made earlier in development. Unknown schema versions are preserved and skipped, never guessed.

## Persistence and bounds

One daemon writer, a 128-task queue, unique temporary files and atomic replace prevent partial overwrites. A full queue reports failure rather than blocking rendering. Graceful shutdown drains for up to five seconds. Abrupt termination can lose the active fight or queued writes, but cannot replace an existing file with a partial one. Atomic rename falls back to filesystem replace only when unsupported.

The active stream is capped at 10,000 events; pending attacks at 64; in-memory history at 50,000 events and 1,000 fights. Loading accepts at most 64 MiB of JSON and scans at most 2,000 files. Individual reports exceeding 4 MiB and symlinks are skipped. Retention prunes valid UUID-named managed records by date and configured byte/count budgets, independently of the smaller memory cache. Corrupt, future and unrecognized files are preserved and can consume additional disk space; inspect logged warnings rather than assuming the entire folder is under budget.

The config schema is v1. Invalid originals are copied to timestamped backups before defaults load. There was no pre-existing configuration schema to migrate. JSON and HTML exports contain no account credentials, chat, server address, world seed or unrelated player profiles.

## Rendering and UI

Fabric HUD elements register before chat, retaining vanilla HUD visibility. Text summaries refresh at 5 Hz; event processing is incremental. Movement uses a horizontal facing-vector dot product. Normalized anchors are measured over available viewport space after panel dimensions and scale are subtracted. This keeps panels inside the window and preserves their relative placement across GUI scale changes.

Custom buttons and sliders reuse vanilla focus, keyboard and narration behavior. Panels bound labels to the viewport. Long screens scroll with the wheel or Page Up/Down. Color selectors use RGB hexadecimal text; choice dialogs and key selectors use the same screen/control system. HUD editing supports pointer drag, keyboard nudges and wheel scaling. GUI input remains in the Screen event system.

## Sharing

ReportExporter writes report.json and index.html. The viewer reads an embedded JSON payload, escapes HTML-sensitive characters, uses textContent for data and has a restrictive content security policy. It has no network code. Timeline playback reconstructs annotations, not world state. Hosting requires an owner-selected destination, privacy policy, access controls and explicit deployment authorization.
