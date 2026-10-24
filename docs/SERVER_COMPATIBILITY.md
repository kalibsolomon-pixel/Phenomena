# Server compatibility and evidence collection (0.1.4)

Compatibility means recognition of the listed observed messages, not universal support for a server's modes. The local inventory is `kwamster/server-compatibility.json`. Adapter metadata is also included in diagnostic captures, together with the rule version used to interpret them.

| Server | Hosts (including subdomains) | Start / end / result / round | Evidence status | Last fixture validation | Evidence and limitations |
| --- | --- | --- | --- | --- | --- |
| Minemen | minemen.club, minemen.net | Supported / supported / supported / supported | **Real log fixtures** | 2026-10-24 | Owner's anonymized `outcomes.json`, measurement audit; observed Duel/Casual Match blocks, kill lines, round scores and paired results. No new explicit draw or forfeit grammar has been invented. Countdown results retain existing conservative evidence handling. |
| PVPHQ | pvphq.com | Supported / supported / supported / supported | **Real log fixtures** | 2026-10-24 | Same fixture corpus; opponent, ranked/unranked banner, kill, match report, winner and loser lines, best-of continuation. One-name results require the existing scoped context. |
| Other hosts/private servers | Unknown | No host-specific guarantees | **Unknown/unsupported** | — | Manual recording works independently of a server adapter. Generic exact fallbacks remain non-authoritative and cannot manufacture a confirmed result. |

Evidence categories are: **real multiplayer recordings**, **real log fixtures**, **synthetic tests only**, and **unknown/unsupported**. This release claims real log fixture coverage for these two adapters. It does not add new live-multiplayer validation. Synthetic manual-session tests and integrated-server client tests do not upgrade an adapter to real multiplayer validation.

## Adapter architecture

`Announcements.Rules` is the extension point. Each implementation exposes `metadata()` with an ID, rule-set version, aliases, capability flags, observed modes, evidence source, validation date and limitations. It supplies hostname matching and whole-message parsing. Registration happens during initialization; combat measurements need no server-specific changes.

Minemen and PVPHQ retain their existing exact patterns and confidence rules. System, player chat, disguised chat, overlay, title and subtitle are distinct origins. Titles/subtitles are non-authoritative notes, and overlays do not resolve results. The capture mixin can observe system/actionbar, title and subtitle packets; it cannot promise access to every plugin-rendered surface. Player/disguised chat packets are not hooked for diagnostics. A text file such as `latest.log` is not a complete announcement source.

The engine owns duplicate suppression, named-opponent binding, round scores, result grace periods, delayed-result correlation, rematches and conservative disconnect handling. A result from another host cannot resolve the active fight. Kill evidence remains distinct from declared results. Unsupported draw/forfeit formats stay unsupported until observed. Evidence does not become trusted merely because diagnostic capture collected it.

## Collecting a new server's evidence

1. Join the server and enter `/kwm diagnostics on`. Capture is **off by default**. It stores relevant candidate announcements and lifecycle observations locally; nothing is uploaded.
2. Enter `/kwm start`, play the match or several rounds, then `/kwm end`. This gives you a usable recording even when automatic detection does not understand the server.
3. Enter `/kwm diagnostics off` after playing. Captures are in `kwamster/diagnostics/<capture-id>.json`. They include hostname, epoch/monotonic timestamps, origin, raw candidate text, interpreted winner/loser/opponent candidates, rule, confidence and the lifecycle acceptance/rejection category. Attack endpoints, attributed/unattributed damage, local death, removal, teleport, disconnect and finalized outcomes supply context.
4. Enter `/kwm diagnostics export` to create `fixtures-<timestamp>.json` locally. This exports the available capture history with player names and server identities redacted and timestamps made relative. `/kwm diagnostics export-names` redacts names while retaining host fields; `export-servers` retains names while redacting hosts. There is no automatic upload or unredacted export command.
5. Inspect `summary`: observed start/end/result rules, unrecognized candidates, outcome confidence, Unknown/Interrupted counts and potential false positives. Inspect each entry's decision and channel before treating a pattern as authoritative. A recognized pattern can still be rejected for stale, duplicate, missing-participant or mismatched-context evidence.
6. Review a copy of the bundle before sharing it. Unknown word tokens are conservatively replaced during name redaction; restore reviewed public grammar where needed to make a regression fixture. Private originals remain local and unchanged. Never commit raw captures, private logs or recordings.
7. For a new adapter, add exact observed structures and negative cases to tests, replay start/round/end ordering and delayed/rematch cases, then update the inventory's evidence level and limitations. A synthetic grammar alone is not validated compatibility.

Diagnostics omit unrelated text, explicit private-message prefixes, player/disguised chat and uncertain origins by default. Servers can relay chat on the system channel using arbitrary formats, so filtering cannot recognize every possible private-chat convention. Inspect local evidence before sharing; the default export additionally redacts unknown tokens. Captures rotate at 2,048 entries, are checkpointed every five seconds, and are never automatically pruned. Disabling diagnostics flushes already-collected evidence. Export reads only local capture files, preserves originals, and performs serialization/file work on the storage worker.
