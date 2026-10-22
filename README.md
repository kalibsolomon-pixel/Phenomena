# KWAMSTER

Standalone, client-side PvP analytics for **Minecraft Java 1.21.11**, Fabric, Java 21. Press **K** in a world to open the dashboard. The key can also be changed in Minecraft Controls.

KWAMSTER observes combat. It never changes reach, cooldowns, attacks, movement, knockback or network packets. No server installation is needed. Training against living entities is supported.

## Build and run

Use a Java 21 JDK. The development workspace has a checksum-verified local Temurin installation under the ignored .tools directory. In PowerShell:

~~~powershell
$env:JAVA_HOME = (Get-ChildItem .tools -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
./gradlew.bat build
./gradlew.bat runClient
./gradlew.bat runClientGameTest
~~~

On another machine install Java 21 and set JAVA_HOME, then use the checked-in Gradle wrapper. The wrapper verifies its distribution checksum. Build output: build/libs/kwamster-0.1.0.jar. Install this jar and Fabric API 0.141.6+1.21.11 into a Fabric 1.21.11 client. Sources and integration-test mods are not required for installation.

## Features

- Distinct aimed attacks, client ray contact, empty misses, source-attributed server melee damage events and uncertain outcomes.
- Eye-to-target geometry, normalized aim, observed critical conditions, sprint state, jump candidates and diagnostic movement traces.
- Fight lifecycle, conservative outcomes, combo interruption rules, timing consistency and estimated momentum.
- Charcoal custom screens, keyboard-accessible controls, local configuration, independent draggable HUD panels, scaling, alignment, opacity and visibility.
- Configurable readiness categories, category colors and resource sounds, including XP-orb pickup; throttled local chat and sound feedback.
- Atomic asynchronous local recordings, retention, history filters, details, deletion confirmation, anonymized export and self-contained HTML replay.

## Reading the numbers

Accuracy is **correlated hits / combat attempts**, including unresolved attempts in the denominator. Unanswered entity attacks are uncertain, not proven misses. A server damage event identifies a melee source, but reports no damage amount or critical multiplier. Critical conditions and reach remain client observations. Readiness is a transparent cooldown classification, not an objective skill or damage score. Jump-reset success is unavailable; only candidates and evidence are recorded. Opponent death does not prove a win.

Configuration: config/kwamster.json. Recordings: kwamster/fights. Exports: kwamster/exports. These paths are relative to the Minecraft game directory. Nothing is uploaded; export creates files only after a user action. Exported reports are anonymized by default. Hosted sharing awaits separately authorized infrastructure.

See [architecture](docs/ARCHITECTURE.md), [configuration](docs/CONFIGURATION.md), [measurement definitions](docs/METRICS.md), [acceptance procedures](docs/VALIDATION.md), [performance](docs/PERFORMANCE.md), and [release readiness](docs/RELEASE_READINESS.md).

PowerShell convenience wrapper: `./scripts/dev.ps1 build` or `./scripts/dev.ps1 runClientGameTest`. All development test clients stay muted.
