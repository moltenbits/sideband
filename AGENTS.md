# Agent notes for Sideband

Quirks of the host clients that Sideband has to work around, and the
workarounds. Each was measured, not inferred; the version and date say when.
The README explains what Sideband is; REQUIREMENTS.md is the specification.

## Codex (codex-cli 0.153.4, measured 2026-09-07)

- **After `/clear`, Codex cannot rejoin the discussion until it is prompted.**
  `/clear` creates the new thread at once, but Codex runs the `SessionStart`
  hook (source `clear`) and the `UserPromptSubmit` hook only when the first
  prompt is submitted in that thread, both in the same instant. Neither
  Sideband hook runs in between, so Sideband's recorded thread id stays on the
  old thread until that prompt. Workaround: **after `/clear` in Codex, type
  one prompt before expecting delivery.**
- **The old thread stays loaded and answers off screen.** `/clear` does not
  unload the previous thread. `codex queue --thread <old id>` still injects a
  turn into it, which runs, acks, and replies through Sideband while a
  different thread is on screen. So an entry pushed in the window above is
  not lost: it is handled by the old thread and its reply is recorded in the
  discussion, just not shown in the new conversation. `sideband pending` does
  not replay Codex's completed replies.
- **No supported displayed-thread lookup was found in the tested TUI setup.** Codex's
  generated app-server protocol has no request or notification for a client's
  displayed thread; `codex queue` takes only a thread id or exact session
  name; the running TUI's in-process server had no attachable socket in the
  tested setup. The lock files under `~/.codex/thread-writer-locks/` were
  tried and rejected: resuming an already-loaded thread kept its old lock
  time, an ephemeral thread start took no lock, a durable fork took another,
  a SIGKILL left a stale file. Side conversations and subagents were not
  tested. Do not propose the lock files again without new evidence.
- **Changed hook definitions must be re-trusted, and a skipped hook looks
  installed.** Codex stores a `trusted_hash` per definition under
  `[hooks.state]` in `~/.codex/config.toml`, keyed by the hooks file's path
  plus event and indexes. A new or changed definition is skipped, silently
  from Sideband's point of view, until the operator trusts it through
  `/hooks`; an unchanged trusted one keeps running. `sideband doctor` checks
  registration only, so it reports such a hook as installed. Workaround: after
  any `sideband init` that adds or changes a definition in `.codex/hooks.json`,
  run `/hooks` in Codex and trust it, then type one prompt so the recorded
  thread id refreshes.
- **Debugging a silent Codex.** Use `sideband doctor` for the recorded thread
  id and state directory. Codex's rollout files under `~/.codex/sessions/`
  and its `queue_1.sqlite` are version-specific diagnostic evidence, not
  APIs; inspect the schema before querying. A queued item that never drains,
  or another thread's rollout growing after a push, warrants a look but does
  not by itself prove a stale address: the clear-window failure above drains
  the queue normally in the old thread. Never edit Sideband state or Codex's
  queue or trust records by hand.

## Claude Code (2.1.263, measured 2026-09-07)

- **A push is held for approval unless the user settings accept it.** A
  Sideband push arrives over the session's inbox socket from a process that
  is not the session's child, and a bypass-permissions session holds it
  unless `crossSessionInbound` is `accept` in `~/.claude/settings.json`. The
  repository's `.claude/settings.json` can only tighten that value, so
  `sideband init` does not write it; `sideband doctor` says where accept must
  go.
- **Delivery does not depend on Sideband's record.** The writer finds the
  running session through `~/.claude/sessions/<pid>.json` by working
  directory, so a clear or restart in Claude Code needs nothing further.
- **To learn what Claude Code actually does, read its binary.** Its bundled
  code is plain strings: `strings "$(readlink -f "$(command -v claude)")"`.
  That is how the hook payloads, the cross-session message frame, and the
  `SessionStart` sources (`startup`, `resume`, `clear`, `compact`, `fork`)
  were verified. Record the version checked in REQUIREMENTS.md section 17.

## Build

- **Changing the SQLite journal mode per connection fails under contention**
  (sqlite-jdbc 3.53.4.0 bundling SQLite 3.53.4, macOS 26 on Apple silicon,
  measured 2026-09-07). `SQLiteConfig.setJournalMode(WAL)` makes the driver
  run `PRAGMA journal_mode` inside `SQLiteConfig.apply` while opening each
  connection, and SQLite answers `SQLITE_BUSY` there at once, without the busy
  handler, when another connection is mid-write. Probe: the "concurrent
  writers" case of `SqliteJournalSpec` (8 threads, 25 appends each) with that
  setting on the data source; it lost one writer's 25 appends in about one run
  in three, the failure's cause chain ending in `SQLiteConfig.apply`. The store
  therefore never sets a journal mode and relies on the default rollback journal
  plus the busy timeout.
- **sqlite-jdbc extracts its native library on every run** (same versions and
  date). `SQLiteJDBCLoader` writes `libsqlitejdbc.dylib` to a fresh
  `java.io.tmpdir` file, named with a random UUID, each process, and macOS
  inspects the new file before loading it. Probe: `DYLD_PRINT_LIBRARIES=1
  sideband pending` shows the temporary path; `/usr/bin/time` put a warm
  command at ~250 ms, ~40 ms with `-Dorg.sqlite.lib.path` pointing at a copy
  extracted once. The store keeps one copy in the state directory, beside the
  database, as `sqlite-jdbc-<version>-<os>-<arch>/libsqlitejdbc.<ext>`, and
  points the driver at that directory through `org.sqlite.lib.path` only:
  overriding `org.sqlite.lib.name` as well would make the driver look for a
  bundled resource of that name after a failed load and skip its extraction
  fallback, so a damaged copy would lock the database (Codex reproduced this
  with an x86_64 library in an ARM state directory, 2026-09-07). The first
  command in a repository takes ~1 s to write it.
- **The skill instructions are embedded in the executable.** `build.gradle.kts`
  copies `skills/` into the resources, so a change under `skills/` needs
  `just install` before the installed hooks and skills reflect it.
- **Writing literal Unicode escapes into source.** Some tool layers decode
  `\uXXXX` into the real character before the file is written; a quoted shell
  heredoc preserves what it receives. Verify the resulting bytes rather than
  assuming either. When a layer decodes, build the sequence from ASCII
  pieces. An ASCII-only check does not prove an escape was preserved, since
  it also passes if the escape was decoded to an ASCII character.
