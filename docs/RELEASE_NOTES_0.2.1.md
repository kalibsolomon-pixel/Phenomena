# Phenomena 0.2.1

## Highlights

- New Spxctra-designed HTML fight reports, with a result-first header, clearer summaries and sticky navigation.
- Responsive charts preserve readable labels across desktop, tablet and mobile layouts.
- Measurement confidence and inline metric definitions make observed, inferred and unavailable data easier to distinguish.
- Improved round and exchange navigation, including timeline jumps, filters and playback controls.
- Reports remain self-contained, offline HTML files that can be opened directly from disk and shared without a hosted service.
- Preserves the PVPHQ export-notification correction: startup recovery does not replay old completion notices.

The native in-game interface and recording/report schemas are unchanged from 0.2.0.

## Phenomena 0.2.0 foundation

This is the first public Phenomena release after the project rename. The preceding 0.2.0 development milestone renamed KWAMSTER to Phenomena, introduced an independent native rendering architecture and Spxctra in-game UI, added non-destructive legacy data migration, and made `/phenomena` the primary command. Those foundations were implemented in 0.2.0; the HTML report redesign is new in 0.2.1.

## Compatibility

- Minecraft Java Edition **1.21.11**.
- Fabric Loader **0.19.5 or later**.
- **Java 21 or later**; production validation used Java 21.
- **Fabric API required**; validated with **0.141.6+1.21.11**. The mod metadata does not declare a minimum Fabric API version; use the validated version for this release.
- Standalone operation: **Aurora Client is not required**.

Compatibility smoke testing covers Sodium 0.8.14, Lithium 0.21.4, FerriteCore 8.2.0 and ImmediatelyFast 1.14.3 together on Minecraft 1.21.11. These optional mods are not bundled.

## Upgrading from KWAMSTER

1. Back up your existing game configuration and recordings.
2. Remove the old KWAMSTER mod jar and install `phenomena-0.2.1.jar`. Do not install both versions together.
3. Launch the same Minecraft game directory. Phenomena copies the legacy `kwamster/` data directory forward into `phenomena/` once, preserving the original files. Identical destination files are skipped; conflicts are preserved without overwriting. Symbolic links are not migrated.
4. When `config/phenomena.json` is absent, Phenomena reads the legacy `config/kwamster.json`; subsequent saves use the new configuration filename.

Use `/phenomena start`, `/phenomena end` and `/phenomena diagnostics`. `/kwm` remains a deprecated alias. Press **K** in a world to open the dashboard; a previously customized dashboard binding may need to be set again after the rename.

Historical recordings remain readable. Existing HTML reports retain their previous appearance until individually re-exported from History; this update does not regenerate them automatically.

## Known limitations

- Browser validation covers Chromium/Microsoft Edge. Firefox, Safari, physical phones and print output have not been validated. Mobile and tablet checks use emulated viewports.
- Resizing across a responsive breakpoint preserves filter values but clears selected combo details and aim-dot highlights.
- An extreme zoom setting can place one label outside the chart in a long multi-round report; the underlying values remain available through the report controls.
- Reports use a dark appearance. Combat measurements retain their documented confidence limits; missing data is shown as unavailable.
- Compatibility tests use controlled local clients and captured-evidence regressions; they do not establish universal mod compatibility or new live multiplayer-server acceptance.

## Installation

1. Install Fabric Loader for Minecraft **1.21.11** and use **Java 21**.
2. Put Fabric API **0.141.6+1.21.11** and the attached **`phenomena-0.2.1.jar`** in that profile's `mods` folder.
3. Start the Fabric profile, join a world and press **K**. Recording commands run locally and need no server permissions.

The attached `SHA256SUMS.txt` verifies the production jar:

```text
7164c15a00eee27b456aae8a40640d1cfd3044611ec6639db8f9b5c015e605ba  phenomena-0.2.1.jar
```
