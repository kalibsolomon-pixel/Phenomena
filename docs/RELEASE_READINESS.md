# Release readiness

**Status: working development build; public release is not approved or ready.**

The jar compiles against Minecraft 1.21.11 using Java 21 and official mappings. The unit suite and actual Fabric client sequence pass, including the four-mod compatibility smoke test. Local recording, history, export and browser event replay are operational. Screenshots and an anonymized singleplayer training report are checked in as evidence. Nothing has been pushed, uploaded or published.

## Implemented and exercised

- Fabric foundation, configuration validation/persistence and corruption backups.
- Ordinary melee observation, source correlation, reach/aim geometry, critical-condition eligibility, sprint transitions and conservative jump candidates.
- Custom screens, keyboard controls, GUI resizing, HUD scaling/position editing and four display modes.
- Fight lifecycle, conservative outcomes, summaries, cooldown readiness feedback, combos, timing and estimated momentum.
- Bounded asynchronous recording, schema handling, retention, history details, explicit deletion and anonymized export.
- Self-contained report viewer with reach distribution, readiness breakdown, combo annotations and timeline playback.

## Remaining gates and limits

| Item | Current state | Required before broader claims |
| --- | --- | --- |
| Real multiplayer PvP | Automated integrated-server melee test passes; no manual two-player server session | Execute VALIDATION.md with two authorized accounts and server-side evidence. |
| Jump-reset effectiveness | Candidates and motion traces only; success deliberately unavailable | Controlled baseline measurements and a validated estimator, or retain unavailable labeling. |
| Server critical damage / knockback / wins | No invented critical multiplier, knockback verdict or win attribution | An authoritative compatible evidence source would be necessary. |
| Spear stab/charge and secondary victims | Separate vanilla attack paths are not captured | Weapon-specific capture and tests; ordinary sword/axe/fist support must not be generalized to these paths. |
| Audio perception | Category selection/playback code is exercised through hits, while all test clients are muted | An owner-selected audible session to assess sound balance; duplicate eligibility is covered by the correlator tests. |
| Compatibility breadth | One simultaneous smoke test for the pinned Sodium/Lithium/FerriteCore/ImmediatelyFast versions | Longer play sessions and tests with the owner's additional HUD mods. |
| Performance | Synthetic frame/CPU/heap/GC comparison and separate analytics/storage microbenchmark | Repeated representative live-match measurements on varied hardware. No zero-impact claim. |
| Hosted sharing | Local viewer and portable export work; no hosted backend or public links | Owner-provided infrastructure, access/privacy decisions and explicit deployment approval. |
| Public distribution | Local jar only | Owner review, license/distribution decisions and explicit release approval. |

Known technical limits: a source-attributed damage report confirms the reported source, while matching it to a specific pending attack is inferred. Missing/late/custom packets can under-count hits. Raw recording is bounded to 10,000 events per fight, and reaching the cap interrupts that segment. Graceful shutdown drains the writer; abrupt shutdown may lose the active or queued fight. Unknown/corrupt files are preserved outside the managed retention budget. Older timelines load on demand to bound memory. Settings and sounds use Minecraft resource identifiers; resource packs or server combat changes can alter behavior.

No manual gameplay validation, external-server trial, audible sound validation, hosted report link or production performance guarantee is claimed.
