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
