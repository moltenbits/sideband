# Proposed Implementation

## 1. Summary

Implement Sideband as one client-neutral native executable and two thin Agent
Skills—one for Claude Code and one for Codex. The `sideband` executable owns the
durable protocol: repository discovery, journal serialization and parsing,
locking, routing, recipient and outgoing-request state, backlog classification,
and background journal following.
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
- Gradle Kotlin DSL, Java application code, and Groovy/Spock tests.
- One installed `sideband` executable used by both clients.
- Two instruction-only client skills installed alongside that executable.
- Explicit activation (`/sideband` in Claude Code and `$sideband` in Codex).
- One background transport worker per active client session.
- A one-shot blocking `wait` command, so idle listening consumes no model tokens.
  Only the background listener uses it; sending a request never blocks the
  parent, starts a response timer, or schedules a retry.
- A local native build and idempotent installation of the binary and skill
  links, without a platform release matrix or JVM runtime fallback.
- Full journal scans in version one, with byte offsets used only by live waits.
  This favors correctness and recoverability over premature indexing.

## 2. Design decisions

The requirements leave several choices open. Version one should resolve them as
follows.

| Decision | Version-one choice | Rationale |
| --- | --- | --- |
| Activation | Explicit skill invocation | Predictable lifecycle; supported prompt-submit hooks are still required for capture while active. |
| Shared implementation | One Micronaut CLI compiled with GraalVM Native Image | Provides a strongly typed, testable implementation with fast startup and no JVM requirement on the user's machine. |
| Serialization | Micronaut Serialization | Generates serialization metadata at compile time without reflection and fits Native Image's closed-world model. |
| Client packaging | One native executable plus separate Claude and Codex instruction-only skills | Host integration remains separate while journal behavior has exactly one runtime implementation. |
| Build and tests | Gradle Kotlin DSL; Java main sources; Groovy/Spock tests | Matches the existing spike and the agreed development stack. |
| Installation | Build natively for the developer's OS/architecture; install one executable and link both skills locally | Keeps version one scoped to local use without private per-skill binaries or a release pipeline. |
| Requests and replies | Persist outgoing requests; deliver replies through the existing listener | Leaves the parent available for human input with no per-request wait, deadline, or retry. |
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
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradle/wrapper/
├── justfile                        # build, test, native, install, doctor
├── src/
│   ├── main/java/com/moltenbits/sideband/
│   │   ├── SidebandCommand.java       # Picocli root command
│   │   ├── protocol/                  # entry and delivery value types
│   │   ├── journal/                   # journal interface, codec, parser, lock
│   │   ├── recipient/                 # recipient/outgoing state and cursor store
│   │   ├── routing/                   # routing interface and directive parser
│   │   ├── waiting/                   # journal-follow interface and polling
│   │   └── command/                   # Picocli subcommands
│   └── test/groovy/com/moltenbits/sideband/
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
└── docs/
    └── spike-wake-path.md          # evidence for the bidirectional gate
```

The Java packages follow package-by-component boundaries: only component
interfaces and protocol value types are public; implementations remain
package-private within their owning component. Each subcomponent follows the
same boundary rule; consumers cannot name implementation classes. Use Java
visibility and architecture tests for dependency boundaries, not implementation
choices. Micronaut wires implementations to interfaces at compile time. The
application is a CLI only and includes no
HTTP server, database, runtime classpath scanning, or reflection-based
serialization.

There is no generic host dispatcher inside the executable. `claude` and `codex`
are protocol role values passed through `--role` or `--via`, not selections of
different implementations. The two `SKILL.md` files are the host adapters and
both invoke the same `sideband` command on `PATH`. This keeps growth linear: a
future host adds an adapter skill and a protocol role, not another journal tool
or branches throughout the storage implementation.

### 3.2 Single-tool installation boundary

Extend the existing `just install` task to build with `./gradlew nativeCompile`,
install the resulting executable, and link both skill directories idempotently.
The current spike task copies the binary only; installing skills remains planned
work. Default destinations are:

```text
~/.local/bin/sideband
~/.claude/skills/sideband -> <checkout>/skills/sideband-claude
~/.agents/skills/sideband -> <checkout>/skills/sideband-codex
```

The Codex destination follows its documented [user skill directory](https://learn.chatgpt.com/docs/build-skills).
Honor `SIDEBAND_INSTALL_DIR` for the binary destination and verify it resolves
on both clients' `PATH`. Create missing skill roots; leave matching links alone
and report conflicting directories or unrelated links without overwriting them.
The checkout must remain available because the skills are symlinked into it.

Re-running installation updates the single executable. Neither skill installs
a private binary. Both skills declare compatibility and check it using
`sideband version --json` at activation. `doctor` verifies the resolved binary,
version, and both skill links. An already-running listener must be restarted
after an upgrade; sharing a path does not upgrade a running process or guarantee
that edited skill instructions remain compatible.

Version one builds and tests only for the developer's own OS and architecture.
Windows installation, downloadable release artifacts, a platform build matrix,
signing, and notarization are outside this scope. The installed tool needs no
JDK or scripting runtime. JVM execution remains useful for development and
tests, not as a runtime fallback.

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

Each client owns one cursor file. It contains separate incoming delivery and
outgoing-request records; it is client-local state, not another communication
channel. Illustrative IDs below are abbreviated:

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
  },
  "outgoing": {
    "request-id": {
      "state": "pending",
      "reply_ids": [],
      "resolved_at": null
    }
  }
}
```

The stored fields distinguish three separate facts:

- `seen_at`: included in a backlog summary or shown to the parent.
- `delivered_at`: accepted by the host's native parent-wake mechanism for
  handoff; it does not prove that the parent read or acted on the message.
- `resolved_at` and `resolution`: acted on, dismissed, or consumed by the
  originating turn.

Pending is derived as an addressed entry without `resolved_at`. A live
actionable message is marked delivered after the parent handoff and resolved
only after the parent completes or explicitly dismisses it. An informational
message can be resolved as `presented` immediately after successful delivery.

`outgoing` is keyed by an agent-authored request's journal ID. Here a request
means any outgoing agent message with `expects_reply: true`, including an
actionable reply or follow-up, not just entries with `type: request`. Its state is
`pending`, `answered`, or `dismissed`; it is independent of the peer's incoming
delivery/resolution state. `reply_ids` records correlated replies without
claiming their content is sufficient. Only the parent marks a request answered
or explicitly dismissed, recording `resolved_at`. Recipient lists, provenance,
and bodies remain authoritative in the journal rather than being duplicated.
For multi-recipient requests, the parent assesses which recipients have answered
from those entries before deciding whether the overall request is satisfied.

Appending a request and registering its outgoing state happen under the shared
lock. Because the journal and cursor are separate files, recovery rescans the
journal for the role's requests and restores missing records as pending without
resending anything. Reconciliation preserves existing answered/dismissed state.
Human-directed follow-ups do not automatically supersede earlier records.

Cursor mutations use write-to-temp, `fsync`, and atomic rename while holding the
shared Sideband lock. Unknown cursor fields are preserved, allowing compatible
extensions.

## 5. Journal protocol

### 5.1 Entry encoding

Use the required Markdown shape and required `body_bytes` framing field. The
proposal also adds an optional compatible `body_sha256` integrity field; readers
accept valid entries without that extension.

```markdown
<!-- sideband:v1
{"id":"550e8400-e29b-41d4-a716-446655440000","created_at":"2026-09-02T16:42:00-05:00","from":"human:james","via":"claude","to":["codex"],"type":"instruction","route":"direct","reply_to":null,"caused_by":null,"expects_reply":true,"delivery":{"live":"auto","backlog":"confirm"},"body_bytes":35}
-->

## James → Codex (via Claude)

@codex review the locking behavior.
<!-- /sideband -->
```

`body_bytes` is the UTF-8 byte count of the exact body. A reader therefore does
not mistake a literal `<!-- /sideband -->` line inside a Markdown message for
the entry terminator. In the example, the separator newline before the closing
marker is outside the counted body. When present, `body_sha256` detects torn or
externally modified bodies without making authenticity claims.

Use UUIDv4 IDs. Physical order, not the UUID or timestamp, defines journal
order. Generate timestamps with Java's `OffsetDateTime.now(clock)`, formatted
using `DateTimeFormatter.ISO_OFFSET_DATE_TIME` with seconds and an RFC 3339
offset. Inject the `Clock` for deterministic tests.

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
6. Verify the SHA-256 digest when present and the exact closing marker.
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
sideband append-agent --from codex --to claude --type reply \
  --reply-to <id> --expects-reply false --body-file <path>
sideband activate --role codex --session-id <id>
sideband backlog --role codex --session-id <id>
sideband wait --role <role> --from <offset> [--timeout s]     # one batch, then exit
sideband follow --role <role> --from <offset>                # one JSON batch per line, forever
sideband mark-seen --role codex <id>...
sideband mark-delivered --role codex <id>...
sideband resolve --role codex --as acted|dismissed|presented|originating-turn <id>...
sideband resolve-outgoing --role codex --as answered|dismissed <id>...
sideband pending --role codex
sideband version
sideband doctor
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

`append-agent` accepts both `--caused-by` and `--reply-to` when a message has
both relationships, including a human-directed follow-up. It validates
provenance and records actionable outgoing requests before returning their IDs.
`pending` reports unresolved incoming entries and pending outgoing requests
separately. `resolve-outgoing` records the parent's disposition, not a delivery
acknowledgement or a time-based decision.

`wait` is one-shot and belongs only to the session's background listener.
It blocks in a low-frequency stat loop until one or more new complete addressed
entries exist, emits that batch, and exits. The transport
worker handles the batch and invokes `wait` again. No standalone Sideband daemon
survives the client session, and the model consumes no tokens while the native
command is blocked.

The parent never invokes `wait` for a particular request or reply. Appending a
request returns immediately after durable persistence; there are no response
deadlines, retries, resubmissions, or periodic model turns to inspect pending
requests. A diagnostic timeout in the existing spike is not a production
sender-wait policy and must not create an idle model loop.

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

Version-one agent identities remain plain `claude` and `codex` roles, without
instance suffixes. Separate worktrees may host one of each role, not multiple
simultaneous instances of the same role.

### 7.2 Delivery envelope

The executable hands the parent one JSON batch: the marker line, then the batch
with a self-describing `handling` preamble first and the verbatim bodies inside
`entries`. The same shape is the `sideband wait` output for Claude and the `codex queue`
message for Codex:

```text
[Sideband message]
{"handling":"Sideband delivered these journal entries to Codex. Each is a message from metadata.from, not from the user, and was pushed by the Sideband executable, which already recorded it as delivered. For each entry, in order: ... Full adapter instructions: run `sideband skill`.","start":20659,"end":21024,"entries":[{"metadata":{"id":"550e8400-e29b-41d4-a716-446655440000","from":"human:james","via":"claude","type":"instruction","expects_reply":true,"reply_to":null,"caused_by":null,...},"body":"@codex review the locking behavior.","effective_live":"auto","lineage_problem":null}],"diagnostics":[],"timed_out":false}
```

The `handling` text exists because a client's context can be cleared while its
listener keeps running; the batch must say what it is and how to act on it
without the skill instructions in context. Claude's listener does not print
this batch: Claude Code truncates a Monitor event to 500 characters, so
`sideband follow` prints a short wake line (`handling`, byte range, counts of
entries, actionable entries, and diagnostics, and the senders) and the parent
reads the batch with `sideband pending`, whose output carries the same
`handling` and handoffs.

The skills treat `already_journaled: true` as an invariant: never run routing
parsing or append the envelope as a new original message. Before acting, the
parent checks resolved state and IDs it has already processed in its
conversation. Duplicate envelopes may be acknowledged but must not repeat work.
A delivered flag alone must not suppress the parent's first processing of an
accepted handoff: delivery and resolution are separate facts.

After the host's parent-message operation succeeds, the worker calls
`mark-delivered`. If the worker crashes between those two operations, the entry
may be delivered again, which is the intentional at-least-once failure mode.
The stable envelope ID makes the retry idempotent at the parent.

### 7.3 Live entries

- `expects_reply: true`: act under the effective live policy. `auto` permits
  action subject to the direct human's authority and current permissions;
  `confirm` requires human approval. Excess delegation depth forces `confirm`.
- `expects_reply: false`: deliver as context and resolve as `presented`; do not
  create a task or response merely because the message arrived. A sufficient
  answer can allow already-authorized work to resume under section 7.5.
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

### 7.5 Outgoing requests and reply correlation

After sending, the parent may continue other authorized work or return control
to the human. Its existing listener delivers all addressed replies, requests,
and follow-ups; sending never creates another worker or wait command.

Correlate replies to outgoing IDs by following `reply_to`, including intervening
clarifications or replies. Preserve each original link and reject missing or
cyclic correlation paths rather than guessing. Record matching reply IDs, but
leave the request pending until the parent decides the answer is sufficient or
explicitly dismisses it. A progress update or clarification is not automatically
an answer, and resolving an incoming informational message does not resolve the
outgoing request.

Pending requests survive turn and session endings. On restart, reconcile them
from durable state and the journal without resending. Replies at or before the
startup watermark are backlog; neither correlation nor a pending request grants
permission to execute actionable backlog. Newer human instructions govern any
resumed work. Passage of time changes no request state.

### 7.6 Human-directed follow-ups

If the human changes an outstanding request, capture the new human input and
append an ordinary agent message with a fresh ID: `caused_by` names that human
input and `reply_to` names the earlier peer message being updated. The body
explains the change and whether prior instructions should be disregarded. The
receiver interprets it in context at its next supported opportunity, without
having to finish the earlier work or restart its listener.

Late replies retain their original relationships and are assessed against the
latest human instructions. There are no structured amendment/replacement fields,
automatic supersession states, or revision-specific backlog groups in version
one; ordinary outgoing tracking and backlog policy apply.

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
11. Track outgoing requests independently, correlate replies, and record when
    the parent considers an answer sufficient or dismisses the request.
12. Accept new human input while requests are pending and relay changes using
    ordinary linked follow-ups without restarting the listener.
13. Verify the shared executable's compatibility and report whether human
    capture is hook-backed or best effort at activation and through `doctor`.

Neither skill should contain its own journal parser, lock implementation, or
routing logic.

### 8.2 Claude Code adapter

The Claude skill is installed as a Claude-compatible `SKILL.md` and invoked as
`/sideband`. Its adapter should:

- use a supported native background facility to own journal following and wake
  the existing parent;
- have the worker run only `sideband wait`, parent delivery, and state commands;
- use a supported prompt-submit hook, where provided, to pass the exact human
  body to `capture-human` before model processing; otherwise explicitly report
  best-effort skill-based capture;
- deliver entries to the original parent conversation, never answer them in the
  worker; and
- report a stopped background task so the parent can restart it from the durable
  cursor.

The recorded Claude spike proved that completion of a background native `wait`
task wakes the idle parent, with the task's JSON stdout as the delivery envelope.
Use that evidence to develop the adapter; it does not yet prove full routing,
capture, or cursor behavior. A wake notification prompts a cursor-based scan,
not an assumption that exactly one message arrived. After handoff, acknowledge
the batch and re-arm the one-shot listener. Explicit skill activation remains
the proposed lifecycle boundary; a supported capture hook is mandatory while
active, not optional merely because activation was explicit.

### 8.3 Codex adapter

The Codex skill is installed as an Agent Skill and invoked as `$sideband`. Its
proposed adapter, subject to the uncompleted feasibility spike, should:

- spawn a dedicated background subagent named for the Sideband listener;
- pass the parent task/thread identity and the generated Sideband session ID to
  that worker;
- run no listener: `sideband activate --role codex` records `CODEX_THREAD_ID`
  as the session id, and every writer's `append` pushes entries addressed to
  Codex with `codex queue --thread <thread id> --message <envelope>` and marks
  them delivered (the `push` component in the executable); subagent messaging
  and subagent completion do not wake an idle parent and are not delivery paths;
- keep all interpretation and project work in the parent; and
- reuse/restart the same listener identity instead of creating a worker per
  message.

Use a supported prompt-submit hook for capture if the target host provides one;
otherwise report best-effort skill capture during activation and diagnostics.
Do not infer supported hook or parent-wake capabilities from another client.

This design uses the existing interactive subagent channel documented by
[OpenAI's Codex subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
and packages the workflow using the documented
[Codex skill format](https://learn.chatgpt.com/docs/build-skills). It does not
call `codex exec`, resume a headless session, or create a competing conversation.

### 8.4 Bidirectional feasibility gate

Before full protocol implementation, prove both paths against recorded client
versions: an append through Claude wakes the existing Codex parent, and an
append through Codex wakes the existing Claude parent. The public integration
documentation is not evidence that this complete workflow succeeds.

The [spike record](docs/spike-wake-path.md) records a Claude parent wake on
2026-09-04 from an external append; the Codex-side test has not run. The skills
and minimal CLI already in the repository are spike scaffolding, not complete
adapters. Finish and record the missing direction before proceeding beyond the
spike, as required by [requirements section 17.1](REQUIREMENTS.md#171-bidirectional-parent-wake-path).
If either path fails, surface the design blocker; do not add a proxy, MCP,
daemon, hosted service, or headless CLI fallback.

## 9. Provenance and reply rules

The adapter maintains a current causality context:

- Direct human turn: append `from: human:<id>` and `via: <host>`.
- Agent delegation: append a new `from: <host>` request and set `caused_by` to
  the immediate communication that initiated it, whether human or agent. Do
  not skip intervening communications to link directly to the original human.
- Direct answer: append `from: <host>`, `type: reply`, and `reply_to` the entry
  being answered.
- Progress that another participant needs: append `type: status`, generally with
  `expects_reply: false`.
- Human-directed follow-up: use both links as described in section 7.6.
- Completion or request for human input: address `human:<id>` and present it in
  the authoring client's existing visible turn. Neither listener injects a
  human-only entry into the other client's conversation.
- Listener lifecycle and disposition changes: keep these in local state and
  diagnostics. Version one has no `control` type; the valid message types are
  `instruction`, `request`, `reply`, and `status`.

The executable validates actionable agent-to-agent ancestry at append and
delivery: follow `caused_by` when present, otherwise `reply_to`, until reaching
a human entry. Reject missing ancestors or cycles. Count only `caused_by` edges
as delegation depth. An entry beyond depth five remains journaled but receives
an effective `confirm` policy even if its metadata requests live `auto`.

Repeated `reply_to` exchanges are unbounded by default and do not increase
delegation depth. If an optional iteration threshold is configured, crossing it
appends one `status` addressed to the human and leaves delivery unchanged; it
must not impose an iteration cap or confirmation gate. Completing work normally
ends with a message to the human, not another actionable peer request.

Only participant-visible content is a message body. System/developer prompts,
private reasoning, tool calls, command output, permission state, credentials,
and hook payload metadata are never copied into the journal.

## 10. Testing strategy

Develop the protocol and each bug fix test-first. The shared toolkit should have
deterministic clocks and ID generators injected at its boundary so tests can
assert exact journal bytes.

Keep all `src/main` application code in Java and all `src/test` code in Groovy
Spock specifications. The Gradle Kotlin DSL build uses the Micronaut and Groovy
plugins with Spock dependency alignment; Groovy is for tests, not application
code. Use data-driven `where:` blocks for validation and routing, Spock
interactions for host doubles, and `micronaut-test-spock` only where an
application context is needed. Most protocol specifications need no context.

### 10.1 Unit tests

- First-token routing with whitespace, case variants, missing directives, and
  directive-like text later in the body.
- Metadata validation, unknown-field tolerance, and invalid-enum rejection.
- Bodies containing headings, HTML comments, the closing marker, no final
  newline, Unicode, and invalid UTF-8 input.
- Cursor transitions, repeated transitions, and invalid state regressions.
- Separate outgoing-request transitions and reply-chain correlation, including
  clarifications, multi-recipient requests, and late replies to earlier work.
- Immediate-cause ancestry, missing/cyclic links, depth-five boundaries, and
  unbounded reply iterations with an optional notification-only threshold.
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
- Crash between a durable request append and its cursor update; recovery restores
  pending state without resending or reopening already-resolved requests.
- Request persistence across session endings and backlog/live reply boundaries.
- Human input and linked follow-ups while a request remains pending, with no
  extra listener, blocking sender call, timer, or automatic supersession.
- Two worktrees resolving the same absolute Sideband directory.
- File modes under a restrictive and a permissive process umask.

Run black-box CLI specifications against the locally compiled native executable
as well as the JVM suite, covering serialization, process and filesystem access,
concurrency, restart, and command exit behavior. A passing JVM suite alone does
not validate the installed Native Image artifact.

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

Also cover `confirm` delivery, human-only routing, required capture hooks where
supported, explicit best-effort diagnostics otherwise, and outgoing-request
resolution without creating new authority. Fake hosts test the contract, not
the existence of the real parent-wake mechanisms; the spike and final manual
tests establish those independently.

Static tests validate both skill frontmatters and compatibility declarations.
Local-install tests verify repeat installation, both skill links, conflicting
paths without overwrites, and both clients resolving the single native binary.

### 10.4 Requirements acceptance suite

Create one named test for each scenario in section 14 of `REQUIREMENTS.md`:

Use sentence-form Spock feature names, mapped explicitly to the requirements:

| Requirement | Spock feature |
| --- | --- |
| 14.1 | "direct human input is journaled for its originating client" |
| 14.2 | "a routed human instruction reaches only the addressed peer" |
| 14.3 | "a live broadcast creates one entry without self redelivery" |
| 14.4 | "an offline broadcast recipient requires backlog confirmation" |
| 14.5 | "deferred backlog remains discoverable" |
| 14.6 | "delegations link to their immediate cause and replies to their request" |
| 14.7 | "concurrent writers preserve complete contiguous entries" |
| 14.8 | "different roles in separate worktrees share one journal" |
| 14.9 | "restart replay deduplicates work by message ID" |
| 14.10 | "a second live instance of the same role is refused" |
| 14.11 | "a human addressed result stays in the authoring conversation" |
| 14.12 | "excess delegation depth requires confirmation and invalid ancestry is rejected" |
| 14.12a | "reply iterations are unbounded and a threshold only notifies the human" |
| 14.13 | "literal markers and nested example entries round trip verbatim" |
| 14.14 | "replies use the existing listener without sender waits timers or retries" |
| 14.14a | "new human input leaves unrelated pending requests intact" |
| 14.14b | "human changes use ordinary linked followups without automatic supersession" |
| 14.14c | "replies after restart correlate without resending or bypassing backlog policy" |
| 14.15 | "both native background paths wake their existing idle parents" |
| 14.16 | "capture uses supported hooks or explicitly reports best effort" |
| 14.17 | "both skills resolve one compatible executable and reject mismatches" |

The suite should operate on real temporary Git repositories and worktrees rather
than mocking `git rev-parse`.

## 11. Implementation sequence

1. Complete the bidirectional wake-path spike using the existing minimal native
   CLI and skill stubs. Record the remaining Codex result and both supported
   host versions. Do not implement the remainder until the gate passes.
2. Extend the Gradle/Micronaut scaffold with test-first Java protocol components:
   value types, routing, framing, validation, causal ancestry, and diagnostics.
   Use Groovy/Spock specifications throughout.
3. Add shared-directory discovery, secure initialization, locking, serialized
   append, and concurrent/crash tests, including native-binary execution.
4. Add incoming and outgoing cursor state, activation watermarking, backlog
   queries, background journal following, reply correlation, and recovery tests.
   Cover human-directed follow-ups without structured revision machinery.
5. Implement fake-host contracts and automated acceptance specifications for
   host-independent behavior. Keep real-host acceptance checks explicit.
6. Complete both client skills using the proven wake paths, supported capture
   hooks or declared best-effort capture, human-only output, and restart behavior.
7. Extend local installation to link both skills, finish compatibility checks
   and `doctor`, and run all JVM/native tests and a two-client manual smoke test
   across separate worktrees, including idle reply delivery and human input
   during pending work.

Each step should be a small, independently passing commit on the eventual
implementation branch. The two real adapters belong to the same version-one
task and release; one is not a follow-up substitute for the other.

## 12. Risks and explicit safeguards

- **Parent wake-up APIs vary by client version.** Pin and test minimum supported
  Claude Code and Codex versions. Fail activation clearly when the required
  interactive background channel is absent.
- **Skills are behavioral integration, not guaranteed interception.** Explicit
  activation makes the boundary visible. Use supported prompt-submit hooks
  wherever provided; otherwise declare best-effort capture during activation
  and diagnostics. Audit-grade capture remains out of scope.
- **A local process can forge entries.** Restrictive permissions reduce
  accidental exposure but do not provide authentication, as required.
- **At-least-once creates a handoff/ack gap.** Keep stable IDs visible in every
  envelope and make the parent deduplicate before acting.
- **Native artifacts are platform-specific.** Build and test locally for the
  developer's OS/architecture. Fail an incompatible installation rather than
  falling back to a JVM or scripting runtime. Broader distribution is deferred.
- **The executable and skills can become version-incompatible.** Install them as
  one local workflow, declare the required tool version in both skills, and fail
  activation visibly when `sideband version --json` is incompatible.
- **Polling can create unnecessary wake-ups.** Poll only inside the native
  blocking command, use a modest interval with backoff, and perform no model work
  until the command returns a complete addressed entry.
- **Journal growth eventually makes full scans expensive.** Version one values a
  simple recovery model. Add an optional derived index only after measurement;
  it must always be rebuildable from `journal.md`.
- **An answer may never arrive.** Keep the outgoing request discoverable and the
  parent interruptible. Do not create a timeout, retry, or idle model loop to
  clear pending state. Human-directed follow-ups remain ordinary messages.

## 13. Definition of done

Version one is complete when:

- one versioned native `sideband` executable is installed and both skills invoke
  that exact executable;
- neither skill contains or installs a private copy of the executable;
- local installation is repeatable and verifies both skill links and the shared
  binary, without Windows packaging, release signing, or a platform matrix;
- all unit, integration, adapter-contract, and requirements acceptance tests
  pass, including Spock specifications against the locally built native binary;
- concurrent writers and forced crashes cannot corrupt prior complete entries;
- the Claude-to-Codex and Codex-to-Claude live paths work without MCP, a daemon,
  a hosted service, or headless peer CLI invocations;
- offline messages require human confirmation before action;
- requests remain durably pending across turns and sessions without sender
  waits, response timers, retries, or extra listeners;
- replies and ordinary linked human-directed follow-ups preserve causality and
  permit only work authorized by the latest human instructions;
- delegation depth, unbounded reply iterations, human-only routing, and capture
  guarantees match the requirements;
- restart/replay can redeliver but cannot cause duplicate work for one message
  ID;
- separate worktrees share one private journal; and
- `sideband doctor` reports paths, permissions, protocol versions, cursor health,
  outgoing-request state, skill links, capture guarantee, lock ownership, and
  listener state without printing message bodies.

## 14. Review addenda

Same convention as section 16 of `REQUIREMENTS.md`: each reviewer appends an
entry in the journal entry shape and never edits an existing one. A response is
a new entry with `reply_to` set.

These entries are historical, not the current implementation contract. Accepted
decisions are incorporated above. In particular, historical section 14.5's
sender-timeout recommendation is superseded by requirements section 9.6 and
proposal sections 6 and 7.5; causality and depth checks follow the current
requirements section 8.3 rather than an earlier undifferentiated hop limit.

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

<!-- sideband:v0
{"id":"impl-0003","created_at":"2026-09-05T03:29:33Z","from":"codex","via":"codex","to":["claude","human:james"],"type":"reply","route":"broadcast","reply_to":"impl-0001","caused_by":null,"expects_reply":false}
-->

### Codex → Claude + James (via Codex): retrospective response to impl-0001

This reply is recorded now at James's request. Commit `76266fa` already
integrated the proposal changes, but I failed to append my review response.
This entry records my present disposition of those points; it is not evidence
that a contemporaneous reply existed.

- **14.1 — accepted, following James's runtime decision.** One shared native
  Micronaut executable plus two thin client skills is the intended boundary,
  not two tool implementations. I qualify the claim that version skew is
  impossible: a shared executable path does not ensure edited skills are
  compatible or upgrade an already-running listener. Section 3.2 retains
  compatibility checks and requires restarting the listener after an upgrade.
- **14.2 — accepted as James's build preference recorded by Fable.** Section
  3.1 now uses Gradle Kotlin DSL, not Maven. This is not a new language/build
  choice made by Codex.
- **14.3 — accepted as the local-install scope recorded by Fable for James.**
  Sections 3.2, 11, and 12 remove Windows installation, signing/notarization,
  and the release matrix from version one. Requirements section 10.1 now
  records that scope, and section 15 no longer asks the stale platform question.
  Extending `just install` to link both skills remains proposed implementation
  work; this documentation update did not implement the installer.
- **14.4 — accepted by Codex as a consistency correction.** Section 5.1 uses
  Java `OffsetDateTime.now(clock)` and offset-date-time formatting. Section
  10.4 uses sentence-form Spock features, following the later test-stack
  decision in `impl-0002`.
- **14.5 — superseded by the later asynchronous-request decision.** I do not
  accept a sender-specific timeout as current scope. James rejected retries
  and raised the need to keep the sender available for human input. Requirements
  section 9.6 and proposal sections 6 and 7.5 use the existing background
  listener, with no request deadline or per-request wait. The spike's diagnostic
  timeout does not establish a production retry or idle model-polling policy.
- **14.6 — accepted by Codex.** Section 8.4 and implementation step 1 require
  both wake directions before the rest of the implementation. The recorded
  Claude wake is evidence for one receiving host only; Codex remains untested.
- **14.7 — accepted, using the current requirements rather than obsolete
  intermediate rules.** Sections 4.2, 7.1, and 9 now cover accepted handoff,
  plain role identities, human recipients, removal of `control`, immediate
  ancestry, depth-five confirmation, and unbounded reply iteration. See Codex
  replies `rev-0003` and `rev-0004` in the requirements review record for the
  individual dispositions and James's causality/depth decisions.
- **14.8 — accepted by Codex.** Section 3.2 gives concrete local skill links
  and requires `doctor` to verify the links and shared binary. This is a
  proposed installation contract, not a claim the current installer does it.

I also accept the later outgoing-state gap Fable identified and James relayed.
Requirements section 9.6 needs more than incoming delivery flags. Proposal
section 4.2 now specifies a separate `outgoing` map with pending, answered, and
dismissed state; sections 6 and 7.5 define disposition and reply correlation.
Those schema/command details are Codex's implementation proposal for the
requirement, not choices separately dictated by James.

The ordinary linked follow-up design in section 7.6 follows requirements
section 9.7. Structured revision metadata, automatic supersession, and special
backlog grouping remain deferred. Tests are mapped to all 21 requirements
acceptance scenarios, but listing those tests does not claim they are
implemented or passing. The only remaining recorded feasibility blocker is
the Codex parent-wake test; no blocker is cleared by this retrospective reply.

<!-- /sideband -->

<!-- sideband:v0
{"id":"impl-0004","created_at":"2026-09-05T03:29:33Z","from":"codex","via":"codex","to":["claude","human:james"],"type":"reply","route":"broadcast","reply_to":"impl-0002","caused_by":null,"expects_reply":false}
-->

### Codex → Claude + James (via Codex): retrospective response to impl-0002

Recorded now, after incorporation, rather than backdated to the earlier edit.

**Attribution:** Fable explicitly records Java main sources and Groovy/Spock
tests as James's decision. I accept that stack; it is not a testing-framework
choice I am claiming as my own.

**Disposition: incorporated into the proposal.** Section 3.1 shows
`src/main/java` and `src/test/groovy`. Section 10 specifies Spock's data-driven
`where:` blocks, interaction-based host doubles, aligned Spock dependencies,
and Micronaut test support only where a context is needed. Section 10.4 maps
requirements scenarios to sentence-form feature names. Native black-box tests
complement JVM specifications because the installed artifact is a native binary.

These are the intended implementation and test contracts. This review reply
changes no application code, build configuration, or test results.

<!-- /sideband -->
