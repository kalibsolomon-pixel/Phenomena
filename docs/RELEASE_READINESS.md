# Release readiness

**Status: working development build 0.1.3 (see the 0.1.3 section at the end); public release is not approved or ready.**

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
