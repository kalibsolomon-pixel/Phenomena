# Development log

## Initial inventory — 2026-10-21
Workspace C:\Dev\Kwamster was empty, including hidden files. No Git repository, HEAD, tracked files, untracked files, or existing work. No ancestor AGENTS.md found. Initialized main locally. Installed Java 25 only; no global Gradle. Downloaded checksum-verified Temurin Java 21 into ignored .tools.

Dependencies verified against Fabric metadata: Minecraft 1.21.11, stable Loader 0.19.5, Fabric API 0.141.6+1.21.11, Loom 1.14.10. Gradle 9.2.1. Official Mojang mappings. See https://www.fabricmc.net/2025/12/05/12111.html and https://meta.fabricmc.net/v2/versions/loader/1.21.11 and https://maven.fabricmc.net/.

Architecture: immutable raw events -> bounded ordered correlator -> fight lifecycle -> incremental statistics. Minecraft state is sampled only on the client thread. Persistence receives immutable finalized snapshots on a bounded single writer. Rendering consumes cached summaries. No server mod or packet modifications.

## Phase 0
Gradle build and 3 foundation tests pass on Java 21. Custom K dashboard compiled using 1.21.11 renderContents API. Live launch pending. Configuration schema v1 preserves invalid originals. No previous schema exists to migrate.

## Phase 1
24 unit tests pass (geometry, aim normalization, correlation, duplicate prevention, expiry, cooldown quality, fight lifecycle, combos and jump candidates). Build passes. Foundation development client launched and rendered assets successfully. Three narrow observational mixins capture entity attacks, empty swings and source-attributed damage packets after main-thread dispatch. Critical conditions remain observed; jump-reset success remains unavailable. Incoming motion-baseline estimates require controlled trials. Gameplay validation pending integration tests.

## Phases 2–7 — connected implementation and validation
Custom dashboard/settings/history screens, accessible vanilla-backed controls, independent HUD editor, four display modes, fight lifecycle and summary alerts, per-category cooldown-readiness feedback, movement indicator, combo/timing/momentum metrics, bounded local recording, lazy history timelines, anonymized portable reports and browser timeline replay are connected. These phases share the composition root and were validated together rather than committing disconnected UI/storage stubs.

The final unit suite contains 41 passing tests. Actual Fabric 1.21.11 client gametests exercise navigation, toggle persistence, resize, HUD scale/keyboard position, singleplayer melee correlation, history and file export. A second pass with Sodium 0.8.14, Lithium 0.21.4, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3 passes together. These are smoke tests, not a claim of universal compatibility. External-server PvP and audible sound perception remain untested.

Browser validation loaded the generated report, advanced playback, and observed the final raw events. Saved screenshots and a synthetic training report are local evidence. No combat recording was uploaded. Hosted sharing is explicitly pending infrastructure and owner authorization.

Review corrections included guarded empty-swing capture after vanilla validation; actual 1.21.11 mobility restrictions for critical conditions; interpolated geometry; the resolved vanilla attack-range accessor; world-change boundaries; avoiding target corruption by unrelated incoming damage; policy snapshots; bounded history with lazy timelines; and preventing stale async navigation callbacks.

All Java sources use consistent formatting. The Gradle wrapper distribution is checksum pinned. scripts/dev.ps1 selects the local Java 21 toolchain when available.

## Performance validation
Initial profiler output was rejected because a mid-frame start produced an invalid first sample, buffers saturated, and Minecraft's AFK limiter contaminated the final mode. Corrected test-only sampling spans the entire workload at stride eight, verifies throttle state, and uses actual persistence checkpoints. The benchmark is isolated from optional compatibility mods. See PERFORMANCE.md and docs/performance for the accepted data and limits. Synthetic analytics and atomic-write costs are measured separately.

## Owner preference
All development Minecraft test instances must stay muted while the owner watches a show. Both local options files have master volume zero; the test-only initializer reapplies it after Fabric's options reset. System and other application audio are unchanged.

## Local commits and final review
Foundation: 1cd57ec. Combat core: 0809231. Connected GUI/recording/replay implementation: 1823f9c. The final build and 41 tests pass; both ordinary and four-mod client gametests pass. Checked diffs contain additions and modifications only, with no unexpected deletions. No remote is configured and no push or release occurred. All test clients exited; their saved master volume remains zero.

Final estimator correction: use server-evidence arrival time for momentum decay while preserving attack-time hit intervals. A regression test covers retaliation between attack and delayed confirmation; 41 tests pass after the correction.

## 0.1.1 tabbed analytics GUI and local replay - 2026-10-22

Starting point: main at b19b5c2, clean tree. Export folders are named "Fight vs <opponent> <local time>" (opponent omitted when anonymizing) at the owner's request. Six empty untracked directories at the repository root (`.gitattribute`, `.gitignor`, `README.m`, `build.gradl`, `gradle.propertie`, `settings.gradl`) were left untouched.

The separate PanelScreen-family screens (Dashboard, History, FightDetail, Settings, Choice, Confirm) were replaced by one tabbed `KwamsterScreen` with persistent session state. These seven deletions are intentional. `HudEditorScreen` and `CombatHud` remain, dormant. A Minecraft-independent analytics package now feeds both the dashboard and the browser report. Configuration moved to schema 2 with a migration from schema 1. File actions go through `SafePaths` and `SystemOpener`.

Defects found and fixed during the work:

- `ReportExporter`'s `"\u003c"` replacements were Java compile-time escapes and did nothing; Gson's default HTML escaping had been covering them. Escaping is now explicit and tested.
- In the new viewer, a chained `Element.append()` stopped the Timeline and table rendering, and empty swings that retain geometry were labelled as unreported attacks.

The client test now generates genuine fights, including uncertain, sprint and critical-condition samples, and drives the GUI through real cursor and key input. A run with -PrealOpen=true opened File Explorer for the fights and exports folders and the default browser for the report. Screenshots were reviewed and the layout corrected: compact chrome on short windows, keyboard-only focus rings, column widths, swatch placement, label truncation and card tags.

The unit suite now has 78 tests, one of them skipped on Windows (symbolic-link privilege). Both client sequences pass. All development test clients stayed muted.


## 0.1.3 combat intelligence and detection accuracy - 2026-10-24

Starting point: `977f1ffe` on `codex/0.1.2-combat-corrections`, clean tree, 158 tests passing with one Windows skip. Starting state and SHA-256 digests of the owner's instance data are stored in the ignored `.local-diagnostics/v013`. No clean commands were used. The six empty root directories noted in 0.1.1 are still untouched.

Key evidence and changes:

- The owner's 0.1.2 recordings contained no win or loss at all. That led to host and format fixes in the adapters, plus a ranked-evidence lifecycle with deferred round starts and implicit best-of rounds. See MEASUREMENT_AUDIT.md.
- A labeled, anonymized fixture set (1,200 matches) is extracted from the logs by a read-only script and replayed in `OutcomeFixtureTest`. The 0.1.2 engine compiled from Git decides 0 of the same matches.
- Bytecode inspection of 1.21.11 `LivingEntity.hurtServer` showed that a shield-blocked hit sends no damage event. The capture layer now observes shield sounds, crit animations, the death packet and titles at the TAIL of vanilla handlers.

Defects found during the work and fixed:

- The viewer referenced an undefined SVG text helper, which broke the reach histogram; the browser check found it.
- A best-of countdown produced an empty pre-round recording.
- A kill message's 3-second grace could not be extended by the following match result.
- Results arriving between best-of rounds were dropped.
- The largest-swing search was quadratic; it is now a linear sliding window, checked against brute force.
- Combo analysis built a per-event hash map at every finalization; it now uses binary search.

Removed file: `gui/screens/AnalyticsPage.java`, replaced by the Offense, Defense and Fight Flow pages. Shared logic moved to `AnalysisSupport`.

The client test needed to hold `keyUse`/`keyJump` mappings directly, because Fabric's test input did not reach them while the window was ungrabbed. All test clients stayed muted, and every run used a new `build/run/v013-*` directory.


## 0.1.5 — six-server capture environments

Started at `ddb906ecc2a8c4d5fa1219dd9ad6a89ebea2d536`, clean tree on the existing branch. Inventoried 1,355 authoritative Aurora source files before read-only processing; 137 logging artifacts and two diagnostic captures locate all 18 matches. Added six exact-host capture environments, independent match/round evidence and combo boundaries, manual/disabled/configured/generic resolver, diagnostic policy/version/status and compact dashboard status. No generic fallback or inactivity end exists on configured environments.

Baseline 253 unit cases passed with one skip; final 293 cases have 292 passes, one skip and no failures. Both redacted announcement and private original-combat replays agree with all 18 owner outcomes. Historical export: 243/243. Muted final client and four-mod runs pass. Browser interaction is explicitly unverified after file-protocol rejection; static report contracts and syntax pass. All earlier logs, builds and screenshots are preserved. [0.1.5 notes](0.1.5.md) contain exact denominators, limitations, performance and correlation references. Changes are committed locally only.
