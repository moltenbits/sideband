# Sideband Requirements

Status: Initial requirements derived from the product-design discussion.

## 1. Purpose

Sideband provides local, durable, tridirectional communication among a human,
Claude Code, and Codex. It replaces the live AgentBridge proxy with a shared,
append-only journal that each client reads and writes directly.

Sideband is primarily delivered as a skill for each supported client. It must
not require an MCP server, an intermediary daemon, a hosted service, or
non-interactive invocations such as `claude -p` or `codex exec resume`.

## 2. Design principles

- The journal is the authoritative record of Sideband communication.
- The raw journal remains pleasant for a human to read.
- Human authorship, the client through which a message entered, and the
  intended recipients are separate concepts.
- Live messages and messages accumulated while a client was offline have
  different execution semantics.
- Delivery is at least once. Stable message identifiers make processing
  idempotent.
- Client-specific integration stays behind a shared protocol.
- Version one favors a small, local implementation over premature support for
  networks, multiple machines, or many simultaneous sessions.

## 3. Terminology

- **Human**: the person using Claude Code or Codex.
- **Client**: a supported interactive agent host, initially Claude Code or
  Codex.
- **Author**: the participant that composed a message.
- **Via**: the client in which a human entered a message.
- **Recipient**: a client expected to receive a message.
- **Live message**: a message appended after a recipient's listener became
  ready for the current session.
- **Backlog message**: an addressed message already present when a recipient's
  session established its startup watermark.
- **Journal**: the append-only Markdown file containing the shared history.

## 4. Version-one scope

Version one supports:

- One local Git repository.
- One active Claude Code session and one active Codex session per repository.
- A single append-only `journal.md` shared by both clients.
- Human-to-agent, agent-to-agent, and agent-to-human entries.
- Direct and broadcast routing.
- Immediate handling of live messages.
- Human-approved handling of backlog messages.
- Text and Markdown message bodies.

## 5. Storage

### 5.1 Location

Sideband state must live beneath Git's shared private metadata directory, as
resolved by:

```bash
git rev-parse --path-format=absolute --git-common-dir
```

For a normal checkout this resolves to its `.git` directory. For additional
Git worktrees it resolves to the same common Git directory used by the primary
checkout. Consequently, every worktree sees the same Sideband conversation,
while Sideband state remains absent from `git status` and cannot be committed
or pushed accidentally.

The initial layout is:

```text
<git-common-dir>/sideband/
├── journal.md
├── cursors/
│   ├── claude.json
│   └── codex.json
└── journal.lock
```

Supporting non-Git directories is not required for version one.

### 5.2 Persistence boundaries

- Cloning the repository elsewhere does not transfer its Sideband journal.
- Removing the repository's Git metadata also removes its journal.
- Sideband must never place the journal in tracked repository content by
  default.
- The Sideband state directory and its contents should be private to the local
  user.

## 6. Journal

### 6.1 General format

`journal.md` is a single append-only Markdown document. Each entry consists of:

1. Machine-readable JSON metadata inside a Markdown-compatible HTML comment.
2. A human-readable Markdown heading.
3. A Markdown message body.
4. An explicit closing marker.

For example:

```markdown
<!-- sideband:v1
{"id":"019a","created_at":"2026-09-02T16:42:00-05:00","from":"human:james","via":"claude","to":["claude","codex"],"type":"instruction","route":"broadcast","reply_to":null,"caused_by":null,"expects_reply":true,"delivery":{"live":"auto","backlog":"confirm"}}
-->

## James → Claude + Codex (via Claude)

@all independently review the proposed database migration.

<!-- /sideband -->
```

The JSON is visible in the raw file but hidden by normal Markdown rendering.
The heading and body remain readable in both forms.

### 6.2 Required metadata

Every entry must include:

- `id`: a globally unique, stable message identifier.
- `created_at`: an RFC 3339 timestamp including an offset.
- `from`: the actual author, such as `human:james`, `claude`, or `codex`.
- `to`: a non-empty array of intended client roles.
- `type`: initially `instruction`, `request`, `reply`, `status`, or `control`.
- `route`: `direct` or `broadcast`.
- `expects_reply`: whether recipients should treat the entry as actionable.
- `delivery.live`: the delivery policy for live messages.
- `delivery.backlog`: the delivery policy for backlog messages.

The following fields are conditional:

- `via`: required for human-authored messages and identifies the client where
  the human entered the message.
- `reply_to`: identifies the message to which this is a direct response.
- `caused_by`: identifies an earlier instruction that led to a delegation or
  other derived message.

Additional metadata may be introduced compatibly. Readers must ignore unknown
fields.

### 6.3 Immutability

- Existing entries must never be edited or deleted.
- Corrections, acknowledgements, and state changes are represented by later
  entries or recipient-local state.
- Physical append order is the canonical journal order.
- Readers must not process an entry until its closing marker is present.
- A malformed or incomplete trailing entry must not prevent processing earlier
  valid entries.

## 7. Human participation and provenance

### 7.1 Human messages

The human is a first-class Sideband participant, not an implicit part of the
client through which they communicate.

When Sideband is active, a direct human prompt should be recorded separately
with:

- The human as `from`.
- The receiving client as `via`.
- The resolved routing destination in `to`.
- The original human text as the body.

The body should be copied without paraphrasing. Routing metadata must remain
separate from authorship so a later delegation is not falsely attributed to
the human.

### 7.2 Delegation

If Claude or Codex translates a human instruction into a request for the other
agent, it must append a separate agent-authored entry and set `caused_by` to the
human message's identifier. For example:

```text
James → Claude: review the change and ask Codex to test concurrency
Claude → Codex: independently test the concurrency behavior
```

This preserves both the original instruction and the agent's interpretation.

### 7.3 Included and excluded content

Sideband records participant-visible communication relevant to the shared
conversation. It must not record:

- System or developer instructions.
- Hidden client context.
- Private reasoning.
- Routine tool calls or raw tool output.
- Permission internals or authentication material.

Text entered directly by the human is eligible for verbatim recording. Binary
attachments and audit-grade byte-for-byte prompt capture are outside the
version-one scope.

### 7.4 Inbound message identity

A Sideband-delivered message may appear to a client as a new input turn. The
delivery envelope must preserve its actual Sideband author and message ID.
Recipients must never relabel a delivered Claude or Codex message as a new
human message.

## 8. Routing

### 8.1 Human routing directives

The first non-whitespace token of a direct human message may be one of these
case-insensitive routing directives:

| Directive | Recipients |
| --- | --- |
| None | Only the client where the message was entered |
| `@claude` | Claude Code |
| `@codex` | Codex |
| `@all` | Claude Code and Codex |

A token elsewhere in the message is ordinary content and must not alter
routing. For example, asking "What does `@all` mean?" must not accidentally
broadcast the prompt unless `@all` is its first non-whitespace token.

The original body, including any routing directive, remains in the journal.

### 8.2 Broadcasts

- `@all` creates one journal entry with both clients in `to`; it must not create
  duplicate per-recipient entries.
- The client through which the broadcast entered is recorded in `via`.
- The originating client acts on the human's existing turn and must not inject
  a second copy into its own conversation.
- Other recipients receive the journal entry through their listeners.
- A broadcast targets all supported client roles even if one is offline. An
  offline recipient handles it under the backlog policy when it returns.

### 8.3 Loop prevention

Delivered entries must carry an internal Sideband envelope. A recipient must
not interpret a routing directive inside an already journaled message as a new
instruction to republish that entry. A message ID may be delivered more than
once, but it may be journaled as an original message only once.

### 8.4 Isolation boundary

Routing is logical, not a security boundary. A client must ignore entries that
do not address it, but both clients can technically read the shared journal.
True recipient confidentiality would require separate journals or encryption
and is not a version-one requirement.

## 9. Delivery behavior

### 9.1 Default policy

The version-one default is:

```json
{
  "live": "auto",
  "backlog": "confirm"
}
```

### 9.2 Live delivery

When an addressed entry is appended after the recipient's listener is ready:

- The recipient should receive it promptly.
- An actionable entry may be handled without an additional backlog approval.
- A non-actionable reply or status entry should be presented as context and
  must not manufacture additional work.
- The recipient records successful delivery so duplicate notifications do not
  cause duplicate work.

### 9.3 Startup watermark

When a client activates Sideband, it must establish a watermark at the last
complete entry already present in the journal. Addressed unresolved entries at
or before that watermark are backlog. Entries appended after the listener is
ready are live.

This boundary must be race-safe: an entry may be classified as backlog or live,
but it must not be lost between the initial scan and listener startup.

### 9.4 Backlog handling

A returning client must not silently execute its backlog. It must summarize the
pending entries and ask the human whether to:

- Act on every actionable entry.
- Act on selected entries.
- Show full message bodies before deciding.
- Dismiss selected or all entries.
- Leave selected or all entries pending.

The summary should distinguish actionable requests from informational replies
and statuses. Informational entries may be summarized for awareness but must
not be presented as pending work when `expects_reply` is false.

### 9.5 Recipient state

Each client must distinguish:

- **Seen**: included in a backlog summary or otherwise presented.
- **Resolved**: successfully acted upon or explicitly dismissed.
- **Pending**: neither resolved nor dismissed, including work the human chose
  to defer.

Dismissing a message updates recipient-local state and never removes the
journal entry. Deferred messages remain discoverable without necessarily
interrupting every subsequent turn.

## 10. Client integration

### 10.1 Shared responsibilities

Each client-specific Sideband skill must:

1. Locate the repository's Sideband state directory.
2. Initialize it safely when absent.
3. Read and classify unresolved entries for that client.
4. Apply backlog confirmation rules.
5. Start no more than one listener for its parent session.
6. Deliver new addressed entries to the parent conversation.
7. Append participant messages using the shared writer.
8. Maintain recipient state and deduplicate by message ID.
9. Surface listener, parse, and write failures rather than silently losing
   messages.

The background processor is a transport worker. It should forward messages to
the parent client rather than independently answering substantive project
questions with stale or incomplete parent context.

### 10.2 Claude Code

The Claude integration may use Claude Code's native Monitor capability to
watch the journal or a command that follows it. Monitor events should prompt a
scan from Claude's last recorded cursor rather than treating each filesystem
notification as exactly one message.

### 10.3 Codex

The Codex integration may maintain a background Sideband subagent that follows
the journal and uses native parent follow-up messaging to wake the parent
conversation when an addressed entry arrives. A non-model listener using
Codex's queue facility may be considered later if retaining a subagent proves
costly or unreliable.

### 10.4 Lifecycle

- The listener exists only while its client session is running.
- Idle waiting should not consume model tokens.
- Messages written while a client is absent remain durable in the journal.
- A returning client drains the backlog using the confirmation workflow.
- A stopped or failed listener must be restartable without losing or
  duplicating journal entries.

### 10.5 Activation

Hooks are not required for version one. Sideband may be activated through a
skill plus client instructions that initialize it on the first turn of a
session. Hooks or plugin startup facilities may be added later if deterministic
pre-turn activation and automatic crash recovery become necessary.

The exact choice between automatic first-turn activation and explicit skill
invocation remains open.

## 11. Writing and concurrency

### 11.1 Serialized appends

Claude and Codex can attempt to append concurrently. All writers must therefore
use the same serialized append mechanism.

A small shared helper should:

1. Validate required metadata.
2. Construct the complete entry in temporary storage.
3. Acquire `journal.lock` atomically.
4. Append the entry as one serialized operation.
5. Flush and close the journal.
6. Release the lock.

The helper is invoked only for journal operations. It is not a daemon, server,
proxy, or independent agent.

### 11.2 Failure behavior

- A writer crash must not interleave two entries.
- Readers must wait for a closing marker before delivering a new entry.
- Lock ownership must include enough information to detect and recover a stale
  lock without disrupting a live writer.
- A write failure must leave prior journal content intact.
- Malformed metadata must be reported and skipped, not interpreted
  heuristically as an instruction.

### 11.3 Delivery guarantees

- Sideband provides at-least-once rather than exactly-once delivery.
- Recipients must deduplicate using `id`.
- Recipient state advances only after successful delivery into the parent
  conversation or an explicit human disposition of a backlog entry.
- The originating client must not redeliver a human message it already
  received directly.

## 12. Authority and safety

- Direct human instructions have higher authority than agent-to-agent
  messages.
- An agent message cannot broaden the scope or permissions granted by the
  human.
- A client should treat peer-agent content as collaboration input, not as a
  replacement for system, developer, or direct human instructions.
- Version one assumes trusted local filesystem access. Any local process with
  write access could forge a journal entry; cryptographic authentication is
  outside scope.
- The journal may contain sensitive prompts or repository information and
  should use restrictive local permissions.

## 13. Version-one non-goals

Version one does not provide:

- A cloud service, WebSocket transport, or multi-machine synchronization.
- An AgentBridge-style proxy or other persistent intermediary service.
- MCP-based transport.
- Non-interactive client driving through `claude -p`, `codex exec`, or resume
  automation.
- A database or graphical user interface.
- Multiple simultaneous sessions of the same client role in one repository.
- Session-specific routing or presence heartbeats.
- Exactly-once delivery.
- Recipient confidentiality within the shared journal.
- Binary attachment storage.
- Full client transcripts, hidden prompts, tool logs, or private reasoning.
- Audit-grade guaranteed capture of every human input byte.

## 14. Acceptance scenarios

### 14.1 Direct human instruction

Given Sideband is active in Claude, when the human enters a message without a
routing directive, the journal records one human-authored entry with
`via: claude` and `to: [claude]`. Claude acts on the existing turn; Codex does
not act.

### 14.2 Cross-client human instruction

Given Sideband is active in Claude, when the human begins a message with
`@codex`, the journal records the human as author, Claude as `via`, and Codex as
the sole recipient. Codex receives the entry once through its listener.

### 14.3 Broadcast

Given both clients are live, when the human enters `@all` through either
client, the journal contains one broadcast entry naming both recipients and
the originating client. The originating client acts on its existing turn, and
the other client receives and acts on the journal entry without rebroadcasting
it.

### 14.4 Offline broadcast recipient

Given Codex is offline when an `@all` entry is appended, when Codex next starts,
it summarizes the unresolved broadcast as backlog and waits for the human's
choice before acting.

### 14.5 Deferred backlog

Given the human leaves a backlog item pending, Sideband preserves it as
unresolved without deleting or editing the original journal entry. The human
can later approve, inspect, or dismiss it.

### 14.6 Agent delegation

Given a human asks Claude to obtain an independent Codex review, the journal
contains the original human-to-Claude instruction and a separate
Claude-to-Codex request linked with `caused_by`.

### 14.7 Concurrent writers

Given Claude and Codex append at nearly the same time, both complete entries
appear in the journal without interleaving or corruption.

### 14.8 Worktrees

Given Claude and Codex operate from different worktrees of the same Git
repository, both resolve and use the same Sideband journal.

### 14.9 Restart and replay

Given a listener stops after an entry is written but before recipient state is
advanced, restarting Sideband may redeliver that entry, but message-ID
deduplication prevents duplicate work.

## 15. Open design decisions

The following questions remain intentionally unresolved:

- Whether Sideband activates automatically on every first turn or through an
  explicit skill command.
- The implementation language and packaging of the one-shot journal helper.
- How the local human identifier and display name are configured.
- Whether every visible agent-to-human response is journaled automatically or
  only responses participating in Sideband workflows.
- Whether a routing directive is removed from the delivered body while being
  retained verbatim in the journal.
- How deferred backlog is resurfaced without becoming noisy.
- Whether and how to support non-Git directories.
- Retention, archive, compaction, and export policies for very large journals.
- Future attachment representation.
- Future support for multiple sessions of the same client role.
- Whether later versions should add an optional cloud journal and WebSocket
  notification layer while retaining the same entry protocol.

## 16. Review addenda

This section is a crude, hand-maintained stand-in for the Sideband journal
itself. Each reviewer appends an entry below in the journal entry shape. Entries
are never edited after they are appended; a response is a new entry with
`reply_to` set. This lets the requirements be reviewed using the same
methodology the tool is meant to provide.

<!-- sideband:v0
{"id":"rev-0001","created_at":"2026-09-02T21:45:00-05:00","from":"claude","model":"claude-fable-5-1","via":"claude","to":["codex","human:james"],"type":"request","route":"broadcast","reply_to":null,"caused_by":"human:james review request","expects_reply":true}
-->

### Claude Fable 5.1 → Codex + James (via Claude)

Review of sections 1 through 15 as committed in `f5f0ad7`, plus decisions James
made in discussion afterward. Items are numbered so a response can reference
them individually.

#### 16.1 Parent wake path is a hard constraint, not an option

Sections 10.2 and 10.3 say each integration "may use" a background facility to
deliver entries into the parent conversation. Everything in the design depends
on this capability existing in both hosts without hooks, a daemon, or headless
CLI invocations. It should be stated as a hard requirement, and the first
deliverable of any implementation should be a spike that proves the path in
both directions: an append in Claude wakes the Codex parent, and an append in
Codex wakes the Claude parent. If either host cannot do this, that is a design
blocker to surface, not a reason to add a proxy or headless fallback.

#### 16.2 Human capture is unenforceable without a hook

Section 2 names the journal the authoritative record, but section 10.5 makes
hooks optional. Skill-only capture depends on the model remembering to journal
every prompt and parse the first token correctly, so it will be lossy. Where a
host offers a prompt-submit hook, the requirement should be to use it for
capture and directive parsing, since both then become deterministic. Where no
such hook exists, the document should say plainly that human capture is best
effort for that host rather than authoritative.

#### 16.3 One instance per role in version one; per-instance identity later

Section 5.1 wants every worktree to share one journal, and scenario 14.8 tests
it. Multiple worktrees are exactly the situation that produces two sessions of
the same role on one repository, which section 13 declares a non-goal.

Decision from discussion: version one supports at most one Claude Code instance
and one Codex instance per repository. Activating a second instance of a role
must be refused with a clear message rather than silently sharing or replacing
the first instance's cursor.

Noted for a later version: each instance becomes its own participant with a
two-level identity such as `claude:<instance>`, where the instance name is
human-readable and stable across restarts (a worktree or branch name is a good
default). Role-level addressing such as `@claude` fans out to every live
instance of that role; `reply_to` targets the specific instance that asked.
Cursors move from per-role to per-instance. An instance registry with a
retired state is needed so that backlog addressed to a deleted worktree is
reported as orphaned rather than pending forever. The section 13 non-goal on
session-specific routing and presence would be relaxed at that point. The
version-one format should keep `from` and `to` as plain role names so this can
be added compatibly.

#### 16.4 Agent-to-human entries need an addressing model

Section 4 lists agent-to-human entries as in scope, but section 6.2 defines
`to` as an array of client roles, and the human is not one. Section 8.1 has no
directive for addressing the human.

Proposed: the human is a valid recipient in `to`, using the same identifier
form as `from` (for example `human:james`). Delivery to the human is satisfied
by the authoring client's own visible turn; no listener forwards it. Other
clients treat a human-addressed entry as informational context. Because the
entry has an ID, the human can later answer it from either client using
`reply_to`. An agent may use this to request input or to report completion.

#### 16.5 Loop prevention covers republishing but not conversation loops

Section 8.3 prevents an entry from being republished. Nothing prevents Claude
and Codex from exchanging actionable replies indefinitely under the live
`auto` policy.

Proposed, in three parts:

- Convention: when an agent judges its part complete, it addresses the human
  (per 16.4) rather than the other agent. This is the normal terminal state
  and is equivalent to stopping.
- Rule: an agent-to-agent entry with `expects_reply: true` must trace back to
  a human-authored entry through its `caused_by` chain. Agents cannot
  originate work between themselves.
- Backstop: a maximum number of consecutive agent-to-agent hops since the last
  human-authored entry in the chain. The chain is already in the metadata, so
  the check is cheap. An entry exceeding the cap is journaled but delivered
  under the `confirm` policy regardless of its live policy.

#### 16.6 The entry format must be unambiguous for arbitrary bodies

Section 6.1 uses a literal closing marker. A body that itself contains that
marker, for example a message quoting the journal format, would terminate the
entry early. The requirement should state that the format must parse correctly
for any body, and leave the mechanism (a byte length, an escaped marker, or a
per-entry delimiter) to the implementation.

#### 16.7 Whether undirected human prompts are journaled

Section 7.1 records every direct human prompt while Sideband is active, which
includes prompts like "fix the typo" that have nothing to do with the shared
conversation. Section 15 raises this question for agent responses but not for
human prompts. The same question should be listed for human prompts, with the
tradeoff stated: completeness of the record versus journal noise and exposure
of prompts that were never meant for the other participant.

#### 16.8 A sender may block waiting for a reply

The document does not say whether an agent that sends a request may wait for
the answer within its current turn. A one-shot blocking wait run as a shell
command consumes no model tokens while blocked, which makes "ask Codex for a
review and wait for the result" a supported workflow rather than an
anti-pattern. This should be stated explicitly, together with the requirement
that such a wait has a timeout and that a timeout leaves the request pending
rather than failed.

#### 16.9 "Successful delivery" should be defined as handoff

Sections 9.2 and 11.3 advance recipient state on "successful delivery into the
parent conversation." A transport worker cannot observe whether the parent
model actually read an entry. Delivery should be defined as successful handoff
to the host's native mechanism for waking the parent. Whatever happens after
that is the parent's responsibility and is covered by ID deduplication.

#### 16.10 The `control` type has no defined semantics

Section 6.2 lists `control` as a valid type but nothing describes when it is
used or how a recipient treats it. Either define it or drop it from version
one.

#### 16.11 Implementation language remains open; Python is not a given

Section 15 leaves the helper's language and packaging open. James has stated
that Python was never the intent. The constraints that actually matter are
that the helper runs as a one-shot command from both a Claude Code skill and a
Codex skill, provides atomic file locking and JSON handling, and is present or
trivially installable on any machine that has both clients. Node satisfies
these and is guaranteed present wherever Claude Code runs. A compiled binary
satisfies them but is awkward to ship inside a skill bundle. Shell alone
should be ruled out because atomic locking and JSON parsing are exactly what it
does badly. This remains an open decision for James.

<!-- /sideband -->
