# KWAMSTER

Standalone, client-side PvP analytics for **Minecraft Java 1.21.11**, Fabric, Java 21. Press **K** in a world to open the KWAMSTER dashboard. The key is a normal Minecraft key mapping (Controls > KWAMSTER) and can also be rebound in the dashboard's Settings > Controls.

KWAMSTER observes combat. It never changes reach, cooldowns, attacks, movement, knockback or network packets. No server installation is needed. Training against living entities is supported. Tracking and recording run in the background whether or not the dashboard is open.

## Build and run

Use a Java 21 JDK. The development workspace has a checksum-verified local Temurin installation under the ignored .tools directory. In PowerShell:

~~~powershell
$env:JAVA_HOME = (Get-ChildItem .tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
./gradlew.bat build
./gradlew.bat runClient
./gradlew.bat runClientGameTest
~~~

On another machine install Java 21 and set JAVA_HOME, then use the checked-in Gradle wrapper. The wrapper verifies its distribution checksum. Build output: build/libs/kwamster-0.1.2.jar. Install this jar and Fabric API 0.141.6+1.21.11 into a Fabric 1.21.11 client. Sources and integration-test mods are not required for installation.

## The dashboard

One window with four persistent tabs. Keys 1-4 switch tabs; Escape or the dashboard key closes it. Tab, filter, section and scroll positions are remembered for the session.

| Tab | Contents |
| --- | --- |
| Overview | Accuracy, hits, attempts, average/maximum reach, critical- and sprint-hit rates, average hit interval, fight count and longest combo over the last *N* fights (default 20); a per-fight trend chart switchable between accuracy, reach, critical-hit rate and hit timing; the five most recent fights with a Browser shortcut. |
| Combat Analytics | Eight sections: Accuracy, Reach, Aim, Timing, Critical hits, Sprint hits, Jump resets, Combos. Scope: last 10, last 25 or all fights. Includes the reach histogram and progression, a normalized hitbox with individual impacts or aggregate density, weapon-specific attempt intervals and cooldown data, interval trend, signed tick timing around incoming damage, combo progression and the estimated momentum timeline. |
| Fight History | Sortable, filterable list of local recordings (date, duration, opponent, outcome, hits, accuracy, average reach) with Details, View in Browser, Export and Delete (confirmed). **Open Recordings Folder** and **Open Exports Folder** open the real directories in the system file manager. Details load the recorded timeline on demand. |
| Settings | General, Controls, Combat feedback (hit sounds, Minecraft sound category, presets or custom sound IDs, volume, pitch, perfect-reach window, sound styles and tick timing tolerances), Recording & storage (retention, budget, anonymization, motion traces, folders) and Interface (accent color, density, reduced motion, chart markers and grid). |

Charts show gaps where a fight has no samples; missing values are shown as a dash, never as zero. Hover any card or chart point for its definition or value.

## Multiplayer corrections in 0.1.2

Server adapters recognize observed PVPHQ and Minemen duel announcements, using the system packet channel, local identity, opponent and round context. Ordinary player/disguised chat is excluded. Paired participants can provide a confirmed server-declared result; one-name results and same-opponent rematches require independent endpoint evidence and remain inferred. Ambiguous or expired results never become confirmed wins/losses. Death and world changes allow a three-second result grace period; announced duels have a separate 60-second inactivity fallback.

Weapon timing uses attempted melee attacks and the held item's actual effective attack speed/cooldown, including modifiers. Openers are ungraded; one cooldown later is perfect within a configurable one-tick tolerance, earlier is fast, later through two cooldowns is slow. Switching weapons or exceeding two cooldowns resets the sequence. The special sound instead rewards source-correlated ray contacts at 3.00 ± 0.06 blocks, configurable independently of timing.

Actual jump actions are recorded once per key press. Signed client-tick offsets distinguish early and late candidates: same tick is ideal timing, ±4 ticks is an attempt candidate. These do not establish knockback cancellation. Melee aim uses nearest reachable hitbox surfaces and pose/elevation-aware geometry; browser reports show four labeled, color-coded regions per selected hit. Older recordings retain raw placement and show unavailable metrics where necessary.

See [implementation and validation](docs/0.1.2.md). The [synthetic report](docs/example-report-0.1.2/index.html) contains no multiplayer recordings.

## Browser replay

Every finalized fight, with recording enabled (default), automatically writes its JSON recording and a self-contained HTML report without opening the GUI. **View in Browser** reuses the same export folder and opens it. Exports live in `kwamster/exports/Fight <date> <time>/index.html` when anonymization is on (default), or `Fight vs <opponent> <date> <time>` when off. Local recordings always retain known opponent names independently of sharing privacy.

The page works offline: no hosted site, external scripts or network requests. Tabs: Summary, Timeline (play/pause, speed, scrubbing and annotations), Reach, Aim, Timing and Movement. Playback follows recorded event timestamps; it does not reconstruct the Minecraft world. Failed writes retry three times, then every 30 seconds while running, and missing or damaged exports recover on restart. Completed exports are checked by content digest and reused.

Derived numbers in the report are computed by the same Java analytics code as the dashboard and embedded in the page, so both surfaces agree.

## Reading the numbers

Accuracy is **correlated hits / combat attempts**, including unresolved attempts in the denominator. Unanswered entity attacks are uncertain, not proven misses; empty swings are the only confirmed misses. A server damage event identifies a melee source, but reports no damage amount or critical multiplier. Critical conditions and reach remain client observations. Attempt timing measures weapon intervals; cooldown strength remains a separate diagnostic. Perfect reach is a separate geometry metric. Jump-reset success is unavailable; only candidates and timing evidence are shown. Momentum is an illustrative estimate. Opponent death does not prove a win.

## Files and privacy

| What | Location (relative to the Minecraft game directory) |
| --- | --- |
| Configuration | config/kwamster.json (schema 2; a schema-1 file is migrated and its original kept as kwamster.json.v1-backup) |
| Recordings | kwamster/fights/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/&lt;uuid&gt;.json; legacy root UUID files also load |
| Reports and exports | kwamster/exports/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/index.html and report.json |

Automatic saves preserve existing files and do not prune recordings. Retention limits in-memory history; the legacy manual-save budget does not delete automatic recordings. Nothing is uploaded. Reports are anonymized by default (opponent ID and name replaced by "Opponent"); this can be turned off in Settings > Recording & storage. Folder and browser actions only open these KWAMSTER-managed locations, resolved from the game directory and validated fight IDs.

## Persistent HUD

The persistent in-game HUD, fight-summary popup and movement indicator are retired from the default experience in 0.1.1 and no longer render. Their code remains dormant (config field `hudOverlay`, not exposed in the interface). Hit sounds remain available.

See [architecture](docs/ARCHITECTURE.md), [configuration](docs/CONFIGURATION.md), [measurement definitions](docs/METRICS.md), [acceptance procedures](docs/VALIDATION.md), [performance](docs/PERFORMANCE.md), and [release readiness](docs/RELEASE_READINESS.md).

PowerShell convenience wrapper: `./scripts/dev.ps1 build` or `./scripts/dev.ps1 runClientGameTest`. All development test clients stay muted.
