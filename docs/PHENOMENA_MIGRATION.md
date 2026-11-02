# KWAMSTER → Phenomena migration and modernization report

Version 0.2.0 · 2026-11-02 · starting commit `1b5f85b860636488a02373ee43ea5be098fb13b9` (KWAMSTER 0.1.6 + notification hotfix) · local development work; no push, publication or Aurora installation.

## 1. Rename inventory

| Identity | Old | New |
| --- | --- | --- |
| Product name | KWAMSTER | Phenomena |
| Fabric mod id | `kwamster` | `phenomena` |
| Java packages | `dev.kwamster.*` | `dev.phenomena.*` |
| Root project / artifact | `kwamster` → `kwamster-0.1.6.jar` | `phenomena` → `phenomena-0.2.0.jar` |
| Entry point | `dev.kwamster.KwamsterClient` | `dev.phenomena.PhenomenaClient` |
| Mixins config | `kwamster.mixins.json` (package `dev.kwamster.mixin`) | `phenomena.mixins.json` (package `dev.phenomena.mixin`) |
| Asset namespace | `assets/kwamster/` | `assets/phenomena/` |
| Keybind ids | `key.kwamster.open`, category `kwamster:controls` | `key.phenomena.open`, category `phenomena:controls` |
| HUD element id | `kwamster:analytics` | `phenomena:analytics` |
| Commands | `/kwm start|end|diagnostics …` | `/phenomena start|end|diagnostics …`; `/kwm` retained as a deprecated redirect alias |
| Data directory | `<game>/kwamster` | `<game>/phenomena` (one-time forward copy) |
| Config file | `config/kwamster.json` | `config/phenomena.json` (legacy read once) |
| Export ownership marker | `.kwamster-owner` | writes `.phenomena-owner`; reads both spellings |
| Report payload | `format: "kwamster-report"` | `format: "phenomena-report"` |
| Logger name / chat prefix | `kwamster` / `[KWAMSTER]` | `phenomena` / `[Phenomena]` |
| Test mod id | `kwamster-test` | `phenomena-test` |

Historical versioned documents (`docs/0.1.*`), example reports, performance snapshots and screenshots keep the KWAMSTER name deliberately: they describe provenance. Current documentation and every user-facing surface use Phenomena.

## 2. Repository location

Final repository: `C:\Dev\Phenomena` (branch `codex/0.1.2-combat-corrections`, full history preserved). The Windows directory rename was blocked by an external handle on `C:\Dev\Kwamster` held by the development session harness itself (both `ren` and `Rename-Item` returned "used by another process" while subdirectories moved freely). The migration therefore used a verified full-tree copy (robocopy `/E /COPY:DAT /DCOPY:DAT`), then confirmed: clean `git status`, identical HEAD, `git fsck` clean, and 14,504 = 14,504 files. **The original `C:\Dev\Kwamster` is untouched** and can be deleted by the owner once no session has it open. Nothing was force-deleted or cleaned.

## 3. Configuration and historical data migration

- `ConfigStore` reads `config/kwamster.json` exactly once when `config/phenomena.json` is absent; all saves target the new file. The legacy file is never modified. Schema 2 decoding is unchanged, so schemas 1–3 history and all settings fields survive as before.
- `LegacyDataMigration` runs as the first task on the single storage worker, before history load and report recovery: it copies `kwamster/` → `phenomena/` forward-only. Files copy to a temp sibling and move atomically, so an interrupted pass never leaves a partial file that could be mistaken for a conflict. Identical existing files are skipped (idempotent re-runs); conflicting files are never overwritten and are recorded by name. A committed `phenomena/migration.json` marker (schema 1, source, counts, bounded conflict list) prevents repeated migration on every startup. Symbolic links are ignored, never followed. The legacy tree is left completely untouched.
- Receipts remain valid: the export digest (recording + options + report format version 6) is unchanged by the rename, so migrated fights are **not** re-exported and historical reports keep their original KWAMSTER branding as provenance. New exports carry Phenomena branding.
- Known cosmetic limitation: a rebound dashboard key reverts to the default K once, because Minecraft stores keybindings by name and the id changed. Documented in Settings → Controls.
- Regression coverage: `PhenomenaMigrationTest` (fabric metadata identity, asset namespace, legacy config read-once, fresh-install marker, exact-copy-once, interrupted-resume/conflict preservation, diagnostics migration, primary command + alias, both ownership markers).

## 4. PVPHQ notification regression

The owner-reported burst (`[KWAMSTER] Fight saved; automatic HTML report ready` ×9+) was diagnosed and fixed at `1b5f85b` (silent startup recovery, one-shot delivery tokens in `ExportNotifications`). This milestone re-verified the mechanism rather than re-diagnosing:

- Live-instance evidence re-inspected read-only (manifest hash recorded before/after): no PVPHQ session and zero completion notices in the newest logs; the incident remains the 2026-10-24 session already explained by receipt-revision re-exports replayed as fresh completions.
- All notification lifecycle suites (`ExportNotificationsTest`, `ExportNotificationLifecycleTest`, `NotificationClientTest` chat-probe acceptance) pass on the renamed tree; delivery text is unchanged.
- The rename preserves the fix verbatim: one token per newly committed receipt revision, silent recovery, bounded session queue, no replay on world/server changes.

## 5. Renderer (adapted from Aurora Client)

Aurora Client's per-pixel approach was audited at `C:\Dev\Aurora-Client` (rev `0b1e41a`): analytic coverage rasterization in **physical device pixels** (`RenderUtil.fillAARow`), submitted as ordinary batched `GuiGraphics.fill` calls under a `1/guiScale` pose — no shaders, no textures, no GL state changes; fonts stay on Minecraft's native pipeline. Phenomena adapts the *technique* (not the code) in `dev.phenomena.gui.pixel.Pixel` (~330 lines): rounded fills/outlines with exact sub-pixel coverage folded into alpha, circles, axis-aligned and diagonal AA lines, per-device-row gradients, an integer fast path, fractional GUI scales, and a fills-submitted counter. Vanilla-pipeline compatibility is inherited by construction (Sodium/ImmediatelyFast safe; no framebuffer or blend-state tampering).

Aurora components audited for interaction semantics: focus rings offset outside the selection edge (so they remain visible on selected peers), selection as wash + one accent edge (never triple emphasis), destructive actions as neutral faces with red labels committing to solid red on press, one primary per screen, no all-caps micro-labels — all adopted from `SPXCTRA_UI_SIMPLIFICATION_REPORT.md` rulings and the Spxctra UI skill.

## 6. UI modernization

- **Spxctra theme** (`Theme`): role-named surface ladder, status colors, the validated CVD-safe chart series (unchanged semantics), geometry vocabulary (radius 4/3, focus gap), 120 ms eased state transitions with reduced-motion snap.
- **Components**: KButton (tab/chip/primary/danger/subtle styles on Pixel), new KToggle switch, KSlider with AA track/knob, KTextField inset wells, swatches, popups, FightList, HUD and all chart blocks repainted at device resolution.
- **Screens**: rounded content card on a translucent scrim, flat-rest tabs with animated accent indicator, rounded scrollbar with scroll fades, hairline footer, per-pixel stat cards/notes/tables, AA chart grid/lines/points/bars/hitboxes.
- **History**: repeated per-row Browser action removed — the selection toolbar owns contextual actions (Details / View in Browser / Export / Delete); semantic result inks, rounded hover/selection rows, roomier rows.
- **Settings**: regrouped into Recording, Server capture, Combat exchanges, Attack classification, Audio feedback, Appearance, Data & exports, Diagnostics, Controls; the generic-capture inactivity timeout and the combat-exchange inactivity timeout are explicitly separated and explained; boolean settings use real switches.
- Combat analytics definitions, capture policies, schemas and report formats are untouched.

## 7. Validation summary

- Unit suite: **361 tests, 0 failures** (1 pre-existing Windows symlink-privilege skip) — includes the new migration regressions.
- Native client acceptance and per-resolution screenshots: see `docs/0.2.0.md`.
- Performance and four-mod compatibility results: see `docs/0.2.0.md`.

## 8. Known limitations

- `C:\Dev\Kwamster` original directory remains until the owner removes it (external handle; see §2).
- A rebound open-key resets to K once after the rename.
- Historical reports keep KWAMSTER branding (provenance); re-export from History refreshes branding per fight if ever wanted.
- Live external PVPHQ acceptance of 0.2.0 is owner testing; all local evidence paths passed.
