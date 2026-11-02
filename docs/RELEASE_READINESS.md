# Release readiness

## 0.2.1 public release validation — 2026-11-02

The owner approved publication as **Phenomena 0.2.1** to [kalibsolomon-pixel/Phenomena](https://github.com/kalibsolomon-pixel/Phenomena). The checkout initially had no remote or tags; no existing repository for this project was found in the authenticated owner's account. The similarly named public KWAMSTER project under another owner has different history and is not the release destination.

Release source builds on development commit `69699b63f39bc8111d31b6189aa802bc97d2e455`. Release preparation changes installation and release documentation only. The native GUI, recording implementation, analytics, dependencies and report schemas remain unchanged from the accepted implementation.

Verified production artifact: **`phenomena-0.2.1.jar`**, **610,742 bytes**, SHA-256 **`7164c15a00eee27b456aae8a40640d1cfd3044611ec6639db8f9b5c015e605ba`**. A fresh build in an isolated Git worktree reproduced the development jar byte-for-byte. Its Fabric identity, version, client entry point, four mixins, resource namespace and embedded report template were inspected. No tests, test instrumentation, private files or Aurora dependency are bundled. Opt-in local diagnostics remain an intended product feature.

- Unit suite: **362 cases: 361 passed, one pre-existing Windows symlink skip, zero failures/errors**.
- Muted Fabric 1.21.11 functional client: passed recording, native GUI, exports, configured capture environments, manual interruption, notification lifecycle and clean shutdown.
- Packaged-jar production client: the same full acceptance sequence passed in [Loom's production launcher](https://docs.fabricmc.net/develop/loom/production-run-tasks), using the release jar and a separate remapped test mod; clean shutdown and master volume zero confirmed. Test helpers are excluded from the distributed jar.
- Four-mod compatibility client: the same sequence passed with Sodium 0.8.14, Lithium 0.21.4, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3 together, without Aurora.
- Historical recordings: **332/332** exported separately, schemas 1, 2, 4 and 5, zero failures. All original recording lengths, timestamps and SHA-256 hashes remained unchanged. Schema 3 retains unit and client coverage.
- Offline static checks: passed on **397** historical, synthetic and functional/compatibility/production-client reports.
- Edge 156 browser review: **280 views** across eight synthetic reports, ten functional-client reports and four large historical reports; zero script errors, external requests or horizontal page overflow. Desktop, tablet and phone viewport emulation, filters, navigation, playback and resizing were exercised. The largest historical examples contain up to 10,000 events.

Remaining limits are documented in the [release notes](RELEASE_NOTES_0.2.1.md): Chromium-only browser evidence, emulated mobile input, unreviewed print output, transient selection loss across responsive breakpoints and one label escaping a long-match chart at extreme zoom. Existing HTML exports retain their old appearance until individually re-exported. These do not compromise the release's recording or offline-report contracts.

The sections below are historical assessments; their former publication restrictions and browser-tool limits do not describe this release.

## 0.1.6 local review status (historical)

Development jar: `build/libs/kwamster-0.1.6.jar`. The [full report](0.1.6.md) establishes the PVPHQ premature-finalization code path, preserves the Bo3tu/Bo3tw screenshot uncertainty, documents additional MCPvP failures and corrections, and defines canonical two-tick attack groups and independent combat exchanges. Automated build/unit/replay/history validation passes; muted client and compatibility evidence is in VALIDATION.md. Original owner data remains unchanged; local commit only.

Remaining review limits: new external-server acceptance with this jar, exact screenshot identity/target-score discrepancy, unvalidated server grammar and spear paths, opponent-side shield use with two players, the preserved CatPvP round-count discrepancy, interactive browser QA blocked by protocol policy, and long live-session frame/memory measurements. These limits do not authorize pushing, publishing or installing into Aurora.

**Historical 0.1.5 assessment:** development build for owner review; public release was not approved. See [0.1.5 evidence, validation and remaining gates](0.1.5.md). The sections below retain historical release assessments and do not describe the current capture policy.

The jar compiles against Minecraft 1.21.11 using Java 21 and official mappings. The unit suite (78 tests) and the actual Fabric client sequence pass. 0.1.1 replaces the HUD-oriented screens with a tabbed analytics dashboard and a local browser replay workflow. Nothing has been pushed, uploaded or published.

## Implemented and exercised

- Fabric foundation, configuration validation/persistence, corruption backups and schema-1 to schema-2 migration.
- Ordinary melee observation, source correlation, reach/aim geometry, critical-condition eligibility, sprint transitions and conservative jump candidates.
- Tabbed dashboard (K): Overview with headline statistics, a four-metric trend chart and recent fights; Combat Analytics in eight sections with histograms, a normalized hitbox map (impacts and density), readiness/cooldown distributions, interval, combo and momentum charts; Fight History with sorting, filtering, selection, on-demand details, export, confirmed deletion and View in Browser; Settings in five sections.
- Open Recordings Folder / Open Exports Folder through the system file manager (Windows verified by a real launch; Linux and macOS commands unit tested only).
- Self-contained offline browser report: Summary, Timeline (recorded-timestamp playback with speed, scrubbing and event stepping), Reach, Aim, Timing, Movement. Derived values are shared with the GUI.
- Bounded asynchronous recording, retention, schema handling, anonymized export by default.
- Mouse, keyboard, focus, dropdown, slider, color-selector and dialog interaction; layout checked at GUI scales 1-4 and 854x480.
- Persistent HUD retired from the default experience (dormant, not deleted); hit sounds retained.

## Remaining gates and limits

| Item | Current state | Required before broader claims |
| --- | --- | --- |
| Real multiplayer PvP | Automated integrated-server melee tests only; no manual two-player server session | Execute VALIDATION.md with two authorized accounts and server-side evidence. |
| Jump-reset effectiveness | Candidates, timing offsets and motion traces only; success deliberately unavailable. The automated test cannot produce player-sourced incoming damage, so jump-timing charts were exercised only by unit tests. | Controlled baseline measurements and a validated estimator, or retain unavailable labeling. |
| Server critical damage / knockback / wins | No invented critical multiplier, knockback verdict or win attribution | An authoritative compatible evidence source would be necessary. |
| Spear stab/charge and secondary victims | Separate vanilla attack paths are not captured | Weapon-specific capture and tests. |
| Manual GUI review | Screenshots captured and reviewed by the developer agent; no manual user review | Owner review of the dashboard and report at their usual resolution, GUI scale and resource packs. |
| Folder / browser opening on Linux and macOS | Commands unit tested; only Windows 11 launched for real | Run the -PrealOpen=true client test or a manual check on each platform. |
| Audio perception | Sound selection and preview code paths run while every test client is muted | An owner-selected audible session to assess sound balance. |
| Compatibility breadth | The 0.1.0 four-mod smoke test was not repeated for 0.1.1 | Repeat with Sodium, Lithium, FerriteCore, ImmediatelyFast and the owner's HUD/GUI mods. |
| Performance | Dashboard aggregate at the in-memory caps measured about 5 ms (computed on history change, not per frame); frame-time benchmark not re-run for 0.1.1 | Repeated representative live-match measurements on varied hardware. No zero-impact claim. |
| Hosted sharing | Local viewer and portable export only | Owner-provided infrastructure, access/privacy decisions and explicit deployment approval. |
| Public distribution | Local jar only | Owner review, license/distribution decisions and explicit release approval. |

Known technical limits: a source-attributed damage report confirms the reported source, while matching it to a specific pending attack is inferred. Missing, late or custom packets can under-count hits. Raw recording is bounded to 10,000 events per fight. Per-hit dashboard views use only timelines held in memory (up to 50,000 events); older fights contribute their summary counts and load fully only when opened. Graceful shutdown drains the writer; abrupt shutdown may lose the active or queued fight. Unknown/corrupt files are preserved outside the managed retention budget. Player opponents' names are stored locally in recordings as display labels and removed from anonymized reports. The browser report uses the platform default font stack unless a Minecraft-style monospace font is installed.

No manual gameplay validation, external-server trial, audible sound validation, manual user review, hosted report link or production performance guarantee is claimed.


## 0.1.3 status

**Working development build 0.1.3; public release is not approved.** The jar is `build/libs/kwamster-0.1.3.jar`. The unit suite, the Fabric client sequence and the four-mod compatibility sequence pass. Nothing was pushed or published, and the owner's Aurora instance was not modified.

| Item | State after 0.1.3 | Still required |
| --- | --- | --- |
| Outcome detection | Minemen (`.net`/`.club`) and PVPHQ formats from the owner's logs. 998 of 998 decided fixture matches classified correctly, all confirmed; 0.1.2 decided 0. | Live acceptance on those servers with the new build; formats for other servers; titles if a server uses them for results. |
| Incoming attribution | Server cause entity recorded and verified in the client test | Servers that rewrite cause entities |
| Shields | Local block verified with a real shield in the client test; opponent blocks verified in unit and simulated tests | A two-player session with an opponent blocking |
| Dominance / combos | Deterministic, bounded and documented; graphs agree with summaries in 17 scenario tests | Owner review of whether the weights read sensibly in real fights |
| Jump resets | Categories and signed ticks per attributed hit; success still unavailable | A validated knockback baseline before any effectiveness claim |
| Damage dealt | Unavailable (opponent health is not trusted) | An authoritative source |
| Historical data | 222 recordings decode, analyze and render; stored outcomes unchanged | — |
| Performance | No measurable frame-time difference in the gametest loop. Per-fight analytics cost more than in 0.1.2 and is computed on history change (PERFORMANCE.md). | Live long-session measurements |
| Manual multiplayer acceptance | Not performed in this assignment | Run VALIDATION.md with two authorized accounts |


## 0.1.5 owner-review status

Working local development jar: `build/libs/kwamster-0.1.5.jar`. Six explicit capture environments, generic capture only on unconfigured hosts, manual override/restoration, round metadata and diagnostic policy/version status are implemented. All 18 owner matches are investigated and replayed from captured announcements and original combat events; unit, functional, four-mod and historical export checks pass. See [the full validation report and matrix](0.1.5.md).

Remaining gates are owner review, new live testing of the 0.1.5 jar on the six servers, resolution of CatPvP #1's 3–1 versus observed 2–1 discrepancy, evidence for other modes, and interactive browser QA (blocked by tool protocol policy). Incomplete signals intentionally do not fall back to generic combat. Schema 1–4 source data and original logs/exports remain unchanged. No distribution, push, publication or Aurora installation is authorized or performed.
