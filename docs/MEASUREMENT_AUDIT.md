# KWAMSTER 0.1.3 measurement audit

Starting point: 0.1.2, commit `977f1ffe5c76a39a7ac0a8af85dc53475ae10d91`, branch `codex/0.1.2-combat-corrections`, clean tree. Baseline suite: 158 JUnit tests, 0 failures, 1 skip (Windows symlink privilege).

The evidence is the owner's Aurora instance, read only: 222 recordings (52 schema 1 from 0.1.0/0.1.1, 170 schema 2 from 0.1.2) and native Minecraft logs, including the rotated October 22–23 logs written while 0.1.2 was installed. SHA-256 digests of every file under `kwamster/` and `logs/` were taken before the investigation and checked again at the end (`.local-diagnostics/v013`, ignored by Git). Player names below are replaced with `PlayerA` (local), `PlayerB` (opponent) and `PlayerC` (third party).

## 1. Outcomes: 0 of 170 0.1.2 recordings were classified as a win or loss

| 0.1.2 result | Count |
| --- | ---: |
| Interrupted | 115 |
| Unknown | 55 |
| Win / Loss | **0** |

Root causes, in order of impact:

1. **Host adapter mismatch (Minemen).** The paired-result adapter was registered for `minemen.net`. The owner plays on `minemen.club`. In the logs, all 132 `Match Results (Click to view) / Winner: … ▏ Loser: …` messages and 150 `PlayerB was killed by PlayerA!` messages fell through to generic rules that do not match them. Only `Round N started!` matched, and its generic rule split recordings into "New round boundary" fragments without a result (79 recordings).
2. **Minemen start format too narrow.** Only `Casual Match` blocks were recognized. Most observed starts are `<Mode> Duel` blocks (`Sword Duel`, `Bow Duel`, `Diamond Potion Duel`, `Diamond SMP Duel`, `Stick Fight Duel`, `Creeper Duel`, `Bridge Duel`): 103 of 155 start blocks.
3. **One-name results needed an endpoint that PVPHQ never produces.** `► Winner: PlayerA (7.9 ❤) [Click]` was parsed, then rejected 70 times (`pvphq-result`, accepted = false). 0.1.2 required a death or end marker before a one-name result could count. PVPHQ teleports players without a client-visible death, so the grace window never opened. The round had already been scoped by `► Opponent: PlayerB`, which identifies both participants.
4. **PVPHQ's paired evidence was ignored.** PVPHQ also sends `🗡 PlayerB has been killed by PlayerA.` (different wording and punctuation from Minemen), `🏆 MATCH REPORT 🏆` and a separate `► Loser: PlayerB [Click]` line. None of these were parsed.
5. **Best-of rounds lost their opponent.** `Round N started!` cleared the announced opponent, so later rounds of the same match could no longer bind or accept results. Minemen round results `PlayerA won! 1 - 0` were not recognized, and the round-versus-match distinction did not exist.
6. **Local death is not observable on these servers.** No local `DEATH` event appears in any of the 222 recordings. Duel servers respawn or teleport players before `isDeadOrDying()` is seen on a client tick. Losses therefore depend on server announcements, or on the low-frequency `ClientboundPlayerCombatKillPacket` where a server sends it.
7. **Early finalization discards late evidence.** An accepted result finalized the fight immediately. A following, more specific message (a round result followed by a match result in the same second) found no engaged fight and was dropped.

Evidence needed to fix: the exact host-scoped formats above, the order in which they arrive, the start-to-result binding, and a labeled fixture set built from these logs (Section 7).

## 2. Incoming hits: unattributed damage was treated as opponent retaliation

5,801 incoming damage events were recorded: 5,149 have the tracked opponent as the server-reported cause and 652 have no cause entity (fall, fire, environment, or a cause the client could not resolve). The engine and analytics counted every one of them as an opponent hit. **240 unattributed incoming events directly followed a local hit and reset the local combo**, and every one moved the momentum estimate toward the opponent.

Incoming events also kept no damage type, no direct-versus-indirect source flag, no health change and no knockback link, so melee hits could not be separated from projectiles.

## 3. Shields: blocked attacks were indistinguishable from uncertain attempts

Inspection of the 1.21.11 `LivingEntity.hurtServer` bytecode shows that when `applyItemBlocking` absorbs any damage, the server calls `BlocksAttacks.onBlocked` (block sound) **instead of** `broadcastDamageEvent`. An attack into a raised shield therefore never produces the damage event KWAMSTER uses as hit confirmation. A disable plays the shield break sound. 0.1.2 recorded neither the target's blocking state nor these sounds, so blocked attacks counted as "uncertain" and lowered accuracy.

## 4. Combos and momentum

- Combos were a timeout-based hit streak (3 s), broken by any incoming event, unattributed ones included (Section 2).
- No combo start or end times, durations, opponent combos or exchange structure were recorded.
- Momentum (+0.20 per hit, −0.25 per incoming event, 3 s decay) had no evidence-sufficiency concept. With incoming events missing or unattributed, it produced a confident curve anyway. It also drifted toward zero during inactivity without marking that period as "no evidence".

## 5. Measurement inventory at 0.1.2

| Metric | 0.1.2 label | Audit finding |
| --- | --- | --- |
| Attack attempt, empty swing | Observed | Sound. |
| Correlated hit | Confirmed source / inferred match | Sound. Shield-blocked attacks fell into "uncertain" (Section 3). |
| Accuracy | Inferred | The denominator included shield-blocked attacks. |
| Reach, aim coordinates | Observed | Sound. No window percentage, per-weapon breakdown or consistency measure was reported. |
| Perfect reach | Observed / confirmed hit | Sound; reward eligibility is preserved. |
| Critical conditions | Observed | Client conditions only. The server's critical-hit animation packet (`ClientboundAnimatePacket` action 4) was not used. |
| Weapon timing | Observed | Sound, but the GUI table mixed weapons and had no expected-versus-actual chart. |
| Jump-reset candidates | Inferred | Paired with every incoming event, unattributed ones included. No knockback, movement-state or sprint context was recorded. |
| Jump-reset success | Unavailable | Remains unavailable as a success rate (Section 6.6). |
| Combo | Inferred | Biased by unattributed incoming events. |
| Momentum | Inferred | Biased and overconfident (Section 4). |
| Outcome | Confirmed / inferred / observed | Systematically unresolved (Section 1). |
| Damage dealt | Not shown | Opponent health is commonly obfuscated by servers. Remains unavailable. |
| Damage received | Not recorded | The local health change is observable. Added in 0.1.3. |

## 6. 0.1.3 corrections derived from this audit

1. Host adapters for `minemen.net` and `minemen.club`, all observed `<Mode> Duel`/`Match` starts, Minemen round results (`won! a - b`), and PVPHQ's start banner, kill, match-report, winner and loser lines. Results are graded by scope: round versus match.
2. A one-name result counts as server-declared only inside a round whose start announcement named the opponent, after the opponent entity has bound to that name. Otherwise it stays inferred or rejected.
3. Accepted results hold the round in a short grace period, so a later, more specific announcement can upgrade it. Round to match, and kill to declared result, are upgrades; contradictions produce Unknown with a conflict recorded. Finalization happens exactly once.
4. An explicit result classification (confirmed win/loss, inferred win/loss, draw, unknown, interrupted) is stored separately from its source, confidence and establishing observation. Older recordings derive it read-only.
5. Incoming hits record damage type, direct source and attribution. Only attributed opponent melee hits break combos, move dominance or pair with jump resets.
6. Shield state at the attack, plus block and disable sounds, produce separate shield observations. Confirmed blocks require the target to be blocking, no damage event, and a block sound at the target. A blocking target without a sound is an estimated block. Blocked attacks leave the accuracy denominator, which is now defined explicitly.
7. Server critical-hit animations, local health changes, knockback velocity linked to incoming hits, and the local combat-kill packet are captured as low-frequency observations.
8. Combo, exchange and dominance models with an explicit evidence-sufficiency state. The dominance graph is drawn neutral where incoming attribution is unavailable.

## 7. Validation evidence plan

- An anonymized outcome fixture set extracted from these logs by `scripts/extract_outcome_fixtures.py`, read only, with names replaced and only announcement text and relative timing kept. It is replayed through the real adapters and engine, with precision and recall reported per class. Labels come from a separate consensus rule over the same server messages, so this measures the parser and lifecycle against the server's own statements. It is not independent ground truth and not real-world accuracy on untested servers.
- Synthetic edge cases: third-party kills, rematches, delayed and contradictory results, teleports, disconnects and multi-opponent fights.
