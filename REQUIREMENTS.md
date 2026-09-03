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
