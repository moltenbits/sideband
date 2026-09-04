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

## Codex: not yet run

Codex was not connected to the bridge during this session, so the capability
questions in the Codex skill stub (background subagent survival across idle,
long-blocking shell command, parent follow-up messaging) are unanswered.
`skills/sideband-codex/SKILL.md` describes the exact test to run. Until it
passes, implementation beyond this spike must not proceed (REQUIREMENTS.md 17.1).
