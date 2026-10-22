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

The config schema is v2. Schema 1 files migrate on load (display mode becomes `chatSummary`; the persistent HUD is disabled) and the original is kept once as `kwamster.json.v1-backup`. Invalid or unknown-version originals are copied to timestamped backups before defaults load. Recordings gained an optional `opponentLabel` (entity type or player name, local display only); older recordings load with it absent. JSON and HTML exports contain no account credentials, chat, server address, world seed or unrelated player profiles.

## Derived analytics (shared by GUI and reports)

`dev.kwamster.analytics` is independent of Minecraft. `FightAnalytics` derives per-hit, per-miss, readiness, cooldown, reach-bin, placement, interval, combo-chain, jump-timing and momentum data from one recording's raw events and frozen evaluations. It does not re-correlate: an attack is a hit exactly when the engine recorded an evaluation for it, and the momentum timeline replays the `Statistics` constants so its value at every hit equals the recorded evaluation. `Aggregate` pools counting metrics from per-fight `Statistics` (available even for summary-only records) and per-hit distributions from fights whose timelines are in memory, reporting that coverage. Missing data is NaN and renders as a dash. `MetricDefs` holds the labels, units, confidence and help text used by both surfaces. `HistoryQuery` filters and sorts summaries without loading timelines.

## Dashboard GUI

`KwamsterScreen` is one Screen with four persistent tabs (Overview, Combat Analytics, Fight History, Settings), a contextual title row with optional section chips and right-aligned toolbar dropdowns, one clipped scroll viewport and a status footer. Pages build a vertical flow through `Content` (stat grids, charts, tables, notes, setting rows, widget rows) in content coordinates; the screen offsets items by scroll and re-positions widgets without rebuilding them. Rebuilds happen only on tab/section/structure changes or when `KwamsterClient.historyVersion` changes, and restore keyboard focus by widget id. `UiState` (pure) keeps tab, sections, scope, filters, selection and per-page scroll for the session.

Content widgets reject pointer input outside the viewport (`Viewport`), so nothing scrolled under the header or footer is clickable. Focus changes scroll the focused widget into view. `Popup` overlays (dropdown lists and modal confirmations) render on a separate stratum and receive all input while open; Escape dismisses them first. The dashboard key closes the screen unless a text field is focused; 1-4 switch tabs. While the screen is open, gameplay input is handled by the Screen event system as with any vanilla screen; tracking continues because capture runs from mixins and client ticks, not from the GUI. `Layout` (pure) computes the frame, tab segments, chip wrapping, card columns, scrollbar and reveal arithmetic; layouts become compact automatically below 300 scaled pixels of height.

Charts (`Blocks`) are drawn with fills from data prepared at build time: single-axis line/point charts with NaN gaps and crosshair tooltips, grouped bars, a part-to-whole bar, a normalized hitbox (individual impacts or a 6x12 density grid) and virtualized tables. Series colors use a dark-surface categorical order validated for colour-vision deficiency; the accent color is reserved for interface chrome. `DataCache` memoizes aggregates per history version and scope, per-fight analytics (LRU 256), and up to four timelines loaded on demand from disk on the storage worker. At the in-memory caps (1,000 fights, 50,000 events) an aggregate rebuild measured about 5 ms; it never runs per frame.

## File actions

`FightActions` performs every user-initiated file operation on the storage worker. `SafePaths` resolves only `<gameDir>/kwamster/fights`, `<gameDir>/kwamster/exports` and one folder per fight under exports, named `Fight vs <opponent> <local start time>` (opponent omitted when anonymizing), with recording-derived text sanitized to a single safe segment, the fight ID required to be a canonical UUID, and a short-ID suffix when another fight owns the name, creates missing managed directories, and refuses symbolic links and non-directories. `SystemOpener` validates that the target exists and launches `explorer.exe` / `rundll32 url.dll,FileProtocolHandler <file URI>` on Windows, `open` on macOS and `xdg-open` on Linux from a short-lived daemon thread; failures become footer notices. Nothing read from a recording becomes a path. The launcher is replaceable so tests record commands instead of opening windows.

## Persistent HUD (dormant)

The 0.1.0 HUD panels, fight-summary popup, floating readiness text and movement indicator still compile but render only when `hudOverlay` is true, which no interface sets. `CombatHud.tick` is skipped unless enabled, so tracking has no HUD dependency. `HudEditorScreen` is unreachable from the dashboard.

## Sharing

`ReportExporter` writes `report.json` (the recording after privacy options) and `index.html`. The HTML embeds one JSON payload: format/version, privacy flags, metric definitions, the shared recording and the derived analytics computed by `FightAnalytics`. Anonymization is structural: the opponent UUID becomes "Opponent" in the record and its events, and the opponent label is dropped. Motion traces can be omitted. The payload is serialized without HTML escaping and then made script-safe by escaping `<`, `>`, `&`, U+2028 and U+2029 (valid inside JSON strings only). The viewer parses it with `JSON.parse`, builds every element with `createElement`/`textContent` (no `innerHTML`), uses SVG for charts, has no network code and ships a content security policy of `default-src 'none'` with inline script/style only. Timeline playback advances through recorded timestamps and annotates observations; it does not reconstruct world state. Hosting requires an owner-selected destination, privacy policy, access controls and explicit deployment authorization.


## 0.1.2 additions

Announcements contains pure extensible host-scoped adapters; PacketListenerMixin reads system-chat packets at the client dispatch tail, separately from player and disguised chat. CombatEngine scopes evidence to the current host/local/opponent/round, deduplicates announcements, rejects unrelated/expired results and grants endpoint observations a bounded grace period. Same-opponent rematches require additional endpoint evidence and remain inferred. Live combat correlation still works on unknown servers. Motion/input traces are thinned at 9,000 events to reserve the 10,000-event bound for combat evidence.

ClientCapture snapshots effective weapon cooldowns and geometry. AttackTiming, JumpResets, MeleeAim and PerfectReach are Minecraft-independent classifiers. LivingEntityMixin observes real jump actions; input and server velocity observations are separate. Live jump correlation uses a bounded recent queue, while finalization derives the frozen candidate count from the full retained timeline.

AutoReports uses FightStore's single bounded worker (128 queued operations), at most 128 pending retry snapshots and a bounded 1,000-entry availability cache. Raw JSON is atomically persisted before HTML publication. Each export file is atomically replaced; an ownership marker plus a final receipt containing source/options and HTML/JSON digests distinguishes complete, duplicate and partial exports. Recovery checks existing recordings on startup without rewriting legacy files. Three immediate retries are followed by 30-second retries; queue overflow is reported and retained pending jobs can be pumped again. A process termination before initial disk persistence can still lose queued data; disk exhaustion is explicitly reported.

Local recordings use readable opponent folders with a UUID file inside; legacy UUID files at the fights root remain readable. Full UUID collision suffixes preserve occupied directories. Shared paths independently follow anonymization. Recording/export IDs match. No automatic pruning deletes prior recordings. Report schema 2 accepts absent optional fields; report-viewer payload format 3 shares the same Java-derived values with the GUI.
