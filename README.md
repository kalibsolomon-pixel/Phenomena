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

On another machine install Java 21 and set JAVA_HOME, then use the checked-in Gradle wrapper. The wrapper verifies its distribution checksum. Build output: build/libs/kwamster-0.1.1.jar. Install this jar and Fabric API 0.141.6+1.21.11 into a Fabric 1.21.11 client. Sources and integration-test mods are not required for installation.

## The dashboard

One window with four persistent tabs. Keys 1-4 switch tabs; Escape or the dashboard key closes it. Tab, filter, section and scroll positions are remembered for the session.

| Tab | Contents |
| --- | --- |
| Overview | Accuracy, hits, attempts, average/maximum reach, critical- and sprint-hit rates, average hit interval, fight count and longest combo over the last *N* fights (default 20); a per-fight trend chart switchable between accuracy, reach, critical-hit rate and hit timing; the five most recent fights with a Browser shortcut. |
| Combat Analytics | Eight sections: Accuracy, Reach, Aim, Timing, Critical hits, Sprint hits, Jump resets, Combos. Scope: last 10, last 25 or all fights. Includes the reach histogram and progression, a normalized hitbox with individual impacts or aggregate density, readiness and cooldown distributions, interval trend, jump timing after incoming damage, combo progression and the estimated momentum timeline. |
| Fight History | Sortable, filterable list of local recordings (date, duration, opponent, outcome, hits, accuracy, average reach) with Details, View in Browser, Export and Delete (confirmed). **Open Recordings Folder** and **Open Exports Folder** open the real directories in the system file manager. Details load the recorded timeline on demand. |
| Settings | General, Controls, Combat feedback (hit sounds, Minecraft sound category, presets or custom sound IDs, volume, pitch, per-category colors, readiness thresholds), Recording & storage (retention, budget, anonymization, motion traces, folders) and Interface (accent color, density, reduced motion, chart markers and grid). |

Charts show gaps where a fight has no samples; missing values are shown as a dash, never as zero. Hover any card or chart point for its definition or value.

## Browser replay

**View in Browser** writes (or refreshes) `kwamster/exports/Fight vs <opponent> <date> <time>/index.html` and opens it in your default browser. Folders are named after the opponent's name and the local start time (`Fight vs Steve 2026-10-22 18-04-31`); with anonymization on (the default), or for an opponent without a name, the name is left out (`Fight 2026-10-22 18-04-31`). The page is self-contained and works offline: no hosted site, server, external scripts or network requests. Tabs: Summary, Timeline (play/pause, speed, scrubbing, previous/next event, annotations), Reach, Aim, Timing and Movement. Playback follows recorded event timestamps and annotates observations; it does not reconstruct the Minecraft world.

Derived numbers in the report are computed by the same Java analytics code as the dashboard and embedded in the page, so both surfaces agree.

## Reading the numbers

Accuracy is **correlated hits / combat attempts**, including unresolved attempts in the denominator. Unanswered entity attacks are uncertain, not proven misses; empty swings are the only confirmed misses. A server damage event identifies a melee source, but reports no damage amount or critical multiplier. Critical conditions and reach remain client observations. Readiness is a transparent cooldown classification, not an objective skill or damage score. Jump-reset success is unavailable; only candidates and timing evidence are shown. Momentum is an illustrative estimate. Opponent death does not prove a win.

## Files and privacy

| What | Location (relative to the Minecraft game directory) |
| --- | --- |
| Configuration | config/kwamster.json (schema 2; a schema-1 file is migrated and its original kept as kwamster.json.v1-backup) |
| Recordings | kwamster/fights/&lt;uuid&gt;.json |
| Reports and exports | kwamster/exports/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/index.html and report.json |

Nothing is uploaded. Reports are anonymized by default (opponent ID and name replaced by "Opponent"); this can be turned off in Settings > Recording & storage. Folder and browser actions only open these KWAMSTER-managed locations, resolved from the game directory and validated fight IDs.

## Persistent HUD

The persistent in-game HUD, fight-summary popup and movement indicator are retired from the default experience in 0.1.1 and no longer render. Their code remains dormant (config field `hudOverlay`, not exposed in the interface). Hit sounds remain available.

See [architecture](docs/ARCHITECTURE.md), [configuration](docs/CONFIGURATION.md), [measurement definitions](docs/METRICS.md), [acceptance procedures](docs/VALIDATION.md), [performance](docs/PERFORMANCE.md), and [release readiness](docs/RELEASE_READINESS.md).

PowerShell convenience wrapper: `./scripts/dev.ps1 build` or `./scripts/dev.ps1 runClientGameTest`. All development test clients stay muted.
