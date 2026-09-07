# Agent notes for Sideband

Operational knowledge for anyone, human or agent, working on this repository.
The README explains what Sideband is and how to use it; REQUIREMENTS.md is the
specification. This file holds what neither states: how the two agents work
in this repository together, and what the host clients were measured to do.

## Working in this repository

- **Two agents, one working tree.** Claude Code and Codex commit to the same
  checkout and coordinate through Sideband's journal under `.git/sideband/`.
  Ownership: Claude owns `src/**`, the build files, `skills/claude/**`, the
  README and REQUIREMENTS.md; Codex owns `skills/codex/**` and Codex-specific
  documentation. Never edit the other side's paths; send a request through
  Sideband instead. Commit only your own paths with explicit `git add <paths>`,
  never `git add -A`, because the other agent may have uncommitted work in the
  tree.
- **Codex reviews every commit.** After each commit, request a review through
  Sideband (`sideband append --to codex --type request --caused-by <id>`)
  naming the hash, what changed, and the test count. Address findings on the
  same branch. A PR is opened only when the operator asks.
- **Native only.** Anything a client runs is the native `sideband` executable
  or a stub that invokes it: hooks are `sideband hook <event>` subcommands,
  skills are `SKILL.md` stubs that run `sideband skill`. No Python, Node, jq,
  or shell logic in anything installed or invoked by a client. Tests are
  Groovy Spock and may use whatever they like.
- **Build only what changed.** `./gradlew test` is the gate. Run `just install`
  (native image, about forty seconds on every core) only when `src/main/java`
  or an embedded resource changed. The skill instructions under `skills/` are
  embedded in the executable, so a change there needs a rebuild and install
  before the installed hooks and skills reflect it.
- **Verify host behavior, do not guess.** Claude Code's bundled code is plain
  strings in its binary (`strings "$(readlink -f "$(command -v claude)")"`).
  Codex's app-server protocol can be generated from its executable. Record
  the version checked in REQUIREMENTS.md section 17.
- **Writing Unicode escapes.** A `\uXXXX` typed into a heredoc, sed pattern, or
  editor tool lands as the real character. Build such sequences from ASCII
  pieces and check with `LC_ALL=C grep -c '[^ -~]' <file>`.

## What Codex was measured to do (codex-cli 0.153.4, 2026-09-07)

- **`/clear` starts a new thread and keeps the old one loaded.** The new thread
  id and its lock under `~/.codex/thread-writer-locks/` appear at the clear.
  The old thread stays runnable inside the same process: `codex queue
  --thread <old id>` still injects a turn into it, which runs and answers off
  screen.
- **Hooks run at the first prompt, not at the clear.** The `SessionStart` hook
  with source `clear` and the `UserPromptSubmit` hook both fire when the first
  prompt is submitted in the new thread, in the same instant. Nothing runs
  between the clear and that prompt. Sideband's record therefore follows the
  operator at the first prompt, and any entry pushed in the window goes to the
  old thread. **After `/clear` in Codex, type one prompt before expecting
  delivery.** This window is accepted; see REQUIREMENTS.md 7.1 and 17.2.
- **Do not try to find the displayed thread.** Codex's generated protocol has
  no request or notification for a client's displayed thread; `codex queue`
  takes only a thread id or exact session name; the running TUI's in-process
  server had no attachable socket in the tested setup. The lock files were
  rejected as a heuristic: resuming a loaded thread keeps its old lock time,
  ephemeral threads take no lock, forks take their own, a kill leaves stale
  ones. Do not propose it again without new evidence.
- **Hook trust is per definition and must be redone after `init` changes the
  file.** Codex stores a `trusted_hash` per definition under `[hooks.state]` in
  `~/.codex/config.toml`, keyed by the absolute path of `.codex/hooks.json`. A
  new or changed definition is skipped until the operator trusts it through
  `/hooks`; an unchanged trusted one keeps running. `sideband doctor` checks
  registration, not trust, so a skipped hook looks installed to it. After
  trusting, the operator types one prompt so the address refreshes.
- **Debugging silent Codex.** Compare the thread id in
  `.git/sideband/sessions/codex.json` (or `sideband doctor`) with the rollout
  files under `~/.codex/sessions/` and the `queued_items` table in
  `~/.codex/queue_1.sqlite`. A queued item that never drains, or a rollout for
  a thread other than the recorded one growing after your push, means the
  record is stale.

## What Claude Code was measured to do (2.1.263, 2026-09-07)

- Delivery rides the session's inbox socket found through
  `~/.claude/sessions/<pid>.json` by working directory, not through Sideband's
  record, so a clear or restart needs nothing beyond the
  `crossSessionInbound: accept` setting in the user's `~/.claude/settings.json`.
- The `SessionStart` hook supports sources `startup`, `resume`, `clear`,
  `compact` and `fork`, matches the matcher against `source`, and reads
  `hookSpecificOutput.additionalContext`. The prompt hook fires on every
  prompt the operator types, including slash commands.
