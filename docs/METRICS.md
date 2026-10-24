# Measurement definitions and confidence

| Metric | Confidence | Meaning and limitations |
| --- | --- | --- |
| Attack attempt | Observed | Local entity attack call, or actual empty swing during an active engagement. Does not prove server acceptance. |
| Ray contact | Observed | Attack-time ray intersects interpolated client targeting box within available interaction range. No damage proof. |
| Correlated hit | Confirmed source / inferred matching | Server damage event identifies local direct melee source and pending target within configured window. No health amount or critical multiplier. Under-counts delayed/nonstandard servers; rapid pending attempts can make timing attribution ambiguous. |
| Empty miss | Observed | Swing with no selected entity during a fight. Recent opponent geometry may be retained as a plausible target. Mining and unrelated idle swings are excluded. |
| Uncertain | Inferred outcome status | Entity attempt without matching report, including expired pending attempts. Never relabeled as a proven miss. |
| Accuracy | Inferred conservative ratio | Correlated hits / combat attempts, denominator includes uncertain attacks. Not an exact server hit rate. |
| Reach | Observed | Distance from interpolated player eye to ray intersection with interpolated target box plus vanilla pick radius; nearest box distance if ray does not intersect. Attack samples preserve rayContact to distinguish the two. This is client geometry, not a server reach verdict. |
| Aim | Observed | Three normalized box coordinates; absent intersection uses -1. High/low use vertical quartiles; edge/center use the horizontal coordinate on the contacted face. No inherent effectiveness claim. |
| Critical conditions | Observed | Cooldown > .9 plus falling, airborne, not climbing, not in water, not mobility restricted, not riding and not sprinting. Matches inspected 1.21.11 Player.canCriticalAttack logic; does not prove critical damage. Rate denominator is correlated hits. |
| Sprint hit | Observed | Sprint state on the correlated attack. Never presented as proof of extra knockback. |
| Jump candidate ("Jump candidates" in the GUI) | Inferred | Observed ground-to-air transition, upward velocity > .15 and held jump key, shortly after an opponent damage report. Only this conservative post-damage window is counted. Pre-damage jumps remain visible in the raw timeline. |
| Jump reset success | Unavailable | No validated counterfactual knockback baseline. No success percentage is invented. Motion traces at 10 Hz during fights support future controlled analysis. |
| Combo | Inferred | Consecutive correlated attacks against tracked opponent within combo timeout; incoming opponent damage resets it. Uncertain attacks do not extend it. |
| Momentum | Inferred | +.20 on a correlated hit; -.25 on incoming damage; clipped to [-1,1], exponentially decays with 3-second time constant. Illustrative recent exchange indicator, not win probability. |
| Hit intervals | Observed timestamps / inferred matching | Monotonic attack-time intervals for correlated hits; mean and population standard deviation. Client scheduling and network correlation affect samples. |
| Readiness | Inferred | Perfect >= configured perfect cooldown, Great >= configured great cooldown, Normal >= .5, otherwise Poor. Evaluated only after an eligible correlated hit for feedback. No center-aim or maximum-reach reward. |

Distance sampling accounts for client interpolation but cannot correct the server's position history, lag compensation, entity shape changes or server-specific rules. Minecraft 1.21.11 custom attack-range components affect the maximum ray length; piercing/jabbing spear attacks take a distinct vanilla path and are not currently captured. Ordinary sword/axe/fist melee is the validated path. These limitations must remain visible when comparing fights across servers.

Confirmation is deliberately limited to what a damage event says. It does not establish damage amount, critical damage, kill attribution or successful jump-reset mechanics. Local player death is a loss of that engagement, with opponent attribution unknown. Outcomes WIN and DRAW are reserved for future reliable evidence and are never guessed from disengagement.

## Derived views (0.1.1 dashboard and browser report)

Both surfaces compute these with the same `dev.kwamster.analytics` code and the labels in `MetricDefs`; the browser report embeds the precomputed values.

| View | Definition |
| --- | --- |
| Hit / miss split | An attack is a hit exactly when the engine recorded an evaluation for it. Other attempts are empty swings (no entity selected, or an empty swing retaining a recent opponent's geometry) or uncertain. |
| Estimated miss distance | Client distance on attempts without a correlated hit (uncertain attacks and empty swings near a recent opponent). Shown separately from hit distance and never pooled into average reach. |
| Reach distribution | 0.5 m bins from 0 to 6 m, last bin open-ended, with hit distance and estimated miss distance as separate series. |
| Aim map | Ray-contact attempts only. Horizontal = coordinate across the contacted vertical face (the same face rule as the placement category); vertical = feet (0) to head (1). Density view: 6 x 12 cells. |
| Readiness categories | Counts of the frozen per-hit evaluations (recorded thresholds). Cooldown distribution: attack strength at correlated hits in 0.1 bins (0.9-1.0 is the top bin). |
| Interval consistency | Mean, population standard deviation and coefficient of variation (SD / mean) of attack-time gaps between consecutive correlated hits, pooled across fights from per-fight sums. |
| Combo chains | A chain ends where the next hit's combo value does not increase. Average combo is the mean chain length, single hits included. |
| Momentum timeline | Replays the momentum model at evidence times for hits and incoming damage, decaying between changes. The value at each hit equals the recorded evaluation. Estimate only. |
| Jump timing | Offset from the most recent incoming opponent damage (up to 1 s) in 100 ms bins; candidates are those inside the frozen jump window. Jumps with no incoming damage in the preceding second are counted separately. Success remains unavailable. |
| Coverage | Counting metrics use every fight in scope. Per-hit views use only fights whose timelines are in memory; the dashboard states the count when they differ. |
| Trends | One value per fight, oldest to newest. A fight without samples for a metric is a gap, never zero. |


## 0.1.2 corrected definitions (supersede legacy readiness and millisecond jump scoring)

- **Attempt timing:** capture every melee attempt in an engagement, including empty swings near the opponent. Record item/slot/attribute-component identity, effective ATTACK_SPEED, vanilla getCurrentItemAttackStrengthDelay, observed client tick and attack-strength scale. Cooldown ticks are 20 / effective speed. The preceding attack's duration controls the next interval. Inclusive tolerance is one client tick by default, justified by 50 ms quantization and fractional cooldowns. Beyond two cooldowns, or after an observed switch, the next attack opens an ungraded sequence. Mid-sequence modifiers apply to the new attack's next window. Server-only mechanics are unavailable unless reflected in client attributes. These are attempts, independently of hit confirmation.
- **Perfect reach:** only uniquely correlated local melee-source damage events can trigger feedback/counts. Player attacks and mace-smash sources are eligible; projectile tridents are excluded. A valid, unobstructed attack ray must contact the interpolated client target bounding box inflated by pick radius. Reach is eye-to-entry-point Euclidean distance. Nearest-box fallback distances remain descriptive and cannot reward a perfect-reach sound. Default inclusive window is 2.94–3.06 blocks. Geometry is client-observed, never a server reach verdict.
- **Jump reset:** actual jumpFromGround calls from the local player in an appropriate grounded movement state, with a pressed jump key, once per key press. Held input cannot repeatedly score; prevented input remains JUMP_INPUT. Nearest damage is selected primarily by absolute client-tick distance, with monotonic nanoseconds breaking ties. Signed jump-minus-damage offsets preserve early negatives and late positives. Zero = ideal-timing candidate; within ±4 ticks = attempt candidate; outside = nonqualifying. No validated cancellation or effectiveness metric exists. Server velocity updates are recorded as unverified-cause diagnostics, not assumed successful resets.
- **Melee aim:** preserve raw normalized XYZ, eye position, box dimensions, ray contact and the derived region separately. The closest reachable box point changes with elevation and pose. Inset the nearest visible face by 15% to avoid fragile edge-only alignment, falling back to the closest point near the reach limit. Ideal/good/peripheral radii are 0.18/0.40/0.70 times the smallest box dimension; further contacts are poor. These thresholds are explicit client geometry heuristics, not measured damage benefits. Current contact checks block obstruction; a report cannot reconstruct occlusion elsewhere on the surface. Eye-level aiming is not universally optimal for elevated, crawling or differently sized targets. Top/bottom faces use X/Z projection in the browser. No projectile impact uses these rules.
- **Outcome confidence:** confirmed means an exact known system-message format names both current participants in an unambiguous round. One-name results and immediate same-participant rematches require endpoint evidence and remain inferred. Uncertain-origin evidence is retained locally but cannot resolve outcomes. Player/disguised chat and actionbar messages are excluded. Opponent death without a qualifying announcement stays Unknown. Local death is an observed loss, with attribution unavailable.


## 0.1.3 definitions (supersede the combo, momentum and accuracy rows above)

Every metric below is computed by `dev.kwamster.analytics` (or `combat.metrics.Combos`) from recorded events and frozen evaluations, so the GUI, the HTML report and the stored summary agree. The confidence labels mean:

- **Observed:** read directly from client state or a packet.
- **Confirmed source:** a server packet names the source.
- **Inferred:** a deterministic rule combines observations.
- **Estimated:** a plausible but unverified reading.
- **Unavailable:** the evidence does not exist. It is shown as a dash, never as zero.

### Results

| Metric | Definition and inputs | Formula / rule | Confidence | Limitations | Display |
| --- | --- | --- | --- | --- | --- |
| Result classification | One of: confirmed server-declared win or loss, inferred win or loss, draw, unknown, interrupted. Inputs are host-scoped announcements, kill messages, local death, opponent death or removal, disconnect and inactivity. | Ranked evidence: match result 5 > round result 4 > kill message 3 > generic text / local death 2 > third-party kill 1 > endpoint 0. A stronger candidate replaces a weaker one; contradictions of equal rank become Unknown. A match result finalizes at once. Anything else waits for the grace period (3 s, or 8 s after a round result). | Confirmed only for exact trusted adapter text naming both players in the current round: paired text, or one name inside a round whose start named the opponent. | Untested servers fall back to generic, inferred rules. Announcement text without round IDs cannot prove which round a very late message belongs to. | Result card; fight list labels such as "Win (confirmed)"; detail view with the establishing announcement and notes. |
| Evidence source / scope | Which observation established the result, and whether it covers a match, a round or a client engagement. | Stored in `Resolution` with the evidence-entry index or event sequence. | As recorded | Pre-0.1.3 recordings get a derived, read-only resolution marked `derived`. | Detail view; report Overview. |

### Incoming hits and attribution

| Metric | Definition and inputs | Formula / rule | Confidence | Limitations | Display |
| --- | --- | --- | --- | --- | --- |
| Attributed incoming hits | Server damage events against you whose cause entity is the tracked opponent (direct melee or indirect projectile). | `ClientboundDamageEventPacket.sourceCauseId` resolves to the opponent. Duplicates in one tick count once. | Confirmed source | Servers that hide or rewrite cause entities reduce attribution. The packet carries no damage amount. | Defense > Incoming hits; input to combos and dominance. |
| Unattributed damage | Incoming damage with no resolvable cause (fall, fire, environment), or from a third party. | Counted separately; never treated as retaliation. | Observed | | Defense. Makes opponent combos "estimated" and dominance "incomplete". |
| Health lost / per-hit damage | Decrease of your health plus absorption. Per hit: the drop within two ticks after an attributed incoming hit. | Sum of decreases. Per hit: health before minus the minimum within ticks 0–2. | Observed | Regeneration, absorption and other damage in the same ticks mix in. Damage dealt to the opponent is unavailable, because servers obfuscate opponent health. | Defense > Damage & knockback. |
| Knockback observed | A server velocity update for you within one tick of an attributed hit. | Horizontal magnitude, blocks/tick. | Observed | The cause of a velocity update is not reported; pairing is temporal. | Defense tables; jump timeline tooltip. |

### Combos and exchanges

| Metric | Definition and inputs | Formula / rule | Confidence | Limitations | Display |
| --- | --- | --- | --- | --- | --- |
| Combo | Two or more consecutive confirmed hits by one side, with no confirmed hit by the other in between. Your marks are hit evaluations at server evidence time; the opponent's marks are attributed incoming hits. | A streak ends on retaliation, a same-tick trade (neither side is extended), a gap longer than the combo timeout (default 3 s), or the end of the recording. | Inferred | Late or missing server reports can split or merge streaks. | Fight Flow > Combos timeline (×length on each band, selectable); tables. |
| Longest / average combo, combo count, hits in combos | From your streaks of length ≥ 2. | Max, mean, count, and sum of lengths. | Inferred | | Cards in Overview, Fight Flow and the report. |
| Longest opponent combo | The same rule applied to attributed incoming hits. | | Inferred. **Estimated** when some damage was unattributed; **unavailable** when nothing was attributed and some damage was unattributed. | | Fight Flow. |
| Longest unanswered streak | Most consecutive hits of yours before an attributed return hit, ignoring disengagement gaps. | Run length over marks; trades reset it. | Inferred | | Fight Flow. |
| Exchange advantage | An exchange is a run of marks with no gap longer than the combo timeout. | Your hits minus opponent hits, per exchange. | Inferred | | Fight Flow > Exchanges. |

### Encounter dominance

This measures combat advantage over time. It is not physical momentum and not a win probability.

| Item | Value |
| --- | --- |
| Inputs | Your confirmed hits +1.0 (+0.25 when continuing a combo). Attributed opponent hits −1.0 (with the same −0.25 combo bonus). Your attack confirmed blocked −0.25. You disabled the opponent's shield +0.5. Your shield blocked +0.25. Your shield disabled −0.5. Accuracy, aim, reach and critical conditions are deliberately not inputs. Damage is not weighted, because it is only observable for you. |
| Decay | Every input decays as exp(−Δt / 3 s). |
| Normalization | P = decayed positive mass, N = decayed negative mass, D = (P − N) / (P + N + 1), which lies in (−1, 1). The +1 prior bounds a single event at ±0.5. |
| States | M = P + N < 0.35: no evidence (not drawn). Decayed unattributed-damage mass U ≥ 0.5: incomplete attribution (dotted, neutral). D ≥ +0.2: you. D ≤ −0.2: opponent. Otherwise neutral. |
| Sampling | Every 0.05 s, or duration / 2000 for long fights. |
| Shares | Time in each state divided by active time (you + opponent + neutral). Inactive and incomplete periods are excluded and reported separately. |
| Longest advantage, largest swing, changes | Longest contiguous you/opponent period; largest change in D within 3 s; number of passes between you and the opponent (reset by no-evidence or incomplete periods). |
| Availability | Incomplete when incomplete time exceeds 25 % of evidence time, or unattributed damage exceeds 25 % of incoming. Insufficient when active time is under 1 s or there are fewer than 3 inputs. |
| Limitations | Missing server reports appear as missing evidence. A fight with no incoming damage reads as one-sided only because damage events against you are always observable; an unattributed or hidden-damage pattern disables that reading. |

### Shields

| Metric | Definition and inputs | Formula / rule | Confidence | Limitations | Display |
| --- | --- | --- | --- | --- | --- |
| Attacks into a raised shield | Your attempts while the target's client-visible state was blocking. | `LivingEntity.isBlocking()` at the attack. | Observed | The state can lag the server by network delay. | Defense > Shields. |
| Confirmed shield block | An attack into a blocking target, followed by the shield-block sound within 1.5 blocks of the target and no damage event inside the correlation window. In 1.21.11 a blocked hit sends no damage event (verified in bytecode and observed in the client test). | Consumes the pending attack and removes it from the accuracy denominator. | Confirmed source / inferred match | Another nearby blocker could produce the sound; matching is temporal. Damage prevented is never inferred. | Defense; accuracy note; timeline diamonds. |
| Estimated shield block | An attack into a raised, facing shield with neither a damage event nor a sound. | Facing: target view vector · direction to you > 0. | Estimated (stays uncertain) | | Defense. |
| Shield disable | The shield-break sound at the opponent after your attack. | Counts as both a block and a disable. | Confirmed source / inferred match | | Defense; dominance input. |
| Hits after a disable | Confirmed hits within 5 s (the vanilla axe disable) after a disable you caused. | | Confirmed source | | Defense. |
| Hits past a raised shield | Confirmed hits while the target showed a raised shield (from behind, or after a disable). | | Observed | | Defense. |
| Your shield blocks / disabled | Shield sounds at your position. | | Observed | The blocked attacker is not reported. | Defense. |

### Accuracy (redefined)

Accuracy = confirmed hits / (attempts − confirmed shield blocks). Uncertain and estimated-block attempts stay in the denominator. Empty swings are the only confirmed misses.

### Jump resets

| Metric | Definition | Confidence | Display |
| --- | --- | --- | --- |
| Category per attributed incoming hit | The nearest actual jump by client tick decides the category. Same tick: ideal-timing candidate. Within ±window (default 4): attempt candidate. Within 20 ticks: outside window. No jump: no jump. Water, climbing or riding at the hit: excluded. No ticks: unavailable. Signed offsets (jump − hit) are preserved. | Inferred | Defense > Jump resets: offset strip, shared tick timeline (report), and a table with movement state and knockback. |
| Reset success | Not measured. | Unavailable | The card states "Not measured". |
| Speed after knockback (experimental) | Mean horizontal speed in motion samples 2–8 ticks after an attributed hit with knockback, comparing candidates against no-jump and outside-window hits. Shown only with at least 3 samples per group. | Experimental, descriptive | Defense card. Confounded by attacker sprint, enchantments, terrain and input. |

### Reach and perfect spacing

| Metric | Definition | Formula | Confidence | Display |
| --- | --- | --- | --- | --- |
| Eligible hit | A confirmed hit whose attack ray contacted the target's client hitbox. | | Observed | |
| Reach in target window | Eligible hits within target ± tolerance (default 3.00 ± 0.06, inclusive). | within / eligible | Observed | Histogram: 0.05-block bins from 2 to 4 blocks, shaded window, target line, per-hit strip, weapon filter. |
| Reach consistency | Population SD of eligible reach. | | Observed | |
| Average deviation from target | Mean of \|reach − target\|. | | Observed | |
| Maximum reach, reach by weapon | Max; per item id: count, within, mean, SD and max. | | Observed | Table. |

Raw distances are preserved. Perfect-spacing feedback still requires a unique source-confirmed correlation. A value above the target is not evidence of cheating: interpolation, latency and per-weapon attack-range components differ.

### Weapon timing

Samples are attack attempts with a recorded effective cooldown, and Δ = interval − expected (in ticks). Openers, after a switch or a gap over two cooldowns, are resets and ungraded. **Below resolution** is new: when the expected cooldown is no longer than max(tolerance, 1 tick), fast and perfect cannot be separated, so the attempt is not graded. Each weapon is compared with its own cooldown; no unlabeled cross-weapon average is shown. Mean \|Δ\| and the SD of Δ measure consistency. Each attempt's outcome (hit, shield block, uncertain, empty) is kept for filtering. Cooldown readiness is mechanical, not a tactical judgement.

### Aim

Raw normalized contact coordinates are kept separately from the derived grade. New derived values:

- Contacted face: the coordinate nearest a box boundary.
- Distance from the region's ideal point, in blocks.
- Central share: hits graded ideal or good.
- Consistency: the SD of that distance.
- Breakdowns by weapon and by reach band (< 2.5, 2.5–3.0, ≥ 3.0 blocks).

Misses without contact have no coordinates and are not drawn. Projectiles are not graded.

### Critical hits

A server critical hit is a confirmed hit of yours with the server's critical-hit animation (`ClientboundAnimatePacket` action 4) on the opponent within one tick. Critical conditions remain a separate client observation. Neither reports the damage multiplier.
