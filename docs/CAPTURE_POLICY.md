# Capture policy (0.1.5)

`CapturePolicy.resolve(host, automatic, manual)` selects one policy. Overview shows the current server, automatic switch, policy, environment, adapter version, match/round state, manual state, opt-in diagnostics, and export status. K and the six tabs are preserved. No gameplay HUD is added.

| Priority | Policy | Start | Normal end |
| --- | --- | --- | --- |
| 1 | Manual | `/kwm start` or Overview Start | `/kwm end` or Overview End |
| 2 | Automatic disabled | None | None |
| 3 | Configured server | Exact observed host/surface-specific start | Environment match result or explicit match-end signal |
| 4 | Generic combat on unconfigured servers | Qualifying attack or attributed incoming damage | Owner's inactivity setting; existing conservative engagement endpoints |

The six configured environments are MCPvP, CatPvP East, Minemen, PVPHQ, FishFFA and PvPLegacy. Host normalization handles case, an optional numeric port and a trailing DNS dot. Matching uses exact known hosts/aliases in `ServerCompatibility`; arbitrary subdomains and suffix lookalikes do not inherit a configuration.

Configured environments never start from ordinary combat, never end from combat inactivity, and never fall back to generic recording when announcements are missing. Deaths, removals, kill messages, unrelated attacks, and round results cannot normally finalize a configured match. Missing starts suppress combat capture and appear in diagnostics/status. Missing ends keep the match active; an explicitly announced different opponent can preserve the previous match as Interrupted after a two-second late-result window. Same-opponent starts while a match remains unresolved are suppressed as duplicates: use manual capture when a missing end prevents recognizing a rematch.

PVPHQ's MATCH REPORT marker is an explicit match end. The engine waits for the result block; if no result arrives within the configured result grace, it saves Unknown with the missing-result explanation. This wait follows an observed end marker and is independent of combat inactivity. Disconnect, world/connection replacement, automatic capture being disabled, shutdown and resource limits preserve Interrupted recordings. They are not successful match-end detections. A result arriving after finalization cannot rewrite the saved outcome.

A server-session ID groups distinct configured match IDs; each match has one recording. Its `MatchEvidence.rounds` retains independent round boundaries, outcomes, confidence and rules. A result scoped to a round never becomes a match result. When no explicit new-round marker is available, resumed combat after a declared round result supplies an approximate next-round boundary; an overall winner does not fabricate the last round's outcome. Existing event IDs and combo exchanges remain individual measurements within the match. Analytics segment exchanges by measured combat gaps and explicit round boundaries; round transitions also split unanswered streaks. Metadata is bounded to 256 rounds/512 announcement entries; existing 10,000-event automatic recording limits preserve Interrupted data when exceeded.

Manual recording overrides both enabled and disabled automatic capture on every server. Announcements and inactivity may affect nested measurement exchanges, but never end the manual parent. Multiple participants, five-second atomic checkpoints, crash recovery and automatic offline HTML export retain the 0.1.4 behavior. End clears automatic match context and restores the appropriate configured/generic/disabled policy.

Config schema remains 2. The legacy `serverInactivitySeconds` value is retained when loading old configuration but has no authority to end a configured match; its obsolete GUI control is removed. `inactivitySeconds` is labeled **Applies only to generic combat capture on unconfigured servers**. Recording schema remains 4 with optional additive round metadata; schemas 1–3 and existing 0.1.4 recordings are read without rewriting outcomes.
