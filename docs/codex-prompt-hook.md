# Codex prompt capture hook

## Implementation and registration

On 2026-09-05, James asked Codex to implement capture in the shared executable,
then explicitly approved automatic detection plus an optional `--agent` flag.
There is no separate Codex capture script or listener.

Both clients register `sideband hook prompt`. It reads the host's JSON from
stdin, captures the original prompt through `HumanCapture`, and returns
`hookSpecificOutput.additionalContext` confirming capture. The Codex skill
uses that confirmation to avoid a second capture or routing action.

Detection order is explicit `--agent codex|claude`, existing host environment
markers, then a unique match of the payload's `session_id` to a recorded
role session. An explicit/detected role must also match that session. Missing
or ambiguous ownership captures nothing. A dead process for the same
conversation can now be refreshed from the identified living caller before
capture, under the state lock; activation timestamps, watermarks and all
incoming/outgoing state remain unchanged. A living recorded process or
different conversation is never replaced by this refresh. Missing caller
identification skips capture with a diagnostic rather than guessing a PID.
`hook_event_name`, when
supplied, must be `UserPromptSubmit`; both hosts use that name, so it does not
identify the caller. Delivered envelopes (including leading whitespace),
slash commands, shell commands and blank prompts are skipped. Payload and
capture failures return zero with a diagnostic rather than blocking the
human's prompt; invalid command-line flags still follow CLI error handling.

`sideband init` merges the command into `.codex/hooks.json`, preserving
unrelated settings and an existing `--agent` override. `doctor` exposes its
registration as `clients.codex_hook`, while retaining `clients.hook` for Claude.
Registration is not evidence that Codex loaded or trusted the hook.

The [official hook documentation](https://learn.chatgpt.com/docs/hooks)
confirms project registration, JSON stdin, the prompt-submit response shape,
and the requirement for user trust review through `/hooks`.

## Verified

- Installed host: `codex-cli 0.153.3`.
- `./gradlew test`: 255 passing test cases. Added coverage includes both
  clients, unknown additive payload fields, exact whitespace/Unicode bodies,
  routing/provenance, originating-turn disposition, marker-free fallback,
  override precedence, mismatched/ambiguous sessions, envelope filtering,
  invalid paths, non-submit events, and registration preservation/idempotency.
- `just install`: native build succeeded and installed
  `/Users/jamesdh/.local/bin/sideband` (protocol v1). Binary SHA-256:
  `a464e00ff9b54095ede02b5bcd506ff5792887a5e298522703a127283e685889`.
- Native smoke checks passed at `2026-09-05T19:58:01.085Z`, exercising real
  subprocess stdin/stdout: automatic Codex capture, exact body and authorship,
  originating-turn disposition, skipped envelope, marker-free session
  fallback, explicit override of Claude markers, stale-session rejection,
  routing to Claude, and repeatable installation/doctor reporting.
  Temporary evidence: `/tmp/sideband-native-hook.nzL87d/smoke.cjs`; entries
  `336e0e21-f7b6-4a0b-a6b0-b5d231bc39c2`,
  `a2f07f06-6188-46fb-9a24-396a3eecaa19`, and
  `766697f0-a24c-4e42-ad81-8d40e79512b6` exist only in that temporary repository.
  The executable alone read/wrote its journal; no test entry entered the real
  project conversation.
- Skill validation passed using the provided validator with PyYAML supplied
  through `uv`; no Python dependency was added to Sideband.
- `sideband init` in this project reported the Codex hook `added`, and both
  skill stubs and the existing Claude hook `unchanged`.

## Initial live host verification checklist

The tests above invoke the real executable with fixture payloads. They do not
prove that the interactive Codex host invokes the hook.

1. James reviews and trusts this project's Sideband `UserPromptSubmit` entry
   through `/hooks`. Do not write trust hashes or bypass hook trust. If the
   new entry is not discovered by this running host, resume the conversation
   with the updated project configuration before reviewing it.
2. Submit an ordinary human prompt. Before a model-driven capture, verify
   that hook context confirms capture; inspect the new entry only through
   Sideband. Confirm one entry, the exact body, `from: human:james`, and
   `via: codex`.
3. Deliver one authorized `[Sideband message]` envelope. Confirm no new human
   entry is captured for it, including by the skill fallback.
4. Submit a human revision while Codex is active. Verify the host invokes
   capture for that input, not just for prompts that start idle turns.

Inspect the result on each resulting turn; do not idle indefinitely waiting
for a hook to prove itself. Actual hook-shell environment inheritance remains
unobserved. The tested session fallback and optional flag mean that missing
markers alone need not block capture. Cross-layer duplicate registration and
host-level repeated event delivery are not an exactly-once guarantee from
this implementation; use one capture registration and the per-prompt success
context to avoid model recapture.

## Live capture result and resume correction

After James trusted the hook and restarted Codex, `sideband doctor` reported
the recorded Codex host as dead: activation still referenced PID `21585`.
The thread ID remained `01a064f7-eaa7-7b63-af57-59796b87129f`. The original
hook required the stored PID to be alive, so it rejected the resumed thread.
Explicit reactivation updated the recorded host to PID `14532`.

At `2026-09-05T15:10:19-05:00`, James's `test again` was captured automatically
as `adbf04d6-df9e-46d9-8de1-3e05cc3c202d`, with the exact ten-byte body,
`from: human:james` and `via: codex`. A native scan returned one new entry and
the parent received `Sideband journaled this prompt. Do not capture it again.`
as developer context. No model-driven capture was performed. The following
question and `Make that fix` also arrived with hook confirmation and are
recorded as `f74ff7bb-de5d-4aaf-bd98-77041ffddf0d` and
`7d3b601b-4d8e-4f8b-8b54-50cb66ea2021`.

James approved automatic same-conversation refresh. The hook now matches
recorded session identity independently of PID liveness, then asks the state
component to check ownership and refresh a dead PID under its existing lock.
No full activation is performed. This preserves the existing live/backlog
classification and dispositions; it does not replay or act on waiting work.
Envelopes are filtered before refresh, so receiving one cannot revive a role.

The pre-restart configuration-loading explanation remains a hypothesis.
The post-restart stale-PID rejection and successful capture following
reactivation are observed facts. Subsequent automatic recovery across a real
host restart, queued-envelope filtering after restart and mid-turn human
capture remain distinct live checks, not results inferred from fixture tests.

The fix passed the complete 271-case JVM suite and `just install`. Native
restart regression passed at `2026-09-05T20:17:21.731Z`, using the installed
binary and a throwaway repository: a dead recorded PID was refreshed via
process ancestry with no client marker environment variables, the resumed
prompt was captured once, watermark and pending incoming/outgoing data were
unchanged, an envelope did not revive the session, and another conversation
could not replace it. Evidence script:
`/tmp/sideband-resume-hook.bW9J7v/check.cjs`; fixture entry:
`f4529a9e-be21-4d37-b621-81db16ceb299`. Only the executable accessed its journal
and cursor. Installed binary SHA-256:
`9f9efbbfcb9335793a15e6fc63b6ee4c320f82052f7c6edafcd200e5757a0879`.
No host configuration or trust definition changed for this fix.
