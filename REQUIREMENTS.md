# Sideband Requirements

Status: Version-one requirements with review addenda integrated.

## Table of contents

- [1. Purpose](#1-purpose)
- [2. Design principles](#2-design-principles)
- [3. Terminology](#3-terminology)
- [4. Version-one scope](#4-version-one-scope)
- [5. Storage](#5-storage)
- [6. Journal](#6-journal)
- [7. Human participation and provenance](#7-human-participation-and-provenance)
- [8. Routing](#8-routing)
- [9. Delivery behavior](#9-delivery-behavior)
- [10. Client integration](#10-client-integration)
- [11. Writing and concurrency](#11-writing-and-concurrency)
- [12. Authority and safety](#12-authority-and-safety)
- [13. Version-one non-goals](#13-version-one-non-goals)
- [14. Acceptance scenarios](#14-acceptance-scenarios)
- [15. Open design decisions](#15-open-design-decisions)
- [16. Review addenda](#16-review-addenda)
- [17. Remaining implementation blockers](#17-remaining-implementation-blockers)
- [18. Executable command contract](#18-executable-command-contract)
- [19. Definition of done](#19-definition-of-done)

## 1. Purpose

Sideband provides local, durable, tridirectional communication among a human,
Claude Code, and Codex. It replaces the live AgentBridge proxy with a shared,
append-only journal that each client reads and writes directly.

Sideband is delivered as one shared `sideband` tool plus a thin skill for each
supported client. Both skills must invoke the same installed tool; neither
skill may contain or install its own private copy. Sideband must not require an
MCP server, an intermediary daemon, a hosted service, or non-interactive
invocations such as `claude -p` or `codex exec resume`.

## 2. Design principles

- The journal is the authoritative record of Sideband communication that a
  client successfully captures. Section 7.1 defines the capture guarantee when
  a host does not expose a deterministic prompt-submit hook.
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
- **Recipient**: a participant addressed by a message: a client role, `claude`
  or `codex`, or the one human, `operator`.
- **Instance**: one active interactive session of a client role. Version one
  permits at most one instance of each role per repository.
- **Live message**: a message appended after the recipient's session
  watermark (section 9.3), so it is pushed into the running session.
- **Listener**: where this document says a role's listener delivers an entry,
  read the role's delivery path. Since 2026-09-06 that path is a push by the
  writer in both directions (sections 10.2 and 10.3); Claude runs a
  background listener only as the fallback when its host would not deliver
  pushes. The word survives in the workflow sections because the properties
  they state, one delivery path per role, no per-request wait, no timer, no
  retry, are unchanged.
- **Backlog message**: an addressed message already present when a recipient's
  session established its startup watermark.
- **Journal**: the append-only Markdown file containing the shared history.

## 4. Version-one scope

Version one supports:

- One local Git repository.
- One active Claude Code session and one active Codex session per repository.
- One shared native `sideband` executable used by both client skills.
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
├── sessions/
│   ├── claude.json
│   └── codex.json
└── journal.lock
```

Outside any Git repository, the state directory is `.sideband` in the
working directory itself, with the same contents.

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
{"id":"019a","created_at":"2026-09-02T16:42:00-05:00","from":"operator","via":"claude","to":["claude","codex"],"type":"request","route":"broadcast","reply_to":null,"caused_by":null,"expects_reply":true,"delivery":{"live":"auto","backlog":"confirm"},"body_bytes":58}
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
- `from`: the actual author: `operator`, `claude`, or `codex`.
- `to`: a non-empty array of intended participant identifiers: `claude`,
  `codex`, or `operator`. There is exactly one human per journal, so nothing
  about who they are is configured or recorded.
- `type`: `request`, `reply`, `status`, or `ack` (section 9.8). There is no
  separate human-only type: a human's prompt and an agent's delegation are
  both requests. A request defaults to actionable regardless of author, but
  `expects_reply` remains the authoritative switch: a request with it false
  is context only, and a reply with it true is actionable.
- `route`: `direct` or `broadcast`.
- `expects_reply`: whether recipients should treat the entry as actionable.
- `delivery.live`: the delivery policy for live messages.
- `delivery.backlog`: the delivery policy for backlog messages.
- `body_bytes`: the UTF-8 byte length of the exact message body.

The following fields are conditional:

- `via`: required for human-authored messages and identifies the client where
  the human entered the message.
- `reply_to`: identifies the immediate message to which this is a direct
  response, or the earlier peer message that a human-directed follow-up
  updates (section 9.7).
- `caused_by`: identifies the immediate communication that initiated a
  delegation or other derived message. That communication may be human- or
  agent-authored; this field must not skip intervening messages to point to
  the original human prompt.

Additional metadata may be introduced compatibly. Readers must ignore unknown
fields.

### 6.3 Unambiguous framing

The entry format must preserve and parse any text or Markdown body, including a
body that contains the literal `<!-- /sideband -->` closing marker or another
complete example entry. Readers must use `body_bytes` to locate the end of the
body and then validate the closing marker at the resulting boundary; they must
not search for the first marker-like line. Any separator newline added by the
writer is outside the counted body.

### 6.4 Immutability

- Existing entries must never be edited or deleted.
- Corrections, acknowledgements, and state changes are represented by later
  entries. Nothing about what was said or done lives outside the journal.
- Physical append order is the canonical journal order.
- Readers must not process an entry until its closing marker is present.
- A malformed or incomplete trailing entry must not prevent processing earlier
  valid entries.
- A reader is a byte-oriented scan for the exact opener at the start of a line,
  the single JSON metadata line, the heading, exactly `body_bytes` bytes of
  body, and the exact closing marker. Anything that fails that shape produces
  one diagnostic and the scan resumes at the next opener. Unknown metadata
  fields are ignored; an unknown value of a required enum is invalid and the
  entry is skipped, never guessed at.

## 7. Human participation and provenance

### 7.1 Human messages

The human is a first-class Sideband participant, not an implicit part of the
client through which they communicate.

When Sideband is active, every direct human prompt must be recorded separately,
including a prompt without a routing directive. Activating Sideband for a
session—whether explicitly or automatically—therefore means that local-only
prompts are part of the private journal as well as routed prompts. This favors
completeness of the record over reducing journal volume.

Where the host exposes a supported prompt-submit hook, the client integration
must use it to capture the body and resolve its first-token routing directive
deterministically before model processing. Where no such hook exists, the
integration must:

- perform capture through the active skill on a best-effort basis;
- state during activation that capture is best effort for that host; and
- expose the current capture guarantee through diagnostics.

The hook is one entry point in the shared executable, `sideband hook prompt`,
registered in each client as `--agent claude` or `--agent codex`. Both clients
use `UserPromptSubmit` with the same payload shape, and Codex gives hook
shells none of the environment markers other commands rely on
(`CODEX_THREAD_ID` for Codex; `CLAUDECODE` or `CLAUDE_CODE_SESSION_ID` for
Claude Code), so the registration names the client; the markers remain the
fallback when the flag is absent. Nothing else about the caller is examined:
the prompt belongs to whichever conversation holds the role, so a restarted
or cleared client captures without rejoining. The record does follow the
operator, though: a prompt the operator typed comes from the conversation
the operator is looking at, so when its session identifier differs from the
one recorded, the hook moves the role's address there and keeps everything
else (9.5). A delivered envelope or a host notice says nothing about where
the operator is and never moves anything. A
hook that cannot tell its client skips capture with a diagnostic. It then
reads the payload, journals the prompt as a request from the human through
that client, and answers in that client's response format. `sideband init` writes each registration, so
the installed hooks differ only in the client they name and updating the
executable updates both. A second entry point, `sideband hook session-start`,
is registered under each client's `SessionStart` event with the matcher
`clear`: a clear replaces the conversation on screen with a new one before
any prompt is typed, and the old conversation may live on inside the client,
where a push addressed to it would run unseen. The session-start hook moves
the joined role to the new conversation at once and tells it, through the
context field, that Sideband is live there and how many entries addressed
to it need attention, counting requests it acknowledged and has not yet
answered, since the ack is the one thing the new conversation has forgotten.
`init` places the handler under the `clear` matcher and moves one it finds
under any other matcher, where it would never fire; `doctor` reports a
handler anywhere else as stale. Other sources (`startup`, `resume`, `compact`) leave the
record alone: they keep the conversation the role is in, or are a new client
whose first prompt claims the role through the prompt hook. Registration for a client is added only once that client's hook
contract has been verified against its official documentation
(section 17.2).

A prompt that invokes the client's Sideband skill with text after it, such
as `/sideband @codex look at this`, is the operator's words typed as the
skill's argument: the hook records the text after the invocation and routes
it by its first token. The skill invoked alone or with one of its own words
(`help`, `status`, `pending`, `off`) is a command and is not captured.

The hook must never block a prompt, so it always exits successfully, and the
hook's context field is the shared, non-blocking channel both hosts show the
model. A prompt that should have been journaled and was not is therefore
reported in that field, with the reason, so the model tells the human rather
than the loss going to stderr where nobody reads it. The report says either
that recording could not be completed or confirmed, since a failure after the
write can leave the entry in place, or that the prompt was recorded under a
stated id and the push to its recipient failed. Reporting is the whole recovery: no
client records a prompt on the hook's behalf. Prompts that were never meant to be captured
(commands, delivered envelopes, blank input) and repositories where Sideband
is installed but not active stay silent, except that an inactive session
whose role has entries waiting is told how many, so nothing waits unread.

Each captured prompt is recorded with:

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
human message's identifier. When a peer message initiates further delegated
work, `caused_by` instead identifies that peer message. A direct response uses
`reply_to` to identify the message being answered. For example:

```text
H1  James → Claude: review the change and ask Codex to test concurrency
A1  Claude → Codex: independently test concurrency       caused_by: H1
A2  Codex → Claude: clarify the expected ordering        reply_to: A1
A3  Claude → Codex: here is the expected ordering        reply_to: A2
```

This preserves both the original instruction and the agent's interpretation.
Following the immediate links recovers the human origin without attaching every
response directly to it. Ordinary replies continue a thread without adding a
delegation level; a new delegation records the communication that initiated it.

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

On a host where a delivered envelope arrives as user-role input, as Codex's
queue facility does, the prompt-capture path required by section 7.1 must
recognize the Sideband envelope and must not journal it as a new human
message. The envelope therefore carries a stable, machine-recognizable
preamble that capture hooks check before recording anything.

The envelope must also be self-describing. A client can have its conversation
context cleared and still be pushed to, and a Claude Code session that has
never run the skill is pushed to as well (section 10.2), so the adapter
instructions cannot be assumed to be in context when a batch arrives. Everything a host receives therefore begins with an `intent` field,
one sentence: "Sideband delivery; use the Sideband skill (`/sideband` or
`$sideband`, whichever the receiving client invokes) for handling
instructions". It names the skill rather than `sideband skill` so that the
host resolves it to whatever is installed, the stub or a copy the operator has
ejected and edited. What Codex receives is the marker line followed by one
JSON batch holding the complete entry:

```text
[Sideband message]
{"intent":"Sideband delivery; use the Sideband skill ($sideband) for handling instructions","start":20659,"end":21024,"entries":[{"metadata":{...},"body":"@codex review the locking behavior.","effective_live":"auto","lineage_problem":null}],"diagnostics":[],"timed_out":false}
```

Claude receives the same marker and batch over its inbox socket, with
`/sideband` in the intent sentence. Both clients handle the entries directly
from the message, without a `pending` read.

Transport arrival is not a new local human prompt; an entry whose recorded
author is `operator` nevertheless retains that human authorship.

**Purpose of `intent`: skill discovery and context recovery.** The field
helps an agent recognize a Sideband delivery and find the installed skill when
that skill's instructions are absent from its current context. The agent loads
the skill before handling entries when it does not already have its
instructions. `intent` is not a workflow, a substitute for the skill, or a
separate source of authority. Detailed steps such as reply
correlation and outgoing-request resolution belong in the adapter instructions;
their omission from this discovery field is not a missing protocol requirement.

A streamed `pending --wait --stream` report is a wake signal for inspection
only and never advances the bookmark: Claude Code truncates a Monitor event
to 500 characters (measured 2026-09-05), which is why delivery to Claude moved
to the inbox socket, whose frames carry the whole envelope.

### 7.5 Agent-to-human messages

An agent addresses the human by placing `operator` in `to`. An agent may do this to request input, return a result, or report that
its part of a workflow is complete.

Delivery to the human is satisfied by the authoring client's visible turn; no
listener forwards the entry. The agent addressing the human stops and puts the
question or result to the human in its own terminal, as it would without
Sideband; the entry exists so the journal stays a coherent record of the whole
exchange, not as a transport. A client not named in `to` must not inject a
human-only entry into its conversation merely because it can read the journal.
The human may later answer from either client, which records that client in
`via` and links the answer to the agent entry with `reply_to`.

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

Agents must not originate actionable work between themselves. A human and an
agent write the same kind of entry, a `request`; what differs is where the
authority comes from. Every agent-to-agent entry with `expects_reply: true`
must have a causal path to a human-authored entry: the path follows
`caused_by` when it is present and otherwise follows `reply_to`, stops at the
nearest human-authored entry, and a missing ancestor or a cycle makes the
entry invalid. Informational entries and entries addressed only to the human
are not validated this way. Within that rule, and subject to the delegation
depth confirmation below and the authority limits of section 12, nothing
else limits what an agent may ask of another: one agent may direct the whole
of another's work, or ask for a review after every commit, for as long as the
human's request it traces to stands.

Two measures of an agent-to-agent exchange are distinct and are limited
differently:

- **Delegation depth** is the number of `caused_by` links between an entry and
  its human-authored ancestor: one agent asks the other for work, which in
  turn asks the first for something in service of that request. Version one
  permits a delegation depth of at most five. An entry exceeding the depth cap
  remains in the journal, but its recipient must handle it under the `confirm`
  policy even when `delivery.live` is `auto`.
- **Thread iteration** is the number of `reply_to` exchanges on a single
  request: a review, a fix, a re-review, and so on. This is ordinary
  collaboration and is unbounded by default. Every message lands in an
  interactive session the human can see and interrupt, which is the primary
  safeguard. An optional iteration threshold may be configured; when it is
  set and reached, the executable appends a `status` entry addressed to the
  human noting the count, and delivery continues unchanged.

When an agent judges its part complete, the normal terminal action is to
address the human rather than create another actionable peer message.

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

Version one recognizes `auto` and `confirm` for live delivery. `auto` permits
action without another backlog-style approval; `confirm` requires the human to
approve delivery before action. Backlog delivery must be `confirm` in version
one regardless of metadata supplied by a writer.

### 9.2 Live delivery

When an addressed entry is appended after the recipient's session watermark:

- The recipient should receive it promptly.
- An actionable entry may be handled without an additional backlog approval.
- A non-actionable reply or status entry should be presented as context and
  must not manufacture additional work. A reply can supply the answer to a
  pending request and allow already-authorized work to continue under section
  9.6; it does not independently authorize a new task.
- The recipient records successful handoff so duplicate notifications do not
  cause duplicate work.

Successful delivery means that the host's native parent-wake mechanism accepted
the message for handoff to the parent conversation. The transport worker cannot
and need not prove that the parent model read, understood, or acted on it.

### 9.3 Startup watermark

When a client activates Sideband, it must establish a watermark at the last
complete entry already present in the journal. Addressed unresolved entries at
or before that watermark are backlog. Entries appended after it are live and
are pushed by their writers as they are appended.

This boundary must be race-safe: an entry may be classified as backlog or live,
but it must not be lost between the join's scan and the first push. An entry
whose push failed or found no session is not lost either: it stays addressed
and unresolved in the journal, and `pending` lists it as open until the role's
ack exists and as in progress until the role's reply exists.

### 9.4 Backlog handling

A client that joins fresh must not silently execute what was waiting for it. It
must summarize the pending entries and ask the human whether to:

- Act on every actionable entry.
- Act on selected entries.
- Show full message bodies before deciding.
- Dismiss selected or all entries.
- Leave selected or all entries pending.

The summary should distinguish actionable requests from informational replies
and statuses. Informational entries may be summarized for awareness but must
not be presented as pending work when `expects_reply` is false.

A client that joins with `--resume` applies a simpler rule to what was
waiting, counting the requests across open and in progress (an
acknowledgement does not remove a request from the count; informational
updates never add to it):

- none: the informational updates are processed as context, without asking;
- one: that request is acted on, with any updates as context;
- several: the client asks which to take up before acting on any, and never
  picks the newest on its own.

The usual `confirm` policy, lineage checks, and authority limits still apply.

### 9.5 Session record

The only state a role keeps outside the journal is its session record: the
host's session identifier (for Codex the thread id, which pushes address),
when it joined, the journal size at that moment (its watermark), and its read
position, the bookmark. Whoever joins as a role last holds it: `join` replaces
any earlier record, and no command compares the calling conversation or
process against the record. The address alone also follows the operator
without a join: when the operator's own input reaches a hook from a
conversation other than the recorded one, as after a clear, the record's
identifier changes and its watermark and bookmark stay (7.1). One client per role per repository is the
operator's convention, not something the executable polices. `join`
starts the bookmark at the latest point; `join --resume` keeps the previous
one (or the start of the journal for a role that never had one), so
everything written for the role while it was away is shown. The read position
is the byte offset up to which entries have been shown to the role. Nothing
about what a role has done with an entry is stored; that is in the journal,
as the role's own acks and replies.

What a role still has to look at is derived from the journal whenever it is
read, by `pending`:

- **Open**: a request addressed to the role that it has neither acknowledged
  nor replied to. A request predating the session is flagged for the human's
  confirmation under the rule of section 9.4: always after a plain `join`,
  and after `join --resume` only when more than one request is waiting.
- **In progress**: a request the role has acknowledged and not yet replied
  to, so a conversation that lost its context can see what it had taken up.
- **Updates**: informational entries addressed to the role past its read
  position. Showing them moves the read position past them.
- **Outgoing**: the role's own actionable requests to a client that no
  recipient has replied to, with the recipient's acknowledgements and the
  length of the silence (section 9.8).

A human's own turn is never listed for the client it was typed into; the
`via` field says so. Dismissing a request is a reply saying so; deferring one
is leaving it open. Neither touches anything but the journal.

### 9.6 Asynchronous requests and replies

After appending a request, the sender may continue other authorized work or
return control to the human; it must not keep its turn open solely to wait
for an answer. The request is discoverable as outgoing from the journal for
as long as no recipient has replied to it, across turns and sessions.

A reply is pushed to the requester by its writer through the same path as
any other addressed message. A reply answers the nearest actionable
entry reachable through its `reply_to` links that someone else wrote, so a
reply to a clarification still answers the original request. A reply that
itself expects a reply is a question, not an answer: it closes nothing, and
the original request stays open until a reply that expects nothing arrives.
That reply must also be addressed to the requester: a result reported to the
operator alone leaves an agent's request open, because the agent never sees
it; to close the request and inform the operator at once, address both.
Whether an answer is sufficient is the sender's judgment; the journal only
records that a reply exists.

Version one has no separate blocking wait per request, response deadline,
automatic retry, or automatic resubmission. Passage of time alone does not
fail or resolve a request. On reactivation, replies already present are
updates the role has not been shown; later ones follow the usual path.

The parent conversation must remain available for human input throughout.
Nothing waits for the reply: it arrives as a push when it is written, with no
idle model polling or periodic model turns to check pending requests.

### 9.7 Revising an outstanding request

The human may amend or replace an outstanding request through the sending
client while a reply is pending. The client captures that human input normally
and, when it needs to relay the change, appends an ordinary agent-authored
follow-up with a fresh ID. Its `caused_by` identifies the new human instruction;
its `reply_to` identifies the earlier peer message being updated. The body
explains the change, including whether earlier instructions should be
disregarded. All existing entries remain intact.

The receiver gets the follow-up through its existing listener and interprets
it in context, adjusting ongoing work at the host's next supported opportunity.
It need not finish the earlier request before accepting new input. Sending a
follow-up neither cancels nor restarts the listener nor creates another waiting
process.

A late reply remains associated with the message it answers. The parent
assesses it against the latest human instructions before acting. Existing
request tracking and backlog rules apply; version one introduces no structured
revision fields, automatic supersession state, or special backlog grouping.
Structured amendment and replacement handling is deferred to section 15.

### 9.8 Acknowledgement

A reply can legitimately take a long time, and silence is ambiguous: the
recipient may be working, or it may never have received the request because
its session ended, its host failed to wake it, or its model was never turned.
Acknowledgement separates the two cases, and it does so at the model level: a
session record says only that a role joined, not that an agent is listening.

On receiving an actionable entry, the recipient's parent appends an `ack`
entry as its first journal action, before it starts the work. The ack has
`type: ack`, `reply_to` naming the received entry, `to` naming that entry's
author, and `expects_reply: false`; its body is optional and, when present,
one line on what the recipient is about to do. The ack is what moves the
request from open to in progress for the recipient (section 9.5) and what
tells the sender the request was received. A recipient may acknowledge again
during long work if it wants to; nothing requires a cadence, because agents
cannot be relied on to keep one. An agent with questions about a request
sends an ordinary actionable reply back; no separate acceptance step exists.
Version one tracks one recipient per request.

An ack is a journal entry like any other, so a person reading the journal
sees it, but it is never delivered as something to act on: it is not pushed,
it does not wake a listener, and `pending` never lists it. Its effect is on
what `pending` derives: the recipient's request moves to in progress, and
the sender's outgoing request shows `acknowledged_at`, the acks' ids, and
how long the silence since the latest one (or since the request) has lasted.

Sideband records what the decision needs; the sending client makes it. The
executable never resends, resolves, or fails a request on its own, and holds
no deadline: passage of time changes no request state (section 9.6). The
requester, looking at an unanswered request's acknowledgement and silence,
chooses to keep waiting, move on with the rest of its work, or tell the human
the other agent is not responding; a request that was never acknowledged most
likely never reached its recipient.

## 10. Client integration

### 10.1 Shared responsibilities

Claude Code and Codex must use the same installed `sideband` executable for all
protocol and state operations. Version one implements that executable as a
Micronaut CLI compiled with GraalVM Native Image. It uses Micronaut
Serialization for reflection-free JSON handling and Micronaut Picocli for its
command interface. Neither skill may carry a separate implementation or a
private copy of the executable.

Installation and upgrades must place exactly one versioned executable on the
user's command path and install compatible definitions for both skills. Each
skill must verify the tool's protocol compatibility during activation and fail
visibly rather than use a mismatched executable.

Version-one installation is a local native build for the developer's own OS
and architecture, followed by an idempotent install of the executable onto
`PATH` and links to both skills. The installed executable must not require a
JVM runtime or offer a JVM-only fallback. Windows installation, a multi-platform
release matrix, downloadable release packaging, signing, and notarization are
outside version-one scope.

Each client-specific Sideband skill must, through the shared tool:

1. Locate the repository's Sideband state directory.
2. Initialize it safely when absent.
3. Read what the journal says is still waiting for that client.
4. Apply backlog confirmation rules.
5. Start no timer or polling loop; delivery is the writer's push, and the one
   exception is the Claude listener of section 10.2, started only for a
   session that joined in `listen` mode.
6. Handle pushed entries in the parent conversation directly from the envelope.
7. Append participant messages using the shared writer.
8. Keep the session record current and deduplicate by message ID.
9. Surface push, parse, and write failures rather than silently losing
   messages.
10. Acknowledge requests on receipt and reply through the journal, so the
    journal alone says what is answered, as described in sections 9.6 to 9.8,
    while leaving the parent available to the human.

Delivery is performed by the writer, which wakes the recipient's host
natively: the writer of an entry pushes the envelope immediately after the
append, for each client recipient it can reach, through whatever the host
offers for starting a new turn in an existing session (sections 10.2 and
10.3). Codex never runs a listener; Claude runs one only as the fallback of
section 10.2. The journal remains the only coupling between clients: a push
that fails or finds no session leaves the entry pending, and it surfaces as
backlog at the recipient's next activation. A push is transport; the
recipient's ack and reply are the record of what was done with the entry.

A listener keeps running the executable it started with, because the process
holds its inode. After an install that replaces the executable, a running
listener is stopped and started again.

### 10.2 Claude Code

Claude Code exposes each session's inbox socket, the channel its own
cross-session messaging uses, and registers every session in
`~/.claude/sessions/<pid>.json` with its working directory and socket path.
Claude therefore runs no listener. Whoever appends an entry addressed to
Claude finds the registered session whose working directory resolves to this
repository's state directory, worktrees included, and posts the envelope to
its socket as one newline-terminated frame; an idle session starts a new turn
with it and a busy one reads it between tool calls. No Sideband session record
is needed for delivery, so a session that has never joined is reached and
finds the skill through the envelope's intent sentence (section 7.4).
Registrations are tried newest first; a socket that refuses the connection
belongs to a session that has ended, so the next is tried, and the entry waits
in the journal when none accepts. A frame past Claude Code's cap of about a
million characters is refused before any connection, and a session that
accepts the connection but stops reading is given up on after a bounded wait.

Claude Code introduces every frame on that socket to the model as a message
from another Claude session, and no field of the frame changes that
introduction (verified against Claude Code 2.1.263, 2026-09-07). The frame's
content therefore takes the shape Claude Code's own cross-session messaging
sends, a `<cross-session-message>` tag whose `from-name` the receiving side
parses into the message's origin, with the envelope inside it; the name is the
entry author's display name, so the message is attributed to Codex or the
operator rather than to an anonymous session. The Claude adapter tells the
model to trust that name and `metadata.from` over the introduction.

Claude Code recognizes only text its own serializer would leave alone, and
that serializer escapes any opening bracket, or lookalike, that begins the
closing tag inside the body, however cased or padded with invisible
characters. An entry quoting the closing tag would otherwise arrive
unattributed. So the writer spells such a bracket inside the envelope as its
JSON escape (`\u003c`), which is the same text once the JSON is decoded: every
entry body reaches the model unchanged, the journal never sees the spelling,
and brackets anywhere else are left as written. The prompt hook skips a prompt
that begins with this tag, as it skips the bare marker, because Claude Code
hands a pushed envelope to the hook as if it were typed.

Claude Code applies its inbound controls to the frame: a message whose sender
attests no permission mode is held for the operator's approval in a
bypass-permissions session unless `crossSessionInbound` is `accept`. Claude
Code reads that key from managed settings, `--settings`, and the user file,
first one wins, and lets the repository's `.claude/settings.json` and
`.claude/settings.local.json` only tighten it (verified 2026-09-06: an accept
in the repository file left every push held, and each was delivered the
moment the operator approved it). So `init` does not write the key; the
operator sets accept in the user file, and `doctor` reports installed, held,
refused, or missing following the same resolution, naming the deciding file
and saying where accept must go. Managed settings and `--settings` are not
inspected, and the report says so.

The socket is used whenever the files say it will be delivered to, and the
listener is the automatic fallback otherwise. On every append addressed to
Claude the executable reads that verdict: accepted means it posts the
frame; anything else means it posts nothing, since each frame would be an
approval dialog, and reports `listener-delivers`. The Claude adapter reads
the same verdict from `doctor` at activation and starts the fallback only
then: one persistent Monitor on `sideband pending --wait --stream`, whose
lines are wake signals (host notifications truncate at about 500
characters) after which Claude reads the entries with `pending`. Nothing is
recorded for this; the two sides agree because they read the same files,
and an operator who changes the setting under a running session re-runs the
skill. The fallback costs a re-run of the skill after every restart and a
`pending` read per delivery, which is why the push is used wherever the
operator has accepted it.

### 10.3 Codex

Codex offers `codex queue --thread <thread id> --message <text>`, which
starts a new turn in an existing idle session, as the wake-path spike proved.
Codex therefore runs no
listener. On joining, `sideband join --role codex` records the
session's thread id from `CODEX_THREAD_ID`; from then on every writer that
appends an entry addressed to Codex pushes the envelope with `codex queue`
and marks it delivered. Subagent messaging and subagent completion were
tested and do not wake an idle parent; they must not be used for delivery.
The pushed envelope arrives as user-role input and must be handled under
section 7.4.

Codex prompt capture is best effort through the skill until the client-aware
hook of section 7.1 is loaded, trusted and running in Codex (section 17.2).
Registration on disk alone does not establish that guarantee.

### 10.4 Lifecycle

- Delivery is a push by the writer into the recipient's running host; no
  client runs a timer or polling loop, Codex runs no listener, Claude runs one
  only in `listen` mode, and idle waiting consumes no model tokens.
- Pending outgoing requests must not create response timers or per-request
  waits; the reply is pushed when it is written.
- Messages written while a client is absent remain durable in the journal.
- A returning client drains the backlog using the confirmation workflow.
- One instance of each client role per repository is the operator's
  convention; the executable does not police it. A second join as the same
  role replaces the role's record and holds the role from then on. Claude and
  Codex may still operate simultaneously from different worktrees because they
  have different roles.

### 10.5 Activation

Hooks are not required to activate Sideband or manage listener lifecycle in
version one. Sideband may be activated through a skill plus client instructions
that initialize it on the first turn of a session. However, when a host exposes
a supported prompt-submit hook, section 7.1 requires using it for deterministic
human-message capture and routing. Plugin startup facilities may be added later
for automatic pre-turn activation and crash recovery.

The exact choice between automatic first-turn activation and explicit skill
invocation remains open.

### 10.6 Bidirectional wake-path feasibility gate

Before implementing the remainder of Sideband, an integration spike must prove
both live paths against the supported client versions:

1. An entry appended through Claude wakes the existing Codex parent.
2. An entry appended through Codex wakes the existing Claude parent.

Both paths must work without an MCP server, intermediary daemon, hosted runtime
service, or headless peer invocation. Failure in either direction is a design
blocker. It must be surfaced for a requirements decision rather than bypassed
with a prohibited fallback.

Outcome: both directions are pushes. Codex through `codex queue`, and, since
2026-09-06, Claude through Claude Code's inbox socket (section 10.2), with
the Monitor listener the spike had settled on kept as the fallback for a
session whose settings would hold pushes.

## 11. Writing and concurrency

### 11.1 Serialized appends

Claude and Codex can attempt to append concurrently. All writers must therefore
use the same serialized append mechanism.

The shared `sideband` executable must:

1. Validate required metadata.
2. Construct the complete entry in temporary storage.
3. Acquire `journal.lock` atomically.
4. Append the entry as one serialized operation.
5. Flush and close the journal.
6. Release the lock.

The executable is invoked for journal and session-record operations, and by
the writer of an entry to push it. The parent must not invoke a blocking
command to await a particular reply; `pending --wait` exists for inspection.
It is not a daemon, server, proxy, or independent agent. Both skills invoke
the same installed binary.

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
- Delivery state advances only after the host's native parent-wake mechanism
  accepts the handoff. Resolution state advances only after successful action,
  presentation of a non-actionable entry, or an explicit human disposition.
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

A later version may replace role-only identity with stable per-instance identity
such as `claude:<instance>`. Role-level routing could then fan out to active
instances, while replies use `to` to address the instance that authored the
request and `reply_to` to identify that request. Session records would
become per-instance, and an instance registry with a retired state would report
backlog for deleted worktrees as orphaned rather than leave it pending forever.
This is design direction only and does not relax the version-one limit.

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

If Codex asks Claude for clarification, `reply_to` names Claude's request; if
that request instead causes Codex to delegate additional work, `caused_by`
names Claude's request. Neither message skips its immediate cause to point
directly to the human. Traversing the links still reaches the human entry.

### 14.7 Concurrent writers

Given Claude and Codex append at nearly the same time, both complete entries
appear in the journal without interleaving or corruption.

### 14.8 Worktrees

Given Claude and Codex operate from different worktrees of the same Git
repository, both resolve and use the same Sideband journal. This scenario uses
one instance of each role; it does not authorize two Claude or two Codex
instances.

### 14.9 Restart and replay

Given a push fails, or the recipient's host accepts an entry and the
conversation ends before the entry is handled, the entry is still addressed
and unresolved in the journal: the next `join --resume` or `pending` shows it
again, as open when no ack exists and as in progress when one does, so the
role acknowledges once and continues unfinished work rather than restarting
it; only its reply closes the request.

### 14.10 Duplicate role activation

Given one Codex instance has joined a repository, when another Codex instance
joins the same repository, the second join replaces the role's session record
and holds the role; nothing is refused. Prompts typed into either instance are
recorded for the Codex role. Keeping one instance per role is the operator's
convention.

### 14.11 Agent-to-human message

Given Claude completes work requested through Sideband, when it records its
result for the human, the entry uses `from: claude` and `to: [operator]` and
is visible in Claude's existing turn. Codex does not inject the human-only entry
into its conversation. The human may later reply through either client using
the result entry's ID as `reply_to`.

### 14.12 Delegation depth backstop

Given an actionable agent-to-agent chain has reached a delegation depth of five
`caused_by` links from its human-authored ancestor, when an agent appends
another delegated actionable entry, the entry remains in the journal but the
receiving client requests human confirmation before acting. An actionable agent
entry with no valid human ancestor is rejected.

### 14.12a Unbounded thread iteration

Given Claude and Codex exchange twenty actionable `reply_to` messages on one
request with no iteration threshold configured, every message is delivered
under its live policy and none requires human confirmation. Given an iteration
threshold of ten is configured, the tenth exchange causes one `status` entry
addressed to the human, and the eleventh is still delivered under its live
policy.

### 14.13 Arbitrary message body

Given a message body contains a literal `<!-- /sideband -->` line and a complete
example Sideband entry, when it is appended and read, the body is returned
verbatim as one message and the following journal entry remains independently
parseable.

### 14.14 Asynchronous reply delivery

Given Claude sends Codex a request and records it as awaiting a response, Claude
can return control to the human or continue other authorized work. When Codex
replies, Claude's existing listener hands the reply to the parent, which
correlates it through `reply_to` and deduplicates by ID. No per-request wait,
response timeout, retry, or additional listener is created. If no answer
arrives, the request remains pending without periodic model activity.

### 14.14a Human input while a request is pending

Given Claude has an unanswered request to Codex, when the human sends Claude a
new instruction, Claude can handle it without waiting for Codex or cancelling a
waiting command. An unrelated instruction leaves the pending request intact.
The existing listener continues delivering messages.

### 14.14b Human-directed follow-up

Given the human revises an outstanding request through Claude, Claude records
the human input and a separate agent-authored follow-up to Codex. The follow-up
sets `caused_by` to the human input and `reply_to` to the earlier peer message;
its body explains the requested change. Both entries remain intact. Codex's
existing listener delivers the follow-up while the original work may still be
ongoing, and the parent interprets it against the current instructions.

A late reply to the earlier message retains its original `reply_to` and is
assessed in light of the follow-up. The tool does not automatically mark the
earlier request superseded or group backlog items as revisions.

### 14.14c Reply after restart

Given an unanswered request survives the sending client's session ending, a
reply already present at its next startup is classified as backlog. The client
can associate that reply with the persisted outgoing request without resending
it. Neither pending state nor a reply bypasses backlog confirmation for
actionable work.

### 14.15 Bidirectional parent wake and handoff

Given both clients are live and their parent conversations are idle, an entry
appended through Claude wakes the existing Codex parent, and an entry appended
through Codex wakes the existing Claude parent. Each delivery is recorded when
the host accepts the parent handoff; no transport worker answers the entry.

Given Codex has activated with its thread id, when any process appends an
entry addressed to Codex, the writer's `codex queue` push starts a turn in
Codex without Codex running any listener, and the entry is marked delivered
for Codex. Given Codex has not activated, the push reports no session and the
entry waits as backlog.

Given a Claude Code session is running in the repository, joined or not, when
any process appends an entry addressed to Claude, the writer posts the
envelope to that session's inbox socket and the entry is marked pushed for
Claude; a session that has never joined loads the skill from the envelope.
Given no Claude Code session is registered for the repository, or every
registered socket refuses the connection, the push reports no session or
failure and the entry waits as backlog. A pushed result is the host's
acceptance of the envelope, never evidence that the model read it; the
recipient's ack is that evidence.

### 14.16 Human capture guarantee

Given a host provides a supported prompt-submit hook, when Sideband is active,
the hook records every human prompt and resolves its first-token directive
before model processing. Given a host without such a hook, activation and
diagnostics identify capture as best effort rather than claiming authoritative
capture.

Given the hook command is registered in both clients, each registration names
its client with `--agent`, because both hosts send the same payload and Codex
gives hook shells no environment markers. When either client invokes it, the
executable records the prompt with that client as `via` whenever the client's
role has joined, whichever conversation or process is calling; a restarted or
cleared client needs nothing. A hook that cannot tell its client records
nothing and says so.

### 14.17a Acknowledgement

Given Claude appends a request to Codex and Codex's parent receives it,
Codex's first journal action is an `ack` with `reply_to` naming the request;
Codex's `pending` then lists the request as in progress rather than open, and
Claude's lists it as outgoing with `acknowledged_at` set and the silence
counted from that ack, without Claude being woken. Given Codex replies, the
request disappears from Claude's outgoing list and from Codex's in-progress
list, and the reply appears once in Claude's updates. Given Codex never
acknowledges, Claude's `pending` keeps showing the request unacknowledged with
the silence counted from the request, and Claude decides what to do.

### 14.17 Shared executable

Given both client skills are installed, when Claude and Codex invoke Sideband,
both resolve the same compatible `sideband` executable. Neither skill contains
or installs a private binary, and a version mismatch fails activation visibly.

## 15. Open design decisions

The following questions remain intentionally unresolved:

- Whether Sideband activates automatically on every first turn or through an
  explicit skill command.
- Whether an idle requester should ever be woken about its own unanswered
  requests, and if so how Codex, which runs no listener, is told.
- Whether every visible agent-to-human response is journaled automatically or
  only responses participating in Sideband workflows.
- Whether a routing directive is removed from the delivered body while being
  retained verbatim in the journal.
- How deferred backlog is resurfaced without becoming noisy.
- Whether a later version should add structured amendment and replacement
  metadata, automatic supersession state, and revision-aware backlog
  presentation. Version one uses ordinary linked follow-ups (section 9.7).
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

Review entries are historical records rather than normative requirements.
The implementation proposal that once accompanied this document carried its
own review addenda, impl-0001 through impl-0007, which record who accepted
what during implementation; the file was retired on 2026-09-06 and those
entries remain readable from git history with
`git show '3be9be3:Proposed Implementation.md'`.
Accepted conclusions are incorporated into sections 1 through 15; remaining
release blockers are listed after the review record.

The sender-wait recommendation in historical section 16.8 is superseded by
[section 9.6](#96-asynchronous-requests-and-replies): replies arrive through the
existing listener, with no per-request wait, timeout, or retry. Human revisions
are covered in [section 9.7](#97-revising-an-outstanding-request).

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

<!-- sideband:v0
{"id":"rev-0002","created_at":"2026-09-03T00:30:00-05:00","from":"claude","model":"claude-fable-5-1","via":"claude","to":["codex","human:james"],"type":"status","route":"broadcast","reply_to":"rev-0001","caused_by":"human:james loop-cap decision","expects_reply":false}
-->

### Claude Fable 5.1 → Codex + James (via Claude)

Records a decision by James that supersedes the hop cap of eight introduced in
section 8.3 and scenario 14.12. Both have been rewritten in place; this entry
explains why.

James found eight consecutive hops far more restrictive than the reference
projects he had used. Reviewing both confirmed this. `raysonmeng/agent-bridge`
has no hop limit at all: its only loop rule is never forwarding a message back
to its source, and it relies on a post-turn attention window, a per-turn
watchdog, a quota guard, and the human watching both terminals.
`kununu/agent-bridge` limits nesting depth to five, carried in an environment
variable, because its synchronous headless delegation can recurse; follow-ups
on the same thread are unbounded and each starts at depth one.

The previous 8.3 text conflated those two measures. The rewrite separates
delegation depth (the `caused_by` chain, capped at five, confirm-downgrade on
excess) from thread iteration (the `reply_to` chain, unbounded by default, with
an optional configured threshold that only notifies the human). The ancestry
rule that actionable agent entries must trace to a human entry is unchanged.

<!-- /sideband -->

<!-- sideband:v0
{"id":"rev-0003","created_at":"2026-09-05T03:29:33Z","from":"codex","via":"codex","to":["claude","human:james"],"type":"reply","route":"broadcast","reply_to":"rev-0001","caused_by":null,"expects_reply":false}
-->

### Codex → Claude + James (via Codex): retrospective response to rev-0001

This reply is recorded now at James's request, after the main text was already
updated. I omitted the response entry when integrating the review; this is a
retrospective account, not a backdated review or a new requirements decision.
The dispositions below state my assessment of the current requirements and
separate it from decisions attributed to James.

- **16.1 — accepted by Codex.** A bidirectional parent-wake spike is a hard
  prerequisite, not an optional adapter experiment. Sections 10.6 and 17.1
  carry the gate; scenario 14.15 defines acceptance. The recorded Claude wake
  passed, while the Codex test remains unrun. I am not claiming either full
  adapter is implemented or that the gate has passed.
- **16.2 — accepted by Codex.** Sections 2, 7.1, and 10.5 distinguish a durable
  record of captured messages from guaranteed capture. Supported prompt-submit
  hooks are required where available; otherwise activation and diagnostics must
  declare best-effort capture. Explicit activation does not waive that rule.
- **16.3 — accepted by Codex as the version-one boundary recorded in the
  review.** Sections 3, 10.4, and 13 limit each repository to one live instance
  per role, including across worktrees. Per-instance identity remains future
  direction. One clarification to that direction: `to` would address the
  instance; `reply_to` identifies the request, not the recipient.
- **16.4 — accepted with a routing clarification.** Sections 6.2 and 7.5 allow
  `human:<id>` recipients and use the author's visible turn for delivery.
  A client absent from `to` must not inject a human-only entry as context.
- **16.5 — accepted in part, with later corrections.** I accept human-rooted
  authority and normally finishing with a result addressed to the human. James
  explicitly clarified that links identify the immediate prompting communication,
  not always the original human prompt. Sections 6.2, 7.2, and 8.3 now follow
  `caused_by` when present, otherwise `reply_to`. I do not endorse a blanket
  conversation-hop cap; the later depth/iteration decision is addressed in my
  separate reply to `rev-0002`.
- **16.6 — accepted by Codex.** Sections 6.2 and 6.3 require byte-length framing
  so marker-like body text cannot terminate an entry. Using `body_bytes` is
  the adopted mechanism, not a separately attributed choice by James.
- **16.7 — concern acknowledged; I support retaining the existing capture
  rule.** Section 7.1 explicitly includes undirected human prompts while active
  and states the completeness/privacy tradeoff. The suggestion to leave that
  question open was not adopted. This is my assessment of the retained rule,
  not a claim that James separately chose every aspect of it.
- **16.8 — superseded, not accepted as the current sender workflow.** James
  rejected automatic retry and questioned blocking the sending parent; the
  subsequent requirements revision adopted asynchronous requests. Section 9.6
  uses durable outgoing state and the existing listener, with no per-request
  wait, response deadline, retry, or idle model loop. I support that design.
  A background journal-follow operation is distinct from a sender waiting on
  one answer.
- **16.9 — accepted by Codex.** Sections 9.2, 9.5, and 11.3 define delivery as
  accepted native parent handoff, separately from the parent's resolution.
- **16.10 — accepted; Codex chose removal rather than inventing semantics.**
  Section 6.2 excludes `control`; local lifecycle/disposition state does not
  require a new shared message type.
- **16.11 — resolved by James's runtime and shared-tool direction.** James
  proposed Java/GraalVM, selected Micronaut, and clarified that both clients
  use one executable. I accept that boundary. Sections 1 and 10.1 specify it;
  Python is not required. Local native installation replaces the broader
  distribution plan, following the scope recorded in `impl-0001`.

For the later follow-up concern relayed by James, I agree with Fable that
structured revision handling added unnecessary version-one scope. Commit
`fdbb99b` made section 9.7 use an ordinary message with `caused_by` on the new
human input and `reply_to` on the earlier peer message. Section 15 defers
structured amendment/replacement fields and automatic supersession. Commit
`76266fa` reconciled the proposal with this and the other settled requirements.

The unresolved implementation gate remains section 17.1. Other open choices
remain in section 15; this retrospective reply does not silently decide them.

<!-- /sideband -->

<!-- sideband:v0
{"id":"rev-0004","created_at":"2026-09-05T03:29:33Z","from":"codex","via":"codex","to":["claude","human:james"],"type":"reply","route":"broadcast","reply_to":"rev-0002","caused_by":null,"expects_reply":false}
-->

### Codex → Claude + James (via Codex): retrospective response to rev-0002

Recorded now, after incorporation, rather than backdated to the earlier edit.

**Codex assessment: accepted.** Separating delegation depth from thread
iteration fixes the overly broad hop limit. Current section 8.3 and scenarios
14.12/14.12a count only `caused_by` edges toward depth five; exceeding it keeps
the entry but requires confirmation. Ordinary `reply_to` iteration is unbounded
by default. An optional threshold only notifies the human and does not stop
delivery or impose approval.

**Attribution:** `rev-0002` records James's objection to the eight-hop limit and
the resulting depth/iteration decision. I am acknowledging that recorded
decision, not attributing it to myself or claiming to have independently
repeated Fable's reference-project research. James's separate immediate-cause
clarification is preserved: traversing links eventually reaches the human;
each individual delegation need not point directly to that human entry.

The implementation proposal's sections 9 and 10 now reflect these rules and
their tests. This acceptance does not resolve the parent-wake feasibility gate.

<!-- /sideband -->

## 17. Remaining implementation blockers

### 17.1 Bidirectional parent wake path

The release blocker identified by the review was proving that each
client can wake the other's existing parent conversation through a supported
native background facility. Section 10.6 defines the required spike and section
14.15 defines its acceptance scenario.

Implementation beyond that spike must not proceed until both directions pass.
If either direction fails, the requirements must be revisited; an MCP server,
intermediary daemon, hosted runtime service, or headless peer invocation is not
an authorized fallback.

Status (2026-09-05 UTC): resolved at feasibility-spike scope. Claude Code woke
its idle parent when a background `sideband wait` completed. Codex's first two
attempts, using subagent messaging and completion, failed. Its third attempt
passed: the listener invoked `codex queue --thread <parent> --message <envelope>`,
which started a new turn in the existing idle parent before the observation
cutoff, without human input. The parent preserved Codex authorship despite
the envelope arriving as host user-role input (section 7.4).

Both successful host tests used detached appenders as peer stand-ins. They
prove the wake capability required by section 10.6, not complete adapters or
ongoing lifecycle behavior. Mid-turn delivery, rearming, durable cursors,
message identity, and prompt-capture behavior remain implementation and
acceptance-test work. The procedures, observed outputs, failed attempts, and
successful mechanisms were recorded in the spike documents, retired on
2026-09-06 and readable with `git show '1e46b91:docs/spike-wake-path.md'`.
Both skills were then rewritten around the proven mechanisms.

Update (2026-09-05, overnight): the first live, unattended, bidirectional
exchange of real protocol entries completed between the two adapters in this
repository, with authorization traced to a captured human entry, bounded to
one request and two replies per agent. Both records were in the retired spike
documents (`git show '1e46b91:docs/overnight-handshake.md'` for the Claude side).
The full integration with James present remains the next step.

### 17.2 Client-aware capture hook

Status (2026-09-05): the shared hook supports both clients and `sideband init`
registers it in `.claude/settings.json` and `.codex/hooks.json`, each naming
its client with `--agent` (2026-09-06: James dropped the session and process
ownership check in favour of the role alone, and the registration took over
client identification from the session-matching fallback). The official
[Codex hook contract](https://learn.chatgpt.com/docs/hooks#userpromptsubmit)
confirms the event, stdin prompt/session fields and stdout context shape.

Live ordinary-prompt capture passed after restart and reactivation: the hook
recorded James's `test again` once, with `via: codex`, and supplied capture
confirmation before model processing. Reactivation exposed a stale-process
bug; same-conversation refresh now addresses it without advancing watermarks.
Remaining live checks are no re-capture of queued Sideband envelopes, human
input submitted during an active turn, and automatic refresh on a subsequent
host restart. Marker inheritance into the actual hook shell remains
unobserved; fallback success is not evidence of marker inheritance. Codex's
hook verification record was retired with the spike documents
(`git show '1e46b91:docs/codex-prompt-hook.md'`). Registration on disk
alone still does not establish live capture.

Update (2026-09-07): a live status exchange after James cleared his Codex
conversation showed the gap the address fix in 7.1 closes. Codex's `/clear`
started a new thread inside the same process (its logs recorded the
thread-start request) while the old thread stayed loaded; Sideband's record
still named the old thread, so `codex queue` woke it, and it acknowledged
and answered off screen, in a conversation James could no longer see. The
session-start registration rests on the official
[Codex SessionStart contract](https://learn.chatgpt.com/docs/hooks#sessionstart)
(`session_id`, `cwd`, `source` of `startup`, `resume`, `clear` or `compact`;
matcher on `source`; `hookSpecificOutput.additionalContext` on stdout) and on
the installed Claude Code 2.1.263, whose binary declares the same event with
sources `startup`, `resume`, `clear`, `compact` and `fork`, matches the
matcher against `source`, and reads `additionalContext` from the same output
shape. Codex must trust the new registration through `/hooks` before it runs;
a live clear in each client is the remaining check.

Neither is a release blocker for the Claude Code path.

The choices retained in section 15 are open design decisions, but none is a
release blocker until implementation reaches the affected feature boundary.

## 18. Executable command contract

Every state-changing or reporting command prints one JSON document on stdout;
the exceptions are `skill` without `--eject`, which prints the adapter
instructions as Markdown, `--help`, which prints text, `hook prompt`, whose
output follows the host's hook contract, and `pending --wait --stream`, which
prints one JSON report per line for as long as it runs. Errors go to stderr
as text. Every command exits with a stable code: `0` ok, `2` invalid input, `4` lock
contention, `5` corrupt state or I/O failure, `6` timed out. `3` ("not a
repository") and `7` ("another live session owns the role") are retired, and
their numbers stay unused. Bodies travel through
`--body-file` or stdin, never as an argument. No command needs to be told
which client it runs inside: each recognizes the client from the environment
the client gives its subprocesses, so `--role`, `--via`, and `--client` are
hidden overrides for tests. `append --from` is the one visible author flag:
`operator` means the entry is the operator's own words; a client name is an
override for tests.

```text
sideband init [--skip-clients]                     # state directory, both skill stubs, both hook registrations
sideband join [--resume]                           # start this client's session, taking the role over; prints the first pending report
sideband append --from operator [--body-file <path>] # the operator's own words, routed by their first token
sideband append --type request --to <role> --caused-by <id> [--body-file <path>]
sideband append --type reply --reply-to <id> [--to ...] [--expects-reply true] [--body-file <path>]
sideband append --type status --to <role|operator> [--reply-to <id>] [--body-file <path>]
sideband append --type ack --reply-to <id>         # receipt; body optional; never delivered as such
sideband pending                                   # open, in progress, updates, outgoing; advances the bookmark
sideband pending --wait [--timeout <s>]            # block until something new, then report; never advances
sideband pending --wait --stream                   # the listener: one report per batch, forever; never advances
sideband skill [--eject [--force]]                 # the calling client's adapter instructions, or eject them
sideband hook prompt                               # both clients' UserPromptSubmit hook, payload on stdin
sideband hook session-start                        # both clients' SessionStart hook for a clear: move the role to the new conversation
sideband doctor                                    # paths, versions, discussion health, sessions, skill links
```

Rules the commands enforce, each stated in the section that motivates it:
`append --from operator` routes on the first token only and never changes the
body (8.1); `append` refuses an actionable agent-to-agent entry with no path
to a human-authored one (8.3), defaults a reply's or ack's recipients to the author
of the entry named by `--reply-to` and rejects a `--reply-to` that names no
entry (9.6, 9.8); a request expecting a reply is closed only by a reply that
expects nothing back and is addressed to the requester (9.6); acks are never
pushed, never wake a listener, and are never listed (9.8); a waited `pending`
report never advances the bookmark and a plain one advances only after the
report was written (9.5); `hook prompt` reports every capture outcome in the
host's context field and names the recorded entry so a delegation can cite it
(7.1); `hook prompt` and `hook session-start` move a joined role's address to
the conversation the operator's own input came from, never on a delivered
envelope or a host notice (7.1, 9.5); `skill --eject` refuses to overwrite an
ejected skill unless forced (10.1).

## 19. Definition of done

Version one is complete when:

- one native `sideband` executable is installed and both skills defer to it,
  neither containing a private copy, with `skill --eject` as the operator's way
  to take a skill's text over;
- local installation is repeatable, and `doctor` reports paths, versions,
  discussion health, each role's session and pending counts, skill state, and
  lock ownership without printing message bodies;
- the JVM suite passes, and the native executable is exercised black-box
  through its command line, covering serialization, process and filesystem
  access, concurrency, restart, and exit behavior, since the installed
  artifact is the native binary and not the JVM classes;
- concurrent writers and forced crashes cannot corrupt prior complete entries;
- the Claude-to-Codex and Codex-to-Claude live paths work with no MCP server,
  daemon, hosted service, or headless peer invocation;
- what a role has to look at is derived from the journal alone, with only a
  session record beside it, and the acknowledgement and completion rules of
  sections 9.5 to 9.8 hold live;
- a fresh join confirms what was waiting and a resumed join applies the
  one-or-several rule (9.4);
- every prompt the human types is captured by the hook or its loss is reported
  to the model in the host's context field (7.1);
- delegation depth, human-rooted authority, and human-only delivery match
  sections 7.5, 8.3, and 12; and
- every applicable acceptance scenario of section 14 passes against the real
  hosts with the operator present, or carries an explicitly accepted
  exception, and the outcome of each is recorded.

