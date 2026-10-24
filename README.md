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

On another machine install Java 21 and set JAVA_HOME, then use the checked-in Gradle wrapper. The wrapper verifies its distribution checksum. Build output: build/libs/kwamster-0.1.5.jar. Install this jar and Fabric API 0.141.6+1.21.11 into a Fabric 1.21.11 client. Sources and integration-test mods are not required for installation.

## Capture environments (0.1.5)

Automatic capture uses six observed server environments: `mcpvp.club`, `east.catpvp.net`, `minemen.club`, `pvphq.com`, `fishffa.gg` and `pvplegacy.net`, plus exact verified aliases. These start and end from their server signals; combat, death and inactivity never substitute for a missing match announcement. Round results stay inside one match recording. On unconfigured servers, qualifying combat starts capture and the owner’s inactivity setting ends it. Turning Automatic capture off disables both paths.

Overview shows the policy, environment/version, match/round state, diagnostics and export status. The inactivity setting applies only to generic combat capture on unconfigured servers. See [capture policy](docs/CAPTURE_POLICY.md), [six-server evidence and all 18 correlations](docs/0.1.5.md), and [server formats and limitations](docs/SERVER_COMPATIBILITY.md). These are captured-evidence regressions, not new live-server acceptance.

## Manual recording

Type **`/kwm start`**, fight for as long as you want, then **`/kwm end`**. These commands run locally without server permissions. End automatically saves a recording and an anonymous offline HTML report, with status in chat. Repeating Start or End does not create duplicate recordings. Overview also has Start/End and folder buttons; K is unchanged.

Manual sessions can contain several opponents, exchanges and rounds. Automatic announcements and inactivity do not stop the session. Starting during an automatic fight carries its observations into the manual session. Disconnect/world change/shutdown saves an Interrupted session; five-second atomic checkpoints recover after a crash without resuming on another server. The session's overall outcome stays Unknown unless interrupted; individual exchanges retain their own evidence/results. Browser reports filter by participant and exchange, with dominance kept separate for each exchange.

All six configured adapters have real-log and/or packet fixture coverage; individual formats and missing-signal limits remain explicit. Use `/kwm diagnostics on` while playing, `/kwm diagnostics off` afterwards, then `/kwm diagnostics export` for a local redacted fixture bundle. Capture is off by default and nothing is uploaded. Read the [server inventory and evidence procedure](docs/SERVER_COMPATIBILITY.md) and [0.1.4 lifecycle, privacy, recovery and validation notes](docs/0.1.4.md).

## The dashboard

One window with six persistent tabs. Keys 1–6 switch tabs; Escape or the dashboard key closes it. Tab, section, filter, focused-fight and scroll positions are remembered for the session. Each analysis tab has one row of sections and a scope selector (last 10, last 25 or all fights).

| Tab | Contents |
| --- | --- |
| Overview | The latest fight's result (with how it was established), duration, accuracy, reach in the target window, longest combo and advantage share, plus generated plain-language interpretations; recent-performance cards; a per-fight trend chart; the five most recent fights. |
| Offense | Accuracy (denominator excludes confirmed shield blocks), Reach & spacing (0.05-block histogram with the target window shaded, weapon filter, per-weapon table), Aim (measured contacts, derived grades, by weapon and reach band), Weapon timing (interval minus each weapon's cooldown, filters by weapon and result, resets marked), Critical hits (client conditions and server crit animations), Sprint hits. |
| Defense | Incoming hits split into attributed, unattributed and third-party damage; Shields (confirmed, estimated, disables, your own blocks); Jump resets (signed tick offsets per attributed hit, movement state, knockback; success is not measured); Damage & knockback. |
| Fight Flow | For a focused fight (Older / Newer): Combos timeline with selectable combos, the encounter dominance graph (above the centerline favors you, below the opponent, empty where there is no evidence, dotted where attribution is incomplete), and the exchange breakdown with turning points. Scope totals alongside. |
| History | Sortable, filterable list of local recordings with result labels such as "Win (confirmed)" or "Loss (inferred)". Details, View in Browser, Export and confirmed Delete. **Open Recordings Folder** and **Open Exports Folder** open the real directories. Details show the result source, the establishing announcement, notes, the dominance graph and the combo timeline. |
| Settings | General, Controls, Sounds, Measurement (fight detection, reach target and tolerance, timing tolerance, jump window), Recording & storage, Interface. |

Charts show gaps where there are no samples. Missing values are a dash, never zero. Hover any card or chart for its definition or value.

## Results and evidence

Recordings store classification, evidence source and confidence separately. Configured matches require a host-scoped match announcement identifying the local player or bound opponent; server-validated one-name winners do not require a death packet. Individual round outcomes and kill observations cannot finalize the whole match. Unknown and Interrupted preserve missing or abandoned evidence without manufacturing wins or losses. Generic combat results retain conservative inferred classification when authoritative server evidence is unavailable.

The owner's 18 confirmed outcomes (16 wins, 2 losses) were investigated against the specified Aurora logs and packet diagnostics. All 18 replay with one recording/report each. CatPvP match 1 has an unresolved round-count discrepancy: owner label 3–1, captured final score 2–1; its overall win is unambiguous. The older 1,200-fixture Minemen/PVPHQ corpus remains intact: 977 decisive eligible cases and 200 negative cases pass; 21 round-only cases explicitly remain unresolved under the new policy, and two historically ambiguous labels remain excluded. See [validation](docs/VALIDATION.md) for denominators and limits.

Incoming damage counts as an opponent hit only when the server names the opponent as its cause. Fall, fire and third-party damage are kept separately and never break your combos. Shield blocks are detected from the shield sound and the absence of a damage event; a blocked attack is neither a hit nor a miss. See the [measurement audit](docs/MEASUREMENT_AUDIT.md) and [0.1.3 notes](docs/0.1.3.md).

## Multiplayer corrections in 0.1.2

(Superseded where noted by 0.1.3.) Server adapters recognize observed PVPHQ and Minemen duel announcements, using the system packet channel, local identity, opponent and round context. Ordinary player/disguised chat is excluded. Paired participants can provide a confirmed server-declared result; one-name results and same-opponent rematches require independent endpoint evidence and remain inferred. Ambiguous or expired results never become confirmed wins/losses. Death and world changes allow a three-second result grace period; announced duels have a separate 60-second inactivity fallback.

Weapon timing uses attempted melee attacks and the held item's actual effective attack speed/cooldown, including modifiers. Openers are ungraded; one cooldown later is perfect within a configurable one-tick tolerance, earlier is fast, later through two cooldowns is slow. Switching weapons or exceeding two cooldowns resets the sequence. The special sound instead rewards source-correlated ray contacts at 3.00 ± 0.06 blocks, configurable independently of timing.

Actual jump actions are recorded once per key press. Signed client-tick offsets distinguish early and late candidates: same tick is ideal timing, ±4 ticks is an attempt candidate. These do not establish knockback cancellation. Melee aim uses nearest reachable hitbox surfaces and pose/elevation-aware geometry; browser reports show four labeled, color-coded regions per selected hit. Older recordings retain raw placement and show unavailable metrics where necessary.

See [implementation and validation](docs/0.1.2.md). The [synthetic report](docs/example-report-0.1.2/index.html) contains no multiplayer recordings.

## Browser replay

Every finalized automatic fight, with recording enabled (default), automatically writes its JSON recording and a self-contained HTML report without opening the GUI. **View in Browser** reuses the same export folder and opens it. Exports live in `kwamster/exports/Fight <date> <time>/index.html` when anonymization is on (default), or `Fight vs <opponent> <date> <time>` when off. Local recordings always retain known opponent names independently of sharing privacy.

The page works offline: no hosted site, external scripts or network requests. Tabs: Overview (result, evidence, interpretations), Offense (accuracy, reach histogram, aim faces, weapon timing), Defense (attribution, shields, tick-aligned jump timeline, damage and knockback), Fight Flow (clickable combo timeline, dominance graph, exchanges) and Timeline (play/pause, speed, scrubbing and annotations). Playback follows recorded event timestamps; it does not reconstruct the Minecraft world. Failed writes retry three times, then every 30 seconds while running, and missing or damaged exports recover on restart. Completed exports are checked by content digest and reused.

Derived numbers in the report are computed by the same Java analytics code as the dashboard and embedded in the page, so both surfaces agree.

## Reading the numbers

Accuracy is **confirmed hits / (attempts − confirmed shield blocks)**, including unresolved attempts in the denominator. Unanswered entity attacks are uncertain, not proven misses; empty swings are the only confirmed misses. A server damage event identifies a melee source, but reports no damage amount or critical multiplier. Critical conditions and reach remain client observations. Attempt timing measures weapon intervals; cooldown strength remains a separate diagnostic. Perfect reach is a separate geometry metric. Jump-reset success is unavailable; only candidates and timing evidence are shown. Encounter dominance is a bounded, decaying share of confirmed hits and shield events, shown neutral or empty where evidence is thin or unattributed; it is not physical momentum or a win probability. Opponent death does not prove a win. Every metric's definition, inputs, formula, confidence and limits are in [METRICS.md](docs/METRICS.md).

## Files and privacy

| What | Location (relative to the Minecraft game directory) |
| --- | --- |
| Configuration | config/kwamster.json (schema 2; a schema-1 file is migrated and its original kept as kwamster.json.v1-backup) |
| Manual checkpoints | kwamster/pending-sessions/&lt;uuid&gt;.json; recovered locally after interruption |
| Diagnostics (opt-in) | kwamster/diagnostics/&lt;capture-id&gt;.json and fixtures-&lt;timestamp&gt;.json |
| Server inventory | kwamster/server-compatibility.json |
| Recordings | kwamster/fights/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/&lt;uuid&gt;.json; legacy root UUID files also load |
| Reports and exports | kwamster/exports/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/index.html and report.json |

Automatic saves preserve existing files and do not prune recordings. Retention limits in-memory history; the legacy manual-save budget does not delete automatic recordings. Nothing is uploaded. Reports are anonymized by default (opponent ID and name replaced by "Opponent"); this can be turned off in Settings > Recording & storage. Folder and browser actions only open these KWAMSTER-managed locations, resolved from the game directory and validated fight IDs.

## Persistent HUD

The persistent in-game HUD, fight-summary popup and movement indicator are retired from the default experience in 0.1.1 and no longer render. Their code remains dormant (config field `hudOverlay`, not exposed in the interface). Hit sounds remain available.

See [architecture](docs/ARCHITECTURE.md), [configuration](docs/CONFIGURATION.md), [measurement definitions](docs/METRICS.md), [acceptance procedures](docs/VALIDATION.md), [performance](docs/PERFORMANCE.md), and [release readiness](docs/RELEASE_READINESS.md).

PowerShell convenience wrapper: `./scripts/dev.ps1 build` or `./scripts/dev.ps1 runClientGameTest`. All development test clients stay muted.
