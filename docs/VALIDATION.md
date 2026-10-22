# Validation and reproducible acceptance

## Automated coverage

Run ./gradlew.bat build for deterministic JUnit checks. Run ./gradlew.bat runClientGameTest for the actual Fabric 1.21.11 client sequence. The test mod and frame probe are excluded from the production jar.

Unit coverage includes configuration round trips/corruption, geometry intersection and normalization, reach bounds, face-aware aim regions, critical-condition exclusions, source correlation, duplicate sequence rejection, expiry, uncertain outcomes, target isolation, fight transitions, retaliation/combo rules, jump candidates, snapshot isolation, atomic replacement, storage retention, schema rejection, UUID-restricted deletion, report escaping/anonymization, HUD anchors and raw-event recalculation.

The client test opens Overview and Settings, changes/restores tracking, resizes to 854×480, adjusts/resets HUD scale, creates a flat singleplayer world and attacks a stationary husk. It asserts exactly one attempt, one source-correlated server melee report and a reach sample, opens fight details, scrolls to Export, drains the writer and captures screenshots. This is automated gameplay validation; it is not a manual multiplayer trial.

## Manual multiplayer acceptance still required

Use two authorized accounts on a test server without KWAMSTER installed server-side. Record server version, mod/plugin list, latency, tick rate, weapon, armor and GUI scale. Do not upload recordings.

1. Idle swing/mining for 30 seconds: no fight or combat misses should appear.
2. Begin combat; perform spaced fully charged attacks, empty swings and obstructed/rejected attacks. Compare observed attempts and source reports with server logs where available. Rejected/unanswered attempts must remain uncertain.
3. Repeat with third-party melee, projectiles, environmental damage and nearby fights. They must not confirm the local player's outgoing melee attacks. A tracked opponent's retaliation must interrupt the combo.
4. Test falling/grounded/sprinting/climbing/water/mobility-restricted/riding states. Critical-condition labels must match client state and must never imply a verified critical multiplier.
5. Repeat at measured distances and different target poses while stationary and moving. Inspect rayContact, normalized coordinates, interpolation details and nearest-box fallback. Never treat a discrepancy as proof of illegitimate server reach.
6. Jump before/after incoming damage at known timings. Inspect ground transitions and 10 Hz motion traces. Only conservative post-damage candidates count; successful knockback reduction remains unavailable.
7. Test target death, local death, target switches, inactivity, dimension changes and disconnect. No unexplained victories; no duplicate finalization.
8. Change GUI scale and window resolution; drag, scale, align and hide each panel. Confirm no unintended attacks or movement while editing. Test keyboard focus and scrolling at narrow resolutions.
9. Exercise all four display modes. NONE must still record fights, with no HUD/chat feedback. Chat must remain local. Check enabled sound categories on a separate audible test session if desired; this development run is intentionally muted.
10. Restart the client, inspect saved history, compare raw events with summaries, export, replay and delete a fight. Abruptly terminate a separate disposable test instance while writes are pending; existing completed recordings must remain parseable.

Spear stabbing/charging, sweeping secondary victims, real PvP latency behavior, all common HUD-mod combinations and perceived audio balance need separate acceptance before a public release. The current capture validates the ordinary sword/axe/fist path. No manual gameplay or real-server validation is claimed in this development session.

## Reproduce performance

- ./gradlew.bat runClientGameTest -Pperformance=baseline disables KWAMSTER through Fabric Loader and runs the same test scene.
- ./gradlew.bat runClientGameTest -Pperformance=mod tests inactive, tracking, HUD and recording modes.
- ./gradlew.bat benchmarkAnalytics measures synthetic event/allocation and atomic-write costs.

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
