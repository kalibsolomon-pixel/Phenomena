# Validation and reproducible acceptance

## 0.1.6 acceptance

See [the complete investigation](0.1.6.md) for source IDs, timestamps, diagnostic indices and uncertainty. Baseline 293 cases: 292 pass, one existing Windows symlink skip. Final 327: **326 pass, one skip, zero failures/errors**. Added deterministic tests cover inclusive tick windows/chains, targets/sources/weapons, delayed hit/shield upgrades, canonical denominator/feedback/combos/reach/timing, opening misses, noncombat exclusion, unresolved outcomes, independent timeout changes, breaks/dominance, history summaries, exact captured lifecycle timings, true disconnect, queued starts, underscore names and MCPvP completion variants. A 10,000-input manual scope regression checks deduplication and input preservation across its event-cap handoff. Existing manual tests now assert arena/dimension continuity; generic capture keeps its independent inactivity timeout.

Captured replays: 18/18 owner-confirmed prior matches (16 W, 2 L), and 26/26 latest windows (20 W, 5 L, one explicit end with missing result). Latest inputs comprise 20,665 actual available recorded events plus 60 explicitly transformed old level-heuristic observations. No lost combat was synthesized. Announcements are real; missing packet timing uses log seconds. Both layers produce one recording/report per window and check canonical hit graph totals against summaries. All 269 historical source files export read-only elsewhere without failure (schemas 1/2/4: 52/170/47); synthetic schema-3 coverage remains.

Muted Fabric 1.21.11 client runs `v016-functional01` through final source acceptance `v016-functional06` pass. The final run exits normally in 1m 32s. The sequence checks real same-tick vanilla attack calls, ordinary melee/source confirmation, a real local shield block, manual command/GUI controls, five-second recovery behavior, actual Nether/Overworld dimension replacement, real disconnect, automatic report generation and all six configured policy cases supplied locally. K, six tabs, GUI scales 1–4, 854×480 and compact layouts remain covered. Opponent shield attribution is unit/synthetic coverage, not a second-player client test. Selected GUI screenshots from `v016-functional03` are under `screenshots/0.1.6`; subsequent changes affect measurement boundary arithmetic, not their layout.

The full four-mod sequence passes in fresh `v016-compat01` through final source acceptance `v016-compat04`, with the existing SHA-512-verified Sodium 0.8.14, Lithium 0.21.4, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3 jars loaded together. The final run exits normally in 1m 38s. All clients keep master audio at zero. No Aurora modification or new dependency version was required.

Static validation of **354 reports** passes syntax, embedded outcome/round data, canonical count/classification identities, hit graph totals, timing sample identity, interval sums, offline CSP and absence of network calls/resources. These include 269 historical exports, 44 replay predictions, three synthetic examples (including two combat exchanges), and 38 reports from the final functional/compatibility clients, including their nested six-adapter capture-acceptance exports. The final artifacts share one current template script, which passes syntax checks; 86 reports contain rounds and nine contain manual sessions. The browser tool explicitly rejected the local file URL; no workaround was attempted. Interactive browser filters/graphs/playback, responsive layout, console errors and runtime network requests remain unverified. The static checker deliberately reports only static acceptance.

Reproduce ordinary checks with `./scripts/dev.ps1 build`, `./scripts/dev.ps1 generateExchangeExampleReport`, `./scripts/dev.ps1 benchmarkCombatExchanges`, and `./scripts/dev.ps1 runClientGameTest -PvalidationRun=NEW_UNIQUE_DIRECTORY`. The private replay input/output overrides and historical export run are saved in ignored `.local-diagnostics/0.1.6/validation.gradle`; fixtures use read-only originals and fresh output paths. Do not run clean tasks. See PERFORMANCE.md for measurements and limits; see the preservation receipt for original hashes.

## Automated coverage

Run ./gradlew.bat build for the deterministic JUnit checks: 78 tests, with one skipped where Windows denies symbolic-link creation without elevated privilege. Run ./gradlew.bat runClientGameTest for the actual Fabric 1.21.11 client sequence. The test mod and frame probe are excluded from the production jar.

Unit coverage includes:

- Configuration round trips, corruption, schema-1 migration (every display mode), and unknown schema or enum values.
- Geometry, aim normalization and critical-condition exclusions.
- Correlation, duplicates, expiry, uncertain outcomes, fight transitions, combo rules and jump candidates.
- Snapshot isolation, atomic replacement and retention.
- Corrupted, truncated and future recordings; UUID-restricted deletion and loading; pre-0.1.1 recordings without the new fields; raw-event recalculation.

New in 0.1.1:

- Derived analytics checked against the engine's own statistics: hit/miss split, readiness, reach bins, placement, intervals, combo chains, jump timing, and momentum equal to the recorded evaluations.
- Pooled aggregates and NaN for missing data; history filtering and sorting.
- Tab state, per-section scroll preservation, detail navigation and deletion.
- Layout arithmetic across scaled widths 300-3840: frame, tabs, chip wrapping, columns, scrollbar and reveal.
- Safe folder resolution (path traversal, non-UUID names, file in place of a folder, symbolic links), per-OS open commands and opener failure reporting.
- Report payload values, script-breakout escaping (including U+2028), anonymization, motion omission, and the viewer's offline and no-network constraints.

The client test runs muted. It opens the empty dashboard, creates a flat singleplayer world with a stationary high-health husk, and checks that K opens and closes the dashboard.

It then generates four real engagements through vanilla input and packet paths:

- left clicks at varied aim and cooldown;
- empty swings at the air;
- a follow-up attack inside invulnerability frames, which gets no server report and so stays uncertain;
- sprinting attacks, and attacks while falling after a jump.

It asserts correlated hits, empty swings, uncertain attempts, sprint hits, critical conditions, reach samples, the opponent label and the written recordings, and that tracking continues while the dashboard is open.

It then drives the GUI with real cursor clicks and key presses:

- trend metric switching, every analytics section, the aim density view, and scroll restoration across tabs;
- the outcome-filter dropdown: keyboard selection, Escape closing only the dropdown, and a filtered-out selection being cleared;
- Open Recordings Folder and Open Exports Folder, checked against the exact managed paths;
- Details, and View in Browser (report generated, anonymized and requested to open);
- delete with confirmation: Escape cancels, confirming removes the file and the history entry;
- a settings toggle persisted to config/kwamster.json, Open Minecraft Controls and return, and every settings section;
- a layout check of every widget against the window and viewport, for all tabs at GUI scales 1-4, at 854x480 and in compact density.

The run captures 27 screenshots. With -PrealOpen=true the same sequence launches the real file manager and browser; on this Windows 11 machine, File Explorer opened the fights and exports folders and the default browser opened the report.

A report generated by that run was rendered headlessly, one capture per tab, and exercised in a browser pane:

- 4x playback advanced along the recorded timestamps;
- next-event and scrubbing updated the annotation and the event table;
- no console errors occurred, and the only request was the page itself;
- no tab scrolled horizontally at 375 px.

This is automated validation. No manual play session was performed and no manual user review is claimed.

Screenshots are in docs/screenshots/0.1.1: gui-*.png from the client test at 1600x900 (GUI scale 3) and 854x480, and browser-*.png for the six report tabs. The anonymized example report from the automated test fight is in docs/example-report.

## Manual multiplayer acceptance still required

Use two authorized accounts on a test server without KWAMSTER installed server-side. Record server version, mod/plugin list, latency, tick rate, weapon, armor and GUI scale. Do not upload recordings.

1. Idle swing/mining for 30 seconds: no fight or combat misses should appear.
2. Begin combat; perform spaced fully charged attacks, empty swings and obstructed/rejected attacks. Compare observed attempts and source reports with server logs where available. Rejected/unanswered attempts must remain uncertain.
3. Repeat with third-party melee, projectiles, environmental damage and nearby fights. They must not confirm the local player's outgoing melee attacks. A tracked opponent's retaliation must interrupt the combo.
4. Test falling/grounded/sprinting/climbing/water/mobility-restricted/riding states. Critical-condition labels must match client state and must never imply a verified critical multiplier.
5. Repeat at measured distances and different target poses while stationary and moving. Inspect rayContact, normalized coordinates, interpolation details and nearest-box fallback. Never treat a discrepancy as proof of illegitimate server reach.
6. Jump before/after incoming damage at known timings. Inspect ground transitions and 10 Hz motion traces. Only conservative post-damage candidates count; successful knockback reduction remains unavailable.
7. Test target death, local death, target switches, inactivity, dimension changes and disconnect. No unexplained victories; no duplicate finalization.
8. Change GUI scale and window resolution. Navigate every dashboard tab and section using only the mouse, then only the keyboard. Confirm no unintended attacks or movement while the dashboard is open, and that the dashboard key and Escape close it.
9. With the dashboard closed, fights must still record and no HUD may render. Toggle the fight-summary chat line; chat must remain local. Check sound presets, categories and preview in a separate audible session if desired; this development run is intentionally muted.
10. Restart the client, inspect saved history and compare raw events with summaries. Use Open Recordings Folder, Open Exports Folder and View in Browser with your default browser, then export and delete a fight. Abruptly terminate a separate disposable test instance while writes are pending; existing completed recordings must remain parseable.

Spear stabbing/charging, sweeping secondary victims, real PvP latency behavior, all common HUD-mod combinations and perceived audio balance need separate acceptance before a public release. The current capture validates the ordinary sword/axe/fist path. No manual gameplay or real-server validation is claimed in this development session.

## Reproduce performance

- ./gradlew.bat runClientGameTest -Pperformance=baseline disables KWAMSTER through Fabric Loader and runs the same test scene.
- ./gradlew.bat runClientGameTest -Pperformance=mod tests the inactive, tracking, dormant-HUD and recording modes.
- ./gradlew.bat benchmarkAnalytics measures synthetic event processing and allocation, atomic writes, dashboard aggregation and report export.

Raw results are written to build/performance. The benchmark uses a test-only runTick probe and every eighth eligible frame across each 12-second workload, after warmup. It is not a normal interactive FPS benchmark; see PERFORMANCE.md for results and limits.

## Recorded compatibility smoke test

The full client acceptance sequence also passed with all four of these Fabric 1.21.11 builds loaded simultaneously:

| Mod | Version | Distribution metadata |
| --- | --- | --- |
| Sodium | 0.8.14+mc1.21.11 | https://modrinth.com/mod/sodium/version/rkdTcxoT |
| Lithium | 0.21.4+mc1.21.11 | https://modrinth.com/mod/lithium/version/Ow7wA0kG |
| FerriteCore | 8.2.0 | https://modrinth.com/mod/ferrite-core/version/Ii0gP3D8 |
| ImmediatelyFast | 1.14.3+1.21.11 | https://modrinth.com/mod/immediatelyfast/version/4EwhsTu7 |

Downloaded jars were verified against their SHA-512 metadata. They remain only in ignored development/test folders and are not bundled with KWAMSTER. Sodium reported its AMD driver workaround; the smoke test and screenshots still completed. This does not establish compatibility with every setting, resource pack or long-running workload.


## 0.1.2 final acceptance

The final build passed 158 JUnit cases: 157 passed, one existing Windows symlink-privilege skip, zero failures/errors. Final muted Fabric client acceptance also passed with the four existing checksum-verified compatibility mods simultaneously loaded. The additional sequence exercises actual sword/axe/trident/fist/item/mace attributes, a changed attack-speed attribute, mace-smash confirmation, automatic report availability before GUI use, and an integrated-server system result with inferred confidence. Pure tests additionally cover named-start forfeits and identity binding, conservative same-opponent rematches, delayed/unknown/player-chat results, queue pressure, transient/permanent export failures and partial/damaged export recovery.

The final combined command was:

```powershell
./scripts/dev.ps1 build generateExampleReport runClientGameTest -PvalidationRun=v012-final-compat
```

The run directory was new and included the four compatibility jars in its mods folder. It captured 27 GUI screenshots; selected images and a synthetic, anonymized report are in docs/screenshots/0.1.2 and docs/example-report-0.1.2. Previous run directories remain intact. The test harness disables Fabric's packet synchronizer because its low-level packet-observation guard explicitly requires it; the first exploratory run failed on that restriction and subsequent runs passed. No manual multiplayer acceptance or audible sound acceptance is claimed. Browser visual acceptance remains unverified because the browser policy blocked the local file URL. The final generated JavaScript passes Node syntax checking.

See 0.1.2.md for the evidence investigation, changed definitions, preservation guarantees, paths and remaining limits.


## 0.1.3 validation

### A. Unit tests

`./scripts/dev.ps1 build` runs 224 JUnit cases: 223 passed, 1 skipped (the existing Windows symbolic-link privilege test), 0 failures or errors. The baseline at 0.1.2 was 158 tests with the same single skip. New suites:

| Suite | Coverage |
| --- | --- |
| `OutcomeLifecycleTest` (23) | Minemen `.club` host; every observed start-block mode; paired, one-name and round results; PVPHQ best-of rounds and match report; round-score checks; kill messages inferred then upgraded; third-party kills; contradictions; delayed results after the next start; deferred round starts; disconnect then result; countdown forfeits; spectator suffixes; teleports and titles; local and opponent death; exactly-once finalization; external finish keeping evidence; opponent removal; legacy derivation; draws |
| `CombatIntelligenceTest` (22) | Unattributed and third-party damage; four-hit combo and opponent combo; same-tick trades; disengagement; duplicate observations; dominance shift, decay, prior bound and incomplete attribution; a brute-force check of the largest swing; confirmed, estimated and bypassed shield blocks; axe disable and follow-ups; local shield events; server crit correlation; jump categories and signed offsets; damage and knockback pairing; unexplained health drops; the experimental jump effect; reach window and per-weapon summary; below-resolution timing; aim face and distance; legacy availability |
| `SimulatedFightsTest` (17) | The 15 planned scenarios plus the example report and escaping. Each exports a real report and checks that the embedded graph data agrees with analytics and the stored summary |
| `OutcomeFixtureTest` | Precision and recall on 1,198 anonymized log fixtures (below) |
| `AnnouncementsTest`, `InterfaceTest` | Rematch rules updated to the evidence-based rule; six-tab information architecture and focus state |

### B. Labeled outcome fixtures

`scripts/extract_outcome_fixtures.py` (read-only, anonymized; labels from an independent consensus rule) produced 1,200 match fixtures from the owner's Minemen and PVPHQ logs. Two ambiguous segmentation groups were excluded.

| | WIN (826 labeled) | LOSS (172 labeled) | No decisive statement (200) |
| --- | --- | --- | --- |
| 0.1.2 engine, same harness | 0 tp, 0 fp; recall 0.000 | 0 tp, 0 fp; recall 0.000 | 200 undecided |
| 0.1.3 engine | 826 tp, 0 fp; precision 1.000, recall 1.000 | 172 tp, 0 fp; precision 1.000, recall 1.000 | 200 undecided |

All 998 decided matches are confirmed server-declared results. This measures parsing and lifecycle against the servers' own statements on two servers, with simulated melee. It is not independent ground truth or real-world accuracy elsewhere.

### C. Minecraft development client

`./scripts/dev.ps1 runClientGameTest -PvalidationRun=v013-final` (muted, new run directory) passed. Beyond the 0.1.2 sequence it checks:

- **Server crits.** The server critical-hit animation is correlated with confirmed hits.
- **Schema.** New recordings use schema 3.
- **Incoming damage.** Real `/damage … by` husk hits are attributed to the opponent, with knockback velocity and health drop linked. Fall damage stays unattributed.
- **Jump pairing.** A jump just before a hit is paired on the tick axis.
- **Shield block.** A real off-hand shield block produces no damage event and a block sound, recorded as a local block.
- **Navigation.** Every Offense, Defense and Fight Flow section is visited, including fight focus and combo selection. Layout is checked at GUI scales 1–4, at 854×480 and in compact density.

It captured 37 screenshots; a selection is in `docs/screenshots/0.1.3`. The test client cannot raise a shield on a mob opponent, so opponent-side blocks are validated by unit and simulated tests only.

Driving held input during these tests: Fabric's test input did not reach `keyUse`/`keyJump` while the window was ungrabbed. The test holds those key mappings directly and starts the off-hand use through `MultiPlayerGameMode.useItem`, the vanilla path a right-click takes.

### D. Compatibility

The same full sequence passed with Sodium 0.8.14+mc1.21.11, Lithium 0.21.4+mc1.21.11, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3+1.21.11 loaded together (`v013-compat`, then `v013-final-compat`). The jars are the existing ones; their SHA-512 matched the Modrinth metadata. The new sound, animate, combat-kill and title injections caused no mixin conflicts.

### E. Browser reports

- **Example report.** The synthetic report (`docs/example-report-0.1.3`) was served from `127.0.0.1` in the in-app browser pane. Every tab was checked: combo selection, the dominance graph, shield and jump timelines, reach histogram and filters, aim face filters, the timing scatter and filters, and playback. There were no console errors, and the only network request was the page itself. At 375 px, no tab scrolls horizontally.
- **Historical recordings.** All 222 of the owner's recordings were exported read-only and anonymized into the session scratchpad (0 failures; 52 schema 1, 170 schema 2) and loaded tab by tab in a harness: 1,110 tab checks, 0 script errors, 0 empty panels, 0 horizontal overflow.
- **Syntax and escaping.** The generated JavaScript passes `node --check`. Escaping and the offline constraints are covered by the existing report tests.

### F. Performance

See PERFORMANCE.md (0.1.3 section).


## 0.1.4 manual sessions and server evidence

Starting SHA `8864f172a1c2ca4bc6557bea1fa2496c28b483d0`, clean branch `codex/0.1.2-combat-corrections`. Baseline tests were rerun: 224 total, 223 passing, one existing Windows symlink skip. Final build: 253 total, 252 passing, one skip, zero failures/errors. This includes the full 0.1.3 real-log fixture suite and 29 additional unit cases for local command dispatch, manual lifecycle/promotion/priority, participants, delayed/nested results, context transitions, persistence/recovery/idempotence, naming/privacy, diagnostics and host boundaries.

Muted Fabric 1.21.11 full functional acceptance passed in `v014-functional01`. It exercises the normal `/kwm start` and `/kwm end` client path, real melee and source confirmation, automatic recording/report creation even when automatic tracking/saving preferences are off, duplicate commands, shared Overview controls and actual disconnect preservation. A test-only server command-packet mixin verifies zero `kwm` commands received by the server. The existing K/navigation, measurement and automatic-export acceptance remains included. No external multiplayer acceptance is claimed.

Four-mod smoke acceptance passed in isolated `v014-final-compat` and `v014-final-compat02`: Sodium 0.8.14+mc1.21.11, Lithium 0.21.4+mc1.21.11, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3+1.21.11. Existing jar SHA-512 values were rechecked against the saved vendor metadata and all matched. An earlier exploratory compatibility run overlapped development recompilation and failed to load a transiently unavailable class; the non-overlapping rerun passed. Its evidence was preserved.

Synthetic manual report generation uses `generateManualExampleReport`; the parent includes two participants, two independent exchanges and 18 confirmed hits. The embedded scopes have 13 and 5 hits respectively, both with their own available dominance curves; the parent has none. Anonymous copies retain distinct aliases and no original test names/host. A read-only export check also decoded/analyzed/exported the three checked-in schema-1/2/3 example recordings without failure into `build/validation/v014-history-exports`.

The browser protocol gate rejected the local `file:` URL. No workaround or security bypass was attempted. Generated JavaScript passes `node --check`; unit/static checks cover escaping, CSP, offline data, filtering payloads and absence of network calls. Browser execution, tabs/filter interaction, responsive layout, network observation and console-error acceptance remain unverified for 0.1.4. The Minecraft manual-recording screenshot was inspected. Do not inherit 0.1.3's browser acceptance as proof for the changed viewer.

All runs use fresh labeled directories; no pre-existing recordings, logs, screenshots, ignored artifacts or local developer files were deleted. No clean, push, publication, upload or installation into Aurora occurred. See [0.1.4](0.1.4.md), [server evidence procedure](SERVER_COMPATIBILITY.md), and [performance](PERFORMANCE.md).


## 0.1.5 six-server capture validation

Current build and complete evidence are in [0.1.5](0.1.5.md), including all 18 owner correlations, denominators, the preserved CatPvP round-count conflict and browser limitations. Final unit suite: 293 total, 292 passed, one existing Windows symlink skip, zero failures/errors. All 18 announcement fixtures and all 18 private replays of 21,748 original combat events pass. Each fixture produces one recording and one offline report. The unchanged legacy corpus validates 977 decisive eligible cases plus 200 negatives, explicitly abstains on 21 round-only cases, and excludes two historically ambiguous labels.

Fresh muted functional acceptance (`v015-review-functional`) and the full four-mod sequence (`v015-review-compat`) pass, including locally supplied configured-environment scenarios and nine isolated recording/report pairs. Ordinary integrated-server melee and real manual/disconnect/GUI paths are also exercised. No new external-server acceptance is claimed. Historical validation exported all 243 originals (52 schema 1, 170 schema 2, 21 schema 4), with zero failures; schema 3 is covered by unit tests. Interactive browser access is blocked by protocol policy; static syntax/data/offline checks pass for 253 reports. No browser workaround was attempted.

Reproduce from the repository root (PowerShell, Java 21):

```powershell
./scripts/dev.ps1 build
./scripts/dev.ps1 benchmarkCapture
./scripts/dev.ps1 runClientGameTest -PvalidationRun=NEW_UNIQUE_DIRECTORY
```

Private evidence preparation (read-only source; first preserve source inventory/hashes): run `scripts/investigate_six_servers.py INSTANCE`, then `scripts/extract_six_server_fixtures.py`, then `./scripts/dev.ps1 replayCapturedEvidence`. The extractor is explicitly for the October 24 corpus with reviewed log boundaries; it does not infer future-session ground truth. Raw outputs stay ignored. `scripts/check_offline_reports.py FOLDER...` checks syntax and embedded contracts, not browser interaction.
