# Configuration

Press **K** in a world to open the dashboard. The key is a single vanilla key mapping (Controls > Key Binds > KWAMSTER). It can be rebound there or in Settings > Controls, which also has "Reset to K", "Open Minecraft Controls" and a warning when another mapping shares the key.

Navigation: four tabs (Overview, Combat Analytics, Fight History, Settings); keys 1-4 switch tabs; Left/Right on a focused tab moves between tabs. Tab/Shift+Tab move focus and Enter or Space activates. Arrow keys adjust sliders and move the fight-list selection. Mouse wheel, the scrollbar, Page Up/Down and Home/End scroll. Escape closes an open dropdown or dialog first, then the window; the dashboard key also closes it unless a text field is focused. Tabs, sections, filters, selection and scroll positions are kept for the session.

Settings save asynchronously when the dashboard closes and on graceful shutdown. Invalid config files are backed up as config/kwamster.json.invalid-TIMESTAMP. The schema is 2. A schema-1 file is migrated on load and kept once as config/kwamster.json.v1-backup; unknown schema versions are preserved and defaults load.

## Settings by section

| Section | Control | Default / limits | Effect |
| --- | --- | --- | --- |
| General | Combat tracking | On | Observes combat independently of the dashboard. Turning it off ends the current fight as interrupted. |
| General | Record finished fights | On | Queues finalized fight snapshots to local storage. |
| General | Fight summary in chat | Off | One local chat line per finished fight; never sent to the server. |
| General / Storage | Fights kept | 100 / 1-1,000 | Applied on the next recording write; oldest valid recordings are removed first. |
| General / Storage | Storage budget | 64 MiB / 1-1,024 | Applies to valid managed fight files; exports and preserved corrupt/future files are separate. |
| General | Inactivity timeout | 8 s / 2-30 in the GUI (2-120 in the file) | Ends engagement as Unknown; ENDING begins at 75% of the interval. |
| General | Damage correlation window | 250 ms / 50-500 | Match source-attributed damage to recent attacks. Higher values can increase temporal ambiguity. |
| General | Combo timeout | 3 s / 0.5-10 | Consecutive correlated attacks beyond this gap begin a new combo. |
| General | Jump candidate window | 150 ms / 50-300 | Conservative candidate window after incoming opponent damage. |
| General | Reset all settings | Confirmation | Restores defaults; recordings and exports are kept. |
| Controls | Open KWAMSTER | K | See above. |
| Combat feedback | Play hit sounds | On | Readiness sound after each correlated hit, at most one per 120 ms. |
| Combat feedback | Sound category | Interface (UI) | Minecraft sound category (UI, Players or Master), so the matching vanilla volume slider applies. |
| Combat feedback | Per readiness category | Perfect: XP-orb pickup; others note block | Sound on/off, preset or any sound resource identifier (unknown IDs are silent), volume, pitch, preview button and chart color (hex field plus presets). |
| Combat feedback | Perfect / Great thresholds | 0.95 / 0.85 | Cooldown thresholds; Normal starts at 0.50. Apply to new fights. |
| Combat feedback | Per-hit chat line | Off | Local readiness messages, at most two per second. |
| Recording & storage | Anonymize reports | On | Replaces the opponent ID and name with "Opponent" in exports and browser views. |
| Recording & storage | Include motion traces | On | Off omits 10 Hz velocity samples from report copies (statistics unchanged). |
| Recording & storage | Open after export | Off | Export also opens the report in the browser. |
| Recording & storage | Open Recordings / Exports Folder | - | Opens the managed folder in the system file manager. |
| Interface | Accent color | 88C8AA | Active tab, focus outline and highlights. Hex field plus presets; incomplete values leave the color unchanged. |
| Interface | Density | Comfortable | Compact spacing. Layouts also compact automatically on short windows. Size otherwise follows Minecraft's GUI Scale. |
| Interface | Reduced motion | Off | Disables the tab-indicator animation and report transitions. |
| Interface | Point markers / Grid lines | On / On | Chart presentation. |
| Interface | Overview window | 20 fights / 5-100 | Fights included in the Overview summary and trend. |

Measurement policy values are captured at fight start, so correlation, combo, jump-window and readiness changes affect the next fight.

## Files

All paths are relative to the Minecraft game directory and resolved from it at runtime; nothing is hard-coded.

| Path | Contents |
| --- | --- |
| config/kwamster.json | Settings (schema 2). |
| kwamster/fights/&lt;uuid&gt;.json | One recording per fight, written atomically by a background writer. |
| kwamster/exports/Fight [vs &lt;opponent&gt;] &lt;date&gt; &lt;time&gt;/ | index.html (offline viewer) and report.json, written by View in Browser and Export. |

Export folders are named "Fight vs <opponent> YYYY-MM-DD HH-MM-SS" from the local fight start time, for example `Fight vs Steve 2026-10-22 18-04-31`. With "Anonymize reports" on (the default), or for an opponent without a name, the name is omitted: `Fight 2026-10-22 18-04-31`. The opponent text is reduced to letters, digits, spaces, dashes and underscores (at most 32 characters). If a different fight already owns a name, the first eight characters of its fight ID are appended in brackets. Reports exported by 0.1.0 keep their UUID folder names. **View in Browser** regenerates the fight's folder and opens index.html in the default browser. **Export** writes the same folder and opens it only when "Open after export" is on. Exports are independent copies: deleting a recording (with confirmation) removes only `fights/<uuid>.json`. Missing managed folders are created when opened; nothing outside them is created or opened.

## Retired HUD settings

Schema-1 HUD layout fields (`analytics`, `direction`, `alert`, `summary`, `alertSeconds`, `animate`, `floatingText`, movement colors) are still read and preserved but have no interface. `display` migrates to `chatSummary` (CHAT or BOTH) and is no longer written. `hudOverlay` (default false, not exposed) re-enables the dormant overlay for development measurements only.

Development test clients are muted at the Minecraft master-volume setting. A test-only initializer maintains that preference after Fabric resets options. This does not change the system volume or the user's normal Minecraft installation.
