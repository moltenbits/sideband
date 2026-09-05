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
markers, then a unique match of the payload's `session_id` to a live recorded
role session. An explicit/detected role must also match that session. Missing,
stale or ambiguous ownership captures nothing. `hook_event_name`, when
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

## Live host verification still required

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
