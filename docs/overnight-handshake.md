# Overnight handshake, 2026-09-05 (Claude side)

The first live, unattended, bidirectional exchange between the two adapters
through the real journal in this repository, after the wake-path gate passed.
James authorized it before sleeping; the sequence and caps were agreed in
advance (see `skills/sideband-codex/references/bounded-handshake.md` for the
Codex side).

All times America/Bogota, from the journal's `created_at` values.

| Step | Entry | From → To | Type | Notes |
| --- | --- | --- | --- | --- |
| Authorization | `bb7492e4` | human:james → claude (via claude) | instruction | James's message, captured verbatim with `capture-human`. Every actionable entry below traces to it. |
| a | `63f94afe` 00:21 | claude → codex | status | "listener armed from offset 1271" |
| a | `52354eea` | codex → claude | status | "listener armed from offset 1646". Woke Claude's background `wait`. |
| b | `db3787df` 00:30 | claude → codex | request, caused_by `bb7492e4` | "ping: reply with one line confirming receipt, then stop." Registered as outgoing in Claude's cursor. |
| c | `6d8af889` 00:32 | codex → claude | reply, reply_to `db3787df` | "Received your ping through the Sideband journal in the Codex parent conversation." Woke Claude; `mark-delivered` correlated it to the outgoing request; resolved presented; request resolved answered. |
| c | `6b42cd07` 00:32 | codex → claude | request, caused_by `bb7492e4` | Codex's ping. Delivered with `effective_live: auto`, resolved acted. |
| d | `a0702d37` 00:33 | claude → codex | reply, reply_to `6b42cd07` | "Received your ping through the Sideband journal in the Claude Code parent conversation." |
| d | `45c5a091` 00:33 | claude → human:james | reply, reply_to `bb7492e4` | Summary for James. Visible in Claude's own turn; not delivered to Codex. |

Caps held: Claude sent one request and two replies. After step d Claude's
listener stayed armed but nothing actionable was sent.

## What this proved beyond the spike

- Codex's `codex queue` path and Claude's background-task path work with
  real protocol entries, not probes: routing metadata, `caused_by`, `reply_to`,
  and the ancestry check all passed through both adapters.
- The recipient cursor did its job on the Claude side: the originating human
  turn was never redelivered, the outgoing request went pending → answered
  with the reply id correlated automatically, and `pending` came back empty
  at the end.
- The role-scoped `wait --role claude` filtered Claude's own entries and the
  human-addressed summary out, so the listener only woke for the three entries
  that concerned Claude.

## Rough edges seen

- The authorizing human entry was captured before `capture-human` learned to
  mark the originating turn resolved, so it showed up as backlog on the first
  `activate` and had to be resolved by hand. Entries captured with the current
  binary do not have this problem.
- `activate` reported one diagnostic: the unframed bytes left by the spike
  probes at the head of this repository's journal. Harmless, and gone in any
  journal written only by the current binary.
