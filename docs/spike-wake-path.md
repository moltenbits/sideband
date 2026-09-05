# Wake-path spike (REQUIREMENTS.md 10.6)

The feasibility gate requires proof that an entry appended to the journal by
another process wakes each client's idle parent conversation without a hook,
daemon, MCP server, or headless invocation.

## Claude Code: passed (2026-09-04)

Setup, run from an interactive Claude Code session in this repository with the
native `sideband` binary on `PATH`:

1. The session started `sideband wait --repo <repo> --from 0 --timeout 120` as a
   background Bash task and then went idle.
2. A detached shell process (`nohup ... & disown`, not tracked by the session)
   slept ten seconds and ran `sideband append --body-file ...`.
3. The wait process observed the complete entry and exited 0 with
   `{"start":0,"end":141,"entries":[...],"timed_out":false}`.
4. Claude Code delivered the task completion as a notification, which
   re-invoked the parent conversation. The parent read the entry, with its
   body verbatim, and could act on it.

Mechanism: Claude Code re-invokes the model when a background task exits. No
model tokens were spent while the wait process was blocked. The entry landed
in `.git/sideband/journal.md`, which `git status` does not show.

Observed detail worth carrying into the design: the delivery is the task's
stdout, so the JSON shape of `wait` is the delivery envelope on this host.

## Codex: experiment prepared; idle wake not yet verified

Experiment ID: `sideband-codex-wake.R0qG3u`. Prepared on 2026-09-05 UTC
(2026-09-04 in the session's America/Bogota timezone).

Environment and baseline:

- `codex --version`: `codex-cli 0.153.3` (installed CLI).
- Native executable: `/Users/jamesdh/.local/bin/sideband`.
- Binary SHA-256:
  `0c2fb5fee10b4ed76315d2b05db1d6069334be100a25c842ff38c7700c70d9f4`.
- Repository: `/Users/jamesdh/Projects/moltenbits/sideband`.
- Initial journal size: 141 bytes; existing entries must not be replayed.
- `./gradlew test` succeeded with the test task up-to-date; no fresh test
  execution or native rebuild is claimed.

Procedure for this attempt:

1. Spawn exactly one transport subagent, `/root/sideband_listener`, using the
   current host's `collaboration.spawn_agent` facility. It runs:

   ```sh
   /Users/jamesdh/.local/bin/sideband wait --repo /Users/jamesdh/Projects/moltenbits/sideband --from 141 --timeout 3600
   ```

2. After listener readiness, launch a detached shell process that sleeps 120
   seconds and then invokes `sideband append` with the unique probe body in
   `/tmp/sideband-codex-wake.R0qG3u/body.md`. This is an external-writer stand-in,
   not an actual Claude session. Its output goes to `append.log` in that same
   temporary directory. The delay separates setup from delivery; it is not a
   request retry or a production timeout policy.
3. End the parent's turn with a final response. Do not use a parent wait tool
   or send user input to trigger delivery.
4. The listener forwards successful `wait` stdout verbatim through
   `collaboration.send_message`, addressed to `/root`, prefixed with
   `[Sideband message]`. It starts its next native wait instead of finishing,
   so subagent completion is not a competing wake trigger.
5. If that message starts a new parent turn, record the actual envelope,
   offsets, timestamps, and delivery behavior here, then stop the listener.
   If another user turn is needed to discover the message, do not count that
   as an automatic wake. A missing wake requires external observation; this
   preparation record is not evidence of success or failure.

The host's shell tool yields a running session, which the listener resumes
with `write_stdin`; its tool waits are capped at 60 seconds in this
environment. This experiment must not be reported as proof of zero idle model
tokens or an uninterrupted hour-long wait. It tests survival across the
parent's turn boundary and the actual interval before the external append.

[Official subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
describes interactive subagents and returned results, but does not establish
this post-final idle-parent wake guarantee. The OpenAI Docs check therefore
does not substitute for this live experiment.

Outcome: pending observation. Implementation beyond the spike remains gated
by REQUIREMENTS.md sections 10.6 and 17.1. No daemon, hook, MCP server, or
headless Codex invocation is introduced.
