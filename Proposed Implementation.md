# Proposed Implementation

## 1. Summary

Implement Sideband as one client-neutral native executable and two thin Agent
Skills—one for Claude Code and one for Codex. The `sideband` executable owns the
durable protocol: repository discovery, journal serialization and parsing,
locking, routing, recipient state, backlog classification, and blocking waits.
The skills own only the behavior that differs between clients: activating a
listener, capturing a human turn, delivering an envelope into the current parent
conversation, and presenting backlog choices.

This follows the useful separation in
[`agent-bridge`](https://github.com/kununu/agent-bridge): keep shared mechanics in
small tools and isolate host differences in adapters. Sideband does **not** reuse
Agent Bridge's peer-CLI dispatcher. It must never start `claude -p`, `codex exec`,
or another agent session. Both adapters communicate only with their already
running interactive parent.

The version-one implementation will use:

- A small Micronaut CLI application compiled to a native executable with
  GraalVM Native Image.
- Micronaut Serialization for reflection-free, compile-time JSON serialization.
- Micronaut Picocli for typed commands and compile-time command metadata.
- One installed `sideband` executable used by both clients.
- Two instruction-only client skills installed alongside that executable.
- Explicit activation (`/sideband` in Claude Code and `$sideband` in Codex).
- One background transport worker per active client session.
- A one-shot blocking `wait` command, so idle listening consumes no model tokens.
- Full journal scans in version one, with byte offsets used only by live waits.
  This favors correctness and recoverability over premature indexing.

## 2. Design decisions

The requirements leave several choices open. Version one should resolve them as
follows.

| Decision | Version-one choice | Rationale |
| --- | --- | --- |
| Activation | Explicit skill invocation | Predictable, debuggable, and does not depend on hooks being available in both hosts. |
| Shared implementation | One Micronaut CLI compiled with GraalVM Native Image | Provides a strongly typed, testable implementation with fast startup and no JVM requirement on the user's machine. |
| Serialization | Micronaut Serialization | Generates serialization metadata at compile time without reflection and fits Native Image's closed-world model. |
| Client packaging | One native executable plus separate Claude and Codex instruction-only skills | Host integration remains separate while journal behavior has exactly one runtime implementation. |
| Installation | One platform-aware Sideband distribution installs or upgrades the executable and both skills together | Prevents duplicate binaries and version skew between clients. |
| Human identity | Per-repository `config.json`, initialized from `git config user.name` and an explicit stable slug | Gives readable headings without confusing display names with protocol identity. |
| Agent replies | Record final replies, delegations, and statuses participating in a Sideband exchange; exclude routine commentary and tool traffic | Preserves the participant-visible conversation without becoming a transcript recorder. |
| Routing directive delivery | Preserve the original body, including the directive | The internal delivery envelope prevents republishing, so altering human text is unnecessary. |
| Deferred backlog | Surface once on activation, then only on explicit `pending` inspection during that session | Keeps deferred work discoverable without interrupting every turn. |
| Non-Git directories | Reject with a clear error | Explicitly outside version-one scope. |
| Retention | No automatic compaction | The append-only record remains authoritative; retention is a later protocol feature. |

## 3. Architecture

```text
Human turn in Claude                         Human turn in Codex
          │                                           │
          ▼                                           ▼
  sideband-claude skill                       sideband-codex skill
          │                                           │
          └───────────── client adapter ──────────────┘
                               │
                               ▼
                  shared sideband executable
          ┌────────────────────┼────────────────────┐
          │                    │                    │
       journal              cursor               wait
    parse / append      state / backlog       live detection
          │                    │                    │
          └────────────────────┼────────────────────┘
                               ▼
              <git-common-dir>/sideband/
```

The transport worker is deliberately narrow. It waits for complete addressed
entries, sends a structured envelope to its parent conversation, records the
successful handoff, and waits again. It never answers a project question, edits
project files, delegates work, or interprets a peer message beyond delivery
policy.

### 3.1 Proposed repository layout

```text
sideband/
├── pom.xml
├── src/
│   ├── main/java/com/moltenbits/sideband/
│   │   ├── SidebandCommand.java       # Picocli root command
│   │   ├── protocol/                  # entry and delivery value types
│   │   ├── journal/                   # public journal component boundary
│   │   │   └── internal/              # Markdown codec, parser, and lock
│   │   ├── recipient/                 # public recipient-state boundary
│   │   │   └── internal/              # atomic JSON cursor store
│   │   ├── routing/                   # public routing boundary
│   │   │   └── internal/              # first-token directive parser
│   │   ├── waiting/                   # public blocking-wait boundary
│   │   │   └── internal/              # filesystem polling implementation
│   │   └── command/                   # Picocli subcommands
│   └── test/java/com/moltenbits/sideband/
│       ├── unit/
│       ├── integration/
│       └── acceptance/
├── skills/
│   ├── sideband-claude/
│   │   ├── SKILL.md
│   │   └── references/protocol.md
│   └── sideband-codex/
│       ├── SKILL.md
│       └── references/protocol.md
├── distribution/
│   ├── install.sh                 # macOS/Linux bootstrap
│   ├── install.ps1                # Windows bootstrap
│   └── package-release.sh         # binary, skills, manifest, checksums
└── .github/workflows/
    └── native-release.yml         # supported OS/architecture build matrix
```

The Java packages follow package-by-component boundaries: only component
interfaces and protocol value types are public; implementations remain
package-private beneath their owning component. Micronaut wires implementations
to interfaces at compile time. The application is a CLI only and includes no
HTTP server, database, runtime classpath scanning, or reflection-based
serialization.

There is no generic host dispatcher inside the executable. `claude` and `codex`
are protocol role values passed through `--role` or `--via`, not selections of
different implementations. The two `SKILL.md` files are the host adapters and
both invoke the same `sideband` command on `PATH`. This keeps growth linear: a
future host adds an adapter skill and a protocol role, not another journal tool
or branches throughout the storage implementation.

### 3.2 Single-tool installation boundary

A Sideband release contains exactly one native executable for its target
OS/architecture, both skill directories, a version manifest, and checksums. The
bootstrap installer selects the matching release artifact and performs one
idempotent installation:

```text
~/.local/bin/sideband
<claude-skill-root>/sideband/SKILL.md
<codex-skill-root>/sideband/SKILL.md
```

Re-running the installer upgrades the single executable and refreshes both skill
definitions. It must never install a private executable inside either skill.
The skills declare their compatible tool version and verify it with
`sideband version --json` during activation. Installing or upgrading one client
therefore cannot leave Claude and Codex using different protocol tools.

The release workflow builds and tests one artifact per supported platform,
records its SHA-256 checksum, and tests the archive in a clean environment. A
download service is used only to install or upgrade Sideband; normal operation
is fully local and offline.

## 4. On-disk state

Resolve the state root on every invocation with:

```bash
git rev-parse --path-format=absolute --git-common-dir
```

Do not derive it from the worktree's `.git` path. Normalize the command output,
then create this layout:

```text
<git-common-dir>/sideband/
├── config.json
├── journal.md
├── journal.lock
├── cursors/
│   ├── claude.json
│   └── codex.json
└── diagnostics/
```

Create the state directory with mode `0700` and regular state files with mode
`0600`. On platforms that cannot enforce POSIX modes, report that permissions
could not be tightened but continue; version one already assumes trusted local
filesystem access.

`diagnostics/` contains only parser and recovery reports. It must not contain
copies of message bodies because those may be sensitive.

### 4.1 Local configuration

`config.json` has a deliberately small schema:

```json
{
  "schema": 1,
  "human": {
    "id": "james",
    "display_name": "James"
  }
}
```

On first activation, propose values derived from `git config user.name` and ask
for correction only when they are absent or ambiguous. The identifier must match
`[a-z0-9][a-z0-9._-]*`; the display name is presentation-only. Neither value is
embedded in a skill or inferred independently by each client.

### 4.2 Recipient cursor

Each client owns one cursor file. It is recipient-local state, not another
communication channel:

```json
{
  "schema": 1,
  "role": "codex",
  "sessions": {
    "current": {
      "id": "8ccadbe2-...",
      "started_at": "2026-09-02T17:00:00-05:00",
      "watermark_id": "98fb7e8c-...",
      "watermark_end": 4821
    }
  },
  "entries": {
    "98fb7e8c-...": {
      "seen_at": "2026-09-02T17:00:01-05:00",
      "delivered_at": null,
      "resolved_at": null,
      "resolution": null
    }
  }
}
```

The stored fields distinguish three separate facts:

- `seen_at`: included in a backlog summary or shown to the parent.
- `delivered_at`: successfully inserted into the parent conversation.
- `resolved_at` and `resolution`: acted on, dismissed, or consumed by the
  originating turn.

Pending is derived as an addressed entry without `resolved_at`. A live
actionable message is marked delivered after the parent handoff and resolved
only after the parent completes or explicitly dismisses it. An informational
message can be resolved as `presented` immediately after successful delivery.

Cursor mutations use write-to-temp, `fsync`, and atomic rename while holding the
shared Sideband lock. Unknown cursor fields are preserved, allowing compatible
extensions.

## 5. Journal protocol

### 5.1 Entry encoding

Use the required Markdown shape and add two compatible metadata fields:
`body_bytes` and `body_sha256`.

```markdown
<!-- sideband:v1
{"id":"550e8400-e29b-41d4-a716-446655440000","created_at":"2026-09-02T16:42:00-05:00","from":"human:james","via":"claude","to":["codex"],"type":"instruction","route":"direct","reply_to":null,"caused_by":null,"expects_reply":true,"delivery":{"live":"auto","backlog":"confirm"},"body_bytes":35,"body_sha256":"..."}
-->

## James → Codex (via Claude)

@codex review the locking behavior.
<!-- /sideband -->
```

`body_bytes` is the UTF-8 byte count of the exact body. A reader therefore does
not mistake a literal `<!-- /sideband -->` line inside a Markdown message for
the entry terminator. `body_sha256` detects torn or externally modified bodies
without making authenticity claims.

Use UUIDv4 IDs. Physical order, not the UUID or timestamp, defines journal
order. Timestamps come from `datetime.now().astimezone()` and always include an
RFC 3339 offset.

The heading is generated presentation. Metadata is authoritative for routing
and identity. Display names are stripped of newlines and other control
characters before use in headings.

### 5.2 Parser

Implement a byte-oriented state machine rather than a regular expression over
the entire file:

1. Find an exact opener at the beginning of a line.
2. Read and parse the single JSON metadata line.
3. Validate the metadata schema and supported protocol major version.
4. Locate the generated heading/body boundary.
5. Read exactly `body_bytes` bytes.
6. Verify the SHA-256 digest and exact closing marker.
7. Emit the entry with its start and end byte offsets.

If metadata is malformed, a version is unsupported, a body digest fails, or an
entry is incomplete, emit a structured diagnostic and search for the next exact
opener. Earlier valid entries remain available. An incomplete trailing entry is
not emitted until it becomes complete.

Unknown metadata fields are retained on decode and ignored by version-one
behavior. Unknown values of required enums are invalid and skipped rather than
guessed.

### 5.3 Serialized append and recovery

The append algorithm is:

1. Validate and encode the complete entry in a temporary file inside the
   Sideband directory.
2. Flush and `fsync` the temporary file.
3. Acquire `journal.lock` with `O_CREAT | O_EXCL`.
4. Rescan the journal tail while holding the lock.
5. If the tail contains an incomplete prior write, append an explicit abort
   delimiter that closes any open HTML comment and identifies the fragment as
   invalid. Do not truncate or rewrite existing bytes.
6. Open `journal.md` with append semantics and copy the complete encoded entry.
7. Flush and `fsync` the journal.
8. Close it, remove the lock only if its random ownership token still matches,
   and delete the temporary file.

The lock file contains JSON with a random token, PID, process-start fingerprint,
hostname, and acquisition time. On the same host, a contender checks both PID
liveness and the start fingerprint to avoid PID-reuse mistakes. It never steals
from a verified live process, regardless of lock age. A dead or mismatched owner
is claimed by atomically renaming the stale lock before retrying acquisition.
Foreign-host ownership is reported instead of stolen because multi-machine use
is outside version-one scope.

This guarantees serialized writers and preserves all bytes from prior complete
entries. A crash can leave only one incomplete trailing fragment, which readers
skip and the next writer closes without editing history.

## 6. Toolkit command contract

All commands emit machine-readable JSON to stdout and human-readable errors to
stderr. Exit codes are stable: `0` success, `2` invalid input, `3` unsupported
location or protocol, `4` lock contention, and `5` corrupt state or I/O failure.

```text
sideband init
sideband capture-human --via claude --body-file <path>
sideband append-agent --from claude --to codex --type request \
  --caused-by <id> --body-file <path>
sideband activate --role codex --session-id <id>
sideband backlog --role codex --session-id <id>
sideband wait --role codex --session-id <id>
sideband mark-seen --role codex <id>...
sideband mark-delivered --role codex <id>...
sideband resolve --role codex --as acted|dismissed|presented|originating-turn <id>...
sideband pending --role codex
sideband version --json
sideband doctor --role codex
```

Message bodies enter through files or stdin, never interpolated into shell
source or passed as a single command-line argument. This avoids quote expansion,
argument-size limits, and accidental command execution. Temporary request files
live below the private Sideband directory and are removed after a successful
append.

`capture-human` applies routing only to the first non-whitespace token and keeps
the body unchanged. It accepts only the known directives. A missing directive
routes to `via`; `@all` writes one entry with both roles. For a direct human
message addressed to its `via` role, the append result identifies that role as
already consumed by the originating turn. The adapter immediately records
`originating-turn`, and the deterministic `from`/`via` rule also prevents later
self-redelivery if that cursor update was interrupted.

`wait` is one-shot. It blocks in a low-frequency stat loop until one or more new
complete addressed entries exist, emits that batch, and exits. The transport
worker handles the batch and invokes `wait` again. No standalone Sideband daemon
survives the client session, and the model consumes no tokens while the native
command is blocked.

## 7. Race-safe activation and delivery

### 7.1 Startup watermark

`activate` acquires the shared lock, parses through the last complete entry,
stores its ID and ending byte offset as the session watermark, and releases the
lock. It then returns all unresolved addressed entries at or before that offset
as backlog.

The transport worker starts its first `wait` from the stored byte offset. An
entry appended after the watermark but before the blocking wait begins is found
by the wait's mandatory initial scan and is classified as live. Thus every
entry is either at/before the watermark (backlog) or after it (live); there is no
scan/watch gap in which it can disappear.

Only one current session may exist per role. Starting a second listener for the
same role reports the existing session rather than silently replacing it. A
stale session can be superseded after its process/session ownership is shown to
be dead.

### 7.2 Delivery envelope

The client adapter sends the parent a structured preamble followed by the
verbatim body:

```text
[Sideband message]
id: 550e8400-e29b-41d4-a716-446655440000
author: human:james
via: claude
type: instruction
expects_reply: true
already_journaled: true
reply_to: null
caused_by: null

@codex review the locking behavior.
```

The skills treat `already_journaled: true` as an invariant: never run routing
parsing or append the envelope as a new original message. Before acting, the
parent checks the ID against delivered/resolved state and IDs already present in
its conversation. Duplicate envelopes may be acknowledged but must not repeat
work.

After the host's parent-message operation succeeds, the worker calls
`mark-delivered`. If the worker crashes between those two operations, the entry
may be delivered again, which is the intentional at-least-once failure mode.
The stable envelope ID makes the retry idempotent at the parent.

### 7.3 Live entries

- `expects_reply: true`: deliver immediately. The parent may act under the live
  `auto` policy, subject to the direct human's authority and current permissions.
- `expects_reply: false`: deliver as context and resolve as `presented`; do not
  create a task or response merely because the message arrived.
- Unknown delivery policies: report and leave pending.

Agent-to-agent messages are collaboration input. The receiving skill explicitly
states that they cannot expand scope, permissions, or authority granted by the
human.

### 7.4 Backlog entries

On activation, the parent receives a compact table grouped into:

- actionable entries (`expects_reply: true`), and
- informational replies/statuses (`expects_reply: false`).

Each row includes a short ID, author, timestamp, type, and one-line escaped
preview. The adapter marks the listed items seen, then asks the human to act on
all, select IDs, show bodies, dismiss IDs, or leave them pending. No actionable
backlog body is inserted as an instruction until approved. Informational items
may be displayed for awareness and resolved as presented only after display.

A deferred item is not automatically raised again in that session. `pending`
shows it on demand, and the next activation summarizes it again.

## 8. Client skills

### 8.1 Shared skill responsibilities

Both `SKILL.md` files must instruct their host to:

1. Run `init` and `activate` once for the current parent session.
2. Capture each direct human turn before substantive work while Sideband is
   active.
3. Act on an originating direct/broadcast turn without injecting it back into
   the same conversation.
4. Start exactly one transport worker.
5. Apply backlog confirmation before delivering actionable backlog bodies.
6. Recognize Sideband envelopes and preserve their author and ID.
7. Journal delegations separately with `caused_by`.
8. Journal participating final replies with `reply_to`.
9. Surface all helper, parser, listener, and parent-delivery failures.
10. Stop the worker when the parent session ends.

Neither skill should contain its own journal parser, lock implementation, or
routing logic.

### 8.2 Claude Code adapter

The Claude skill is installed as a Claude-compatible `SKILL.md` and invoked as
`/sideband`. Its adapter should:

- use Claude's current background-agent or Monitor facility to own the transport
  loop;
- have the worker run only `sideband wait`, parent delivery, and state commands;
- use a session-scoped prompt hook when available to pass the exact human body to
  `capture-human`, while retaining the skill-instruction path as the portable
  fallback;
- deliver entries to the original parent conversation, never answer them in the
  worker; and
- report a stopped background task so the parent can restart it from the durable
  cursor.

The optional hook is a capture improvement, not a protocol dependency. Explicit
skill activation remains the version-one lifecycle boundary.

### 8.3 Codex adapter

The Codex skill is installed as an Agent Skill and invoked as `$sideband`. Its
adapter should:

- spawn a dedicated background subagent named for the Sideband listener;
- pass the parent task/thread identity and the generated Sideband session ID to
  that worker;
- have the worker block in `sideband wait`, send each returned envelope through
  Codex's native parent follow-up messaging, mark successful delivery, and loop;
- keep all interpretation and project work in the parent; and
- reuse/restart the same listener identity instead of creating a worker per
  message.

This design uses the existing interactive subagent channel documented by
[OpenAI's Codex subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
and packages the workflow using the documented
[Codex skill format](https://learn.chatgpt.com/docs/build-skills). It does not
call `codex exec`, resume a headless session, or create a competing conversation.

The first implementation spike must prove one vertical path—append in Claude,
wake the Codex parent, acknowledge delivery—against the supported host versions.
If a host cannot wake its parent through a background worker, that is a release
blocker rather than a reason to add a proxy or headless CLI fallback.

## 9. Provenance and reply rules

The adapter maintains a current causality context:

- Direct human turn: append `from: human:<id>` and `via: <host>`.
- Agent delegation: append a new `from: <host>` request and set `caused_by` to
  the human instruction that led to it.
- Direct answer: append `from: <host>`, `type: reply`, and `reply_to` the entry
  being answered.
- Progress that another participant needs: append `type: status`, generally with
  `expects_reply: false`.
- Listener lifecycle or disposition notices: use `type: control` only when they
  belong in the shared conversation. Local cursor changes do not create journal
  entries.

Only participant-visible content is a message body. System/developer prompts,
private reasoning, tool calls, command output, permission state, credentials,
and hook payload metadata are never copied into the journal.

## 10. Testing strategy

Develop the protocol and each bug fix test-first. The shared toolkit should have
deterministic clocks and ID generators injected at its boundary so tests can
assert exact journal bytes.

### 10.1 Unit tests

- First-token routing with whitespace, case variants, missing directives, and
  directive-like text later in the body.
- Metadata validation, unknown-field tolerance, and invalid-enum rejection.
- Bodies containing headings, HTML comments, the closing marker, no final
  newline, Unicode, and invalid UTF-8 input.
- Cursor transitions, repeated transitions, and invalid state regressions.
- Lock ownership, dead owners, live owners, PID reuse, and foreign hosts.
- Heading sanitization and local identity validation.

### 10.2 Integration tests

- Multiprocess concurrent appends over many iterations; every entry must parse,
  remain contiguous, and appear exactly once in physical order.
- Forced writer termination at each append stage; prior entries remain valid and
  a later writer recovers the trailing fragment append-only.
- Entry creation before activation, during activation, between activation and
  wait, and while wait is blocked; none may be lost.
- Crash after parent handoff but before `mark-delivered`; retry emits the same ID
  and the simulated parent performs work once.
- Atomic cursor updates under interruption.
- Two worktrees resolving the same absolute Sideband directory.
- File modes under a restrictive and a permissive process umask.

### 10.3 Adapter contract tests

Use fake host drivers implementing `deliver_to_parent` and `session_alive`.
Run the same suite against Claude and Codex adapters:

- exactly one listener per role/session;
- live actionable delivery;
- informational delivery without manufactured work;
- backlog confirmation and selection;
- defer, inspect, dismiss, and later activation;
- originating-client broadcast suppression;
- delivered-envelope loop prevention;
- correct `reply_to` and `caused_by` provenance; and
- visible failure when the helper or parent channel fails.

Static tests also validate both skill frontmatters and verify that every packaged
skill declares compatibility with the single packaged executable version.

### 10.4 Requirements acceptance suite

Create one named test for each scenario in section 14 of `REQUIREMENTS.md`:

```text
test_direct_human_instruction
test_cross_client_human_instruction
test_live_broadcast
test_offline_broadcast_recipient
test_deferred_backlog
test_agent_delegation
test_concurrent_writers
test_shared_state_across_worktrees
test_restart_and_replay_is_idempotent
```

The suite should operate on real temporary Git repositories and worktrees rather
than mocking `git rev-parse`.

## 11. Implementation sequence

1. Build the value types, routing parser, journal codec, and malformed-entry
   diagnostics with unit tests.
2. Add shared-directory discovery, secure initialization, locking, serialized
   append, and concurrent/crash integration tests.
3. Add cursor transitions, activation watermarking, backlog queries, blocking
   wait, and restart/replay tests.
4. Implement a fake-host adapter and make the full requirements acceptance suite
   pass without either real client.
5. Write the Claude skill and validate capture, background delivery, and restart
   behavior in an interactive Claude session.
6. Write the Codex skill and validate the same adapter contract in an interactive
   Codex session.
7. Add the GraalVM Native Image build, platform release matrix, checksummed
   installer, installation documentation, `doctor`, and a two-client manual
   smoke test across separate worktrees.

Each step should be a small, independently passing commit on the eventual
implementation branch. The two real adapters belong to the same version-one
task and release; one is not a follow-up substitute for the other.

## 12. Risks and explicit safeguards

- **Parent wake-up APIs vary by client version.** Pin and test minimum supported
  Claude Code and Codex versions. Fail activation clearly when the required
  interactive background channel is absent.
- **Skills are behavioral integration, not guaranteed interception.** Explicit
  activation makes the boundary visible. Claude can use a session hook when
  available; Codex must capture through the active skill instructions until it
  exposes an equivalent prompt hook. Audit-grade capture remains out of scope.
- **A local process can forge entries.** Restrictive permissions reduce
  accidental exposure but do not provide authentication, as required.
- **At-least-once creates a handoff/ack gap.** Keep stable IDs visible in every
  envelope and make the parent deduplicate before acting.
- **Native artifacts are platform-specific.** Build and test an explicit release
  matrix, select artifacts by normalized OS/architecture, and fail installation
  rather than falling back to a JVM or scripting runtime.
- **The executable and skills can become version-incompatible.** Install them as
  one release, declare the required tool version in both skills, and fail
  activation visibly when `sideband version --json` is incompatible.
- **Native distribution introduces trust warnings.** Publish checksums from the
  release workflow and add platform signing/notarization where the supported
  operating system requires it.
- **Polling can create unnecessary wake-ups.** Poll only inside the native
  blocking command, use a modest interval with backoff, and perform no model work
  until the command returns a complete addressed entry.
- **Journal growth eventually makes full scans expensive.** Version one values a
  simple recovery model. Add an optional derived index only after measurement;
  it must always be rebuildable from `journal.md`.

## 13. Definition of done

Version one is complete when:

- one versioned native `sideband` executable is installed and both skills invoke
  that exact executable;
- neither skill contains or installs a private copy of the executable;
- all unit, integration, adapter-contract, and requirements acceptance tests
  pass;
- concurrent writers and forced crashes cannot corrupt prior complete entries;
- the Claude-to-Codex and Codex-to-Claude live paths work without MCP, a daemon,
  a hosted service, or headless peer CLI invocations;
- offline messages require human confirmation before action;
- restart/replay can redeliver but cannot cause duplicate work for one message
  ID;
- separate worktrees share one private journal; and
- `sideband doctor` reports paths, permissions, protocol versions, cursor health,
  lock ownership, and listener state without printing message bodies.

## 14. Review addenda

Same convention as section 16 of `REQUIREMENTS.md`: each reviewer appends an
entry in the journal entry shape and never edits an existing one. A response is
a new entry with `reply_to` set.

<!-- sideband:v0
{"id":"impl-0001","created_at":"2026-09-02T22:30:00-05:00","from":"claude","model":"claude-fable-5-1","via":"claude","to":["codex","human:james"],"type":"request","route":"broadcast","reply_to":null,"caused_by":"human:james review request","expects_reply":true}
-->

### Claude Fable 5.1 → Codex + James (via Claude)

Review of the revised implementation document (Micronaut CLI compiled with
GraalVM Native Image, single `sideband` executable on `PATH`). Items are
numbered so a response can reference them individually.

#### 14.1 The runtime and packaging decision is accepted

One native executable shared by both skills is an improvement over the earlier
draft, which vendored a copy of the toolkit into each skill and needed a sync
check to keep them identical. Version skew between the two skills is no longer
possible by construction. Java also covers the hard protocol pieces directly:
create-new semantics for the lock file, process liveness and start time via
`ProcessHandle`, and durable writes via `FileChannel.force`. James has
confirmed native image is the intended distribution model, startup cost is not
a concern, and no JVM-only fallback is wanted. No further discussion needed on
this point.

#### 14.2 Build tool is Gradle

Section 3.1 shows a `pom.xml`. James uses Gradle exclusively. Replace with a
Gradle build (Kotlin DSL unless he says otherwise) using the Micronaut Gradle
plugin, which also provides the `nativeCompile` task for the native image
build.

#### 14.3 Drop Windows, notarization, and the release matrix from version one

James is not touching Windows, and Sideband is not distributed through the App
Store, Homebrew, or any channel that requires signing or notarization. Remove
`install.ps1`, the notarization risk item, and the multi-platform release
matrix from the version-one plan rather than leaving them as planned work.
Version one needs a build for the developer's own OS and architecture and an
idempotent local install that places the executable on `PATH` and links both
skills. A release workflow can be added when there is a second machine to
install on.

#### 14.4 Stale Python references

Section 5.1 still says timestamps come from `datetime.now().astimezone()`.
Replace with the Java equivalent (`OffsetDateTime.now()` or
`ZonedDateTime.now()` formatted as RFC 3339). The test names in section 10.4
are snake_case Python style; they should follow the project's Java test naming
convention.

#### 14.5 `wait` needs a timeout

The command contract in section 6 has no way for `wait` to give up. Addendum
16.8 of the requirements makes "send a request and block waiting for the reply"
a supported workflow, which requires a bounded wait. Add a `--timeout` option;
on expiry, exit with a distinct code and leave the request pending rather than
failed. The transport worker can use no timeout or a long one; a sender waiting
on a specific `reply_to` uses a short one.

#### 14.6 The spike must prove both directions

Section 8.3 says the first spike proves one vertical path, append in Claude and
wake the Codex parent. Requirements addendum 16.1 requires both directions,
because the Claude-side wake path (a background task or Monitor re-invoking the
parent) and the Codex-side path (subagent parent follow-up messaging) are
different mechanisms with different failure modes. Neither can be assumed from
the other.

#### 14.7 Requirements addenda not yet reflected

The document predates section 16 of the requirements and disagrees with it in
these places. Each should be resolved once Codex has reviewed that section:

- 16.4: `to` accepts only client roles. The human must be a valid recipient so
  an agent can request input or report completion. Delivery to the human is
  satisfied by the authoring client's own visible turn; no listener forwards it.
- 16.5: there is no loop prevention beyond the `already_journaled` invariant.
  Add the rule that an agent-to-agent entry with `expects_reply: true` must
  trace to a human entry through `caused_by`, and a hop cap after which an
  entry is delivered under the `confirm` policy regardless of its live policy.
  Both are checks the executable can enforce at append and at delivery, not
  skill instructions.
- 16.10: `type: control` is used in section 9 without defined semantics.
  Either specify what a recipient does with one or remove it from version one.
- 16.3: section 7.1 already refuses a second listener per role, which matches
  the single-instance decision. Add a sentence noting that `from` and `to`
  stay plain role names in version one so a per-instance suffix can be added
  compatibly later.
- 16.9: section 4.2 describes `delivered_at` as "successfully inserted into the
  parent conversation." Reword to "successfully handed off to the host's
  parent-wake mechanism," since the worker cannot observe more than that.

#### 14.8 Skill install locations should be stated concretely

Section 3.2 uses placeholder paths for the skill roots. For version one these
are known: Claude Code reads user skills from `~/.claude/skills/<name>/SKILL.md`
and Codex from its equivalent user skills directory. The installer should name
them and verify them with `doctor`, since a mislinked skill is the most likely
first-run failure.

<!-- /sideband -->

<!-- sideband:v0
{"id":"impl-0002","created_at":"2026-09-03T00:00:00-05:00","from":"claude","model":"claude-fable-5-1","via":"claude","to":["codex","human:james"],"type":"request","route":"broadcast","reply_to":"impl-0001","caused_by":"human:james test stack decision","expects_reply":true}
-->

### Claude Fable 5.1 → Codex + James (via Claude)

#### 14.9 Test stack is Spock and Groovy; main code stays Java

Decision from James: all application code under `src/main` is Java. All tests
under `src/test` are written in Groovy using the Spock framework. Section 3.1
should show `src/test/groovy/...` rather than `src/test/java/...`, and section
10 should assume Spock specifications throughout: data-driven `where:` blocks
for the routing and metadata validation cases, and Spock's interaction-based
mocking for the fake host drivers in the adapter contract tests. The Gradle
build applies the Groovy plugin for the test source set only, with the Spock
BOM for version alignment. Micronaut's Spock test support (`micronaut-test-spock`)
can be used where a test needs the application context, though most protocol
tests should not need one. Test names in section 10.4 become Spock feature
method names in sentence form, for example
`"direct human instruction is journaled with via and to set to the originating client"`.

<!-- /sideband -->
