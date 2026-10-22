# Configuration

Press K in a world. Overview, History, Settings and HUD editor are the main navigation controls. Mouse wheel or Page Up/Down scrolls long panels. Standard Tab, arrow keys, Enter and Escape navigation comes from vanilla widgets. The menu key is available both in Settings and vanilla Controls.

Settings save asynchronously when a screen closes or changes and on graceful shutdown. Invalid config files are backed up as config/kwamster.json.invalid-TIMESTAMP. New settings use schemaVersion 1; unknown schema versions are preserved before defaults load.

| Control | Default / limits | Effect |
| --- | --- | --- |
| Tracking | On | Observes combat independently of display. Turning it off ends the current fight as interrupted. |
| Display | HUD | HUD, CHAT, BOTH or NONE; chat messages are added locally and never sent to the server. NONE also hides summary/feedback overlays. |
| Recording | On | Queues finalized fight snapshots to local storage. |
| Per-hit chat | Off | Local readiness messages, limited to one per 500 ms. |
| Summary | On / 6 seconds | Fight-end panel, position/scale/opacity configured in HUD editor; optional short fade. |
| Inactivity | 8 seconds / 2–120 | Ends engagement as UNKNOWN; ENDING begins at 75% of the interval. The GUI slider exposes 2–30 seconds. |
| Correlation | 250 ms / 50–500 | Match source-attributed damage to recent attacks. Higher values can increase temporal ambiguity. |
| Combo timeout | 3 seconds / .5–10 | Consecutive correlated attacks beyond this gap begin a new combo. |
| Jump window | 150 ms / 50–300 | Conservative candidate window after incoming opponent damage. |
| Perfect / Great readiness | .95 / .85 | Cooldown thresholds. Normal starts at .5. No reach/aim weighting is applied. |
| Category feedback | XP-orb for Perfect | Per-category RGB, sound resource ID, volume, pitch, audio and visual enablement. Missing/invalid sound IDs are silent. Sound starts are limited to one per 120 ms. |
| Floating text | Off | Brief readiness text near the crosshair; no reconstructed world-space effect. |
| Accent / movement colors | Restrained green/amber/gray | RGB hexadecimal selectors. Invalid incomplete values leave the current color unchanged. |
| Retention | 100 fights / 1–1,000 | Applied on the next recording write. Deletion of valid oldest recordings is automatic under the configured budget. |
| Storage budget | 64 MiB / 1–1,024 | Applies to valid managed fight files; exports and preserved corrupt/future files are separate. |

Measurement policy values are captured at fight start. Current UI/display choices update immediately; per-fight correlation/readiness rules change on the next fight.

The HUD editor independently selects Analytics, Direction or Summary. Drag a preview; use arrows for position nudges and the wheel or Scale +/- for size. Visibility, background, alignment, opacity and reset apply to the selected panel. Anchors are normalized over the remaining viewport after scaled panel size, so GUI scale/window changes preserve relative placement. Extremely small windows can still be too narrow for a full analytics panel.

Recording paths are relative to the game directory. Each fight is a UUID-named JSON file in kwamster/fights. Export is explicit from fight details and creates an anonymized folder in kwamster/exports. Open index.html in any modern browser. Delete has a confirmation dialog and affects only the selected fight recording. Exports are independent copies and remain until manually removed.

Development test clients are muted at the Minecraft master-volume setting. A test-only initializer maintains that preference after Fabric resets options. This does not change the system volume or the user's normal Minecraft installation.
